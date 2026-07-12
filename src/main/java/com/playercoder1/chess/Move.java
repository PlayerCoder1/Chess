package com.playercoder1.chess;

import java.util.Objects;

public final class Move
{
    private final int from;
    private final int to;
    private final PieceType promotion;

    public Move(int from, int to)
    {
        this(from, to, null);
    }

    public Move(int from, int to, PieceType promotion)
    {
        if (from < 0 || from >= Square.BOARD_SIZE || to < 0 || to >= Square.BOARD_SIZE)
        {
            throw new IllegalArgumentException("Move square outside board");
        }
        if (promotion != null
            && promotion != PieceType.QUEEN
            && promotion != PieceType.ROOK
            && promotion != PieceType.BISHOP
            && promotion != PieceType.KNIGHT)
        {
            throw new IllegalArgumentException("Invalid promotion piece: " + promotion);
        }
        this.from = from;
        this.to = to;
        this.promotion = promotion;
    }

    public int getFrom()
    {
        return from;
    }

    public int getTo()
    {
        return to;
    }

    public PieceType getPromotion()
    {
        return promotion;
    }

    public String toUci()
    {
        String result = Square.toAlgebraic(from) + Square.toAlgebraic(to);
        if (promotion != null)
        {
            switch (promotion)
            {
                case QUEEN:
                    return result + "q";
                case ROOK:
                    return result + "r";
                case BISHOP:
                    return result + "b";
                case KNIGHT:
                    return result + "n";
                default:
                    break;
            }
        }
        return result;
    }

    public static Move fromUci(String value)
    {
        if (value == null || (value.length() != 4 && value.length() != 5))
        {
            throw new IllegalArgumentException("Invalid UCI move: " + value);
        }
        PieceType promotion = null;
        if (value.length() == 5)
        {
            switch (Character.toLowerCase(value.charAt(4)))
            {
                case 'q':
                    promotion = PieceType.QUEEN;
                    break;
                case 'r':
                    promotion = PieceType.ROOK;
                    break;
                case 'b':
                    promotion = PieceType.BISHOP;
                    break;
                case 'n':
                    promotion = PieceType.KNIGHT;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid promotion in UCI move: " + value);
            }
        }
        return new Move(
            Square.fromAlgebraic(value.substring(0, 2)),
            Square.fromAlgebraic(value.substring(2, 4)),
            promotion);
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof Move))
        {
            return false;
        }
        Move move = (Move) other;
        return from == move.from && to == move.to && promotion == move.promotion;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(from, to, promotion);
    }

    @Override
    public String toString()
    {
        return toUci();
    }
}
