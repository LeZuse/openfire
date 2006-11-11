/**
 * $RCSfile$
 * $Revision: $
 * $Date: $
 *
 * Copyright (C) 2006 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution.
 */
package org.jivesoftware.wildfire.http;

import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.Log;
import org.jivesoftware.util.JiveConstants;
import org.jivesoftware.wildfire.SessionManager;
import org.jivesoftware.wildfire.StreamID;
import org.jivesoftware.wildfire.SessionPacketRouter;
import org.jivesoftware.wildfire.multiplex.UnknownStanzaException;
import org.jivesoftware.wildfire.auth.UnauthorizedException;
import org.dom4j.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;

/**
 * Manages sessions for all users connecting to Wildfire using the HTTP binding protocal,
 * <a href="http://www.xmpp.org/extensions/xep-0124.html">XEP-0124</a>.
 */
public class HttpSessionManager {

    /**
     * Milliseconds a connection has to be idle to be closed. Default is 30 minutes. Sending
     * stanzas to the client is not considered as activity. We are only considering the connection
     * active when the client sends some data or hearbeats (i.e. whitespaces) to the server.
     * The reason for this is that sending data will fail if the connection is closed. And if
     * the thread is blocked while sending data (because the socket is closed) then the clean up
     * thread will close the socket anyway.
     */
    private static int inactivityTimeout;

    /**
     * The connection manager MAY limit the number of simultaneous requests the client makes with
     * the 'requests' attribute. The RECOMMENDED value is "2". Servers that only support polling
     * behavior MUST prevent clients from making simultaneous requests by setting the 'requests'
     * attribute to a value of "1" (however, polling is NOT RECOMMENDED). In any case, clients MUST
     * NOT make more simultaneous requests than specified by the connection manager.
     */
    private static int maxRequests;

    /**
     * The connection manager SHOULD include two additional attributes in the session creation
     * response element, specifying the shortest allowable polling interval and the longest
     * allowable inactivity period (both in seconds). Communication of these parameters enables
     * the client to engage in appropriate behavior (e.g., not sending empty request elements more
     * often than desired, and ensuring that the periods with no requests pending are
     * never too long).
     */
    private static int pollingInterval;

    /**
     * Specifies the longest time (in seconds) that the connection manager is allowed to wait before
     * responding to any request during the session. This enables the client to prevent its TCP
     * connection from expiring due to inactivity, as well as to limit the delay before it
     * discovers any network failure.
     */
    private static int maxWait;

    private SessionManager sessionManager;
    private Map<String, HttpSession> sessionMap = new ConcurrentHashMap<String, HttpSession>();
    private Timer inactivityTimer;
    private TimerTask inactivityThread;

    static {
        // Set the default read idle timeout. If none was set then assume 30 minutes
        inactivityTimeout = JiveGlobals.getIntProperty("xmpp.httpbind.client.idle", 30);
        maxRequests = JiveGlobals.getIntProperty("xmpp.httpbind.client.requests.max", 2);
        pollingInterval = JiveGlobals.getIntProperty("xmpp.httpbind.client.requests.polling", 5);
        maxWait = JiveGlobals.getIntProperty("xmpp.httpbind.client.requests.wait",
                Integer.MAX_VALUE);
    }

    public HttpSessionManager() {
        this.sessionManager = SessionManager.getInstance();
    }

    public void start() {
        inactivityThread = new HttpSessionReaper();
        inactivityTimer = new Timer("HttpSession Inactivity Timer");
        inactivityTimer.schedule(inactivityThread, 30 * JiveConstants.SECOND,
                30 * JiveConstants.SECOND);
    }

    public void stop() {
        inactivityTimer.cancel();
        inactivityTimer = null;
        inactivityThread = null;
        for(HttpSession session : sessionMap.values()) {
            session.close();
        }
        sessionMap.clear();
    }

    /**
     * Returns the session related to a stream id.
     *
     * @param streamID the stream id to retrieve the session.
     * @return the session related to the provided stream id.
     */
    public HttpSession getSession(String streamID) {
        return sessionMap.get(streamID);
    }

    /**
     * Creates an HTTP binding session which will allow a user to exchnage packets with Wildfire.
     *
     * @param address the internet address that was used to bind to Wildfie.
     * @param rootNode the body element that was sent containing the request for a new session.
     * @param connection the HTTP connection object which abstracts the individual connections
     * to Wildfire over the HTTP binding protocol. The initial session creation response is
     * returned to this connection.
     * @return the created HTTP session.
     * @throws UnauthorizedException if the Wildfire server is currently in an uninitialized state.
     * Either shutting down or starting up.
     * @throws HttpBindException when there is an internal server error related to the creation of
     * the initial session creation response.
     */
    public HttpSession createSession(InetAddress address, Element rootNode,
            HttpConnection connection)
            throws UnauthorizedException, HttpBindException
    {
        // TODO Check if IP address is allowed to connect to the server

        // Default language is English ("en").
        String language = rootNode.attributeValue("xml:lang");
        if(language == null || "".equals(language)) {
            language = "en";
        }

        int wait = getIntAttribute(rootNode.attributeValue("wait"), 60);
        int hold = getIntAttribute(rootNode.attributeValue("hold"), 1);

        HttpSession session = createSession(connection.getRequestId(), address);
        session.setWait(Math.min(wait, maxWait));
        session.setHold(hold);
        session.setSecure(connection.isSecure());
        session.setMaxPollingInterval(pollingInterval);
        session.setInactivityTimeout(inactivityTimeout);
        // Store language and version information in the connection.
        session.setLanaguage(language);
        try {
            connection.deliverBody(createSessionCreationResponse(session));
        }
        catch (HttpConnectionClosedException e) {
            /* This won't happen here. */
        }
        catch (DocumentException e) {
            Log.error("Error creating document", e);
            throw new HttpBindException("Internal server error", true, 500);
        }
        return session;
    }

    private HttpSession createSession(long rid, InetAddress address) throws UnauthorizedException {
        // Create a ClientSession for this user.
        StreamID streamID = SessionManager.getInstance().nextStreamID();
        // Send to the server that a new client session has been created
        HttpSession session = sessionManager.createClientHttpSession(rid, address, streamID);
        // Register that the new session is associated with the specified stream ID
        sessionMap.put(streamID.getID(), session);
        session.addSessionCloseListener(new SessionListener() {
            public void connectionOpened(HttpSession session, HttpConnection connection) {
            }

            public void connectionClosed(HttpSession session, HttpConnection connection) {
            }

            public void sessionClosed(HttpSession session) {
                sessionMap.remove(session.getStreamID().getID());
                sessionManager.removeSession(session);
            }
        });
        return session;
    }

    private static int getIntAttribute(String value, int defaultValue) {
        if(value == null || "".equals(value)) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(value);
        }
        catch (Exception ex) {
            return defaultValue;
        }
    }

    private String createSessionCreationResponse(HttpSession session) throws DocumentException {
        Element response = DocumentHelper.createElement("body");
        response.addNamespace("", "http://jabber.org/protocol/httpbind");
        response.addNamespace("stream", "http://etherx.jabber.org/streams");
        response.addAttribute("authid", session.getStreamID().getID());
        response.addAttribute("sid", session.getStreamID().getID());
        response.addAttribute("secure", Boolean.TRUE.toString());
        response.addAttribute("requests", String.valueOf(maxRequests));
        response.addAttribute("inactivity", String.valueOf(session.getInactivityTimeout()));
        response.addAttribute("polling", String.valueOf(pollingInterval));
        response.addAttribute("wait", String.valueOf(session.getWait()));

        Element features = response.addElement("stream:features");
        for(Element feature : session.getAvailableStreamFeaturesElements()) {
            features.add(feature);
        }

        return response.asXML();
    }

    /**
     * Forwards a client request, which is related to a session, to the server. A connection is
     * created and queued up in the provided session. When a connection reaches the top of a queue
     * any pending packets bound for the client will be forwarded to the client through the
     * connection.
     *
     * @param rid the unique, sequential, requestID sent from the client.
     * @param session the HTTP session of the client that made the request.
     * @param isSecure true if the request was made over a secure channel, HTTPS, and false if it
     * was not.
     * @param rootNode the XML body of the request.
     * @return the created HTTP connection.
     * @throws HttpBindException for several reasons: if the encoding inside of an auth packet is
     * not recognized by the server, or if the packet type is not recognized.
     * @throws HttpConnectionClosedException if the session is no longer available.
     */
    public HttpConnection forwardRequest(long rid, HttpSession session, boolean isSecure,
                                         Element rootNode) throws HttpBindException,
            HttpConnectionClosedException
    {
        //noinspection unchecked
        List<Element> elements = rootNode.elements();
        boolean isPoll = elements.size() <= 0;
        HttpConnection connection = session.createConnection(rid, isPoll, isSecure);
        SessionPacketRouter router = new SessionPacketRouter(session);

        for (Element packet : elements) {
            try {
                router.route(packet);
                session.incrementClientPacketCount();
            }
            catch (UnsupportedEncodingException e) {
                throw new HttpBindException("Bad auth request, unknown encoding", true, 400);
            }
            catch (UnknownStanzaException e) {
                throw new HttpBindException("Unknown packet type.", false, 400);
            }
        }

        return connection;
    }

    private class HttpSessionReaper extends TimerTask {

        public void run() {
            for(HttpSession session : sessionMap.values()) {
                long lastActive = (System.currentTimeMillis() - session.getLastActivity()) / 1000;
                if(lastActive > session.getInactivityTimeout()) {
                    session.close();
                }
            }
        }
    }
}
