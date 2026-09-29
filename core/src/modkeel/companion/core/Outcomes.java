package modkeel.companion.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Whether each crash fix worked, judged by play rather than by asking: play time on the fixed
 * set, and whether the same crash came back. Stored in {@code modkeel/outcomes.txt}.
 */
public final class Outcomes {
    /** Server ticks of play for the first label (1 hour); the second is five times that. */
    public static final long HELD_TICKS = Long.getLong("modkeel.test.held_ticks", 72000L);
    private static final int KEEP = 20;

    public enum Status {
        OPEN(false), HELD_1H(false), HELD_5H(true), RECURRED(true), UNDONE(true);

        public final boolean done;

        Status(boolean done) {
            this.done = done;
        }
    }

    public static final class Fix {
        public long created;
        public String signature;
        public String title;
        /** The files the fix left disabled: the fix is undone when none of them remain. */
        public List<String> disabled = new ArrayList<>();
        public long ticks;
        public Status status = Status.OPEN;
        /** Fingerprint of the mod set the fix left, taken on the next start ("-" until then). */
        public String set = "-";

        String line() {
            return created + "\t" + signature + "\t" + status + "\t" + ticks + "\t" + set + "\t"
                    + String.join("|", disabled) + "\t" + title;
        }

        static Fix parse(String line) {
            String[] p = line.split("\t", 7);
            Fix f = new Fix();
            f.created = Long.parseLong(p[0]);
            f.signature = p[1];
            f.status = Status.valueOf(p[2]);
            f.ticks = Long.parseLong(p[3]);
            f.set = p[4];
            if (!p[5].isEmpty()) {
                f.disabled = new ArrayList<>(Arrays.asList(p[5].split("\\|")));
            }
            f.title = p[6];
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

    public synchronized void add(String signature, String title, List<String> disabled) {
        Fix f = new Fix();
        f.created = System.currentTimeMillis();
        f.signature = signature;
        f.title = title;
        f.disabled = new ArrayList<>(disabled);
        fixes.add(0, f);
        while (fixes.size() > KEEP) {
            fixes.remove(fixes.size() - 1);
        }
        save();
    }

    /** Count play on every fix still being watched. */
    public synchronized void addTicks(long n) {
        boolean changed = false;
        for (Fix f : fixes) {
            if (f.status.done) {
                continue;
            }
            f.ticks += n;
            Status next = f.ticks >= 5 * HELD_TICKS ? Status.HELD_5H
                    : f.ticks >= HELD_TICKS ? Status.HELD_1H : Status.OPEN;
            if (next != f.status) {
                f.status = next;
                Log.info("fix \"" + f.title + "\": " + next);
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
                Log.info("fix \"" + f.title + "\": the same crash came back");
            }
        }
        if (changed) {
            save();
        }
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
                Log.info("fix \"" + f.title + "\": undone");
            }
        }
        if (changed) {
            save();
        }
    }
}
