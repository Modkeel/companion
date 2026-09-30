package modkeel.companion.neoforge;

import modkeel.companion.game.Client;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge client side. */
final class NeoClient {
    private NeoClient() {
    }

    static void init() {
        Client.init();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) ->
                Client.clientTick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post e) ->
                Client.afterScreenInit(Minecraft.getInstance(), e.getScreen(), e.getScreen().width,
                        e::addListener));
    }
}
