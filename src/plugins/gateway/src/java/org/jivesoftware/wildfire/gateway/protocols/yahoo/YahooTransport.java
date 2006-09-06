/**
 * $Revision$
 * $Date$
 *
 * Copyright (C) 2006 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution.
 */

package org.jivesoftware.wildfire.gateway.protocols.yahoo;

import org.jivesoftware.util.Log;
import org.jivesoftware.wildfire.gateway.BaseTransport;
import org.jivesoftware.wildfire.gateway.PresenceType;
import org.jivesoftware.wildfire.gateway.Registration;
import org.jivesoftware.wildfire.gateway.TransportSession;
import org.xmpp.packet.JID;
import org.xmpp.packet.Presence;
import ymsg.network.StatusConstants;

/**
 * Yahoo Transport Interface.
 *
 * This handles the bulk of the XMPP work via BaseTransport and provides
 * some gateway specific interactions.
 *
 * @author Daniel Henninger
 */
public class YahooTransport extends BaseTransport {

    /**
     * @see org.jivesoftware.wildfire.gateway.BaseTransport#getTerminologyUsername()
     */
    public String getTerminologyUsername() {
        return "Yahoo! ID";
    }

    /**
     * @see org.jivesoftware.wildfire.gateway.BaseTransport#getTerminologyPassword()
     */
    public String getTerminologyPassword() {
        return "Password";
    }

    /**
     * @see org.jivesoftware.wildfire.gateway.BaseTransport#getTerminologyRegistration()
     */
    public String getTerminologyRegistration() {
        return "Please enter your Yahoo! ID and password.";
    }

    /**
     * Handles creating a Yahoo session and triggering a login.
     *
     * @param registration Registration information to be used to log in.
     * @param jid JID that is logged into the transport.
     * @param presenceType Type of presence.
     * @param verboseStatus Longer status description.
     */
    public TransportSession registrationLoggedIn(Registration registration, JID jid, PresenceType presenceType, String verboseStatus, Integer priority) {
        Log.debug("Logging in to Yahoo gateway.");
        TransportSession session = new YahooSession(registration, jid, this, priority);
//        Thread sessionThread = new Thread(session);
//        sessionThread.start();
        ((YahooSession)session).logIn(presenceType, verboseStatus);
        return session;
    }

    /**
     * Handles logging out of a Yahoo session.
     *
     * @param session The session to be disconnected.
     */
    public void registrationLoggedOut(TransportSession session) {
        Log.debug("Logging out of Yahoo gateway.");
        ((YahooSession)session).logOut();
//        session.sessionDone();
    }

    /**
     * Converts a jabber status to an Yahoo status.
     *
     * @param jabStatus Jabber presence type.
     */
    public long convertJabStatusToYahoo(PresenceType jabStatus) {
        if (jabStatus == PresenceType.available) {
            return StatusConstants.STATUS_AVAILABLE;
        }
        else if (jabStatus == PresenceType.away) {
            return StatusConstants.STATUS_BRB;
        }
        else if (jabStatus == PresenceType.xa) {
            return StatusConstants.STATUS_STEPPEDOUT;
        }
        else if (jabStatus == PresenceType.dnd) {
            return StatusConstants.STATUS_BUSY;
        }
        else if (jabStatus == PresenceType.chat) {
            return StatusConstants.STATUS_AVAILABLE;
        }
        else if (jabStatus == PresenceType.unavailable) {
            return StatusConstants.STATUS_OFFLINE;
        }
        else {
            return StatusConstants.STATUS_AVAILABLE;
        }
    }

    /**
     * Sets up a presence packet according to Yahoo status.
     *
     * @param yahooStatus Yahoo StatusConstants constant.
     */
    public void setUpPresencePacket(Presence packet, long yahooStatus) {
        if (yahooStatus == StatusConstants.STATUS_AVAILABLE) {
            // We're good, leave the type as blank for available.
        }
        else if (yahooStatus == StatusConstants.STATUS_BRB) {
            packet.setShow(Presence.Show.away);
        }
        else if (yahooStatus == StatusConstants.STATUS_BUSY) {
            packet.setShow(Presence.Show.dnd);
        }
        else if (yahooStatus == StatusConstants.STATUS_IDLE) {
            packet.setShow(Presence.Show.away);
        }
        else if (yahooStatus == StatusConstants.STATUS_OFFLINE) {
            packet.setType(Presence.Type.unavailable);
        }
        else if (yahooStatus == StatusConstants.STATUS_NOTATDESK) {
            packet.setShow(Presence.Show.away);
        }
        else if (yahooStatus == StatusConstants.STATUS_NOTINOFFICE) {
            packet.setShow(Presence.Show.away);
        }
        else if (yahooStatus == StatusConstants.STATUS_ONPHONE) {
            packet.setShow(Presence.Show.away);
        }
        else if (yahooStatus == StatusConstants.STATUS_ONVACATION) {
            packet.setShow(Presence.Show.xa);
        }
        else if (yahooStatus == StatusConstants.STATUS_OUTTOLUNCH) {
            packet.setShow(Presence.Show.xa);
        }
        else if (yahooStatus == StatusConstants.STATUS_STEPPEDOUT) {
            packet.setShow(Presence.Show.away);
        }
        else {
            // Not something we handle, we're going to ignore it.
        }
    }
}
