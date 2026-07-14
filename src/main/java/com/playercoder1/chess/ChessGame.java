package com.playercoder1.chess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChessGame
{
    public static final String STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private static final int[] PAWN_FILE_OFFSETS = {-1, 1};
    private static final int[][] KNIGHT_OFFSETS = {
        {1, 2}, {2, 1}, {2, -1}, {1, -2},
        {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}
    };
    private static final int[][] BISHOP_DIRECTIONS = {
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int[][] ROOK_DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    private static final int[][] QUEEN_DIRECTIONS = {
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private Piece[] board;
    private ChessColor sideToMove;
    private boolean whiteKingSide;
    private boolean whiteQueenSide;
    private boolean blackKingSide;
    private boolean blackQueenSide;
    private int enPassantSquare;
    private int halfmoveClock;
    private int fullmoveNumber;
    private GameStatus status;
    private Move lastMove;

    public ChessGame()
    {
        loadFen(STARTING_FEN);
    }

    private ChessGame(ChessGame source)
    {
        this.board = source.board.clone();
        this.sideToMove = source.sideToMove;
        this.whiteKingSide = source.whiteKingSide;
        this.whiteQueenSide = source.whiteQueenSide;
        this.blackKingSide = source.blackKingSide;
        this.blackQueenSide = source.blackQueenSide;
        this.enPassantSquare = source.enPassantSquare;
        this.halfmoveClock = source.halfmoveClock;
        this.fullmoveNumber = source.fullmoveNumber;
        this.status = source.status;
        this.lastMove = source.lastMove;
    }

    public static ChessGame fromFen(String fen)
    {
        ChessGame game = new ChessGame();
        game.loadFen(fen);
        game.updateTerminalStatus();
        return game;
    }

    public ChessGame copy()
    {
        return new ChessGame(this);
    }

    public Piece getPiece(int square)
    {
        if (square < 0 || square >= Square.BOARD_SIZE)
        {
            throw new IllegalArgumentException("Invalid square: " + square);
        }
        return board[square];
    }

    public ChessColor getSideToMove()
    {
        return sideToMove;
    }

    public GameStatus getStatus()
    {
        return status;
    }

    public Move getLastMove()
    {
        return lastMove;
    }

    void restoreLastMoveForDisplay(Move move)
    {
        lastMove = move;
    }

    public boolean isInCheck(ChessColor color)
    {
        int kingSquare = findKing(color);
        return kingSquare >= 0 && isSquareAttacked(kingSquare, color.opposite());
    }

    public List<Move> getLegalMoves()
    {
        if (status.isFinished())
        {
            return Collections.emptyList();
        }
        return generateLegalMoves(sideToMove);
    }

    public List<Move> getLegalMovesFrom(int from)
    {
        if (from < 0 || from >= Square.BOARD_SIZE || status.isFinished())
        {
            return Collections.emptyList();
        }

        List<Move> result = new ArrayList<>();
        for (Move move : generateLegalMoves(sideToMove))
        {
            if (move.getFrom() == from)
            {
                result.add(move);
            }
        }
        return result;
    }

    public boolean playUci(String uci)
    {
        return playMove(Move.fromUci(uci));
    }

    public boolean playMove(Move requestedMove)
    {
        if (requestedMove == null || status.isFinished())
        {
            return false;
        }

        Move selected = null;
        for (Move legalMove : generateLegalMoves(sideToMove))
        {
            if (legalMove.getFrom() != requestedMove.getFrom() || legalMove.getTo() != requestedMove.getTo())
            {
                continue;
            }

            if (legalMove.getPromotion() == requestedMove.getPromotion())
            {
                selected = legalMove;
                break;
            }

            if (requestedMove.getPromotion() == null && legalMove.getPromotion() == PieceType.QUEEN)
            {
                selected = legalMove;
            }
        }

        if (selected == null)
        {
            return false;
        }

        applyMoveUnchecked(selected);
        updateTerminalStatus();
        return true;
    }

    public void resign(ChessColor color)
    {
        if (!status.isFinished())
        {
            status = color == ChessColor.WHITE
                ? GameStatus.BLACK_WINS_RESIGNATION
                : GameStatus.WHITE_WINS_RESIGNATION;
        }
    }

    public void timeout(ChessColor color)
    {
        if (!status.isFinished())
        {
            status = color == ChessColor.WHITE
                ? GameStatus.BLACK_WINS_TIMEOUT
                : GameStatus.WHITE_WINS_TIMEOUT;
        }
    }

    public void agreeDraw()
    {
        if (!status.isFinished())
        {
            status = GameStatus.DRAW_AGREEMENT;
        }
    }

    public String toFen()
    {
        StringBuilder result = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--)
        {
            int empty = 0;
            for (int file = 0; file < 8; file++)
            {
                Piece piece = board[Square.of(file, rank)];
                if (piece == null)
                {
                    empty++;
                }
                else
                {
                    if (empty > 0)
                    {
                        result.append(empty);
                        empty = 0;
                    }
                    result.append(piece.fenCharacter());
                }
            }
            if (empty > 0)
            {
                result.append(empty);
            }
            if (rank > 0)
            {
                result.append('/');
            }
        }

        result.append(sideToMove == ChessColor.WHITE ? " w " : " b ");
        StringBuilder castling = new StringBuilder();
        if (whiteKingSide)
        {
            castling.append('K');
        }
        if (whiteQueenSide)
        {
            castling.append('Q');
        }
        if (blackKingSide)
        {
            castling.append('k');
        }
        if (blackQueenSide)
        {
            castling.append('q');
        }
        result.append(castling.length() == 0 ? "-" : castling.toString());
        result.append(' ');
        result.append(enPassantSquare < 0 ? "-" : Square.toAlgebraic(enPassantSquare));
        result.append(' ').append(halfmoveClock);
        result.append(' ').append(fullmoveNumber);
        return result.toString();
    }

    private void loadFen(String fen)
    {
        if (fen == null)
        {
            throw new IllegalArgumentException("FEN cannot be null");
        }

        String[] fields = fen.trim().split("\\s+");
        if (fields.length != 6)
        {
            throw new IllegalArgumentException("FEN must contain six fields: " + fen);
        }

        Piece[] loadedBoard = new Piece[Square.BOARD_SIZE];
        String[] ranks = fields[0].split("/");
        if (ranks.length != 8)
        {
            throw new IllegalArgumentException("FEN must contain eight ranks: " + fen);
        }

        for (int fenRank = 0; fenRank < 8; fenRank++)
        {
            int boardRank = 7 - fenRank;
            int file = 0;
            for (int i = 0; i < ranks[fenRank].length(); i++)
            {
                char value = ranks[fenRank].charAt(i);
                if (Character.isDigit(value))
                {
                    int empty = value - '0';
                    if (empty < 1 || empty > 8)
                    {
                        throw new IllegalArgumentException("Invalid empty count in FEN: " + value);
                    }
                    file += empty;
                }
                else
                {
                    if (file >= 8)
                    {
                        throw new IllegalArgumentException("Too many files in FEN rank");
                    }
                    loadedBoard[Square.of(file, boardRank)] = Piece.fromFenCharacter(value);
                    file++;
                }
            }
            if (file != 8)
            {
                throw new IllegalArgumentException("FEN rank does not contain eight files: " + ranks[fenRank]);
            }
        }

        int whiteKings = 0;
        int blackKings = 0;
        for (Piece piece : loadedBoard)
        {
            if (piece != null && piece.getType() == PieceType.KING)
            {
                if (piece.getColor() == ChessColor.WHITE)
                {
                    whiteKings++;
                }
                else
                {
                    blackKings++;
                }
            }
        }
        if (whiteKings != 1 || blackKings != 1)
        {
            throw new IllegalArgumentException("FEN must contain exactly one king for each color");
        }

        ChessColor loadedSide;
        if ("w".equals(fields[1]))
        {
            loadedSide = ChessColor.WHITE;
        }
        else if ("b".equals(fields[1]))
        {
            loadedSide = ChessColor.BLACK;
        }
        else
        {
            throw new IllegalArgumentException("Invalid side-to-move field: " + fields[1]);
        }

        String castling = fields[2];
        if (!"-".equals(castling) && !castling.matches("K?Q?k?q?"))
        {
            throw new IllegalArgumentException("Invalid castling field: " + castling);
        }

        int loadedEnPassant = "-".equals(fields[3]) ? -1 : Square.fromAlgebraic(fields[3]);
        int loadedHalfmove;
        int loadedFullmove;
        try
        {
            loadedHalfmove = Integer.parseInt(fields[4]);
            loadedFullmove = Integer.parseInt(fields[5]);
        }
        catch (NumberFormatException ex)
        {
            throw new IllegalArgumentException("Invalid move counters in FEN", ex);
        }
        if (loadedHalfmove < 0 || loadedFullmove < 1)
        {
            throw new IllegalArgumentException("Invalid move counters in FEN");
        }

        board = loadedBoard;
        sideToMove = loadedSide;
        whiteKingSide = castling.contains("K");
        whiteQueenSide = castling.contains("Q");
        blackKingSide = castling.contains("k");
        blackQueenSide = castling.contains("q");
        enPassantSquare = loadedEnPassant;
        halfmoveClock = loadedHalfmove;
        fullmoveNumber = loadedFullmove;
        status = GameStatus.ACTIVE;
        lastMove = null;
    }

    private List<Move> generateLegalMoves(ChessColor color)
    {
        List<Move> legalMoves = new ArrayList<>();
        for (Move move : generatePseudoLegalMoves(color))
        {
            ChessGame copy = new ChessGame(this);
            copy.applyMoveUnchecked(move);
            if (!copy.isInCheck(color))
            {
                legalMoves.add(move);
            }
        }
        return legalMoves;
    }

    private List<Move> generatePseudoLegalMoves(ChessColor color)
    {
        List<Move> moves = new ArrayList<>();
        for (int square = 0; square < Square.BOARD_SIZE; square++)
        {
            Piece piece = board[square];
            if (piece == null || piece.getColor() != color)
            {
                continue;
            }

            switch (piece.getType())
            {
                case PAWN:
                    addPawnMoves(moves, square, color);
                    break;
                case KNIGHT:
                    addKnightMoves(moves, square, color);
                    break;
                case BISHOP:
                    addSlidingMoves(moves, square, color, BISHOP_DIRECTIONS);
                    break;
                case ROOK:
                    addSlidingMoves(moves, square, color, ROOK_DIRECTIONS);
                    break;
                case QUEEN:
                    addSlidingMoves(moves, square, color, QUEEN_DIRECTIONS);
                    break;
                case KING:
                    addKingMoves(moves, square, color);
                    break;
                default:
                    throw new IllegalStateException("Unknown piece type: " + piece.getType());
            }
        }
        return moves;
    }

    private void addPawnMoves(List<Move> moves, int square, ChessColor color)
    {
        int file = Square.file(square);
        int rank = Square.rank(square);
        int direction = color == ChessColor.WHITE ? 1 : -1;
        int startRank = color == ChessColor.WHITE ? 1 : 6;
        int promotionRank = color == ChessColor.WHITE ? 7 : 0;

        int oneRank = rank + direction;
        if (Square.isInside(file, oneRank))
        {
            int oneStep = Square.of(file, oneRank);
            if (board[oneStep] == null)
            {
                addPawnMove(moves, square, oneStep, oneRank == promotionRank);
                int twoRank = rank + direction * 2;
                if (rank == startRank)
                {
                    int twoStep = Square.of(file, twoRank);
                    if (board[twoStep] == null)
                    {
                        moves.add(new Move(square, twoStep));
                    }
                }
            }
        }

        for (int fileOffset : PAWN_FILE_OFFSETS)
        {
            int targetFile = file + fileOffset;
            int targetRank = rank + direction;
            if (!Square.isInside(targetFile, targetRank))
            {
                continue;
            }

            int target = Square.of(targetFile, targetRank);
            Piece captured = board[target];
            if (captured != null && captured.getColor() != color)
            {
                addPawnMove(moves, square, target, targetRank == promotionRank);
            }
            else if (target == enPassantSquare)
            {
                int capturedSquare = Square.of(targetFile, rank);
                Piece enPassantPawn = board[capturedSquare];
                if (enPassantPawn != null
                    && enPassantPawn.getColor() != color
                    && enPassantPawn.getType() == PieceType.PAWN)
                {
                    moves.add(new Move(square, target));
                }
            }
        }
    }

    private static void addPawnMove(List<Move> moves, int from, int to, boolean promotion)
    {
        if (!promotion)
        {
            moves.add(new Move(from, to));
            return;
        }
        moves.add(new Move(from, to, PieceType.QUEEN));
        moves.add(new Move(from, to, PieceType.ROOK));
        moves.add(new Move(from, to, PieceType.BISHOP));
        moves.add(new Move(from, to, PieceType.KNIGHT));
    }

    private void addKnightMoves(List<Move> moves, int square, ChessColor color)
    {
        int file = Square.file(square);
        int rank = Square.rank(square);
        for (int[] offset : KNIGHT_OFFSETS)
        {
            addStepMove(moves, square, file + offset[0], rank + offset[1], color);
        }
    }

    private void addKingMoves(List<Move> moves, int square, ChessColor color)
    {
        int file = Square.file(square);
        int rank = Square.rank(square);
        for (int fileOffset = -1; fileOffset <= 1; fileOffset++)
        {
            for (int rankOffset = -1; rankOffset <= 1; rankOffset++)
            {
                if (fileOffset != 0 || rankOffset != 0)
                {
                    addStepMove(moves, square, file + fileOffset, rank + rankOffset, color);
                }
            }
        }

        int homeRank = color == ChessColor.WHITE ? 0 : 7;
        if (square != Square.of(4, homeRank) || isInCheck(color))
        {
            return;
        }

        boolean canKingSide = color == ChessColor.WHITE ? whiteKingSide : blackKingSide;
        if (canKingSide
            && board[Square.of(5, homeRank)] == null
            && board[Square.of(6, homeRank)] == null
            && isRookAt(Square.of(7, homeRank), color)
            && !isSquareAttacked(Square.of(5, homeRank), color.opposite())
            && !isSquareAttacked(Square.of(6, homeRank), color.opposite()))
        {
            moves.add(new Move(square, Square.of(6, homeRank)));
        }

        boolean canQueenSide = color == ChessColor.WHITE ? whiteQueenSide : blackQueenSide;
        if (canQueenSide
            && board[Square.of(1, homeRank)] == null
            && board[Square.of(2, homeRank)] == null
            && board[Square.of(3, homeRank)] == null
            && isRookAt(Square.of(0, homeRank), color)
            && !isSquareAttacked(Square.of(3, homeRank), color.opposite())
            && !isSquareAttacked(Square.of(2, homeRank), color.opposite()))
        {
            moves.add(new Move(square, Square.of(2, homeRank)));
        }
    }

    private boolean isRookAt(int square, ChessColor color)
    {
        Piece piece = board[square];
        return piece != null && piece.getColor() == color && piece.getType() == PieceType.ROOK;
    }

    private void addStepMove(List<Move> moves, int from, int targetFile, int targetRank, ChessColor color)
    {
        if (!Square.isInside(targetFile, targetRank))
        {
            return;
        }
        int target = Square.of(targetFile, targetRank);
        Piece occupant = board[target];
        if (occupant == null || occupant.getColor() != color)
        {
            moves.add(new Move(from, target));
        }
    }

    private void addSlidingMoves(List<Move> moves, int square, ChessColor color, int[][] directions)
    {
        int startFile = Square.file(square);
        int startRank = Square.rank(square);
        for (int[] direction : directions)
        {
            int file = startFile + direction[0];
            int rank = startRank + direction[1];
            while (Square.isInside(file, rank))
            {
                int target = Square.of(file, rank);
                Piece occupant = board[target];
                if (occupant == null)
                {
                    moves.add(new Move(square, target));
                }
                else
                {
                    if (occupant.getColor() != color)
                    {
                        moves.add(new Move(square, target));
                    }
                    break;
                }
                file += direction[0];
                rank += direction[1];
            }
        }
    }

    private boolean isSquareAttacked(int square, ChessColor attacker)
    {
        int targetFile = Square.file(square);
        int targetRank = Square.rank(square);

        int pawnSourceRank = targetRank + (attacker == ChessColor.WHITE ? -1 : 1);
        for (int fileOffset : PAWN_FILE_OFFSETS)
        {
            int pawnFile = targetFile + fileOffset;
            if (Square.isInside(pawnFile, pawnSourceRank))
            {
                Piece piece = board[Square.of(pawnFile, pawnSourceRank)];
                if (piece != null && piece.getColor() == attacker && piece.getType() == PieceType.PAWN)
                {
                    return true;
                }
            }
        }

        for (int[] offset : KNIGHT_OFFSETS)
        {
            int file = targetFile + offset[0];
            int rank = targetRank + offset[1];
            if (Square.isInside(file, rank))
            {
                Piece piece = board[Square.of(file, rank)];
                if (piece != null && piece.getColor() == attacker && piece.getType() == PieceType.KNIGHT)
                {
                    return true;
                }
            }
        }

        if (attackedAlong(square, attacker, ROOK_DIRECTIONS, PieceType.ROOK))
        {
            return true;
        }
        if (attackedAlong(square, attacker, BISHOP_DIRECTIONS, PieceType.BISHOP))
        {
            return true;
        }

        for (int fileOffset = -1; fileOffset <= 1; fileOffset++)
        {
            for (int rankOffset = -1; rankOffset <= 1; rankOffset++)
            {
                if (fileOffset == 0 && rankOffset == 0)
                {
                    continue;
                }
                int file = targetFile + fileOffset;
                int rank = targetRank + rankOffset;
                if (Square.isInside(file, rank))
                {
                    Piece piece = board[Square.of(file, rank)];
                    if (piece != null && piece.getColor() == attacker && piece.getType() == PieceType.KING)
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean attackedAlong(int square, ChessColor attacker, int[][] directions, PieceType linePiece)
    {
        int targetFile = Square.file(square);
        int targetRank = Square.rank(square);
        for (int[] direction : directions)
        {
            int file = targetFile + direction[0];
            int rank = targetRank + direction[1];
            while (Square.isInside(file, rank))
            {
                Piece piece = board[Square.of(file, rank)];
                if (piece != null)
                {
                    if (piece.getColor() == attacker
                        && (piece.getType() == linePiece || piece.getType() == PieceType.QUEEN))
                    {
                        return true;
                    }
                    break;
                }
                file += direction[0];
                rank += direction[1];
            }
        }
        return false;
    }

    private int findKing(ChessColor color)
    {
        for (int square = 0; square < Square.BOARD_SIZE; square++)
        {
            Piece piece = board[square];
            if (piece != null && piece.getColor() == color && piece.getType() == PieceType.KING)
            {
                return square;
            }
        }
        return -1;
    }

    private void applyMoveUnchecked(Move move)
    {
        Piece movingPiece = board[move.getFrom()];
        if (movingPiece == null)
        {
            throw new IllegalStateException("No piece on move source: " + move);
        }

        Piece capturedPiece = board[move.getTo()];
        int fromFile = Square.file(move.getFrom());
        int fromRank = Square.rank(move.getFrom());
        int toFile = Square.file(move.getTo());
        int toRank = Square.rank(move.getTo());
        boolean pawnMove = movingPiece.getType() == PieceType.PAWN;
        boolean enPassantCapture = pawnMove
            && capturedPiece == null
            && fromFile != toFile
            && move.getTo() == enPassantSquare;

        if (enPassantCapture)
        {
            int capturedSquare = Square.of(toFile, fromRank);
            capturedPiece = board[capturedSquare];
            board[capturedSquare] = null;
        }

        updateCastlingRightsForMove(move.getFrom(), move.getTo(), movingPiece, capturedPiece);

        board[move.getFrom()] = null;
        Piece placedPiece = move.getPromotion() == null
            ? movingPiece
            : new Piece(movingPiece.getColor(), move.getPromotion());
        board[move.getTo()] = placedPiece;

        if (movingPiece.getType() == PieceType.KING && Math.abs(toFile - fromFile) == 2)
        {
            int rookFromFile = toFile == 6 ? 7 : 0;
            int rookToFile = toFile == 6 ? 5 : 3;
            int rank = fromRank;
            int rookFrom = Square.of(rookFromFile, rank);
            int rookTo = Square.of(rookToFile, rank);
            board[rookTo] = board[rookFrom];
            board[rookFrom] = null;
        }

        enPassantSquare = -1;
        if (pawnMove && Math.abs(toRank - fromRank) == 2)
        {
            enPassantSquare = Square.of(fromFile, (fromRank + toRank) / 2);
        }

        if (pawnMove || capturedPiece != null)
        {
            halfmoveClock = 0;
        }
        else
        {
            halfmoveClock++;
        }

        if (sideToMove == ChessColor.BLACK)
        {
            fullmoveNumber++;
        }
        lastMove = move;
        sideToMove = sideToMove.opposite();
    }

    private void updateCastlingRightsForMove(int from, int to, Piece movingPiece, Piece capturedPiece)
    {
        if (movingPiece.getType() == PieceType.KING)
        {
            if (movingPiece.getColor() == ChessColor.WHITE)
            {
                whiteKingSide = false;
                whiteQueenSide = false;
            }
            else
            {
                blackKingSide = false;
                blackQueenSide = false;
            }
        }

        if (movingPiece.getType() == PieceType.ROOK)
        {
            clearRookCastlingRight(from, movingPiece.getColor());
        }

        if (capturedPiece != null && capturedPiece.getType() == PieceType.ROOK)
        {
            clearRookCastlingRight(to, capturedPiece.getColor());
        }
    }

    private void clearRookCastlingRight(int square, ChessColor color)
    {
        if (color == ChessColor.WHITE)
        {
            if (square == Square.fromAlgebraic("a1"))
            {
                whiteQueenSide = false;
            }
            else if (square == Square.fromAlgebraic("h1"))
            {
                whiteKingSide = false;
            }
        }
        else
        {
            if (square == Square.fromAlgebraic("a8"))
            {
                blackQueenSide = false;
            }
            else if (square == Square.fromAlgebraic("h8"))
            {
                blackKingSide = false;
            }
        }
    }

    private void updateTerminalStatus()
    {
        if (status.isFinished())
        {
            return;
        }

        List<Move> legalMoves = generateLegalMoves(sideToMove);
        if (legalMoves.isEmpty())
        {
            if (isInCheck(sideToMove))
            {
                status = sideToMove == ChessColor.WHITE
                    ? GameStatus.BLACK_WINS_CHECKMATE
                    : GameStatus.WHITE_WINS_CHECKMATE;
            }
            else
            {
                status = GameStatus.DRAW_STALEMATE;
            }
            return;
        }

        if (halfmoveClock >= 100)
        {
            status = GameStatus.DRAW_FIFTY_MOVE;
            return;
        }

        if (hasInsufficientMaterial())
        {
            status = GameStatus.DRAW_INSUFFICIENT_MATERIAL;
        }
    }

    private boolean hasInsufficientMaterial()
    {
        List<Integer> nonKings = new ArrayList<>();
        for (int square = 0; square < Square.BOARD_SIZE; square++)
        {
            Piece piece = board[square];
            if (piece != null && piece.getType() != PieceType.KING)
            {
                if (piece.getType() == PieceType.PAWN
                    || piece.getType() == PieceType.ROOK
                    || piece.getType() == PieceType.QUEEN)
                {
                    return false;
                }
                nonKings.add(square);
            }
        }

        if (nonKings.isEmpty())
        {
            return true;
        }
        if (nonKings.size() == 1)
        {
            return true;
        }

        for (int square : nonKings)
        {
            if (board[square].getType() != PieceType.BISHOP)
            {
                return false;
            }
        }

        int firstColor = (Square.file(nonKings.get(0)) + Square.rank(nonKings.get(0))) % 2;
        for (int square : nonKings)
        {
            int squareColor = (Square.file(square) + Square.rank(square)) % 2;
            if (squareColor != firstColor)
            {
                return false;
            }
        }
        return true;
    }
}
