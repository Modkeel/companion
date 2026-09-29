package modkeel.companion.game;

import net.minecraft.client.gui.layouts.LinearLayout;

/** A row or a column of elements with a gap between them. */
public final class Stack extends LinearLayout {
    private Stack(Orientation orientation, int spacing) {
        super(0, 0, orientation);
        spacing(spacing);
    }

    public static Stack vertical(int spacing) {
        return new Stack(Orientation.VERTICAL, spacing);
    }

    public static Stack horizontal(int spacing) {
        return new Stack(Orientation.HORIZONTAL, spacing);
    }
}
