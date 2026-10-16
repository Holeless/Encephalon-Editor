package com.example.encephalon.client;

import com.example.encephalon.EncephalonMod;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/**
 * The patch of sky where the Mob Editor parks the server-side twin of a modded mob (see MobEditorScreen).
 * Anything that appears in it while the editor is open belongs to the preview: the twin, and whatever it spawns
 * (body segments, a detached head, ...). Those must not hunt the player or take damage, so they are made
 * invulnerable and stripped of targets. Uses no client classes, so it is safe on any side.
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class PreviewZone {
    /** Null while no preview twin exists. */
    static volatile AABB zone;

    private PreviewZone() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        AABB z = zone;
        Entity e = event.getEntity();
        if (z == null || event.getLevel().isClientSide() || e instanceof Player || !z.contains(e.position())) {
            return;
        }
        e.setInvulnerable(true);
        e.setNoGravity(true);
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        AABB z = zone;
        if (z != null && z.contains(event.getEntity().position())) {
            event.setCanceled(true);
        }
    }
}
