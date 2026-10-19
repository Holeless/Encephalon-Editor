package com.example.encephalon.config;

import com.example.encephalon.EncephalonMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * Applies the effect duration / level set in the Mob Editor (Behaviors). When a mob that has a value stored for an effect
 * applies that effect to something, the effect is replaced by one with the edited duration (seconds) and level. Only effects
 * whose numbers were found in the mob's code are listed in the editor, so nothing else is touched.
 * The mob is the effect source the mod passed in; if the mod passed none, the mob that last hurt the victim a moment ago.
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class EffectOverrides {
    private static final ThreadLocal<Boolean> GUARD = ThreadLocal.withInitial(() -> false);

    private EffectOverrides() {
    }

    @SubscribeEvent
    public static void onAdded(MobEffectEvent.Added event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || GUARD.get()) {
            return;
        }
        MobEffectInstance inst = event.getEffectInstance();
        Mob mob = null;
        Entity src = event.getEffectSource();
        if (src instanceof Mob m) {
            mob = m;
        } else if (target.getLastHurtByMob() instanceof Mob m && target.tickCount - target.getLastHurtByMobTimestamp() < 10) {
            mob = m;
        }
        if (mob == null) {
            return;
        }
        MinecraftServer server = mob.getServer();
        if (server == null) {
            return;
        }
        MobConfigData data = MobConfigData.get(server);
        String effectId = inst.getEffect().unwrapKey().map(k -> k.location().toString()).orElse(null);
        if (effectId == null) {
            return;
        }
        String mobId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        Double seconds = CombatInfo.effectValue(data, mobId, CombatInfo.FX_DURATION, effectId);
        Double level = CombatInfo.effectValue(data, mobId, CombatInfo.FX_LEVEL, effectId);
        if (seconds == null && level == null) {
            return;
        }
        int duration = seconds != null ? (int) Math.max(1, Math.round(seconds * 20.0)) : inst.getDuration();
        int amplifier = level != null ? (int) Math.max(0, Math.round(level) - 1) : inst.getAmplifier();
        if (duration == inst.getDuration() && amplifier == inst.getAmplifier()) {
            return;
        }
        MobEffectInstance changed = new MobEffectInstance(inst.getEffect(), duration, amplifier, inst.isAmbient(), inst.isVisible(),
                inst.showIcon());
        GUARD.set(true);
        try {
            target.removeEffect(inst.getEffect());
            target.addEffect(changed, src != null ? src : mob);
        } finally {
            GUARD.set(false);
        }
    }
}
