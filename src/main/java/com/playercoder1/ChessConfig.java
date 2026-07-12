package com.playercoder1;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("runelitechess")
public interface ChessConfig extends Config
{
    @Range(min = 1, max = 180)
    @ConfigItem(
        keyName = "initialMinutes",
        name = "Clock minutes",
        description = "Starting time for each player. Applied when a new game begins."
    )
    default int initialMinutes()
    {
        return 10;
    }

    @Range(min = 0, max = 60)
    @ConfigItem(
        keyName = "incrementSeconds",
        name = "Increment seconds",
        description = "Time added after every completed move. Applied when a new game begins."
    )
    default int incrementSeconds()
    {
        return 5;
    }
}
