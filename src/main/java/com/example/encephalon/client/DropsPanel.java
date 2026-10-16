package com.example.encephalon.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.encephalon.EncephalonMod;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * Works out the DEFAULT drops of a mob type: every item its loot table can give, how often, and how many.
 * <p>
 * Loot tables only exist on the (integrated) server and are built from conditions and functions that can't be summed up
 * exactly, so the table is simply rolled thousands of times for a mob killed by a player and the results are counted. That
 * works for any mod's loot table. It runs once per mob type per editor session, on the server thread, and never blocks the
 * render thread: defaults() answers null until the result has arrived. The Mob Editor shows and edits the result.
 */
final class DropsPanel {
    record Drop(ItemStack stack, double chance, int min, int max) {
    }

    private static final int ROLLS = 4000;
    private static final int MIN_ROLLS = 300;
    private static final long BUDGET_NANOS = 40_000_000L;

    private final Map<EntityType<?>, List<Drop>> results = new ConcurrentHashMap<>();
    private final Set<EntityType<?>> requested = new HashSet<>();

    /** The default drops of the type (the same list instance every time), or null while they are still being worked out. */
    List<Drop> defaults(MinecraftServer server, ResourceKey<Level> dim, UUID playerId, EntityType<?> type) {
        request(server, dim, playerId, type);
        return results.get(type);
    }

    private void request(MinecraftServer server, ResourceKey<Level> dim, UUID playerId, EntityType<?> type) {
        if (results.containsKey(type) || !requested.add(type)) {
            return;
        }
        try {
            server.execute(() -> results.put(type, compute(server, dim, playerId, type)));
        } catch (RuntimeException ex) {
            results.put(type, List.of());
        }
    }

    /** Server thread. */
    private static List<Drop> compute(MinecraftServer server, ResourceKey<Level> dim, UUID playerId, EntityType<?> type) {
        Entity entity = null;
        try {
            ServerLevel level = server.getLevel(dim);
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (level == null || player == null) {
                return List.of();
            }
            LootTable table = server.reloadableRegistries().getLootTable(type.getDefaultLootTable());
            if (table == LootTable.EMPTY) {
                return List.of();
            }
            entity = type.create(level);
            if (entity == null) {
                return List.of();
            }
            LootParams params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.THIS_ENTITY, entity)
                    .withParameter(LootContextParams.ORIGIN, entity.position())
                    .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().playerAttack(player))
                    .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, player)
                    .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, player)
                    .withOptionalParameter(LootContextParams.LAST_DAMAGE_PLAYER, player)
                    .create(LootContextParamSets.ENTITY);

            Map<Item, int[]> stats = new HashMap<>(); // hits, smallest and largest count in one kill
            long deadline = System.nanoTime() + BUDGET_NANOS;
            int rolls = 0;
            while (rolls < ROLLS && (rolls < MIN_ROLLS || System.nanoTime() < deadline)) {
                Map<Item, Integer> got = new HashMap<>();
                for (ItemStack stack : table.getRandomItems(params)) {
                    if (!stack.isEmpty()) {
                        got.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
                rolls++;
                for (Map.Entry<Item, Integer> e : got.entrySet()) {
                    int[] st = stats.computeIfAbsent(e.getKey(), k -> new int[] {0, Integer.MAX_VALUE, 0});
                    st[0]++;
                    st[1] = Math.min(st[1], e.getValue());
                    st[2] = Math.max(st[2], e.getValue());
                }
            }
            List<Drop> list = new ArrayList<>();
            for (Map.Entry<Item, int[]> e : stats.entrySet()) {
                int[] st = e.getValue();
                list.add(new Drop(new ItemStack(e.getKey()), st[0] / (double) rolls, st[1], st[2]));
            }
            list.sort(Comparator.comparingDouble(Drop::chance).reversed()
                    .thenComparing(d -> d.stack().getHoverName().getString()));
            return List.copyOf(list);
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not work out the drops of {}", type, ex);
            return List.of();
        } finally {
            if (entity != null) {
                entity.discard();
            }
        }
    }
}
