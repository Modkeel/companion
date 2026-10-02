package modkeel.companion.fabric;

import java.nio.file.Path;

import modkeel.companion.game.StartingCrash;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Fabric runs this before Minecraft's first class loads: a crash that stopped the last start
 * there (a mixin that breaks a game class) is offered its fix before it happens again.
 * Touches no Minecraft class, nor {@link CompanionMod}, whose imports would load some.
 */
public final class CompanionPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.getEnvironmentType() != EnvType.CLIENT) {
            return; // a server has no screen to wait for: its startup runs as before
        }
        boolean quilt = loader.isModLoaded("quilt_loader");
        StartingCrash.preLaunch(loader.getGameDir(), version(loader, "minecraft"),
                quilt ? "quilt" : "fabric", version(loader, quilt ? "quilt_loader" : "fabricloader"),
                CompanionPreLaunch::selfJar);
    }

    static String version(FabricLoader loader, String id) {
        return loader.getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }

    static Path selfJar() {
        return FabricLoader.getInstance().getModContainer("modkeel")
                .map(c -> c.getOrigin().getPaths().get(0))
                .orElseThrow(() -> new IllegalStateException("modkeel container not found"));
    }
}
