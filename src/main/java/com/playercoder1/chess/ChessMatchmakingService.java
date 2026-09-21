package com.playercoder1.chess;

import java.util.ArrayList;
import java.util.Collections;
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
public final class ChessMatchmakingService
{
    public enum State
    {
        IDLE,
        BROWSING,
        SEARCHING,
        CONNECTING
    }

    @FunctionalInterface
    public interface Listener
    {
        void onMatchmakingChanged();
    }

    private static final int PROTOCOL_VERSION = 2;
    private static final String LOBBY_PASSPHRASE = "runelite-chess-unrated-lobby-v2";
    private static final long QUEUE_HEARTBEAT_MILLIS = TimeUnit.SECONDS.toMillis(3);
    private static final long QUEUE_STALE_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final long OFFER_RETRY_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long OFFER_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(6);
    private static final long READY_REPEAT_MILLIS = 250L;
    private static final int READY_SEND_COUNT = 3;
    private static final int MAX_CODE_LENGTH = 8;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";


    private static final int[][] QUICK_QUEUES =
            {
                    {1, 0},
                    {3, 0},
                    {3, 2},
                    {5, 0},
                    {5, 3},
                    {10, 0},
                    {10, 3},
                    {10, 5},
                    {15, 10}
            };

    private final PartyService partyService;
    private final EventBus eventBus;
    private final ChessMultiplayerService multiplayer;
    private final ChessBotService botService;
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<Long, QueuePeer> peers = new HashMap<>();

    private State state = State.IDLE;
    private String previousPartyPassphrase;
    private String queueTicket;
    private int initialMinutes = ChessTimeControl.DEFAULT_MINUTES;
    private int incrementSeconds = ChessTimeControl.DEFAULT_INCREMENT_SECONDS;
    private long searchStartedMillis;
    private long lastQueueSentMillis;
    private long lastNotifiedElapsedSecond = -1L;

    private long outgoingPeerId;
    private String outgoingPeerTicket;
    private String outgoingOfferToken;
    private String outgoingMatchCode;
    private long outgoingOfferStartedMillis;
    private long lastOfferSentMillis;

    private long incomingPeerId;
    private String incomingPeerTicket;
    private String incomingOfferToken;
    private String incomingMatchCode;
    private long incomingOfferStartedMillis;

    private boolean readyingHost;
    private int readySendCount;
    private long lastReadySentMillis;
    private boolean started;

    @Inject
    public ChessMatchmakingService(
            PartyService partyService,
            EventBus eventBus,
            ChessMultiplayerService multiplayer,
            ChessBotService botService)
    {
        this.partyService = partyService;
        this.eventBus = eventBus;
        this.multiplayer = multiplayer;
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
        closeLobbyInternal(false);
    }

    public State getState()
    {
        return state;
    }

    public boolean isLobbyOpen()
    {
        return state != State.IDLE;
    }

    public boolean isLobbyReady()
    {
        return isLobbyOpen() && isInLobbyParty() && localMemberId() != 0L;
    }

    public boolean isBrowsing()
    {
        return state == State.BROWSING;
    }

    public boolean isSearching()
    {
        return state == State.SEARCHING || state == State.CONNECTING;
    }

    public boolean isConnecting()
    {
        return state == State.CONNECTING;
    }

    public int getInitialMinutes()
    {
        return initialMinutes;
    }

    public int getIncrementSeconds()
    {
        return incrementSeconds;
    }

    public long getSearchElapsedSeconds()
    {
        if (!isSearching() || searchStartedMillis == 0L)
        {
            return 0L;
        }
        return Math.max(0L, (System.currentTimeMillis() - searchStartedMillis) / 1000L);
    }

    public static int[][] queuePresets()
    {
        int[][] copy = new int[QUICK_QUEUES.length][2];
        for (int i = 0; i < QUICK_QUEUES.length; i++)
        {
            copy[i][0] = QUICK_QUEUES[i][0];
            copy[i][1] = QUICK_QUEUES[i][1];
        }
        return copy;
    }

    public boolean isSelectedQueue(int minutes, int increment)
    {
        return isSearching() && initialMinutes == minutes && incrementSeconds == increment;
    }

    public int getQueueCount(int minutes, int increment)
    {
        long cutoff = System.currentTimeMillis() - QUEUE_STALE_MILLIS;
        int count = isSelectedQueue(minutes, increment) && localMemberId() != 0L ? 1 : 0;

        for (QueuePeer peer : peers.values())
        {
            if (peer.lastSeenMillis >= cutoff
                    && peer.initialMinutes == minutes
                    && peer.incrementSeconds == increment)
            {
                count++;
            }
        }
        return count;
    }


    public int getVisiblePlayerCount()
    {
        int total = 0;
        for (int[] queue : QUICK_QUEUES)
        {
            total += getQueueCount(queue[0], queue[1]);
        }
        return total;
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


    public void openLobby()
    {
        if (multiplayer.isOnline())
        {
            throw new IllegalStateException("Leave the current online match before matchmaking.");
        }
        if (state != State.IDLE)
        {
            return;
        }

        botService.stopGame();
        previousPartyPassphrase = partyService.getPartyPassphrase();
        peers.clear();
        clearPairing();
        queueTicket = null;
        searchStartedMillis = 0L;
        lastQueueSentMillis = 0L;
        state = State.BROWSING;
        partyService.changeParty(LOBBY_PASSPHRASE);
        notifyListeners();
    }

    public void closeLobby()
    {
        closeLobbyInternal(true);
    }

    public void joinQueue(int minutes, int increment)
    {
        ChessTimeControl.validate(minutes, increment);
        if (!isQuickQueue(minutes, increment))
        {
            throw new IllegalArgumentException("Choose one of the public quick-match time controls.");
        }
        if (multiplayer.isOnline())
        {
            throw new IllegalStateException("Leave the current online match before joining matchmaking.");
        }
        if (state == State.IDLE)
        {
            openLobby();
        }
        if (!isInLobbyParty())
        {
            throw new IllegalStateException("The matchmaking lobby is still connecting. Try again in a moment.");
        }

        if (isSearching())
        {
            cancelQueueInternal(false);
        }

        initialMinutes = minutes;
        incrementSeconds = increment;
        queueTicket = UUID.randomUUID().toString();
        searchStartedMillis = System.currentTimeMillis();
        lastQueueSentMillis = 0L;
        lastNotifiedElapsedSecond = -1L;
        clearPairing();
        state = State.SEARCHING;
        sendQueue(0L);
        notifyListeners();
    }


    public void cancelQueue()
    {
        cancelQueueInternal(true);
    }

    public void tick()
    {
        if (state == State.IDLE)
        {
            return;
        }

        long now = System.currentTimeMillis();
        prunePeers(now);

        if (isSearching())
        {
            long elapsedSecond = Math.max(0L, (now - searchStartedMillis) / 1000L);
            if (elapsedSecond != lastNotifiedElapsedSecond)
            {
                lastNotifiedElapsedSecond = elapsedSecond;
                notifyListeners();
            }
        }

        if (!isInLobbyParty())
        {
            return;
        }

        if (state == State.SEARCHING)
        {
            if (now - lastQueueSentMillis >= QUEUE_HEARTBEAT_MILLIS)
            {
                sendQueue(0L);
            }
            attemptPairing(now);
            return;
        }

        if (state != State.CONNECTING)
        {
            return;
        }

        if (readyingHost)
        {
            if (readySendCount < READY_SEND_COUNT
                    && now - lastReadySentMillis >= READY_REPEAT_MILLIS)
            {
                sendReady();
            }
            else if (readySendCount >= READY_SEND_COUNT
                    && now - lastReadySentMillis >= READY_REPEAT_MILLIS)
            {
                beginHostMatch();
            }
            return;
        }

        if (outgoingPeerId != 0L)
        {
            if (now - outgoingOfferStartedMillis >= OFFER_TIMEOUT_MILLIS)
            {
                resumeSearching("Opponent did not answer. Searching again…");
            }
            else if (now - lastOfferSentMillis >= OFFER_RETRY_MILLIS)
            {
                sendOffer();
            }
            return;
        }

        if (incomingPeerId != 0L
                && now - incomingOfferStartedMillis >= OFFER_TIMEOUT_MILLIS)
        {
            resumeSearching("Match connection timed out. Searching again…");
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
    public void onChessLobbyMessage(ChessLobbyMessage message)
    {
        runOnSwingThread(() -> handleLobbyMessage(message));
    }

    private void handleUserJoin(UserJoin event)
    {
        if (state == State.IDLE || event == null || !isInLobbyParty()
                || event.getPartyId() != partyService.getPartyId())
        {
            return;
        }

        long local = localMemberId();
        if (local == 0L)
        {
            return;
        }

        if (event.getMemberId() == local)
        {
            if (isSearching())
            {
                sendQueue(0L);
            }
        }
        else if (isSearching())
        {

            sendQueue(event.getMemberId());
        }
    }

    private void handleUserPart(UserPart event)
    {
        if (state == State.IDLE || event == null)
        {
            return;
        }

        long memberId = event.getMemberId();
        peers.remove(memberId);


        if (readyingHost && memberId == outgoingPeerId)
        {
            return;
        }

        if (memberId == outgoingPeerId || memberId == incomingPeerId)
        {
            resumeSearching("Opponent left the lobby. Searching again…");
        }
        else
        {
            notifyListeners();
        }
    }

    private void handleLobbyMessage(ChessLobbyMessage message)
    {
        if (state == State.IDLE || message == null || message.kind == null
                || message.protocolVersion != PROTOCOL_VERSION || !isInLobbyParty())
        {
            return;
        }

        long local = localMemberId();
        if (local == 0L || message.getMemberId() == 0L || message.getMemberId() == local)
        {
            return;
        }
        if (message.targetMemberId != 0L && message.targetMemberId != local)
        {
            return;
        }

        switch (message.kind)
        {
            case ChessLobbyMessage.QUEUE:
                handleQueue(message);
                break;
            case ChessLobbyMessage.OFFER:
                if (matchesSelectedQueue(message))
                {
                    handleOffer(message);
                }
                break;
            case ChessLobbyMessage.ACCEPT:
                if (matchesSelectedQueue(message))
                {
                    handleAccept(message);
                }
                break;
            case ChessLobbyMessage.READY:
                if (matchesSelectedQueue(message))
                {
                    handleReady(message);
                }
                break;
            case ChessLobbyMessage.CANCEL:
                handleCancel(message);
                break;
            default:
                break;
        }
    }

    private void handleQueue(ChessLobbyMessage message)
    {
        if (!isUuid(message.queueTicket)
                || !isQuickQueue(message.initialMinutes, message.incrementSeconds))
        {
            return;
        }

        peers.put(message.getMemberId(), new QueuePeer(
                message.getMemberId(),
                message.queueTicket,
                message.initialMinutes,
                message.incrementSeconds,
                System.currentTimeMillis()));
        notifyListeners();

        if (state == State.SEARCHING
                && matchesSelectedQueue(message))
        {
            attemptPairing(System.currentTimeMillis());
        }
    }

    private void handleOffer(ChessLobbyMessage message)
    {
        if (state != State.SEARCHING && state != State.CONNECTING)
        {
            return;
        }
        if (readyingHost
                || !isUuid(message.queueTicket)
                || !queueTicketEquals(message.targetQueueTicket)
                || !isUuid(message.offerToken)
                || !isValidInternalCode(message.matchCode))
        {
            return;
        }

        long sender = message.getMemberId();
        peers.put(sender, new QueuePeer(
                sender,
                message.queueTicket,
                message.initialMinutes,
                message.incrementSeconds,
                System.currentTimeMillis()));


        if (expectedCoordinatorForLocal() != sender)
        {
            return;
        }

        if (incomingPeerId == sender
                && message.offerToken.equals(incomingOfferToken)
                && message.matchCode.equals(incomingMatchCode))
        {
            sendAccept();
            return;
        }

        clearOutgoingOffer(true);
        incomingPeerId = sender;
        incomingPeerTicket = message.queueTicket;
        incomingOfferToken = message.offerToken;
        incomingMatchCode = message.matchCode;
        incomingOfferStartedMillis = System.currentTimeMillis();
        state = State.CONNECTING;
        sendAccept();
        notifyListeners();
    }

    private void handleAccept(ChessLobbyMessage message)
    {
        if (state != State.CONNECTING || outgoingPeerId == 0L || readyingHost
                || message.getMemberId() != outgoingPeerId
                || !queueTicketEquals(message.targetQueueTicket)
                || !safeEquals(outgoingPeerTicket, message.queueTicket)
                || !safeEquals(outgoingOfferToken, message.offerToken)
                || !safeEquals(outgoingMatchCode, message.matchCode))
        {
            return;
        }

        readyingHost = true;
        readySendCount = 0;
        lastReadySentMillis = 0L;
        sendReady();
        notifyListeners();
    }

    private void handleReady(ChessLobbyMessage message)
    {
        if (state != State.CONNECTING || incomingPeerId == 0L
                || message.getMemberId() != incomingPeerId
                || !queueTicketEquals(message.targetQueueTicket)
                || !safeEquals(incomingPeerTicket, message.queueTicket)
                || !safeEquals(incomingOfferToken, message.offerToken)
                || !safeEquals(incomingMatchCode, message.matchCode))
        {
            return;
        }

        beginGuestMatch();
    }

    private void handleCancel(ChessLobbyMessage message)
    {
        QueuePeer peer = peers.get(message.getMemberId());
        if (peer != null && (message.queueTicket == null || safeEquals(peer.queueTicket, message.queueTicket)))
        {
            peers.remove(message.getMemberId());
        }

        if (message.getMemberId() == outgoingPeerId || message.getMemberId() == incomingPeerId)
        {
            resumeSearching("Opponent cancelled. Searching again…");
        }
        else
        {
            notifyListeners();
        }
    }

    private void attemptPairing(long now)
    {
        if (state != State.SEARCHING)
        {
            return;
        }

        long local = localMemberId();
        if (local == 0L)
        {
            return;
        }

        List<Long> members = sortedActiveMembers(now, local);
        int index = members.indexOf(local);
        if (index < 0 || (index & 1) != 0 || index + 1 >= members.size())
        {
            return;
        }

        long candidateId = members.get(index + 1);
        QueuePeer candidate = peers.get(candidateId);
        if (candidate == null)
        {
            return;
        }

        outgoingPeerId = candidateId;
        outgoingPeerTicket = candidate.queueTicket;
        outgoingOfferToken = UUID.randomUUID().toString();
        outgoingMatchCode = multiplayer.generateInternalMatchCode();
        outgoingOfferStartedMillis = now;
        lastOfferSentMillis = 0L;
        state = State.CONNECTING;
        sendOffer();
        notifyListeners();
    }

    private long expectedCoordinatorForLocal()
    {
        long local = localMemberId();
        if (local == 0L)
        {
            return 0L;
        }

        List<Long> members = sortedActiveMembers(System.currentTimeMillis(), local);
        int index = members.indexOf(local);
        if (index <= 0 || (index & 1) == 0)
        {
            return 0L;
        }
        return members.get(index - 1);
    }

    private List<Long> sortedActiveMembers(long now, long local)
    {
        long cutoff = now - QUEUE_STALE_MILLIS;
        List<Long> members = new ArrayList<>();
        members.add(local);
        for (QueuePeer peer : peers.values())
        {
            if (peer.lastSeenMillis >= cutoff
                    && peer.initialMinutes == initialMinutes
                    && peer.incrementSeconds == incrementSeconds)
            {
                members.add(peer.memberId);
            }
        }
        Collections.sort(members);
        return members;
    }

    private void sendQueue(long targetMemberId)
    {
        if (!isSearching() || queueTicket == null || localMemberId() == 0L || !isInLobbyParty())
        {
            return;
        }

        ChessLobbyMessage message = baseMessage(ChessLobbyMessage.QUEUE);
        message.targetMemberId = targetMemberId;
        partyService.send(message);
        lastQueueSentMillis = System.currentTimeMillis();
    }

    private void sendOffer()
    {
        if (outgoingPeerId == 0L || outgoingPeerTicket == null
                || outgoingOfferToken == null || outgoingMatchCode == null)
        {
            return;
        }

        ChessLobbyMessage message = baseMessage(ChessLobbyMessage.OFFER);
        message.targetMemberId = outgoingPeerId;
        message.targetQueueTicket = outgoingPeerTicket;
        message.offerToken = outgoingOfferToken;
        message.matchCode = outgoingMatchCode;
        partyService.send(message);
        lastOfferSentMillis = System.currentTimeMillis();
    }

    private void sendAccept()
    {
        if (incomingPeerId == 0L || incomingPeerTicket == null
                || incomingOfferToken == null || incomingMatchCode == null)
        {
            return;
        }

        ChessLobbyMessage message = baseMessage(ChessLobbyMessage.ACCEPT);
        message.targetMemberId = incomingPeerId;
        message.targetQueueTicket = incomingPeerTicket;
        message.offerToken = incomingOfferToken;
        message.matchCode = incomingMatchCode;
        partyService.send(message);
    }

    private void sendReady()
    {
        if (!readyingHost || outgoingPeerId == 0L)
        {
            return;
        }

        ChessLobbyMessage message = baseMessage(ChessLobbyMessage.READY);
        message.targetMemberId = outgoingPeerId;
        message.targetQueueTicket = outgoingPeerTicket;
        message.offerToken = outgoingOfferToken;
        message.matchCode = outgoingMatchCode;
        partyService.send(message);
        readySendCount++;
        lastReadySentMillis = System.currentTimeMillis();
    }

    private void sendCancel()
    {
        if (!isInLobbyParty() || queueTicket == null || localMemberId() == 0L)
        {
            return;
        }
        ChessLobbyMessage message = baseMessage(ChessLobbyMessage.CANCEL);
        partyService.send(message);
    }

    private ChessLobbyMessage baseMessage(String kind)
    {
        ChessLobbyMessage message = new ChessLobbyMessage();
        message.protocolVersion = PROTOCOL_VERSION;
        message.kind = kind;
        message.queueTicket = queueTicket;
        message.initialMinutes = initialMinutes;
        message.incrementSeconds = incrementSeconds;
        return message;
    }

    private void beginHostMatch()
    {
        if (!readyingHost || outgoingMatchCode == null)
        {
            resumeSearching("Could not start the match. Searching again…");
            return;
        }

        String code = outgoingMatchCode;
        String restoreParty = previousPartyPassphrase;
        int minutes = initialMinutes;
        int increment = incrementSeconds;
        resetWithoutPartyRestore();
        multiplayer.startMatchmadeHost(code, minutes, increment, restoreParty);
    }

    private void beginGuestMatch()
    {
        if (incomingMatchCode == null)
        {
            resumeSearching("Could not join the match. Searching again…");
            return;
        }

        String code = incomingMatchCode;
        String restoreParty = previousPartyPassphrase;
        int minutes = initialMinutes;
        int increment = incrementSeconds;
        resetWithoutPartyRestore();
        multiplayer.startMatchmadeGuest(code, minutes, increment, restoreParty);
    }

    private void resumeSearching(String notice)
    {
        if (!isSearching())
        {
            return;
        }
        clearPairing();
        state = State.SEARCHING;
        lastQueueSentMillis = 0L;
        sendQueue(0L);
        notifyListeners();
    }

    private void cancelQueueInternal(boolean showNotice)
    {
        if (!isSearching())
        {
            return;
        }

        try
        {
            sendCancel();
        }
        catch (RuntimeException ignored)
        {

        }

        queueTicket = null;
        searchStartedMillis = 0L;
        lastQueueSentMillis = 0L;
        lastNotifiedElapsedSecond = -1L;
        clearPairing();
        state = State.BROWSING;
        if (showNotice)
        {
        }
        notifyListeners();
    }

    private void closeLobbyInternal(boolean showNotice)
    {
        if (state == State.IDLE)
        {
            return;
        }

        if (isSearching())
        {
            try
            {
                sendCancel();
            }
            catch (RuntimeException ignored)
            {

            }
        }

        String restore = previousPartyPassphrase;
        resetWithoutPartyRestore();

        if (partyService.isInParty())
        {
            partyService.changeParty(restore);
        }
        if (showNotice)
        {
            notifyListeners();
        }
    }

    private void resetWithoutPartyRestore()
    {
        state = State.IDLE;
        previousPartyPassphrase = null;
        queueTicket = null;
        searchStartedMillis = 0L;
        lastQueueSentMillis = 0L;
        lastNotifiedElapsedSecond = -1L;
        peers.clear();
        clearPairing();
        notifyListeners();
    }

    private void clearPairing()
    {
        outgoingPeerId = 0L;
        outgoingPeerTicket = null;
        outgoingOfferToken = null;
        outgoingMatchCode = null;
        outgoingOfferStartedMillis = 0L;
        lastOfferSentMillis = 0L;
        incomingPeerId = 0L;
        incomingPeerTicket = null;
        incomingOfferToken = null;
        incomingMatchCode = null;
        incomingOfferStartedMillis = 0L;
        readyingHost = false;
        readySendCount = 0;
        lastReadySentMillis = 0L;
    }

    private void clearOutgoingOffer(boolean notifyPeer)
    {
        if (notifyPeer && outgoingPeerId != 0L && isInLobbyParty())
        {
            try
            {
                ChessLobbyMessage cancel = baseMessage(ChessLobbyMessage.CANCEL);
                cancel.targetMemberId = outgoingPeerId;
                partyService.send(cancel);
            }
            catch (RuntimeException ignored)
            {

            }
        }

        outgoingPeerId = 0L;
        outgoingPeerTicket = null;
        outgoingOfferToken = null;
        outgoingMatchCode = null;
        outgoingOfferStartedMillis = 0L;
        lastOfferSentMillis = 0L;
        readyingHost = false;
        readySendCount = 0;
        lastReadySentMillis = 0L;
    }

    private void prunePeers(long now)
    {
        long cutoff = now - QUEUE_STALE_MILLIS;
        boolean changed = peers.values().removeIf(peer -> peer.lastSeenMillis < cutoff);
        if (changed)
        {
            notifyListeners();
        }
    }

    private boolean isInLobbyParty()
    {
        return LOBBY_PASSPHRASE.equals(partyService.getPartyPassphrase());
    }

    private boolean matchesSelectedQueue(ChessLobbyMessage message)
    {
        return isSearching()
                && message.initialMinutes == initialMinutes
                && message.incrementSeconds == incrementSeconds;
    }

    private boolean queueTicketEquals(String value)
    {
        return queueTicket != null && queueTicket.equals(value);
    }

    private long localMemberId()
    {
        PartyMember member = partyService.getLocalMember();
        return member == null ? 0L : member.getMemberId();
    }

    private String timeControlText()
    {
        return initialMinutes + "+" + incrementSeconds;
    }

    private static boolean isQuickQueue(int minutes, int increment)
    {
        for (int[] queue : QUICK_QUEUES)
        {
            if (queue[0] == minutes && queue[1] == increment)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isUuid(String value)
    {
        if (value == null || value.length() > 36)
        {
            return false;
        }
        try
        {
            return UUID.fromString(value).toString().equals(value);
        }
        catch (IllegalArgumentException ex)
        {
            return false;
        }
    }

    private static boolean isValidInternalCode(String code)
    {
        if (code == null || code.length() != MAX_CODE_LENGTH)
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

    private static boolean safeEquals(String left, String right)
    {
        return left != null && left.equals(right);
    }

    private void notifyListeners()
    {
        for (Listener listener : new ArrayList<>(listeners))
        {
            listener.onMatchmakingChanged();
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

    private static final class QueuePeer
    {
        private final long memberId;
        private final String queueTicket;
        private final int initialMinutes;
        private final int incrementSeconds;
        private final long lastSeenMillis;

        private QueuePeer(
                long memberId,
                String queueTicket,
                int initialMinutes,
                int incrementSeconds,
                long lastSeenMillis)
        {
            this.memberId = memberId;
            this.queueTicket = queueTicket;
            this.initialMinutes = initialMinutes;
            this.incrementSeconds = incrementSeconds;
            this.lastSeenMillis = lastSeenMillis;
        }
    }
}
