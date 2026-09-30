package modkeel.testmods.crash;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Test mod: with -Dmfcrash.after=N, crashes the client N seconds after a world is loaded.
 * With -Dmfcrash.lag=N, freezes the client for -Dmfcrash.lag_ms (800) every 30 s from N
 * seconds after a world is loaded, busy in its own code (for the lag spike detector).
 */
@Mod(value = "mfcrash", dist = Dist.CLIENT)
public final class MfCrashNeo {
    private long inWorldSince;
    private long lastLag;
    private static volatile long sink;

    public MfCrashNeo() {
        String after = System.getProperty("mfcrash.after");
        String lag = System.getProperty("mfcrash.lag");
        if (after == null && lag == null) {
            return;
        }
        long delay = after == null ? Long.MAX_VALUE : Long.parseLong(after) * 1000;
        long lagFrom = lag == null ? Long.MAX_VALUE : Long.parseLong(lag) * 1000;
        long lagMs = Long.getLong("mfcrash.lag_ms", 800);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                inWorldSince = 0;
                return;
            }
            if (inWorldSince == 0) {
                inWorldSince = System.currentTimeMillis();
            }
            long inWorld = System.currentTimeMillis() - inWorldSince;
            if (inWorld > delay) {
                explode();
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
