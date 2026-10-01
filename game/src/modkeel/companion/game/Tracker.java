package modkeel.companion.game;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import modkeel.companion.core.Activity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Per-mod play, read once a minute on the server thread from what the game already keeps:
 * each player's stats and advancements (grouped by the namespace of their ids), the mod
 * dimension or biome they stand in, and the block entities around them. No mixins.
 */
final class Tracker {
    /** Chunks around each player whose block entities count as machines in use. */
    private static final int RADIUS = 6;

    private static final class Seen {
        Map<String, long[]> totals;
        double x, y, z;
        float yRot, xRot;
    }

    private final Map<UUID, Seen> seen = new HashMap<>();
    private final Map<BlockEntityType<?>, String> typeMods = new IdentityHashMap<>();

    /** A player who just joined gets their starting totals now, not at the next sample. */
    void meet(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!seen.containsKey(p.getUUID())) {
                seen.put(p.getUUID(), read(server, p));
            }
        }
    }

    /** One sample: what the players did since the last one, over {@code ticks} ticks. */
    Activity sample(MinecraftServer server, long ticks) {
        Activity a = new Activity();
        Set<UUID> online = new HashSet<>();
        Map<String, long[]> machines = new HashMap<>();
        Map<ServerLevel, Set<Long>> chunks = new HashMap<>();
        boolean active = false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            online.add(p.getUUID());
            Seen now = read(server, p);
            Seen before = seen.put(p.getUUID(), now);
            if (before != null) {
                boolean moved = before.x != now.x || before.y != now.y || before.z != now.z
                        || before.yRot != now.yRot || before.xRot != now.xRot;
                active |= a.addGrowth(before.totals, now.totals) | moved;
            }
            ServerLevel level = (ServerLevel) p.level();
            String dim = Play.namespace(level.dimension());
            String biome = level.getBiome(p.blockPosition()).unwrapKey().map(Play::namespace).orElse("minecraft");
            if (!dim.equals("minecraft")) {
                a.count(dim, Activity.HERE, ticks);
            }
            if (!biome.equals("minecraft") && !biome.equals(dim)) {
                a.count(biome, Activity.HERE, ticks);
            }
            Set<Long> near = chunks.computeIfAbsent(level, l -> new HashSet<>());
            int cx = p.blockPosition().getX() >> 4;
            int cz = p.blockPosition().getZ() >> 4;
            for (int x = cx - RADIUS; x <= cx + RADIUS; x++) {
                for (int z = cz - RADIUS; z <= cz + RADIUS; z++) {
                    if (near.add(((long) x << 32) ^ (z & 0xffffffffL))) {
                        LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                        if (chunk != null) {
                            for (BlockEntity be : chunk.getBlockEntities().values()) {
                                machines.computeIfAbsent(typeMod(be.getType()), k -> new long[1])[0]++;
                            }
                        }
                    }
                }
            }
        }
        seen.keySet().retainAll(online);
        for (Map.Entry<String, long[]> e : machines.entrySet()) {
            a.peak(e.getKey(), Activity.MACHINES, e.getValue()[0]);
        }
        if (active) {
            a.activeTicks = ticks;
        }
        return a;
    }

    private static Seen read(MinecraftServer server, ServerPlayer p) {
        Seen s = new Seen();
        s.totals = totals(server, p);
        s.x = p.getX();
        s.y = p.getY();
        s.z = p.getZ();
        s.yRot = p.getYRot();
        s.xRot = p.getXRot();
        return s;
    }

    /** The player's running totals per mod: the stats that show using a mod's content. */
    private static Map<String, long[]> totals(MinecraftServer server, ServerPlayer p) {
        Map<String, long[]> out = new HashMap<>();
        ServerStatsCounter stats = p.getStats();
        add(stats, Stats.BLOCK_MINED, Activity.MINED, out);
        add(stats, Stats.ITEM_CRAFTED, Activity.CRAFTED, out);
        add(stats, Stats.ITEM_USED, Activity.USED, out);
        add(stats, Stats.ENTITY_KILLED, Activity.KILLED, out);
        Play.advancements(server, p, out);
        return out;
    }

    /** A stat only exists once something counted it: the rest are skipped without lookups. */
    private static <T> void add(ServerStatsCounter stats, StatType<T> type, int kind, Map<String, long[]> out) {
        Registry<T> registry = type.getRegistry();
        for (T value : registry) {
            if (!type.contains(value)) {
                continue;
            }
            int n = stats.getValue(type.get(value));
            if (n > 0) {
                out.computeIfAbsent(namespace(String.valueOf(registry.getKey(value))),
                        k -> new long[Activity.KINDS.length])[kind] += n;
            }
        }
    }

    private String typeMod(BlockEntityType<?> type) {
        return typeMods.computeIfAbsent(type,
                t -> namespace(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(t))));
    }

    /** "create:cogwheel" -> "create" (ids print as namespace:path in every version). */
    private static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }
}
