package com.example.encephalon.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.encephalon.EncephalonMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
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
 * like the rest of the editor). Sizes are cached per type, so a mob without an override costs one volatile read per tick, and a
 * mob with one costs a map lookup.
 * <p>
 * The footprint is a width x length rectangle. Minecraft boxes cannot rotate, so each tick the rectangle is turned by the mob's
 * body yaw and the axis-aligned bounds of the result are used: tight when the mob faces a cardinal direction, with some slack
 * at diagonal angles (a limit of vanilla's AABB).
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

    /** {width, height, length} of the edited hitbox of the type, or null when it has no override. Safe to call from any thread. */
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
        if (!(event.getEntity() instanceof LivingEntity e) || e instanceof Player) {
            return;
        }
        float[] s = sizeOf(e.getType()); // {width, height, length}
        if (s == null) {
            return;
        }
        double yaw = Math.toRadians(e.yBodyRot);
        double c = Math.abs(Math.cos(yaw));
        double sn = Math.abs(Math.sin(yaw));
        double xs = c * s[0] + sn * s[2];
        double zs = sn * s[0] + c * s[2];
        AABB want = new AABB(e.getX() - xs / 2.0, e.getY(), e.getZ() - zs / 2.0,
                e.getX() + xs / 2.0, e.getY() + s[1], e.getZ() + zs / 2.0);
        AABB b = e.getBoundingBox();
        if (Math.abs(b.minX - want.minX) > 1.0E-4 || Math.abs(b.maxX - want.maxX) > 1.0E-4
                || Math.abs(b.minY - want.minY) > 1.0E-4 || Math.abs(b.maxY - want.maxY) > 1.0E-4
                || Math.abs(b.minZ - want.minZ) > 1.0E-4 || Math.abs(b.maxZ - want.maxZ) > 1.0E-4) {
            e.setBoundingBox(want);
        }
    }
}
