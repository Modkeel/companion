package modkeel.companion.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The play session as a journal in {@code modkeel/session.txt}: written every minute of play,
 * turned into a summary on the next start. A crash or a killed game loses nothing, and no
 * loader needs a shutdown hook. Summaries leave only when the player picked "always share".
 */
public final class Sessions {
    static final int MAX_SPIKES = 20;
    private static final int MAX_OWNERS = 10;
    private static final Pattern SECTION = Pattern.compile("[a-z_]{1,32}");
    private static final Pattern MOD_ID = Pattern.compile("[a-z0-9_.\\-]{1,64}");

    /** One session as the journal keeps it. */
    static final class Journal {
        long start;
        String set = "-";
        String env = "{}";
        long ticks;
        /** The worst spikes: "at\tms\tjson", worst first. */
        final List<String> spikes = new ArrayList<>();

        long minutes() {
            return (ticks + 1199) / 1200;
        }

        List<String> lines() {
            List<String> out = new ArrayList<>(List.of("start\t" + start, "set\t" + set,
                    "env\t" + env, "ticks\t" + ticks));
            for (String s : spikes) {
                out.add("spike\t" + s);
            }
            return out;
        }

        static Journal parse(List<String> lines) {
            Journal j = new Journal();
            for (String line : lines) {
                String[] p = line.split("\t", 2);
                if (p.length < 2) {
                    continue;
                }
                switch (p[0]) {
                    case "start": j.start = Long.parseLong(p[1]); break;
                    case "set": j.set = p[1]; break;
                    case "env": j.env = p[1]; break;
                    case "ticks": j.ticks = Long.parseLong(p[1]); break;
                    case "spike": j.spikes.add(p[1]); break;
                    default: break;
                }
            }
            return j;
        }
    }

    private final Guardian g;
    private final Path file;
    private Journal now;

    Sessions(Guardian g) {
        this.g = g;
        this.file = g.home.resolve("session.txt");
    }

    /**
     * At startup: the last session ends (crashed when this start found a new crash) and is
     * queued if the player shares sessions; a new one begins.
     */
    synchronized void begin(boolean crashed) {
        Journal last = read();
        if (last != null && last.ticks > 0 && g.shareSessions() && g.reports.enabled()) {
            g.reports.queue("session", summary(last, crashed, true));
        }
        now = new Journal();
        now.start = System.currentTimeMillis();
        now.set = g.current().fingerprint();
        now.env = g.reports.env();
        save();
    }

    /** Play in a world, with the spikes measured so far (newest first, any age). */
    synchronized void played(long ticks, List<Spikes.Spike> recent) {
        if (now == null) {
            return;
        }
        now.ticks += ticks;
        for (Spikes.Spike s : recent) {
            if (s.at < now.start) {
                continue;
            }
            String key = s.at + "\t";
            if (now.spikes.stream().noneMatch(l -> l.startsWith(key))) {
                now.spikes.add(s.at + "\t" + s.millis + "\t" + spike(s));
            }
        }
        now.spikes.sort((a, b) -> Long.compare(ms(b), ms(a)));
        while (now.spikes.size() > MAX_SPIKES) {
            now.spikes.remove(now.spikes.size() - 1);
        }
        save();
    }

    /** "What is sent": this session so far, as it would leave on the next start. */
    public synchronized String preview() {
        Journal j = now != null ? now : new Journal();
        return summary(j, false, false);
    }

    /**
     * The summary as sent. The mod list goes once per set, and only when the set is still the
     * one installed (a summary is built on the next start).
     */
    private String summary(Journal j, boolean crashed, boolean markSent) {
        String set = j.set.equals("-") ? g.current().fingerprint() : j.set;
        List<String> sentSets = g.state.getList("sessionSets");
        String mods = "null";
        if (!sentSets.contains(set) && set.equals(g.current().fingerprint())) {
            mods = g.reports.mods(30 * 1024);
            if (markSent) {
                sentSets.add(set);
                while (sentSets.size() > 50) {
                    sentSets.remove(0);
                }
                g.state.setList("sessionSets", sentSets);
                g.state.save();
            }
        }
        List<String> spikes = new ArrayList<>();
        for (String s : j.spikes) {
            spikes.add(s.split("\t", 3)[2]);
        }
        return "{\"v\":1,\"install\":\"\",\"env\":" + (j.env.equals("{}") ? g.reports.env() : j.env)
                + ",\"set\":" + Reports.q(set) + ",\"mods\":" + mods
                + ",\"minutes\":" + Math.max(1, Math.min(j.minutes(), 7 * 24 * 60))
                + ",\"crashed\":" + crashed + ",\"spikes\":[" + String.join(",", spikes) + "]}";
    }

    /** A spike for the server: where, how long, and who owned it; no stacks, no names. */
    static String spike(Spikes.Spike s) {
        List<String> owners = new ArrayList<>();
        for (Spikes.Share sh : s.shares) {
            String id = sh.id.toLowerCase(Locale.ROOT);
            if (!MOD_ID.matcher(id).matches() || owners.size() == MAX_OWNERS) {
                continue;
            }
            String section = sh.section != null && SECTION.matcher(sh.section).matches()
                    ? Reports.q(sh.section) : "null";
            owners.add("{\"mod\":" + Reports.q(id) + ",\"section\":" + section
                    + ",\"pct\":" + pct(sh.percent) + "}");
        }
        return "{\"where\":\"" + s.where.name().toLowerCase(Locale.ROOT) + "\",\"ms\":"
                + Math.min(s.millis, 600_000) + ",\"gc\":" + pct(s.gcPercent) + ",\"wait\":"
                + pct(s.waitPercent) + ",\"gpu\":" + pct(s.gpuPercent) + ",\"disk\":"
                + pct(s.diskPercent) + ",\"owners\":[" + String.join(",", owners) + "]}";
    }

    private static int pct(int p) {
        return Math.max(0, Math.min(100, p));
    }

    private static long ms(String line) {
        return Long.parseLong(line.split("\t", 3)[1]);
    }

    private Journal read() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Journal.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.info("skipping a bad " + file);
            return null;
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, now.lines(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("cannot write " + file, e);
        }
    }
}
