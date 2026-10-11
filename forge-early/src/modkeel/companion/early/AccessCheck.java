package modkeel.companion.early;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.minecraftforge.accesstransformer.parser.AccessTransformerList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forge 1.20.1 reads every mod's access transformer before any mod runs. One it cannot parse
 * (a jar made for a newer Minecraft, where the format changed) stops the game with no message
 * and no report, on every start, before Modkeel could run. So each jar's access transformer,
 * and those of the jars it bundles, is read first with Forge's own parser; a jar that would
 * stop the game is turned off, and a note left for Modkeel to show once the game is up
 * ({@link Note}).
 */
final class AccessCheck {
    static final Logger LOG = LogManager.getLogger("modkeel");
    static final String AT = "META-INF/accesstransformer.cfg";
    static final String BUNDLED = "META-INF/jarjar/";

    private AccessCheck() {
    }

    static void run(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return;
        }
        List<Path> jars;
        try (Stream<Path> s = Files.list(mods)) {
            jars = s.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p))
                    .sorted().toList();
        } catch (IOException e) {
            LOG.warn("[modkeel] cannot list " + mods + ": " + e);
            return;
        }
        for (Path jar : jars) {
            if (!breaks(jar)) {
                continue;
            }
            Path off = Note.disabledName(jar);
            try {
                Files.move(jar, off);
            } catch (IOException e) {
                LOG.warn("[modkeel] cannot turn off " + jar.getFileName() + ": " + e);
                continue;
            }
            LOG.info("[modkeel] turned off " + jar.getFileName() + ": Forge cannot read its access"
                     + " transformer (made for another Minecraft version) and would close the game");
            Note.add(gameDir, jar, off, "at");
        }
    }

    /** Forge would stop reading this jar's access transformer, or that of a jar it bundles. */
    static boolean breaks(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry at = z.getEntry(AT);
            if (at != null) {
                try (InputStream in = z.getInputStream(at)) {
                    if (!parses(in.readAllBytes(), jar.getFileName().toString())) {
                        return true;
                    }
                }
            }
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry inner = e.nextElement();
                if (inner.getName().startsWith(BUNDLED) && inner.getName().endsWith(".jar")) {
                    try (InputStream in = z.getInputStream(inner)) {
                        if (!bundledParses(in.readAllBytes(), inner.getName())) {
                            return true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            // not a jar Forge can open either: it says so itself
        }
        return false;
    }

    private static boolean bundledParses(byte[] jar, String name) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().equals(AT)) {
                    return parses(z.readAllBytes(), name);
                }
            }
        }
        return true;
    }

    /** What Forge does with each mod's access transformer; it throws on one it cannot parse. */
    static boolean parses(byte[] cfg, String name) throws IOException {
        Path tmp = Files.createTempFile("modkeel-at", ".cfg");
        try {
            Files.write(tmp, cfg);
            new AccessTransformerList().loadFromPath(tmp, name);
            return true;
        } catch (RuntimeException e) {
            return false;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
