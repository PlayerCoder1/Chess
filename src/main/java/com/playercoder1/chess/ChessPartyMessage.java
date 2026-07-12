package com.playercoder1.chess;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Versioned wire message for private chess rooms. Public fields are intentional:
 * RuneLite's party client serializes message subclasses with Gson.
 */
public final class ChessPartyMessage extends PartyMemberMessage
{
    public static final String OPEN = "OPEN";
    public static final String JOIN = "JOIN";
    public static final String ACCEPT = "ACCEPT";
    public static final String REJECT = "REJECT";
    public static final String MOVE = "MOVE";
    public static final String STATE = "STATE";
    public static final String DRAW = "DRAW";
    public static final String RESIGN = "RESIGN";
    public static final String REMATCH_REQUEST = "REMATCH_REQUEST";
    public static final String REMATCH_ACCEPT = "REMATCH_ACCEPT";
    public static final String SYNC_REQUEST = "SYNC_REQUEST";
    public static final String LEAVE = "LEAVE";

    public int protocolVersion;
    public String kind;
    public String invitationCode;
    public String matchToken;
    public long targetMemberId;
    public long hostMemberId;
    public long guestMemberId;
    public int initialMinutes;
    public int incrementSeconds;
    public int gameNumber;
    public int sequence;
    public String hostColor;
    public String uci;
    public String positionFen;
    public String lastMoveUci;
    public long whiteMillis;
    public long blackMillis;
    public String activeColor;
    public boolean clockRunning;
    public String gameStatus;
    public String drawOfferedBy;
    public long rematchRequestedBy;
    public String notice;
    public String reason;
}
