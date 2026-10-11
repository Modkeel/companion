package modkeel.companion.early;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The early checks' plain-Java part, against made-up jars and logs; run by build.py. */
public final class EarlyTest {
    static int failures;

    public static void main(String[] args) throws Exception {
        findsEachMessage();
        holdersAndPackages();
        blamesWhatChanged();
        runTurnsOffOnceAndLeavesTheNote();
        runWithoutAClashDoesNothing();
        if (failures > 0) {
            System.err.println(failures + " early check(s) failed");
            System.exit(1);
        }
        System.out.println("early checks ok");
    }

    static void findsEachMessage() {
        ModuleCheck.Clash c = ModuleCheck.find("[01:25:07] [main/ERROR]: Error while resolving modules.\n"
                + "java.lang.module.ResolutionException: Modules mfsplita and mfsplitb export package"
                + " modkeel.split to module mixinextras.neoforge\n\tat java.base/...");
        eq("modkeel.split", c.pkg(), "two exporters: package");
        eq(List.of("mfsplita", "mfsplitb"), c.modules(), "two exporters: modules");
        c = ModuleCheck.find("java.lang.module.ResolutionException: Module jei contains package"
                + " com.google.gson, module gson exports package com.google.gson to jei");
        eq("com.google.gson", c.pkg(), "contains and exports: package");
        eq(List.of("jei", "gson"), c.modules(), "contains and exports: modules");
        c = ModuleCheck.find("java.lang.LayerInstantiationException: Package org.joml in both"
                + " module joml and module veil");
        eq("org.joml", c.pkg(), "layer: package");
        eq(null, ModuleCheck.find("[main/INFO]: Loading 3 mods\nModules a and b are fine"), "no clash");
    }

    static void holdersAndPackages() throws IOException {
        Path mods = tmp().resolve("mods");
        jar(mods.resolve("a.jar"), "lib/shared/A.class");
        jar(mods.resolve("deeper.jar"), "lib/shared/inner/B.class");           // a sub-package
        jar(mods.resolve("mr.jar"), "META-INF/versions/17/lib/shared/C.class");  // multi-release
        Path inner = tmp().resolve("inner.jar");
        jar(inner, "lib/shared/D.class");
        jar(mods.resolve("bundles.jar"), Files.readAllBytes(inner), "META-INF/jarjar/inner.jar");
        jar(mods.resolve("modkeel.jar"), "lib/shared/E.class", "META-INF/modkeel/companion.jar");
        jar(mods.resolve("old.jar.disabled"), "lib/shared/F.class");
        eq(List.of("a.jar", "bundles.jar", "mr.jar"),
           names(ModuleCheck.holders(mods, "lib.shared")), "holders");
        check(ModuleCheck.inPackage("lib/shared/A.class", "lib/shared/"), "class in package");
        check(!ModuleCheck.inPackage("lib/shared/A.txt", "lib/shared/"), "resource is no class");
    }

    static void blamesWhatChanged() throws IOException {
        Path mods = tmp().resolve("mods");
        Path a = jar(mods.resolve("a.jar"), "p/A.class");
        Path b = jar(mods.resolve("b.jar"), "p/B.class");
        Files.setLastModifiedTime(a, FileTime.fromMillis(2_000_000));
        Files.setLastModifiedTime(b, FileTime.fromMillis(1_000_000));
        eq(b, ModuleCheck.blame(List.of(a, b), Set.of("a.jar")), "the jar not in the last good set");
        eq(a, ModuleCheck.blame(List.of(a, b), Set.of()), "else the newest");
        eq(a, ModuleCheck.blame(List.of(a, b), Set.of("a.jar", "b.jar")), "both were there: newest");
    }

    static void runTurnsOffOnceAndLeavesTheNote() throws IOException {
        Path game = tmp();
        Path mods = game.resolve("mods");
        jar(mods.resolve("mfsplita.jar"), "modkeel/split/A.class");
        jar(mods.resolve("mfsplitb.jar"), "modkeel/split/A.class");
        Files.createDirectories(game.resolve("modkeel"));
        Files.writeString(game.resolve("modkeel").resolve("lastgood.txt"), "abc\tmfsplita.jar\n");
        gz(game.resolve("logs").resolve("2026-10-11-1.log.gz"), "[main/ERROR]: Error while resolving"
                + " modules.\njava.lang.module.ResolutionException: Modules mfsplita and mfsplitb"
                + " export package modkeel.split to module mixinextras.neoforge\n");
        gz(game.resolve("logs").resolve("2026-10-10-3.log.gz"), "an older start");
        Files.setLastModifiedTime(game.resolve("logs").resolve("2026-10-10-3.log.gz"),
                FileTime.fromMillis(1_000));
        List<String> log = new ArrayList<>();
        ModuleCheck.run(game, log::add);
        check(Files.exists(mods.resolve("mfsplitb.jar.disabled")), "the new jar is off");
        check(Files.exists(mods.resolve("mfsplita.jar")), "the one that started fine stays");
        eq(List.of("mfsplitb.jar\tmfsplitb.jar.disabled\tmodule\tmfsplita.jar\tmodkeel.split"),
           Files.readAllLines(game.resolve("modkeel").resolve(Note.FILE)), "the note");
        check(log.size() == 1 && log.get(0).contains("turned off mfsplitb.jar"), "logged " + log);
        // the player turns it back on: the same old log is not acted on twice
        Files.move(mods.resolve("mfsplitb.jar.disabled"), mods.resolve("mfsplitb.jar"));
        ModuleCheck.run(game, log::add);
        check(Files.exists(mods.resolve("mfsplitb.jar")), "one failure, one action");
    }

    static void runWithoutAClashDoesNothing() throws IOException {
        Path game = tmp();
        jar(game.resolve("mods").resolve("a.jar"), "p/A.class");
        List<String> log = new ArrayList<>();
        ModuleCheck.run(game, log::add);   // no logs folder yet
        gz(game.resolve("logs").resolve("2026-10-11-1.log.gz"), "[main/INFO]: a clean start");
        ModuleCheck.run(game, log::add);
        check(Files.exists(game.resolve("mods").resolve("a.jar")), "nothing turned off");
        check(!Files.exists(game.resolve("modkeel").resolve(Note.FILE)), "no note");
        eq(List.of(), log, "nothing logged");
    }

    // ---- helpers ------------------------------------------------------------------------

    static Path tmp() throws IOException {
        return Files.createTempDirectory("modkeel-early");
    }

    static Path jar(Path file, String... entries) throws IOException {
        return jar(file, new byte[] {1}, entries);
    }

    static Path jar(Path file, byte[] body, String... entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String e : entries) {
                z.putNextEntry(new ZipEntry(e));
                z.write(body);
                z.closeEntry();
            }
        }
        return file;
    }

    static void gz(Path file, String text) throws IOException {
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
