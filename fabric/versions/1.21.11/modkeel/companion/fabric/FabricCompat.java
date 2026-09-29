package modkeel.companion.fabric;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

/** Fabric API calls that changed between Minecraft versions. */
final class FabricCompat {
    private FabricCompat() {
    }

    static void addWidget(Screen screen, AbstractWidget widget) {
        Screens.getButtons(screen).add(widget);
    }
}
