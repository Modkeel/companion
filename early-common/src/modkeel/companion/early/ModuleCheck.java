package modkeel.companion.early;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * (Neo)Forge puts every mod in one Java module layer. Two jars that hold the same package (a
 * library both shade, the same mod twice under different ids) make Java refuse the layer, and
 * the game closes before any mod runs: no crash report, nothing Modkeel could show, and the same
 * on every start. Java names the package in the log it leaves
 * ({@code ResolutionException: Modules a and b export package p to module c}).
 *
 * <p>So, before the loader looks for mods, the log of the previous start is read: if that start
 * ended this way, one of the jars holding the package is turned off (the one that was not there
 * when the game last started fine, else the newest), and a note left for Modkeel to show once
 * the game is up, with the other jar and the package, so the player can choose differently.
 * One jar per start: Java only reports the first clash, and each start reads the one before.
 *
 * <p>Plain Java only: it runs before the loader and is shared by the Forge and NeoForge early
 * services; the logger is passed in.
 */
public final class ModuleCheck {
    /** Java's messages for a package two modules provide (java.lang.module.Resolver, ModuleLayer). */
    static final List<Pattern> CLASHES = List.of(
            Pattern.compile("Modules (\\S+) and (\\S+) export package ([\\w.$]+) to module \\S+"),
            Pattern.compile("Module (\\S+) contains package ([\\w.$]+), module (\\S+) exports package"),
            Pattern.compile("Package ([\\w.$]+) in both module (\\S+) and module (\\S+)"));
    /** The log of the start before this one, rolled over by log4j when this start began. */
    static final Pattern ROLLED = Pattern.compile(".+\\.log\\.gz");
    /** The first lines are enough: the layer is built before the game window opens. */
    static final int READ_AT_MOST = 4 << 20;
    /** Which rolled log was already handled, so one failure is acted on once. */
    static final String SEEN = "early-module.txt";
    static final String BUNDLED = "META-INF/jarjar/";

    /** What Java refused: the package, and the modules it named. */
    record Clash(String pkg, List<String> modules) {
    }

    private ModuleCheck() {
    }

    /** Read the previous start's log and turn off one jar if it shows a package clash. */
    public static void run(Path gameDir, Consumer<String> log) {
        Path home = gameDir.resolve("modkeel");
        Path previous = previousLog(gameDir.resolve("logs"));
        if (previous == null) {
            return;
        }
        String seenAs = previous.getFileName() + "\t" + size(previous);
        Path seenFile = home.resolve(SEEN);
        try {
            if (Files.exists(seenFile) && Files.readString(seenFile).strip().equals(seenAs)) {
                return;
            }
        } catch (IOException e) {
            // read again below
        }
        Clash clash;
        try {
            clash = find(readStart(previous));
        } catch (IOException e) {
            log.accept("[modkeel] cannot read " + previous + ": " + e);
            return;
        }
        if (clash != null) {
            fix(gameDir, clash, log);
        }
        try {
            Files.createDirectories(home);
            Files.writeString(seenFile, seenAs + "\n");
        } catch (IOException e) {
            log.accept("[modkeel] cannot write " + seenFile + ": " + e);
        }
    }

    /** Turn off the jar to blame for `clash` and leave the note; false when none can be. */
    static boolean fix(Path gameDir, Clash clash, Consumer<String> log) {
        Path mods = gameDir.resolve("mods");
        List<Path> holders = holders(mods, clash.pkg());
        if (holders.isEmpty()) {
            log.accept("[modkeel] the last start stopped: modules " + clash.modules()
                       + " both have package " + clash.pkg() + ", in no jar Modkeel can turn off");
            return false;
        }
        Path jar = blame(holders, lastGood(gameDir.resolve("modkeel")));
        Path off = Note.disabledName(jar);
        try {
            Files.move(jar, off);
        } catch (IOException e) {
            log.accept("[modkeel] cannot turn off " + jar.getFileName() + ": " + e);
            return false;
        }
        String other = holders.stream().filter(p -> !p.equals(jar)).findFirst()
                .map(p -> p.getFileName().toString()).orElse("");
        log.accept("[modkeel] turned off " + jar.getFileName() + ": it and "
                   + (other.isEmpty() ? "the game" : other) + " both have package " + clash.pkg()
                   + ", and the game cannot start with both");
        Note.add(gameDir, jar, off, "module", other, clash.pkg());
        return true;
    }

    /**
     * Which of the jars holding the package to turn off: one that was not in the last set the
     * game started with (what changed since is what broke it), else the newest file.
     */
    static Path blame(List<Path> holders, Set<String> lastGood) {
        List<Path> pool = holders.stream()
                .filter(p -> !lastGood.contains(p.getFileName().toString())).toList();
        if (pool.isEmpty()) {
            pool = holders;
        }
        return pool.stream().max((a, b) -> Long.compare(mtime(a), mtime(b))).orElseThrow();
    }

    /** The newest rolled log: the start before this one. */
    static Path previousLog(Path logs) {
        if (!Files.isDirectory(logs)) {
            return null;
        }
        try (Stream<Path> s = Files.list(logs)) {
            return s.filter(p -> ROLLED.matcher(p.getFileName().toString()).matches()
                                 && Files.isRegularFile(p))
                    .max((a, b) -> Long.compare(mtime(a), mtime(b))).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static String readStart(Path gz) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
            return new String(in.readNBytes(READ_AT_MOST), StandardCharsets.UTF_8);
        }
    }

    /** The package clash a log shows, or null. */
    static Clash find(String log) {
        for (int i = 0; i < CLASHES.size(); i++) {
            Matcher m = CLASHES.get(i).matcher(log);
            if (m.find()) {
                return switch (i) {
                    case 0 -> new Clash(m.group(3), List.of(m.group(1), m.group(2)));
                    case 1 -> new Clash(m.group(2), List.of(m.group(1), m.group(3)));
                    default -> new Clash(m.group(1), List.of(m.group(2), m.group(3)));
                };
            }
        }
        return null;
    }

    /** The jars in the mods folder that hold classes of `pkg`, themselves or in a bundled jar. */
    static List<Path> holders(Path mods, String pkg) {
        String dir = pkg.replace('.', '/') + "/";
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(mods)) {
            for (Path jar : s.filter(p -> p.getFileName().toString().endsWith(".jar")
                                          && Files.isRegularFile(p)).sorted().toList()) {
                if (!isModkeel(jar) && holds(jar, dir)) {
                    out.add(jar);
                }
            }
        } catch (IOException e) {
            // no mods folder: nothing to turn off
        }
        return out;
    }

    static boolean holds(Path jar, String dir) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (inPackage(name, dir)) {
                    return true;
                }
                if (name.startsWith(BUNDLED) && name.endsWith(".jar")) {
                    try (InputStream in = z.getInputStream(entry)) {
                        if (bundledHolds(in.readAllBytes(), dir)) {
                            return true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            // not a jar the loader can open either
        }
        return false;
    }

    private static boolean bundledHolds(byte[] jar, String dir) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (inPackage(e.getName(), dir)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A class directly in the package (or its multi-release copy), not in a sub-package. */
    static boolean inPackage(String entry, String dir) {
        String name = entry.startsWith("META-INF/versions/")
                ? entry.replaceFirst("^META-INF/versions/\\d+/", "") : entry;
        return name.startsWith(dir) && name.endsWith(".class")
               && name.indexOf('/', dir.length()) < 0;
    }

    /** Modkeel's own jar is never the one to blame: it is what does the turning off. */
    static boolean isModkeel(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry("META-INF/modkeel/companion.jar") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** File names in the last set the game started with (Guardian's lastgood.txt). */
    static Set<String> lastGood(Path home) {
        Set<String> names = new HashSet<>();
        try {
            for (String line : Files.readAllLines(home.resolve("lastgood.txt"), StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    names.add(line.substring(tab + 1));
                }
            }
        } catch (IOException e) {
            // never started fine with Modkeel yet
        }
        return names;
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }
}
