package modkeel.companion.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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
        run("guardian: the world open at a crash", CoreTest::guardianWorldAtCrash);
        run("plan round trip", CoreTest::planRoundTrip);
        run("crash signature ignores lines, lambdas and mixin hashes", CoreTest::signature);
        run("fix outcomes: held, recurred, undone", CoreTest::outcomes);
        run("reports: payload, outbox, install token", CoreTest::reports);
        run("reports: a shared fix sends its steps", CoreTest::reportSteps);
        run("reports: session summaries, only when always shared", CoreTest::sessions);
        run("per-mod activity: growth, peaks, journal", CoreTest::activity);
        run("rules bundle and version in file names", CoreTest::rules);
        run("lab rules in the diagnosis", CoreTest::labHints);
        run("lag spike: owner of a sampled stack", CoreTest::spikeOwner);
        run("lag spike: busy stall named, idle stall ignored", CoreTest::spikeLive);
        run("lag spike: blocked on worker threads, and the crowds after it", CoreTest::spikeWait);
        run("lag spike: part of Minecraft and resource of a stack", CoreTest::spikeSections);
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

    static void guardianWorldAtCrash() throws Exception {
        Path game = tmp();
        Path world = game.resolve("saves").resolve("Castle");
        Files.createDirectories(world);
        Files.write(world.resolve("level.dat"), new byte[] {1});
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(world.resolve("level.dat"), FileTime.fromMillis(now - 4 * 60_000));
        Path reports = game.resolve("crash-reports");
        Files.createDirectories(reports);

        // closed normally, then a crash on the title screen minutes later: no world to talk about
        Guardian g = new Guardian(game);
        g.onWorldStarting(world);
        g.onWorldStopped();
        Path first = reports.resolve("crash-a-client.txt");
        Files.copy(fixtures.resolve("fabric-mixin-fabrishot.txt"), first);
        Files.setLastModifiedTime(first, FileTime.fromMillis(now + 10 * 60_000));
        g = new Guardian(game);
        check(g.checkCrashes() != null, "crash found");
        eq(null, g.worldAtCrash, "no world open");
        Files.delete(first);

        // a crash while the world is open: the crash stops the server right after the report
        g.onWorldStarting(world);
        Path second = reports.resolve("crash-b-client.txt");
        Files.copy(fixtures.resolve("fabric-mixin-fabrishot.txt"), second);
        Files.setLastModifiedTime(second, FileTime.fromMillis(now + 2000));
        g.onWorldStopped();
        g = new Guardian(game);
        check(g.checkCrashes() != null, "second crash found");
        check(g.worldAtCrash != null, "world open at the crash");
        eq("Castle", g.worldAtCrash.name, "world name");
        eq(4L, g.worldAtCrash.unsavedMinutes(), "minutes since the last save");
        check(g.worldAtCrash.backup != null, "the first start with Modkeel backed it up");
        eq(null, new Guardian(game).state.get("playing", null), "cleared once read");
    }

    static void planRoundTrip() throws Exception {
        Path dir = tmp();
        Plan p = new Plan();
        p.title = Msg.of("modkeel.action.disabled", "X");
        p.move(dir.resolve("a b.jar"), dir.resolve("a b.jar.disabled")).copy(dir.resolve("c.jar"), dir.resolve("d.jar"));
        Path f = dir.resolve("plan.txt");
        p.write(f);
        Plan q = Plan.read(f);
        eq(Msg.of("modkeel.action.disabled", "X"), q.title, "title");
        eq("X", Msg.args(q.title)[0], "title argument");
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

    static byte[] zip(String... nameThenContent) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                z.putNextEntry(new ZipEntry(nameThenContent[i]));
                z.write(nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytes.toByteArray();
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    static void reports() throws Exception {
        Path game = tmp();
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        // a jar whose file name holds a user name, with a mod bundled in it
        byte[] inner = zip("fabric.mod.json", "{\"id\":\"inner\",\"version\":\"2.0\"}");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(mods.resolve("Steve's bad-1.0.jar")))) {
            z.putNextEntry(new ZipEntry("fabric.mod.json"));
            z.write("{\"id\":\"bad\",\"version\":\"1.0\"}".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("bad/Bad.class"));
            z.write(1);
            z.putNextEntry(new ZipEntry("META-INF/jars/inner.jar"));
            z.write(inner);
        }
        Files.write(mods.resolve("neo.jar"), zip(
                "META-INF/mods.toml", "[[mods]]\nmodId=\"neo\"\nversion=\"${file.jarVersion}\"\n",
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 3.1\n"));
        Path crashes = game.resolve("crash-reports");
        Files.createDirectories(crashes);
        Files.write(crashes.resolve("crash-1.txt"),
                "java.lang.RuntimeException: x\n\tat knot//bad.Bad.run$0(Bad.java:1)\n".getBytes(StandardCharsets.UTF_8));
        Guardian g = new Guardian(game);
        g.mcVersion = "26.2";
        g.loader = "fabric";
        g.loaderVersion = "0.17.3";
        Diagnosis d = g.startup();
        check(d != null && d.top().id.equals("bad"), "crash diagnosed");
        g.shareCrash(false, null);
        eq(0, g.reports.pending().size(), "not shared, not queued");
        eq(false, g.shareChoice(), "choice kept");

        List<String> got = new ArrayList<>();
        int[] status = {200};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/install", ex -> reply(ex, 200, "{\"install\":\"" + "ab".repeat(16) + "\"}"));
        server.createContext("/v1/crash", ex -> {
            got.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, status[0], "{\"id\":\"" + "r1".repeat(12) + "\",\"seen\":3,\"fixes\":[]}");
        });
        server.start();
        try {
            Reports r = new Reports(g, "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            String json = r.crash(d, r.disableFix(d.top()));
            check(!json.contains("Steve") && !json.contains(".jar"), "no file names: " + json);
            check(json.startsWith("{\"v\":1,\"install\":\"\",\"env\":{\"mc\":\"26.2\",\"loader\":\"fabric\","
                    + "\"loader_version\":\"0.17.3\""), json);
            check(json.contains("{\"id\":\"bad\",\"version\":\"1.0\",\"sha1\":\""), "top-level mod: " + json);
            check(json.contains("{\"id\":\"neo\",\"version\":\"3.1\","), "version from the manifest: " + json);
            check(json.contains("\"id\":\"inner\",\"version\":\"2.0\",\"sha1\":\"" + ModSet.sha1(inner)
                    + "\",\"in\":\"bad\"}"), "bundled mod: " + json);
            check(json.contains("\"signature\":\"" + d.signature + "\",\"kind\":\"GENERIC\","
                    + "\"exception\":\"java.lang.RuntimeException\",\"frames\":[\"bad.Bad.run$\"],"
                    + "\"suspects\":[\"bad\"],\"fix\":{\"title\":\"disable:bad\",\"disabled\":[\"bad\"]}}"), json);
            String readable = r.readable(json);
            check(readable.contains("\n  \"signature\": \"" + d.signature + "\","), readable);
            check(readable.contains("\n    {\"id\": \"neo\", \"version\": \"3.1\", "), "one mod per line: " + readable);
            check(readable.contains("\"fix\": {\"title\": \"disable:bad\", \"disabled\": [\"bad\"]}\n}"), readable);

            r.queue("crash", json);
            check(r.send(), "all sent");
            eq(1, got.size(), "posted");
            check(got.get(0).contains("\"install\":\"" + "ab".repeat(16) + "\""), "token filled in");
            eq(0, r.pending().size(), "outbox empty");
            check(Files.readString(game.resolve("modkeel/install.txt")).equals("ab".repeat(16)), "token kept");
            try (var sent = Files.list(r.sent)) {
                check(sent.anyMatch(f -> {
                    try {
                        return Files.readString(f).contains("\"answer\":{\"id\":\"" + "r1".repeat(12) + "\"");
                    } catch (IOException e) {
                        return false;
                    }
                }), "sent copy with the answer");
            }

            status[0] = 503;
            r.queue("crash", json);
            check(!r.send(), "busy server: retry later");
            eq(1, r.pending().size(), "kept");
            status[0] = 400;
            check(r.send(), "a refused payload is not retried");
            eq(0, r.pending().size(), "dropped");
            Path old = r.queue("crash", json);
            Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 8L * 24 * 3600 * 1000));
            int posted = got.size();
            r.send();
            eq(posted, got.size(), "a week-old report is not sent");
            eq(0, r.pending().size(), "and is dropped");
            eq(false, new Reports(g, "").enabled(), "empty endpoint turns sending off");
        } finally {
            server.stop(0);
        }
    }

    static boolean waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
        for (int i = 0; i < 100 && !cond.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        return cond.getAsBoolean();
    }

    static void reportSteps() throws Exception {
        Path game = tmp();
        Path mods = game.resolve("mods");
        jar(mods, "bad.jar", "bad", null, "bad/Bad.class");
        Path self = jar(game, "self.jar", "modkeel", null, "x/X.class");
        Path crashes = game.resolve("crash-reports");
        Files.createDirectories(crashes);
        Files.write(crashes.resolve("crash-1.txt"),
                "java.lang.RuntimeException: x\n\tat knot//bad.Bad.run(Bad.java:1)\n".getBytes(StandardCharsets.UTF_8));
        String id = "0123456789abcdef01234567";
        List<String> steps = java.util.Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/install", ex -> reply(ex, 200, "{\"install\":\"" + "ab".repeat(16) + "\"}"));
        server.createContext("/v1/crash", ex -> {
            ex.getRequestBody().readAllBytes();
            reply(ex, 200, "{\"id\":\"" + id + "\",\"seen\":1,\"fixes\":[]}");
        });
        server.createContext("/v1/outcome", ex -> {
            steps.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, "{\"ok\":true}");
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            System.setProperty("modkeel.api", url);
            Guardian g = new Guardian(game);
            Diagnosis d = g.startup();
            g.shareCrash(true, g.reports.disableFix(d.top()));
            g.applyCrashFix(g.disablePlan(d.top()), self);
            check(g.outcomes.fixes.get(0).report.startsWith("q:"), "linked to the queued crash");
            check(waitFor(() -> Outcomes.load(g.home).fixes.get(0).report.equals(id)),
                    "linked to the report id once sent");

            g.addPlay(Outcomes.HELD_TICKS, List.of());
            check(waitFor(() -> steps.size() == 1), "held 1 h sent");
            eq("{\"v\":1,\"install\":\"" + "ab".repeat(16) + "\",\"report\":\"" + id
                    + "\",\"status\":\"held-1h\",\"ticks\":" + Outcomes.HELD_TICKS + "}", steps.get(0), "outcome");
            g.addPlay(1, List.of());
            check(waitFor(() -> g.reports.pending().isEmpty()), "outbox empty");
            eq(1, steps.size(), "a step is sent once");

            Guardian again = new Guardian(game);
            again.startup();
            again.addPlay(4 * Outcomes.HELD_TICKS, List.of());
            check(waitFor(() -> steps.size() == 2), "held 5 h sent after a restart");
            check(steps.get(1).contains("\"status\":\"held-5h\""), steps.get(1));

            // a fix never shared sends nothing
            Files.write(game.resolve("modkeel/outcomes.txt"), List.of(
                    "1\tabc\tOPEN\t0\t-\tx.jar.disabled\told line"), StandardCharsets.UTF_8);
            Outcomes old = Outcomes.load(game.resolve("modkeel"));
            eq("-", old.fixes.get(0).report, "a line from before reports reads as not shared");
            eq("old line", old.fixes.get(0).title, "title kept");
        } finally {
            System.clearProperty("modkeel.api");
            server.stop(0);
        }
    }

    static Spikes.Spike spike(Spikes.Where where, long at, long ms, Spikes.Share... shares) {
        return new Spikes.Spike(where, at, ms, 5, List.of(shares), 0, 0, 0, false);
    }

    static void sessions() throws Exception {
        Path game = tmp();
        Path mods = game.resolve("mods");
        jar(mods, "bad.jar", "bad", null, "bad/Bad.class");
        List<String> got = java.util.Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/install", ex -> reply(ex, 200, "{\"install\":\"" + "ab".repeat(16) + "\"}"));
        server.createContext("/v1/session", ex -> {
            got.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, "{\"ok\":true}");
        });
        server.start();
        try {
            System.setProperty("modkeel.api", "http://127.0.0.1:" + server.getAddress().getPort());
            Guardian g = new Guardian(game);
            g.mcVersion = "26.2";
            g.loader = "fabric";
            g.startup();
            long t0 = System.currentTimeMillis() + 1;
            List<Spikes.Spike> spikes = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                spikes.add(spike(Spikes.Where.FRAME, t0 + i, 200 + i));
            }
            spikes.add(spike(Spikes.Where.WORLD, t0 + 100, 1300,
                    new Spikes.Share("Bad", "Bad Mod", null, 70),
                    new Spikes.Share(Spikes.VANILLA, null, "chunks", 20)));
            g.addPlay(1300, spikes);
            g.addPlay(1200, spikes); // the same spikes again count once
            String preview = g.sessions.preview();
            check(preview.contains("\"minutes\":3,\"crashed\":false,\"spikes\":[{\"where\":\"world\",\"ms\":1300,"
                    + "\"gc\":5,\"wait\":0,\"gpu\":0,\"disk\":0,\"owners\":[{\"mod\":\"bad\",\"section\":null,\"pct\":70},"
                    + "{\"mod\":\"minecraft\",\"section\":\"chunks\",\"pct\":20}]}"), "worst spike first: " + preview);
            eq(20, preview.split("\"where\"").length - 1, "the 20 worst spikes");
            check(!preview.contains("Bad Mod"), "no mod names");

            new Guardian(game).startup();
            Thread.sleep(300);
            eq(0, got.size(), "not sent without always share");

            Guardian a = new Guardian(game);
            a.mcVersion = "26.2";
            a.loader = "fabric";
            a.startup();
            check(!a.offerSessions(), "not offered before a shared crash");
            a.setShareSessions(true);
            a.addPlay(100, List.of());
            Guardian b = new Guardian(game);
            b.mcVersion = "26.2";
            b.loader = "fabric";
            b.startup();
            check(waitFor(() -> got.size() == 1), "sent on the next start");
            String first = got.get(0);
            check(first.contains("\"set\":\"" + b.current().fingerprint() + "\",\"mods\":[{\"id\":\"bad\""), first);
            check(first.contains("\"minutes\":1,\"crashed\":false,\"spikes\":[]"), first);
            check(first.contains("\"install\":\"" + "ab".repeat(16) + "\""), "token filled in");

            b.addPlay(100, List.of());
            Path crashes = game.resolve("crash-reports");
            Files.createDirectories(crashes);
            Files.write(crashes.resolve("crash-1.txt"),
                    "java.lang.RuntimeException: x\n\tat knot//bad.Bad.run(Bad.java:1)\n".getBytes(StandardCharsets.UTF_8));
            new Guardian(game).startup();
            check(waitFor(() -> got.size() == 2), "crashed session sent");
            check(got.get(1).contains("\"mods\":null,\"minutes\":1,\"crashed\":true"), "mods once per set: " + got.get(1));
        } finally {
            System.clearProperty("modkeel.api");
            server.stop(0);
        }
    }

    static void activity() throws Exception {
        java.util.Map<String, long[]> before = new java.util.HashMap<>();
        before.put("create", new long[]{10, 0, 5, 0, 1, 0, 0});
        java.util.Map<String, long[]> after = new java.util.HashMap<>();
        after.put("create", new long[]{14, 2, 5, 0, 1, 0, 0});
        after.put("ae2", new long[]{0, 0, 3, 0, 0, 0, 0});
        Activity first = new Activity();
        check(!first.addGrowth(null, after), "first sight is only a baseline");
        eq(0, first.mods.size(), "nothing counted on first sight");
        check(first.addGrowth(before, after), "growth seen");
        eq(4L, first.of("create")[Activity.MINED], "mined grew by 4");
        eq(2L, first.of("create")[Activity.CRAFTED], "crafted grew by 2");
        eq(0L, first.of("create")[Activity.USED], "used did not grow");
        eq(3L, first.of("ae2")[Activity.USED], "a new mod counts from zero");
        first.peak("create", Activity.MACHINES, 30);
        first.count("twilightforest", Activity.HERE, 1200);
        first.activeTicks = 1200;
        Activity second = new Activity();
        second.peak("create", Activity.MACHINES, 12);
        second.count("create", Activity.MINED, 1);
        second.count("twilightforest", Activity.HERE, 100);
        second.activeTicks = 100;
        first.add(second);
        eq(5L, first.of("create")[Activity.MINED], "counts sum");
        eq(30L, first.of("create")[Activity.MACHINES], "machines keep the peak");
        String json = first.json();
        check(json.startsWith("{\"create\":{\"mined\":5,\"crafted\":2,\"used\":0,\"killed\":0,\"adv\":0,"
                + "\"machines\":30,\"here\":0}"), "busiest mod first: " + json);
        check(json.contains("\"twilightforest\":{\"mined\":0,\"crafted\":0,\"used\":0,\"killed\":0,\"adv\":0,"
                + "\"machines\":0,\"here\":2}"), "here in minutes: " + json);
        Activity bad = new Activity();
        bad.count("Bad Name", Activity.MINED, 1);
        eq("{}", bad.json(), "only valid mod ids leave");

        Path game = Files.createTempDirectory("mk-activity");
        Guardian g = new Guardian(game);
        g.mcVersion = "26.2";
        g.loader = "fabric";
        g.startup();
        g.addPlay(1200, List.of(), first);
        g.addPlay(1200, List.of(), second);
        String preview = g.sessions.preview();
        check(preview.contains("\"active_minutes\":2,\"activity\":{\"create\":{\"mined\":6,"), preview);
        Sessions.Journal j = Sessions.Journal.parse(Files.readAllLines(game.resolve("modkeel/session.txt")));
        eq(6L, j.activity.of("create")[Activity.MINED], "the journal keeps the activity");
        eq(1400L, j.activity.activeTicks, "and the active ticks");
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
        eq(Msg.of("modkeel.reason.lab_crashes", "26.2"), d.top().reasons.get(0), "lab reason");
        check(d.top().reasons.contains(Msg.of("modkeel.reason.file_name", "26.1.2", "26.2")),
                "file name hint on a suspect " + d.top().reasons);
        eq(2, g.labMods().size(), "lab view of installed mods");
    }

    // ---- lag spikes ----------------------------------------------------------------------

    static StackTraceElement el(String cls, String method) {
        return new StackTraceElement(cls, method, null, 1);
    }

    static void spikeOwner() throws Exception {
        Path mods = tmp().resolve("mods");
        jar(mods, "create.jar", "create", null, "com/simibubi/create/Create.class");
        jar(mods, "sodium.jar", "sodium", null, "net/caffeinemc/mods/sodium/Sodium.class");
        jar(mods, "fabric-api.jar", "fabric-api", null, "net/fabricmc/fabric/impl/Foo.class");
        Owners o = owners(mods);
        eq("create", Spikes.owner(new StackTraceElement[] {
            el("net.minecraft.world.level.Level", "getBlockState"),
            el("com.simibubi.create.Create", "tickBelts"),
            el("net.caffeinemc.mods.sodium.Sodium", "render")}, o), "innermost mod frame wins");
        eq("sodium", Spikes.owner(new StackTraceElement[] {
            el("net.minecraft.client.renderer.LevelRenderer", "handler$zfk000$sodium$renderLevel"),
            el("net.minecraft.client.Minecraft", "runTick")}, o), "merged mixin handler");
        eq(Spikes.VANILLA, Spikes.owner(new StackTraceElement[] {
            el("net.fabricmc.fabric.impl.Foo", "invoke"),
            el("net.minecraft.client.Minecraft", "runTick")}, o), "platform is not blamed");
    }

    static volatile long sink;

    static void spin(long ms) {
        long end = System.nanoTime() + ms * 1_000_000;
        long x = 0;
        while (System.nanoTime() < end) {
            x += x * 31 + 7;
        }
        sink = x;
    }

    static void beatFor(Spikes.Watch w, long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000;
        while (System.nanoTime() < end) {
            w.beat();
            Thread.sleep(5);
        }
    }

    static void spikeLive() throws Exception {
        Path mods = tmp().resolve("mods");
        // this test's own package stands for a mod's code
        jar(mods, "busy.jar", "busymod", null, "modkeel/companion/core/Busy.class");
        Owners o = owners(mods);
        Spikes spikes = new Spikes(() -> o, Sections.parse(List.of(), List.of()));
        spikes.startMs = 60;
        spikes.reportMs = 150;
        spikes.start();
        Throwable[] error = {null};
        Thread game = new Thread(() -> {
            try {
                Spikes.Watch w = spikes.watch(Spikes.Where.FRAME);
                beatFor(w, 100);
                Thread.sleep(400); // a paused game: the thread waits, nothing to blame
                beatFor(w, 100);
                spin(400);
                beatFor(w, 100);
            } catch (Throwable e) {
                error[0] = e;
            }
        });
        game.start();
        game.join();
        Thread.sleep(50);
        spikes.stop();
        check(error[0] == null, "game thread: " + error[0]);
        List<Spikes.Spike> recent = spikes.recent();
        eq(1, recent.size(), "only the busy stall: " + recent);
        Spikes.Spike s = recent.get(0);
        check(s.millis >= 350 && s.millis < 700, "duration " + s.millis);
        eq("busymod", s.top().id, "culprit");
        check(s.top().percent >= 50, "share " + s.top().percent);
        eq("BUSYMOD Mod", s.top().name, "display name");
        eq(0, s.gpuPercent, "no graphics wait");
    }

    static void spikeWait() throws Exception {
        Owners o = owners(tmp().resolve("mods"));
        // the latch stands for a chunk the game waits on
        Spikes spikes = new Spikes(() -> o, Sections.parse(
                List.of("java.util.concurrent.CountDownLatch\tchunks"), List.of()));
        spikes.startMs = 60;
        spikes.reportMs = 150;
        spikes.start();
        Throwable[] error = {null};
        Thread game = new Thread(() -> {
            try {
                Spikes.Watch w = spikes.watch(Spikes.Where.WORLD);
                beatFor(w, 100);
                Thread.sleep(400); // parked between ticks: nothing to blame
                beatFor(w, 100);
                new java.util.concurrent.CountDownLatch(1).await(400, java.util.concurrent.TimeUnit.MILLISECONDS);
                beatFor(w, 100);
            } catch (Throwable e) {
                error[0] = e;
            }
        });
        game.start();
        game.join();
        Thread.sleep(50);
        spikes.stop();
        check(error[0] == null, "game thread: " + error[0]);
        List<Spikes.Spike> recent = spikes.recent();
        eq(1, recent.size(), "only the blocked stall: " + recent);
        Spikes.Spike s = recent.get(0);
        eq("chunks", s.top().section, "part of the game");
        check(s.waitPercent >= 90, "wait " + s.waitPercent);

        s.count(java.util.Map.of("entity.minecraft.item", 1200, "entity.minecraft.zombie", 400,
                "entity.minecraft.cow", 12, "entity.minecraft.skeleton", 60,
                "entity.minecraft.creeper", 55));
        eq("[1200 entity.minecraft.item, 400 entity.minecraft.zombie, 60 entity.minecraft.skeleton]",
                s.crowds.toString(), "biggest crowds, rare types left out");
    }

    static StackTraceElement[] stack(String... frames) {
        StackTraceElement[] out = new StackTraceElement[frames.length];
        for (int i = 0; i < frames.length; i++) {
            int dot = frames[i].lastIndexOf('.');
            out[i] = el(frames[i].substring(0, dot), frames[i].substring(dot + 1));
        }
        return out;
    }

    static void spikeSections() throws Exception {
        List<String> table = Files.readAllLines(fixtures.resolve("../../../game/resources/modkeel/sections.txt"));
        Sections sec = Sections.parse(table, List.of("net.minecraft.class_1297\tentities"));
        // innermost first, like Thread.getStackTrace
        eq("entities", sec.of(stack(
                "net.minecraft.util.Mth.floor",
                "net.minecraft.world.entity.monster.Zombie.tick",
                "net.minecraft.server.level.ServerLevel.tickNonPassenger",
                "net.minecraft.server.MinecraftServer.tickServer")), "entity tick");
        eq("entities", sec.of(stack(
                "net.minecraft.world.level.block.Block.getShape",
                "net.minecraft.world.entity.Entity.move",
                "net.minecraft.server.level.ServerLevel.tickNonPassenger")),
                "a block looked up by a moving mob is still the mob");
        eq("blocks", sec.of(stack(
                "net.minecraft.world.level.redstone.NeighborUpdater.update",
                "net.minecraft.server.level.ServerChunkCache.tickChunks",
                "net.minecraft.server.MinecraftServer.tickServer")), "random ticks inside chunk ticking");
        eq("worldgen", sec.of(stack(
                "net.minecraft.world.level.block.state.BlockBehaviour.getShape",
                "net.minecraft.world.level.levelgen.feature.TreeFeature.place",
                "net.minecraft.server.level.ChunkMap.lambda$scheduleChunkGeneration$1",
                "net.minecraft.server.level.ServerChunkCache.getChunk")), "generation refines chunk loading");
        eq("rendering", sec.of(stack(
                "net.minecraft.world.entity.Entity.getX",
                "net.minecraft.client.renderer.entity.EntityRenderer.render",
                "net.minecraft.client.renderer.GameRenderer.render",
                "net.minecraft.client.Minecraft.runTick")), "entity drawing is rendering");
        eq("entities", sec.of(stack(
                "net.minecraft.class_1297$class_5529.method_31486",
                "net.minecraft.class_3218.method_18762")), "intermediary names through classnames.tsv");
        eq(null, sec.of(stack("net.minecraft.server.MinecraftServer.tickServer")), "no part");

        eq(Sections.Resource.GPU, Sections.resource(stack(
                "org.lwjgl.system.JNI.invokeV",
                "org.lwjgl.glfw.GLFW.glfwSwapBuffers",
                "com.mojang.blaze3d.platform.Window.updateDisplay")), "swap buffers waits on the GPU");
        eq(Sections.Resource.DISK, Sections.resource(stack(
                "sun.nio.ch.FileDispatcherImpl.write0",
                "sun.nio.ch.FileChannelImpl.write",
                "net.minecraft.world.level.chunk.storage.RegionFile.write")), "file write");
        eq(Sections.Resource.CPU, Sections.resource(stack(
                "java.util.HashMap.get",
                "net.minecraft.world.entity.Entity.tick")), "game code on the processor");
    }
}
