package modkeel.companion.core;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Whether the memory the launcher gave Java suits this pack: too little and a modded game
 * stutters on garbage collection or runs out of memory; nearly all of the computer's and the
 * system itself starts swapping. Only sizes are read, nothing is sent.
 */
public final class Memory {
    /** fine, low (more would help), high (the computer is left too little) or bits32. */
    public final String verdict;
    /** What Java may use (-Xmx), in MB. */
    public final int givenMb;
    /** The computer's memory in GB, as machines are sold (0: unknown). */
    public final int ramGb;
    /** The size to set in the launcher, in GB (0 when fine). */
    public final int adviseGb;
    /** Which launcher started the game: prism, curseforge, modrinth, vanilla or other. */
    public final String launcher;

    /** Below this share of the advice Java is warned about: -Xmx4G reads a little under 4096. */
    private static final double LOW = 0.85;
    /** Above this share of the computer's memory the system is left too little. */
    private static final double HIGH = 0.75;
    /** What the system and the launcher keep, in GB, when advising on a small computer. */
    private static final int SYSTEM_GB = 3;

    Memory(int givenMb, int ramGb, int mods, boolean bits32, String launcher) {
        this.givenMb = givenMb;
        this.ramGb = ramGb;
        this.launcher = launcher;
        int need = needGb(mods);
        int room = ramGb <= 0 ? need : Math.max(2, Math.min(need, ramGb - SYSTEM_GB));
        if (bits32) {
            verdict = "bits32";
            adviseGb = room;
        } else if (ramGb > 0 && givenMb > ramGb * 1024 * HIGH) {
            verdict = "high";
            adviseGb = room;
        } else if (givenMb < room * 1024 * LOW) {
            verdict = "low";
            adviseGb = room;
        } else {
            verdict = "fine";
            adviseGb = 0;
        }
    }

    /** Java running this game, with {@code mods} jars, started from {@code gameDir}. */
    public static Memory of(Path gameDir, int mods) {
        int given = (int) Math.min(Runtime.getRuntime().maxMemory() >> 20, Integer.MAX_VALUE);
        String test = System.getProperty("modkeel.test.memory");  // "givenMb" for the e2e
        if (test != null) {
            given = Integer.parseInt(test.trim());
        }
        return new Memory(given, Gpu.ramGb(), mods, "32".equals(System.getProperty("sun.arch.data.model")),
                launcher(gameDir));
    }

    /** A modded game's working set grows with its mods: what keeps garbage collection short. */
    static int needGb(int mods) {
        if (mods <= 40) {
            return 3;
        }
        if (mods <= 120) {
            return 4;
        }
        return mods <= 250 ? 6 : 8;
    }

    /** Read from where the launcher keeps its instances, so the hint names its own settings. */
    static String launcher(Path gameDir) {
        String p = gameDir.toAbsolutePath().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (p.contains("prismlauncher") || p.contains("multimc") || p.contains("polymc")) {
            return "prism";
        }
        if (p.contains("curseforge")) {
            return "curseforge";
        }
        if (p.contains("modrinthapp") || p.contains("com.modrinth.theseus")) {
            return "modrinth";
        }
        return p.endsWith("/.minecraft") || (p.endsWith("/minecraft") && p.contains("application support"))
                ? "vanilla" : "other";
    }

    /** What Java was given, in GB with one decimal ("1.5"), for the player. */
    public String givenGb() {
        double gb = Math.round(givenMb / 102.4) / 10.0;
        return gb == Math.floor(gb) ? String.valueOf((int) gb) : String.valueOf(gb);
    }

    /** Something to show the player. */
    public boolean warns() {
        return !verdict.equals("fine");
    }
}
