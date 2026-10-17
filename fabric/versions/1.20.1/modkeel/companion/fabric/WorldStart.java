package modkeel.companion.fabric;

import modkeel.companion.game.Common;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;

/** When a world starts, for this version: no client classes, dedicated servers load it too. */
final class WorldStart {
    private WorldStart() {
    }

    /**
     * The client shows its loading screen only once the server has made its levels: a backup at
     * server start froze the window without the bar, so on clients it waits for the levels.
     */
    static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                Common.serverStarting(server, server.isDedicatedServer()));
        ServerWorldEvents.LOAD.register((server, level) -> Common.levelsCreated(server));
    }
}
