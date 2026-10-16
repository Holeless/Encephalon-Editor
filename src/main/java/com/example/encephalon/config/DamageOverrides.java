package com.example.encephalon.config;

import com.example.encephalon.EncephalonMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Applies the attack damage set in the Mob Editor. Melee and other attacks that hurt: the value is the damage on Normal
 * difficulty; against players it is scaled the way vanilla scales mob damage (Easy less, Hard more). Explosions (a creeper):
 * the value is the most the blast does at point blank, and the damage the victim really takes is scaled by value / default, so
 * the fall-off with distance and the difficulty scaling stay as they are. Also keeps mobs set to Passive from hurting anyone in
 * melee, and notes who hurt a mob (a Neutral mob keeps fighting whoever provoked it).
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class DamageOverrides {
    private DamageOverrides() {
    }

    @SubscribeEvent
    public static void onIncoming(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) {
            return;
        }
        DamageSource src = event.getSource();
        if (victim instanceof Mob m && src.getEntity() instanceof LivingEntity a && a != m) {
            CategoryBehavior.provoke(m, a);
        }
        if (!(src.getEntity() instanceof Mob attacker)) {
            return;
        }
        MinecraftServer server = attacker.getServer();
        if (server == null) {
            return;
        }
        String attack = DamageDefaults.attackOf(attacker, src);
        if (attack == null) {
            return;
        }
        if (DamageDefaults.MELEE.equals(attack) && "passive".equals(CategoryBehavior.mode(attacker))) {
            event.setCanceled(true);
            return;
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString();
        Double v = MobConfigData.get(server).damage(id, attack);
        if (v == null) {
            return;
        }
        if (DamageDefaults.EXPLOSION.equals(attack)) {
            double def = DamageDefaults.explosionMax(attacker);
            if (def > 0.0) {
                event.setAmount((float) (event.getAmount() * v / def));
            }
            return;
        }
        double amount = v;
        if (victim instanceof Player && src.scalesWithDifficulty()) {
            Difficulty d = attacker.level().getDifficulty();
            amount = switch (d) {
                case PEACEFUL -> 0.0;
                case EASY -> Math.min(amount / 2.0 + 1.0, amount);
                case HARD -> amount * 1.5;
                default -> amount;
            };
        }
        event.setAmount((float) amount);
    }
}
