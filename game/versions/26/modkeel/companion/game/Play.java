package modkeel.companion.game;

import java.util.Map;

import modkeel.companion.core.Activity;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;

/** Minecraft 26 and 1.21.11 names for what {@link Tracker} reads; server side only. */
final class Play {
    private Play() {
    }

    static String namespace(ResourceKey<?> key) {
        return key.identifier().getNamespace();
    }

    /** Each mod's advancements the player has earned, recipe unlocks left out. */
    static void advancements(MinecraftServer server, ServerPlayer player, Map<String, long[]> out) {
        PlayerAdvancements done = player.getAdvancements();
        for (AdvancementHolder a : server.getAdvancements().getAllAdvancements()) {
            if (!a.id().getPath().startsWith("recipes/") && done.getOrStartProgress(a).isDone()) {
                out.computeIfAbsent(a.id().getNamespace(), k -> new long[Activity.KINDS.length])[Activity.ADV]++;
            }
        }
    }
}
