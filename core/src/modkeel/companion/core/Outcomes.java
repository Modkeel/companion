package modkeel.companion.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Whether each crash fix worked, judged by play rather than by asking: active play on the fixed
 * set, and whether the same crash came back. Stored in {@code modkeel/outcomes.txt}.
 */
public final class Outcomes {
    /** Ticks of active play for the first label (1 hour); the second is five times that. */
    public static final long HELD_TICKS = Long.getLong("modkeel.test.held_ticks", 72000L);
    private static final int KEEP = 20;

    public enum Status {
        OPEN(false, null), HELD_1H(false, "held-1h"), HELD_5H(true, "held-5h"),
        RECURRED(true, "recurred"), UNDONE(true, "undone");

        public final boolean done;
        /** The status as the reports API names it; null for none to send. */
        public final String wire;

        Status(boolean done, String wire) {
            this.done = done;
            this.wire = wire;
        }
    }

    public static final class Fix {
        public long created;
        public String signature;
        public String title;
        /** The files the fix left disabled: the fix is undone when none of them remain. */
        public List<String> disabled = new ArrayList<>();
        /** All play on the fixed set, idle included. */
        public long ticks;
        /** Play with a player active: what the held labels count (see {@link Activity}). */
        public long active;
        /** Mod ids of the crash's suspects the fix left enabled. */
        public List<String> suspects = new ArrayList<>();
        /** How much those suspects were played since the fix. */
        public Activity played = new Activity();
        /** False once some play could not be measured: then {@link #played} says nothing. */
        public boolean measured = true;
        public Status status = Status.OPEN;
        /** Fingerprint of the mod set the fix left, taken on the next start ("-" until then). */
        public String set = "-";
        /**
         * The shared crash report this fix answers: "-" when not shared, {@code q:<outbox file>}
         * until the server gave it an id, then that id.
         */
        public String report = "-";
        /** The last status sent for {@link #report} ("-" for none). */
        public String sent = "-";

        String line() {
            return created + "\t" + signature + "\t" + status + "\t" + ticks + "\t" + set + "\t"
                    + String.join("|", disabled) + "\t" + report + "\t" + sent + "\t" + active + "\t"
                    + (suspects.isEmpty() ? "-" : String.join("|", suspects)) + "\t" + played.field()
                    + "\t" + (measured ? 1 : 0) + "\t" + title;
        }

        static Fix parse(String line) {
            String[] p = line.split("\t", 13);
            if (p.length < 13) { // written before per-mod activity: all play counted, unmeasured
                p = line.split("\t", 9);
                if (p.length < 9) { // written before reports: no link
                    p = line.split("\t", 7);
                    p = new String[] {p[0], p[1], p[2], p[3], p[4], p[5], "-", "-", p[6]};
                }
                p = new String[] {p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[3], "-", "-", "0", p[8]};
            }
            Fix f = new Fix();
            f.created = Long.parseLong(p[0]);
            f.signature = p[1];
            f.status = Status.valueOf(p[2]);
            f.ticks = Long.parseLong(p[3]);
            f.set = p[4];
            if (!p[5].isEmpty()) {
                f.disabled = new ArrayList<>(Arrays.asList(p[5].split("\\|")));
            }
            f.report = p[6];
            f.sent = p[7];
            f.active = Long.parseLong(p[8]);
            if (!p[9].equals("-")) {
                f.suspects = new ArrayList<>(Arrays.asList(p[9].split("\\|")));
            }
            f.played = Activity.parseField(p[10]);
            f.measured = p[11].equals("1");
            f.title = p[12];
            return f;
        }
    }

    private final Path file;
    public final List<Fix> fixes = new ArrayList<>();

    private Outcomes(Path file) {
        this.file = file;
    }

    public static Outcomes load(Path home) {
        Outcomes o = new Outcomes(home.resolve("outcomes.txt"));
        if (Files.exists(o.file)) {
            try {
                for (String line : Files.readAllLines(o.file, StandardCharsets.UTF_8)) {
                    if (!line.isEmpty()) {
                        try {
                            o.fixes.add(Fix.parse(line));
                        } catch (RuntimeException e) {
                            Log.info("skipping a bad line in " + o.file);
                        }
                    }
                }
            } catch (IOException e) {
                Log.warn("cannot read " + o.file, e);
            }
        }
        return o;
    }

    public synchronized void save() {
        List<String> lines = new ArrayList<>();
        for (Fix f : fixes) {
            lines.add(f.line());
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("cannot write " + file, e);
        }
    }

    /**
     * {@code suspects}: mod ids of the crash's suspects the fix left enabled. {@code report} is
     * the shared crash's outbox file name, or null when not shared.
     */
    public synchronized void add(String signature, String title, List<String> disabled,
                                 List<String> suspects, String report) {
        Fix f = new Fix();
        f.created = System.currentTimeMillis();
        f.signature = signature;
        f.title = title;
        f.disabled = new ArrayList<>(disabled);
        f.suspects = new ArrayList<>(suspects);
        f.report = report == null ? "-" : "q:" + report;
        fixes.add(0, f);
        while (fixes.size() > KEEP) {
            fixes.remove(fixes.size() - 1);
        }
        save();
    }

    /**
     * Count play on every fix still being watched: only active play moves it to held, and the
     * suspects' share of {@code activity} is kept. {@code activity} is null when this play was
     * not measured (measuring failed): then all of it counts as active.
     */
    public synchronized void addPlay(long ticks, Activity activity) {
        boolean changed = false;
        for (Fix f : fixes) {
            if (f.status.done) {
                continue;
            }
            f.ticks += ticks;
            if (activity == null) {
                f.active += ticks;
                f.measured = false;
            } else {
                f.active += Math.min(activity.activeTicks, ticks);
                f.played.add(activity.only(f.suspects));
            }
            Status next = f.active >= 5 * HELD_TICKS ? Status.HELD_5H
                    : f.active >= HELD_TICKS ? Status.HELD_1H : Status.OPEN;
            if (next != f.status) {
                f.status = next;
                Log.info("fix \"" + Msg.plain(f.title) + "\": " + next);
            }
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    public synchronized boolean watching() {
        for (Fix f : fixes) {
            if (!f.status.done) {
                return true;
            }
        }
        return false;
    }

    /** On the first start after a fix: remember the mod set it left. */
    public synchronized void bindSet(String fingerprint) {
        boolean changed = false;
        for (Fix f : fixes) {
            if (!f.status.done && f.set.equals("-")) {
                f.set = fingerprint;
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    /**
     * A new crash with this signature, on the same mod set the fix left, means the fix did not
     * hold. On a different set it says nothing about the fix: the player added or changed mods.
     */
    public synchronized void onCrash(String signature, String fingerprint) {
        boolean changed = false;
        for (Fix f : fixes) {
            if (!f.status.done && f.signature.equals(signature) && f.set.equals(fingerprint)) {
                f.status = Status.RECURRED;
                changed = true;
                Log.info("fix \"" + Msg.plain(f.title) + "\": the same crash came back");
            }
        }
        if (changed) {
            save();
        }
    }

    /**
     * Queue each new status of a shared fix, once its crash report has an id from the server.
     * Returns whether anything was queued.
     */
    public synchronized boolean queueSteps(Reports reports) {
        boolean changed = false;
        boolean queued = false;
        for (Fix f : fixes) {
            if (f.report.startsWith("q:")) {
                String name = f.report.substring(2);
                String id = reports.reportId(name);
                if (id != null) {
                    f.report = id;
                    changed = true;
                } else if (!Files.exists(reports.outbox.resolve(name))) {
                    f.report = "-"; // dropped unsent: nothing to link to
                    changed = true;
                }
            }
            if (f.report.equals("-") || f.report.startsWith("q:") || f.status.wire == null
                    || f.status.name().equals(f.sent)) {
                continue;
            }
            if (reports.queue("outcome", reports.outcome(f)) != null) {
                f.sent = f.status.name();
                changed = true;
                queued = true;
            }
        }
        if (changed) {
            save();
        }
        return queued;
    }

    /** A fix whose disabled files are all gone again was undone by the player. */
    public synchronized void checkUndone(Path modsDir) {
        boolean changed = false;
        for (Fix f : fixes) {
            if (f.status.done || f.disabled.isEmpty()) {
                continue;
            }
            boolean any = false;
            for (String d : f.disabled) {
                any |= Files.exists(modsDir.resolve(d));
            }
            if (!any) {
                f.status = Status.UNDONE;
                changed = true;
                Log.info("fix \"" + Msg.plain(f.title) + "\": undone");
            }
        }
        if (changed) {
            save();
        }
    }
}
