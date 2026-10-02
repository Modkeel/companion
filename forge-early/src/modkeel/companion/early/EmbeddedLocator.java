package modkeel.companion.early;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;

/**
 * Forge skips a mods-folder jar that holds services ({@link EarlyService}) when it looks for
 * mods, so the Modkeel mod travels inside this jar and is handed to Forge from here.
 */
public final class EmbeddedLocator extends AbstractJarFileModLocator {
    static final String INNER = "/META-INF/modkeel/companion.jar";

    @Override
    public Stream<Path> scanCandidates() {
        try {
            return Stream.of(extract(FMLPaths.GAMEDIR.get().resolve("modkeel").resolve("forge")
                    .resolve("modkeel-companion.jar")));
        } catch (IOException e) {
            AccessCheck.LOG.warn("[modkeel] cannot unpack the mod: " + e);
            return Stream.empty();
        }
    }

    /** The bundled mod at `dest`, written again only when it changed. */
    static Path extract(Path dest) throws IOException {
        byte[] jar;
        try (InputStream in = EmbeddedLocator.class.getResourceAsStream(INNER)) {
            if (in == null) {
                throw new IOException(INNER + " missing");
            }
            jar = in.readAllBytes();
        }
        if (Files.exists(dest) && Arrays.equals(Files.readAllBytes(dest), jar)) {
            return dest;
        }
        Files.createDirectories(dest.getParent());
        try {
            Files.write(dest, jar);
        } catch (IOException e) {
            if (!Files.exists(dest)) {
                throw e;
            }
            // another game on this folder holds it open: run the copy it unpacked
            AccessCheck.LOG.warn("[modkeel] cannot update " + dest + ": " + e);
        }
        return dest;
    }

    @Override
    public String name() {
        return "modkeel";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
    }
}
