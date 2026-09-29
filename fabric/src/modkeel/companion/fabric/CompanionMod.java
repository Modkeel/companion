package modkeel.companion.fabric;

import java.nio.file.Path;

import modkeel.companion.game.Common;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric common entrypoint: world backups and the last good set, on clients and servers. */
public final class CompanionMod implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();
        Common.init(loader.getGameDir(), loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(""),
                CompanionMod::selfJar);
        ServerLifecycleEvents.SERVER_STARTING.register(Common::serverStarting);
        ServerTickEvents.END_SERVER_TICK.register(server -> Common.serverTick());
        if (loader.getEnvironmentType() == EnvType.SERVER) {
            Common.dedicatedServerStartup();
        }
    }

    private static Path selfJar() {
        return FabricLoader.getInstance().getModContainer("modkeel")
                .map(c -> c.getOrigin().getPaths().get(0))
                .orElseThrow(() -> new IllegalStateException("modkeel container not found"));
    }
}
