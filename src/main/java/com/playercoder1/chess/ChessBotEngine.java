package com.playercoder1.chess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public final class ChessBotEngine
{
    private static final int CHECKMATE_SCORE = 100_000;
    private static final int SEARCH_INFINITY = 1_000_000;
    private static final int MAX_QUIESCENCE_DEPTH = 3;

    private final Random random;

    public ChessBotEngine()
    {
        this(new Random());
    }

    ChessBotEngine(Random random)
    {
        this.random = random;
    }

    public Move chooseMove(ChessGame position, BotDifficulty difficulty)
    {
        if (position == null || difficulty == null || position.getStatus().isFinished())
        {
            return null;
        }

        List<Move> legalMoves = position.getLegalMoves();
        if (legalMoves.isEmpty())
        {
            return null;
        }
        if (legalMoves.size() == 1)
        {
            return legalMoves.get(0);
        }

        if (difficulty == BotDifficulty.GOBLIN)
        {
            return chooseGoblinMove(position, legalMoves);
        }

        ChessColor botColor = position.getSideToMove();
        SearchContext context = new SearchContext(
            System.nanoTime() + difficulty.getThinkTimeMillis() * 1_000_000L,
            botColor);

        List<ScoredMove> bestCompleted = null;
        for (int depth = 1; depth <= difficulty.getMaximumDepth(); depth++)
        {
            try
            {
                List<ScoredMove> scored = scoreRootMoves(position, depth, context);
                if (!scored.isEmpty())
                {
                    bestCompleted = scored;
                }
            }
            catch (SearchStoppedException ignored)
            {
                break;
            }
        }

        if (bestCompleted == null || bestCompleted.isEmpty())
        {
            return chooseGoblinMove(position, legalMoves);
        }

        return chooseByDifficulty(bestCompleted, difficulty);
    }

    private Move chooseGoblinMove(ChessGame position, List<Move> legalMoves)
    {
        List<Move> captures = new ArrayList<>();
        for (Move move : legalMoves)
        {
            if (isCapture(position, move) || move.getPromotion() != null)
            {
                captures.add(move);
            }
        }

        // Goblins like shiny captures, but are otherwise gloriously random.
        if (!captures.isEmpty() && random.nextInt(100) < 45)
        {
            return captures.get(random.nextInt(captures.size()));
        }
        return legalMoves.get(random.nextInt(legalMoves.size()));
    }

    private List<ScoredMove> scoreRootMoves(ChessGame position, int depth, SearchContext context)
    {
        checkSearch(context);
        List<Move> ordered = orderedMoves(position);
        List<ScoredMove> result = new ArrayList<>(ordered.size());
        for (Move move : ordered)
        {
            checkSearch(context);
            ChessGame child = position.copy();
            child.playMove(move);
            // Search each root candidate with a full window. This preserves an
            // accurate ranking for weaker personalities that intentionally pick
            // from more than one candidate.
            int score = search(
                child,
                depth - 1,
                -SEARCH_INFINITY,
                SEARCH_INFINITY,
                context,
                1);
            result.add(new ScoredMove(move, score));
        }

        result.sort(Comparator.comparingInt(ScoredMove::getScore).reversed());
        return result;
    }

    private int search(
        ChessGame position,
        int depth,
        int alpha,
        int beta,
        SearchContext context,
        int ply)
    {
        checkSearch(context);
        if (position.getStatus().isFinished())
        {
            return terminalScore(position.getStatus(), context.botColor, ply);
        }
        if (depth <= 0)
        {
            return quiescence(position, alpha, beta, context, ply, 0);
        }

        boolean maximizing = position.getSideToMove() == context.botColor;
        List<Move> moves = orderedMoves(position);
        if (maximizing)
        {
            int best = -SEARCH_INFINITY;
            for (Move move : moves)
            {
                ChessGame child = position.copy();
                child.playMove(move);
                best = Math.max(best, search(child, depth - 1, alpha, beta, context, ply + 1));
                alpha = Math.max(alpha, best);
                if (alpha >= beta)
                {
                    break;
                }
            }
            return best;
        }

        int best = SEARCH_INFINITY;
        for (Move move : moves)
        {
            ChessGame child = position.copy();
            child.playMove(move);
            best = Math.min(best, search(child, depth - 1, alpha, beta, context, ply + 1));
            beta = Math.min(beta, best);
            if (alpha >= beta)
            {
                break;
            }
        }
        return best;
    }

    private int quiescence(
        ChessGame position,
        int alpha,
        int beta,
        SearchContext context,
        int ply,
        int quiescenceDepth)
    {
        checkSearch(context);
        int standPat = evaluate(position, context.botColor);
        boolean maximizing = position.getSideToMove() == context.botColor;

        if (quiescenceDepth >= MAX_QUIESCENCE_DEPTH)
        {
            return standPat;
        }

        if (maximizing)
        {
            if (standPat >= beta)
            {
                return beta;
            }
            alpha = Math.max(alpha, standPat);
        }
        else
        {
            if (standPat <= alpha)
            {
                return alpha;
            }
            beta = Math.min(beta, standPat);
        }

        List<Move> tacticalMoves = new ArrayList<>();
        boolean inCheck = position.isInCheck(position.getSideToMove());
        for (Move move : position.getLegalMoves())
        {
            if (inCheck || isCapture(position, move) || move.getPromotion() != null)
            {
                tacticalMoves.add(move);
            }
        }
        tacticalMoves.sort(Comparator.comparingInt(move -> -moveOrderingScore(position, move)));

        if (maximizing)
        {
            int best = standPat;
            for (Move move : tacticalMoves)
            {
                ChessGame child = position.copy();
                child.playMove(move);
                int score = child.getStatus().isFinished()
                    ? terminalScore(child.getStatus(), context.botColor, ply + 1)
                    : quiescence(child, alpha, beta, context, ply + 1, quiescenceDepth + 1);
                best = Math.max(best, score);
                alpha = Math.max(alpha, best);
                if (alpha >= beta)
                {
                    break;
                }
            }
            return best;
        }

        int best = standPat;
        for (Move move : tacticalMoves)
        {
            ChessGame child = position.copy();
            child.playMove(move);
            int score = child.getStatus().isFinished()
                ? terminalScore(child.getStatus(), context.botColor, ply + 1)
                : quiescence(child, alpha, beta, context, ply + 1, quiescenceDepth + 1);
            best = Math.min(best, score);
            beta = Math.min(beta, best);
            if (alpha >= beta)
            {
                break;
            }
        }
        return best;
    }

    private Move chooseByDifficulty(List<ScoredMove> scoredMoves, BotDifficulty difficulty)
    {
        int candidateCount = Math.min(difficulty.getCandidateMoveCount(), scoredMoves.size());
        if (candidateCount <= 1)
        {
            return scoredMoves.get(0).move;
        }

        // Most of the time the opponent chooses its best move. On an intentional
        // mistake it chooses a lower-ranked candidate, weighted toward the less
        // damaging options at intermediate levels.
        if (random.nextInt(100) >= difficulty.getMistakeChancePercent())
        {
            return scoredMoves.get(0).move;
        }

        int index = 1 + random.nextInt(candidateCount - 1);
        return scoredMoves.get(index).move;
    }

    private static List<Move> orderedMoves(ChessGame position)
    {
        List<Move> moves = new ArrayList<>(position.getLegalMoves());
        moves.sort(Comparator.comparingInt(move -> -moveOrderingScore(position, move)));
        return moves;
    }

    private static int moveOrderingScore(ChessGame position, Move move)
    {
        Piece attacker = position.getPiece(move.getFrom());
        Piece victim = position.getPiece(move.getTo());
        int score = 0;
        if (victim != null)
        {
            score += 10 * pieceValue(victim.getType());
            if (attacker != null)
            {
                score -= pieceValue(attacker.getType());
            }
        }
        if (move.getPromotion() != null)
        {
            score += pieceValue(move.getPromotion()) + 800;
        }
        return score;
    }

    private static boolean isCapture(ChessGame position, Move move)
    {
        if (position.getPiece(move.getTo()) != null)
        {
            return true;
        }

        Piece moving = position.getPiece(move.getFrom());
        return moving != null
            && moving.getType() == PieceType.PAWN
            && Square.file(move.getFrom()) != Square.file(move.getTo());
    }

    private static int evaluate(ChessGame position, ChessColor botColor)
    {
        int white = 0;
        int black = 0;
        int whiteBishops = 0;
        int blackBishops = 0;
        int nonPawnMaterial = 0;

        for (int square = 0; square < Square.BOARD_SIZE; square++)
        {
            Piece piece = position.getPiece(square);
            if (piece != null
                && piece.getType() != PieceType.PAWN
                && piece.getType() != PieceType.KING)
            {
                nonPawnMaterial += pieceValue(piece.getType());
            }
        }
        boolean endgame = nonPawnMaterial < 2_000;

        for (int square = 0; square < Square.BOARD_SIZE; square++)
        {
            Piece piece = position.getPiece(square);
            if (piece == null)
            {
                continue;
            }

            int value = pieceValue(piece.getType());
            value += positionalBonus(piece, square, endgame);

            if (piece.getColor() == ChessColor.WHITE)
            {
                white += value;
                if (piece.getType() == PieceType.BISHOP)
                {
                    whiteBishops++;
                }
            }
            else
            {
                black += value;
                if (piece.getType() == PieceType.BISHOP)
                {
                    blackBishops++;
                }
            }
        }

        if (whiteBishops >= 2)
        {
            white += 28;
        }
        if (blackBishops >= 2)
        {
            black += 28;
        }

        // A small tempo bonus helps the engine prefer active positions.
        if (position.getSideToMove() == ChessColor.WHITE)
        {
            white += 8;
        }
        else
        {
            black += 8;
        }

        int score = white - black;
        return botColor == ChessColor.WHITE ? score : -score;
    }

    private static int positionalBonus(Piece piece, int square, boolean endgame)
    {
        int file = Square.file(square);
        int rank = Square.rank(square);
        int relativeRank = piece.getColor() == ChessColor.WHITE ? rank : 7 - rank;
        int centerDistance = Math.abs(file * 2 - 7) + Math.abs(rank * 2 - 7);
        int centerBonus = 14 - centerDistance;

        switch (piece.getType())
        {
            case PAWN:
                return relativeRank * 7 + (file >= 2 && file <= 5 ? 5 : 0);
            case KNIGHT:
                return centerBonus * 4 - (relativeRank == 0 ? 12 : 0);
            case BISHOP:
                return centerBonus * 2 - (relativeRank == 0 ? 7 : 0);
            case ROOK:
                return relativeRank == 6 ? 18 : 0;
            case QUEEN:
                return centerBonus;
            case KING:
                if (endgame)
                {
                    return centerBonus * 3;
                }
                boolean castledFile = file == 2 || file == 6;
                return castledFile && relativeRank == 0 ? 30 : -relativeRank * 8;
            default:
                return 0;
        }
    }

    private static int terminalScore(GameStatus status, ChessColor botColor, int ply)
    {
        switch (status)
        {
            case WHITE_WINS_CHECKMATE:
            case WHITE_WINS_RESIGNATION:
            case WHITE_WINS_TIMEOUT:
                return botColor == ChessColor.WHITE
                    ? CHECKMATE_SCORE - ply
                    : -CHECKMATE_SCORE + ply;
            case BLACK_WINS_CHECKMATE:
            case BLACK_WINS_RESIGNATION:
            case BLACK_WINS_TIMEOUT:
                return botColor == ChessColor.BLACK
                    ? CHECKMATE_SCORE - ply
                    : -CHECKMATE_SCORE + ply;
            default:
                return 0;
        }
    }

    private static int pieceValue(PieceType type)
    {
        switch (type)
        {
            case PAWN:
                return 100;
            case KNIGHT:
                return 320;
            case BISHOP:
                return 335;
            case ROOK:
                return 500;
            case QUEEN:
                return 900;
            case KING:
            default:
                return 0;
        }
    }

    private static void checkSearch(SearchContext context)
    {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= context.deadlineNanos)
        {
            throw SearchStoppedException.INSTANCE;
        }
    }

    private static final class SearchContext
    {
        private final long deadlineNanos;
        private final ChessColor botColor;
        private SearchContext(long deadlineNanos, ChessColor botColor)
        {
            this.deadlineNanos = deadlineNanos;
            this.botColor = botColor;
        }
    }

    private static final class ScoredMove
    {
        private final Move move;
        private final int score;

        private ScoredMove(Move move, int score)
        {
            this.move = move;
            this.score = score;
        }

        private int getScore()
        {
            return score;
        }
    }

    private static final class SearchStoppedException extends RuntimeException
    {
        private static final SearchStoppedException INSTANCE = new SearchStoppedException();

        private SearchStoppedException()
        {
            super(null, null, false, false);
        }
    }
}
