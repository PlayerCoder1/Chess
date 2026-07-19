package com.playercoder1.chess;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.audio.AudioPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class ChessSoundService implements ChessGameListener
{
    private static final Logger LOG = LoggerFactory.getLogger(ChessSoundService.class);
    private static final float SOUND_GAIN_DB = 0.0f;

    private enum Sound
    {
        MOVE("/move-self.wav"),
        CAPTURE("/capture.wav"),
        CASTLE("/castle.wav"),
        CHECK("/move-check.wav"),
        PROMOTION("/promote.wav"),
        GAME_END("/game-end.wav");

        private final String resourcePath;

        Sound(String resourcePath)
        {
            this.resourcePath = resourcePath;
        }
    }

    private final LocalChessController controller;
    private final AudioPlayer audioPlayer;

    private ExecutorService executor;
    private String previousFen;
    private String previousLastMove;
    private GameStatus previousStatus;
    private boolean started;
    private boolean playbackFailureLogged;

    @Inject
    public ChessSoundService(LocalChessController controller, AudioPlayer audioPlayer)
    {
        this.controller = controller;
        this.audioPlayer = audioPlayer;
    }

    public synchronized void start()
    {
        if (started)
        {
            return;
        }

        executor = Executors.newSingleThreadExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "runelite-chess-audio");
            thread.setDaemon(true);
            return thread;
        });
        started = true;
        playbackFailureLogged = false;
        rememberCurrentGameState();
        controller.addListener(this);
    }

    public void stop()
    {
        ExecutorService executorToStop;
        synchronized (this)
        {
            if (!started)
            {
                return;
            }

            started = false;
            controller.removeListener(this);
            executorToStop = executor;
            executor = null;
            previousFen = null;
            previousLastMove = null;
            previousStatus = null;
        }

        if (executorToStop != null)
        {
            executorToStop.shutdownNow();
        }
    }

    @Override
    public void onGameChanged()
    {
        Sound sound = detectGameSound();
        if (sound != null)
        {
            play(sound);
        }
    }

    private synchronized Sound detectGameSound()
    {
        if (!started)
        {
            return null;
        }

        String currentFen = controller.getPositionFen();
        String currentLastMove = controller.getLastMoveUci();
        GameStatus currentStatus = controller.getGame().getStatus();
        Sound result = null;

        if (previousStatus != null && !previousStatus.isFinished() && currentStatus.isFinished())
        {
            result = Sound.GAME_END;
        }
        else if (currentLastMove != null
            && (!Objects.equals(currentFen, previousFen)
                || !Objects.equals(currentLastMove, previousLastMove)))
        {
            result = classifyMove(previousFen, currentLastMove);
        }

        previousFen = currentFen;
        previousLastMove = currentLastMove;
        previousStatus = currentStatus;
        return result;
    }

    private Sound classifyMove(String positionBeforeMove, String moveUci)
    {
        if (positionBeforeMove == null || moveUci == null)
        {
            return Sound.MOVE;
        }

        try
        {
            ChessGame previousGame = ChessGame.fromFen(positionBeforeMove);
            Move move = Move.fromUci(moveUci);
            Piece movingPiece = previousGame.getPiece(move.getFrom());
            if (movingPiece == null)
            {
                return Sound.MOVE;
            }

            int fromFile = Square.file(move.getFrom());
            int toFile = Square.file(move.getTo());
            boolean capture = previousGame.getPiece(move.getTo()) != null
                || (movingPiece.getType() == PieceType.PAWN && fromFile != toFile);
            boolean castle = movingPiece.getType() == PieceType.KING
                && Math.abs(toFile - fromFile) == 2;

            ChessGame currentGame = controller.getGame();
            if (currentGame.isInCheck(currentGame.getSideToMove()))
            {
                return Sound.CHECK;
            }
            if (castle)
            {
                return Sound.CASTLE;
            }
            if (move.getPromotion() != null)
            {
                return Sound.PROMOTION;
            }
            if (capture)
            {
                return Sound.CAPTURE;
            }
            return Sound.MOVE;
        }
        catch (IllegalArgumentException | IllegalStateException ex)
        {
            LOG.debug("Unable to classify chess move sound for {}", moveUci, ex);
            return Sound.MOVE;
        }
    }

    private synchronized void rememberCurrentGameState()
    {
        previousFen = controller.getPositionFen();
        previousLastMove = controller.getLastMoveUci();
        previousStatus = controller.getGame().getStatus();
    }

    private void play(Sound sound)
    {
        ExecutorService currentExecutor;
        synchronized (this)
        {
            if (!started || executor == null)
            {
                return;
            }
            currentExecutor = executor;
        }

        try
        {
            currentExecutor.execute(() -> playNow(sound));
        }
        catch (RejectedExecutionException ex)
        {
            LOG.debug("Chess sound task was rejected during shutdown", ex);
        }
    }

    private void playNow(Sound sound)
    {
        try
        {
            audioPlayer.play(ChessSoundService.class, sound.resourcePath, SOUND_GAIN_DB);
        }
        catch (Exception ex)
        {
            logPlaybackFailureOnce("Unable to play chess sound " + sound.resourcePath, ex);
        }
    }

    private synchronized void logPlaybackFailureOnce(String message, Exception ex)
    {
        if (playbackFailureLogged)
        {
            return;
        }

        playbackFailureLogged = true;
        LOG.debug(message, ex);
    }
}
