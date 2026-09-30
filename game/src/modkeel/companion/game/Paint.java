package modkeel.companion.game;

import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;

/** The two drawing calls Modkeel's own widgets need, over whatever Minecraft draws with. */
interface Paint {
    /** Fills the rectangle from (x1, y1) to (x2, y2), exclusive, with an ARGB color. */
    void fill(int x1, int y1, int x2, int y2, int argb);

    void text(Font font, FormattedCharSequence text, int x, int y, int argb, boolean shadow);
}
