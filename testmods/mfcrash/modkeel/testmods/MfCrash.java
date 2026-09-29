package modkeel.testmods;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Test mod: with -Dmfcrash.after=N, crashes the client N seconds after a world is loaded. */
public final class MfCrash implements ClientModInitializer {
    private long inWorldSince;

    @Override
    public void onInitializeClient() {
        String after = System.getProperty("mfcrash.after");
        if (after == null) {
            return;
        }
        long delay = Long.parseLong(after) * 1000;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
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
