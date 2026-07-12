package com.playercoder1;

import com.playercoder1.chess.ChessMultiplayerService;
import com.playercoder1.chess.ChessPartyMessage;
import com.playercoder1.ui.ChessBoardOverlay;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
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
    description = "Play local or private two-player chess in the sidebar or an interactive game overlay",
    tags = {"chess"}
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

    private NavigationButton navigationButton;

    @Override
    protected void startUp()
    {
        wsClient.registerMessage(ChessPartyMessage.class);
        multiplayerService.start();
        overlayManager.add(boardOverlay);
        mouseManager.registerMouseListener(boardOverlay);

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/chess_icon.png");
        navigationButton = NavigationButton.builder()
            .tooltip("Chess")
            .icon(icon)
            .priority(7)
            .panel(panel)
            .build();

        panel.start();
        clientToolbar.addNavigation(navigationButton);
    }

    @Override
    protected void shutDown()
    {
        boardOverlay.setVisible(false);
        mouseManager.unregisterMouseListener(boardOverlay);
        overlayManager.remove(boardOverlay);
        multiplayerService.stop();
        wsClient.unregisterMessage(ChessPartyMessage.class);

        panel.stop();
        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }
    }
}
