package modkeel.companion.fabric;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

/** Fabric API calls that changed between Minecraft versions. */
final class FabricCompat {
    static final boolean BACKUP_BAR_BY_EVENT = false;

    private FabricCompat() {
    }

    static void addWidget(Screen screen, AbstractWidget widget) {
        Screens.getWidgets(screen).add(widget);
    }

    /** Nothing to add: the bar rides on a widget here. */
    static void afterScreenInit(Screen screen) {
    }
}
