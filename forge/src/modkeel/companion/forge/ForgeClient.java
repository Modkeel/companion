package modkeel.companion.forge;

import modkeel.companion.game.Client;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;

/** Forge client side. */
final class ForgeClient {
    private ForgeClient() {
    }

    static void init() {
        Client.init();
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ScreenEvent.Init.Post.class,
                e -> Client.afterScreenInit(Minecraft.getInstance(), e.getScreen(),
                        e.getScreen().width, e::addListener));
    }
}
