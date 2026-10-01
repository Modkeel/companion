package modkeel.companion.game;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** A card's first line: the key fact in white, then pill tags; pills that do not fit wrap. */
final class Heading extends Canvas {
    private static final int ROW = Keel.PILL_H + 3;
    private static final int GAP = 3;

    private final Font font;
    private final List<FormattedCharSequence> title;
    private final Component[] pills;
    private final int color;

    Heading(Component title, int width, Component... pills) {
        this(title, width, Keel.TEXT, pills);
    }

    Heading(Component title, int width, int color, Component... pills) {
        super(width, 0);
        this.font = Minecraft.getInstance().font;
        this.title = font.split(title, width);
        this.pills = pills;
        this.color = color;
        resize(width, layout(null) + Keel.PILL_H);
    }

    /** Draws when {@code p} is set; returns the top of the last row. */
    private int layout(Paint p) {
        int x = getX();
        int y = getY();
        int top = y;
        int tx = x;
        for (int i = 0; i < title.size(); i++) {
            if (i > 0) {
                top += ROW;
            }
            if (p != null) {
                p.text(font, title.get(i), x, top + 2, color, true);
            }
            tx = x + font.width(title.get(i)) + 6;
        }
        for (Component pill : pills) {
            int w = Keel.pillWidth(font, pill);
            if (tx > x && tx - x + w > getWidth()) {
                top += ROW;
                tx = x;
            }
            if (p != null) {
                Keel.pill(p, font, tx, top, pill);
            }
            tx += w + GAP;
        }
        return top - y;
    }

    @Override
    protected void paint(Paint p, int mouseX, int mouseY) {
        layout(p);
    }
}
