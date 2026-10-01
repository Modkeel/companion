package modkeel.companion.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Per-mod play: how much of each mod's content was used, so a fix that "held" through idle
 * hours counts for little. One minute's sample, or a whole session once added up. Mods are
 * namespaces of the game's own stats and registries; "minecraft" is the baseline.
 */
public final class Activity {
    /** Counts kept per mod: stat deltas, advancements, peak machines, ticks spent in its places. */
    public static final String[] KINDS = {"mined", "crafted", "used", "killed", "adv", "machines", "here"};
    public static final int MINED = 0;
    public static final int CRAFTED = 1;
    public static final int USED = 2;
    public static final int KILLED = 3;
    public static final int ADV = 4;
    /** Block entities near a player: the peak, not a sum. */
    public static final int MACHINES = 5;
    /** Ticks a player spent in the mod's dimension or biome; sent as minutes. */
    public static final int HERE = 6;
    /** Kinds that only grow (the game's totals): a sample is the difference of two totals. */
    static final int CUMULATIVE = ADV + 1;
    static final int MAX_MODS = 100;
    private static final Pattern MOD_ID = Pattern.compile("[a-z0-9_.\\-]{1,64}");

    public final Map<String, long[]> mods = new TreeMap<>();
    /** Ticks with a player moving, looking around or using anything. */
    public long activeTicks;

    public long[] of(String mod) {
        return mods.computeIfAbsent(mod, k -> new long[KINDS.length]);
    }

    public void count(String mod, int kind, long n) {
        if (n > 0) {
            of(mod)[kind] += n;
        }
    }

    /** Machines are a level, not an amount: keep the highest. */
    public void peak(String mod, int kind, long n) {
        if (n > 0) {
            long[] c = of(mod);
            c[kind] = Math.max(c[kind], n);
        }
    }

    /**
     * What one player did between two readings of their totals ({@code before} null on first
     * sight: no difference yet). True when anything grew.
     */
    public boolean addGrowth(Map<String, long[]> before, Map<String, long[]> now) {
        if (before == null) {
            return false;
        }
        boolean grew = false;
        for (Map.Entry<String, long[]> e : now.entrySet()) {
            long[] old = before.get(e.getKey());
            for (int k = 0; k < CUMULATIVE; k++) {
                long d = e.getValue()[k] - (old == null ? 0 : old[k]);
                if (d > 0) {
                    count(e.getKey(), k, d);
                    grew = true;
                }
            }
        }
        return grew;
    }

    /** Adds a later sample: counts sum, machines keep the peak. */
    public void add(Activity other) {
        activeTicks += other.activeTicks;
        for (Map.Entry<String, long[]> e : other.mods.entrySet()) {
            long[] mine = of(e.getKey());
            for (int k = 0; k < KINDS.length; k++) {
                mine[k] = k == MACHINES ? Math.max(mine[k], e.getValue()[k]) : mine[k] + e.getValue()[k];
            }
        }
    }

    /** The same play, only for {@code ids} (active ticks kept). */
    public Activity only(Collection<String> ids) {
        Activity out = new Activity();
        out.activeTicks = activeTicks;
        for (String id : ids) {
            long[] c = mods.get(id);
            if (c != null) {
                out.mods.put(id, c.clone());
            }
        }
        return out;
    }

    /** One field without tabs: "mod=n,n,...;mod=..." ("-" for none). */
    String field() {
        return mods.isEmpty() ? "-" : String.join(";", lines()).replace('\t', '=');
    }

    static Activity parseField(String field) {
        Activity out = new Activity();
        if (!field.equals("-")) {
            for (String line : field.split(";")) {
                out.parseLine(line.replace('=', '\t'));
            }
        }
        return out;
    }

    /** Journal lines: "mod\tn,n,...". */
    List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : mods.entrySet()) {
            StringBuilder sb = new StringBuilder(e.getKey()).append('\t');
            for (int k = 0; k < KINDS.length; k++) {
                sb.append(k == 0 ? "" : ",").append(e.getValue()[k]);
            }
            out.add(sb.toString());
        }
        return out;
    }

    void parseLine(String line) {
        String[] p = line.split("\t", 2);
        String[] n = p[1].split(",");
        long[] c = of(p[0]);
        for (int k = 0; k < Math.min(n.length, KINDS.length); k++) {
            c[k] = Long.parseLong(n[k]);
        }
    }

    static long minutes(long ticks) {
        return (ticks + 1199) / 1200;
    }

    /**
     * {"create":{"mined":1,...},...}: the mods with the most activity first, at most
     * {@link #MAX_MODS}; ids only, nothing else about the world.
     */
    String json() {
        List<Map.Entry<String, long[]>> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : mods.entrySet()) {
            if (MOD_ID.matcher(e.getKey()).matches() && total(e.getValue()) > 0) {
                rows.add(e);
            }
        }
        rows.sort((a, b) -> Long.compare(total(b.getValue()), total(a.getValue())));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : rows.subList(0, Math.min(rows.size(), MAX_MODS))) {
            List<String> fields = new ArrayList<>();
            for (int k = 0; k < KINDS.length; k++) {
                long n = k == HERE ? minutes(e.getValue()[k]) : e.getValue()[k];
                fields.add("\"" + KINDS[k] + "\":" + Math.min(n, Integer.MAX_VALUE));
            }
            out.add(Reports.q(e.getKey()) + ":{" + String.join(",", fields) + "}");
        }
        return "{" + String.join(",", out) + "}";
    }

    private static long total(long[] c) {
        long t = 0;
        for (int k = 0; k < KINDS.length; k++) {
            t += k == HERE ? minutes(c[k]) : c[k];
        }
        return t;
    }
}
