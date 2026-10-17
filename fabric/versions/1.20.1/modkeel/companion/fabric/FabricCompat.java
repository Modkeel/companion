package modkeel.companion.fabric;

import modkeel.companion.game.Compat;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

/** Fabric API calls that changed between Minecraft versions. */
final class FabricCompat {
    /** The loading screen draws no widgets (1.20.1): the bar is drawn after it instead. */
    static final boolean BACKUP_BAR_BY_EVENT = true;

    private FabricCompat() {
    }

    static void addWidget(Screen screen, AbstractWidget widget) {
        Screens.getButtons(screen).add(widget);
    }

    static void afterScreenInit(Screen screen) {
        ScreenEvents.afterRender(screen).register((s, g, mouseX, mouseY, delta) ->
                Compat.drawBackupBar(s, g));
    }
}
