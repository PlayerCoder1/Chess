package com.playercoder1.ui;

import com.playercoder1.chess.ChessColor;
import com.playercoder1.chess.Piece;
import com.playercoder1.chess.PieceType;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import javax.swing.ImageIcon;
import net.runelite.client.util.ImageUtil;

public final class ChessPieceImages
{
    private static final Map<ChessColor, Map<PieceType, BufferedImage>> IMAGES = new EnumMap<>(ChessColor.class);
    private static final Map<String, ImageIcon> ICON_CACHE = new ConcurrentHashMap<>();

    static
    {
        Map<PieceType, BufferedImage> white = new EnumMap<>(PieceType.class);
        white.put(PieceType.BISHOP, load("/white-bishop.png"));
        white.put(PieceType.KING, load("/white-king.png"));
        white.put(PieceType.KNIGHT, load("/white-knight.png"));
        white.put(PieceType.PAWN, load("/white-pawn.png"));
        white.put(PieceType.QUEEN, load("/white-queen.png"));
        white.put(PieceType.ROOK, load("/white-rook.png"));
        IMAGES.put(ChessColor.WHITE, white);

        Map<PieceType, BufferedImage> black = new EnumMap<>(PieceType.class);
        black.put(PieceType.BISHOP, load("/black-bishop.png"));
        black.put(PieceType.KING, load("/black-king.png"));
        black.put(PieceType.KNIGHT, load("/black-knight.png"));
        black.put(PieceType.PAWN, load("/black-pawn.png"));
        black.put(PieceType.QUEEN, load("/black-queen.png"));
        black.put(PieceType.ROOK, load("/black-rook.png"));
        IMAGES.put(ChessColor.BLACK, black);
    }

    private ChessPieceImages()
    {
    }

    public static BufferedImage get(ChessColor color, PieceType type)
    {
        Map<PieceType, BufferedImage> byType = IMAGES.get(color);
        return byType == null ? null : byType.get(type);
    }

    public static ImageIcon icon(Piece piece, int size)
    {
        if (piece == null)
        {
            return null;
        }
        return icon(piece.getColor(), piece.getType(), size);
    }

    private static ImageIcon icon(ChessColor color, PieceType type, int size)
    {
        if (size <= 0)
        {
            throw new IllegalArgumentException("Icon size must be positive");
        }

        BufferedImage image = get(color, type);
        if (image == null)
        {
            return null;
        }

        String key = color.name() + ':' + type.name() + ':' + size;
        ImageIcon cached = ICON_CACHE.get(key);
        if (cached != null)
        {
            return cached;
        }

        ImageIcon icon = new ImageIcon(ImageUtil.resizeImage(image, size, size, false));
        ImageIcon previous = ICON_CACHE.putIfAbsent(key, icon);
        return previous == null ? icon : previous;
    }

    private static BufferedImage load(String resourcePath)
    {
        try
        {
            return ImageUtil.loadImageResource(ChessPieceImages.class, resourcePath);
        }
        catch (RuntimeException ex)
        {
            // The board falls back to text symbols when a resource is missing.
            return null;
        }
    }
}
