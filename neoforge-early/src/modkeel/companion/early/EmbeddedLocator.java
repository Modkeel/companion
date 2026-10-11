package modkeel.companion.early;

import java.io.IOException;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * NeoForge skips a mods-folder jar that holds services ({@link EarlyService}) when it looks for
 * mods, so the Modkeel mod travels inside this jar and is handed to NeoForge from here.
 */
public final class EmbeddedLocator implements IModFileCandidateLocator {
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        try {
            pipeline.addPath(Note.unpack(EmbeddedLocator.class, FMLPaths.GAMEDIR.get()
                            .resolve("modkeel").resolve("neoforge").resolve("modkeel-companion.jar")),
                    ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR);
        } catch (IOException e) {
            EarlyService.LOG.warn("[modkeel] cannot unpack the mod: " + e);
        }
    }

    @Override
    public String toString() {
        return "modkeel";
    }
}
