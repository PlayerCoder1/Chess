package com.playercoder1.chess;

import java.util.Arrays;

/** Shared validation and presets for local and private chess clocks. */
public final class ChessTimeControl
{
    public static final int DEFAULT_MINUTES = 10;
    public static final int DEFAULT_INCREMENT_SECONDS = 5;

    private static final Integer[] MINUTE_OPTIONS = {1, 3, 5, 10, 15};
    private static final Integer[] INCREMENT_OPTIONS = {0, 1, 2, 3, 5, 10, 15, 30};

    private ChessTimeControl()
    {
    }

    public static Integer[] minuteOptions()
    {
        return Arrays.copyOf(MINUTE_OPTIONS, MINUTE_OPTIONS.length);
    }

    public static Integer[] incrementOptions()
    {
        return Arrays.copyOf(INCREMENT_OPTIONS, INCREMENT_OPTIONS.length);
    }

    public static int normalizeMinutes(int minutes)
    {
        return contains(MINUTE_OPTIONS, minutes) ? minutes : DEFAULT_MINUTES;
    }

    public static int normalizeIncrement(int incrementSeconds)
    {
        return contains(INCREMENT_OPTIONS, incrementSeconds)
            ? incrementSeconds
            : DEFAULT_INCREMENT_SECONDS;
    }

    public static boolean isSupported(int minutes, int incrementSeconds)
    {
        return contains(MINUTE_OPTIONS, minutes) && contains(INCREMENT_OPTIONS, incrementSeconds);
    }

    public static void validate(int minutes, int incrementSeconds)
    {
        if (!contains(MINUTE_OPTIONS, minutes))
        {
            throw new IllegalArgumentException("Choose 1, 3, 5, 10, or 15 minutes.");
        }
        if (!contains(INCREMENT_OPTIONS, incrementSeconds))
        {
            throw new IllegalArgumentException("Choose a 0, 1, 2, 3, 5, 10, 15, or 30-second increment.");
        }
    }

    private static boolean contains(Integer[] options, int value)
    {
        for (int option : options)
        {
            if (option == value)
            {
                return true;
            }
        }
        return false;
    }
}
