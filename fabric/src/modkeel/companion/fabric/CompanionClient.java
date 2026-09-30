package modkeel.companion.fabric;

import modkeel.companion.game.Client;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/** Fabric client entrypoint. */
public final class CompanionClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Client.init();
        ClientTickEvents.END_CLIENT_TICK.register(Client::clientTick);
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) ->
                Client.afterScreenInit(mc, screen, width, w -> FabricCompat.addWidget(screen, w)));
    }
}
