package modkeel.companion.game;

/**
 * Behind one of our screens: darker strips for the header and footer and a translucent panel
 * behind the content column, so text reads well while the paused world still shows through.
 */
final class Backdrop extends Canvas {
    private final int header;
    private final int footer;
    private final int column;

    /** @param column the content's width; the panel adds a margin around it */
    Backdrop(int screenWidth, int screenHeight, int header, int footer, int column) {
        super(screenWidth, screenHeight);
        this.header = header;
        this.footer = footer;
        this.column = column;
    }

    @Override
    protected void paint(Paint p, int mouseX, int mouseY) {
        int w = getWidth();
        int h = getHeight();
        Keel.strip(p, 0, 0, w, header, false);
        Keel.strip(p, 0, h - footer, w, footer, true);
        int x = (w - column) / 2 - Keel.PANEL_MARGIN;
        Keel.panel(p, x, header + 4, column + 2 * Keel.PANEL_MARGIN, h - header - footer - 8);
    }
}
