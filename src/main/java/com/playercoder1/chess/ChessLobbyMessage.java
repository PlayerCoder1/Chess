package com.playercoder1.chess;

import net.runelite.client.party.messages.PartyMemberMessage;


public final class ChessLobbyMessage extends PartyMemberMessage
{
    public static final String QUEUE = "QUEUE";
    public static final String OFFER = "OFFER";
    public static final String ACCEPT = "ACCEPT";
    public static final String READY = "READY";
    public static final String CANCEL = "CANCEL";

    public int protocolVersion;
    public String kind;
    public long targetMemberId;
    public String queueTicket;
    public String targetQueueTicket;
    public int initialMinutes;
    public int incrementSeconds;
    public String offerToken;
    public String matchCode;
}
