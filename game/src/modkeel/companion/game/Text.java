package modkeel.companion.game;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Wrapped text in Keel's way: left-aligned inside a card, centered between cards. */
final class Text extends Canvas {
    static final int LINE = 10;

    private final Font font;
    private final List<FormattedCharSequence> lines;
    private final int color;
    private final boolean centered;

    private Text(Component text, int width, int color, boolean centered) {
        super(width, 0);
        this.font = Minecraft.getInstance().font;
        this.lines = font.split(text, width);
        this.color = color;
        this.centered = centered;
        resize(width, Math.max(0, lines.size() * LINE - 1));
    }

    /** Inside a card. */
    static Text in(Component text, int width, int color) {
        return new Text(text, width, color, false);
    }

    /** Between cards, centered in the column. */
    static Text loose(Component text, int width, int color) {
        return new Text(text, width, color, true);
    }

    @Override
    protected void paint(Paint p, int mouseX, int mouseY) {
        int y = getY();
        for (FormattedCharSequence line : lines) {
            int x = centered ? getX() + (getWidth() - font.width(line)) / 2 : getX();
            p.text(font, line, x, y, color, true);
            y += LINE;
        }
    }
}
