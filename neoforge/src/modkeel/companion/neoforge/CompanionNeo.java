package modkeel.companion.neoforge;

import modkeel.companion.game.Common;
import net.minecraft.SharedConstants;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** NeoForge entrypoint: world backups and the last good set, on clients and servers. */
@Mod("modkeel")
public final class CompanionNeo {
    public CompanionNeo(IEventBus modBus, ModContainer container, Dist dist) {
        Common.init(FMLPaths.GAMEDIR.get(), SharedConstants.getCurrentVersion().getName(),
                () -> container.getModInfo().getOwningFile().getFile().getFilePath());
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent e) -> Common.serverStarting(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> Common.serverTick());
        if (dist.isClient()) {
            // a class of its own: client classes are absent on a dedicated server
            NeoClient.init();
        } else {
            Common.dedicatedServerStartup();
        }
    }
}
