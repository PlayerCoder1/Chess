package com.playercoder1;

import com.playercoder1.chess.BotDifficulty;
import com.playercoder1.chess.ChessBotService;
import com.playercoder1.chess.ChessClock;
import com.playercoder1.chess.ChessColor;
import com.playercoder1.chess.ChessGameListener;
import com.playercoder1.chess.ChessMatchmakingService;
import com.playercoder1.chess.ChessMultiplayerService;
import com.playercoder1.chess.ChessTimeControl;
import com.playercoder1.chess.LocalChessController;
import com.playercoder1.ui.ChessBoardOverlay;
import com.playercoder1.ui.ChessBoardPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import javax.inject.Inject;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;


@SuppressWarnings("serial")
public final class ChessPanel extends PluginPanel
    implements ChessGameListener, ChessMultiplayerService.Listener,
    ChessBotService.Listener
{
    private static final Integer[] MINUTES = ChessTimeControl.minuteOptions();
    private static final Integer[] INCREMENTS = ChessTimeControl.incrementOptions();

    private static final Color ACCENT = new Color(190, 145, 68);
    private static final Color ACCENT_HOVER = new Color(211, 167, 89);
    private static final Color SUCCESS = new Color(106, 176, 102);
    private static final Color WARNING = new Color(222, 171, 76);
    private static final Color DANGER = new Color(202, 88, 79);
    private static final Color MUTED_TEXT = new Color(178, 178, 178);
    private static final Color CARD_BACKGROUND = new Color(39, 39, 39);
    private static final Color CARD_BORDER = new Color(70, 70, 70);
    private static final Color DISABLED_BUTTON_TEXT = new Color(186, 186, 186);


    private static final Font CONTROL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font CONTROL_FONT_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font CONTROL_FONT_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

    private static final String BOARD_CARD = "board";
    private static final String OVERLAY_CARD = "overlay";
    private static final String GAME_DRAWER = "game";
    private static final String PRIVATE_DRAWER = "private";

    private final LocalChessController controller;
    private final ChessMultiplayerService multiplayer;
    private final ChessMatchmakingService matchmaking;
    private final ChessBotService botService;
    private final ChessBoardPanel boardPanel;
    private final ChessBoardOverlay boardOverlay;

    private final JLabel modeBadge = new JLabel("LOCAL", SwingConstants.CENTER);
    private final JLabel timeControlLabel = new JLabel("10 min", SwingConstants.RIGHT);
    private final JLabel topClockLabel = new JLabel("Black  10:00", SwingConstants.CENTER);
    private final JLabel bottomClockLabel = new JLabel("White  10:00", SwingConstants.CENTER);
    private final JLabel statusText = new JLabel("White to move.", SwingConstants.CENTER);
    private final JLabel feedbackText = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel sessionTitle = new JLabel("Private match");
    private final JLabel sessionDetail = new JLabel(" ");
    private final JLabel connectionDot = new JLabel("●");

    private final JPanel boardHost = new JPanel(new CardLayout());
    private final JPanel drawerHost = new RoundedPanel(CARD_BACKGROUND, CARD_BORDER, 10);
    private final JPanel drawerBody = new JPanel();
    private final JPanel sessionCard = new RoundedPanel(CARD_BACKGROUND, CARD_BORDER, 10);

    private final JComboBox<Integer> minutesBox = new JComboBox<>(MINUTES);
    private final JComboBox<Integer> incrementBox = new JComboBox<>(INCREMENTS);
    private final JComboBox<BotDifficulty> botDifficultyBox = new JComboBox<>(BotDifficulty.values());

    private final JButton overlayButton = primaryButton("Show board");
    private final JButton flipButton = secondaryButton("Flip");
    private final JButton gameButton = secondaryButton("Game");
    private final JButton privateButton = secondaryButton("Private");
    private final JTextField privateCodeField = new JTextField();

    private final Timer uiTimer;
    private final Timer feedbackTimer;
    private Timer confirmTimer;
    private JButton armedButton;
    private String openDrawer;
    private String lastDrawerStateKey = "";
    private boolean listenersRegistered;


    private ChessColor lastAutoOrientedColor;

    @Inject
    public ChessPanel(
        LocalChessController controller,
        ChessMultiplayerService multiplayer,
        ChessMatchmakingService matchmaking,
        ChessBotService botService,
        ChessBoardOverlay boardOverlay)
    {

        super();
        this.controller = controller;
        this.multiplayer = multiplayer;
        this.matchmaking = matchmaking;
        this.botService = botService;
        this.boardOverlay = boardOverlay;
        this.boardPanel = new ChessBoardPanel(controller, multiplayer);

        minutesBox.setSelectedItem(ChessTimeControl.DEFAULT_MINUTES);
        incrementBox.setSelectedItem(ChessTimeControl.DEFAULT_INCREMENT_SECONDS);
        botDifficultyBox.setSelectedItem(BotDifficulty.GOBLIN);

        privateCodeField.setFont(CONTROL_FONT_BOLD);
        privateCodeField.setHorizontalAlignment(SwingConstants.CENTER);
        privateCodeField.setBackground(new Color(31, 31, 31));
        privateCodeField.setForeground(Color.WHITE);
        privateCodeField.setCaretColor(Color.WHITE);
        privateCodeField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(CARD_BORDER),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        privateCodeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 31));
        privateCodeField.setToolTipText("Enter the eight-character private match code");

        uiTimer = new Timer(200, event ->
        {
            multiplayer.tick();
            matchmaking.tick();
        });
        feedbackTimer = new Timer(2600, event ->
        {
            feedbackText.setText(" ");
            feedbackText.setForeground(MUTED_TEXT);
            ((Timer) event.getSource()).stop();
        });
        feedbackTimer.setRepeats(false);

        boardOverlay.setVisibilityListener(visible -> SwingUtilities.invokeLater(() ->
        {
            updateBoardPresentation(visible);
            refreshUi();
        }));


        boardOverlay.setOrientationListener(flipped -> SwingUtilities.invokeLater(() ->
        {
            if (boardPanel.isFlipped() != flipped)
            {
                boardPanel.setFlipped(flipped);
            }
            refreshClockRows();
        }));

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(BorderFactory.createEmptyBorder(7, 5, 10, 5));

        JPanel root = new JPanel();
        root.setOpaque(false);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.add(buildHeader());
        root.add(Box.createRigidArea(new Dimension(0, 7)));
        root.add(buildBoardCard());
        root.add(Box.createRigidArea(new Dimension(0, 7)));
        root.add(buildPrimaryControls());
        root.add(Box.createRigidArea(new Dimension(0, 7)));
        root.add(buildSessionCard());
        root.add(Box.createRigidArea(new Dimension(0, 7)));
        root.add(buildDrawer());
        root.add(Box.createRigidArea(new Dimension(0, 5)));
        root.add(feedbackText);

        feedbackText.setForeground(MUTED_TEXT);
        feedbackText.setFont(CONTROL_FONT_SMALL);
        feedbackText.setAlignmentX(Component.CENTER_ALIGNMENT);
        feedbackText.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

        add(root, BorderLayout.NORTH);

        wireActions();
        updateBoardPresentation(boardOverlay.isVisible());
        refreshUi();
    }

    public void start()
    {
        if (!listenersRegistered)
        {
            controller.addListener(this);
            multiplayer.addListener(this);
            botService.addListener(this);
            listenersRegistered = true;
        }
        uiTimer.start();
        refreshUi();
    }

    public void stop()
    {
        uiTimer.stop();
        feedbackTimer.stop();
        cancelArmedAction();
        boardOverlay.setVisible(false);
        if (listenersRegistered)
        {
            controller.removeListener(this);
            multiplayer.removeListener(this);
            botService.removeListener(this);
            listenersRegistered = false;
        }
    }

    private JPanel buildHeader()
    {
        JPanel header = new RoundedPanel(new Color(31, 31, 31), new Color(82, 66, 42), 10);
        header.setLayout(new BorderLayout(8, 0));
        header.setBorder(BorderFactory.createEmptyBorder(8, 9, 8, 9));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Chess");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        JLabel subtitle = new JLabel("Local, computer & private • public at Varrock");
        subtitle.setForeground(MUTED_TEXT);
        subtitle.setFont(CONTROL_FONT_SMALL);
        text.add(title);
        text.add(Box.createRigidArea(new Dimension(0, 2)));
        text.add(subtitle);

        JPanel meta = new JPanel();
        meta.setOpaque(false);
        meta.setLayout(new BoxLayout(meta, BoxLayout.Y_AXIS));
        styleBadge(modeBadge);
        timeControlLabel.setForeground(MUTED_TEXT);
        timeControlLabel.setFont(timeControlLabel.getFont().deriveFont(Font.PLAIN, 10f));
        timeControlLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        meta.add(modeBadge);
        meta.add(Box.createRigidArea(new Dimension(0, 3)));
        meta.add(timeControlLabel);

        header.add(text, BorderLayout.CENTER);
        header.add(meta, BorderLayout.EAST);
        return header;
    }

    private JPanel buildBoardCard()
    {
        JPanel card = new RoundedPanel(CARD_BACKGROUND, CARD_BORDER, 10);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 390));

        styleClock(topClockLabel);
        styleClock(bottomClockLabel);
        boardPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel overlayPlaceholder = new JPanel();
        overlayPlaceholder.setOpaque(false);
        overlayPlaceholder.setLayout(new BoxLayout(overlayPlaceholder, BoxLayout.Y_AXIS));
        overlayPlaceholder.setPreferredSize(new Dimension(216, 94));
        overlayPlaceholder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 94));

        JLabel openLabel = new JLabel("Board is open over the game", SwingConstants.CENTER);
        openLabel.setForeground(Color.WHITE);
        openLabel.setFont(openLabel.getFont().deriveFont(Font.BOLD, 12f));
        openLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel openHint = new JLabel("Alt-drag to move or resize it", SwingConstants.CENTER);
        openHint.setForeground(MUTED_TEXT);
        openHint.setFont(openHint.getFont().deriveFont(Font.PLAIN, 10f));
        openHint.setAlignmentX(Component.CENTER_ALIGNMENT);
        JButton fitButton = secondaryButton("Fit board to client");
        fitButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        fitButton.setMaximumSize(new Dimension(170, 27));
        fitButton.addActionListener(event -> boardOverlay.fitToCanvas());

        overlayPlaceholder.add(Box.createVerticalGlue());
        overlayPlaceholder.add(openLabel);
        overlayPlaceholder.add(Box.createRigidArea(new Dimension(0, 3)));
        overlayPlaceholder.add(openHint);
        overlayPlaceholder.add(Box.createRigidArea(new Dimension(0, 7)));
        overlayPlaceholder.add(fitButton);
        overlayPlaceholder.add(Box.createVerticalGlue());

        boardHost.setOpaque(false);
        boardHost.add(boardPanel, BOARD_CARD);
        boardHost.add(overlayPlaceholder, OVERLAY_CARD);
        boardHost.setAlignmentX(Component.CENTER_ALIGNMENT);

        statusText.setForeground(new Color(226, 226, 226));
        statusText.setFont(CONTROL_FONT_SMALL);
        statusText.setBorder(BorderFactory.createEmptyBorder(6, 3, 0, 3));
        statusText.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusText.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        card.add(topClockLabel);
        card.add(Box.createRigidArea(new Dimension(0, 5)));
        card.add(boardHost);
        card.add(Box.createRigidArea(new Dimension(0, 5)));
        card.add(bottomClockLabel);
        card.add(statusText);
        return card;
    }

    private JPanel buildPrimaryControls()
    {
        JPanel controls = new JPanel(new GridLayout(2, 2, 5, 5));
        controls.setOpaque(false);
        controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 67));
        controls.add(overlayButton);
        controls.add(flipButton);
        controls.add(gameButton);
        controls.add(privateButton);
        return controls;
    }

    private JPanel buildSessionCard()
    {
        sessionCard.setLayout(new BorderLayout(8, 0));
        sessionCard.setBorder(BorderFactory.createEmptyBorder(8, 9, 8, 9));
        sessionCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        sessionTitle.setForeground(Color.WHITE);
        sessionTitle.setFont(sessionTitle.getFont().deriveFont(Font.BOLD, 12f));
        sessionDetail.setForeground(MUTED_TEXT);
        sessionDetail.setFont(CONTROL_FONT_SMALL);
        text.add(sessionTitle);
        text.add(Box.createRigidArea(new Dimension(0, 3)));
        text.add(sessionDetail);

        connectionDot.setFont(connectionDot.getFont().deriveFont(Font.BOLD, 14f));
        connectionDot.setForeground(WARNING);

        sessionCard.add(text, BorderLayout.CENTER);
        sessionCard.add(connectionDot, BorderLayout.EAST);
        return sessionCard;
    }

    private JPanel buildDrawer()
    {
        drawerHost.setLayout(new BorderLayout());
        drawerHost.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        drawerHost.setVisible(false);
        drawerHost.setMaximumSize(new Dimension(Integer.MAX_VALUE, 440));

        drawerBody.setOpaque(false);
        drawerBody.setLayout(new BoxLayout(drawerBody, BoxLayout.Y_AXIS));
        drawerHost.add(drawerBody, BorderLayout.CENTER);
        return drawerHost;
    }

    private void wireActions()
    {
        overlayButton.addActionListener(event ->
        {
            if (!boardOverlay.isVisible())
            {
                boardOverlay.setVisible(true);
                boardOverlay.fitToCanvasIfNeeded();
            }
            else
            {
                boardOverlay.setVisible(false);
            }
        });

        flipButton.addActionListener(event -> flipBoardFromTable());

        gameButton.addActionListener(event -> toggleDrawer(GAME_DRAWER));
        privateButton.addActionListener(event -> toggleDrawer(PRIVATE_DRAWER));
    }


    public void openGameActionsFromTable()
    {
        showDrawer(GAME_DRAWER);
    }


    public void openPrivateActionsFromTable()
    {
        showDrawer(PRIVATE_DRAWER);
    }


    public void flipBoardFromTable()
    {
        boolean flipped = !boardPanel.isFlipped();
        applyBoardOrientation(flipped);
        showFeedback(flipped ? "Black is at the bottom." : "White is at the bottom.", false);
    }

    
    private void applyBoardOrientation(boolean flipped)
    {
        if (boardPanel.isFlipped() != flipped)
        {
            boardPanel.setFlipped(flipped);
        }
        if (boardOverlay.isFlipped() != flipped)
        {
            boardOverlay.setFlipped(flipped);
        }
        refreshClockRows();
    }

    private void toggleDrawer(String drawer)
    {
        if (drawer.equals(openDrawer))
        {
            openDrawer = null;
            drawerHost.setVisible(false);
            updateActionButtons();
            revalidate();
            repaint();
            return;
        }

        showDrawer(drawer);
    }

    private void showDrawer(String drawer)
    {
        openDrawer = drawer;
        drawerHost.setVisible(true);
        rebuildDrawer();
        updateActionButtons();
        revalidate();
        repaint();
    }

    private void rebuildDrawer()
    {
        drawerBody.removeAll();
        if (GAME_DRAWER.equals(openDrawer))
        {
            buildGameDrawer(drawerBody);
        }
        else if (PRIVATE_DRAWER.equals(openDrawer))
        {
            buildPrivateDrawer(drawerBody);
        }
        lastDrawerStateKey = drawerStateKey();
        drawerBody.revalidate();
        drawerBody.repaint();
    }

    private void buildGameDrawer(JPanel body)
    {
        body.add(drawerHeader("Game actions", "Local games, computer opponents and resign"));
        body.add(Box.createRigidArea(new Dimension(0, 8)));

        boolean local = multiplayer.getMode() == ChessMultiplayerService.Mode.LOCAL;
        JPanel timeRow = labeledChoices();
        timeRow.setEnabled(local);
        setChildrenEnabled(timeRow, local);
        body.add(timeRow);
        body.add(Box.createRigidArea(new Dimension(0, 6)));

        JButton start = primaryButton(local ? "Start two-player local game" : "Online game in progress");
        start.setEnabled(local);
        start.addActionListener(event -> startNewLocalGame());
        body.add(fullWidth(start));

        if (local)
        {
            body.add(sectionDivider());
            body.add(sectionLabel("PLAY AGAINST COMPUTER"));
            body.add(Box.createRigidArea(new Dimension(0, 5)));

            botDifficultyBox.setFont(CONTROL_FONT);
            botDifficultyBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 31));
            botDifficultyBox.setAlignmentX(Component.CENTER_ALIGNMENT);
            body.add(botDifficultyBox);
            body.add(Box.createRigidArea(new Dimension(0, 6)));

            JPanel botRow = new JPanel(new GridLayout(1, 2, 5, 0));
            botRow.setOpaque(false);
            JButton playWhite = secondaryButton("Play White");
            JButton playBlack = secondaryButton("Play Black");
            playWhite.addActionListener(event -> startBotGame(ChessColor.WHITE));
            playBlack.addActionListener(event -> startBotGame(ChessColor.BLACK));
            botRow.add(playWhite);
            botRow.add(playBlack);
            body.add(botRow);
        }

        body.add(Box.createRigidArea(new Dimension(0, 8)));

        JPanel row = new JPanel(new GridLayout(1, 2, 5, 0));
        row.setOpaque(false);
        JButton draw = secondaryButton(drawActionText());
        draw.setEnabled(!botService.isActive()
            && !controller.getGame().getStatus().isFinished()
            && (!multiplayer.isPlayingOnline() || multiplayer.canLocalPlayerAct()));
        draw.addActionListener(event ->
        {
            multiplayer.offerAcceptOrCancelDraw();
            rebuildDrawer();
        });

        JButton resign = dangerButton("Resign");
        resign.setEnabled(!controller.getGame().getStatus().isFinished()
            && (!multiplayer.isPlayingOnline() || multiplayer.canLocalPlayerAct()));
        resign.addActionListener(event -> armDestructiveAction(
            resign,
            "Confirm resign",
            () ->
            {
                multiplayer.resign();
                showFeedback("Game resigned.", true);
            }));
        row.add(draw);
        row.add(resign);
        body.add(row);

        if (!local)
        {
            body.add(sectionDivider());
            body.add(sectionLabel("ONLINE MATCH"));
            body.add(Box.createRigidArea(new Dimension(0, 5)));

            JButton rematch = primaryButton(rematchActionText());
            rematch.setEnabled(multiplayer.isPlayingOnline()
                && controller.getGame().getStatus().isFinished()
                && multiplayer.isOpponentConnected()
                && !multiplayer.isRematchRequestedByLocalPlayer());
            rematch.addActionListener(event ->
            {
                multiplayer.requestOrAcceptRematch();
                rebuildDrawer();
            });
            body.add(fullWidth(rematch));
            body.add(Box.createRigidArea(new Dimension(0, 6)));

            JButton leave = dangerButton("Leave online match");
            leave.addActionListener(event -> armDestructiveAction(
                leave,
                "Confirm leave",
                () ->
                {
                    multiplayer.leaveMatch();
                    showFeedback("Left the online match.", true);
                }));
            body.add(fullWidth(leave));
        }
    }


    private void buildPrivateDrawer(JPanel body)
    {
        body.add(drawerHeader("Private friend match", "Create a room or join with an 8-character code"));
        body.add(Box.createRigidArea(new Dimension(0, 8)));

        if (multiplayer.isMatchmade())
        {
            JLabel notice = new JLabel("A public quick match is currently active.");
            notice.setForeground(MUTED_TEXT);
            notice.setFont(CONTROL_FONT_SMALL);
            body.add(notice);
            body.add(Box.createRigidArea(new Dimension(0, 7)));

            JButton actions = secondaryButton("Open game actions");
            actions.addActionListener(event -> showDrawer(GAME_DRAWER));
            body.add(fullWidth(actions));
            return;
        }

        switch (multiplayer.getMode())
        {
            case LOCAL:
                body.add(sectionLabel("CREATE PRIVATE ROOM"));
                body.add(Box.createRigidArea(new Dimension(0, 5)));
                body.add(labeledChoices());
                body.add(Box.createRigidArea(new Dimension(0, 6)));

                JButton create = primaryButton("Create private match");
                create.addActionListener(event -> createPrivateMatchFromPanel());
                body.add(fullWidth(create));

                body.add(sectionDivider());
                body.add(sectionLabel("JOIN PRIVATE ROOM"));
                body.add(Box.createRigidArea(new Dimension(0, 5)));
                privateCodeField.setText(privateCodeField.getText().toUpperCase());
                body.add(privateCodeField);
                body.add(Box.createRigidArea(new Dimension(0, 6)));

                JButton join = secondaryButton("Join with code");
                join.addActionListener(event -> joinPrivateMatchFromPanel());
                body.add(fullWidth(join));
                body.add(Box.createRigidArea(new Dimension(0, 7)));

                JLabel hint = new JLabel("Codes are one-use and reserve exactly two players.");
                hint.setForeground(MUTED_TEXT);
                hint.setFont(CONTROL_FONT_SMALL);
                body.add(hint);
                break;

            case HOST_WAITING:
                body.add(sectionLabel("ROOM READY"));
                body.add(Box.createRigidArea(new Dimension(0, 5)));

                JLabel code = new JLabel(
                    ChessMultiplayerService.formatCode(multiplayer.getInvitationCode()),
                    SwingConstants.CENTER);
                code.setOpaque(true);
                code.setBackground(new Color(31, 31, 31));
                code.setForeground(ACCENT_HOVER);
                code.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(82, 66, 42)),
                    BorderFactory.createEmptyBorder(8, 6, 8, 6)));
                code.setFont(CONTROL_FONT_BOLD.deriveFont(16f));
                code.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
                code.setAlignmentX(Component.CENTER_ALIGNMENT);
                body.add(code);
                body.add(Box.createRigidArea(new Dimension(0, 6)));

                JLabel waiting = new JLabel("Waiting for your friend to join…");
                waiting.setForeground(WARNING);
                waiting.setFont(CONTROL_FONT_SMALL);
                body.add(waiting);
                body.add(Box.createRigidArea(new Dimension(0, 7)));

                JButton copy = primaryButton("Copy invite code");
                copy.addActionListener(event -> copyPrivateInviteCode());
                body.add(fullWidth(copy));
                body.add(Box.createRigidArea(new Dimension(0, 6)));

                JButton cancel = dangerButton("Cancel private room");
                cancel.addActionListener(event -> armDestructiveAction(
                    cancel,
                    "Confirm cancel",
                    () ->
                    {
                        multiplayer.leaveMatch();
                        showFeedback("Private room closed.", false);
                    }));
                body.add(fullWidth(cancel));
                break;

            case GUEST_JOINING:
                body.add(sectionLabel("CONNECTING TO ROOM"));
                body.add(Box.createRigidArea(new Dimension(0, 5)));

                JLabel joiningCode = new JLabel(
                    ChessMultiplayerService.formatCode(multiplayer.getInvitationCode()),
                    SwingConstants.CENTER);
                joiningCode.setForeground(ACCENT_HOVER);
                joiningCode.setFont(CONTROL_FONT_BOLD.deriveFont(15f));
                joiningCode.setAlignmentX(Component.CENTER_ALIGNMENT);
                body.add(joiningCode);
                body.add(Box.createRigidArea(new Dimension(0, 5)));

                JLabel connecting = new JLabel("Waiting for the host to accept the connection…");
                connecting.setForeground(WARNING);
                connecting.setFont(CONTROL_FONT_SMALL);
                body.add(connecting);
                body.add(Box.createRigidArea(new Dimension(0, 7)));

                JButton cancelJoin = dangerButton("Cancel connection");
                cancelJoin.addActionListener(event -> armDestructiveAction(
                    cancelJoin,
                    "Confirm cancel",
                    () ->
                    {
                        multiplayer.leaveMatch();
                        showFeedback("Private connection cancelled.", false);
                    }));
                body.add(fullWidth(cancelJoin));
                break;

            case HOST_PLAYING:
            case GUEST_PLAYING:
                if (multiplayer.getMatchKind() == ChessMultiplayerService.MatchKind.PRIVATE)
                {
                    body.add(sectionLabel("PRIVATE MATCH CONNECTED"));
                    body.add(Box.createRigidArea(new Dimension(0, 5)));

                    JLabel connected = new JLabel(multiplayer.isOpponentConnected()
                        ? "Your friend is connected."
                        : "Friend disconnected — waiting for reconnect.");
                    connected.setForeground(multiplayer.isOpponentConnected() ? SUCCESS : WARNING);
                    connected.setFont(CONTROL_FONT_SMALL);
                    body.add(connected);
                    body.add(Box.createRigidArea(new Dimension(0, 7)));

                    JButton showBoard = primaryButton(boardOverlay.isVisible()
                        ? "Board is open in game"
                        : "Show board in game");
                    showBoard.setEnabled(!boardOverlay.isVisible());
                    showBoard.addActionListener(event ->
                    {
                        boardOverlay.setVisible(true);
                        boardOverlay.fitToCanvasIfNeeded();
                        refreshUi();
                    });
                    body.add(fullWidth(showBoard));
                    body.add(Box.createRigidArea(new Dimension(0, 6)));

                    JButton gameActions = secondaryButton("Open game actions");
                    gameActions.addActionListener(event -> showDrawer(GAME_DRAWER));
                    body.add(fullWidth(gameActions));
                    body.add(Box.createRigidArea(new Dimension(0, 6)));

                    JButton leave = dangerButton("Leave private match");
                    leave.addActionListener(event -> armDestructiveAction(
                        leave,
                        "Confirm leave",
                        () ->
                        {
                            multiplayer.leaveMatch();
                            showFeedback("Left the private match.", false);
                        }));
                    body.add(fullWidth(leave));
                }
                break;

            default:
                break;
        }
    }

    private void createPrivateMatchFromPanel()
    {
        int minutes = selected(minutesBox, ChessTimeControl.DEFAULT_MINUTES);
        int increment = selected(incrementBox, ChessTimeControl.DEFAULT_INCREMENT_SECONDS);
        try
        {
            if (matchmaking.isLobbyOpen())
            {
                matchmaking.closeLobby();
            }
            multiplayer.createPrivateMatch(minutes, increment);
            boardPanel.clearSelection();
            boardOverlay.clearSelection();
            showFeedback("Private room created. Share the invite code with your friend.", false);
        }
        catch (IllegalArgumentException | IllegalStateException ex)
        {
            showFeedback(ex.getMessage(), true);
        }
        refreshUi();
    }

    private void joinPrivateMatchFromPanel()
    {
        String code = privateCodeField.getText();
        if (code == null || code.trim().isEmpty())
        {
            showFeedback("Enter your friend's private match code.", true);
            return;
        }

        try
        {
            if (matchmaking.isLobbyOpen())
            {
                matchmaking.closeLobby();
            }
            multiplayer.joinPrivateMatch(code);
            privateCodeField.setText(ChessMultiplayerService.formatCode(multiplayer.getInvitationCode()));
            showFeedback("Connecting to the private match…", false);
        }
        catch (IllegalArgumentException | IllegalStateException ex)
        {
            showFeedback(ex.getMessage(), true);
        }
        refreshUi();
    }

    private void copyPrivateInviteCode()
    {
        String code = multiplayer.getInvitationCode();
        if (code == null || code.isEmpty())
        {
            showFeedback("No active private room code.", true);
            return;
        }

        try
        {
            String formatted = ChessMultiplayerService.formatCode(code);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(formatted), null);
            showFeedback("Invite code copied.", false);
        }
        catch (RuntimeException ex)
        {
            showFeedback("Could not copy the invite code.", true);
        }
    }

    
    private JPanel drawerHeader(String titleText, String subtitleText)
    {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(titleText);
        title.setForeground(Color.WHITE);
        title.setFont(CONTROL_FONT_BOLD.deriveFont(13f));
        JLabel subtitle = new JLabel(ellipsize(subtitleText, 54));
        subtitle.setForeground(MUTED_TEXT);
        subtitle.setFont(CONTROL_FONT_SMALL);
        panel.add(title);
        panel.add(Box.createRigidArea(new Dimension(0, 2)));
        panel.add(subtitle);
        return panel;
    }

    private JPanel labeledChoices()
    {
        JPanel panel = new JPanel(new GridLayout(2, 2, 6, 5));
        panel.setOpaque(false);
        JLabel minutes = new JLabel("Minutes");
        JLabel increment = new JLabel("Increment");
        minutes.setForeground(MUTED_TEXT);
        increment.setForeground(MUTED_TEXT);
        minutes.setFont(CONTROL_FONT_SMALL);
        increment.setFont(CONTROL_FONT_SMALL);
        minutesBox.setFont(CONTROL_FONT);
        incrementBox.setFont(CONTROL_FONT);
        panel.add(minutes);
        panel.add(increment);
        panel.add(minutesBox);
        panel.add(incrementBox);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        return panel;
    }

    private Component sectionDivider()
    {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(9, 0, 8, 0));
        JPanel line = new JPanel();
        line.setBackground(CARD_BORDER);
        line.setPreferredSize(new Dimension(1, 1));
        wrapper.add(line, BorderLayout.CENTER);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        return wrapper;
    }

    private JLabel sectionLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED_TEXT);
        label.setFont(CONTROL_FONT_BOLD.deriveFont(11f));
        return label;
    }

    private JPanel fullWidth(JButton button)
    {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        wrapper.add(button, BorderLayout.CENTER);
        return wrapper;
    }

    private void startNewLocalGame()
    {
        int minutes = selected(minutesBox, ChessTimeControl.DEFAULT_MINUTES);
        int increment = selected(incrementBox, ChessTimeControl.DEFAULT_INCREMENT_SECONDS);
        try
        {
            if (matchmaking.isLobbyOpen())
            {
                matchmaking.closeLobby();
            }
            multiplayer.startLocalGame(minutes, increment);
            boardPanel.clearSelection();
            boardOverlay.clearSelection();
            openDrawer = null;
            drawerHost.setVisible(false);
            showFeedback("New " + timeControl(minutes, increment) + " local game started.", false);
        }
        catch (IllegalArgumentException ex)
        {
            showFeedback(ex.getMessage(), true);
        }
        refreshUi();
    }

    private void startBotGame(ChessColor humanColor)
    {
        BotDifficulty difficulty = (BotDifficulty) botDifficultyBox.getSelectedItem();
        if (difficulty == null)
        {
            difficulty = BotDifficulty.GOBLIN;
        }

        int minutes = selected(minutesBox, ChessTimeControl.DEFAULT_MINUTES);
        int increment = selected(incrementBox, ChessTimeControl.DEFAULT_INCREMENT_SECONDS);
        if (matchmaking.isLobbyOpen())
        {
            matchmaking.closeLobby();
        }
        multiplayer.startBotGame(difficulty, humanColor, minutes, increment);
        applyBoardOrientation(humanColor == ChessColor.BLACK);
        showFeedback("Playing " + difficulty.getDisplayName() + " as "
            + humanColor.displayName() + ".", false);
        refreshUi();
    }

    
    
    
    
    
    
    private void armDestructiveAction(JButton button, String confirmationText, Runnable action)
    {
        if (armedButton == button)
        {
            cancelArmedAction();
            action.run();
            refreshUi();
            return;
        }

        cancelArmedAction();
        armedButton = button;
        String original = button.getText();
        button.putClientProperty("originalText", original);
        button.setText(confirmationText);
        button.setBackground(DANGER);

        confirmTimer = new Timer(4000, event -> cancelArmedAction());
        confirmTimer.setRepeats(false);
        confirmTimer.start();
        showFeedback("Click once more to confirm.", true);
    }

    private void cancelArmedAction()
    {
        if (confirmTimer != null)
        {
            confirmTimer.stop();
            confirmTimer = null;
        }
        if (armedButton != null)
        {
            Object original = armedButton.getClientProperty("originalText");
            if (original instanceof String)
            {
                armedButton.setText((String) original);
            }
            styleDangerButton(armedButton);
            armedButton = null;
        }
    }

    private String drawActionText()
    {
        if (botService.isActive())
        {
            return "No bot draws";
        }
        ChessColor actor = multiplayer.isPlayingOnline()
            ? multiplayer.getLocalColor()
            : controller.getGame().getSideToMove();
        ChessColor offeredBy = controller.getDrawOfferedBy();
        if (offeredBy == null)
        {
            return "Offer draw";
        }
        if (offeredBy == actor)
        {
            return "Cancel draw";
        }
        return "Accept draw";
    }

    private String rematchActionText()
    {
        if (!multiplayer.isPlayingOnline())
        {
            return "Waiting for opponent";
        }
        if (!controller.getGame().getStatus().isFinished())
        {
            return "Rematch after game";
        }
        if (multiplayer.isRematchRequestedByLocalPlayer())
        {
            return "Rematch requested";
        }
        if (multiplayer.isRematchRequestedByOpponent())
        {
            return "Accept rematch";
        }
        return "Request rematch";
    }

    private void showFeedback(String message, boolean error)
    {
        feedbackText.setText("<html><div style='text-align:center;'>" + escapeHtml(message) + "</div></html>");
        feedbackText.setForeground(error ? DANGER : SUCCESS);
        feedbackTimer.restart();
    }

    private String drawerStateKey()
    {
        return String.valueOf(openDrawer)
            + '|' + multiplayer.getMode()
            + '|' + multiplayer.getMatchKind()
            + '|' + multiplayer.getInvitationCode()
            + '|' + multiplayer.isMatchmade()
            + '|' + controller.getGame().getStatus()
            + '|' + controller.getDrawOfferedBy()
            + '|' + multiplayer.isOpponentConnected()
            + '|' + multiplayer.getRematchRequestedBy()
            + '|' + multiplayer.getLocalColor()
            + '|' + botService.isActive()
            + '|' + botService.isThinking()
            + '|' + botService.getDifficulty();
    }

    private void updateBoardPresentation(boolean overlayVisible)
    {
        CardLayout cards = (CardLayout) boardHost.getLayout();
        cards.show(boardHost, overlayVisible ? OVERLAY_CARD : BOARD_CARD);
        overlayButton.setText(overlayVisible ? "Hide board" : "Show board");
        boardHost.revalidate();
        boardHost.repaint();
    }

    private void updateActionButtons()
    {
        gameButton.setText(GAME_DRAWER.equals(openDrawer) ? "Close game" : "Game");
        privateButton.setText(PRIVATE_DRAWER.equals(openDrawer) ? "Close private" : "Private");
    }

    @Override
    public void onGameChanged()
    {
        SwingUtilities.invokeLater(this::refreshUi);
    }

    @Override
    public void onSessionChanged()
    {
        SwingUtilities.invokeLater(() ->
        {
            ChessColor localColor = multiplayer.getLocalColor();

            if (localColor != null && localColor != lastAutoOrientedColor)
            {
                lastAutoOrientedColor = localColor;
                applyBoardOrientation(localColor == ChessColor.BLACK);
            }
            else if (multiplayer.getMode() == ChessMultiplayerService.Mode.LOCAL)
            {

                lastAutoOrientedColor = null;
            }

            refreshUi();
        });
    }

    @Override
    public void onBotChanged()
    {
        SwingUtilities.invokeLater(this::refreshUi);
    }

    private void refreshUi()
    {
        refreshClockRows();
        String notice = botService.isActive() && botService.isThinking()
            ? botService.getDifficulty().getDisplayName() + " is thinking…"
            : controller.getNotice();
        statusText.setText("<html><div style='text-align:center;'>"
            + escapeHtml(notice) + "</div></html>");

        boolean local = multiplayer.getMode() == ChessMultiplayerService.Mode.LOCAL;
        int minutes;
        int increment;
        if (local)
        {
            minutes = selected(minutesBox, ChessTimeControl.DEFAULT_MINUTES);
            increment = selected(incrementBox, ChessTimeControl.DEFAULT_INCREMENT_SECONDS);
        }
        else
        {
            minutes = multiplayer.getInitialMinutes();
            increment = multiplayer.getIncrementSeconds();
        }
        timeControlLabel.setText(timeControl(minutes, increment));

        if (local)
        {
            modeBadge.setText(botService.isActive() ? "BOT" : "LOCAL");
            modeBadge.setForeground(botService.isActive() ? ACCENT : MUTED_TEXT);
            sessionCard.setVisible(false);
        }
        else
        {
            modeBadge.setText(multiplayer.isMatchmade()
                ? "MATCH"
                : (multiplayer.isPlayingOnline() ? "ONLINE" : "ROOM"));
            modeBadge.setForeground(multiplayer.isOpponentConnected() ? SUCCESS : WARNING);
            sessionCard.setVisible(true);
            updateSessionCard();
            minutesBox.setSelectedItem(ChessTimeControl.normalizeMinutes(multiplayer.getInitialMinutes()));
            incrementBox.setSelectedItem(ChessTimeControl.normalizeIncrement(multiplayer.getIncrementSeconds()));
        }

        if (openDrawer != null)
        {
            String stateKey = drawerStateKey();
            if (!stateKey.equals(lastDrawerStateKey))
            {
                rebuildDrawer();
            }
        }
        updateActionButtons();
        updateBoardPresentation(boardOverlay.isVisible());
        revalidate();
        repaint();
    }

    private void refreshClockRows()
    {
        ChessClock clock = controller.getClock();
        ChessColor topColor = boardPanel.isFlipped() ? ChessColor.WHITE : ChessColor.BLACK;
        ChessColor bottomColor = topColor.opposite();
        topClockLabel.setText(clockText(topColor, clock));
        bottomClockLabel.setText(clockText(bottomColor, clock));
    }

    private static String clockText(ChessColor color, ChessClock clock)
    {
        return color.displayName() + "  "
            + ChessClock.format(clock.getRemainingMillis(color));
    }

    
    private void updateSessionCard()
    {
        if (multiplayer.isMatchmade())
        {
            switch (multiplayer.getMode())
            {
                case HOST_WAITING:
                case GUEST_JOINING:
                    sessionTitle.setText("Match found");
                    sessionDetail.setText("Connecting automatically to opponent");
                    connectionDot.setForeground(WARNING);
                    return;
                case HOST_PLAYING:
                case GUEST_PLAYING:
                    sessionTitle.setText(multiplayer.getLocalColor() == null
                        ? "Quick match"
                        : "Playing as " + multiplayer.getLocalColor().displayName());
                    sessionDetail.setText(multiplayer.isOpponentConnected()
                        ? "Unrated opponent connected"
                        : "Opponent disconnected — reconnecting");
                    connectionDot.setForeground(multiplayer.isOpponentConnected() ? SUCCESS : DANGER);
                    return;
                default:
                    break;
            }
        }

        switch (multiplayer.getMode())
        {
            case HOST_WAITING:
                sessionTitle.setText("Room " + ChessMultiplayerService.formatCode(multiplayer.getInvitationCode()));
                sessionDetail.setText("Waiting for the reserved opponent");
                connectionDot.setForeground(WARNING);
                break;
            case GUEST_JOINING:
                sessionTitle.setText("Joining room");
                sessionDetail.setText(ChessMultiplayerService.formatCode(multiplayer.getInvitationCode()));
                connectionDot.setForeground(WARNING);
                break;
            case HOST_PLAYING:
            case GUEST_PLAYING:
                sessionTitle.setText(multiplayer.getLocalColor() == null
                    ? "Private match"
                    : "Playing as " + multiplayer.getLocalColor().displayName());
                sessionDetail.setText(multiplayer.isOpponentConnected()
                    ? "Opponent connected"
                    : "Opponent disconnected — reconnecting");
                connectionDot.setForeground(multiplayer.isOpponentConnected() ? SUCCESS : DANGER);
                break;
            default:
                sessionTitle.setText("Private match");
                sessionDetail.setText("Synchronizing...");
                connectionDot.setForeground(WARNING);
        }
    }

    private void styleClock(JLabel label)
    {
        label.setOpaque(true);
        label.setBackground(new Color(47, 47, 47));
        label.setForeground(Color.WHITE);
        label.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(69, 69, 69)),
            BorderFactory.createEmptyBorder(5, 4, 5, 4)));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, 31));
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    private static void styleBadge(JLabel label)
    {
        label.setOpaque(true);
        label.setBackground(new Color(49, 49, 49));
        label.setForeground(MUTED_TEXT);
        label.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(74, 74, 74)),
            BorderFactory.createEmptyBorder(2, 7, 2, 7)));
        label.setFont(CONTROL_FONT_BOLD.deriveFont(10f));
        label.setAlignmentX(Component.RIGHT_ALIGNMENT);
    }

    private static JButton primaryButton(String text)
    {
        JButton button = baseButton(text);
        button.setBackground(ACCENT);
        button.setForeground(Color.BLACK);
        button.addChangeListener(event ->
        {
            if (!button.isEnabled())
            {
                return;
            }
            button.setBackground(button.getModel().isRollover() ? ACCENT_HOVER : ACCENT);
        });
        return button;
    }

    private static JButton secondaryButton(String text)
    {
        JButton button = baseButton(text);
        button.setBackground(new Color(66, 66, 66));
        button.setForeground(Color.WHITE);
        button.addChangeListener(event ->
        {
            if (!button.isEnabled())
            {
                return;
            }
            button.setBackground(button.getModel().isRollover()
                ? new Color(82, 82, 82)
                : new Color(66, 66, 66));
        });
        return button;
    }

    private static JButton dangerButton(String text)
    {
        JButton button = baseButton(text);
        styleDangerButton(button);
        return button;
    }

    private static void styleDangerButton(JButton button)
    {
        button.setBackground(new Color(91, 54, 51));
        button.setForeground(new Color(255, 218, 214));
    }

    private static JButton baseButton(String text)
    {
        JButton button = new JButton(text);
        button.setUI(new ReadableButtonUI());
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(33, 33, 33)),
            BorderFactory.createEmptyBorder(5, 7, 5, 7)));
        button.setFont(CONTROL_FONT_BOLD);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        button.setPreferredSize(new Dimension(0, 32));
        return button;
    }


    private static final class ReadableButtonUI extends BasicButtonUI
    {
        @Override
        protected void paintText(Graphics graphics, AbstractButton button, Rectangle textRect, String text)
        {
            Graphics2D g = (Graphics2D) graphics.create();
            try
            {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(button.getFont());
                g.setColor(button.isEnabled() ? button.getForeground() : DISABLED_BUTTON_TEXT);
                FontMetrics metrics = g.getFontMetrics();
                BasicGraphicsUtils.drawStringUnderlineCharAt(
                    g,
                    text,
                    button.getDisplayedMnemonicIndex(),
                    textRect.x,
                    textRect.y + metrics.getAscent());
            }
            finally
            {
                g.dispose();
            }
        }
    }

    private static void setChildrenEnabled(Component component, boolean enabled)
    {
        component.setEnabled(enabled);
        if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                setChildrenEnabled(child, enabled);
            }
        }
    }

    private static int selected(JComboBox<Integer> box, int fallback)
    {
        Integer selected = (Integer) box.getSelectedItem();
        return selected == null ? fallback : selected;
    }

    private static String timeControl(int minutes, int increment)
    {
        return increment > 0 ? minutes + "+" + increment : minutes + " min";
    }

    private static String ellipsize(String text, int max)
    {
        if (text == null)
        {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String escapeHtml(String value)
    {
        if (value == null)
        {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private static final class RoundedPanel extends JPanel
    {
        private final Color fill;
        private final Color border;
        private final int arc;

        private RoundedPanel(Color fill, Color border, int arc)
        {
            this.fill = fill;
            this.border = border;
            this.arc = arc;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics)
        {
            Graphics2D g = (Graphics2D) graphics.create();
            try
            {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(fill);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g.setColor(border);
                g.setStroke(new BasicStroke(1f));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            finally
            {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }
}
