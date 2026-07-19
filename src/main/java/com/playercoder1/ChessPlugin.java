package com.playercoder1;

import com.playercoder1.chess.ChessBotService;
import com.playercoder1.chess.ChessMultiplayerService;
import com.playercoder1.chess.ChessPartyMessage;
import com.playercoder1.chess.ChessSoundService;
import com.playercoder1.ui.ChessBoardOverlay;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
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
    description = "Play local, computer, or private two-player chess in the sidebar or an interactive game overlay",
    tags = {"chess", "board", "computer", "sidebar", "overlay", "multiplayer", "party"}
)
public class ChessPlugin extends Plugin
{
    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ChessPanel panel;

    @Inject
    private ChessBoardOverlay boardOverlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private MouseManager mouseManager;

    @Inject
    private WSClient wsClient;

    @Inject
    private ChessMultiplayerService multiplayerService;

    @Inject
    private ChessBotService botService;

    @Inject
    private ChessSoundService soundService;

    private NavigationButton navigationButton;

    @Override
    protected void startUp()
    {
        wsClient.registerMessage(ChessPartyMessage.class);
        botService.start();
        multiplayerService.start();
        soundService.start();
        overlayManager.add(boardOverlay);
        mouseManager.registerMouseListener(boardOverlay);

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

    @Override
    protected void shutDown()
    {
        soundService.stop();
        boardOverlay.setVisible(false);
        botService.stop();
        mouseManager.unregisterMouseListener(boardOverlay);
        overlayManager.remove(boardOverlay);
        multiplayerService.stop();
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