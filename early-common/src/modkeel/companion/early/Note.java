package modkeel.companion.early;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * What the early checks share: how a jar is turned off, the note they leave for Modkeel (read
 * by Guardian once the game is up), and unpacking the mod the early jar carries.
 */
public final class Note {
    /** One "jar<TAB>disabled jar<TAB>why[<TAB>detail...]" line per jar turned off. */
    public static final String FILE = "early.txt";
    /** The mod inside the early jar: the loader skips a mods-folder jar that holds services. */
    public static final String INNER = "/META-INF/modkeel/companion.jar";

    private Note() {
    }

    /** "x.jar" to "x.jar.disabled", as Modkeel names the jars it turns off. */
    public static Path disabledName(Path jar) {
        Path p = jar.resolveSibling(jar.getFileName() + ".disabled");
        for (int i = 2; Files.exists(p); i++) {
            p = jar.resolveSibling(jar.getFileName() + "." + i + ".disabled");
        }
        return p;
    }

    /** Append one line to the note; `why` is "at" or "module", details follow it. */
    public static void add(Path gameDir, Path jar, Path off, String why, String... detail) {
        StringBuilder line = new StringBuilder()
                .append(jar.getFileName()).append('\t').append(off.getFileName()).append('\t').append(why);
        for (String d : detail) {
            line.append('\t').append(d.replace('\t', ' '));
        }
        Path file = gameDir.resolve("modkeel").resolve(FILE);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // the jar stays off; the player sees it disabled in Mods without the reason
        }
    }

    /** The mod this jar carries, written to `dest` only when it changed. */
    public static Path unpack(Class<?> from, Path dest) throws IOException {
        byte[] jar;
        try (InputStream in = from.getResourceAsStream(INNER)) {
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
        }
        return dest;
    }
}
