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

package org.jivesoftware.messenger.muc.spi;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.dom4j.Element;
import org.jivesoftware.messenger.PacketRouter;
import org.jivesoftware.messenger.muc.ConflictException;
import org.jivesoftware.messenger.muc.ForbiddenException;
import org.jivesoftware.messenger.muc.MUCRole;
import org.jivesoftware.messenger.muc.NotAllowedException;
import org.jivesoftware.messenger.user.UserNotFoundException;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.Presence;

/**
 * A handler for the IQ packet with namespace http://jabber.org/protocol/muc#admin. This kind of 
 * packets are usually sent by room admins. So this handler provides the necessary functionality
 * to support administrator requirements such as: managing room members/outcasts/etc., kicking 
 * occupants and banning users.
 *
 * @author Gaston Dombiak
 */
public class IQAdminHandler {
    private MUCRoomImpl room;

    private PacketRouter router;

    public IQAdminHandler(MUCRoomImpl chatroom, PacketRouter packetRouter) {
        this.room = chatroom;
        this.router = packetRouter;
    }

    /**
     * Handles the IQ packet sent by an owner or admin of the room. Possible actions are:
     * <ul>
     * <li>Return the list of participants</li>
     * <li>Return the list of moderators</li>
     * <li>Return the list of members</li>
     * <li>Return the list of outcasts</li>
     * <li>Change user's affiliation to member</li>
     * <li>Change user's affiliation to outcast</li>
     * <li>Change user's affiliation to none</li>
     * <li>Change occupant's affiliation to moderator</li>
     * <li>Change occupant's affiliation to participant</li>
     * <li>Change occupant's affiliation to visitor</li>
     * <li>Kick occupants from the room</li>
     * </ul>
     *
     * @param packet the IQ packet sent by an owner or admin of the room.
     * @param role the role of the user that sent the request packet.
     * @throws ForbiddenException If the user is not allowed to perform his request.
     * @throws ConflictException If the desired room nickname is already reserved for the room or
     *                           if the room was going to lose all of its owners.
     * @throws NotAllowedException Thrown if trying to ban an owner or an administrator.
     */
    public void handleIQ(IQ packet, MUCRole role) throws ForbiddenException, ConflictException,
            NotAllowedException {
        IQ reply = IQ.createResultIQ(packet);
        Element element = packet.getChildElement();

        // Analyze the action to perform based on the included element
        List itemsList = element.elements("item");
        if (!itemsList.isEmpty()) {
            handleItemsElement(role, itemsList, reply);
        }
        else {
            // An unknown and possibly incorrect element was included in the query
            // element so answer a BAD_REQUEST error
            reply.setError(PacketError.Condition.bad_request);
        }
        if (reply.getTo() != null) {
            // Send a reply only if the sender of the original packet was from a real JID. (i.e. not
            // a packet generated locally)
            router.route(reply);
        }
    }

    /**
     * Handles packets that includes item elements. Depending on the item's attributes the
     * interpretation of the request may differ. For example, an item that only contains the
     * "affiliation" attribute is requesting the list of participants or members. Whilst if the item
     * contains the affiliation together with a jid means that the client is changing the
     * affiliation of the requested jid.
     *
     * @param senderRole the role of the user that sent the request packet.
     * @param itemsList  the list of items sent by the client.
     * @param reply      the iq packet that will be sent back as a reply to the client's request.
     * @throws ForbiddenException If the user is not allowed to perform his request.
     * @throws ConflictException If the desired room nickname is already reserved for the room or
     *                           if the room was going to lose all of its owners.
     * @throws NotAllowedException Thrown if trying to ban an owner or an administrator.
     */
    private void handleItemsElement(MUCRole senderRole, List itemsList, IQ reply)
            throws ForbiddenException, ConflictException, NotAllowedException {
        Element item;
        String affiliation = null;
        String roleAttribute = null;
        boolean hasJID = ((Element)itemsList.get(0)).attributeValue("jid") != null;
        boolean hasNick = ((Element)itemsList.get(0)).attributeValue("nick") != null;
        // Check if the client is requesting or changing the list of moderators/members/etc.
        if (!hasJID && !hasNick) {
            // The client is requesting the list of moderators/members/participants/outcasts
            for (Iterator items = itemsList.iterator(); items.hasNext();) {
                item = (Element)items.next();
                affiliation = item.attributeValue("affiliation");
                roleAttribute = item.attributeValue("role");
                // Create the result that will hold an item for each
                // moderator/member/participant/outcast
                Element result = reply.setChildElement("query", "http://jabber.org/protocol/muc#admin");

                Element metaData;
                if ("outcast".equals(affiliation)) {
                    // The client is requesting the list of outcasts
                    if (MUCRole.ADMINISTRATOR != senderRole.getAffiliation()
                            && MUCRole.OWNER != senderRole.getAffiliation()) {
                        throw new ForbiddenException();
                    }
                    for (String jid : room.getOutcasts()) {
                        metaData = result.addElement("item", "http://jabber.org/protocol/muc#admin");
                        metaData.addAttribute("affiliation", "outcast");
                        metaData.addAttribute("jid", jid);
                    }

                }
                else if ("member".equals(affiliation)) {
                    // The client is requesting the list of members
                    // In a members-only room members can get the list of members
                    if (!room.isInvitationRequiredToEnter()
                            && MUCRole.ADMINISTRATOR != senderRole.getAffiliation()
                            && MUCRole.OWNER != senderRole.getAffiliation()) {
                        throw new ForbiddenException();
                    }
                    for (String jid : room.getMembers()) {
                        metaData = result.addElement("item", "http://jabber.org/protocol/muc#admin");
                        metaData.addAttribute("affiliation", "member");
                        metaData.addAttribute("jid", jid);
                        try {
                            List<MUCRole> roles = room.getOccupantsByBareJID(jid);
                            MUCRole role = roles.get(0);
                            metaData.addAttribute("role", role.getRoleAsString());
                            metaData.addAttribute("nick", role.getNickname());
                        }
                        catch (UserNotFoundException e) {
                            // Do nothing
                        }
                    }
                }
                else if ("moderator".equals(roleAttribute)) {
                    // The client is requesting the list of moderators
                    if (MUCRole.ADMINISTRATOR != senderRole.getAffiliation()
                            && MUCRole.OWNER != senderRole.getAffiliation()) {
                        throw new ForbiddenException();
                    }
                    for (MUCRole role : room.getModerators()) {
                        metaData = result.addElement("item", "http://jabber.org/protocol/muc#admin");
                        metaData.addAttribute("role", "moderator");
                        metaData.addAttribute("jid", role.getChatUser().getAddress().toBareJID());
                        metaData.addAttribute("nick", role.getNickname());
                        metaData.addAttribute("affiliation", role.getAffiliationAsString());
                    }
                }
                else if ("participant".equals(roleAttribute)) {
                    // The client is requesting the list of participants
                    if (MUCRole.MODERATOR != senderRole.getRole()) {
                        throw new ForbiddenException();
                    }
                    for (MUCRole role : room.getParticipants()) {
                        metaData = result.addElement("item", "http://jabber.org/protocol/muc#admin");
                        metaData.addAttribute("role", "participant");
                        metaData.addAttribute("jid", role.getChatUser().getAddress().toBareJID());
                        metaData.addAttribute("nick", role.getNickname());
                        metaData.addAttribute("affiliation", role.getAffiliationAsString());
                    }
                }
                else {
                    reply.setError(PacketError.Condition.bad_request);
                }
            }
        }
        else {
            // The client is modifying the list of moderators/members/participants/outcasts
            JID jid = null;
            String nick;
            String target = null;
            boolean hasAffiliation = ((Element) itemsList.get(0)).attributeValue("affiliation") !=
                    null;

            // Keep a registry of the updated presences
            List<Presence> presences = new ArrayList<Presence>(itemsList.size());

            // Collect the new affiliations or roles for the specified jids
            for (Iterator items = itemsList.iterator(); items.hasNext();) {
                try {
                    item = (Element)items.next();
                    target = (hasAffiliation ? item.attributeValue("affiliation") : item
                            .attributeValue("role"));
                    // jid could be of the form "full JID" or "bare JID" depending if we are
                    // going to change a role or an affiliation
                    if (hasJID) {
                        jid = new JID(item.attributeValue("jid"));
                    }
                    else {
                        // Get the JID based on the requested nick
                        nick = item.attributeValue("nick");
                        jid = room.getOccupant(nick).getChatUser().getAddress();
                    }

                    room.lock.writeLock().lock();
                    try {
                        if ("moderator".equals(target)) {
                            // Add the user as a moderator of the room based on the full JID
                            presences.add(room.addModerator(jid, senderRole));
                        }
                        else if ("participant".equals(target)) {
                            // Add the user as a participant of the room based on the full JID
                            presences.add(room.addParticipant(jid,
                                    item.elementTextTrim("reason"),
                                    senderRole));
                        }
                        else if ("visitor".equals(target)) {
                            // Add the user as a visitor of the room based on the full JID
                            presences.add(room.addVisitor(jid, senderRole));
                        }
                        else if ("member".equals(target)) {
                            // Add the user as a member of the room based on the bare JID
                            boolean hadAffiliation = room.getAffiliation(jid.toBareJID()) != MUCRole.NONE;
                            presences.addAll(room.addMember(jid.toBareJID(), null, senderRole));
                            // If the user had an affiliation don't send an invitation. Otherwise
                            // send an invitation if the room is members-only
                            if (!hadAffiliation && room.isInvitationRequiredToEnter()) {
                                room.sendInvitation(jid, null, senderRole);
                            }
                        }
                        else if ("outcast".equals(target)) {
                            // Add the user as an outcast of the room based on the bare JID
                            presences.addAll(room.addOutcast(jid.toBareJID(), item.elementTextTrim("reason"), senderRole));
                        }
                        else if ("none".equals(target)) {
                            if (hasAffiliation) {
                                // Set that this jid has a NONE affiliation based on the bare JID
                                presences.addAll(room.addNone(jid.toBareJID(), senderRole));
                            }
                            else {
                                // Kick the user from the room
                                if (MUCRole.MODERATOR != senderRole.getRole()) {
                                    throw new ForbiddenException();
                                }
                                presences.add(room.kickOccupant(jid,
                                        senderRole.getChatUser().getAddress(),
                                        item.elementTextTrim("reason")));
                            }
                        }
                        else {
                            reply.setError(PacketError.Condition.bad_request);
                        }
                    }
                    finally {
                        room.lock.writeLock().unlock();
                    }
                }
                catch (UserNotFoundException e) {
                    // Do nothing
                }
            }

            // Send the updated presences to the room occupants
            for (Presence presence : presences) {
                room.send(presence);
            }
        }
    }
}