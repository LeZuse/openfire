/**
 * $RCSfile$
 * $Revision$
 * $Date$
 *
 * Copyright (C) 2004 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution.
 */

package org.jivesoftware.messenger.transport;

import org.jivesoftware.messenger.container.TrackInfo;
import org.jivesoftware.messenger.container.BasicModule;
import org.jivesoftware.util.LocaleUtils;
import org.jivesoftware.util.Log;
import org.jivesoftware.messenger.*;
import org.jivesoftware.messenger.auth.UnauthorizedException;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes packets to the appropriate transport gateway or drops the packet.
 *
 * @author Iain Shigeoka
 */
public class TransportHandler extends BasicModule implements ChannelHandler {

    private Map<String, Channel> transports = new ConcurrentHashMap<String, Channel>();

    public TransportHandler() {
        super("Transport handler");
    }

    public void addTransport(Channel transport) {
        transports.put(transport.getName(), transport);
    }

    public void process(Packet packet) throws UnauthorizedException, PacketException {
        boolean handled = false;
        String host = packet.getTo().getDomain();
        for (Channel channel : transports.values()) {
            if (channel.getName().equalsIgnoreCase(host)) {
                channel.add(packet);
                handled = true;
            }
        }
        if (!handled) {
            JID recipient = packet.getTo();
            JID sender = packet.getFrom();
            packet.setError(PacketError.Condition.remote_server_timeout);
            packet.setFrom(recipient);
            packet.setTo(sender);
            try {
                deliverer.deliver(packet);
            }
            catch (UnauthorizedException e) {
                Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
            }
            catch (PacketException e) {
                Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
            }
        }
    }

    public PacketDeliverer deliverer;

    protected TrackInfo getTrackInfo() {
        TrackInfo trackInfo = new TrackInfo();
        trackInfo.getTrackerClasses().put(PacketDeliverer.class, "deliverer");
        return trackInfo;
    }
}