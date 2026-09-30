package modkeel.companion.game;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Modkeel's look: Java's black outlines and bevels (light top-left edge, dark bottom-right),
 * with Bedrock's thicker lip under buttons. See docs/companion-ui-style.md.
 */
final class Keel {
    static final int BLACK = 0xFF000000;
    static final int TEXT = 0xFFFFFFFF;
    static final int SOFT = 0xFFE0E0E0;
    static final int GRAY = 0xFFC6C6C6;
    static final int YELLOW = 0xFFFFFF55;
    /** Bar segments in order: neighbours never look alike. */
    static final int[] SEGMENTS = {0xFFFFC629, 0xFF4F9BFF, 0xFF4FD34F, 0xFFC07AFF, 0xFFFF6F4F,
                                   0xFF3FD8D0};
    private static final int REST_A = 0xFF5A5A5A;
    private static final int REST_B = 0xFF474747;

    private Keel() {
    }

    /** A black outline, a fill, a light top-left edge and a dark bottom-right edge. */
    static void bevel(Paint p, int x, int y, int w, int h, int fill, int light, int dark) {
        outline(p, x, y, w, h, BLACK);
        p.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        p.fill(x + 1, y + 1, x + w - 1, y + 2, light);
        p.fill(x + 1, y + 2, x + 2, y + h - 1, light);
        p.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, dark);
        p.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, dark);
    }

    /** Only the 1 px frame: a translucent fill inside it must not sit on black. */
    static void outline(Paint p, int x, int y, int w, int h, int color) {
        p.fill(x, y, x + w, y + 1, color);
        p.fill(x, y + h - 1, x + w, y + h, color);
        p.fill(x, y + 1, x + 1, y + h - 1, color);
        p.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    static final int PANEL_MARGIN = 8;

    /** A translucent dark panel behind a screen's content: the world behind stays visible. */
    static void panel(Paint p, int x, int y, int w, int h) {
        bevel(p, x, y, w, h, 0x60202020, 0x14FFFFFF, 0x40000000);
        stripes(p, x + 2, y + 2, w - 4, h - 4);
    }

    /** A header or footer strip, with a black line on the side facing the content. */
    static void strip(Paint p, int x, int y, int w, int h, boolean footer) {
        p.fill(x, y, x + w, y + h, 0x66000000);
        stripes(p, x, y, w, h);
        int line = footer ? y : y + h - 1;
        p.fill(x, line, x + w, line + 1, BLACK);
    }

    private static final int STRIPE = 5;
    private static final int STRIPE_COLOR = 0x0DFFFFFF;

    /** Faint 45° stripes, like the mockup's background: one run per stripe per row. */
    static void stripes(Paint p, int x, int y, int w, int h) {
        int period = 2 * STRIPE;
        for (int row = 0; row < h; row++) {
            int shift = row % period;
            for (int sx = x - shift; sx < x + w; sx += period) {
                int a = Math.max(x, sx);
                int b = Math.min(x + w, sx + STRIPE);
                if (b > a) {
                    p.fill(a, y + row, b, y + row + 1, STRIPE_COLOR);
                }
            }
        }
    }

    static void card(Paint p, int x, int y, int w, int h) {
        bevel(p, x, y, w, h, 0xEB343434, 0x1AFFFFFF, 0x73000000);
    }

    /** A gray Java button with a darker lip along its bottom; white outline when hovered. */
    static void button(Paint p, Font font, int x, int y, int w, int h, boolean hovered,
                       boolean active, Component label) {
        boolean hot = hovered && active;
        p.fill(x, y, x + w, y + h, hot ? TEXT : BLACK);
        p.fill(x + 1, y + 1, x + w - 1, y + h - 1,
               !active ? 0xFF4A4A4A : hot ? 0xFF8C8C8C : 0xFF727272);
        int light = !active ? 0xFF5E5E5E : hot ? 0xFFC8C8C8 : 0xFFB0B0B0;
        p.fill(x + 1, y + 1, x + w - 1, y + 2, light);
        p.fill(x + 1, y + 2, x + 2, y + h - 4, light);
        p.fill(x + w - 2, y + 2, x + w - 1, y + h - 4, 0xFF4A4A4A);
        p.fill(x + 1, y + h - 4, x + w - 1, y + h - 1, !active ? 0xFF303030 : 0xFF3D3D3D);
        FormattedCharSequence text = label.getVisualOrderText();
        int tw = font.width(text);
        p.text(font, text, x + (w - tw) / 2, y + (h - 4 - 8) / 2 + 1,
               active ? TEXT : 0xFFA0A0A0, true);
    }

    static final int PILL_H = 11;

    /** A small dark tag with text; returns its width. */
    static int pill(Paint p, Font font, int x, int y, Component label) {
        FormattedCharSequence text = label.getVisualOrderText();
        int w = font.width(text) + 7;
        bevel(p, x, y, w, PILL_H, 0xFF252525, 0x2EFFFFFF, 0xFF1A1A1A);
        p.text(font, text, x + 4, y + 2, SOFT, false);
        return w;
    }

    static int pillWidth(Font font, Component label) {
        return font.width(label) + 7;
    }

    /**
     * A bar split into colored segments of these percentages, black lines between them, and
     * the rest up to 100% hatched gray.
     */
    static void bar(Paint p, int x, int y, int w, int h, int[] percents) {
        p.fill(x, y, x + w, y + h, BLACK);
        int inner = w - 2;
        int left = x + 1;
        int used = 0;
        for (int i = 0; i < percents.length; i++) {
            used += percents[i];
            int right = x + 1 + inner * Math.min(100, used) / 100;
            if (right - left > 1) {
                segment(p, left, y + 1, right, y + h - 1, SEGMENTS[i % SEGMENTS.length]);
            }
            left = right + 1; // one black pixel between segments
        }
        int end = x + 1 + inner;
        for (int sx = left; sx < end; sx += 2) {
            p.fill(sx, y + 1, Math.min(sx + 2, end), y + h - 1,
                   (sx - left) / 2 % 2 == 0 ? REST_A : REST_B);
        }
    }

    /** A flat color with a lighter top line and a darker bottom line, like Java's bars. */
    private static void segment(Paint p, int x1, int y1, int x2, int y2, int color) {
        p.fill(x1, y1, x2, y2, color);
        p.fill(x1, y1, x2, y1 + 1, blend(color, 0xFFFFFFFF, 0.35));
        p.fill(x1, y2 - 1, x2, y2, blend(color, BLACK, 0.3));
    }

    /** The color of the rest segment, for its legend square. */
    static void restSwatch(Paint p, int x, int y, int size) {
        p.fill(x, y, x + size, y + size, BLACK);
        for (int i = 1; i < size - 1; i++) {
            p.fill(x + i, y + 1, x + i + 1, y + size - 1, i % 2 == 0 ? REST_A : REST_B);
        }
    }

    static void swatch(Paint p, int x, int y, int size, int color) {
        p.fill(x, y, x + size, y + size, BLACK);
        p.fill(x + 1, y + 1, x + size - 1, y + size - 1, color);
    }

    static int blend(int a, int b, double t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (a & 0xFF000000) | r << 16 | g << 8 | bl;
    }
}
