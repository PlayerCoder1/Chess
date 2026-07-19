package com.playercoder1.chess;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays the bundled chess sounds without blocking RuneLite's client or Swing
 * threads. Resources are PCM WAV files so no external codec or native engine is
 * required.
 */
@Singleton
public final class ChessSoundService implements ChessGameListener
{
    private static final Logger LOG = LoggerFactory.getLogger(ChessSoundService.class);

    private enum Sound
    {
        MOVE_SELF("/move-self.wav"),
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
    private final Set<Clip> activeClips = Collections.synchronizedSet(new HashSet<>());

    private ExecutorService executor;
    private String previousFen;
    private String previousLastMove;
    private GameStatus previousStatus;
    private boolean started;
    private boolean playbackFailureLogged;

    @Inject
    public ChessSoundService(LocalChessController controller)
    {
        this.controller = controller;
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

        Clip[] clipsToClose;
        synchronized (activeClips)
        {
            clipsToClose = activeClips.toArray(new Clip[0]);
            activeClips.clear();
        }
        for (Clip clip : clipsToClose)
        {
            clip.close();
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
            return Sound.MOVE_SELF;
        }

        try
        {
            ChessGame previousGame = ChessGame.fromFen(positionBeforeMove);
            Move move = Move.fromUci(moveUci);
            Piece movingPiece = previousGame.getPiece(move.getFrom());
            if (movingPiece == null)
            {
                return Sound.MOVE_SELF;
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
            return Sound.MOVE_SELF;
        }
        catch (IllegalArgumentException | IllegalStateException ex)
        {
            LOG.debug("Unable to classify chess move sound for {}", moveUci, ex);
            return Sound.MOVE_SELF;
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
        try (InputStream raw = ChessSoundService.class.getResourceAsStream(sound.resourcePath))
        {
            if (raw == null)
            {
                logPlaybackFailureOnce("Missing chess sound resource " + sound.resourcePath, null);
                return;
            }

            try (BufferedInputStream buffered = new BufferedInputStream(raw);
                 AudioInputStream audio = AudioSystem.getAudioInputStream(buffered))
            {
                Clip clip = AudioSystem.getClip();
                clip.open(audio);
                activeClips.add(clip);
                clip.addLineListener(event ->
                {
                    if (event.getType() == LineEvent.Type.STOP
                        || event.getType() == LineEvent.Type.CLOSE)
                    {
                        activeClips.remove(clip);
                        if (clip.isOpen())
                        {
                            clip.close();
                        }
                    }
                });
                clip.setFramePosition(0);
                clip.start();
            }
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
        if (ex == null)
        {
            LOG.debug(message);
        }
        else
        {
            LOG.debug(message, ex);
        }
    }
}
