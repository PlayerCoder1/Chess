package com.playercoder1.chess;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;

@Singleton
public final class ChessMultiplayerService
{
    public enum Mode
    {
        LOCAL,
        HOST_WAITING,
        HOST_PLAYING,
        GUEST_JOINING,
        GUEST_PLAYING
    }

    @FunctionalInterface
    public interface Listener
    {
        void onSessionChanged();
    }

    private static final int PROTOCOL_VERSION = 2;
    private static final String PARTY_PREFIX = "runelite-chess-";
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long JOIN_RETRY_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(2);
    private static final long OPEN_HEARTBEAT_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final long STATE_HEARTBEAT_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(15);
    private static final int MAX_FEN_LENGTH = 128;
    private static final int MAX_NOTICE_LENGTH = 240;
    private static final int MAX_REASON_LENGTH = 160;
    private static final int MAX_UCI_LENGTH = 5;

    private final PartyService partyService;
    private final EventBus eventBus;
    private final LocalChessController controller;
    private final ChessBotService botService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<Long, Long> lastJoinRequestByMember = new HashMap<>();

    private Mode mode = Mode.LOCAL;
    private String invitationCode;
    private String matchToken;
    private String previousPartyPassphrase;
    private long hostMemberId;
    private long guestMemberId;
    private ChessColor hostColor = ChessColor.WHITE;
    private int initialMinutes = 10;
    private int incrementSeconds;
    private int gameNumber = 1;
    private int sequence;
    private long rematchRequestedBy;
    private long lastJoinSentMillis;
    private long lastOpenBroadcastMillis;
    private long lastStateBroadcastMillis;
    private boolean opponentConnected;
    private boolean awaitingHostMoveConfirmation;
    private boolean started;

    @Inject
    public ChessMultiplayerService(
        PartyService partyService,
        EventBus eventBus,
        LocalChessController controller,
        ChessBotService botService)
    {
        this.partyService = partyService;
        this.eventBus = eventBus;
        this.controller = controller;
        this.botService = botService;
    }

    public void start()
    {
        if (!started)
        {
            started = true;
            eventBus.register(this);
        }
    }

    public void stop()
    {
        if (!started)
        {
            return;
        }
        started = false;
        eventBus.unregister(this);
        leaveMatchInternal(true, true);
    }

    public Mode getMode()
    {
        return mode;
    }

    public boolean isOnline()
    {
        return mode != Mode.LOCAL;
    }

    public boolean isHost()
    {
        return mode == Mode.HOST_WAITING || mode == Mode.HOST_PLAYING;
    }

    public boolean isPlayingOnline()
    {
        return mode == Mode.HOST_PLAYING || mode == Mode.GUEST_PLAYING;
    }

    public String getInvitationCode()
    {
        return invitationCode;
    }

    public int getInitialMinutes()
    {
        return initialMinutes;
    }

    public int getIncrementSeconds()
    {
        return incrementSeconds;
    }

    public ChessColor getLocalColor()
    {
        if (mode == Mode.HOST_PLAYING)
        {
            return hostColor;
        }
        if (mode == Mode.GUEST_PLAYING)
        {
            return hostColor.opposite();
        }
        return null;
    }

    public boolean isOpponentConnected()
    {
        return opponentConnected;
    }

    public long getRematchRequestedBy()
    {
        return rematchRequestedBy;
    }

    public boolean isRematchRequestedByLocalPlayer()
    {
        long local = localMemberId();
        return local != 0L && rematchRequestedBy == local;
    }

    public boolean isRematchRequestedByOpponent()
    {
        return rematchRequestedBy != 0L && !isRematchRequestedByLocalPlayer();
    }

    public void addListener(Listener listener)
    {
        if (listener != null && !listeners.contains(listener))
        {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener)
    {
        listeners.remove(listener);
    }

    public void createPrivateMatch(int minutes, int increment)
    {
        validateTimeControl(minutes, increment);
        botService.stopGame();
        if (isOnline())
        {
            leaveMatchInternal(true, false);
        }

        previousPartyPassphrase = partyService.getPartyPassphrase();
        invitationCode = generateCode();
        matchToken = UUID.randomUUID().toString();
        initialMinutes = minutes;
        incrementSeconds = increment;
        gameNumber = 1;
        sequence = 0;
        hostMemberId = 0L;
        guestMemberId = 0L;
        rematchRequestedBy = 0L;
        opponentConnected = false;
        awaitingHostMoveConfirmation = false;
        lastJoinSentMillis = 0L;
        lastOpenBroadcastMillis = 0L;
        lastStateBroadcastMillis = 0L;
        lastJoinRequestByMember.clear();
        hostColor = secureRandom.nextBoolean() ? ChessColor.WHITE : ChessColor.BLACK;
        mode = Mode.HOST_WAITING;
        controller.configureTimeControl(minutes, increment);
        controller.newGame();
        controller.setNotice("Private match created. Share code " + invitationCode + ".");
        partyService.changeParty(PARTY_PREFIX + invitationCode);
        notifyListeners();
    }

    public void joinPrivateMatch(String code)
    {
        botService.stopGame();
        String normalized = normalizeCode(code);
        if (!isValidInvitationCode(normalized))
        {
            throw new IllegalArgumentException("Enter the eight-character invitation code.");
        }

        if (isOnline())
        {
            leaveMatchInternal(true, false);
        }
        previousPartyPassphrase = partyService.getPartyPassphrase();
        invitationCode = normalized;
        matchToken = null;
        hostMemberId = 0L;
        guestMemberId = 0L;
        rematchRequestedBy = 0L;
        opponentConnected = false;
        awaitingHostMoveConfirmation = false;
        lastJoinSentMillis = 0L;
        lastOpenBroadcastMillis = 0L;
        lastStateBroadcastMillis = 0L;
        lastJoinRequestByMember.clear();
        mode = Mode.GUEST_JOINING;
        controller.setNotice("Joining private match " + invitationCode + "…");
        partyService.changeParty(PARTY_PREFIX + invitationCode);
        notifyListeners();
    }

    public void leaveMatch()
    {
        leaveMatchInternal(true, false);
    }

    public boolean canSelectPiece(ChessColor pieceColor)
    {
        if (pieceColor == null || controller.getGame().getStatus().isFinished())
        {
            return false;
        }
        if (!isPlayingOnline())
        {
            return pieceColor == controller.getGame().getSideToMove()
                && botService.canHumanSelect(pieceColor);
        }
        ChessColor localColor = getLocalColor();
        return localColor != null
            && pieceColor == localColor
            && controller.getGame().getSideToMove() == localColor
            && opponentConnected
            && !awaitingHostMoveConfirmation;
    }

    public boolean canLocalPlayerAct()
    {
        if (!isPlayingOnline())
        {
            return true;
        }
        return opponentConnected && getLocalColor() != null && !awaitingHostMoveConfirmation;
    }

    public boolean playMove(Move move)
    {
        if (move == null)
        {
            return false;
        }
        if (!isPlayingOnline())
        {
            if (botService.isActive()
                && controller.getGame().getSideToMove() != botService.getHumanColor())
            {
                controller.setNotice(botService.getDifficulty().getDisplayName() + " is thinking.");
                return false;
            }
            return controller.playMove(move);
        }

        ChessColor localColor = getLocalColor();
        if (!opponentConnected || awaitingHostMoveConfirmation
            || localColor == null || controller.getGame().getSideToMove() != localColor)
        {
            controller.setNotice("Wait for your turn and for the opponent to reconnect.");
            return false;
        }

        if (isHost())
        {
            boolean played = controller.playMove(move);
            if (played)
            {
                sequence++;
                rematchRequestedBy = 0L;
                broadcastState();
            }
            return played;
        }

        ChessPartyMessage message = baseMessage(ChessPartyMessage.MOVE);
        message.targetMemberId = hostMemberId;
        message.sequence = sequence;
        message.gameNumber = gameNumber;
        message.uci = move.toUci();
        awaitingHostMoveConfirmation = true;
        partyService.send(message);
        controller.setNotice("Move sent — waiting for host confirmation.");
        notifyListeners();
        return true;
    }

    public void offerAcceptOrCancelDraw()
    {
        if (!isPlayingOnline())
        {
            if (botService.isActive())
            {
                controller.setNotice("Computer opponents do not accept draw offers.");
                return;
            }
            controller.offerAcceptOrCancelDraw();
            return;
        }
        if (!canLocalPlayerAct() || controller.getGame().getStatus().isFinished())
        {
            return;
        }

        ChessColor actor = getLocalColor();
        if (isHost())
        {
            controller.handleDrawAction(actor);
            sequence++;
            broadcastState();
        }
        else
        {
            ChessPartyMessage message = baseMessage(ChessPartyMessage.DRAW);
            message.targetMemberId = hostMemberId;
            message.sequence = sequence;
            message.gameNumber = gameNumber;
            partyService.send(message);
        }
    }

    public void resign()
    {
        if (!isPlayingOnline())
        {
            if (botService.isActive())
            {
                controller.resign(botService.getHumanColor());
            }
            else
            {
                controller.resignCurrentPlayer();
            }
            return;
        }
        if (!canLocalPlayerAct() || controller.getGame().getStatus().isFinished())
        {
            return;
        }

        if (isHost())
        {
            controller.resign(getLocalColor());
            sequence++;
            broadcastState();
        }
        else
        {
            ChessPartyMessage message = baseMessage(ChessPartyMessage.RESIGN);
            message.targetMemberId = hostMemberId;
            message.sequence = sequence;
            message.gameNumber = gameNumber;
            partyService.send(message);
        }
    }

    public void requestOrAcceptRematch()
    {
        if (!isPlayingOnline() || !controller.getGame().getStatus().isFinished() || !opponentConnected)
        {
            return;
        }

        long local = localMemberId();
        if (local == 0L || rematchRequestedBy == local)
        {
            return;
        }

        if (isHost())
        {
            if (rematchRequestedBy == guestMemberId)
            {
                startRematch();
            }
            else
            {
                rematchRequestedBy = hostMemberId;
                broadcastState();
            }
            return;
        }

        ChessPartyMessage message = baseMessage(
            rematchRequestedBy == hostMemberId
                ? ChessPartyMessage.REMATCH_ACCEPT
                : ChessPartyMessage.REMATCH_REQUEST);
        message.targetMemberId = hostMemberId;
        message.gameNumber = gameNumber;
        message.sequence = sequence;
        partyService.send(message);
        if (rematchRequestedBy == 0L)
        {
            rematchRequestedBy = guestMemberId;
            notifyListeners();
        }
    }

    public void startLocalGame(int minutes, int increment)
    {
        validateTimeControl(minutes, increment);
        botService.stopGame();
        if (isOnline())
        {
            leaveMatchInternal(true, false);
        }
        controller.configureTimeControl(minutes, increment);
        controller.newGame();
    }

    public void startBotGame(
        BotDifficulty difficulty,
        ChessColor humanColor,
        int minutes,
        int increment)
    {
        validateTimeControl(minutes, increment);
        if (isOnline())
        {
            leaveMatchInternal(true, false);
        }
        botService.startGame(difficulty, humanColor, minutes, increment);
        notifyListeners();
    }

    public void tick()
    {
        long now = System.currentTimeMillis();
        switch (mode)
        {
            case LOCAL:
                controller.tick();
                break;
            case HOST_WAITING:
                if (now - lastOpenBroadcastMillis >= OPEN_HEARTBEAT_INTERVAL_MILLIS)
                {
                    sendOpen(0L);
                }
                break;
            case HOST_PLAYING:
                GameStatus before = controller.getGame().getStatus();
                controller.tick();
                if (before != controller.getGame().getStatus()
                    || now - lastStateBroadcastMillis >= STATE_HEARTBEAT_INTERVAL_MILLIS)
                {
                    broadcastState();
                }
                break;
            case GUEST_JOINING:
                sendJoinRequest();
                break;
            case GUEST_PLAYING:
                controller.tickDisplayOnly();
                break;
            default:
                break;
        }
    }

    @Subscribe
    public void onUserJoin(UserJoin event)
    {
        runOnSwingThread(() -> handleUserJoin(event));
    }

    @Subscribe
    public void onUserPart(UserPart event)
    {
        runOnSwingThread(() -> handleUserPart(event));
    }

    @Subscribe
    public void onChessPartyMessage(ChessPartyMessage message)
    {
        runOnSwingThread(() -> handlePartyMessage(message));
    }

    private void handleUserJoin(UserJoin event)
    {
        if (!isOnline() || event == null || event.getPartyId() != partyService.getPartyId())
        {
            return;
        }

        long local = localMemberId();
        if (isHost())
        {
            if (hostMemberId == 0L && local != 0L)
            {
                hostMemberId = local;
            }
            if (event.getMemberId() == guestMemberId && guestMemberId != 0L)
            {
                opponentConnected = true;
                sendAccept(guestMemberId);
                broadcastState();
            }
            else if (mode == Mode.HOST_WAITING)
            {
                sendOpen(event.getMemberId());
            }
        }
        else if (mode == Mode.GUEST_JOINING)
        {
            sendJoinRequest();
        }
        else if (mode == Mode.GUEST_PLAYING && event.getMemberId() == hostMemberId)
        {
            opponentConnected = true;
            sendSyncRequest();
            notifyListeners();
        }
    }

    private void handleUserPart(UserPart event)
    {
        if (!isOnline() || event == null)
        {
            return;
        }

        long opponent = isHost() ? guestMemberId : hostMemberId;
        if (opponent != 0L && event.getMemberId() == opponent)
        {
            opponentConnected = false;
            controller.setNotice("Opponent disconnected. Their seat remains locked while reconnecting.");
            notifyListeners();
        }
    }

    private void handlePartyMessage(ChessPartyMessage message)
    {
        if (!isOnline() || message == null || message.kind == null
            || message.protocolVersion != PROTOCOL_VERSION)
        {
            return;
        }

        long local = localMemberId();
        if (local != 0L && message.getMemberId() == local)
        {
            return;
        }
        if (message.targetMemberId != 0L && message.targetMemberId != local)
        {
            return;
        }

        switch (message.kind)
        {
            case ChessPartyMessage.OPEN:
                handleOpen(message);
                break;
            case ChessPartyMessage.JOIN:
                handleJoinRequest(message);
                break;
            case ChessPartyMessage.ACCEPT:
                handleAccept(message);
                break;
            case ChessPartyMessage.REJECT:
                handleReject(message);
                break;
            case ChessPartyMessage.MOVE:
                handleMoveRequest(message);
                break;
            case ChessPartyMessage.STATE:
                handleState(message);
                break;
            case ChessPartyMessage.DRAW:
                handleDrawRequest(message);
                break;
            case ChessPartyMessage.RESIGN:
                handleResignRequest(message);
                break;
            case ChessPartyMessage.REMATCH_REQUEST:
                handleRematchRequest(message);
                break;
            case ChessPartyMessage.REMATCH_ACCEPT:
                handleRematchAccept(message);
                break;
            case ChessPartyMessage.SYNC_REQUEST:
                if (isHost() && isAcceptedGuest(message) && sameMatch(message))
                {
                    opponentConnected = true;
                    broadcastState();
                }
                break;
            case ChessPartyMessage.LEAVE:
                if (sameMatch(message) && isOpponent(message.getMemberId()))
                {
                    opponentConnected = false;
                    awaitingHostMoveConfirmation = false;
                    controller.setNotice("Opponent closed the private match.");
                    notifyListeners();
                }
                break;
            default:
                break;
        }
    }

    private void handleOpen(ChessPartyMessage message)
    {
        if (mode != Mode.GUEST_JOINING || message.getMemberId() == 0L
            || !matchesInvitation(message) || !isSupportedTimeControl(message.initialMinutes, message.incrementSeconds))
        {
            return;
        }

        long announcedHost = message.hostMemberId == 0L
            ? message.getMemberId()
            : message.hostMemberId;
        if (announcedHost != message.getMemberId()
            || (hostMemberId != 0L && hostMemberId != announcedHost))
        {
            return;
        }

        hostMemberId = announcedHost;
        initialMinutes = message.initialMinutes;
        incrementSeconds = message.incrementSeconds;
        sendJoinRequest();
    }

    private void handleJoinRequest(ChessPartyMessage message)
    {
        if (!isHost() || message.getMemberId() == 0L || !matchesInvitation(message))
        {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastJoinRequestByMember.getOrDefault(message.getMemberId(), 0L);
        if (now - last < JOIN_RETRY_INTERVAL_MILLIS)
        {
            return;
        }
        lastJoinRequestByMember.put(message.getMemberId(), now);

        if (guestMemberId == 0L)
        {
            guestMemberId = message.getMemberId();
            opponentConnected = true;
            mode = Mode.HOST_PLAYING;
            controller.configureTimeControl(initialMinutes, incrementSeconds);
            controller.newGame();
            controller.setNotice("Private match connected. " + controller.describePosition());
            sendAccept(guestMemberId);
            broadcastState();
            notifyListeners();
        }
        else if (guestMemberId == message.getMemberId())
        {
            opponentConnected = true;
            sendAccept(guestMemberId);
            broadcastState();
            notifyListeners();
        }
        else
        {
            ChessPartyMessage rejection = baseMessage(ChessPartyMessage.REJECT);
            rejection.targetMemberId = message.getMemberId();
            rejection.reason = "This one-use invitation has already been consumed by two players.";
            partyService.send(rejection);
        }
    }

    private void handleAccept(ChessPartyMessage message)
    {
        if ((mode != Mode.GUEST_JOINING && mode != Mode.GUEST_PLAYING)
            || message.getMemberId() == 0L
            || !matchesInvitation(message)
            || !isValidMatchToken(message.matchToken)
            || !isSupportedTimeControl(message.initialMinutes, message.incrementSeconds)
            || message.gameNumber < 1
            || message.sequence < 0)
        {
            return;
        }

        long local = localMemberId();
        long announcedHost = message.hostMemberId == 0L
            ? message.getMemberId()
            : message.hostMemberId;
        if (local == 0L || message.guestMemberId != local
            || announcedHost != message.getMemberId()
            || (hostMemberId != 0L && hostMemberId != announcedHost))
        {
            return;
        }
        if (mode == Mode.GUEST_PLAYING
            && (!sameMatch(message)
                || message.gameNumber < gameNumber
                || (message.gameNumber == gameNumber && message.sequence < sequence)))
        {
            return;
        }

        ChessColor acceptedHostColor;
        try
        {
            acceptedHostColor = parseOptionalColor(message.hostColor);
        }
        catch (IllegalArgumentException ex)
        {
            return;
        }
        if (acceptedHostColor == null)
        {
            return;
        }

        boolean firstAcceptance = mode == Mode.GUEST_JOINING;
        hostMemberId = announcedHost;
        guestMemberId = message.guestMemberId;
        matchToken = message.matchToken.trim();
        invitationCode = normalizeCode(message.invitationCode == null ? invitationCode : message.invitationCode);
        hostColor = acceptedHostColor;
        initialMinutes = message.initialMinutes;
        incrementSeconds = message.incrementSeconds;
        gameNumber = message.gameNumber;
        sequence = message.sequence;
        opponentConnected = true;
        awaitingHostMoveConfirmation = false;
        mode = Mode.GUEST_PLAYING;

        if (firstAcceptance)
        {
            controller.configureTimeControl(initialMinutes, incrementSeconds);
            controller.newGame();
            controller.setNotice("Connected. You are " + getLocalColor().displayName() + ".");
            sendSyncRequest();
        }
        notifyListeners();
    }

    private void handleReject(ChessPartyMessage message)
    {
        if (mode != Mode.GUEST_JOINING || message.getMemberId() == 0L
            || !matchesInvitation(message))
        {
            return;
        }

        long announcedHost = message.hostMemberId == 0L
            ? message.getMemberId()
            : message.hostMemberId;
        if (announcedHost != message.getMemberId()
            || (hostMemberId != 0L && hostMemberId != announcedHost))
        {
            return;
        }

        hostMemberId = announcedHost;
        String reason = sanitizedText(
            message.reason,
            MAX_REASON_LENGTH,
            "The invitation is no longer available.");
        leaveMatchInternal(true, false);
        controller.setNotice(reason);
    }

    private void handleMoveRequest(ChessPartyMessage message)
    {
        if (!isHost() || !isAcceptedGuest(message) || !sameMatch(message))
        {
            return;
        }
        if (message.gameNumber != gameNumber || message.sequence != sequence)
        {
            broadcastState();
            return;
        }
        if (controller.getGame().getSideToMove() != hostColor.opposite())
        {
            broadcastState();
            return;
        }

        try
        {
            Move move = Move.fromUci(message.uci);
            if (controller.playMove(move))
            {
                sequence++;
                rematchRequestedBy = 0L;
            }
        }
        catch (IllegalArgumentException ignored)
        {
            // The authoritative state response below corrects malformed or stale requests.
        }
        broadcastState();
    }

    private void handleState(ChessPartyMessage message)
    {
        long local = localMemberId();
        if (mode != Mode.GUEST_PLAYING || local == 0L
            || message.getMemberId() != hostMemberId || !sameMatch(message)
            || message.hostMemberId != hostMemberId || message.guestMemberId != local
            || !isSupportedTimeControl(message.initialMinutes, message.incrementSeconds)
            || message.gameNumber < 1 || message.sequence < 0
            || message.whiteMillis < 0L || message.blackMillis < 0L
            || message.positionFen == null || message.positionFen.trim().isEmpty()
            || message.positionFen.length() > MAX_FEN_LENGTH
            || (message.lastMoveUci != null && message.lastMoveUci.length() > MAX_UCI_LENGTH)
            || !isKnownMemberOrNobody(message.rematchRequestedBy))
        {
            return;
        }
        if (message.gameNumber < gameNumber
            || (message.gameNumber == gameNumber && message.sequence < sequence))
        {
            return;
        }

        try
        {
            GameStatus status = parseRequiredStatus(message.gameStatus);
            ChessColor active = parseOptionalColor(message.activeColor);
            ChessColor offeredBy = parseOptionalColor(message.drawOfferedBy);
            ChessColor stateHostColor = parseOptionalColor(message.hostColor);
            if ((message.clockRunning && active == null) || stateHostColor == null)
            {
                throw new IllegalArgumentException("Incomplete clock or color state");
            }
            controller.restoreNetworkState(
                message.initialMinutes,
                message.incrementSeconds,
                message.positionFen,
                message.lastMoveUci,
                message.whiteMillis,
                message.blackMillis,
                active,
                message.clockRunning,
                offeredBy,
                status,
                sanitizedText(message.notice, MAX_NOTICE_LENGTH, null));
            initialMinutes = message.initialMinutes;
            incrementSeconds = message.incrementSeconds;
            gameNumber = message.gameNumber;
            sequence = message.sequence;
            rematchRequestedBy = message.rematchRequestedBy;
            hostColor = stateHostColor;
            opponentConnected = true;
            awaitingHostMoveConfirmation = false;
            notifyListeners();
        }
        catch (IllegalArgumentException ex)
        {
            controller.setNotice("Could not apply the host state. Requesting another sync.");
            sendSyncRequest();
        }
    }

    private void handleDrawRequest(ChessPartyMessage message)
    {
        if (!isHost() || !isAcceptedGuest(message) || !sameMatch(message)
            || message.gameNumber != gameNumber || message.sequence != sequence
            || controller.getGame().getStatus().isFinished())
        {
            broadcastState();
            return;
        }
        controller.handleDrawAction(hostColor.opposite());
        sequence++;
        broadcastState();
    }

    private void handleResignRequest(ChessPartyMessage message)
    {
        if (!isHost() || !isAcceptedGuest(message) || !sameMatch(message)
            || message.gameNumber != gameNumber || message.sequence != sequence
            || controller.getGame().getStatus().isFinished())
        {
            broadcastState();
            return;
        }
        controller.resign(hostColor.opposite());
        sequence++;
        broadcastState();
    }

    private void handleRematchRequest(ChessPartyMessage message)
    {
        if (!isHost() || !isAcceptedGuest(message) || !sameMatch(message)
            || message.gameNumber != gameNumber || message.sequence != sequence
            || !controller.getGame().getStatus().isFinished())
        {
            return;
        }
        if (rematchRequestedBy == hostMemberId)
        {
            startRematch();
        }
        else
        {
            rematchRequestedBy = guestMemberId;
            broadcastState();
        }
    }

    private void handleRematchAccept(ChessPartyMessage message)
    {
        if (isHost() && isAcceptedGuest(message) && sameMatch(message)
            && message.gameNumber == gameNumber && message.sequence == sequence
            && controller.getGame().getStatus().isFinished()
            && rematchRequestedBy == hostMemberId)
        {
            startRematch();
        }
    }

    private void startRematch()
    {
        gameNumber++;
        sequence = 0;
        rematchRequestedBy = 0L;
        hostColor = hostColor.opposite();
        controller.configureTimeControl(initialMinutes, incrementSeconds);
        controller.newGame();
        controller.setNotice("Rematch started. Colors were swapped.");
        broadcastState();
        notifyListeners();
    }

    private void sendOpen(long targetMemberId)
    {
        if (!isHost() || invitationCode == null)
        {
            return;
        }
        if (hostMemberId == 0L)
        {
            hostMemberId = localMemberId();
        }
        if (hostMemberId == 0L)
        {
            return;
        }

        ChessPartyMessage message = baseMessage(ChessPartyMessage.OPEN);
        message.matchToken = null;
        message.targetMemberId = targetMemberId;
        message.invitationCode = invitationCode;
        message.hostMemberId = hostMemberId;
        message.initialMinutes = initialMinutes;
        message.incrementSeconds = incrementSeconds;
        partyService.send(message);
        lastOpenBroadcastMillis = System.currentTimeMillis();
    }

    private void sendJoinRequest()
    {
        if (mode != Mode.GUEST_JOINING)
        {
            return;
        }
        long local = localMemberId();
        long now = System.currentTimeMillis();
        if (local == 0L || now - lastJoinSentMillis < JOIN_RETRY_INTERVAL_MILLIS)
        {
            return;
        }

        ChessPartyMessage message = baseMessage(ChessPartyMessage.JOIN);
        message.targetMemberId = hostMemberId;
        message.invitationCode = invitationCode;
        partyService.send(message);
        lastJoinSentMillis = now;
    }

    private void sendAccept(long targetMemberId)
    {
        ChessPartyMessage message = baseMessage(ChessPartyMessage.ACCEPT);
        message.targetMemberId = targetMemberId;
        message.invitationCode = invitationCode;
        message.matchToken = matchToken;
        message.hostMemberId = hostMemberId;
        message.guestMemberId = guestMemberId;
        message.initialMinutes = initialMinutes;
        message.incrementSeconds = incrementSeconds;
        message.gameNumber = gameNumber;
        message.sequence = sequence;
        message.hostColor = hostColor.name();
        partyService.send(message);
    }

    private void sendSyncRequest()
    {
        if (mode != Mode.GUEST_PLAYING || hostMemberId == 0L)
        {
            return;
        }
        ChessPartyMessage message = baseMessage(ChessPartyMessage.SYNC_REQUEST);
        message.targetMemberId = hostMemberId;
        message.gameNumber = gameNumber;
        message.sequence = sequence;
        partyService.send(message);
    }

    private void broadcastState()
    {
        if (!isHost() || guestMemberId == 0L || matchToken == null)
        {
            return;
        }

        ChessPartyMessage message = baseMessage(ChessPartyMessage.STATE);
        message.targetMemberId = guestMemberId;
        message.hostMemberId = hostMemberId;
        message.guestMemberId = guestMemberId;
        message.initialMinutes = initialMinutes;
        message.incrementSeconds = incrementSeconds;
        message.gameNumber = gameNumber;
        message.sequence = sequence;
        message.hostColor = hostColor.name();
        message.positionFen = controller.getPositionFen();
        message.lastMoveUci = controller.getLastMoveUci();
        message.whiteMillis = controller.getClock().getRemainingMillis(ChessColor.WHITE);
        message.blackMillis = controller.getClock().getRemainingMillis(ChessColor.BLACK);
        ChessColor active = controller.getClock().getActiveColor();
        message.activeColor = active == null ? null : active.name();
        message.clockRunning = controller.getClock().isRunning();
        message.gameStatus = controller.getGame().getStatus().name();
        ChessColor offeredBy = controller.getDrawOfferedBy();
        message.drawOfferedBy = offeredBy == null ? null : offeredBy.name();
        message.rematchRequestedBy = rematchRequestedBy;
        message.notice = sanitizedText(controller.getNotice(), MAX_NOTICE_LENGTH, "");
        partyService.send(message);
        lastStateBroadcastMillis = System.currentTimeMillis();
    }

    private ChessPartyMessage baseMessage(String kind)
    {
        ChessPartyMessage message = new ChessPartyMessage();
        message.protocolVersion = PROTOCOL_VERSION;
        message.kind = kind;
        message.invitationCode = invitationCode;
        message.matchToken = matchToken;
        message.hostMemberId = hostMemberId;
        message.guestMemberId = guestMemberId;
        return message;
    }

    private boolean isAcceptedGuest(ChessPartyMessage message)
    {
        return message != null && guestMemberId != 0L && message.getMemberId() == guestMemberId;
    }

    private boolean isOpponent(long memberId)
    {
        return memberId != 0L && memberId == (isHost() ? guestMemberId : hostMemberId);
    }

    private boolean sameMatch(ChessPartyMessage message)
    {
        return message != null
            && matchToken != null
            && matchToken.equals(message.matchToken);
    }

    private boolean matchesInvitation(ChessPartyMessage message)
    {
        return message != null
            && invitationCode != null
            && invitationCode.equals(normalizeCode(message.invitationCode));
    }

    private static boolean isSupportedTimeControl(int minutes, int increment)
    {
        return ChessTimeControl.isSupported(minutes, increment);
    }

    private long localMemberId()
    {
        PartyMember member = partyService.getLocalMember();
        return member == null ? 0L : member.getMemberId();
    }

    private void leaveMatchInternal(boolean restorePreviousParty, boolean shuttingDown)
    {
        if (isOnline())
        {
            long local = localMemberId();
            if (local != 0L && matchToken != null)
            {
                ChessPartyMessage leave = baseMessage(ChessPartyMessage.LEAVE);
                leave.targetMemberId = isHost() ? guestMemberId : hostMemberId;
                try
                {
                    partyService.send(leave);
                }
                catch (RuntimeException ignored)
                {
                    // Best effort during shutdown/disconnect.
                }
            }
        }

        String restore = restorePreviousParty ? previousPartyPassphrase : null;
        mode = Mode.LOCAL;
        invitationCode = null;
        matchToken = null;
        hostMemberId = 0L;
        guestMemberId = 0L;
        rematchRequestedBy = 0L;
        opponentConnected = false;
        awaitingHostMoveConfirmation = false;
        sequence = 0;
        gameNumber = 1;
        lastJoinSentMillis = 0L;
        lastOpenBroadcastMillis = 0L;
        lastStateBroadcastMillis = 0L;
        lastJoinRequestByMember.clear();
        previousPartyPassphrase = null;

        if (partyService.isInParty() && (restorePreviousParty || shuttingDown))
        {
            partyService.changeParty(restore);
        }
        if (!shuttingDown)
        {
            controller.setNotice("Returned to local chess.");
            notifyListeners();
        }
    }

    private void notifyListeners()
    {
        for (Listener listener : new ArrayList<>(listeners))
        {
            listener.onSessionChanged();
        }
    }

    private void runOnSwingThread(Runnable runnable)
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            runnable.run();
        }
        else
        {
            SwingUtilities.invokeLater(runnable);
        }
    }

    private String generateCode()
    {
        StringBuilder result = new StringBuilder(8);
        for (int i = 0; i < 8; i++)
        {
            result.append(CODE_ALPHABET.charAt(secureRandom.nextInt(CODE_ALPHABET.length())));
        }
        return result.toString();
    }

    public static String formatCode(String code)
    {
        String normalized = normalizeCode(code);
        if (normalized.length() <= 4)
        {
            return normalized;
        }
        return normalized.substring(0, 4) + "-" + normalized.substring(4);
    }

    private static String normalizeCode(String code)
    {
        if (code == null)
        {
            return "";
        }

        StringBuilder normalized = new StringBuilder(8);
        for (int i = 0; i < code.length(); i++)
        {
            char value = Character.toUpperCase(code.charAt(i));
            if ((value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9'))
            {
                normalized.append(value);
            }
        }
        return normalized.toString();
    }

    private static boolean isValidInvitationCode(String code)
    {
        if (code == null || code.length() != 8)
        {
            return false;
        }
        for (int i = 0; i < code.length(); i++)
        {
            if (CODE_ALPHABET.indexOf(code.charAt(i)) < 0)
            {
                return false;
            }
        }
        return true;
    }

    private static void validateTimeControl(int minutes, int increment)
    {
        ChessTimeControl.validate(minutes, increment);
    }

    private boolean isKnownMemberOrNobody(long memberId)
    {
        return memberId == 0L || memberId == hostMemberId || memberId == guestMemberId;
    }

    private static boolean isValidMatchToken(String token)
    {
        if (token == null || token.length() > 36)
        {
            return false;
        }
        try
        {
            return UUID.fromString(token).toString().equals(token);
        }
        catch (IllegalArgumentException ex)
        {
            return false;
        }
    }

    private static String sanitizedText(String text, int maxLength, String fallback)
    {
        if (text == null || text.trim().isEmpty())
        {
            return fallback;
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static ChessColor parseOptionalColor(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return null;
        }
        return ChessColor.valueOf(value.trim());
    }

    private static GameStatus parseRequiredStatus(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            throw new IllegalArgumentException("Missing game status");
        }
        return GameStatus.valueOf(value.trim());
    }
}
