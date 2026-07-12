package com.playercoder1.chess;

import java.util.Locale;

/** Monotonic two-player Fischer clock. */
public final class ChessClock
{
    private final long initialMillis;
    private final long incrementMillis;
    private long whiteMillis;
    private long blackMillis;
    private ChessColor activeColor;
    private long lastUpdateNanos;
    private boolean running;

    public ChessClock(int initialMinutes, int incrementSeconds)
    {
        ChessTimeControl.validate(initialMinutes, incrementSeconds);
        initialMillis = initialMinutes * 60_000L;
        incrementMillis = incrementSeconds * 1_000L;
        whiteMillis = initialMillis;
        blackMillis = initialMillis;
    }

    /**
     * Restores an authoritative clock snapshot received from the host.
     */
    public void restore(long whiteMillis, long blackMillis, ChessColor activeColor, boolean running)
    {
        this.whiteMillis = Math.max(0L, whiteMillis);
        this.blackMillis = Math.max(0L, blackMillis);
        this.activeColor = activeColor;
        this.running = running && activeColor != null;
        this.lastUpdateNanos = System.nanoTime();
    }

    public void onMoveCompleted(ChessColor movedColor)
    {
        if (movedColor == null)
        {
            throw new IllegalArgumentException("Moved color cannot be null");
        }

        update();
        addTime(movedColor, incrementMillis);
        activeColor = movedColor.opposite();
        lastUpdateNanos = System.nanoTime();
        running = true;
    }

    public void stop()
    {
        update();
        running = false;
        activeColor = null;
    }

    public long getRemainingMillis(ChessColor color)
    {
        if (color == null)
        {
            throw new IllegalArgumentException("Color cannot be null");
        }
        update();
        return color == ChessColor.WHITE ? whiteMillis : blackMillis;
    }

    public ChessColor getActiveColor()
    {
        update();
        return activeColor;
    }

    public boolean isRunning()
    {
        return running;
    }

    public ChessColor getExpiredColor()
    {
        update();
        if (whiteMillis <= 0)
        {
            return ChessColor.WHITE;
        }
        if (blackMillis <= 0)
        {
            return ChessColor.BLACK;
        }
        return null;
    }

    public static String format(long millis)
    {
        long safeMillis = Math.max(0L, millis);
        long totalSeconds = (safeMillis + 999L) / 1_000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private void update()
    {
        if (!running || activeColor == null)
        {
            return;
        }

        long now = System.nanoTime();
        long elapsedMillis = Math.max(0L, (now - lastUpdateNanos) / 1_000_000L);
        if (elapsedMillis == 0L)
        {
            return;
        }

        if (activeColor == ChessColor.WHITE)
        {
            whiteMillis = Math.max(0L, whiteMillis - elapsedMillis);
        }
        else
        {
            blackMillis = Math.max(0L, blackMillis - elapsedMillis);
        }
        lastUpdateNanos = now;
    }

    private void addTime(ChessColor color, long millis)
    {
        if (color == ChessColor.WHITE)
        {
            whiteMillis += millis;
        }
        else
        {
            blackMillis += millis;
        }
    }
}
