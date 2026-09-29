package modkeel.testmods.idle;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;

/**
 * Test mod: keeps the mouse free, as the Fabric probe does in idle mode. Tests run next to
 * someone using the machine, and joining a world grabs the mouse whenever Windows focused it.
 */
@Mod("mfidle")
public final class MfIdleForge {
    public MfIdleForge() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, e -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.mouseHandler.isMouseGrabbed()) {
                mc.mouseHandler.releaseMouse();
            }
        });
    }
}
