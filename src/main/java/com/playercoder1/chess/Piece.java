package com.playercoder1.chess;

import java.util.Objects;

public final class Piece
{
    private final ChessColor color;
    private final PieceType type;

    public Piece(ChessColor color, PieceType type)
    {
        this.color = Objects.requireNonNull(color, "color");
        this.type = Objects.requireNonNull(type, "type");
    }

    public ChessColor getColor()
    {
        return color;
    }

    public PieceType getType()
    {
        return type;
    }

    public char fenCharacter()
    {
        char value;
        switch (type)
        {
            case KING:
                value = 'k';
                break;
            case QUEEN:
                value = 'q';
                break;
            case ROOK:
                value = 'r';
                break;
            case BISHOP:
                value = 'b';
                break;
            case KNIGHT:
                value = 'n';
                break;
            case PAWN:
                value = 'p';
                break;
            default:
                throw new IllegalStateException("Unknown piece type: " + type);
        }
        return color == ChessColor.WHITE ? Character.toUpperCase(value) : value;
    }

    public String symbol()
    {
        switch (type)
        {
            case KING:
                return color == ChessColor.WHITE ? "♔" : "♚";
            case QUEEN:
                return color == ChessColor.WHITE ? "♕" : "♛";
            case ROOK:
                return color == ChessColor.WHITE ? "♖" : "♜";
            case BISHOP:
                return color == ChessColor.WHITE ? "♗" : "♝";
            case KNIGHT:
                return color == ChessColor.WHITE ? "♘" : "♞";
            case PAWN:
                return color == ChessColor.WHITE ? "♙" : "♟";
            default:
                return "";
        }
    }

    public static Piece fromFenCharacter(char value)
    {
        ChessColor color = Character.isUpperCase(value) ? ChessColor.WHITE : ChessColor.BLACK;
        PieceType type;
        switch (Character.toLowerCase(value))
        {
            case 'k':
                type = PieceType.KING;
                break;
            case 'q':
                type = PieceType.QUEEN;
                break;
            case 'r':
                type = PieceType.ROOK;
                break;
            case 'b':
                type = PieceType.BISHOP;
                break;
            case 'n':
                type = PieceType.KNIGHT;
                break;
            case 'p':
                type = PieceType.PAWN;
                break;
            default:
                throw new IllegalArgumentException("Invalid FEN piece: " + value);
        }
        return new Piece(color, type);
    }
}
