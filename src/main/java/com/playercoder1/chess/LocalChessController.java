package com.playercoder1.chess;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Owns the current rules position, chess clock and user-facing game actions.
 * Multiplayer reuses this state and restores host-authoritative snapshots on
 * the guest client.
 */
@Singleton
public final class LocalChessController
{
    private final List<ChessGameListener> listeners = new ArrayList<>();
    private ChessGame game;
    private ChessClock clock;
    private int initialMinutes;
    private int incrementSeconds;
    private ChessColor drawOfferedBy;
    private String notice;

    @Inject
    public LocalChessController()
    {
        this(ChessTimeControl.DEFAULT_MINUTES, ChessTimeControl.DEFAULT_INCREMENT_SECONDS);
    }

    public LocalChessController(int initialMinutes, int incrementSeconds)
    {
        configureTimeControl(initialMinutes, incrementSeconds);
        newGame();
    }

    public void configureTimeControl(int initialMinutes, int incrementSeconds)
    {
        ChessTimeControl.validate(initialMinutes, incrementSeconds);
        this.initialMinutes = initialMinutes;
        this.incrementSeconds = incrementSeconds;
    }

    public void newGame()
    {
        game = new ChessGame();
        clock = new ChessClock(initialMinutes, incrementSeconds);
        drawOfferedBy = null;
        notice = "White to move. Clock starts after White's first move.";
        notifyListeners();
    }

    public ChessGame getGame()
    {
        return game;
    }

    public ChessClock getClock()
    {
        return clock;
    }

    public ChessColor getDrawOfferedBy()
    {
        return drawOfferedBy;
    }

    public String getNotice()
    {
        return notice;
    }

    public String getPositionFen()
    {
        return game.toFen();
    }

    public String getLastMoveUci()
    {
        Move lastMove = game.getLastMove();
        return lastMove == null ? null : lastMove.toUci();
    }

    public void setNotice(String notice)
    {
        this.notice = notice == null ? "" : notice;
        notifyListeners();
    }

    public boolean playMove(Move move)
    {
        ChessColor movedColor = game.getSideToMove();
        if (expireClockIfNeeded())
        {
            return false;
        }
        if (!game.playMove(move))
        {
            notice = "That move is not legal.";
            notifyListeners();
            return false;
        }

        clock.onMoveCompleted(movedColor);
        if (drawOfferedBy != null && movedColor != drawOfferedBy)
        {
            drawOfferedBy = null;
        }
        if (game.getStatus().isFinished())
        {
            clock.stop();
        }
        notice = describePosition();
        notifyListeners();
        return true;
    }

    public void offerAcceptOrCancelDraw()
    {
        handleDrawAction(game.getSideToMove());
    }

    public void handleDrawAction(ChessColor actor)
    {
        if (actor == null || game.getStatus().isFinished())
        {
            return;
        }

        if (drawOfferedBy == null)
        {
            drawOfferedBy = actor;
            notice = actor.displayName() + " offered a draw.";
        }
        else if (drawOfferedBy == actor)
        {
            drawOfferedBy = null;
            notice = "Draw offer cancelled.";
        }
        else
        {
            game.agreeDraw();
            clock.stop();
            drawOfferedBy = null;
            notice = "Draw by agreement.";
        }
        notifyListeners();
    }

    public void resignCurrentPlayer()
    {
        resign(game.getSideToMove());
    }

    public void resign(ChessColor resigningColor)
    {
        if (resigningColor == null || game.getStatus().isFinished())
        {
            return;
        }
        game.resign(resigningColor);
        clock.stop();
        drawOfferedBy = null;
        notice = resigningColor.displayName() + " resigned.";
        notifyListeners();
    }

    public void tick()
    {
        if (game.getStatus().isFinished() || !clock.isRunning())
        {
            return;
        }
        expireClockIfNeeded();
        notifyListeners();
    }

    /**
     * Advances a guest's local clock display without deciding the authoritative
     * result. The host will publish the final timeout state.
     */
    public void tickDisplayOnly()
    {
        if (!clock.isRunning() || game.getStatus().isFinished())
        {
            return;
        }
        clock.getRemainingMillis(ChessColor.WHITE);
        clock.getRemainingMillis(ChessColor.BLACK);
        notifyListeners();
    }

    public void restoreNetworkState(
        int initialMinutes,
        int incrementSeconds,
        String positionFen,
        String lastMoveUci,
        long whiteMillis,
        long blackMillis,
        ChessColor activeColor,
        boolean clockRunning,
        ChessColor offeredBy,
        GameStatus authoritativeStatus,
        String authoritativeNotice)
    {
        configureTimeControl(initialMinutes, incrementSeconds);
        if (positionFen == null || positionFen.trim().isEmpty())
        {
            throw new IllegalArgumentException("Network snapshot did not contain a chess position");
        }

        game = ChessGame.fromFen(positionFen);
        if (lastMoveUci != null && !lastMoveUci.trim().isEmpty())
        {
            game.restoreLastMoveForDisplay(Move.fromUci(lastMoveUci.trim()));
        }

        clock = new ChessClock(initialMinutes, incrementSeconds);
        applyAuthoritativeStatus(authoritativeStatus);
        clock.restore(whiteMillis, blackMillis, activeColor, clockRunning && !game.getStatus().isFinished());
        if (game.getStatus().isFinished())
        {
            clock.stop();
        }
        drawOfferedBy = game.getStatus().isFinished() ? null : offeredBy;
        notice = authoritativeNotice == null || authoritativeNotice.trim().isEmpty()
            ? describePosition()
            : authoritativeNotice.trim();
        notifyListeners();
    }

    public void addListener(ChessGameListener listener)
    {
        if (listener != null && !listeners.contains(listener))
        {
            listeners.add(listener);
        }
    }

    public void removeListener(ChessGameListener listener)
    {
        listeners.remove(listener);
    }

    public String describePosition()
    {
        GameStatus status = game.getStatus();
        switch (status)
        {
            case ACTIVE:
                String text = game.getSideToMove().displayName() + " to move";
                if (game.isInCheck(game.getSideToMove()))
                {
                    text += " — check";
                }
                return text + ".";
            case WHITE_WINS_CHECKMATE:
                return "Checkmate — White wins.";
            case BLACK_WINS_CHECKMATE:
                return "Checkmate — Black wins.";
            case WHITE_WINS_RESIGNATION:
                return "Black resigned — White wins.";
            case BLACK_WINS_RESIGNATION:
                return "White resigned — Black wins.";
            case WHITE_WINS_TIMEOUT:
                return "Black ran out of time — White wins.";
            case BLACK_WINS_TIMEOUT:
                return "White ran out of time — Black wins.";
            case DRAW_STALEMATE:
                return "Draw by stalemate.";
            case DRAW_AGREEMENT:
                return "Draw by agreement.";
            case DRAW_FIFTY_MOVE:
                return "Draw by the fifty-move rule.";
            case DRAW_INSUFFICIENT_MATERIAL:
                return "Draw by insufficient material.";
            default:
                return status.name();
        }
    }

    private boolean expireClockIfNeeded()
    {
        ChessColor expiredColor = clock.getExpiredColor();
        if (expiredColor == null)
        {
            return false;
        }

        game.timeout(expiredColor);
        clock.stop();
        drawOfferedBy = null;
        notice = expiredColor.displayName() + " ran out of time.";
        return true;
    }

    private void applyAuthoritativeStatus(GameStatus authoritativeStatus)
    {
        if (authoritativeStatus == null || authoritativeStatus == game.getStatus())
        {
            return;
        }

        switch (authoritativeStatus)
        {
            case WHITE_WINS_RESIGNATION:
                game.resign(ChessColor.BLACK);
                break;
            case BLACK_WINS_RESIGNATION:
                game.resign(ChessColor.WHITE);
                break;
            case WHITE_WINS_TIMEOUT:
                game.timeout(ChessColor.BLACK);
                break;
            case BLACK_WINS_TIMEOUT:
                game.timeout(ChessColor.WHITE);
                break;
            case DRAW_AGREEMENT:
                game.agreeDraw();
                break;
            default:
                // Board-derived outcomes are reconstructed directly from the FEN snapshot.
                break;
        }
    }

    private void notifyListeners()
    {
        for (ChessGameListener listener : new ArrayList<>(listeners))
        {
            listener.onGameChanged();
        }
    }
}
