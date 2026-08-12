package com.playercoder1;

import com.playercoder1.chess.ChessBotService;
import com.playercoder1.chess.ChessColor;
import com.playercoder1.chess.ChessLobbyMessage;
import com.playercoder1.chess.ChessMatchmakingService;
import com.playercoder1.chess.ChessMultiplayerService;
import com.playercoder1.chess.ChessPartyMessage;
import com.playercoder1.chess.ChessSoundService;
import com.playercoder1.ui.ChessBoardOverlay;
import com.playercoder1.ui.ChessClubOverlay;
import com.playercoder1.ui.ChessLobbyOverlay;
import com.playercoder1.ui.ChessMatchControlsOverlay;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
    name = "Chess",
    description = "Play local, computer, or Varrock Chess Club multiplayer chess",
    tags = {"chess", "board", "computer", "sidebar", "overlay", "multiplayer", "matchmaking", "party", "varrock"}
)
public class ChessPlugin extends Plugin implements ChessMultiplayerService.Listener
{

    private static final int VARROCK_CHESS_TABLE_OBJECT_ID = 17451;
    private static final String PLAY_CHESS = "Play";
    private static final String FIND_OPPONENT = "Find opponent";
    private static final String PRIVATE_MATCH = "Private match";

    @Inject
    private Client client;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ChessPanel panel;

    @Inject
    private ChessBoardOverlay boardOverlay;

    @Inject
    private ChessLobbyOverlay lobbyOverlay;

    @Inject
    private ChessClubOverlay clubOverlay;

    @Inject
    private ChessMatchControlsOverlay matchControlsOverlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private MouseManager mouseManager;

    @Inject
    private WSClient wsClient;

    @Inject
    private ChessMultiplayerService multiplayerService;

    @Inject
    private ChessMatchmakingService matchmakingService;

    @Inject
    private ChessBotService botService;

    @Inject
    private ChessSoundService soundService;

    private NavigationButton navigationButton;
    private ChessColor lastAutoOrientedColor;

    @Override
    protected void startUp()
    {
        wsClient.registerMessage(ChessPartyMessage.class);
        wsClient.registerMessage(ChessLobbyMessage.class);
        botService.start();
        multiplayerService.start();
        multiplayerService.addListener(this);
        matchmakingService.start();
        soundService.start();

        overlayManager.add(boardOverlay);
        overlayManager.add(clubOverlay);
        overlayManager.add(lobbyOverlay);
        overlayManager.add(matchControlsOverlay);
        mouseManager.registerMouseListener(boardOverlay);
        mouseManager.registerMouseListener(clubOverlay);
        mouseManager.registerMouseListener(lobbyOverlay);
        mouseManager.registerMouseListener(matchControlsOverlay);

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/chess_icon.png");
        navigationButton = NavigationButton.builder()
            .tooltip("Chess")
            .icon(icon)
            .priority(7)
            .panel(panel)
            .build();

        SwingUtilities.invokeLater(() ->
        {
            panel.start();
            clientToolbar.addNavigation(navigationButton);
        });
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if (event.getType() != MenuAction.EXAMINE_OBJECT.getId()
            || event.getIdentifier() != VARROCK_CHESS_TABLE_OBJECT_ID)
        {
            return;
        }


        client.createMenuEntry(-1)
            .setOption(PRIVATE_MATCH)
            .setTarget(event.getTarget())
            .setType(MenuAction.RUNELITE)
            .setIdentifier(event.getIdentifier())
            .setParam0(event.getActionParam0())
            .setParam1(event.getActionParam1())
            .setWorldViewId(event.getMenuEntry().getWorldViewId())
            .onClick(entry -> openPrivateMatchFromTable());

        client.createMenuEntry(-1)
            .setOption(FIND_OPPONENT)
            .setTarget(event.getTarget())
            .setType(MenuAction.RUNELITE)
            .setIdentifier(event.getIdentifier())
            .setParam0(event.getActionParam0())
            .setParam1(event.getActionParam1())
            .setWorldViewId(event.getMenuEntry().getWorldViewId())
            .onClick(entry -> openLobbyFromTable());

        client.createMenuEntry(-1)
            .setOption(PLAY_CHESS)
            .setTarget(event.getTarget())
            .setType(MenuAction.RUNELITE)
            .setIdentifier(event.getIdentifier())
            .setParam0(event.getActionParam0())
            .setParam1(event.getActionParam1())
            .setWorldViewId(event.getMenuEntry().getWorldViewId())
            .onClick(entry -> openChessFromTable());
    }

    private void openChessFromTable()
    {
        if (multiplayerService.isPlayingOnline())
        {
            onSessionChanged();
            return;
        }

        if (matchmakingService.isLobbyOpen())
        {
            matchmakingService.closeLobby();
            lobbyOverlay.close();
        }

        Point mouse = client.getMouseCanvasPosition();
        int x = mouse == null ? 30 : mouse.getX();
        int y = mouse == null ? 100 : mouse.getY();
        clubOverlay.openAt(x, y);
    }

    private void openPrivateMatchFromTable()
    {
        if (multiplayerService.isPlayingOnline())
        {
            onSessionChanged();
            return;
        }

        if (matchmakingService.isLobbyOpen())
        {
            matchmakingService.closeLobby();
            lobbyOverlay.close();
        }

        Point mouse = client.getMouseCanvasPosition();
        int x = mouse == null ? 30 : mouse.getX();
        int y = mouse == null ? 100 : mouse.getY();
        clubOverlay.openPrivateAt(x, y);
    }

    private void openLobbyFromTable()
    {
        clubOverlay.close();
        try
        {
            matchmakingService.openLobby();
            Point mouse = client.getMouseCanvasPosition();
            int x = mouse == null ? 30 : mouse.getX();
            int y = mouse == null ? 100 : mouse.getY();
            lobbyOverlay.openAt(x, y);
        }
        catch (IllegalStateException ex)
        {

            if (multiplayerService.isPlayingOnline())
            {
                onSessionChanged();
            }
            else
            {
                SwingUtilities.invokeLater(this::openPanel);
            }
        }
    }

    @Override
    public void onSessionChanged()
    {
        Runnable sync = () ->
        {
            if (multiplayerService.isPlayingOnline())
            {

                lobbyOverlay.close();
                clubOverlay.close();
                boardOverlay.setVisible(true);
                boardOverlay.fitToCanvasIfNeeded();
                matchControlsOverlay.showForMatch();

                ChessColor localColor = multiplayerService.getLocalColor();
                if (localColor != null && localColor != lastAutoOrientedColor)
                {
                    boardOverlay.setFlipped(localColor == ChessColor.BLACK);
                    lastAutoOrientedColor = localColor;
                }
                return;
            }

            matchControlsOverlay.setVisible(false);
            if (!multiplayerService.isOnline())
            {
                lastAutoOrientedColor = null;
            }
        };

        if (SwingUtilities.isEventDispatchThread())
        {
            sync.run();
        }
        else
        {
            SwingUtilities.invokeLater(sync);
        }
    }

    private void openPanel()
    {
        if (navigationButton != null)
        {
            clientToolbar.openPanel(navigationButton);
        }
    }

    @Override
    protected void shutDown()
    {
        soundService.stop();
        boardOverlay.setVisible(false);
        lobbyOverlay.close();
        clubOverlay.close();
        matchControlsOverlay.setVisible(false);
        matchmakingService.stop();
        botService.stop();

        mouseManager.unregisterMouseListener(matchControlsOverlay);
        mouseManager.unregisterMouseListener(lobbyOverlay);
        mouseManager.unregisterMouseListener(clubOverlay);
        mouseManager.unregisterMouseListener(boardOverlay);
        overlayManager.remove(matchControlsOverlay);
        overlayManager.remove(lobbyOverlay);
        overlayManager.remove(clubOverlay);
        overlayManager.remove(boardOverlay);

        multiplayerService.removeListener(this);
        multiplayerService.stop();
        wsClient.unregisterMessage(ChessLobbyMessage.class);
        wsClient.unregisterMessage(ChessPartyMessage.class);

        SwingUtilities.invokeLater(() ->
        {
            panel.stop();
            if (navigationButton != null)
            {
                clientToolbar.removeNavigation(navigationButton);
                navigationButton = null;
            }
        });
    }
}
