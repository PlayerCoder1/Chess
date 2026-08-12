package com.playercoder1.ui;

import com.playercoder1.chess.BotDifficulty;
import com.playercoder1.chess.ChessColor;
import com.playercoder1.chess.ChessMatchmakingService;
import com.playercoder1.chess.ChessMultiplayerService;
import com.playercoder1.chess.ChessTimeControl;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;


@Singleton
public final class ChessClubOverlay extends Overlay implements MouseListener
{
    private enum Page
    {
        HOME,
        LOCAL,
        COMPUTER,
        PRIVATE_MENU,
        PRIVATE_CREATE,
        PRIVATE_WAITING,
        PRIVATE_JOINING
    }

    private static final int WIDTH = 282;
    private static final int PADDING = 12;
    private static final int HEADER_HEIGHT = 54;
    private static final int ROW_HEIGHT = 32;
    private static final int ROW_GAP = 6;

    private static final Color BACKGROUND = new Color(22, 22, 22, 244);
    private static final Color HEADER = new Color(32, 32, 32, 250);
    private static final Color BORDER = new Color(190, 145, 68);
    private static final Color BUTTON = new Color(51, 51, 51, 248);
    private static final Color BUTTON_HOVER = new Color(68, 68, 68, 250);
    private static final Color PRIMARY = new Color(78, 62, 34, 250);
    private static final Color PRIMARY_HOVER = new Color(101, 78, 39, 250);
    private static final Color DANGER = new Color(82, 43, 43, 248);
    private static final Color DANGER_HOVER = new Color(104, 49, 49, 250);
    private static final Color TEXT = new Color(239, 239, 239);
    private static final Color MUTED = new Color(181, 181, 181);
    private static final Color ACCENT = new Color(221, 172, 78);
    private static final Color SUCCESS = new Color(112, 184, 106);

    private static final int[] MINUTES = {1, 3, 5, 10, 15};
    private static final int[] INCREMENTS = {0, 1, 2, 3, 5, 10, 15, 30};

    private final Client client;
    private final ClientThread clientThread;
    private final ChessMultiplayerService multiplayer;
    private final ChessMatchmakingService matchmaking;
    private final ChessBoardOverlay boardOverlay;
    private final List<ButtonHitbox> buttons = new ArrayList<>();

    private boolean open;
    private boolean consumingMouseSequence;
    private Page page = Page.HOME;
    private int hoveredButton = -1;
    private int selectedMinutes = ChessTimeControl.DEFAULT_MINUTES;
    private int selectedIncrement = ChessTimeControl.DEFAULT_INCREMENT_SECONDS;
    private BotDifficulty selectedBot = BotDifficulty.GOBLIN;
    private ChessColor humanColor = ChessColor.WHITE;
    private String feedback;
    private long feedbackUntil;

    @Inject
    public ChessClubOverlay(
        Client client,
        ClientThread clientThread,
        ChessMultiplayerService multiplayer,
        ChessMatchmakingService matchmaking,
        ChessBoardOverlay boardOverlay)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.multiplayer = multiplayer;
        this.matchmaking = matchmaking;
        this.boardOverlay = boardOverlay;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_HIGH);
        setMovable(true);
        setResizable(false);
        setPreferredLocation(new Point(30, 80));
    }

    public void openAt(int canvasX, int canvasY)
    {
        if (!multiplayer.isOnline())
        {
            page = Page.HOME;
        }
        syncPageWithSession();
        showAt(canvasX, canvasY);
    }


    public void openPrivateAt(int canvasX, int canvasY)
    {
        if (!multiplayer.isOnline())
        {
            page = Page.PRIVATE_MENU;
        }
        syncPageWithSession();
        showAt(canvasX, canvasY);
    }

    private void showAt(int canvasX, int canvasY)
    {
        int height = heightForPage();
        int maxX = Math.max(10, client.getCanvasWidth() - WIDTH - 10);
        int maxY = Math.max(10, client.getCanvasHeight() - height - 10);
        setPreferredLocation(new Point(
            clamp(canvasX + 16, 10, maxX),
            clamp(canvasY - 70, 10, maxY)));
        open = true;
        revalidate();
    }

    public void close()
    {
        open = false;
        hoveredButton = -1;
        buttons.clear();
    }

    public boolean isOpen()
    {
        return open;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!open)
        {
            return null;
        }

        syncPageWithSession();
        int height = heightForPage();
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
        drawCentered(graphics, "VARROCK CHESS CLUB", 0, 7, WIDTH, 20);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        graphics.setColor(MUTED);
        drawCentered(graphics, subtitle(), 0, 28, WIDTH, 16);

        buttons.clear();
        int y = HEADER_HEIGHT + PADDING;
        switch (page)
        {
            case HOME:
                y = addButton(graphics, y, "Local game", "Two players on this client", 1, false, false);
                y = addButton(graphics, y, "Play computer", "Choose an OSRS-themed opponent", 2, false, false);
                y = addButton(graphics, y, "Private match", "Create or join with a secret code", 3, true, false);
                addButton(graphics, y, "Close", null, 4, false, true);
                break;
            case LOCAL:
                y = addSelector(graphics, y, "Minutes", String.valueOf(selectedMinutes), 10);
                y = addSelector(graphics, y, "Increment", selectedIncrement + " sec", 11);
                y += 3;
                y = addButton(graphics, y, "Start local game", timeControl(), 12, true, false);
                addButton(graphics, y, "Back", null, 13, false, false);
                break;
            case COMPUTER:
                y = addSelector(graphics, y, "Opponent",
                    selectedBot.getDisplayName() + "  ~" + selectedBot.getApproximateRating(), 20);
                y = addSelector(graphics, y, "Your color", humanColor.displayName(), 21);
                y = addSelector(graphics, y, "Minutes", String.valueOf(selectedMinutes), 22);
                y = addSelector(graphics, y, "Increment", selectedIncrement + " sec", 23);
                y += 3;
                y = addButton(graphics, y, "Start vs " + selectedBot.getDisplayName(), timeControl(), 24, true, false);
                addButton(graphics, y, "Back", null, 25, false, false);
                break;
            case PRIVATE_MENU:
                y = addButton(graphics, y, "Create private match", "Generate a one-use friend code", 30, true, false);
                y = addButton(graphics, y, "Join private match", "Enter the code your friend sent", 31, false, false);
                addButton(graphics, y, "Back", null, 32, false, false);
                break;
            case PRIVATE_CREATE:
                y = addSelector(graphics, y, "Minutes", String.valueOf(selectedMinutes), 40);
                y = addSelector(graphics, y, "Increment", selectedIncrement + " sec", 41);
                y += 3;
                y = addButton(graphics, y, "Create private match", timeControl(), 42, true, false);
                addButton(graphics, y, "Back", null, 43, false, false);
                break;
            case PRIVATE_WAITING:
                y = drawWaitingRoom(graphics, y);
                y = addButton(graphics, y, "Copy invite code", null, 50, true, false);
                addButton(graphics, y, "Cancel room", null, 51, false, true);
                break;
            case PRIVATE_JOINING:
                y = drawJoiningRoom(graphics, y);
                addButton(graphics, y, "Cancel", null, 60, false, true);
                break;
            default:
                break;
        }

        drawFeedback(graphics, height);
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
        for (ButtonHitbox button : buttons)
        {
            if (button.bounds.contains(localX, localY))
            {
                handleAction(button.action);
                break;
            }
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
        hoveredButton = -1;
        if (!isInsideOverlay(event))
        {
            return event;
        }

        int localX = event.getX() - getBounds().x;
        int localY = event.getY() - getBounds().y;
        for (int i = 0; i < buttons.size(); i++)
        {
            if (buttons.get(i).bounds.contains(localX, localY))
            {
                hoveredButton = i;
                break;
            }
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
        hoveredButton = -1;
        return event;
    }

    private void handleAction(int action)
    {
        switch (action)
        {
            case 1:
                page = Page.LOCAL;
                break;
            case 2:
                page = Page.COMPUTER;
                break;
            case 3:
                page = Page.PRIVATE_MENU;
                break;
            case 4:
                close();
                break;
            case 10:
            case 22:
            case 40:
                selectedMinutes = nextValue(MINUTES, selectedMinutes);
                break;
            case 11:
            case 23:
            case 41:
                selectedIncrement = nextValue(INCREMENTS, selectedIncrement);
                break;
            case 12:
                startLocalGame();
                break;
            case 13:
            case 25:
            case 32:
                page = Page.HOME;
                break;
            case 20:
                selectedBot = nextBot(selectedBot);
                break;
            case 21:
                humanColor = humanColor.opposite();
                break;
            case 24:
                startComputerGame();
                break;
            case 30:
                page = Page.PRIVATE_CREATE;
                break;
            case 31:
                promptJoinCode();
                break;
            case 42:
                createPrivateMatch();
                break;
            case 43:
                page = Page.PRIVATE_MENU;
                break;
            case 50:
                copyInviteCode();
                break;
            case 51:
            case 60:
                cancelPrivateMatch();
                break;
            default:
                break;
        }
    }

    private void startLocalGame()
    {
        clientThread.invokeLater(() ->
        {
            closeMatchmakingIfNeeded();
            multiplayer.startLocalGame(selectedMinutes, selectedIncrement);
            boardOverlay.setFlipped(false);
            boardOverlay.setVisible(true);
            boardOverlay.fitToCanvasIfNeeded();
            close();
        });
    }

    private void startComputerGame()
    {
        final BotDifficulty bot = selectedBot;
        final ChessColor side = humanColor;
        clientThread.invokeLater(() ->
        {
            closeMatchmakingIfNeeded();
            multiplayer.startBotGame(bot, side, selectedMinutes, selectedIncrement);
            boardOverlay.setFlipped(side == ChessColor.BLACK);
            boardOverlay.setVisible(true);
            boardOverlay.fitToCanvasIfNeeded();
            close();
        });
    }

    private void createPrivateMatch()
    {
        clientThread.invokeLater(() ->
        {
            try
            {
                closeMatchmakingIfNeeded();
                multiplayer.createPrivateMatch(selectedMinutes, selectedIncrement);
                page = Page.PRIVATE_WAITING;
                setFeedback("Room created. Send the code to your friend.", false);
            }
            catch (IllegalArgumentException | IllegalStateException ex)
            {
                setFeedback(ex.getMessage(), true);
            }
        });
    }

    private void promptJoinCode()
    {
        SwingUtilities.invokeLater(() ->
        {
            String code = JOptionPane.showInputDialog(
                null,
                "Enter the 8-character private match code:",
                "Join private chess match",
                JOptionPane.PLAIN_MESSAGE);
            if (code == null)
            {
                return;
            }

            clientThread.invokeLater(() ->
            {
                try
                {
                    closeMatchmakingIfNeeded();
                    multiplayer.joinPrivateMatch(code);
                    page = Page.PRIVATE_JOINING;
                    setFeedback("Connecting to private match…", false);
                }
                catch (IllegalArgumentException | IllegalStateException ex)
                {
                    page = Page.PRIVATE_MENU;
                    setFeedback(ex.getMessage(), true);
                }
            });
        });
    }

    private void copyInviteCode()
    {
        String code = multiplayer.getInvitationCode();
        if (code == null || code.isEmpty())
        {
            setFeedback("No active private room code.", true);
            return;
        }

        try
        {
            String formatted = ChessMultiplayerService.formatCode(code);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(formatted), null);
            setFeedback("Invite code copied.", false);
        }
        catch (RuntimeException ex)
        {
            setFeedback("Could not copy the invite code.", true);
        }
    }

    private void cancelPrivateMatch()
    {
        clientThread.invokeLater(() ->
        {
            multiplayer.leaveMatch();
            page = Page.PRIVATE_MENU;
            setFeedback("Private room closed.", false);
        });
    }

    private void closeMatchmakingIfNeeded()
    {
        if (matchmaking.isLobbyOpen())
        {
            matchmaking.closeLobby();
        }
    }

    private void syncPageWithSession()
    {
        if (!multiplayer.isOnline())
        {
            if (page == Page.PRIVATE_WAITING || page == Page.PRIVATE_JOINING)
            {
                page = Page.PRIVATE_MENU;
            }
            return;
        }

        if (multiplayer.getMatchKind() != ChessMultiplayerService.MatchKind.PRIVATE)
        {
            close();
            return;
        }

        switch (multiplayer.getMode())
        {
            case HOST_WAITING:
                page = Page.PRIVATE_WAITING;
                break;
            case GUEST_JOINING:
                page = Page.PRIVATE_JOINING;
                break;
            case HOST_PLAYING:
            case GUEST_PLAYING:
                close();
                break;
            default:
                break;
        }
    }

    private int drawWaitingRoom(Graphics2D graphics, int y)
    {
        String code = ChessMultiplayerService.formatCode(multiplayer.getInvitationCode());
        graphics.setColor(new Color(39, 39, 39, 248));
        graphics.fillRoundRect(PADDING, y, WIDTH - PADDING * 2, 65, 7, 7);
        graphics.setColor(new Color(72, 72, 72));
        graphics.drawRoundRect(PADDING, y, WIDTH - PADDING * 2 - 1, 64, 7, 7);

        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        graphics.setColor(MUTED);
        drawCentered(graphics, "INVITE CODE", PADDING, y + 6, WIDTH - PADDING * 2, 15);
        graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
        graphics.setColor(ACCENT);
        drawCentered(graphics, code == null ? "--------" : code, PADDING, y + 22, WIDTH - PADDING * 2, 30);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        graphics.setColor(SUCCESS);
        drawCentered(graphics, "Waiting for your friend…", PADDING, y + 48, WIDTH - PADDING * 2, 14);
        return y + 65 + ROW_GAP;
    }

    private int drawJoiningRoom(Graphics2D graphics, int y)
    {
        String code = ChessMultiplayerService.formatCode(multiplayer.getInvitationCode());
        graphics.setColor(new Color(39, 39, 39, 248));
        graphics.fillRoundRect(PADDING, y, WIDTH - PADDING * 2, 66, 7, 7);
        graphics.setColor(new Color(72, 72, 72));
        graphics.drawRoundRect(PADDING, y, WIDTH - PADDING * 2 - 1, 65, 7, 7);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        graphics.setColor(TEXT);
        drawCentered(graphics, "Connecting to private match", PADDING, y + 8, WIDTH - PADDING * 2, 18);
        graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 17));
        graphics.setColor(ACCENT);
        drawCentered(graphics, code == null ? "--------" : code, PADDING, y + 28, WIDTH - PADDING * 2, 22);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        graphics.setColor(MUTED);
        drawCentered(graphics, "The board opens automatically when connected.", PADDING, y + 49, WIDTH - PADDING * 2, 14);
        return y + 66 + ROW_GAP;
    }

    private int addSelector(Graphics2D graphics, int y, String label, String value, int action)
    {
        Rectangle bounds = new Rectangle(PADDING, y, WIDTH - PADDING * 2, ROW_HEIGHT);
        int index = buttons.size();
        buttons.add(new ButtonHitbox(bounds, action));
        graphics.setColor(hoveredButton == index ? BUTTON_HOVER : BUTTON);
        graphics.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);
        graphics.setColor(new Color(78, 78, 78));
        graphics.drawRoundRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1, 6, 6);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        graphics.setColor(MUTED);
        graphics.drawString(label, bounds.x + 10, bounds.y + 20);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        graphics.setColor(TEXT);
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(value + "  ›", bounds.x + bounds.width - metrics.stringWidth(value + "  ›") - 10, bounds.y + 20);
        return y + ROW_HEIGHT + ROW_GAP;
    }

    private int addButton(
        Graphics2D graphics,
        int y,
        String title,
        String detail,
        int action,
        boolean primary,
        boolean danger)
    {
        int height = detail == null ? ROW_HEIGHT : 42;
        Rectangle bounds = new Rectangle(PADDING, y, WIDTH - PADDING * 2, height);
        int index = buttons.size();
        buttons.add(new ButtonHitbox(bounds, action));

        Color base = danger ? DANGER : (primary ? PRIMARY : BUTTON);
        Color hover = danger ? DANGER_HOVER : (primary ? PRIMARY_HOVER : BUTTON_HOVER);
        graphics.setColor(hoveredButton == index ? hover : base);
        graphics.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);
        graphics.setColor(primary ? BORDER : new Color(80, 80, 80));
        graphics.drawRoundRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1, 6, 6);

        if (detail == null)
        {
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            graphics.setColor(TEXT);
            drawCentered(graphics, title, bounds.x, bounds.y, bounds.width, bounds.height);
        }
        else
        {
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            graphics.setColor(TEXT);
            graphics.drawString(title, bounds.x + 10, bounds.y + 17);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            graphics.setColor(MUTED);
            graphics.drawString(ellipsize(graphics, detail, bounds.width - 20), bounds.x + 10, bounds.y + 33);
        }
        return y + height + ROW_GAP;
    }

    private void drawFeedback(Graphics2D graphics, int height)
    {
        if (feedback == null || System.currentTimeMillis() > feedbackUntil)
        {
            feedback = null;
            return;
        }
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        graphics.setColor(MUTED);
        drawCentered(graphics, ellipsize(graphics, feedback, WIDTH - PADDING * 2),
            PADDING, height - 22, WIDTH - PADDING * 2, 14);
    }

    private void setFeedback(String message, boolean error)
    {
        feedback = message == null ? (error ? "Something went wrong." : "Done.") : message;
        feedbackUntil = System.currentTimeMillis() + 4500L;
    }

    private String subtitle()
    {
        switch (page)
        {
            case LOCAL:
                return "LOCAL TWO-PLAYER GAME";
            case COMPUTER:
                return "COMPUTER OPPONENT";
            case PRIVATE_MENU:
            case PRIVATE_CREATE:
            case PRIVATE_WAITING:
            case PRIVATE_JOINING:
                return "PRIVATE FRIEND MATCH";
            default:
                return "CHOOSE HOW YOU WANT TO PLAY";
        }
    }

    private int heightForPage()
    {
        switch (page)
        {
            case HOME:
                return HEADER_HEIGHT + PADDING + 42 * 3 + ROW_HEIGHT + ROW_GAP * 3 + 32;
            case LOCAL:
                return HEADER_HEIGHT + PADDING + ROW_HEIGHT * 4 + ROW_GAP * 3 + 36;
            case COMPUTER:
                return HEADER_HEIGHT + PADDING + ROW_HEIGHT * 6 + ROW_GAP * 5 + 38;
            case PRIVATE_MENU:
                return HEADER_HEIGHT + PADDING + 42 * 2 + ROW_HEIGHT + ROW_GAP * 2 + 32;
            case PRIVATE_CREATE:
                return HEADER_HEIGHT + PADDING + ROW_HEIGHT * 4 + ROW_GAP * 3 + 36;
            case PRIVATE_WAITING:
                return HEADER_HEIGHT + PADDING + 65 + ROW_HEIGHT * 2 + ROW_GAP * 2 + 36;
            case PRIVATE_JOINING:
                return HEADER_HEIGHT + PADDING + 66 + ROW_HEIGHT + ROW_GAP + 36;
            default:
                return 250;
        }
    }

    private String timeControl()
    {
        return selectedMinutes + "+" + selectedIncrement;
    }

    private boolean isInsideOverlay(MouseEvent event)
    {
        return open && getBounds().contains(event.getPoint());
    }

    private static int nextValue(int[] values, int current)
    {
        for (int i = 0; i < values.length; i++)
        {
            if (values[i] == current)
            {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    private static BotDifficulty nextBot(BotDifficulty current)
    {
        BotDifficulty[] values = BotDifficulty.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private static String ellipsize(Graphics2D graphics, String text, int maxWidth)
    {
        FontMetrics metrics = graphics.getFontMetrics();
        if (metrics.stringWidth(text) <= maxWidth)
        {
            return text;
        }
        String suffix = "…";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + suffix) > maxWidth)
        {
            end--;
        }
        return end == 0 ? suffix : text.substring(0, end) + suffix;
    }

    private static void drawCentered(Graphics2D graphics, String text, int x, int y, int width, int height)
    {
        FontMetrics metrics = graphics.getFontMetrics();
        int textX = x + (width - metrics.stringWidth(text)) / 2;
        int textY = y + (height - metrics.getHeight()) / 2 + metrics.getAscent();
        graphics.drawString(text, textX, textY);
    }

    private static final class ButtonHitbox
    {
        private final Rectangle bounds;
        private final int action;

        private ButtonHitbox(Rectangle bounds, int action)
        {
            this.bounds = bounds;
            this.action = action;
        }
    }
}
