package com.playercoder1.chess;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

@Singleton
public final class ChessBotService implements ChessGameListener
{
    public interface Listener
    {
        void onBotChanged();
    }

    private static final long MOVE_DELAY_MILLIS = 260L;

    private final LocalChessController controller;
    private final ChessBotEngine engine = new ChessBotEngine();
    private final List<Listener> listeners = new ArrayList<>();

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> pendingMove;
    private BotDifficulty difficulty = BotDifficulty.GOBLIN;
    private ChessColor humanColor = ChessColor.WHITE;
    private long gameGeneration;
    private boolean active;
    private boolean thinking;
    private boolean started;

    @Inject
    public ChessBotService(LocalChessController controller)
    {
        this.controller = controller;
    }

    public synchronized void start()
    {
        if (started)
        {
            return;
        }
        started = true;
        executor = Executors.newSingleThreadScheduledExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "runelite-chess-bot");
            thread.setDaemon(true);
            return thread;
        });
        controller.addListener(this);
    }

    public synchronized void stop()
    {
        if (!started)
        {
            return;
        }
        stopGameInternal();
        started = false;
        controller.removeListener(this);
        if (executor != null)
        {
            executor.shutdownNow();
            executor = null;
        }
        notifyListeners();
    }

    public void startGame(
        BotDifficulty difficulty,
        ChessColor humanColor,
        int minutes,
        int incrementSeconds)
    {
        if (difficulty == null || humanColor == null)
        {
            throw new IllegalArgumentException("Opponent and player color are required.");
        }

        synchronized (this)
        {
            ensureStarted();
            cancelPendingMove();
            this.difficulty = difficulty;
            this.humanColor = humanColor;
            active = true;
            thinking = false;
            gameGeneration++;
        }

        controller.configureTimeControl(minutes, incrementSeconds);
        controller.newGame();
        controller.setNotice("Playing " + difficulty.getDisplayName()
            + " (about " + difficulty.getApproximateRating() + ").");
        notifyListeners();
        scheduleMoveIfNeeded();
    }

    public synchronized void stopGame()
    {
        if (!active && pendingMove == null)
        {
            return;
        }
        stopGameInternal();
        notifyListeners();
    }

    private void stopGameInternal()
    {
        cancelPendingMove();
        active = false;
        thinking = false;
        gameGeneration++;
    }

    public synchronized boolean isActive()
    {
        return active;
    }

    public synchronized boolean isThinking()
    {
        return thinking;
    }

    public synchronized BotDifficulty getDifficulty()
    {
        return difficulty;
    }

    public synchronized ChessColor getHumanColor()
    {
        return humanColor;
    }

    public synchronized ChessColor getBotColor()
    {
        return humanColor.opposite();
    }

    public synchronized boolean canHumanSelect(ChessColor pieceColor)
    {
        if (!active)
        {
            return true;
        }
        return pieceColor == humanColor
            && controller.getGame().getSideToMove() == humanColor
            && !thinking;
    }

    public synchronized boolean isHumanTurn()
    {
        return !active || controller.getGame().getSideToMove() == humanColor;
    }

    public synchronized void addListener(Listener listener)
    {
        if (listener != null && !listeners.contains(listener))
        {
            listeners.add(listener);
        }
    }

    public synchronized void removeListener(Listener listener)
    {
        listeners.remove(listener);
    }

    @Override
    public void onGameChanged()
    {
        synchronized (this)
        {
            if (active && controller.getGame().getStatus().isFinished())
            {
                cancelPendingMove();
                thinking = false;
            }
        }
        scheduleMoveIfNeeded();
    }

    private void scheduleMoveIfNeeded()
    {
        final String fen;
        final BotDifficulty selectedDifficulty;
        final long generation;

        synchronized (this)
        {
            if (!started || !active || executor == null
                || controller.getGame().getStatus().isFinished()
                || controller.getGame().getSideToMove() != humanColor.opposite())
            {
                return;
            }
            if (pendingMove != null)
            {
                return;
            }

            fen = controller.getGame().toFen();
            selectedDifficulty = difficulty;
            generation = gameGeneration;
            thinking = true;
            pendingMove = executor.schedule(
                () -> calculateMove(fen, selectedDifficulty, generation),
                MOVE_DELAY_MILLIS,
                TimeUnit.MILLISECONDS);
        }
        notifyListeners();
    }

    private void calculateMove(String fen, BotDifficulty selectedDifficulty, long generation)
    {
        Move move = null;
        try
        {
            move = engine.chooseMove(ChessGame.fromFen(fen), selectedDifficulty);
        }
        finally
        {
            final Move selectedMove = move;
            SwingUtilities.invokeLater(() -> applyMove(fen, selectedMove, generation));
        }
    }

    private void applyMove(String expectedFen, Move move, long generation)
    {
        boolean shouldApply;
        synchronized (this)
        {
            pendingMove = null;
            thinking = false;
            shouldApply = active
                && generation == gameGeneration
                && !controller.getGame().getStatus().isFinished()
                && controller.getGame().getSideToMove() == humanColor.opposite()
                && controller.getGame().toFen().equals(expectedFen);
        }

        if (shouldApply && move != null)
        {
            controller.playMove(move);
        }
        notifyListeners();
    }

    private synchronized void cancelPendingMove()
    {
        if (pendingMove != null)
        {
            pendingMove.cancel(true);
            pendingMove = null;
        }
    }

    private synchronized void ensureStarted()
    {
        if (!started || executor == null)
        {
            throw new IllegalStateException("Chess bot service is not running.");
        }
    }

    private void notifyListeners()
    {
        List<Listener> copy;
        synchronized (this)
        {
            copy = new ArrayList<>(listeners);
        }
        for (Listener listener : copy)
        {
            listener.onBotChanged();
        }
    }
}
