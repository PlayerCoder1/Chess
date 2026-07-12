package com.playercoder1.chess;

public enum GameStatus
{
    ACTIVE,
    WHITE_WINS_CHECKMATE,
    BLACK_WINS_CHECKMATE,
    WHITE_WINS_RESIGNATION,
    BLACK_WINS_RESIGNATION,
    WHITE_WINS_TIMEOUT,
    BLACK_WINS_TIMEOUT,
    DRAW_STALEMATE,
    DRAW_AGREEMENT,
    DRAW_FIFTY_MOVE,
    DRAW_INSUFFICIENT_MATERIAL;

    public boolean isFinished()
    {
        return this != ACTIVE;
    }
}
