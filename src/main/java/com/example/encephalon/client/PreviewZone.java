package com.example.encephalon.client;

import com.example.encephalon.EncephalonMod;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/**
 * The column of sky where the Mob Editor parks the previews (and the server-side twins of modded mobs, see MobEditorScreen).
 * Anything that appears in it while the editor is open belongs to the preview: the twin, and whatever it spawns
 * (body segments, a detached head, a portal, ...). Those must not hunt the player, take damage or make noise.
 * Sounds are silenced by position rather than per mob, so it works for any mod. Uses no client classes, so it is safe on any side.
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class PreviewZone {
    /** Null while the editor is closed. */
    static volatile AABB zone;

    private PreviewZone() {
    }

    static boolean contains(Vec3 pos) {
        AABB z = zone;
        return z != null && z.contains(pos);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (e instanceof Player || !contains(e.position())) {
            return;
        }
        e.setSilent(true); // both sides: the client copy of a part makes its own noises
        if (!event.getLevel().isClientSide()) {
            e.setInvulnerable(true);
            e.setNoGravity(true);
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
