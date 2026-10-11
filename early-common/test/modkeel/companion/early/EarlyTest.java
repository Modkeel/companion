package modkeel.companion.early;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The early checks' plain-Java part, against made-up jars and logs; run by build.py. */
public final class EarlyTest {
    static final String TOML = "META-INF/neoforge.mods.toml";
    static int failures;
    static long clock = 1_000_000;

    public static void main(String[] args) throws Exception {
        predictsTwoModsWithOnePackage();
        whatTheLoaderNeverLoadsIsNoClash();
        bundledJarsAsTheLoaderPicksThem();
        severalClashesInOneStart();
        findsEachMessage();
        holdersAndPackages();
        blamesWhatChanged();
        logIsReadOnceAndOnlyWhenNothingWasPredicted();
        nothingToDo();
        if (failures > 0) {
            System.err.println(failures + " early check(s) failed");
            System.exit(1);
        }
        System.out.println("early checks ok");
    }

    // ---- predicted from the jars ---------------------------------------------------------

    static void predictsTwoModsWithOnePackage() throws IOException {
        Path game = tmp();
        Path mods = game.resolve("mods");
        mod(mods.resolve("create.jar"), "create", "lib/shared/A.class", "create/C.class");
        mod(mods.resolve("flywheel.jar"), "flywheel", "lib/shared/A.class", "flywheel/F.class");
        lastGood(game, "create.jar");
        List<String> log = new ArrayList<>();
        ModuleCheck.run(game, TOML, log::add);
        check(Files.exists(mods.resolve("flywheel.jar.disabled")), "the one not in the last good set is off");
        check(Files.exists(mods.resolve("create.jar")), "the other stays");
        eq(List.of("flywheel.jar\tflywheel.jar.disabled\tmodule\tcreate.jar\tlib.shared"),
           Files.readAllLines(game.resolve("modkeel").resolve(Note.FILE)), "the note names the other");
        check(log.size() == 1 && log.get(0).contains("turned off flywheel.jar"), "logged " + log);
        ModuleCheck.run(game, TOML, log::add);
        eq(1, log.size(), "nothing left on the next start");
    }

    static void whatTheLoaderNeverLoadsIsNoClash() throws IOException {
        Path game = tmp();
        Path mods = game.resolve("mods");
        // the same mod twice: the loader keeps the newer, no clash
        mod(mods.resolve("jei-1.jar"), "jei", "mezz/jei/A.class");
        mod(mods.resolve("jei-2.jar"), "jei", "mezz/jei/A.class");
        // a Fabric mod (no NeoForge metadata, no FMLModType): the loader skips it
        jar(mods.resolve("sodium-fabric.jar"), Map.of("fabric.mod.json", "{}", "mezz/jei/B.class", "x"));
        // a jar of loader services: loaded with the loader, never as a mod
        jar(mods.resolve("service.jar"), Map.of(TOML, "modId = \"svc\"\n", "mezz/jei/C.class", "x",
                "META-INF/services/cpw.mods.modlauncher.api.ITransformationService", "x.Y"));
        // classes in assets/, data/ and META-INF are not packages of a mod
        mod(mods.resolve("a.jar"), "a", "assets/a/Odd.class", "data/a/Odd.class", "META-INF/versions/17/z/Z.class");
        mod(mods.resolve("b.jar"), "b", "assets/a/Odd.class", "data/a/Odd.class", "META-INF/versions/17/z/Z.class");
        // two copies of one library: one module name, the loader keeps one
        jar(mods.resolve("kotlin-1.9.0.jar"), Map.of("META-INF/MANIFEST.MF", manifest("GAMELIBRARY", null),
                "kotlin/K.class", "x"));
        jar(mods.resolve("kotlin-1.9.20.jar"), Map.of("META-INF/MANIFEST.MF", manifest("GAMELIBRARY", null),
                "kotlin/K.class", "x"));
        List<String> log = new ArrayList<>();
        eq(0, ModuleCheck.predict(game, TOML, log::add), "nothing turned off");
        eq(List.of(), log, "nothing logged");
        eq("kotlin", ModuleCheck.moduleName("kotlin-1.9.20.jar"), "library name from the file");
        eq("fabric.api", ModuleCheck.moduleName("fabric-api-0.92.2.jar"), "library name with a dash");
    }

    static void bundledJarsAsTheLoaderPicksThem() throws IOException {
        Path game = tmp();
        Path mods = game.resolve("mods");
        byte[] gson = jarBytes(Map.of("META-INF/MANIFEST.MF", manifest(null, "com.google.gson"),
                "com/google/gson/Gson.class", "x"));
        byte[] gsonNewer = jarBytes(Map.of("META-INF/MANIFEST.MF", manifest(null, "com.google.gson"),
                "com/google/gson/Gson.class", "y", "com/google/gson/New.class", "y"));
        byte[] unlisted = jarBytes(Map.of("org/stray/S.class", "x"));
        // two mods bundling the same library: one module, whichever version is picked
        bundling(mods.resolve("one.jar"), "one", "gson-2.10.jar", gson);
        bundling(mods.resolve("two.jar"), "two", "gson-2.11.jar", gsonNewer);
        // a bundled jar the jar-in-jar list does not name is never loaded
        jar(mods.resolve("three.jar"), Map.of(TOML, "modId = \"three\"\n",
                "META-INF/jarjar/stray.jar", new String(unlisted, StandardCharsets.ISO_8859_1)));
        mod(mods.resolve("four.jar"), "four", "org/stray/S.class");
        eq(0, ModuleCheck.predict(game, TOML, s -> { }), "no clash between bundled copies");
        // a mod whose own classes are a library another mod bundles
        Path shading = mod(mods.resolve("shading.jar"), "shading", "com/google/gson/Gson.class");
        Files.setLastModifiedTime(shading, FileTime.fromMillis(clock += 1000));
        List<String> log = new ArrayList<>();
        eq(1, ModuleCheck.predict(game, TOML, log::add), "a mod against a bundled library");
        check(Files.exists(mods.resolve("shading.jar.disabled")), "the newest jar is off: " + log);
    }

    static void severalClashesInOneStart() throws IOException {
        Path game = tmp();
        Path mods = game.resolve("mods");
        mod(mods.resolve("a.jar"), "a", "p/A.class");
        mod(mods.resolve("b.jar"), "b", "p/B.class");
        mod(mods.resolve("c.jar"), "c", "p/C.class");
        lastGood(game, "a.jar");
        eq(2, ModuleCheck.predict(game, TOML, s -> { }), "two turned off");
        check(Files.exists(mods.resolve("a.jar")), "the one from the last good set stays");
        eq(2, Files.readAllLines(game.resolve("modkeel").resolve(Note.FILE)).size(), "one note line each");
    }

    // ---- from the previous start's log ---------------------------------------------------

    static void findsEachMessage() {
        ModuleCheck.Clash c = ModuleCheck.find("[01:25:07] [main/ERROR]: Error while resolving modules.\n"
                + "java.lang.module.ResolutionException: Modules mfsplita and mfsplitb export package"
                + " modkeel.split to module mixinextras.neoforge\n\tat java.base/...");
        eq("modkeel.split", c.pkg(), "two exporters: package");
        eq(List.of("mfsplita", "mfsplitb"), c.modules(), "two exporters: modules");
        c = ModuleCheck.find("java.lang.module.ResolutionException: Module mfsplitb contains package"
                + " modkeel.split, module mfsplita exports package modkeel.split to mfsplitb");
        eq("modkeel.split", c.pkg(), "contains and exports: package");
        eq(List.of("mfsplitb", "mfsplita"), c.modules(), "contains and exports: modules");
        c = ModuleCheck.find("java.lang.LayerInstantiationException: Package org.joml in both"
                + " module joml and module veil");
        eq("org.joml", c.pkg(), "layer: package");
        eq(null, ModuleCheck.find("[main/INFO]: Loading 3 mods\nModules a and b are fine"), "no clash");
    }

    static void holdersAndPackages() throws IOException {
        Path mods = tmp().resolve("mods");
        jar(mods.resolve("a.jar"), Map.of("lib/shared/A.class", "x"));
        jar(mods.resolve("deeper.jar"), Map.of("lib/shared/inner/B.class", "x"));        // a sub-package
        jar(mods.resolve("mr.jar"), Map.of("META-INF/versions/17/lib/shared/C.class", "x"));  // META-INF
        byte[] inner = jarBytes(Map.of("lib/shared/D.class", "x"));
        jar(mods.resolve("bundles.jar"), Map.of("META-INF/jarjar/inner.jar",
                new String(inner, StandardCharsets.ISO_8859_1)));
        jar(mods.resolve("modkeel.jar"), Map.of("lib/shared/E.class", "x", "META-INF/modkeel/companion.jar", "x"));
        jar(mods.resolve("old.jar.disabled"), Map.of("lib/shared/F.class", "x"));
        eq(List.of("a.jar", "bundles.jar"), names(ModuleCheck.holders(mods, "lib.shared")), "holders");
    }

    static void blamesWhatChanged() throws IOException {
        Path mods = tmp().resolve("mods");
        Path a = jar(mods.resolve("a.jar"), Map.of("p/A.class", "x"));
        Path b = jar(mods.resolve("b.jar"), Map.of("p/B.class", "x"));
        Files.setLastModifiedTime(a, FileTime.fromMillis(2_000_000));
        Files.setLastModifiedTime(b, FileTime.fromMillis(1_000_000));
        eq(b, ModuleCheck.blame(List.of(a, b), Set.of("a.jar")), "the jar not in the last good set");
        eq(a, ModuleCheck.blame(List.of(a, b), Set.of()), "else the newest");
        eq(a, ModuleCheck.blame(List.of(a, b), Set.of("a.jar", "b.jar")), "both were there: newest");
    }

    static void logIsReadOnceAndOnlyWhenNothingWasPredicted() throws IOException {
        // a clash the prediction cannot see (jars without metadata here), named by the last log
        Path game = tmp();
        Path mods = game.resolve("mods");
        jar(mods.resolve("mfsplita.jar"), Map.of("modkeel/split/A.class", "x"));
        jar(mods.resolve("mfsplitb.jar"), Map.of("modkeel/split/A.class", "x"));
        lastGood(game, "mfsplita.jar");
        rolledLog(game, "2026-10-11-1.log.gz", "[main/ERROR]: Error while resolving modules.\n"
                + "java.lang.module.ResolutionException: Modules mfsplita and mfsplitb export package"
                + " modkeel.split to module mixinextras.neoforge\n");
        List<String> log = new ArrayList<>();
        ModuleCheck.run(game, TOML, log::add);
        check(Files.exists(mods.resolve("mfsplitb.jar.disabled")), "from the log: the new jar is off");
        // the player turns it back on: the same log is not acted on twice
        Files.move(mods.resolve("mfsplitb.jar.disabled"), mods.resolve("mfsplitb.jar"));
        ModuleCheck.run(game, TOML, log::add);
        check(Files.exists(mods.resolve("mfsplitb.jar")), "one failure, one action");
        // a start that predicted a fix leaves the last log alone (it is marked read)
        Path g2 = tmp();
        mod(g2.resolve("mods").resolve("x.jar"), "x", "q/A.class");
        mod(g2.resolve("mods").resolve("y.jar"), "y", "q/A.class");
        jar(g2.resolve("mods").resolve("z.jar"), Map.of("r/A.class", "x"));
        rolledLog(g2, "2026-10-11-1.log.gz", "ResolutionException: Modules z and w export package r to module x");
        ModuleCheck.run(g2, TOML, s -> { });
        check(Files.exists(g2.resolve("mods").resolve("z.jar")), "not acted on after a prediction");
        check(Files.exists(g2.resolve("modkeel").resolve(ModuleCheck.SEEN)), "but marked read");
    }

    static void nothingToDo() throws IOException {
        Path game = tmp();
        mod(game.resolve("mods").resolve("a.jar"), "a", "p/A.class");
        List<String> log = new ArrayList<>();
        ModuleCheck.run(game, TOML, log::add);   // no logs folder yet
        rolledLog(game, "2026-10-11-1.log.gz", "[main/INFO]: a clean start");
        ModuleCheck.run(game, TOML, log::add);
        check(Files.exists(game.resolve("mods").resolve("a.jar")), "nothing turned off");
        check(!Files.exists(game.resolve("modkeel").resolve(Note.FILE)), "no note");
        eq(List.of(), log, "nothing logged");
    }

    // ---- helpers ------------------------------------------------------------------------

    static Path tmp() throws IOException {
        return Files.createTempDirectory("modkeel-early");
    }

    /** A NeoForge mod with these (empty) classes; each made one second after the last. */
    static Path mod(Path file, String modId, String... classes) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(TOML, "modLoader = \"javafml\"\n[[mods]]\nmodId = \"" + modId + "\"\n");
        for (String c : classes) {
            entries.put(c, "x");
        }
        Path p = jar(file, entries);
        Files.setLastModifiedTime(p, FileTime.fromMillis(clock += 1000));
        return p;
    }

    /** A mod that bundles one jar and lists it in its jar-in-jar metadata. */
    static Path bundling(Path file, String modId, String inner, byte[] body) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(TOML, "modId = \"" + modId + "\"\n");
        entries.put("META-INF/jarjar/metadata.json", "{\"jars\": [{\"identifier\": {\"group\": \"g\","
                + " \"artifact\": \"a\"}, \"path\": \"META-INF/jarjar/" + inner + "\"}]}");
        entries.put("META-INF/jarjar/" + inner, new String(body, StandardCharsets.ISO_8859_1));
        Path p = jar(file, entries);
        Files.setLastModifiedTime(p, FileTime.fromMillis(clock += 1000));
        return p;
    }

    static String manifest(String type, String moduleName) {
        return "Manifest-Version: 1.0\n" + (type == null ? "" : "FMLModType: " + type + "\n")
               + (moduleName == null ? "" : "Automatic-Module-Name: " + moduleName + "\n");
    }

    /** Entries as ISO-8859-1 text, so a nested jar's bytes pass through unchanged. */
    static Path jar(Path file, Map<String, String> entries) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, jarBytes(entries));
        return file;
    }

    static byte[] jarBytes(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.ISO_8859_1));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static void lastGood(Path game, String... files) throws IOException {
        Files.createDirectories(game.resolve("modkeel"));
        StringBuilder sb = new StringBuilder();
        for (String f : files) {
            sb.append("sha\t").append(f).append('\n');
        }
        Files.writeString(game.resolve("modkeel").resolve("lastgood.txt"), sb);
    }

    static void rolledLog(Path game, String name, String text) throws IOException {
        Path file = game.resolve("logs").resolve(name);
        Files.createDirectories(file.getParent());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        Files.write(file, bytes.toByteArray());
    }

    static List<String> names(List<Path> paths) {
        return paths.stream().map(p -> p.getFileName().toString()).toList();
    }

    static void eq(Object want, Object got, String what) {
        check(want == null ? got == null : want.equals(got), what + ": want " + want + ", got " + got);
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.err.println("FAIL " + what);
        }
    }
}
