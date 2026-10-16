package com.example.encephalon.client;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.encephalon.EncephalonMod;
import com.example.encephalon.config.EncephalonConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The column of sky where the Mob Editor parks the previews (and the server-side twins of modded mobs, see MobEditorScreen).
 * Anything that appears in it while the editor is open belongs to the preview: the twin, and whatever it spawns
 * (body segments, a detached head, a portal, ...). Those must not hunt the player, take damage or make noise.
 * Sounds are silenced by position rather than per mob, so it works for any mod. Uses no client classes, so it is safe on any side.
 *
 * Containment: a twin runs its mod's real server code, and that code can reach outside the column (the Void Worm opens a portal
 * in the sky and a sister portal at its destination, next to the player, which then teleports the player). So while a preview
 * entity is spawning or ticking on the server, every entity it tries to add to a world is vetoed unless it is a living
 * companion inside the column that belongs to the twin's own mod (a body segment or a head, which the preview draws).
 * Minions of another mod or of vanilla (a ghoul that comes with a crowd of zombies) are vetoed too.
 *
 * Stability: twins are pinned to their spot after every tick (the preview draws their parts relative to that spot, and a mob
 * that drifts, like the Void Worm, would carry its parts out of view), and mobs inside the column never despawn (a head or
 * minion is not persistent and the column is hundreds of blocks above the player, so vanilla would remove it at once and the
 * mod would respawn it, which shows as flicker and a missing head).
 *
 * Leftovers: if the game is closed or crashes with the editor open, twins and parts are saved with their chunk. While the editor
 * is closed, a living entity that joins a level above the build limit (or carries the preview tag) is dropped on the spot.
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class PreviewZone {
    /** Null while the editor is closed. */
    static volatile AABB zone;

    /** Greater than zero while a twin is being created on the server thread (MobEditorScreen#startTwin). */
    private static volatile int spawning;
    /** Preview entities that are in the middle of their server tick right now (server thread only). */
    private static final Set<Entity> TICKING = ConcurrentHashMap.newKeySet();
    /** Entity id of each server twin -> the spot it is pinned to. */
    private static final Map<Integer, Vec3> TWIN_HOME = new ConcurrentHashMap<>();
    /** Mod ids of the twins that were made: only entities from these mods may exist next to a twin. */
    private static final Set<String> PART_NAMESPACES = ConcurrentHashMap.newKeySet();

    /** Twins that are about to be added to the level (they must not be mistaken for something they spawned). */
    private static final Set<Entity> EXPECTED = ConcurrentHashMap.newKeySet();
    /** Entity types already reported as blocked, so the log says it once. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private PreviewZone() {
    }

    /** Server thread: this entity is the twin the editor is creating, let it into the level. */
    static void expectTwin(Entity twin) {
        EXPECTED.add(twin);
    }

    static boolean contains(Vec3 pos) {
        AABB z = zone;
        return z != null && z.contains(pos);
    }

    /** Call on the server thread around code that creates a twin of a mob from the given mod. */
    static void beginSpawn(String namespace) {
        PART_NAMESPACES.add(namespace);
        spawning++;
    }

    static void endSpawn() {
        if (spawning > 0) {
            spawning--;
        }
    }

    /** Pins a server twin to its spot: after each of its ticks it is put back there with no velocity. */
    static void pinTwin(Entity twin, Vec3 home) {
        TWIN_HOME.put(twin.getId(), home);
    }

    /** Editor closed: forget everything. */
    static void close() {
        zone = null;
        spawning = 0;
        TICKING.clear();
        TWIN_HOME.clear();
        PART_NAMESPACES.clear();
        EXPECTED.clear();
    }

    /** True while preview code (a twin being created, or one ticking) is running on the server. */
    private static boolean previewCodeRunning() {
        return zone != null && (spawning > 0 || !TICKING.isEmpty());
    }

    /**
     * Whether a living entity next to a preview twin is a body part (a worm segment, a detached head) rather than a minion.
     * It has to be from the twin's own mod, and then it counts as a minion if it is a mob in its own right (it has a spawn egg,
     * so it is also listed in the editor) or if it hunts (it has target goals). Parts have neither. The config lists
     * partMobs / notPartMobs override this for any type it gets wrong.
     */
    static boolean isPart(Entity e) {
        if (!(e instanceof LivingEntity)) {
            return false;
        }
        EntityType<?> t = e.getType();
        if (EncephalonConfig.isForcedNotPart(t)) {
            return false;
        }
        if (!PART_NAMESPACES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(t).getNamespace())) {
            return false;
        }
        if (EncephalonConfig.isForcedPart(t)) {
            return true;
        }
        if (SpawnEggItem.byId(t) != null) {
            return false;
        }
        // Goals only exist on the server; the client only ever sees entities the server let through.
        return !(e instanceof Mob mob && !mob.targetSelector.getAvailableGoals().isEmpty());
    }

    private static void logBlocked(Entity e) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        if (LOGGED.add(id)) {
            EncephalonMod.LOGGER.info("Mob Editor preview: removed {} made by a preview mob (not a body part). "
                    + "If it is one, add it to partMobs in encephalon-client.toml.", id);
        }
    }

    @SubscribeEvent
    public static void onTickPre(EntityTickEvent.Pre event) {
        Entity e = event.getEntity();
        if (zone != null && !e.level().isClientSide() && !(e instanceof Player) && contains(e.position())) {
            TICKING.add(e);
        }
    }

    @SubscribeEvent
    public static void onTickPost(EntityTickEvent.Post event) {
        Entity e = event.getEntity();
        if (!TICKING.isEmpty()) {
            TICKING.remove(e);
        }
        if (zone != null && !TWIN_HOME.isEmpty() && !e.level().isClientSide()) {
            Vec3 home = TWIN_HOME.get(e.getId());
            if (home != null) {
                if (e.isRemoved()) {
                    TWIN_HOME.remove(e.getId());
                } else {
                    e.setPos(home.x, home.y, home.z);
                    e.setDeltaMovement(Vec3.ZERO);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (e instanceof Player) {
            return;
        }
        // Editor closed: anything living this high is a twin or body part left behind by a crash (they are saved with the
        // chunk). Nothing else lives above the build limit, so it is dropped as it loads instead of running its mod's code.
        if (zone == null && !event.getLevel().isClientSide() && e instanceof LivingEntity
                && (e.getTags().contains("encephalon_preview") || e.getY() > event.getLevel().getMaxBuildHeight() + 48)) {
            event.setCanceled(true);
            return;
        }
        boolean inside = contains(e.position());
        // Whatever a preview entity creates is vetoed, except living companions of the same mod inside the column. This is what
        // stops portals, their sister portals next to the player, projectiles, area clouds, items, minions and crowds of
        // zombies from ever existing.
        boolean twin = !event.getLevel().isClientSide() && EXPECTED.remove(e);
        if (!twin && !event.getLevel().isClientSide() && previewCodeRunning() && (!inside || !isPart(e))) {
            if (e instanceof LivingEntity) {
                logBlocked(e);
            }
            event.setCanceled(true);
            return;
        }
        if (!inside) {
            return;
        }
        e.setSilent(true); // both sides: the client copy of a part makes its own noises
        if (!event.getLevel().isClientSide()) {
            e.setInvulnerable(true);
            e.setNoGravity(true);
            if (e instanceof Mob mob) {
                mob.setPersistenceRequired();
            }
        }
    }

    /** Nothing in the column despawns: it is far from every player, and Peaceful removes monsters even if they are persistent. */
    @SubscribeEvent
    public static void onDespawn(MobDespawnEvent event) {
        if (contains(event.getEntity().position())) {
            event.setResult(MobDespawnEvent.Result.DENY);
        }
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (contains(event.getEntity().position())) {
            event.setCanceled(true);
        }
    }

    // Sounds played with level.playSound(...) ignore Entity#setSilent, so they are cancelled by where they come from.
    @SubscribeEvent
    public static void onSoundAtPosition(PlayLevelSoundEvent.AtPosition event) {
        if (contains(event.getPosition())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onSoundAtEntity(PlayLevelSoundEvent.AtEntity event) {
        if (contains(event.getEntity().position())) {
            event.setCanceled(true);
        }
    }
}
