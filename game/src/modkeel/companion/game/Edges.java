package modkeel.companion.game;

import net.minecraft.client.gui.layouts.Layout;

/**
 * Drawn over a scrolling body: a black line and a short shadow where the content runs under
 * the top or bottom edge, so a line cut in half reads as passing under it, not as broken text.
 */
final class Edges extends Canvas {
    private static final int SHADOW = 6;

    private final Layout view;
    private final Layout content;

    /** @param view the scroll area; @param content what scrolls inside it */
    Edges(int screenWidth, int screenHeight, Layout view, Layout content) {
        super(screenWidth, screenHeight);
        this.view = view;
        this.content = content;
    }

    @Override
    protected void paint(Paint p, int mouseX, int mouseY) {
        int x1 = view.getX();
        int x2 = x1 + view.getWidth();
        int top = view.getY();
        int bottom = top + view.getHeight();
        if (content.getY() < top) {
            p.fill(x1, top, x2, top + 1, Keel.BLACK);
            shadow(p, x1, x2, top + 1, 1);
        }
        if (content.getY() + content.getHeight() > bottom) {
            p.fill(x1, bottom - 1, x2, bottom, Keel.BLACK);
            shadow(p, x1, x2, bottom - 2, -1);
        }
    }

    /** Rows fading from dark at {@code y} away from the edge, in direction {@code dir}. */
    private static void shadow(Paint p, int x1, int x2, int y, int dir) {
        for (int i = 0; i < SHADOW; i++) {
            int alpha = 0x90 * (SHADOW - i) / SHADOW;
            int row = y + dir * i;
            p.fill(x1, row, x2, row + 1, alpha << 24);
        }
    }
}
