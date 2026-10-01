package modkeel.companion.game;

import net.minecraft.client.gui.layouts.Layout;

/**
 * Behind one of our screens: darker strips for the header and footer and a translucent panel
 * behind the scroll area, so text reads well while the paused world still shows through. The
 * panel's edges are the scroll area's: content is cut exactly at its border, never above it.
 */
final class Backdrop extends Canvas {
    private final int header;
    private final int footer;
    private final Layout view;

    /** @param view the scroll area; the panel spans it, scroll bar included */
    Backdrop(int screenWidth, int screenHeight, int header, int footer, Layout view) {
        super(screenWidth, screenHeight);
        this.header = header;
        this.footer = footer;
        this.view = view;
    }

    @Override
    protected void paint(Paint p, int mouseX, int mouseY) {
        int w = getWidth();
        int h = getHeight();
        Keel.strip(p, 0, 0, w, header, false);
        Keel.strip(p, 0, h - footer, w, footer, true);
        int x = view.getX() - Keel.PANEL_MARGIN;
        int pw = view.getWidth() + 2 * Keel.PANEL_MARGIN;
        int mid = h - header - footer;
        Keel.checks(p, 0, header, x, mid);
        Keel.checks(p, x + pw, header, w - x - pw, mid);
        Keel.panel(p, x, header, pw, mid);
    }
}
