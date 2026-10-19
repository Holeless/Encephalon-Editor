package com.example.encephalon.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.encephalon.EncephalonMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Applies the hitboxes set in the Mob Editor. Runs on both logical sides (the client needs the same box for aiming at the mob),
 * reading the same HitboxData: in singleplayer the client reads the integrated server's data directly (multiplayer sync is a TODO,
 * like the rest of the editor). The box is only replaced when its size differs, and sizes are cached per type, so a mob without an
 * override costs one volatile read per tick, and a mob with one costs a map lookup.
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class HitboxOverrides {
    private static final float[] NONE = new float[0];
    private static final Map<EntityType<?>, float[]> CACHE = new ConcurrentHashMap<>();
    private static volatile HitboxData current;
    private static volatile HitboxData cachedFor;
    private static volatile int cachedVersion = -1;

    private HitboxOverrides() {
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        current = HitboxData.get(server);
        CACHE.clear();
        cachedFor = null;
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        current = null;
        CACHE.clear();
        cachedFor = null;
    }

    /** {width, height} of the edited hitbox of the type, or null when it has no override. Safe to call from any thread. */
    public static float[] sizeOf(EntityType<?> type) {
        HitboxData d = current;
        if (d == null || !d.any()) {
            return null;
        }
        if (cachedFor != d || cachedVersion != d.version()) {
            CACHE.clear();
            cachedFor = d;
            cachedVersion = d.version();
        }
        float[] s = CACHE.computeIfAbsent(type, t -> {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(t).toString();
            if (!d.has(id)) {
                return NONE;
            }
            return d.hitbox(id).size(t.getWidth(), t.getHeight());
        });
        return s == NONE ? null : s;
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Post event) {
        Entity e = event.getEntity();
        if (!(e instanceof LivingEntity) || e instanceof Player) {
            return;
        }
        float[] s = sizeOf(e.getType());
        if (s == null) {
            return;
        }
        AABB b = e.getBoundingBox();
        if (Math.abs(b.getXsize() - s[0]) > 1.0E-4 || Math.abs(b.getYsize() - s[1]) > 1.0E-4) {
            e.setBoundingBox(EntityDimensions.scalable(s[0], s[1]).makeBoundingBox(e.position()));
        }
    }
}
