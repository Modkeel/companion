package modkeel.companion.game;

import java.util.Map;

import modkeel.companion.core.Activity;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;

/** Minecraft 1.20.1 names for what {@link Tracker} reads; server side only. */
final class Play {
    private Play() {
    }

    static String namespace(ResourceKey<?> key) {
        return key.location().getNamespace();
    }

    /** Each mod's advancements the player has earned, recipe unlocks left out. */
    static void advancements(MinecraftServer server, ServerPlayer player, Map<String, long[]> out) {
        PlayerAdvancements done = player.getAdvancements();
        for (Advancement a : server.getAdvancements().getAllAdvancements()) {
            if (!a.getId().getPath().startsWith("recipes/") && done.getOrStartProgress(a).isDone()) {
                out.computeIfAbsent(a.getId().getNamespace(), k -> new long[Activity.KINDS.length])[Activity.ADV]++;
            }
        }
    }
}
