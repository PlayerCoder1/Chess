package com.playercoder1.ui;

import com.playercoder1.chess.ChessColor;
import com.playercoder1.chess.ChessMultiplayerService;
import com.playercoder1.chess.LocalChessController;
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
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;


@Singleton
public final class ChessMatchControlsOverlay extends Overlay implements MouseListener
{
    private enum PendingAction
    {
        NONE,
        RESIGN,
        LEAVE
    }

    private static final int WIDTH = 278;
    private static final int HEIGHT_ACTIVE = 92;
    private static final int HEIGHT_FINISHED = 92;
    private static final int PADDING = 8;
    private static final int HEADER_HEIGHT = 23;
    private static final int DETAIL_HEIGHT = 22;
    private static final int BUTTON_HEIGHT = 29;
    private static final int BUTTON_GAP = 5;
    private static final long CONFIRM_MILLIS = 3_500L;

    private static final Color BACKGROUND = new Color(23, 23, 23, 242);
    private static final Color BORDER = new Color(190, 145, 68);
    private static final Color HEADER = new Color(31, 31, 31, 248);
    private static final Color BUTTON = new Color(67, 67, 67);
    private static final Color BUTTON_HOVER = new Color(82, 82, 82);
    private static final Color PRIMARY = new Color(173, 126, 45);
    private static final Color PRIMARY_HOVER = new Color(194, 144, 53);
    private static final Color DANGER = new Color(128, 58, 56);
    private static final Color DANGER_HOVER = new Color(153, 68, 64);
    private static final Color DISABLED = new Color(45, 45, 45);
    private static final Color MUTED = new Color(190, 190, 190);
    private static final Color SUCCESS = new Color(102, 181, 100);
    private static final Color WARNING = new Color(222, 172, 76);

    private final Client client;
    private final ChessMultiplayerService multiplayer;
    private final LocalChessController controller;
    private final ChessBoardOverlay boardOverlay;

    private final Rectangle firstButton = new Rectangle();
    private final Rectangle secondButton = new Rectangle();
    private final Rectangle thirdButton = new Rectangle();

    private boolean visible;
    private boolean consumingMouseSequence;
    private int hoveredButton;
    private PendingAction pendingAction = PendingAction.NONE;
    private long confirmUntilMillis;

    @Inject
    public ChessMatchControlsOverlay(
            Client client,
            ChessMultiplayerService multiplayer,
            LocalChessController controller,
            ChessBoardOverlay boardOverlay)
    {
        this.client = client;
        this.multiplayer = multiplayer;
        this.controller = controller;
        this.boardOverlay = boardOverlay;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_HIGH);
        setMovable(true);
        setResettable(true);
        setSnappable(true);
        setPreferredLocation(new Point(28, 438));
        setPreferredSize(new Dimension(WIDTH, HEIGHT_ACTIVE));
    }

    @Override
    public String getName()
    {
        return "Chess Match Controls";
    }

    public void showForMatch()
    {
        if (!visible)
        {
            placeNearBoard();
        }
        setVisible(true);
    }

    private void placeNearBoard()
    {
        Point boardLocation = boardOverlay.getPreferredLocation();
        Dimension boardSize = boardOverlay.getPreferredSize();
        int canvasWidth = Math.max(320, client.getCanvasWidth());
        int canvasHeight = Math.max(320, client.getCanvasHeight());

        int boardX = boardLocation == null ? 28 : boardLocation.x;
        int boardY = boardLocation == null ? 28 : boardLocation.y;
        int boardWidth = boardSize == null ? 276 : boardSize.width;
        int boardHeight = boardSize == null ? 398 : boardSize.height;

        int x = boardX;
        int y = boardY + boardHeight + 6;
        if (y + HEIGHT_ACTIVE > canvasHeight - 12)
        {
            x = boardX + boardWidth + 6;
            y = boardY;
        }
        if (x + WIDTH > canvasWidth - 12 || y + HEIGHT_ACTIVE > canvasHeight - 12)
        {
            x = Math.max(12, canvasWidth - WIDTH - 12);
            y = Math.max(12, canvasHeight - HEIGHT_ACTIVE - 12);
        }

        setPreferredLocation(new Point(Math.max(12, x), Math.max(12, y)));
        revalidate();
    }

    public void setVisible(boolean visible)
    {
        this.visible = visible;
        if (!visible)
        {
            hoveredButton = 0;
            clearPendingAction();
        }
    }

    public boolean isVisible()
    {
        return visible;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!visible || !boardOverlay.isVisible() || !multiplayer.isPlayingOnline())
        {
            return null;
        }

        expireConfirmationIfNeeded();

        boolean finished = controller.getGame().getStatus().isFinished();
        int height = finished ? HEIGHT_FINISHED : HEIGHT_ACTIVE;
        setPreferredSize(new Dimension(WIDTH, height));

        Graphics2D g = (Graphics2D) graphics.create();
        try
        {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(BACKGROUND);
            g.fillRoundRect(0, 0, WIDTH, height, 10, 10);
            g.setColor(BORDER);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(1, 1, WIDTH - 2, height - 2, 10, 10);

            g.setColor(HEADER);
            g.fillRoundRect(PADDING, PADDING, WIDTH - PADDING * 2, HEADER_HEIGHT, 6, 6);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(Color.WHITE);
            drawCentered(g, titleText(), PADDING, PADDING, WIDTH - PADDING * 2, HEADER_HEIGHT);

            int detailY = PADDING + HEADER_HEIGHT;
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g.setColor(multiplayer.isOpponentConnected() ? SUCCESS : WARNING);
            drawCentered(g, detailText(), PADDING, detailY, WIDTH - PADDING * 2, DETAIL_HEIGHT);

            int buttonY = detailY + DETAIL_HEIGHT;
            if (finished)
            {
                drawFinishedButtons(g, buttonY);
            }
            else
            {
                drawActiveButtons(g, buttonY);
            }
        }
        finally
        {
            g.dispose();
        }

        return new Dimension(WIDTH, height);
    }

    private void drawActiveButtons(Graphics2D g, int y)
    {
        int available = WIDTH - PADDING * 2;
        int buttonWidth = (available - BUTTON_GAP * 2) / 3;
        firstButton.setBounds(PADDING, y, buttonWidth, BUTTON_HEIGHT);
        secondButton.setBounds(PADDING + buttonWidth + BUTTON_GAP, y, buttonWidth, BUTTON_HEIGHT);
        thirdButton.setBounds(PADDING + (buttonWidth + BUTTON_GAP) * 2, y,
                available - buttonWidth * 2 - BUTTON_GAP * 2, BUTTON_HEIGHT);

        boolean canAct = multiplayer.canLocalPlayerAct();
        drawButton(g, firstButton, drawActionText(), 1, canAct, false, false);
        drawButton(g, secondButton, pendingAction == PendingAction.RESIGN ? "Confirm" : "Resign",
                2, canAct, false, true);
        drawButton(g, thirdButton, pendingAction == PendingAction.LEAVE ? "Confirm" : "Leave",
                3, true, false, true);
    }

    private void drawFinishedButtons(Graphics2D g, int y)
    {
        int available = WIDTH - PADDING * 2;
        int buttonWidth = (available - BUTTON_GAP) / 2;
        firstButton.setBounds(PADDING, y, buttonWidth, BUTTON_HEIGHT);
        secondButton.setBounds(PADDING + buttonWidth + BUTTON_GAP, y,
                available - buttonWidth - BUTTON_GAP, BUTTON_HEIGHT);
        thirdButton.setBounds(0, 0, 0, 0);

        boolean rematchEnabled = multiplayer.isOpponentConnected()
                && !multiplayer.isRematchRequestedByLocalPlayer();
        drawButton(g, firstButton, rematchText(), 1, rematchEnabled, true, false);
        drawButton(g, secondButton, pendingAction == PendingAction.LEAVE ? "Confirm leave" : "Leave match",
                2, true, false, true);
    }

    private void drawButton(
            Graphics2D g,
            Rectangle bounds,
            String text,
            int index,
            boolean enabled,
            boolean primary,
            boolean danger)
    {
        Color base;
        Color hover;
        if (!enabled)
        {
            base = DISABLED;
            hover = DISABLED;
        }
        else if (danger)
        {
            base = DANGER;
            hover = DANGER_HOVER;
        }
        else if (primary)
        {
            base = PRIMARY;
            hover = PRIMARY_HOVER;
        }
        else
        {
            base = BUTTON;
            hover = BUTTON_HOVER;
        }

        g.setColor(enabled && hoveredButton == index ? hover : base);
        g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 5, 5);
        g.setColor(enabled ? new Color(215, 215, 215) : new Color(110, 110, 110));
        g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 5, 5);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.setColor(enabled ? Color.WHITE : new Color(145, 145, 145));
        drawCenteredEllipsized(g, text, bounds.x + 2, bounds.y, bounds.width - 4, bounds.height);
    }

    private String titleText()
    {
        String kind = multiplayer.isMatchmade() ? "Quick Match" : "Private Match";
        return kind + "  " + multiplayer.getInitialMinutes() + "+" + multiplayer.getIncrementSeconds();
    }

    private String detailText()
    {
        ChessColor local = multiplayer.getLocalColor();
        String side = local == null ? "Online game" : "Playing " + local.displayName();
        return side + (multiplayer.isOpponentConnected() ? "  •  Opponent connected" : "  •  Reconnecting…");
    }

    private String drawActionText()
    {
        ChessColor offeredBy = controller.getDrawOfferedBy();
        ChessColor local = multiplayer.getLocalColor();
        if (offeredBy == null || local == null)
        {
            return "Offer draw";
        }
        return offeredBy == local ? "Cancel draw" : "Accept draw";
    }

    private String rematchText()
    {
        if (multiplayer.isRematchRequestedByLocalPlayer())
        {
            return "Rematch requested";
        }
        if (multiplayer.isRematchRequestedByOpponent())
        {
            return "Accept rematch";
        }
        return "Rematch";
    }

    @Override
    public MouseEvent mousePressed(MouseEvent event)
    {
        if (!isInside(event))
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
        boolean finished = controller.getGame().getStatus().isFinished();

        if (finished)
        {
            if (firstButton.contains(localX, localY))
            {
                if (!multiplayer.isRematchRequestedByLocalPlayer() && multiplayer.isOpponentConnected())
                {
                    clearPendingAction();
                    multiplayer.requestOrAcceptRematch();
                }
            }
            else if (secondButton.contains(localX, localY))
            {
                confirmOrExecuteLeave();
            }
            return event;
        }

        if (firstButton.contains(localX, localY) && multiplayer.canLocalPlayerAct())
        {
            clearPendingAction();
            multiplayer.offerAcceptOrCancelDraw();
        }
        else if (secondButton.contains(localX, localY) && multiplayer.canLocalPlayerAct())
        {
            confirmOrExecuteResign();
        }
        else if (thirdButton.contains(localX, localY))
        {
            confirmOrExecuteLeave();
        }
        return event;
    }

    private void confirmOrExecuteResign()
    {
        if (isConfirmed(PendingAction.RESIGN))
        {
            clearPendingAction();
            multiplayer.resign();
            return;
        }
        arm(PendingAction.RESIGN);
    }

    private void confirmOrExecuteLeave()
    {
        if (isConfirmed(PendingAction.LEAVE))
        {
            clearPendingAction();
            multiplayer.leaveMatch();
            boardOverlay.setVisible(false);
            setVisible(false);
            return;
        }
        arm(PendingAction.LEAVE);
    }

    private void arm(PendingAction action)
    {
        pendingAction = action;
        confirmUntilMillis = System.currentTimeMillis() + CONFIRM_MILLIS;
    }

    private boolean isConfirmed(PendingAction action)
    {
        return pendingAction == action && System.currentTimeMillis() <= confirmUntilMillis;
    }

    private void expireConfirmationIfNeeded()
    {
        if (pendingAction != PendingAction.NONE && System.currentTimeMillis() > confirmUntilMillis)
        {
            clearPendingAction();
        }
    }

    private void clearPendingAction()
    {
        pendingAction = PendingAction.NONE;
        confirmUntilMillis = 0L;
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent event)
    {
        if (consumingMouseSequence || isInside(event))
        {
            event.consume();
            SwingUtilities.invokeLater(() -> consumingMouseSequence = false);
        }
        return event;
    }

    @Override
    public MouseEvent mouseClicked(MouseEvent event)
    {
        if (consumingMouseSequence || isInside(event))
        {
            event.consume();
        }
        return event;
    }

    @Override
    public MouseEvent mouseDragged(MouseEvent event)
    {
        if (consumingMouseSequence || isInside(event))
        {
            event.consume();
        }
        return event;
    }

    @Override
    public MouseEvent mouseMoved(MouseEvent event)
    {
        if (!isInside(event))
        {
            hoveredButton = 0;
            return event;
        }

        int localX = event.getX() - getBounds().x;
        int localY = event.getY() - getBounds().y;
        if (firstButton.contains(localX, localY))
        {
            hoveredButton = 1;
        }
        else if (secondButton.contains(localX, localY))
        {
            hoveredButton = 2;
        }
        else if (thirdButton.contains(localX, localY))
        {
            hoveredButton = 3;
        }
        else
        {
            hoveredButton = 0;
        }
        return event;
    }

    @Override
    public MouseEvent mouseEntered(MouseEvent event)
    {
        return mouseMoved(event);
    }

    @Override
    public MouseEvent mouseExited(MouseEvent event)
    {
        hoveredButton = 0;
        return event;
    }

    private boolean isInside(MouseEvent event)
    {
        return visible && boardOverlay.isVisible()
                && multiplayer.isPlayingOnline() && getBounds().contains(event.getPoint());
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
        if (metrics.stringWidth(value) > width)
        {
            String suffix = "…";
            while (!value.isEmpty() && metrics.stringWidth(value + suffix) > width)
            {
                value = value.substring(0, value.length() - 1);
            }
            value += suffix;
        }
        drawCentered(g, value, x, y, width, height);
    }
}
