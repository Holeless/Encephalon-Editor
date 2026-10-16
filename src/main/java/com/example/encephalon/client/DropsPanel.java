package com.example.encephalon.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.encephalon.EncephalonMod;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * The "Drops" part of a mob's stat sheet: every item the mob's loot table can give, how often, and how many.
 * <p>
 * Loot tables only exist on the (integrated) server and are built from conditions and functions that can't be summed up
 * exactly, so the table is simply rolled thousands of times for a mob killed by a player and the results are counted. That
 * works for any mod's loot table. It runs once per mob type per editor session, on the server thread, and never blocks the
 * render thread: the panel shows "loading" until the result arrives.
 */
final class DropsPanel {
    record Drop(ItemStack stack, double chance, int min, int max) {
    }

    private static final int ROLLS = 4000;
    private static final int MIN_ROLLS = 300;
    private static final long BUDGET_NANOS = 40_000_000L;
    private static final int ROW_H = 18;

    private final Map<EntityType<?>, List<Drop>> results = new ConcurrentHashMap<>();
    private final Set<EntityType<?>> requested = new HashSet<>();
    private int scroll;

    void resetScroll() {
        scroll = 0;
    }

    void scrollBy(int rows) {
        scroll = Math.max(0, scroll + rows);
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
            LootContext context = new LootContext.Builder(params).create(Optional.empty());

            Map<Item, int[]> stats = new HashMap<>(); // hits, smallest and largest count in one kill
            long deadline = System.nanoTime() + BUDGET_NANOS;
            int rolls = 0;
            while (rolls < ROLLS && (rolls < MIN_ROLLS || System.nanoTime() < deadline)) {
                Map<Item, Integer> got = new HashMap<>();
                for (ItemStack stack : table.getRandomItems(context)) {
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

    private static String percent(double chance) {
        double p = chance * 100.0;
        if (p >= 99.95) {
            return "100%";
        }
        if (p < 0.1) {
            return "<0.1%";
        }
        return String.format(Locale.ROOT, "%.1f%%", p);
    }

    private static int percentColor(double chance) {
        return chance >= 0.995 ? 0x55FF55 : chance >= 0.5 ? 0xFFFFFF : chance >= 0.1 ? 0xFFFF55 : 0xFFAA00;
    }

    /**
     * Draws the section between top and bottom (x .. x + w); barX is where the scrollbar goes. Asks for the drops of the type
     * the first time it is drawn.
     */
    void draw(GuiGraphics g, Font font, MinecraftServer server, ResourceKey<Level> dim, UUID playerId, EntityType<?> type,
              int x, int top, int w, int bottom, int barX) {
        request(server, dim, playerId, type);
        g.fill(x, top, x + w, top + 1, 0x50FFFFFF);
        g.drawString(font, Component.translatable("encephalon.screen.drops"), x, top + 4, 0xFFFFFF, true);
        int listTop = top + 16;
        List<Drop> list = results.get(type);
        if (list == null) {
            g.drawString(font, Component.translatable("encephalon.screen.drops_loading"), x, listTop + 2, 0x808080, true);
            return;
        }
        if (list.isEmpty()) {
            g.drawString(font, Component.translatable("encephalon.screen.drops_none"), x, listTop + 2, 0x808080, true);
            return;
        }
        int visible = Math.max(1, (bottom - listTop) / ROW_H);
        int max = Math.max(0, list.size() - visible);
        scroll = Mth.clamp(scroll, 0, max);
        int last = Math.min(list.size(), scroll + visible);
        for (int i = scroll; i < last; i++) {
            Drop d = list.get(i);
            int y = listTop + (i - scroll) * ROW_H;
            g.renderFakeItem(d.stack(), x, y);
            String pct = percent(d.chance());
            String count = d.max() <= 1 ? "" : d.min() == d.max() ? "x" + d.min() : "x" + d.min() + "-" + d.max();
            int pw = font.width(pct);
            int cw = count.isEmpty() ? 0 : font.width(count) + 6;
            int ty = y + 5;
            g.drawString(font, pct, x + w - pw, ty, percentColor(d.chance()), true);
            if (!count.isEmpty()) {
                g.drawString(font, count, x + w - pw - cw, ty, 0xA0A0A0, true);
            }
            String name = d.stack().getHoverName().getString();
            int room = w - 20 - pw - cw - 6;
            if (font.width(name) > room) {
                name = font.plainSubstrByWidth(name, Math.max(0, room - font.width("..."))) + "...";
            }
            g.drawString(font, name, x + 20, ty, 0xE0E0E0, true);
        }
        if (max > 0) {
            int view = bottom - listTop;
            int thumbH = Math.max(16, Math.min(view - 4, view * visible / list.size()));
            int thumbY = listTop + (view - thumbH) * scroll / max;
            PickList.drawScrollbar(g, barX, listTop, bottom, thumbY, thumbH);
        }
    }
}
