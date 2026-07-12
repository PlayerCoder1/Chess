package com.playercoder1.chess;

public final class Square
{
    public static final int BOARD_SIZE = 64;

    private Square()
    {
    }

    public static int of(int file, int rank)
    {
        if (!isInside(file, rank))
        {
            throw new IllegalArgumentException("Square outside board: " + file + "," + rank);
        }
        return rank * 8 + file;
    }

    public static int file(int square)
    {
        validate(square);
        return square % 8;
    }

    public static int rank(int square)
    {
        validate(square);
        return square / 8;
    }

    public static boolean isInside(int file, int rank)
    {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    public static int fromAlgebraic(String value)
    {
        if (value == null || value.length() != 2)
        {
            throw new IllegalArgumentException("Invalid square: " + value);
        }
        int file = Character.toLowerCase(value.charAt(0)) - 'a';
        int rank = value.charAt(1) - '1';
        return of(file, rank);
    }

    public static String toAlgebraic(int square)
    {
        return String.valueOf((char) ('a' + file(square))) + (char) ('1' + rank(square));
    }

    private static void validate(int square)
    {
        if (square < 0 || square >= BOARD_SIZE)
        {
            throw new IllegalArgumentException("Invalid square index: " + square);
        }
    }
}
