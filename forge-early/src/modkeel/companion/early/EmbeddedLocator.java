package modkeel.companion.early;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;

/**
 * Forge skips a mods-folder jar that holds services ({@link EarlyService}) when it looks for
 * mods, so the Modkeel mod travels inside this jar and is handed to Forge from here.
 */
public final class EmbeddedLocator extends AbstractJarFileModLocator {
    @Override
    public Stream<Path> scanCandidates() {
        try {
            return Stream.of(Note.unpack(EmbeddedLocator.class, FMLPaths.GAMEDIR.get()
                    .resolve("modkeel").resolve("forge").resolve("modkeel-companion.jar")));
        } catch (IOException e) {
            AccessCheck.LOG.warn("[modkeel] cannot unpack the mod: " + e);
            return Stream.empty();
        }
    }

    @Override
    public String name() {
        return "modkeel";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
    }
}
