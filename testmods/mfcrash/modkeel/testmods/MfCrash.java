package modkeel.testmods;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Test mod: with -Dmfcrash.after=N, crashes the client N seconds after a world is loaded.
 * With -Dmfcrash.lag=N, freezes the client for -Dmfcrash.lag_ms (800) every 30 s from N
 * seconds after a world is loaded, busy in its own code (for the lag spike detector).
 * With -Dmfcrash.entity=N, summons a burning pig tagged mfcrash N seconds after a world is
 * loaded (fixed UUID); its first fire damage throws inside the entity's tick (a vanilla "Ticking entity"
 * crash of the integrated server, the kind entity guards like Neruina catch).
 */
public final class MfCrash implements ClientModInitializer {
    private long inWorldSince;
    private long lastLag;
    private boolean summoned;
    private static volatile long sink;
    /** The summoned pig's fixed UUID: entity tag accessors differ by version, getUUID does not. */
    private static final UUID PIG = new UUID(0L, 4242L);

    @Override
    public void onInitializeClient() {
        String after = System.getProperty("mfcrash.after");
        String lag = System.getProperty("mfcrash.lag");
        String entity = System.getProperty("mfcrash.entity");
        if (after == null && lag == null && entity == null) {
            return;
        }
        long entityAt = entity == null ? Long.MAX_VALUE : Long.parseLong(entity) * 1000;
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
            if (PIG.equals(victim.getUUID())) {
                LoggerFactory.getLogger("mfcrash").info("mfcrash: throwing in an entity tick");
                throw new IllegalStateException("mfcrash: deliberate entity tick crash");
            }
            return true;
        });
        long delay = after == null ? Long.MAX_VALUE : Long.parseLong(after) * 1000;
        long lagFrom = lag == null ? Long.MAX_VALUE : Long.parseLong(lag) * 1000;
        long lagMs = Long.getLong("mfcrash.lag_ms", 800);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == null) {
                inWorldSince = 0;
                summoned = false;
                return;
            }
            if (inWorldSince == 0) {
                inWorldSince = System.currentTimeMillis();
            }
            long inWorld = System.currentTimeMillis() - inWorldSince;
            if (inWorld > delay) {
                explode();
            }
            MinecraftServer server = mc.getSingleplayerServer();
            if (inWorld > entityAt && !summoned && server != null) {
                summoned = true;
                server.execute(() -> server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack(),
                        "execute at @p run summon minecraft:pig ^ ^ ^3 "
                                + "{UUID:[I;0,0,0,4242],Tags:[\"mfcrash\"],Fire:200s}"));
            }
            if (inWorld > lagFrom && System.currentTimeMillis() - lastLag > 30_000) {
                lastLag = System.currentTimeMillis();
                spin(lagMs);
            }
        });
    }

    private static void explode() {
        throw new IllegalStateException("mfcrash: deliberate test crash");
    }

    private static void spin(long ms) {
        long end = System.nanoTime() + ms * 1_000_000;
        long x = 0;
        while (System.nanoTime() < end) {
            x += x * 31 + 7;
        }
        sink = x;
    }
}
