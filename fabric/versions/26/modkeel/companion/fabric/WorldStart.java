package modkeel.companion.fabric;

import modkeel.companion.game.Common;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** When a world starts, for this version: no client classes, dedicated servers load it too. */
final class WorldStart {
    private WorldStart() {
    }

    /** The loading screen is up from server start, so the backup runs there. */
    static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(Common::serverStarting);
    }
}
