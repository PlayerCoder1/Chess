package com.playercoder1.chess;

public enum ChessColor
{
    WHITE,
    BLACK;

    public ChessColor opposite()
    {
        return this == WHITE ? BLACK : WHITE;
    }

    public String displayName()
    {
        return this == WHITE ? "White" : "Black";
    }
}
