package modkeel.companion.early;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * (Neo)Forge puts every mod in Java modules that must not share a package. Two jars that hold
 * the same one (two mods shading one library without relocating it, a library both bundle
 * under different coordinates) make Java refuse to build the layer, and the game closes before
 * any mod runs: no crash report, nothing Modkeel could show, and the same on every start.
 *
 * <p>Before the loader looks for mods, this works out which jars it will load and their
 * packages, the way the loader does (see {@link #units}), and when two share a package it turns
 * one off (the one not there when the game last started fine, else the newest) and leaves a
 * note for Modkeel to show once the game is up, with the other jar, so the player can swap
 * them. The game starts at the first try.
 *
 * <p>What that misses (a clash with a jar the loader itself brings), NeoForge names in its log
 * ({@code ResolutionException: Modules a and b export package p to module c}): when nothing was
 * predicted, the previous start's log is read and the same is done from it. Forge 1.20.1 prints
 * that error to stderr only, so there the prediction is all there is.
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
    static final String BUNDLED_LIST = "META-INF/jarjar/metadata.json";
    /** A jar with one of these services is loaded with the loader, not as a mod. */
    static final Set<String> SERVICES = Set.of(
            "cpw.mods.modlauncher.api.ITransformationService",
            "net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
            "net.neoforged.neoforgespi.locating.IModFileReader",
            "net.neoforged.neoforgespi.locating.IDependencyLocator",
            "net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper",
            "net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider",
            "net.minecraftforge.forgespi.locating.IModLocator",
            "net.minecraftforge.forgespi.locating.IDependencyLocator",
            "net.minecraftforge.fml.loading.ImmediateWindowProvider");
    /** Most clashes a start resolves: each one turns a jar off and looks again. */
    static final int MAX_FIXES = 8;
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern DASH_VERSION = Pattern.compile("-([.\\d]+)");
    private static final Pattern BUNDLED_PATH = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");

    /** What Java refused: the package, and the modules it named. */
    record Clash(String pkg, List<String> modules) {
    }

    /**
     * One module the loader builds: a mod (by its first mod id), or a library (by its module
     * name). The loader keeps one file per module, so files with the same one are one unit, with
     * only the packages they all have (whichever it keeps has those). `owners` are the jars in
     * the mods folder that bring it: the jar itself, or the jars that bundle it.
     */
    static final class Unit {
        final String id;
        final Set<Path> owners = new LinkedHashSet<>();
        Set<String> packages;

        Unit(String id, Path owner, Set<String> packages) {
            this.id = id;
            this.owners.add(owner);
            this.packages = new HashSet<>(packages);
        }
    }

    /** Two units the loader would load that share a package. */
    record Predicted(Unit a, Unit b, String pkg) {
    }

    private ModuleCheck() {
    }

    /**
     * Turn off what would stop this start, predicted from the jars; when nothing is, act on what
     * the previous start's log shows. `modsToml` is where this loader reads mod metadata
     * ({@code META-INF/neoforge.mods.toml} or {@code META-INF/mods.toml}).
     */
    public static void run(Path gameDir, String modsToml, Consumer<String> log) {
        int fixed = predict(gameDir, modsToml, log);
        fromLog(gameDir, fixed == 0, log);
    }

    // ---- predicted from the jars ---------------------------------------------------------

    /** Turn off one jar per clash the jars show, until none is left; how many were. */
    static int predict(Path gameDir, String modsToml, Consumer<String> log) {
        Path mods = gameDir.resolve("mods");
        Set<String> lastGood = lastGood(gameDir.resolve("modkeel"));
        int fixed = 0;
        while (fixed < MAX_FIXES) {
            Predicted p = firstClash(units(mods, modsToml));
            if (p == null) {
                break;
            }
            List<Path> holders = new ArrayList<>(p.a().owners);
            for (Path o : p.b().owners) {
                if (!holders.contains(o)) {
                    holders.add(o);
                }
            }
            if (!turnOff(gameDir, holders, lastGood, p.pkg(), log)) {
                break;
            }
            fixed++;
        }
        return fixed;
    }

    /** The first package two units share, in a stable order; null when none do. */
    static Predicted firstClash(List<Unit> units) {
        Map<String, Unit> byPackage = new HashMap<>();
        for (Unit u : units) {
            for (String pkg : u.packages.stream().sorted().toList()) {
                Unit seen = byPackage.putIfAbsent(pkg, u);
                if (seen != null && seen != u) {
                    return new Predicted(seen, u, pkg);
                }
            }
        }
        return null;
    }

    /**
     * The modules the loader will build from the mods folder, as it decides them: a jar is
     * loaded when it has this loader's mod metadata or a manifest FMLModType (else the loader
     * skips it: a Fabric mod, a stray file), unless it holds loader services (those load with the
     * loader) or is Modkeel's own; each jar it bundles and lists in its jar-in-jar metadata is a
     * module too. Packages are the folders holding .class files, outside META-INF (and, for
     * mods, assets/ and data/), as the loader's jar handler counts them.
     */
    static List<Unit> units(Path mods, String modsToml) {
        Map<String, Unit> byId = new LinkedHashMap<>();
        List<Path> jars;
        try (Stream<Path> s = Files.list(mods)) {
            jars = s.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
        for (Path jar : jars) {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                Contents top = Contents.of(z);
                if (top.has(Note.INNER.substring(1)) || top.holdsServices() || !top.loaded(modsToml)) {
                    continue;
                }
                merge(byId, top.id(modsToml, jar.getFileName().toString()), jar, top.packages(modsToml));
                for (String path : top.bundled()) {
                    ZipEntry e = z.getEntry(path);
                    if (e == null) {
                        continue;
                    }
                    try (InputStream in = z.getInputStream(e)) {
                        Contents inner = Contents.of(in.readAllBytes());
                        String name = path.substring(path.lastIndexOf('/') + 1);
                        merge(byId, inner.id(modsToml, name), jar, inner.packages(modsToml));
                    }
                }
            } catch (IOException | RuntimeException e) {
                // a jar the loader cannot read either: it says so itself
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static void merge(Map<String, Unit> byId, String id, Path owner, Set<String> packages) {
        Unit u = byId.get(id);
        if (u == null) {
            byId.put(id, new Unit(id, owner, packages));
        } else {
            u.owners.add(owner);
            u.packages.retainAll(packages);
        }
    }

    /** One jar's entries, manifest and the text of its metadata files. */
    record Contents(List<String> names, Manifest manifest, Map<String, String> texts) {
        static Contents of(ZipFile z) throws IOException {
            List<String> names = new ArrayList<>();
            Map<String, String> texts = new HashMap<>();
            Manifest manifest = null;
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                names.add(entry.getName());
                if (keepText(entry.getName())) {
                    try (InputStream in = z.getInputStream(entry)) {
                        byte[] bytes = in.readAllBytes();
                        if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                            manifest = new Manifest(new ByteArrayInputStream(bytes));
                        } else {
                            texts.put(entry.getName(), new String(bytes, StandardCharsets.UTF_8));
                        }
                    }
                }
            }
            return new Contents(names, manifest, texts);
        }

        static Contents of(byte[] jar) throws IOException {
            List<String> names = new ArrayList<>();
            Map<String, String> texts = new HashMap<>();
            Manifest manifest = null;
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(jar))) {
                for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                    names.add(e.getName());
                    if (keepText(e.getName())) {
                        byte[] bytes = z.readAllBytes();
                        if (e.getName().equals("META-INF/MANIFEST.MF")) {
                            manifest = new Manifest(new ByteArrayInputStream(bytes));
                        } else {
                            texts.put(e.getName(), new String(bytes, StandardCharsets.UTF_8));
                        }
                    }
                }
            }
            return new Contents(names, manifest, texts);
        }

        private static boolean keepText(String name) {
            return name.equals("META-INF/MANIFEST.MF") || name.equals("META-INF/neoforge.mods.toml")
                   || name.equals("META-INF/mods.toml") || name.equals(BUNDLED_LIST);
        }

        boolean has(String name) {
            return names.contains(name);
        }

        String attribute(String name) {
            return manifest == null ? null : manifest.getMainAttributes().getValue(name);
        }

        boolean holdsServices() {
            return names.stream().anyMatch(n -> n.startsWith("META-INF/services/")
                    && SERVICES.contains(n.substring("META-INF/services/".length())));
        }

        boolean loaded(String modsToml) {
            return has(modsToml) || attribute("FMLModType") != null;
        }

        /** The module the loader names it: "mod:" its first mod id, else "lib:" its name. */
        String id(String modsToml, String fileName) {
            String toml = texts.get(modsToml);
            if (toml != null) {
                Matcher m = MOD_ID.matcher(toml);
                if (m.find()) {
                    return "mod:" + m.group(1);
                }
            }
            String auto = attribute("Automatic-Module-Name");
            return "lib:" + (auto != null ? auto.strip() : moduleName(fileName));
        }

        Set<String> packages(String modsToml) {
            boolean mod = has(modsToml);
            Set<String> out = new HashSet<>();
            for (String n : names) {
                int slash = n.lastIndexOf('/');
                if (!n.endsWith(".class") || slash < 0 || n.startsWith("META-INF/")) {
                    continue;
                }
                if (mod && (n.startsWith("assets/") || n.startsWith("data/"))) {
                    continue;
                }
                out.add(n.substring(0, slash).replace('/', '.'));
            }
            return out;
        }

        /** The bundled jars the jar-in-jar metadata lists (the loader picks among those only). */
        List<String> bundled() {
            String list = texts.get(BUNDLED_LIST);
            if (list == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            Matcher m = BUNDLED_PATH.matcher(list);
            while (m.find()) {
                out.add(m.group(1));
            }
            return out;
        }
    }

    /**
     * A library's module name from its file name, as the loader's jar handler derives it:
     * without ".jar" and its "-1.2.3" parts, other characters as dots.
     */
    static String moduleName(String fileName) {
        String n = fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
        n = DASH_VERSION.matcher(n).replaceAll("");
        n = n.replaceAll("[^A-Za-z0-9]", ".").replaceAll("\\.{2,}", ".");
        return n.replaceAll("^\\.|\\.$", "");
    }

    // ---- from the previous start's log ---------------------------------------------------

    /**
     * Read the previous start's log once; when it shows a package clash and `act`, turn off a
     * jar holding the package. Not acting still marks the log as read.
     */
    static void fromLog(Path gameDir, boolean act, Consumer<String> log) {
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
        if (act) {
            try {
                Clash clash = find(readStart(previous));
                if (clash != null) {
                    fix(gameDir, clash, log);
                }
            } catch (IOException e) {
                log.accept("[modkeel] cannot read " + previous + ": " + e);
                return;
            }
        }
        try {
            Files.createDirectories(home);
            Files.writeString(seenFile, seenAs + "\n");
        } catch (IOException e) {
            log.accept("[modkeel] cannot write " + seenFile + ": " + e);
        }
    }

    /** Turn off the jar to blame for a clash the log named; false when none can be. */
    static boolean fix(Path gameDir, Clash clash, Consumer<String> log) {
        List<Path> holders = holders(gameDir.resolve("mods"), clash.pkg());
        if (holders.isEmpty()) {
            log.accept("[modkeel] the last start stopped: modules " + clash.modules()
                       + " both have package " + clash.pkg() + ", in no jar Modkeel can turn off");
            return false;
        }
        return turnOff(gameDir, holders, lastGood(gameDir.resolve("modkeel")), clash.pkg(), log);
    }

    // ---- shared --------------------------------------------------------------------------

    /** Turn off the one of `holders` to blame, log it and leave the note naming the other. */
    static boolean turnOff(Path gameDir, List<Path> holders, Set<String> lastGood, String pkg,
                           Consumer<String> log) {
        Path jar = blame(holders, lastGood);
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
                   + (other.isEmpty() ? "the game" : other) + " both have package " + pkg
                   + ", and the game cannot start with both");
        Note.add(gameDir, jar, off, "module", other, pkg);
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

    /** A class directly in the package, not in a sub-package (META-INF is never a package). */
    static boolean inPackage(String entry, String dir) {
        return entry.startsWith(dir) && entry.endsWith(".class")
               && entry.indexOf('/', dir.length()) < 0;
    }

    /** Modkeel's own jar is never the one to blame: it is what does the turning off. */
    static boolean isModkeel(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry(Note.INNER.substring(1)) != null;
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
