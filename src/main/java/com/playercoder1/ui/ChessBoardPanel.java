package com.playercoder1.ui;

import com.playercoder1.chess.ChessColor;
import com.playercoder1.chess.ChessGame;
import com.playercoder1.chess.ChessGameListener;
import com.playercoder1.chess.ChessMultiplayerService;
import com.playercoder1.chess.LocalChessController;
import com.playercoder1.chess.Move;
import com.playercoder1.chess.Piece;
import com.playercoder1.chess.PieceType;
import com.playercoder1.chess.Square;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/** Compact interactive board used inside the RuneLite sidebar. */
@SuppressWarnings("serial")
public final class ChessBoardPanel extends JPanel implements ChessGameListener
{
    private static final Color LIGHT_SQUARE = new Color(181, 154, 107);
    private static final Color DARK_SQUARE = new Color(101, 73, 49);
    private static final Color SELECTED_SQUARE = new Color(214, 179, 67);
    private static final Color LEGAL_MOVE_SQUARE = new Color(120, 142, 70);
    private static final Color LAST_MOVE_SQUARE = new Color(153, 132, 62);

    private final LocalChessController controller;
    private final ChessMultiplayerService multiplayer;
    private final JButton[] buttons = new JButton[Square.BOARD_SIZE];
    private int selectedSquare = -1;
    private boolean flipped;

    public ChessBoardPanel(LocalChessController controller, ChessMultiplayerService multiplayer)
    {
        this.controller = controller;
        this.multiplayer = multiplayer;
        controller.addListener(this);

        setLayout(new GridLayout(8, 8, 0, 0));
        setPreferredSize(new Dimension(216, 216));
        setMaximumSize(new Dimension(216, 216));
        setBorder(BorderFactory.createLineBorder(Color.BLACK));

        Font fallbackFont = new Font(Font.SERIF, Font.PLAIN, 23);
        for (int displayIndex = 0; displayIndex < Square.BOARD_SIZE; displayIndex++)
        {
            JButton button = new JButton();
            button.setFont(fallbackFont);
            button.setHorizontalAlignment(SwingConstants.CENTER);
            button.setVerticalAlignment(SwingConstants.CENTER);
            button.setFocusPainted(false);
            button.setBorderPainted(false);
            button.setOpaque(true);
            button.setMargin(new Insets(0, 0, 0, 0));
            button.setFocusable(false);
            final int clickedDisplayIndex = displayIndex;
            button.addActionListener(event -> clickDisplaySquare(clickedDisplayIndex));
            buttons[displayIndex] = button;
            add(button);
        }
        refresh();
    }

    public void setFlipped(boolean flipped)
    {
        if (this.flipped == flipped)
        {
            return;
        }

        this.flipped = flipped;
        selectedSquare = -1;
        refresh();
    }

    public boolean isFlipped()
    {
        return flipped;
    }

    public void clearSelection()
    {
        selectedSquare = -1;
        refresh();
    }

    @Override
    public void onGameChanged()
    {
        if (controller.getGame().getStatus().isFinished())
        {
            selectedSquare = -1;
        }
        refresh();
    }

    private void clickDisplaySquare(int displayIndex)
    {
        ChessGame game = controller.getGame();
        if (game.getStatus().isFinished())
        {
            return;
        }

        int square = boardSquareForDisplayIndex(displayIndex);
        Piece clickedPiece = game.getPiece(square);
        ChessColor sideToMove = game.getSideToMove();

        if (selectedSquare < 0)
        {
            if (clickedPiece != null
                && clickedPiece.getColor() == sideToMove
                && multiplayer.canSelectPiece(clickedPiece.getColor()))
            {
                selectedSquare = square;
                refresh();
            }
            return;
        }

        if (square == selectedSquare)
        {
            selectedSquare = -1;
            refresh();
            return;
        }

        if (clickedPiece != null && clickedPiece.getColor() == sideToMove)
        {
            if (multiplayer.canSelectPiece(clickedPiece.getColor()))
            {
                selectedSquare = square;
                refresh();
            }
            return;
        }

        List<Move> matchingMoves = new ArrayList<>();
        for (Move move : game.getLegalMovesFrom(selectedSquare))
        {
            if (move.getTo() == square)
            {
                matchingMoves.add(move);
            }
        }

        if (matchingMoves.isEmpty())
        {
            return;
        }

        Move chosenMove = choosePromotionIfNeeded(matchingMoves);
        if (chosenMove != null && multiplayer.playMove(chosenMove))
        {
            selectedSquare = -1;
        }
        refresh();
    }

    private Move choosePromotionIfNeeded(List<Move> matchingMoves)
    {
        if (matchingMoves.size() == 1 && matchingMoves.get(0).getPromotion() == null)
        {
            return matchingMoves.get(0);
        }

        Object[] options = {"Queen", "Rook", "Bishop", "Knight"};
        int choice = JOptionPane.showOptionDialog(
            this,
            "Choose a promotion piece",
            "Pawn promotion",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            options,
            options[0]);

        PieceType type;
        switch (choice)
        {
            case 1:
                type = PieceType.ROOK;
                break;
            case 2:
                type = PieceType.BISHOP;
                break;
            case 3:
                type = PieceType.KNIGHT;
                break;
            case 0:
                type = PieceType.QUEEN;
                break;
            default:
                return null;
        }

        for (Move move : matchingMoves)
        {
            if (move.getPromotion() == type)
            {
                return move;
            }
        }
        return null;
    }

    private void refresh()
    {
        ChessGame game = controller.getGame();
        List<Move> legalMoves = selectedSquare < 0
            ? Collections.emptyList()
            : game.getLegalMovesFrom(selectedSquare);
        Move lastMove = game.getLastMove();

        for (int displayIndex = 0; displayIndex < Square.BOARD_SIZE; displayIndex++)
        {
            int square = boardSquareForDisplayIndex(displayIndex);
            int file = Square.file(square);
            int rank = Square.rank(square);
            JButton button = buttons[displayIndex];
            Piece piece = game.getPiece(square);

            ImageIcon icon = ChessPieceImages.icon(piece, 25);
            button.setIcon(icon);
            button.setText(piece == null || icon != null ? "" : piece.symbol());
            button.setToolTipText(Square.toAlgebraic(square));

            Color background = ((file + rank) & 1) == 0 ? DARK_SQUARE : LIGHT_SQUARE;
            if (lastMove != null && (lastMove.getFrom() == square || lastMove.getTo() == square))
            {
                background = LAST_MOVE_SQUARE;
            }
            for (Move move : legalMoves)
            {
                if (move.getTo() == square)
                {
                    background = LEGAL_MOVE_SQUARE;
                    break;
                }
            }
            if (selectedSquare == square)
            {
                background = SELECTED_SQUARE;
            }
            button.setBackground(background);
        }
        repaint();
    }

    private int boardSquareForDisplayIndex(int displayIndex)
    {
        int row = displayIndex / 8;
        int column = displayIndex % 8;
        int file = flipped ? 7 - column : column;
        int rank = flipped ? row : 7 - row;
        return Square.of(file, rank);
    }
}
