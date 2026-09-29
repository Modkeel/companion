package modkeel.testmods.crash;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Test mod: with -Dmfcrash.after=N, crashes the client N seconds after a world is loaded. */
@Mod(value = "mfcrash", dist = Dist.CLIENT)
public final class MfCrashNeo {
    private long inWorldSince;

    public MfCrashNeo() {
        String after = System.getProperty("mfcrash.after");
        if (after == null) {
            return;
        }
        long delay = Long.parseLong(after) * 1000;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                inWorldSince = 0;
                return;
            }
            if (inWorldSince == 0) {
                inWorldSince = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - inWorldSince > delay) {
                explode();
            }
        });
    }

    private static void explode() {
        throw new IllegalStateException("mfcrash: deliberate test crash");
    }
}
