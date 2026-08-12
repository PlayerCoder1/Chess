package com.playercoder1.ui;

import com.playercoder1.chess.ChessMatchmakingService;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;


@Singleton
public final class ChessLobbyOverlay extends Overlay implements MouseListener
{
    private static final int WIDTH = 252;
    private static final int PADDING = 10;
    private static final int HEADER_HEIGHT = 50;
    private static final int ROW_HEIGHT = 27;
    private static final int ROW_GAP = 3;
    private static final int FOOTER_HEIGHT = 58;

    private static final Color BACKGROUND = new Color(22, 22, 22, 242);
    private static final Color HEADER = new Color(32, 32, 32, 248);
    private static final Color BORDER = new Color(190, 145, 68);
    private static final Color ROW = new Color(48, 48, 48, 245);
    private static final Color ROW_HOVER = new Color(64, 64, 64, 248);
    private static final Color ROW_SELECTED = new Color(92, 70, 37, 248);
    private static final Color TEXT = new Color(238, 238, 238);
    private static final Color MUTED = new Color(183, 183, 183);
    private static final Color ACCENT = new Color(221, 172, 78);
    private static final Color SUCCESS = new Color(105, 177, 101);
    private static final Color DANGER = new Color(202, 88, 79);

    private final Client client;
    private final ClientThread clientThread;
    private final ChessMatchmakingService matchmaking;
    private final List<QueueHitbox> queueHitboxes = new ArrayList<>();
    private final Rectangle closeBounds = new Rectangle();

    private boolean open;
    private boolean consumingMouseSequence;
    private int hoveredQueue = -1;

    @Inject
    public ChessLobbyOverlay(Client client, ClientThread clientThread, ChessMatchmakingService matchmaking)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.matchmaking = matchmaking;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_HIGH);
        setMovable(true);
        setResizable(false);
        setPreferredLocation(new Point(30, 80));
    }

    public void openAt(int canvasX, int canvasY)
    {
        int height = overlayHeight();
        int maxX = Math.max(10, client.getCanvasWidth() - WIDTH - 10);
        int maxY = Math.max(10, client.getCanvasHeight() - height - 10);
        int x = clamp(canvasX + 16, 10, maxX);
        int y = clamp(canvasY - 80, 10, maxY);
        setPreferredLocation(new Point(x, y));
        open = true;
        revalidate();
    }

    public void close()
    {
        open = false;
        hoveredQueue = -1;
        queueHitboxes.clear();
        closeBounds.setBounds(0, 0, 0, 0);
    }

    public boolean isOpen()
    {
        return open;
    }
    @Override
    public MouseEvent mouseEntered(MouseEvent event)
    {
        return mouseMoved(event);
    }
    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!open || !matchmaking.isLobbyOpen())
        {
            return null;
        }

        int[][] queues = ChessMatchmakingService.queuePresets();
        int height = overlayHeight();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        graphics.setColor(BACKGROUND);
        graphics.fillRoundRect(0, 0, WIDTH, height, 10, 10);
        graphics.setColor(BORDER);
        graphics.drawRoundRect(0, 0, WIDTH - 1, height - 1, 10, 10);

        graphics.setColor(HEADER);
        graphics.fillRoundRect(1, 1, WIDTH - 2, HEADER_HEIGHT, 9, 9);
        graphics.fillRect(1, HEADER_HEIGHT - 9, WIDTH - 2, 10);

        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        graphics.setColor(TEXT);
        drawCentered(graphics, "VARROCK CHESS CLUB", 0, 6, WIDTH, 20);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        graphics.setColor(MUTED);
        drawCentered(graphics, "UNRATED QUICK MATCH", 0, 26, WIDTH, 16);

        queueHitboxes.clear();
        int y = HEADER_HEIGHT + PADDING;
        for (int i = 0; i < queues.length; i++)
        {
            int minutes = queues[i][0];
            int increment = queues[i][1];
            Rectangle rowBounds = new Rectangle(PADDING, y, WIDTH - PADDING * 2, ROW_HEIGHT);
            queueHitboxes.add(new QueueHitbox(rowBounds, minutes, increment));

            boolean selected = matchmaking.isSelectedQueue(minutes, increment);
            graphics.setColor(selected ? ROW_SELECTED : (hoveredQueue == i ? ROW_HOVER : ROW));
            graphics.fillRoundRect(rowBounds.x, rowBounds.y, rowBounds.width, rowBounds.height, 6, 6);
            graphics.setColor(selected ? ACCENT : new Color(76, 76, 76));
            graphics.drawRoundRect(rowBounds.x, rowBounds.y, rowBounds.width - 1, rowBounds.height - 1, 6, 6);

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            graphics.setColor(TEXT);
            graphics.drawString(minutes + "+" + increment, rowBounds.x + 10, rowBounds.y + 18);

            int count = matchmaking.getQueueCount(minutes, increment);
            String countText = count == 1 ? "1 waiting" : count + " waiting";
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            graphics.setColor(count > 0 ? SUCCESS : MUTED);
            FontMetrics metrics = graphics.getFontMetrics();
            graphics.drawString(
                countText,
                rowBounds.x + rowBounds.width - metrics.stringWidth(countText) - 10,
                rowBounds.y + 18);

            y += ROW_HEIGHT + ROW_GAP;
        }

        int footerY = y + 3;
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        graphics.setColor(matchmaking.isConnecting() ? ACCENT : MUTED);
        drawCentered(graphics, statusText(), PADDING, footerY, WIDTH - PADDING * 2, 18);

        closeBounds.setBounds(PADDING, footerY + 22, WIDTH - PADDING * 2, 25);
        graphics.setColor(new Color(83, 43, 43, 245));
        graphics.fillRoundRect(closeBounds.x, closeBounds.y, closeBounds.width, closeBounds.height, 6, 6);
        graphics.setColor(DANGER);
        graphics.drawRoundRect(closeBounds.x, closeBounds.y, closeBounds.width - 1, closeBounds.height - 1, 6, 6);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        graphics.setColor(TEXT);
        drawCentered(graphics, "Leave lobby", closeBounds.x, closeBounds.y, closeBounds.width, closeBounds.height);

        return new Dimension(WIDTH, height);
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
            clientThread.invokeLater(() ->
            {
                matchmaking.closeLobby();
                close();
            });
            return event;
        }

        if (matchmaking.isConnecting())
        {
            return event;
        }

        for (QueueHitbox hitbox : queueHitboxes)
        {
            if (!hitbox.bounds.contains(localX, localY))
            {
                continue;
            }

            int minutes = hitbox.minutes;
            int increment = hitbox.increment;
            clientThread.invokeLater(() ->
            {
                if (matchmaking.isSelectedQueue(minutes, increment))
                {
                    matchmaking.cancelQueue();
                    return;
                }

                try
                {
                    matchmaking.joinQueue(minutes, increment);
                }
                catch (IllegalArgumentException | IllegalStateException ignored)
                {

                }
            });
            return event;
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
        hoveredQueue = -1;
        if (!isInsideOverlay(event))
        {
            return event;
        }

        int localX = event.getX() - getBounds().x;
        int localY = event.getY() - getBounds().y;
        for (int i = 0; i < queueHitboxes.size(); i++)
        {
            if (queueHitboxes.get(i).bounds.contains(localX, localY))
            {
                hoveredQueue = i;
                break;
            }
        }
        return event;
    }

    @Override
    public MouseEvent mouseExited(MouseEvent event)
    {
        hoveredQueue = -1;
        return event;
    }

    private boolean isInsideOverlay(MouseEvent event)
    {
        return open && matchmaking.isLobbyOpen() && getBounds().contains(event.getPoint());
    }

    private String statusText()
    {
        if (matchmaking.isConnecting())
        {
            return "Opponent found — connecting…";
        }
        if (matchmaking.isSearching())
        {
            return "Searching " + formatElapsed(matchmaking.getSearchElapsedSeconds())
                + " — click the selected queue to cancel";
        }
        return "Choose a time control to enter the queue";
    }

    private static String formatElapsed(long seconds)
    {
        long safe = Math.max(0L, seconds);
        return String.format("%d:%02d", safe / 60L, safe % 60L);
    }

    private static int overlayHeight()
    {
        int rows = ChessMatchmakingService.queuePresets().length;
        return HEADER_HEIGHT + PADDING + rows * ROW_HEIGHT + Math.max(0, rows - 1) * ROW_GAP + FOOTER_HEIGHT;
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private static void drawCentered(Graphics2D graphics, String text, int x, int y, int width, int height)
    {
        FontMetrics metrics = graphics.getFontMetrics();
        int textX = x + (width - metrics.stringWidth(text)) / 2;
        int textY = y + (height - metrics.getHeight()) / 2 + metrics.getAscent();
        graphics.drawString(text, textX, textY);
    }

    private static final class QueueHitbox
    {
        private final Rectangle bounds;
        private final int minutes;
        private final int increment;

        private QueueHitbox(Rectangle bounds, int minutes, int increment)
        {
            this.bounds = bounds;
            this.minutes = minutes;
            this.increment = increment;
        }
    }
}
