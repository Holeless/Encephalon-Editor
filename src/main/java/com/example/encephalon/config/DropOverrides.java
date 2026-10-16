package com.example.encephalon.config;

import java.util.List;

import com.example.encephalon.EncephalonMod;
import com.example.encephalon.config.MobConfigData.DropEntry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * Applies the drops set in the Mob Editor. A mob type with a custom drop list drops exactly that list (the default loot
 * table and its equipment drops are replaced): each entry is rolled on its own, by its percentage, for an amount between
 * its min and max. A mob type without a custom list is left alone.
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class DropOverrides {
    private DropOverrides() {
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity living = event.getEntity();
        if (living.level().isClientSide() || living instanceof Player) {
            return;
        }
        MinecraftServer server = living.getServer();
        if (server == null) {
            return;
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).toString();
        List<DropEntry> custom = MobConfigData.get(server).drops(id);
        if (custom == null) {
            return;
        }
        event.getDrops().clear();
        RandomSource random = living.getRandom();
        for (DropEntry e : custom) {
            if (e.chance() <= 0.0 || random.nextDouble() * 100.0 >= e.chance()) {
                continue;
            }
            Item item = null;
            try {
                item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(e.item())).orElse(null);
            } catch (RuntimeException ex) {
                // a bad id in the save: skip it
            }
            if (item == null || item == Items.AIR) {
                continue;
            }
            int lo = Math.max(0, e.min());
            int hi = Math.max(lo, e.max());
            int count = hi > lo ? lo + random.nextInt(hi - lo + 1) : lo;
            if (count <= 0) {
                continue;
            }
            ItemEntity drop = new ItemEntity(living.level(), living.getX(), living.getY(), living.getZ(), new ItemStack(item, count));
            drop.setDefaultPickUpDelay();
            event.getDrops().add(drop);
        }
    }
}
