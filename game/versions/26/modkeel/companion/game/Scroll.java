package modkeel.companion.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.Layout;

/** A scrollable screen body: vanilla's ScrollableLayout. */
final class Scroll extends ScrollableLayout {
    Scroll(Minecraft minecraft, Layout content, int maxHeight) {
        super(minecraft, content, maxHeight);
    }
}
