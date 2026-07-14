package com.playercoder1.chess;

public enum BotDifficulty
{
    GOBLIN("Goblin", 300, 0, 35L, 8, 70),
    IMP("Imp", 500, 1, 70L, 6, 55),
    GUARD("Guard", 800, 2, 140L, 4, 30),
    WHITE_KNIGHT("White Knight", 1050, 2, 250L, 3, 15),
    WIZARD("Wizard", 1350, 3, 450L, 2, 6),
    WISE_OLD_MAN("Wise Old Man", 2000, 4, 1_100L, 1, 0);

    private final String displayName;
    private final int approximateRating;
    private final int maximumDepth;
    private final long thinkTimeMillis;
    private final int candidateMoveCount;
    private final int mistakeChancePercent;

    BotDifficulty(
        String displayName,
        int approximateRating,
        int maximumDepth,
        long thinkTimeMillis,
        int candidateMoveCount,
        int mistakeChancePercent)
    {
        this.displayName = displayName;
        this.approximateRating = approximateRating;
        this.maximumDepth = maximumDepth;
        this.thinkTimeMillis = thinkTimeMillis;
        this.candidateMoveCount = candidateMoveCount;
        this.mistakeChancePercent = mistakeChancePercent;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public int getApproximateRating()
    {
        return approximateRating;
    }

    public int getMaximumDepth()
    {
        return maximumDepth;
    }

    public long getThinkTimeMillis()
    {
        return thinkTimeMillis;
    }

    public int getCandidateMoveCount()
    {
        return candidateMoveCount;
    }

    public int getMistakeChancePercent()
    {
        return mistakeChancePercent;
    }

    public String description()
    {
        return displayName + " — about " + approximateRating;
    }

    @Override
    public String toString()
    {
        return description();
    }
}
