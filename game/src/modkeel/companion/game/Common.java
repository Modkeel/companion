package modkeel.companion.game;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import modkeel.companion.core.Diagnosis;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Log;
import modkeel.companion.core.Outcomes;
import modkeel.companion.core.Sections;
import modkeel.companion.core.Spikes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/**
 * What every loader does on clients and servers alike: world backups and the last good set.
 * Loader adapters call these from their own events; nothing here touches client classes.
 */
public final class Common {
    public static Guardian guardian;
    public static Spikes spikes;
    private static volatile MinecraftServer server;
    private static Supplier<Path> selfJar;
    private static int ticks;
    /** Play is counted towards fix outcomes once a minute. */
    private static final int PLAY_CHUNK = (int) Math.min(1200, Outcomes.HELD_TICKS);
    private static boolean marked;

    private Common() {
    }

    /**
     * @param loader "fabric", "quilt", "neoforge" or "forge", with its version: both only go
     *               into reports the player chose to share
     * @param selfJar this mod's jar, which also holds the helper that applies fixes
     */
    public static void init(Path gameDir, String mcVersion, String loader, String loaderVersion,
                            Supplier<Path> selfJar) {
        Log.sink = LoggerFactory.getLogger("modkeel")::info;
        guardian = new Guardian(gameDir);
        guardian.mcVersion = mcVersion;
        guardian.loader = loader;
        guardian.loaderVersion = loaderVersion;
        Common.selfJar = selfJar;
        spikes = new Spikes(guardian::owners, Sections.load());
        spikes.counter = Common::countEntities;
        // read every jar now, off the game threads, so the first spike is named without delay
        background(guardian::owners);
        spikes.start();
    }

    public static Path selfJar() {
        return selfJar.get();
    }

    public static void serverStarting(MinecraftServer server) {
        Common.server = server;
        ticks = 0;
        marked = false;
        guardian.onWorldStarting(server.getWorldPath(LevelResource.ROOT));
    }

    public static void serverStopped() {
        server = null;
        guardian.onWorldStopped();
    }

    /** After a world spike, on the server thread: which entities fill the world. */
    private static void countEntities(Spikes.Spike s) {
        MinecraftServer srv = server;
        if (srv == null) {
            return;
        }
        srv.execute(() -> {
            Map<String, Integer> perType = new HashMap<>();
            for (ServerLevel level : srv.getAllLevels()) {
                for (Entity e : level.getAllEntities()) {
                    perType.merge(e.getType().getDescriptionId(), 1, Integer::sum);
                }
            }
            s.count(perType);
        });
    }

    public static void serverTick() {
        spikes.watch(Spikes.Where.WORLD).beat();
        ++ticks;
        if (!marked && ticks >= Guardian.GOOD_TICKS) {
            marked = true;
            background(guardian::markGood);
        }
        if (ticks % PLAY_CHUNK == 0) {
            background(() -> guardian.addPlay(PLAY_CHUNK));
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
