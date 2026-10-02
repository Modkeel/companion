package modkeel.companion.game;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import modkeel.companion.core.Activity;
import modkeel.companion.core.Diagnosis;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Log;
import modkeel.companion.core.Outcomes;
import modkeel.companion.core.Sections;
import modkeel.companion.core.Spikes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
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
    private static Tracker tracker = new Tracker();
    private static boolean trackerFailed;
    /** Test hook: the players mine and use stone every second (-Dmodkeel.test.play=true). */
    private static final boolean TEST_PLAY = Boolean.getBoolean("modkeel.test.play");

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
        guardian = StartingCrash.early != null ? StartingCrash.early : new Guardian(gameDir);
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
        tracker = new Tracker();
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
        if (ticks % 20 == 0) {
            track(() -> {
                tracker.meet(server);
                if (TEST_PLAY) {
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        p.awardStat(Stats.BLOCK_MINED.get(Blocks.STONE));
                        p.awardStat(Stats.ITEM_USED.get(Items.STONE));
                    }
                }
            });
        }
        if (ticks % PLAY_CHUNK == 0) {
            Activity[] played = {null}; // stays null when measuring is off
            track(() -> played[0] = tracker.sample(server, PLAY_CHUNK));
            background(() -> guardian.addPlay(PLAY_CHUNK, spikes.recent(), played[0]));
        }
    }

    /** Measuring play must never break the game: on the first error it stops, once logged. */
    private static void track(Runnable r) {
        if (trackerFailed || server == null) {
            return;
        }
        try {
            r.run();
        } catch (RuntimeException | LinkageError e) {
            trackerFailed = true;
            Log.warn("per-mod activity off for this run", e);
        }
    }

    /** No screen on a dedicated server: the diagnosis goes to the log. */
    public static void dedicatedServerStartup() {
        Diagnosis d = guardian.startup();
        guardian.loaded();
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
