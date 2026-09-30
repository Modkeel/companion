package modkeel.companion.forge;

import modkeel.companion.game.Common;
import net.minecraft.SharedConstants;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/** Forge entrypoint: world backups and the last good set, on clients and servers. */
@Mod("modkeel")
public final class CompanionForge {
    public CompanionForge() {
        Common.init(FMLPaths.GAMEDIR.get(), SharedConstants.getCurrentVersion().getName(),
                () -> ModList.get().getModFileById("modkeel").getFile().getFilePath());
        // explicit event classes: Forge's bus cannot always read them from a lambda
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStartingEvent.class,
                e -> Common.serverStarting(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ServerTickEvent.class,
                e -> {
                    if (e.phase == TickEvent.Phase.END) {
                        Common.serverTick();
                    }
                });
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class,
                e -> Common.serverStopped());
        if (FMLEnvironment.dist.isClient()) {
            // a class of its own: client classes are absent on a dedicated server
            ForgeClient.init();
        } else {
            Common.dedicatedServerStartup();
        }
    }
}
