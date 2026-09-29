package modkeel.companion.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything a loader adapter calls: world backups when the mod set changes, the last good
 * set, crash diagnosis on the next launch, and reversible fixes on the mods folder.
 * No Minecraft or loader classes here.
 */
public final class Guardian {
    public static final int KEEP_BACKUPS = Integer.getInteger("modkeel.backups.keep", 3);
    public static final long MAX_BACKUP_BYTES =
            Long.getLong("modkeel.backups.max_mb", 8192L) * 1024 * 1024;
    /** Server ticks of play without a crash before the mod set counts as good (10 min). */
    public static final int GOOD_TICKS = Integer.getInteger("modkeel.test.good_ticks", 12000);

    public final Path gameDir;
    public final Path modsDir;
    public final Path home;
    public final State state;
    public final Outcomes outcomes;
    public Rules rules = Rules.load();
    /** The running Minecraft version, set by the loader adapter before {@link #startup()}. */
    public String mcVersion = "";
    private ModSet current;
    private Owners owners;

    /** The crash found at startup, if any, and its report file. */
    public Diagnosis crash;
    public Path crashFile;

    public Guardian(Path gameDir) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
        this.modsDir = this.gameDir.resolve("mods");
        this.home = this.gameDir.resolve("modkeel");
        this.state = State.load(this.gameDir);
        this.outcomes = Outcomes.load(this.home);
    }

    /** At launch, before any screen: what the last fix did, which fixes were undone, and the crash. */
    public Diagnosis startup() {
        Log.info("rules bundle: " + rules.size + " lab facts; Minecraft " + mcVersion);
        takeLastResult();
        if (outcomes.watching()) {
            outcomes.checkUndone(modsDir);
            outcomes.bindSet(current().fingerprint());
        }
        return checkCrashes();
    }

    public synchronized ModSet current() {
        if (current == null) {
            try {
                current = ModSet.scan(modsDir, home.resolve("hashes.properties"));
            } catch (IOException e) {
                Log.warn("cannot scan " + modsDir, e);
                current = new ModSet(new ArrayList<>());
            }
        }
        return current;
    }

    public synchronized Owners owners() {
        if (owners == null) {
            owners = Owners.scan(modsDir);
        }
        return owners;
    }

    // ---- world backups -------------------------------------------------------------------

    /**
     * Before a world loads: back it up if its mods changed since it was last played. Returns
     * the backup, or null when none was needed or possible.
     */
    public Path onWorldStarting(Path worldDir) {
        worldDir = worldDir.toAbsolutePath().normalize();
        String world = worldDir.getFileName().toString();
        Path stored = worldDir.resolve("modkeel").resolve("modset.txt");
        ModSet now = current();
        Path backup = null;
        try {
            boolean changed;
            String before = "none";
            if (Files.exists(stored)) {
                ModSet old = ModSet.read(stored);
                changed = !old.fingerprint().equals(now.fingerprint());
                before = old.jars.size() + " mods";
            } else {
                // first time with Modkeel: back up any world that already existed
                changed = Files.exists(worldDir.resolve("level.dat"));
            }
            if (changed) {
                long size = Backups.size(worldDir);
                if (size > MAX_BACKUP_BYTES) {
                    Log.info("world " + world + " is " + (size >> 20) + " MB, over the backup limit;"
                             + " not backed up (raise -Dmodkeel.backups.max_mb)");
                } else {
                    long t0 = System.currentTimeMillis();
                    backup = Backups.dir(gameDir, world).resolve(Backups.stamp() + ".zip");
                    Backups.zip(worldDir, backup);
                    Backups.prune(gameDir, world, KEEP_BACKUPS);
                    Log.info("mods changed (" + before + " -> " + now.jars.size() + " mods): backed up "
                             + world + " in " + (System.currentTimeMillis() - t0) + " ms to " + backup);
                }
            }
            now.write(stored);
        } catch (IOException e) {
            Log.warn("backup of " + world + " failed", e);
        }
        return backup;
    }

    // ---- last good set -------------------------------------------------------------------

    private Path lastGoodFile() {
        return home.resolve("lastgood.txt");
    }

    public ModSet lastGood() {
        try {
            return Files.exists(lastGoodFile()) ? ModSet.read(lastGoodFile()) : null;
        } catch (IOException e) {
            Log.warn("cannot read " + lastGoodFile(), e);
            return null;
        }
    }

    /** Record the current set as good and keep a copy of each jar so a revert can bring it back. */
    public void markGood() {
        ModSet now = current();
        ModSet old = lastGood();
        if (old != null && old.fingerprint().equals(now.fingerprint())) {
            return;
        }
        Path jars = home.resolve("jars");
        try {
            Files.createDirectories(jars);
            Set<String> keep = new HashSet<>();
            for (ModSet.Jar j : now.jars) {
                keep.add(j.sha1 + ".jar");
                Path cached = jars.resolve(j.sha1 + ".jar");
                if (Files.exists(cached)) {
                    continue;
                }
                try {
                    Files.createLink(cached, modsDir.resolve(j.file));
                } catch (IOException | UnsupportedOperationException e) {
                    Files.copy(modsDir.resolve(j.file), cached);
                }
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(jars, "*.jar")) {
                for (Path p : ds) {
                    if (!keep.contains(p.getFileName().toString())) {
                        Files.deleteIfExists(p);
                    }
                }
            }
            now.write(lastGoodFile());
            Log.info("marked " + now.jars.size() + " mods as the last good set");
        } catch (IOException e) {
            Log.warn("cannot mark the last good set", e);
        }
    }

    /** Jars added or changed since the last good set (empty when there is none). */
    public List<ModSet.Jar> changedSinceGood() {
        ModSet good = lastGood();
        return good == null ? new ArrayList<>() : current().notIn(good);
    }

    public boolean canRevert() {
        ModSet good = lastGood();
        return good != null && !good.fingerprint().equals(current().fingerprint());
    }

    // ---- crash diagnosis -----------------------------------------------------------------

    /**
     * Look for a crash report written since the last launch. On the very first launch only a
     * report from the last 24 hours counts.
     */
    public Diagnosis checkCrashes() {
        long now = System.currentTimeMillis();
        long since = state.getLong("lastCrashCheck", now - 24L * 3600 * 1000);
        state.set("lastCrashCheck", now);
        state.save();
        Path dir = gameDir.resolve("crash-reports");
        Path newest = null;
        long newestTime = since;
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "crash-*.txt")) {
                for (Path p : ds) {
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t > newestTime) {
                        newest = p;
                        newestTime = t;
                    }
                }
            } catch (IOException e) {
                Log.warn("cannot list " + dir, e);
            }
        }
        if (newest == null) {
            return null;
        }
        try {
            Diagnosis d = Diagnosis.of(CrashReport.read(newest), owners(), hints());
            // a suspect the player already removed is no longer a fix to offer
            d.suspects.removeIf(s -> s.file != null && !Files.exists(modsDir.resolve(s.file)));
            crash = d;
            crashFile = newest;
            if (outcomes.watching()) {
                outcomes.onCrash(d.signature, current().fingerprint());
            }
            Log.info("crash " + newest.getFileName() + ": " + d.kind + ", " + d.error
                     + (d.top() == null ? ", no suspect" : ", suspect " + d.top().id + " ("
                        + d.confidence + ", " + String.join("; ", d.top().reasons) + ")"));
            return d;
        } catch (IOException e) {
            Log.warn("cannot read " + newest, e);
            return null;
        }
    }

    // ---- lab rules -----------------------------------------------------------------------

    /** One installed mod as the lab knows it. */
    public static final class LabMod {
        public final String id;
        public final String name;
        public final String file;
        /** What the lab saw this exact file do on this Minecraft version, or null. */
        public final Rules.Rule rule;
        /** The Minecraft version its file name points at, when not the running one. */
        public final String otherVersion;

        LabMod(String id, String name, String file, Rules.Rule rule, String otherVersion) {
            this.id = id;
            this.name = name;
            this.file = file;
            this.rule = rule;
            this.otherVersion = otherVersion;
        }
    }

    /** Every installed top-level mod, with what the rules bundle says about it. */
    public List<LabMod> labMods() {
        List<LabMod> out = new ArrayList<>();
        java.util.Map<String, String> sha1s = new java.util.HashMap<>();
        for (ModSet.Jar j : current().jars) {
            sha1s.put(j.file, j.sha1);
        }
        for (JarInfo info : owners().all) {
            if (info.bundledIn != null || !info.declared) {
                continue;
            }
            String sha1 = sha1s.get(info.file);
            out.add(new LabMod(info.id, info.displayName(), info.file,
                    sha1 == null ? null : rules.jar(sha1, mcVersion),
                    Rules.otherVersionInName(info.file, mcVersion)));
        }
        return out;
    }

    /** Pairs of installed mods the lab saw clash on this version. */
    public List<Rules.Rule> labClashes() {
        List<Rules.Rule> out = new ArrayList<>();
        List<LabMod> mods = labMods();
        for (int i = 0; i < mods.size(); i++) {
            for (int k = i + 1; k < mods.size(); k++) {
                Rules.Rule r = rules.pair(mods.get(i).id, mods.get(k).id, mcVersion);
                if (r != null && r.status == Rules.Status.CLASH) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    private List<Diagnosis.Hint> hints() {
        List<Diagnosis.Hint> out = new ArrayList<>();
        for (LabMod m : labMods()) {
            if (m.rule != null && m.rule.status == Rules.Status.CRASHES) {
                out.add(new Diagnosis.Hint(m.id, 60, "in the Modkeel lab this exact file crashed on Minecraft "
                        + mcVersion, false));
            }
            if (m.otherVersion != null) {
                out.add(new Diagnosis.Hint(m.id, 10, "its file name says it is for Minecraft "
                        + m.otherVersion + ", not " + mcVersion, true));
            }
        }
        for (Rules.Rule r : labClashes()) {
            out.add(new Diagnosis.Hint(r.a, 20, "the Modkeel lab saw it clash with " + r.b, true));
            out.add(new Diagnosis.Hint(r.b, 20, "the Modkeel lab saw it clash with " + r.a, true));
        }
        return out;
    }

    // ---- fixes ---------------------------------------------------------------------------

    /** Installed mods that require {@code id}: they stop loading when it is disabled. */
    public List<String> dependents(String id) {
        List<String> out = new ArrayList<>();
        for (JarInfo info : owners().all) {
            if (info.bundledIn == null && info.depends.contains(id)) {
                out.add(info.displayName());
            }
        }
        return out;
    }

    public Plan disablePlan(Diagnosis.Suspect suspect) {
        Plan p = new Plan();
        p.title = "Disabled " + suspect.name;
        Path jar = modsDir.resolve(suspect.file);
        p.move(jar, Plan.disabledName(jar));
        return p;
    }

    /** Back to the last good set: disable what is new, bring back what went missing. */
    public Plan revertPlan() {
        Plan p = new Plan();
        p.title = "Reverted to the last good mod set";
        ModSet good = lastGood();
        if (good == null) {
            return p;
        }
        ModSet now = current();
        for (ModSet.Jar j : now.notIn(good)) {
            Path jar = modsDir.resolve(j.file);
            p.move(jar, Plan.disabledName(jar));
        }
        for (ModSet.Jar j : good.notIn(now)) {
            Path cached = home.resolve("jars").resolve(j.sha1 + ".jar");
            if (Files.exists(cached)) {
                p.copy(cached, modsDir.resolve(j.file));
            } else {
                Log.info("cannot bring back " + j.file + ": no saved copy");
            }
        }
        return p;
    }

    /** Jars Modkeel disabled that are still disabled. */
    public List<String> disabledByUs() {
        List<String> out = new ArrayList<>();
        for (String f : state.getList("disabled")) {
            if (Files.exists(modsDir.resolve(f))) {
                out.add(f);
            }
        }
        return out;
    }

    public Plan enablePlan(String disabledFile) {
        Plan p = new Plan();
        String jar = disabledFile.replaceAll("(\\.\\d+)?\\.disabled$", "");
        p.title = "Re-enabled " + jar;
        Path to = modsDir.resolve(jar);
        if (Files.exists(to)) {
            to = modsDir.resolve(jar.replaceAll("(?i)\\.jar$", "") + "-reenabled.jar");
        }
        p.move(modsDir.resolve(disabledFile), to);
        return p;
    }

    /** Apply a fix picked on the crash screen and watch whether it holds. */
    public void applyCrashFix(Plan plan, Path selfJar) {
        if (crash != null) {
            List<String> disabled = new ArrayList<>();
            for (Plan.Op op : plan.ops) {
                if (op.to.getFileName().toString().endsWith(".disabled")) {
                    disabled.add(op.to.getFileName().toString());
                }
            }
            outcomes.add(crash.signature, plan.title, disabled);
        }
        apply(plan, selfJar);
    }

    /**
     * Run a plan. Each op is tried now; the ones the running game blocks (locked jars on
     * Windows) go to a helper process that finishes them after the game exits. The caller
     * then closes the game. {@code selfJar} is this mod's jar, which holds {@link Apply}.
     */
    public void apply(Plan plan, Path selfJar) {
        List<String> disabled = state.getList("disabled");
        for (Plan.Op op : plan.ops) {
            String name = op.to.getFileName().toString();
            if (op.to.getParent().equals(modsDir) && name.endsWith(".disabled") && !disabled.contains(name)) {
                disabled.add(name);
            }
            disabled.remove(op.from.getFileName().toString());
        }
        state.setList("disabled", disabled);
        state.set("lastAction", plan.title);
        state.save();

        Plan later = new Plan();
        later.title = plan.title;
        List<String> result = new ArrayList<>();
        result.add("#" + plan.title);
        for (Plan.Op op : plan.ops) {
            try {
                if (op.kind.equals("move")) {
                    Files.move(op.from, op.to);
                } else {
                    Files.copy(op.from, op.to, StandardCopyOption.REPLACE_EXISTING);
                }
                result.add("ok " + op);
            } catch (IOException e) {
                later.ops.add(op);
            }
        }
        Path resultFile = home.resolve("result.txt");
        try {
            Files.createDirectories(home);
            Files.write(resultFile, result, StandardCharsets.UTF_8);
            if (!later.isEmpty()) {
                Path planFile = home.resolve("plan.txt");
                later.write(planFile);
                Path helper = home.resolve("helper.jar");
                Files.copy(selfJar, helper, StandardCopyOption.REPLACE_EXISTING);
                String java = ProcessHandle.current().info().command()
                        .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
                new ProcessBuilder(java, "-cp", helper.toString(), Apply.class.getName(),
                                   Long.toString(ProcessHandle.current().pid()), planFile.toString(),
                                   home.resolve("result-helper.txt").toString())
                        .redirectErrorStream(true)
                        .redirectOutput(home.resolve("helper.log").toFile())
                        .start();
                Log.info(later.ops.size() + " of " + plan.ops.size()
                         + " file changes wait for the game to exit");
            }
        } catch (IOException e) {
            Log.warn("cannot start the helper", e);
        }
        Log.info("applied: " + plan.title);
    }

    /**
     * What the last fix did, read once on the next launch: "ok ..."/"fail ..." lines, or
     * null when there is nothing new.
     */
    public List<String> takeLastResult() {
        List<String> out = new ArrayList<>();
        for (String name : new String[] {"result.txt", "result-helper.txt"}) {
            Path f = home.resolve(name);
            try {
                if (Files.exists(f)) {
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        if (!line.startsWith("#")) {
                            out.add(line);
                        }
                    }
                    Files.delete(f);
                }
            } catch (IOException e) {
                Log.warn("cannot read " + f, e);
            }
        }
        if (Files.exists(home.resolve("plan.txt"))) {
            out.add("fail some changes were not applied: close the game fully and start it again");
        }
        if (out.isEmpty()) {
            return null;
        }
        state.setList("lastResult", out);
        state.save();
        for (String line : out) {
            Log.info("last action: " + line);
        }
        return out;
    }
}
