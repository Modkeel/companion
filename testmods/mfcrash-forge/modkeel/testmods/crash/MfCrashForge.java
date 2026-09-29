package modkeel.testmods.crash;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;

/** Test mod: with -Dmfcrash.after=N, crashes the client N seconds after a world is loaded. */
@Mod("mfcrash")
public final class MfCrashForge {
    private long inWorldSince;

    public MfCrashForge() {
        String after = System.getProperty("mfcrash.after");
        if (after == null) {
            return;
        }
        long delay = Long.parseLong(after) * 1000;
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, e -> {
            if (e.phase != TickEvent.Phase.END) {
                return;
            }
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
