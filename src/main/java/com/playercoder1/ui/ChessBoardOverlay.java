package com.playercoder1.ui;

import com.playercoder1.chess.ChessClock;
import com.playercoder1.chess.ChessColor;
import com.playercoder1.chess.ChessGame;
import com.playercoder1.chess.ChessGameListener;
import com.playercoder1.chess.ChessMultiplayerService;
import com.playercoder1.chess.LocalChessController;
import com.playercoder1.chess.Move;
import com.playercoder1.chess.Piece;
import com.playercoder1.chess.PieceType;
import com.playercoder1.chess.Square;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Interactive chess board rendered over the game canvas. Mouse presses inside
 * its bounds are consumed so they cannot trigger RuneScape actions.
 */
@Singleton
public final class ChessBoardOverlay extends Overlay implements MouseListener, ChessGameListener
{
    private static final int BASE_PADDING = 10;
    private static final int BASE_HEADER_HEIGHT = 30;
    private static final int BASE_CLOCK_HEIGHT = 25;
    private static final int BASE_CLOCK_GAP = 5;
    private static final int BASE_SQUARE_SIZE = 32;
    private static final int BASE_STATUS_HEIGHT = 36;
    private static final int BASE_BOARD_SIZE = BASE_SQUARE_SIZE * 8;
    private static final int BASE_WIDTH = BASE_BOARD_SIZE + BASE_PADDING * 2;
    private static final int BASE_HEIGHT = BASE_PADDING + BASE_HEADER_HEIGHT + BASE_CLOCK_HEIGHT + BASE_CLOCK_GAP
        + BASE_BOARD_SIZE + BASE_CLOCK_GAP + BASE_CLOCK_HEIGHT + BASE_STATUS_HEIGHT + BASE_PADDING;

    private static final double MIN_SCALE = 0.72d;
    private static final double MAX_SCALE = 1.55d;
    private static final int CANVAS_MARGIN = 12;

    private static final Color PANEL_BACKGROUND = new Color(23, 23, 23, 238);
    private static final Color PANEL_BORDER = new Color(190, 145, 68);
    private static final Color HEADER_BACKGROUND = new Color(31, 31, 31, 246);
    private static final Color LIGHT_SQUARE = new Color(186, 158, 109);
    private static final Color DARK_SQUARE = new Color(103, 75, 50);
    private static final Color SELECTED_SQUARE = new Color(222, 182, 62);
    private static final Color LAST_MOVE_SQUARE = new Color(158, 136, 65);
    private static final Color LEGAL_DOT = new Color(47, 92, 58, 185);
    private static final Color CAPTURE_RING = new Color(115, 52, 47, 215);
    private static final Color HOVER_BORDER = new Color(245, 245, 245, 190);
    private static final Color ACTIVE_CLOCK = new Color(95, 75, 42);
    private static final Color INACTIVE_CLOCK = new Color(48, 48, 48);
    private static final Color MUTED_TEXT = new Color(191, 191, 191);
    private static final Color SUCCESS = new Color(103, 174, 99);
    private static final Color WARNING = new Color(220, 169, 73);

    private final Client client;
    private final LocalChessController controller;
    private final ChessMultiplayerService multiplayer;
    private final Rectangle closeBounds = new Rectangle();
    private final Rectangle flipBounds = new Rectangle();
    private final Rectangle boardBounds = new Rectangle();
    private final Rectangle headerBounds = new Rectangle();
    private final Map<PieceType, Rectangle> promotionBounds = new EnumMap<>(PieceType.class);

    private List<Move> pendingPromotionMoves = Collections.emptyList();
    private int selectedSquare = -1;
    private int hoveredSquare = -1;
    private boolean flipped;
    private boolean visible;
    private boolean consumingMouseSequence;
    private boolean fittedOnce;
    private VisibilityListener visibilityListener;
    private OrientationListener orientationListener;

    // Current rendered metrics, also used for hit testing.
    private int padding = BASE_PADDING;
    private int headerHeight = BASE_HEADER_HEIGHT;
    private int clockHeight = BASE_CLOCK_HEIGHT;
    private int clockGap = BASE_CLOCK_GAP;
    private int squareSize = BASE_SQUARE_SIZE;
    private int boardSize = BASE_BOARD_SIZE;
    private int statusHeight = BASE_STATUS_HEIGHT;
    private int renderedWidth = BASE_WIDTH;
    private int renderedHeight = BASE_HEIGHT;
    private double renderScale = 1.0d;

    @Inject
    public ChessBoardOverlay(
        Client client,
        LocalChessController controller,
        ChessMultiplayerService multiplayer)
    {
        this.client = client;
        this.controller = controller;
        this.multiplayer = multiplayer;
        controller.addListener(this);

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_HIGH);
        setMovable(true);
        setResizable(true);
        setMinimumSize(200);
        setResettable(true);
        setSnappable(true);
        setPreferredSize(new Dimension(BASE_WIDTH, BASE_HEIGHT));
        setPreferredLocation(new Point(28, 28));
    }

    @Override
    public String getName()
    {
        return "Chess Board";
    }

    public boolean isVisible()
    {
        return visible;
    }

    public void setVisible(boolean visible)
    {
        if (this.visible == visible)
        {
            return;
        }

        this.visible = visible;
        hoveredSquare = -1;
        pendingPromotionMoves = Collections.emptyList();
        if (!visible)
        {
            selectedSquare = -1;
        }

        if (visibilityListener != null)
        {
            visibilityListener.onVisibilityChanged(visible);
        }
    }

    public void fitToCanvasIfNeeded()
    {
        if (!fittedOnce)
        {
            fitToCanvas();
        }
    }

    public void fitToCanvas()
    {
        int canvasWidth = Math.max(320, client.getCanvasWidth());
        int canvasHeight = Math.max(320, client.getCanvasHeight());
        double widthScale = Math.max(MIN_SCALE, (canvasWidth * 0.46d) / BASE_WIDTH);
        double heightScale = Math.max(MIN_SCALE, (canvasHeight * 0.76d) / BASE_HEIGHT);
        double scale = clamp(Math.min(widthScale, heightScale), MIN_SCALE, 1.22d);
        Dimension size = dimensionsForScale(scale);
        setPreferredSize(size);

        int x = Math.max(CANVAS_MARGIN, Math.min(28, canvasWidth - size.width - CANVAS_MARGIN));
        int y = Math.max(CANVAS_MARGIN, Math.min(28, canvasHeight - size.height - CANVAS_MARGIN));
        setPreferredLocation(new Point(x, y));
        fittedOnce = true;
        revalidate();
    }

    public boolean isFlipped()
    {
        return flipped;
    }

    public void setFlipped(boolean flipped)
    {
        if (this.flipped == flipped)
        {
            return;
        }

        this.flipped = flipped;
        selectedSquare = -1;
        hoveredSquare = -1;
        pendingPromotionMoves = Collections.emptyList();

        if (orientationListener != null)
        {
            orientationListener.onOrientationChanged(flipped);
        }
    }

    public void clearSelection()
    {
        selectedSquare = -1;
        pendingPromotionMoves = Collections.emptyList();
    }

    public void setVisibilityListener(VisibilityListener visibilityListener)
    {
        this.visibilityListener = visibilityListener;
    }

    public void setOrientationListener(OrientationListener orientationListener)
    {
        this.orientationListener = orientationListener;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!visible)
        {
            return null;
        }

        updateMetrics();

        Graphics2D g = (Graphics2D) graphics.create();
        try
        {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            drawPanel(g);
            drawHeader(g);

            int topClockY = padding + headerHeight;
            ChessColor topColor = colorAtTop();
            ChessColor bottomColor = colorAtBottom();
            drawClock(g, topColor, topClockY);

            int boardY = topClockY + clockHeight + clockGap;
            boardBounds.setBounds(padding, boardY, boardSize, boardSize);
            drawBoard(g);

            int bottomClockY = boardY + boardSize + clockGap;
            drawClock(g, bottomColor, bottomClockY);
            drawStatus(g, bottomClockY + clockHeight);

            if (!pendingPromotionMoves.isEmpty())
            {
                drawPromotionChooser(g);
            }
        }
        finally
        {
            g.dispose();
        }

        return new Dimension(renderedWidth, renderedHeight);
    }

    private void updateMetrics()
    {
        Dimension preferred = getPreferredSize();
        int requestedWidth = preferred == null || preferred.width <= 0 ? BASE_WIDTH : preferred.width;
        int requestedHeight = preferred == null || preferred.height <= 0 ? BASE_HEIGHT : preferred.height;

        int maxWidth = Math.max(200, client.getCanvasWidth() - CANVAS_MARGIN * 2);
        int maxHeight = Math.max(300, client.getCanvasHeight() - CANVAS_MARGIN * 2);

        double requestedScale = Math.min(
            requestedWidth / (double) BASE_WIDTH,
            requestedHeight / (double) BASE_HEIGHT);
        double canvasScale = Math.min(maxWidth / (double) BASE_WIDTH, maxHeight / (double) BASE_HEIGHT);
        renderScale = clamp(Math.min(requestedScale, canvasScale), MIN_SCALE, MAX_SCALE);

        padding = scaled(BASE_PADDING);
        headerHeight = scaled(BASE_HEADER_HEIGHT);
        clockHeight = scaled(BASE_CLOCK_HEIGHT);
        clockGap = Math.max(3, scaled(BASE_CLOCK_GAP));
        squareSize = Math.max(22, scaled(BASE_SQUARE_SIZE));
        boardSize = squareSize * 8;
        statusHeight = scaled(BASE_STATUS_HEIGHT);
        renderedWidth = boardSize + padding * 2;
        renderedHeight = padding + headerHeight + clockHeight + clockGap
            + boardSize + clockGap + clockHeight + statusHeight + padding;

        if (preferred != null && (preferred.width > maxWidth || preferred.height > maxHeight))
        {
            setPreferredSize(new Dimension(renderedWidth, renderedHeight));
        }
    }

    private void drawPanel(Graphics2D g)
    {
        g.setColor(PANEL_BACKGROUND);
        g.fillRoundRect(0, 0, renderedWidth, renderedHeight, scaled(11), scaled(11));
        g.setColor(PANEL_BORDER);
        g.setStroke(new BasicStroke(Math.max(1f, (float) (1.6d * renderScale))));
        g.drawRoundRect(1, 1, renderedWidth - 2, renderedHeight - 2, scaled(11), scaled(11));
    }

    private void drawHeader(Graphics2D g)
    {
        headerBounds.setBounds(padding, padding, boardSize, headerHeight - 2);
        g.setColor(HEADER_BACKGROUND);
        g.fillRoundRect(headerBounds.x, headerBounds.y, headerBounds.width, headerBounds.height,
            scaled(6), scaled(6));

        int buttonSize = Math.max(15, scaled(17));
        int gap = Math.max(4, scaled(4));
        closeBounds.setBounds(
            headerBounds.x + headerBounds.width - buttonSize - gap,
            headerBounds.y + (headerBounds.height - buttonSize) / 2,
            buttonSize,
            buttonSize);
        flipBounds.setBounds(closeBounds.x - buttonSize - gap, closeBounds.y, buttonSize, buttonSize);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(11, scaled(14))));
        g.setColor(Color.WHITE);
        g.drawString("Chess", headerBounds.x + scaled(7), headerBounds.y + scaled(19));

        if (renderScale >= 0.88d)
        {
            String mode = modeText();
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(8, scaled(9))));
            g.setColor(multiplayer.isOnline()
                ? (multiplayer.isOpponentConnected() ? SUCCESS : WARNING)
                : MUTED_TEXT);
            int modeWidth = g.getFontMetrics().stringWidth(mode);
            int availableRight = flipBounds.x - scaled(5);
            int modeX = Math.max(headerBounds.x + scaled(102), availableRight - modeWidth);
            g.drawString(mode, modeX, headerBounds.y + scaled(19));
        }

        drawHeaderButton(g, flipBounds, new Color(72, 72, 72), "F");
        drawHeaderButton(g, closeBounds, new Color(177, 67, 63), "×");
    }

    private void drawHeaderButton(Graphics2D g, Rectangle bounds, Color background, String text)
    {
        g.setColor(background);
        g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, scaled(4), scaled(4));
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, scaled(12))));
        drawCentered(g, text, bounds.x, bounds.y - 1, bounds.width, bounds.height);
    }

    private String modeText()
    {
        if (!multiplayer.isOnline())
        {
            return "LOCAL";
        }
        if (!multiplayer.isPlayingOnline())
        {
            return "WAITING";
        }
        return multiplayer.isOpponentConnected() ? "ONLINE" : "RECONNECTING";
    }

    private ChessColor colorAtTop()
    {
        return flipped ? ChessColor.WHITE : ChessColor.BLACK;
    }

    private ChessColor colorAtBottom()
    {
        return colorAtTop().opposite();
    }

    private void drawClock(Graphics2D g, ChessColor color, int y)
    {
        ChessGame game = controller.getGame();
        boolean active = !game.getStatus().isFinished() && game.getSideToMove() == color;
        g.setColor(active ? ACTIVE_CLOCK : INACTIVE_CLOCK);
        g.fillRoundRect(padding, y, boardSize, clockHeight, scaled(5), scaled(5));

        if (active)
        {
            g.setColor(PANEL_BORDER);
            g.fillRoundRect(padding, y, Math.max(4, scaled(5)), clockHeight, scaled(5), scaled(5));
        }

        String text = color.displayName() + "  "
            + ChessClock.format(controller.getClock().getRemainingMillis(color));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(11, scaled(14))));
        g.setColor(Color.WHITE);
        drawCentered(g, text, padding, y, boardSize, clockHeight);
    }

    private void drawBoard(Graphics2D g)
    {
        ChessGame game = controller.getGame();
        List<Move> legalMoves = selectedSquare < 0
            ? Collections.emptyList()
            : game.getLegalMovesFrom(selectedSquare);
        Move lastMove = game.getLastMove();

        for (int displayIndex = 0; displayIndex < 64; displayIndex++)
        {
            int square = boardSquareForDisplayIndex(displayIndex);
            int displayRow = displayIndex / 8;
            int displayColumn = displayIndex % 8;
            int x = boardBounds.x + displayColumn * squareSize;
            int y = boardBounds.y + displayRow * squareSize;
            int file = Square.file(square);
            int rank = Square.rank(square);

            Color squareColor = ((file + rank) & 1) == 0 ? DARK_SQUARE : LIGHT_SQUARE;
            if (lastMove != null && (lastMove.getFrom() == square || lastMove.getTo() == square))
            {
                squareColor = LAST_MOVE_SQUARE;
            }
            if (selectedSquare == square)
            {
                squareColor = SELECTED_SQUARE;
            }

            g.setColor(squareColor);
            g.fillRect(x, y, squareSize, squareSize);

            Piece piece = game.getPiece(square);
            if (piece != null)
            {
                drawPiece(g, piece.getColor(), piece.getType(), x, y, squareSize);
            }

            if (containsDestination(legalMoves, square))
            {
                drawLegalTarget(g, x, y, piece != null);
            }

            if (selectedSquare == square)
            {
                g.setColor(new Color(255, 235, 135, 220));
                g.setStroke(new BasicStroke(Math.max(2f, (float) (2.2d * renderScale))));
                g.drawRect(x + 1, y + 1, squareSize - 3, squareSize - 3);
            }
            else if (hoveredSquare == square)
            {
                g.setColor(HOVER_BORDER);
                g.setStroke(new BasicStroke(Math.max(1.4f, (float) (1.7d * renderScale))));
                g.drawRect(x + 1, y + 1, squareSize - 3, squareSize - 3);
            }

            drawCoordinate(g, displayRow, displayColumn, file, rank, x, y, squareColor);
        }

        g.setColor(new Color(16, 16, 16));
        g.setStroke(new BasicStroke(Math.max(1.2f, (float) (1.5d * renderScale))));
        g.drawRect(boardBounds.x, boardBounds.y, boardBounds.width, boardBounds.height);
    }

    private void drawLegalTarget(Graphics2D g, int x, int y, boolean capture)
    {
        if (capture)
        {
            int inset = Math.max(3, scaled(4));
            g.setColor(CAPTURE_RING);
            g.setStroke(new BasicStroke(Math.max(2f, (float) (3d * renderScale))));
            g.drawOval(x + inset, y + inset, squareSize - inset * 2, squareSize - inset * 2);
        }
        else
        {
            int diameter = Math.max(7, scaled(9));
            g.setColor(LEGAL_DOT);
            g.fillOval(x + (squareSize - diameter) / 2, y + (squareSize - diameter) / 2,
                diameter, diameter);
        }
    }

    private void drawCoordinate(
        Graphics2D g,
        int displayRow,
        int displayColumn,
        int file,
        int rank,
        int x,
        int y,
        Color background)
    {
        if (renderScale < 0.82d)
        {
            return;
        }

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(7, scaled(8))));
        g.setColor(background == LIGHT_SQUARE ? DARK_SQUARE : LIGHT_SQUARE);
        if (displayColumn == 0)
        {
            g.drawString(Integer.toString(rank + 1), x + scaled(2), y + scaled(9));
        }
        if (displayRow == 7)
        {
            String fileText = Character.toString((char) ('a' + file));
            int width = g.getFontMetrics().stringWidth(fileText);
            g.drawString(fileText, x + squareSize - width - scaled(2), y + squareSize - scaled(2));
        }
    }

    private void drawStatus(Graphics2D g, int y)
    {
        String status = controller.getNotice();
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(9, scaled(11))));
        g.setColor(new Color(226, 226, 226));
        drawCenteredEllipsized(g, status, padding + scaled(4), y, boardSize - scaled(8), statusHeight);
    }

    private void drawPromotionChooser(Graphics2D g)
    {
        int chooserWidth = Math.min(boardSize - scaled(20), scaled(196));
        int chooserHeight = scaled(78);
        int chooserX = boardBounds.x + (boardSize - chooserWidth) / 2;
        int chooserY = boardBounds.y + (boardSize - chooserHeight) / 2;

        g.setComposite(AlphaComposite.SrcOver.derive(0.95f));
        g.setColor(new Color(20, 20, 20));
        g.fillRoundRect(chooserX, chooserY, chooserWidth, chooserHeight, scaled(8), scaled(8));
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(PANEL_BORDER);
        g.drawRoundRect(chooserX, chooserY, chooserWidth, chooserHeight, scaled(8), scaled(8));

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, scaled(12))));
        g.setColor(Color.WHITE);
        drawCentered(g, "Choose promotion", chooserX, chooserY + scaled(2), chooserWidth, scaled(22));

        PieceType[] types = {PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT};
        ChessColor color = controller.getGame().getSideToMove();
        int optionSize = Math.max(31, scaled(38));
        int optionGap = Math.max(3, scaled(5));
        int optionsWidth = optionSize * 4 + optionGap * 3;
        int startX = chooserX + (chooserWidth - optionsWidth) / 2;
        int optionY = chooserY + scaled(29);
        promotionBounds.clear();

        for (int i = 0; i < types.length; i++)
        {
            PieceType type = types[i];
            Rectangle bounds = new Rectangle(startX + i * (optionSize + optionGap), optionY, optionSize, optionSize);
            promotionBounds.put(type, bounds);
            g.setColor(new Color(72, 72, 72));
            g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, scaled(5), scaled(5));
            g.setColor(PANEL_BORDER);
            g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, scaled(5), scaled(5));
            drawPiece(g, color, type, bounds.x, bounds.y, bounds.width);
        }
    }

    @Override
    public MouseEvent mousePressed(MouseEvent event)
    {
        if (!isInsideOverlay(event))
        {
            return event;
        }

        consumingMouseSequence = true;
        event.consume();
        if (!SwingUtilities.isLeftMouseButton(event))
        {
            return event;
        }

        int localX = event.getX() - getBounds().x;
        int localY = event.getY() - getBounds().y;

        if (closeBounds.contains(localX, localY))
        {
            setVisible(false);
            return event;
        }
        if (flipBounds.contains(localX, localY))
        {
            setFlipped(!flipped);
            return event;
        }

        if (!pendingPromotionMoves.isEmpty())
        {
            playPromotionAt(localX, localY);
            return event;
        }

        if (boardBounds.contains(localX, localY))
        {
            clickBoard(localX, localY);
        }
        return event;
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent event)
    {
        if (consumingMouseSequence || isInsideOverlay(event))
        {
            event.consume();
            SwingUtilities.invokeLater(() -> consumingMouseSequence = false);
        }
        return event;
    }

    @Override
    public MouseEvent mouseClicked(MouseEvent event)
    {
        if (consumingMouseSequence || isInsideOverlay(event))
        {
            if (event.getClickCount() == 2 && isInHeader(event))
            {
                fitToCanvas();
            }
            event.consume();
        }
        return event;
    }

    @Override
    public MouseEvent mouseDragged(MouseEvent event)
    {
        if (consumingMouseSequence || isInsideOverlay(event))
        {
            event.consume();
        }
        return event;
    }

    @Override
    public MouseEvent mouseMoved(MouseEvent event)
    {
        if (!isInsideOverlay(event))
        {
            hoveredSquare = -1;
            return event;
        }

        int localX = event.getX() - getBounds().x;
        int localY = event.getY() - getBounds().y;
        hoveredSquare = boardBounds.contains(localX, localY)
            ? squareAt(localX, localY)
            : -1;
        return event;
    }

    @Override
    public MouseEvent mouseEntered(MouseEvent event)
    {
        return event;
    }

    @Override
    public MouseEvent mouseExited(MouseEvent event)
    {
        hoveredSquare = -1;
        return event;
    }

    private boolean isInsideOverlay(MouseEvent event)
    {
        return visible && getBounds().contains(event.getPoint());
    }

    private boolean isInHeader(MouseEvent event)
    {
        int localX = event.getX() - getBounds().x;
        int localY = event.getY() - getBounds().y;
        return headerBounds.contains(localX, localY);
    }

    private void clickBoard(int localX, int localY)
    {
        ChessGame game = controller.getGame();
        if (game.getStatus().isFinished())
        {
            return;
        }

        int square = squareAt(localX, localY);
        Piece clickedPiece = game.getPiece(square);
        ChessColor sideToMove = game.getSideToMove();

        if (selectedSquare < 0)
        {
            if (clickedPiece != null
                && clickedPiece.getColor() == sideToMove
                && multiplayer.canSelectPiece(clickedPiece.getColor()))
            {
                selectedSquare = square;
            }
            return;
        }

        if (square == selectedSquare)
        {
            selectedSquare = -1;
            return;
        }

        if (clickedPiece != null && clickedPiece.getColor() == sideToMove)
        {
            if (multiplayer.canSelectPiece(clickedPiece.getColor()))
            {
                selectedSquare = square;
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

        if (matchingMoves.size() > 1 || matchingMoves.get(0).getPromotion() != null)
        {
            pendingPromotionMoves = matchingMoves;
            return;
        }

        if (multiplayer.playMove(matchingMoves.get(0)))
        {
            selectedSquare = -1;
        }
    }

    private void playPromotionAt(int localX, int localY)
    {
        PieceType selectedType = null;
        for (Map.Entry<PieceType, Rectangle> entry : promotionBounds.entrySet())
        {
            if (entry.getValue().contains(localX, localY))
            {
                selectedType = entry.getKey();
                break;
            }
        }

        if (selectedType == null)
        {
            return;
        }

        for (Move move : pendingPromotionMoves)
        {
            if (move.getPromotion() == selectedType && multiplayer.playMove(move))
            {
                selectedSquare = -1;
                pendingPromotionMoves = Collections.emptyList();
                return;
            }
        }
    }

    private int squareAt(int localX, int localY)
    {
        int column = (localX - boardBounds.x) / squareSize;
        int row = (localY - boardBounds.y) / squareSize;
        int displayIndex = row * 8 + column;
        return boardSquareForDisplayIndex(displayIndex);
    }

    private int boardSquareForDisplayIndex(int displayIndex)
    {
        int row = displayIndex / 8;
        int column = displayIndex % 8;
        int file = flipped ? 7 - column : column;
        int rank = flipped ? row : 7 - row;
        return Square.of(file, rank);
    }

    private static boolean containsDestination(List<Move> moves, int square)
    {
        for (Move move : moves)
        {
            if (move.getTo() == square)
            {
                return true;
            }
        }
        return false;
    }

    private void drawPiece(
        Graphics2D g,
        ChessColor color,
        PieceType type,
        int x,
        int y,
        int size)
    {
        BufferedImage image = ChessPieceImages.get(color, type);
        if (image != null)
        {
            int inset = Math.max(1, size / 18);
            g.drawImage(image, x + inset, y + inset, size - inset * 2, size - inset * 2, null);
            return;
        }

        g.setFont(new Font(Font.SERIF, Font.PLAIN, Math.max(17, size - 5)));
        g.setColor(color == ChessColor.WHITE ? Color.WHITE : Color.BLACK);
        drawCentered(g, fallbackSymbol(color, type), x, y - 1, size, size);
    }

    private static String fallbackSymbol(ChessColor color, PieceType type)
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

    private static void drawCentered(Graphics2D g, String text, int x, int y, int width, int height)
    {
        FontMetrics metrics = g.getFontMetrics();
        int textX = x + (width - metrics.stringWidth(text)) / 2;
        int textY = y + (height - metrics.getHeight()) / 2 + metrics.getAscent();
        g.drawString(text, textX, textY);
    }

    private static void drawCenteredEllipsized(
        Graphics2D g,
        String text,
        int x,
        int y,
        int width,
        int height)
    {
        FontMetrics metrics = g.getFontMetrics();
        String value = text == null ? "" : text;
        if (metrics.stringWidth(value) > width - 8)
        {
            String suffix = "…";
            while (!value.isEmpty() && metrics.stringWidth(value + suffix) > width - 8)
            {
                value = value.substring(0, value.length() - 1);
            }
            value += suffix;
        }
        drawCentered(g, value, x, y, width, height);
    }

    private int scaled(int value)
    {
        return Math.max(1, (int) Math.round(value * renderScale));
    }

    private static Dimension dimensionsForScale(double scale)
    {
        int padding = Math.max(1, (int) Math.round(BASE_PADDING * scale));
        int header = Math.max(1, (int) Math.round(BASE_HEADER_HEIGHT * scale));
        int clock = Math.max(1, (int) Math.round(BASE_CLOCK_HEIGHT * scale));
        int gap = Math.max(3, (int) Math.round(BASE_CLOCK_GAP * scale));
        int square = Math.max(22, (int) Math.round(BASE_SQUARE_SIZE * scale));
        int board = square * 8;
        int status = Math.max(1, (int) Math.round(BASE_STATUS_HEIGHT * scale));
        return new Dimension(
            board + padding * 2,
            padding + header + clock + gap + board + gap + clock + status + padding);
    }

    private static double clamp(double value, double minimum, double maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public void onGameChanged()
    {
        if (controller.getGame().getStatus().isFinished())
        {
            selectedSquare = -1;
            pendingPromotionMoves = Collections.emptyList();
        }
    }

    @FunctionalInterface
    public interface VisibilityListener
    {
        void onVisibilityChanged(boolean visible);
    }

    @FunctionalInterface
    public interface OrientationListener
    {
        void onOrientationChanged(boolean flipped);
    }
}
