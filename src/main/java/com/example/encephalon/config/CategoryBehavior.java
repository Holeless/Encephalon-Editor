package com.example.encephalon.config;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.example.encephalon.EncephalonMod;
import com.example.encephalon.scope.MobGroups;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * What the Category setting of a mob type does in the game (it does not move the mob in the editor's list).
 * <ul>
 * <li>Hostile: attacks players on sight, and fights back.</li>
 * <li>Neutral: never starts a fight, but fights whoever hurt it.</li>
 * <li>Passive: never targets anything and never hurts anyone in melee.</li>
 * </ul>
 * Only a category that differs from the mob's default one changes anything. A mob that is made to attack but has no attack of
 * its own gets a melee goal; its damage is its default damage (see DamageDefaults). The goals are added once to a mob and check
 * the setting every time they are asked, so changing or resetting the category takes effect at once, on mobs that already exist.
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class CategoryBehavior {
    /** Mobs that already have the editor's goals (the goals are not saved, so a reloaded mob gets them again on join). */
    private static final Map<Mob, Boolean> ARMED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Neutral mobs: who they were provoked by. */
    private static final Map<Mob, UUID> PROVOKED = Collections.synchronizedMap(new WeakHashMap<>());

    private CategoryBehavior() {
    }

    /** "hostile", "neutral" or "passive" if the mob's category was changed in the editor to something other than its default, else null. */
    public static String mode(Mob mob) {
        MinecraftServer server = mob.getServer();
        if (server == null) {
            return null;
        }
        MobConfigData data = MobConfigData.get(server);
        if (!data.anyCategory()) {
            return null;
        }
        String own = data.category(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
        if (own == null) {
            return null;
        }
        String def = MobGroups.categoryOf(mob.getType());
        return def == null || own.equals(def) ? null : own;
    }

    static void provoke(Mob victim, LivingEntity attacker) {
        if (mode(victim) != null) {
            PROVOKED.put(victim, attacker.getUUID());
        }
    }

    private static boolean provokedBy(Mob mob, LivingEntity target) {
        if (target == mob.getLastHurtByMob()) {
            return true;
        }
        UUID id = PROVOKED.get(mob);
        return id != null && id.equals(target.getUUID());
    }

    private static boolean hasNativeAttack(Mob mob) {
        for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) {
            Goal g = w.getGoal();
            if (g instanceof MeleeAttackGoal || g instanceof RangedAttackGoal || g instanceof RangedBowAttackGoal<?>
                    || g instanceof RangedCrossbowAttackGoal<?>) {
                return true;
            }
        }
        return false;
    }

    /** Adds the editor's goals to a mob (once). */
    public static void arm(Mob mob) {
        if (mob.level().isClientSide() || ARMED.put(mob, Boolean.TRUE) != null) {
            return;
        }
        boolean nativeAttack = hasNativeAttack(mob);
        mob.targetSelector.addGoal(1, new Calm(mob));
        mob.targetSelector.addGoal(3, new Hunt(mob));
        if (mob instanceof PathfinderMob pm) {
            mob.targetSelector.addGoal(2, new Retaliate(pm));
            if (!nativeAttack) {
                // Priority 0 so a mob that normally runs from danger (a cow) fights instead once it is made to.
                mob.goalSelector.addGoal(0, new Bite(pm));
            }
        }
    }

    /** Server thread: arms every loaded mob of the type that is now set to behave differently. */
    public static void refresh(MinecraftServer server, String mobId) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Mob m && BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString().equals(mobId)
                        && mode(m) != null) {
                    arm(m);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (mode(mob) != null) {
            arm(mob);
        }
    }

    /** Drops targets a Passive mob (or a Neutral one that was not provoked) must not have. Never runs, it only checks. */
    private static final class Calm extends Goal {
        private final Mob mob;

        Calm(Mob mob) {
            this.mob = mob;
        }

        @Override
        public boolean canUse() {
            LivingEntity target = mob.getTarget();
            if (target != null) {
                String m = mode(mob);
                if ("passive".equals(m) || ("neutral".equals(m) && !provokedBy(mob, target))) {
                    mob.setTarget(null);
                    mob.setAggressive(false);
                }
            }
            return false;
        }
    }

    /** Hostile: goes after players on sight. */
    private static final class Hunt extends NearestAttackableTargetGoal<Player> {
        Hunt(Mob mob) {
            super(mob, Player.class, true);
        }

        @Override
        public boolean canUse() {
            return "hostile".equals(mode(this.mob)) && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return "hostile".equals(mode(this.mob)) && super.canContinueToUse();
        }
    }

    /** Hostile and Neutral: fights back against whoever hurt it. */
    private static final class Retaliate extends HurtByTargetGoal {
        Retaliate(PathfinderMob mob) {
            super(mob);
        }

        private boolean on() {
            String m = mode(this.mob);
            return "hostile".equals(m) || "neutral".equals(m);
        }

        @Override
        public boolean canUse() {
            return on() && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return on() && super.canContinueToUse();
        }
    }

    /** Melee attack for a mob that has none of its own. */
    private static final class Bite extends MeleeAttackGoal {
        Bite(PathfinderMob mob) {
            super(mob, 1.2D, true);
        }

        private boolean on() {
            String m = mode(this.mob);
            return "hostile".equals(m) || "neutral".equals(m);
        }

        @Override
        public boolean canUse() {
            return on() && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return on() && super.canContinueToUse();
        }
    }
}
