package modkeel.testmods.idle;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Test mod: keeps the mouse free, as the Fabric probe does in idle mode. Tests run next to
 * someone using the machine, and joining a world grabs the mouse whenever Windows focused it.
 */
@Mod(value = "mfidle", dist = Dist.CLIENT)
public final class MfIdleNeo {
    public MfIdleNeo() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.mouseHandler.isMouseGrabbed()) {
                mc.mouseHandler.releaseMouse();
            }
        });
    }
}
