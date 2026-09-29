package modkeel.companion.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Core tests without a test framework: java modkeel.companion.core.CoreTest <fixtures dir>. */
public final class CoreTest {
    private static int passed;
    private static int failed;
    private static Path fixtures;

    public static void main(String[] args) throws Exception {
        fixtures = Paths.get(args[0]);
        run("frame parsing", CoreTest::frames);
        run("fabric mixin failure (fabrishot)", CoreTest::fabrishot);
        run("fabric mixin failure (bobby)", CoreTest::bobby);
        run("neoforge missing class (dynamictrees)", CoreTest::dynamictrees);
        run("neoforge loading issue (athena)", CoreTest::athena);
        run("stack attribution by package and mixin handler", CoreTest::stackAttribution);
        run("libraries and platform ids are never blamed", CoreTest::notBlamed);
        run("mod set fingerprint and hash cache", CoreTest::modSet);
        run("world backup, prune and restore", CoreTest::backups);
        run("guardian: backup only when mods change", CoreTest::guardianBackup);
        run("guardian: last good set and revert", CoreTest::guardianRevert);
        run("guardian: disable, dependents, enable", CoreTest::guardianDisable);
        run("guardian: crash found once", CoreTest::guardianCrash);
        run("plan round trip", CoreTest::planRoundTrip);
        run("crash signature ignores lines, lambdas and mixin hashes", CoreTest::signature);
        run("fix outcomes: held, recurred, undone", CoreTest::outcomes);
        run("rules bundle and version in file names", CoreTest::rules);
        run("lab rules in the diagnosis", CoreTest::labHints);
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    interface Test {
        void run() throws Exception;
    }

    private static void run(String name, Test t) {
        try {
            t.run();
            passed++;
            System.out.println("ok   " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("FAIL " + name + ": " + e);
            e.printStackTrace(System.out);
        }
    }

    private static void check(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError(what);
        }
    }

    private static <T> void eq(T expected, T actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    /** A mod jar with fabric.mod.json (id may be null for a library), classes and files. */
    static Path jar(Path dir, String file, String id, String depends, String... entries) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(file);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            if (id != null) {
                z.putNextEntry(new ZipEntry("fabric.mod.json"));
                String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"name\":\"" + id.toUpperCase()
                        + " Mod\",\"version\":\"1\",\"depends\":{" + (depends == null ? "" : depends) + "}}";
                z.write(json.getBytes(StandardCharsets.UTF_8));
            }
            for (String e : entries) {
                z.putNextEntry(new ZipEntry(e));
                z.write(e.getBytes(StandardCharsets.UTF_8)); // content differs per jar
            }
        }
        return p;
    }

    /** A report already read: move it before the last check so it is not found again. */
    static void past(Path report) throws IOException {
        Files.setLastModifiedTime(report, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
    }

    static Owners owners(Path mods) {
        return Owners.scan(mods);
    }

    static Path tmp() throws IOException {
        return Files.createTempDirectory("mfcore");
    }

    static Diagnosis diagnose(String fixture, Owners owners) throws IOException {
        return Diagnosis.of(CrashReport.read(fixtures.resolve(fixture)), owners);
    }

    // ---- crash parsing and diagnosis -----------------------------------------------------

    static void frames() {
        CrashReport.Frame f = CrashReport.frame("TRANSFORMER/dynamictrees@1.8.0-BETA04/com.dtteam.dynamictrees.DynamicTrees.init");
        eq("dynamictrees", f.module, "module");
        eq("com.dtteam.dynamictrees.DynamicTrees", f.className, "class");
        eq("init", f.method, "method");
        f = CrashReport.frame("knot//net.minecraft.client.Minecraft.<init>");
        eq(null, f.module, "knot module");
        eq("net.minecraft.client.Minecraft", f.className, "knot class");
        f = CrashReport.frame("java.base/java.lang.Thread.run");
        eq(null, f.module, "java.base is not a mod");
        f = CrashReport.frame("knot//net.minecraft.server.level.ServerLevel.handler$zfk000$lithium$onTick");
        eq("lithium", f.mixinHandlerMod(), "handler mod id");
    }

    static void fabrishot() throws Exception {
        Path mods = tmp().resolve("mods");
        jar(mods, "fabrishot-1.0.jar", "fabrishot", null, "me/ramidzkh/fabrishot/Fabrishot.class",
            "mixins.fabrishot.json");
        CrashReport r = CrashReport.read(fixtures.resolve("fabric-mixin-fabrishot.txt"));
        eq("Initializing game", r.description, "description");
        check(r.fromMod.contains("fabrishot"), "from mod: " + r.fromMod);
        check(r.mixinConfigs.contains("mixins.fabrishot.json"), "mixin config: " + r.mixinConfigs);
        eq(3, r.causes.size(), "cause chain");
        Diagnosis d = Diagnosis.of(r, owners(mods));
        eq(Diagnosis.Kind.MIXIN_FAILED, d.kind, "kind");
        eq("fabrishot", d.top().id, "suspect");
        eq("fabrishot-1.0.jar", d.top().file, "file to disable");
        eq("FABRISHOT Mod", d.top().name, "display name");
        eq(Diagnosis.Confidence.HIGH, d.confidence, "confidence");
        eq(1, d.suspects.size(), "suspects");
        check(d.error.startsWith("InjectionError: Critical injection failure"), d.error);
    }

    static void bobby() throws Exception {
        Diagnosis d = diagnose("fabric-mixin-bobby.txt", new Owners());
        eq(Diagnosis.Kind.MIXIN_FAILED, d.kind, "kind");
        eq("bobby", d.top().id, "suspect");
        eq(null, d.top().file, "not installed here, so nothing to disable");
        eq(Diagnosis.Confidence.HIGH, d.confidence, "confidence");
    }

    static void dynamictrees() throws Exception {
        Diagnosis d = diagnose("neoforge-missing-class-dynamictrees.txt", new Owners());
        eq("dynamictrees", d.top().id, "suspect");
        eq(Diagnosis.Confidence.HIGH, d.confidence, "confidence");
        check(d.kind == Diagnosis.Kind.MISSING_CLASS || d.kind == Diagnosis.Kind.MOD_LOADING, "kind " + d.kind);
    }

    static void athena() throws Exception {
        Diagnosis d = diagnose("neoforge-loading-athena.txt", new Owners());
        eq("athena", d.top().id, "suspect");
        eq(Diagnosis.Confidence.HIGH, d.confidence, "confidence");
    }

    static void stackAttribution() throws Exception {
        Path mods = tmp().resolve("mods");
        jar(mods, "boom.jar", "boom", null, "com/example/boom/Boom.class");
        jar(mods, "lithium.jar", "lithium", null, "net/caffeinemc/mods/lithium/Lithium.class");
        String report = String.join("\n",
            "---- Minecraft Crash Report ----",
            "Time: 2026-09-28 10:00:00",
            "Description: Ticking entity",
            "",
            "java.lang.NullPointerException: Cannot invoke \"Object.hashCode()\"",
            "\tat knot//com.example.boom.Boom.explode(Boom.java:10)",
            "\tat knot//net.minecraft.world.entity.Entity.handler$zfk000$lithium$tick(Entity.java:99)",
            "\tat knot//net.minecraft.world.entity.Entity.tick(Entity.java:50)",
            "\tat knot//net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:900)",
            "",
            "A detailed walkthrough of the error, its code path and all known details is as follows:",
            "\tat knot//net.caffeinemc.mods.lithium.Lithium.other(Lithium.java:1)");
        Diagnosis d = Diagnosis.of(CrashReport.parse(report), owners(mods));
        eq(Diagnosis.Kind.GENERIC, d.kind, "kind");
        eq("boom", d.top().id, "first frame wins");
        eq("boom.jar", d.top().file, "file");
        eq(2, d.suspects.size(), "lithium is involved through its handler");
        eq("lithium", d.suspects.get(1).id, "second suspect");
        eq(Diagnosis.Confidence.LOW, d.confidence, "two mods close in score");
        eq("Ticking entity", d.description, "description");
    }

    static void notBlamed() throws Exception {
        Path mods = tmp().resolve("mods");
        jar(mods, "gson-lib.jar", null, null, "com/google/gson/Gson.class");
        jar(mods, "fabric-api.jar", "fabric-api", null, "net/fabricmc/fabric/impl/Foo.class");
        String report = String.join("\n",
            "java.lang.IllegalStateException: bad json",
            "\tat knot//com.google.gson.Gson.fromJson(Gson.java:1)",
            "\tat knot//net.fabricmc.fabric.impl.Foo.bar(Foo.java:1)",
            "\tat knot//net.minecraft.client.Minecraft.run(Minecraft.java:1)");
        Diagnosis d = Diagnosis.of(CrashReport.parse(report), owners(mods));
        eq(0, d.suspects.size(), "no suspect: " + (d.top() == null ? "" : d.top().id));
        eq(Diagnosis.Confidence.NONE, d.confidence, "confidence");
    }

    // ---- guardian ------------------------------------------------------------------------

    static void modSet() throws Exception {
        Path root = tmp();
        Path mods = root.resolve("mods");
        jar(mods, "a.jar", "a", null, "a/A.class");
        jar(mods, "b.jar", "b", null, "b/B.class");
        Files.write(mods.resolve("notes.txt"), new byte[] {1});
        Path cache = root.resolve("modkeel/hashes.properties");
        ModSet s1 = ModSet.scan(mods, cache);
        eq(2, s1.jars.size(), "only jars");
        check(Files.exists(cache), "cache written");
        // same content under another name: same fingerprint
        Files.move(mods.resolve("b.jar"), mods.resolve("b-renamed.jar"));
        ModSet s2 = ModSet.scan(mods, cache);
        eq(s1.fingerprint(), s2.fingerprint(), "rename keeps fingerprint");
        jar(mods, "c.jar", "c", null, "c/C.class");
        ModSet s3 = ModSet.scan(mods, cache);
        check(!s3.fingerprint().equals(s1.fingerprint()), "new jar changes fingerprint");
        eq(1, s3.notIn(s1).size(), "one new jar");
        eq("c.jar", s3.notIn(s1).get(0).file, "the new jar");
        Path f = root.resolve("set.txt");
        s3.write(f);
        eq(s3.fingerprint(), ModSet.read(f).fingerprint(), "round trip");
    }

    static Path world(Path saves, String name) throws IOException {
        Path w = saves.resolve(name);
        Files.createDirectories(w.resolve("region"));
        Files.write(w.resolve("level.dat"), "level".getBytes(StandardCharsets.UTF_8));
        Files.write(w.resolve("region/r.0.0.mca"), new byte[4096]);
        Files.write(w.resolve("session.lock"), new byte[] {1});
        return w;
    }

    static void backups() throws Exception {
        Path game = tmp();
        Path w = world(game.resolve("saves"), "World");
        Path zip = Backups.dir(game, "World").resolve("20260928-100000.zip");
        Backups.zip(w, zip);
        check(Files.size(zip) > 0, "zip written");
        for (String stamp : new String[] {"20260928-100001", "20260928-100002", "20260928-100003"}) {
            Files.copy(zip, zip.resolveSibling(stamp + ".zip"));
        }
        Backups.prune(game, "World", 3);
        List<Path> left = Backups.list(game, "World");
        eq(3, left.size(), "pruned to 3");
        eq("20260928-100003.zip", left.get(0).getFileName().toString(), "newest first");
        eq(List.of("World"), Backups.worlds(game), "worlds");

        Files.write(w.resolve("level.dat"), "changed".getBytes(StandardCharsets.UTF_8));
        Path aside = Backups.restore(game, game.resolve("saves"), "World", left.get(0));
        eq("level", new String(Files.readAllBytes(w.resolve("level.dat")), StandardCharsets.UTF_8), "restored");
        check(!Files.exists(w.resolve("session.lock")), "session.lock not in the backup");
        eq(4096L, Files.size(w.resolve("region/r.0.0.mca")), "region restored");
        eq("changed", new String(Files.readAllBytes(aside.resolve("level.dat")), StandardCharsets.UTF_8),
           "replaced world kept");
    }

    static void guardianBackup() throws Exception {
        Path game = tmp();
        jar(game.resolve("mods"), "a.jar", "a", null, "a/A.class");
        Path w = world(game.resolve("saves"), "World");
        Guardian g = new Guardian(game);
        check(g.onWorldStarting(w) != null, "first launch with Modkeel backs up an existing world");
        check(Files.exists(w.resolve("modkeel/modset.txt")), "world mod set stored");
        g = new Guardian(game);
        eq(null, g.onWorldStarting(w), "same mods: no backup");
        jar(game.resolve("mods"), "b.jar", "b", null, "b/B.class");
        g = new Guardian(game);
        check(g.onWorldStarting(w) != null, "new mod: backup");
        Path fresh = game.resolve("saves/New");
        Files.createDirectories(fresh);
        eq(null, new Guardian(game).onWorldStarting(fresh), "a brand new world needs no backup");
    }

    static void guardianRevert() throws Exception {
        Path game = tmp();
        Path mods = game.resolve("mods");
        jar(mods, "a.jar", "a", null, "a/A.class");
        jar(mods, "old.jar", "old", null, "old/Old.class");
        Guardian g = new Guardian(game);
        check(!g.canRevert(), "no good set yet");
        g.markGood();
        eq(2, g.lastGood().jars.size(), "good set");
        // the player updates "old" and adds "bad"
        Files.delete(mods.resolve("old.jar"));
        jar(mods, "old-2.jar", "old", null, "old/Old.class", "old/New.class");
        jar(mods, "bad.jar", "bad", null, "bad/Bad.class");
        g = new Guardian(game);
        check(g.canRevert(), "can revert");
        eq(2, g.changedSinceGood().size(), "two changed");
        Plan p = g.revertPlan();
        eq(3, p.ops.size(), "two to disable, one to bring back: " + p.ops);
        List<String> result = Apply.run(p);
        for (String line : result.subList(1, result.size())) {
            check(line.startsWith("ok "), line);
        }
        check(Files.exists(mods.resolve("old.jar")), "old version back");
        check(Files.exists(mods.resolve("bad.jar.disabled")), "bad disabled");
        check(Files.exists(mods.resolve("old-2.jar.disabled")), "update disabled");
        g = new Guardian(game);
        check(!g.canRevert(), "back on the good set");
    }

    static void guardianDisable() throws Exception {
        Path game = tmp();
        Path mods = game.resolve("mods");
        jar(mods, "lib.jar", "corelib", null, "corelib/Lib.class");
        jar(mods, "user.jar", "user", "\"corelib\": \"*\", \"minecraft\": \"*\"", "user/User.class");
        Path self = jar(game, "self.jar", "modkeel", null, "x/X.class");
        Guardian g = new Guardian(game);
        eq(List.of("USER Mod"), g.dependents("corelib"), "dependents");
        Diagnosis.Suspect s = Diagnosis.of(CrashReport.parse(
                "java.lang.RuntimeException: x\n\tat knot//corelib.Lib.run(Lib.java:1)\n"), g.owners()).top();
        eq("lib.jar", s.file, "suspect file");
        g.apply(g.disablePlan(s), self);
        check(Files.exists(mods.resolve("lib.jar.disabled")), "disabled in process");
        eq(List.of("lib.jar.disabled"), g.disabledByUs(), "tracked");
        List<String> result = g.takeLastResult();
        check(result != null && result.get(0).startsWith("ok "), "result " + result);
        eq(null, g.takeLastResult(), "result read once");
        g.apply(g.enablePlan("lib.jar.disabled"), self);
        check(Files.exists(mods.resolve("lib.jar")), "enabled again");
        eq(List.of(), g.disabledByUs(), "no longer tracked");
    }

    static void guardianCrash() throws Exception {
        Path game = tmp();
        jar(game.resolve("mods"), "fabrishot.jar", "fabrishot", null, "me/ramidzkh/fabrishot/F.class");
        Path reports = game.resolve("crash-reports");
        Files.createDirectories(reports);
        Path old = reports.resolve("crash-2020-01-01_00.00.00-client.txt");
        Files.copy(fixtures.resolve("fabric-mixin-bobby.txt"), old);
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 48L * 3600 * 1000));
        eq(null, new Guardian(game).checkCrashes(), "a report older than a day is ignored on first run");
        Path fresh = reports.resolve("crash-2026-09-28_10.00.00-client.txt");
        Files.copy(fixtures.resolve("fabric-mixin-fabrishot.txt"), fresh);
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(System.currentTimeMillis() + 1000));
        Guardian g = new Guardian(game);
        Diagnosis d = g.checkCrashes();
        check(d != null && d.top().id.equals("fabrishot"), "fresh crash diagnosed");
        eq(fresh, g.crashFile, "report file");
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        eq(null, new Guardian(game).checkCrashes(), "shown once");
    }

    static void planRoundTrip() throws Exception {
        Path dir = tmp();
        Plan p = new Plan();
        p.title = "Disabled X";
        p.move(dir.resolve("a b.jar"), dir.resolve("a b.jar.disabled")).copy(dir.resolve("c.jar"), dir.resolve("d.jar"));
        Path f = dir.resolve("plan.txt");
        p.write(f);
        Plan q = Plan.read(f);
        eq("Disabled X", q.title, "title");
        eq(2, q.ops.size(), "ops");
        eq("a b.jar", q.ops.get(0).from.getFileName().toString(), "path with space");
        eq("copy", q.ops.get(1).kind, "kind");
        Files.write(dir.resolve("x.jar"), new byte[] {1});
        Files.write(dir.resolve("x.jar.disabled"), new byte[] {1});
        eq("x.jar.2.disabled", Plan.disabledName(dir.resolve("x.jar")).getFileName().toString(), "free name");
    }

    static void signature() {
        String a = "java.lang.IllegalStateException: boom\n"
                + "\tat knot//a.B.lambda$tick$3(B.java:10)\n"
                + "\tat knot//net.minecraft.C.handler$abc123$mymod$tick(C.java:20)\n";
        String b = "java.lang.IllegalStateException: other message\n"
                + "\tat knot//a.B.lambda$tick$7(B.java:99)\n"
                + "\tat knot//net.minecraft.C.handler$fff000$mymod$tick(C.java:5)\n";
        String c = "java.lang.NullPointerException: boom\n"
                + "\tat knot//a.B.lambda$tick$3(B.java:10)\n";
        Owners none = Owners.scan(Paths.get("does-not-exist"));
        String sa = Diagnosis.of(CrashReport.parse(a), none).signature;
        eq(16, sa.length(), "signature length");
        eq(sa, Diagnosis.of(CrashReport.parse(b), none).signature, "same bug, same signature");
        check(!sa.equals(Diagnosis.of(CrashReport.parse(c), none).signature), "other exception differs");
    }

    static void outcomes() throws Exception {
        Path game = tmp();
        Path mods = game.resolve("mods");
        jar(mods, "bad.jar", "bad", null, "bad/Bad.class");
        jar(mods, "other.jar", "other", null, "other/Other.class");
        Path self = jar(game, "self.jar", "modkeel", null, "x/X.class");
        Path reports = game.resolve("crash-reports");
        Files.createDirectories(reports);
        String crash = "java.lang.RuntimeException: x\n\tat knot//bad.Bad.run(Bad.java:1)\n";
        Path r1 = reports.resolve("crash-1.txt");
        Files.write(r1, crash.getBytes(StandardCharsets.UTF_8));

        Guardian g = new Guardian(game);
        Diagnosis d = g.startup();
        check(d != null && d.top().id.equals("bad"), "crash diagnosed");
        g.applyCrashFix(g.disablePlan(d.top()), self);
        Outcomes.Fix f = g.outcomes.fixes.get(0);
        eq(Outcomes.Status.OPEN, f.status, "watched");
        eq(List.of("bad.jar.disabled"), f.disabled, "disabled files");

        g = new Guardian(game);
        g.startup();
        eq(Outcomes.Status.OPEN, g.outcomes.fixes.get(0).status, "still in place after a restart");
        g.outcomes.addTicks(Outcomes.HELD_TICKS);
        eq(Outcomes.Status.HELD_1H, Outcomes.load(game.resolve("modkeel")).fixes.get(0).status, "held 1 h");
        g.outcomes.addTicks(4 * Outcomes.HELD_TICKS);
        eq(Outcomes.Status.HELD_5H, g.outcomes.fixes.get(0).status, "held 5 h");
        g.outcomes.onCrash(d.signature, g.current().fingerprint());
        eq(Outcomes.Status.HELD_5H, g.outcomes.fixes.get(0).status, "a final label stays");

        // a second fix: the same crash from a newly added mod says nothing about it, the same
        // crash on the set it left means it did not hold
        Files.move(mods.resolve("bad.jar.disabled"), mods.resolve("bad.jar"));
        Path r2 = reports.resolve("crash-2.txt");
        Files.write(r2, crash.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(r2, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        g = new Guardian(game);
        d = g.startup();
        past(r2);
        g.applyCrashFix(g.disablePlan(d.top()), self);
        new Guardian(game).startup();
        Path copy = jar(mods, "bad-copy.jar", "bad", null, "bad/Bad.class", "copy.txt");
        Path r3 = reports.resolve("crash-3.txt");
        Files.write(r3, crash.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(r3, FileTime.fromMillis(System.currentTimeMillis() + 4000));
        g = new Guardian(game);
        g.startup();
        past(r3);
        eq(Outcomes.Status.OPEN, g.outcomes.fixes.get(0).status, "a new copy of the mod crashed");
        Files.delete(copy);
        Path r3b = reports.resolve("crash-3b.txt");
        Files.write(r3b, crash.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(r3b, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        g = new Guardian(game);
        g.startup();
        past(r3b);
        eq(Outcomes.Status.RECURRED, g.outcomes.fixes.get(0).status, "same crash on the same set");

        // a third fix the player undoes
        Path r4 = reports.resolve("crash-4.txt");
        Files.write(r4, "java.lang.RuntimeException: y\n\tat knot//other.Other.run(Other.java:1)\n"
                .getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(r4, FileTime.fromMillis(System.currentTimeMillis() + 6000));
        g = new Guardian(game);
        d = g.startup();
        past(r4);
        eq("other", d.top().id, "second mod blamed");
        g.applyCrashFix(g.disablePlan(d.top()), self);
        g.apply(g.enablePlan("other.jar.disabled"), self);
        g = new Guardian(game);
        g.startup();
        eq(Outcomes.Status.UNDONE, g.outcomes.fixes.get(0).status, "undone by the player");
        eq(3, g.outcomes.fixes.size(), "three fixes kept");
    }

    static void rules() throws Exception {
        Rules r = Rules.parse(List.of(
                "# comment",
                "jar\taaa\tsodium\t26.2\tclient\tclient_bench; ran in a benchmark on a client",
                "jar\tbbb\tyungs\t26.2\tcrashes\tofficial_runtime; crashed",
                "pair\tlithium\tvmp\t26.2\tclash\tcompat_check; clash",
                "garbage line"));
        eq(3, r.size, "rules read");
        eq(Rules.Status.CLIENT, r.jar("aaa", "26.2").status, "jar rule");
        eq("client_bench", r.jar("aaa", "26.2").source, "source");
        eq(null, r.jar("aaa", "26.3"), "other version: no claim");
        eq(Rules.Status.CLASH, r.pair("vmp", "lithium", "26.2").status, "pair in any order");

        eq("1.21.1", Rules.otherVersionInName("lithium-fabric-0.15.4+mc1.21.1.jar", "26.2"), "mc1.21.1");
        eq("26.1.2", Rules.otherVersionInName("YungsApi-26.1.2-Fabric-5.1.9.jar", "26.2"), "26.1.2");
        eq(null, Rules.otherVersionInName("ImmediatelyFast-Fabric-1.16.5+26.2.jar", "26.2"), "names 26.2");
        eq(null, Rules.otherVersionInName("macos-input-fixes-1.13.jar", "26.2"), "mod version only");
        eq(null, Rules.otherVersionInName("architectury-fabric-21.1.11.jar", "26.2"), "not a mc version");
        eq(null, Rules.otherVersionInName("sodium-fabric-0.9.2+mc26.2.jar", "26.2.1"), "prefix of the running one");
    }

    static void labHints() throws Exception {
        Path game = tmp();
        Path mods = game.resolve("mods");
        Path bad = jar(mods, "yungs-26.1.2.jar", "yungs", null, "com/yungs/Y.class");
        jar(mods, "other.jar", "other", null, "other/O.class");
        Path reports = game.resolve("crash-reports");
        Files.createDirectories(reports);
        // the report only names a generic frame of another mod
        Files.write(reports.resolve("crash-1.txt"), ("java.lang.RuntimeException: x\n"
                + "\tat knot//other.O.run(O.java:1)\n\tat knot//net.minecraft.W.tick(W.java:1)\n")
                .getBytes(StandardCharsets.UTF_8));
        Guardian g = new Guardian(game);
        g.mcVersion = "26.2";
        g.rules = Rules.parse(List.of("jar\t" + ModSet.sha1(bad) + "\tyungs\t26.2\tcrashes\tofficial_runtime; crashed"));
        Diagnosis d = g.startup();
        eq("yungs", d.top().id, "the lab crash outweighs one generic frame");
        check(d.top().reasons.get(0).contains("Modkeel lab"), "reason " + d.top().reasons);
        check(d.top().reasons.contains("its file name says it is for Minecraft 26.1.2, not 26.2"),
                "file name hint on a suspect " + d.top().reasons);
        eq(2, g.labMods().size(), "lab view of installed mods");
    }
}
