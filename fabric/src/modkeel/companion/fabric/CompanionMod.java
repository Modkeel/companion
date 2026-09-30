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
        boolean quilt = loader.isModLoaded("quilt_loader");
        Common.init(loader.getGameDir(), version(loader, "minecraft"), quilt ? "quilt" : "fabric",
                version(loader, quilt ? "quilt_loader" : "fabricloader"), CompanionMod::selfJar);
        ServerLifecycleEvents.SERVER_STARTING.register(Common::serverStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Common.serverStopped());
        ServerTickEvents.END_SERVER_TICK.register(server -> Common.serverTick());
        if (loader.getEnvironmentType() == EnvType.SERVER) {
            Common.dedicatedServerStartup();
        }
    }

    private static String version(FabricLoader loader, String id) {
        return loader.getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }

    private static Path selfJar() {
        return FabricLoader.getInstance().getModContainer("modkeel")
                .map(c -> c.getOrigin().getPaths().get(0))
                .orElseThrow(() -> new IllegalStateException("modkeel container not found"));
    }
}
