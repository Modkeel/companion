package modkeel.companion.game;

import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;

/**
 * A row or a column of elements with a gap between them. 1.20.1's LinearLayout has no spacing,
 * so this is a one-row or one-column grid.
 */
public final class Stack extends GridLayout {
    private final boolean vertical;
    private int next;

    private Stack(boolean vertical, int spacing) {
        this.vertical = vertical;
        spacing(spacing);
    }

    public static Stack vertical(int spacing) {
        return new Stack(true, spacing);
    }

    public static Stack horizontal(int spacing) {
        return new Stack(false, spacing);
    }

    public <T extends LayoutElement> T addChild(T child) {
        int i = next++;
        return vertical ? addChild(child, i, 0) : addChild(child, 0, i);
    }
}
