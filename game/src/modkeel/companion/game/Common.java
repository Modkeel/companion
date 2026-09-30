package modkeel.companion.game;

import java.nio.file.Path;
import java.util.function.Supplier;

import modkeel.companion.core.Diagnosis;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Log;
import modkeel.companion.core.Outcomes;
import modkeel.companion.core.Sections;
import modkeel.companion.core.Spikes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/**
 * What every loader does on clients and servers alike: world backups and the last good set.
 * Loader adapters call these from their own events; nothing here touches client classes.
 */
public final class Common {
    public static Guardian guardian;
    public static Spikes spikes;
    private static Supplier<Path> selfJar;
    private static int ticks;
    /** Play is counted towards fix outcomes once a minute. */
    private static final int PLAY_CHUNK = (int) Math.min(1200, Outcomes.HELD_TICKS);
    private static boolean marked;

    private Common() {
    }

    /** @param selfJar this mod's jar, which also holds the helper that applies fixes */
    public static void init(Path gameDir, String mcVersion, Supplier<Path> selfJar) {
        Log.sink = LoggerFactory.getLogger("modkeel")::info;
        guardian = new Guardian(gameDir);
        guardian.mcVersion = mcVersion;
        Common.selfJar = selfJar;
        spikes = new Spikes(guardian::owners, Sections.load());
        // read every jar now, off the game threads, so the first spike is named without delay
        background(guardian::owners);
        spikes.start();
    }

    public static Path selfJar() {
        return selfJar.get();
    }

    public static void serverStarting(MinecraftServer server) {
        ticks = 0;
        marked = false;
        guardian.onWorldStarting(server.getWorldPath(LevelResource.ROOT));
    }

    public static void serverStopped() {
        guardian.onWorldStopped();
    }

    public static void serverTick() {
        spikes.watch(Spikes.Where.WORLD).beat();
        ++ticks;
        if (!marked && ticks >= Guardian.GOOD_TICKS) {
            marked = true;
            background(guardian::markGood);
        }
        if (ticks % PLAY_CHUNK == 0) {
            background(() -> guardian.outcomes.addTicks(PLAY_CHUNK));
        }
    }

    /** No screen on a dedicated server: the diagnosis goes to the log. */
    public static void dedicatedServerStartup() {
        Diagnosis d = guardian.startup();
        if (d != null && d.top() != null && d.top().file != null) {
            Log.info("to disable the suspect, rename mods/" + d.top().file + " to "
                     + d.top().file + ".disabled");
        }
    }

    static void background(Runnable r) {
        Thread t = new Thread(r, "modkeel-background");
        t.setDaemon(true);
        t.start();
    }
}
