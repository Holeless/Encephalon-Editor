package com.example.encephalon.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.encephalon.EncephalonMod;
import com.example.encephalon.config.DamageDefaults.Attack;
import com.example.encephalon.config.DamageDefaults.Kind;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

/**
 * What the editor shows in its two lists, kept strictly apart:
 * <ul>
 * <li>Attacks (Combat): only real attacks. A damage row is generated for a mob only when it has no attack of its own at all (no goal,
 * no explosion like the creeper, no verified attack found in its code); a mob that has an attack damage attribute shows that value.
 * Attacks that were only guessed from a name or an animation are dropped: an animation is not an attack.</li>
 * <li>Behaviors: what can be edited (the numbers a mod exposes in its own config for that mob, and the duration and
 * level of every effect the mob's code applies, read from its bytecode), followed by reference-only rows for what its built-in AI
 * does (goals, brain behaviors, see BehaviorScan: an allay's item delivery, dancing, duplication).</li>
 * </ul>
 * Values are stored in MobConfigData's damage map under ids that start with cfg_ (mod config) or fx_ (effects).
 */
public final class CombatInfo {
    public static final String FX_DURATION = "duration";
    public static final String FX_LEVEL = "level";
    private static final String DUR_ID = "fx_duration:";
    private static final String LVL_ID = "fx_level:";
    private static final String VERIFIED = "(read from its code)";

    private static final Map<EntityType<?>, List<Attack>> BEHAVIORS = new ConcurrentHashMap<>();
    private static final Map<EntityType<?>, List<Attack>> ATTACKS = new ConcurrentHashMap<>();

    private CombatInfo() {
    }

    public static boolean isBehaviorId(String id) {
        return id.startsWith(DamageDefaults.CONFIG_PREFIX) || id.startsWith("fx_");
    }

    /** The stored value of an effect's duration (seconds) or level for a mob type, or null. */
    public static Double effectValue(MobConfigData d, String mobId, String kind, String effectId) {
        return d.damage(mobId, (FX_DURATION.equals(kind) ? DUR_ID : LVL_ID) + effectId);
    }

    /** Attacks only (see the class comment). Server thread. */
    public static List<Attack> attacksOf(MinecraftServer server, EntityType<?> t, boolean forced) {
        List<Attack> out = new ArrayList<>();
        boolean melee = false;
        boolean real = false;
        for (Attack a : DamageDefaults.attacksOf(server, t, forced)) {
            if (isBehaviorId(a.id())) {
                continue;
            }
            if (a.id().startsWith("special_") && (a.desc() == null || !a.desc().endsWith(VERIFIED))) {
                continue; // guessed from a name or an animation: not an attack
            }
            if (a.kind() == Kind.MELEE) {
                melee = true;
            } else {
                real = true;
            }
            out.add(a);
        }
        if (!melee && !real) {
            out.add(0, new Attack(DamageDefaults.MELEE, DamageDefaults.defaultMelee(t), null, Kind.MELEE, null));
        }
        ATTACKS.put(t, List.copyOf(out));
        return out;
    }

    /** Editable behavior rows: mod config values, then effect durations and levels. Server thread; cached per type. */
    public static List<Attack> behaviorsOf(MinecraftServer server, EntityType<?> t) {
        List<Attack> cached = BEHAVIORS.get(t);
        if (cached != null) {
            return cached;
        }
        List<Attack> out = new ArrayList<>();
        try {
            for (Attack a : DamageDefaults.attacksOf(server, t, false)) {
                if (a.id().startsWith(DamageDefaults.CONFIG_PREFIX)) {
                    out.add(a);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not read config rows of {}", t, ex);
        }
        try {
            for (EffectScan.Use u : EffectScan.scan(classesOf(server, t))) {
                ResourceLocation rl = ResourceLocation.tryParse(u.effectId());
                var effect = rl == null ? null : BuiltInRegistries.MOB_EFFECT.getOptional(rl).orElse(null);
                String name = effect == null ? u.effectId() : effect.getDisplayName().getString();
                out.add(new Attack(DUR_ID + u.effectId(), u.ticks() < 0 ? -1 : u.ticks() / 20.0, null, Kind.DAMAGE,
                        name + " duration (seconds)", null));
                out.add(new Attack(LVL_ID + u.effectId(), u.amp() < 0 ? -1 : u.amp() + 1, null, Kind.DAMAGE,
                        name + " level", null));
            }
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not read the effects of {}", t, ex);
        }
        try {
            for (BehaviorScan.Info i : aiOf(server, t)) {
                String key = i.label().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
                out.add(new Attack("ai_" + key, -1, null, Kind.EFFECT, i.label(), i.desc()));
            }
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not read the AI of {}", t, ex);
        }
        List<Attack> done = List.copyOf(out);
        BEHAVIORS.put(t, done);
        return done;
    }

    /** What the mob's built-in AI does (goals, brain behaviors, notes): reference rows, nothing to edit. */
    private static List<BehaviorScan.Info> aiOf(MinecraftServer server, EntityType<?> t) {
        Entity e = null;
        try {
            e = t.create(server.overworld());
            if (e == null) {
                return List.of();
            }
            List<net.minecraft.world.entity.ai.goal.Goal> goals = new ArrayList<>();
            if (e instanceof Mob mob) {
                for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) {
                    goals.add(w.getGoal());
                }
                for (WrappedGoal w : mob.targetSelector.getAvailableGoals()) {
                    goals.add(w.getGoal());
                }
            }
            return BehaviorScan.scan(e.getClass(), goals, BuiltInRegistries.ENTITY_TYPE.getKey(t).toString());
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        } finally {
            if (e != null) {
                e.discard();
            }
        }
    }

    /** The already scanned behavior rows of a type, or an empty list (never scans: safe on the render thread). */
    public static List<Attack> cachedBehaviors(EntityType<?> t) {
        List<Attack> l = BEHAVIORS.get(t);
        return l == null ? List.of() : l;
    }

    private static Set<Class<?>> classesOf(MinecraftServer server, EntityType<?> t) {
        Set<Class<?>> out = new LinkedHashSet<>();
        Entity e = null;
        try {
            e = t.create(server.overworld());
            if (e == null) {
                return out;
            }
            for (Class<?> k = e.getClass(); k != null; k = k.getSuperclass()) {
                if (k.getName().startsWith("net.minecraft.world.entity.") && EffectScan.stopNames().contains(k.getSimpleName())) {
                    break;
                }
                out.add(k);
                try {
                    out.addAll(List.of(k.getDeclaredClasses()));
                } catch (LinkageError | RuntimeException ex) {
                    // skip
                }
            }
            if (e instanceof Mob mob) {
                for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) {
                    Class<?> g = w.getGoal().getClass();
                    if (!g.getName().startsWith("net.minecraft.")) {
                        out.add(g);
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            // use what was found
        } finally {
            if (e != null) {
                e.discard();
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ stored values

    public static boolean hasBehaviorValues(MobConfigData d, String mobId) {
        EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(mobId)).orElse(null);
        if (t == null) {
            return false;
        }
        for (Attack a : cachedBehaviors(t)) {
            if (d.damage(mobId, a.id()) != null) {
                return true;
            }
        }
        return false;
    }

    public static void clearBehaviorValues(MobConfigData d, String mobId) {
        EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(mobId)).orElse(null);
        if (t == null) {
            return;
        }
        for (Attack a : cachedBehaviors(t)) {
            d.clearDamage(mobId, a.id());
        }
    }

    /** True if an already listed attack of the mob has a stored value. */
    public static boolean hasAttackValues(MobConfigData d, String mobId) {
        EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(mobId)).orElse(null);
        if (t == null) {
            return false;
        }
        if (!ATTACKS.containsKey(t)) {
            return d.hasDamages(mobId);
        }
        for (Attack a : ATTACKS.get(t)) {
            if (d.damage(mobId, a.id()) != null) {
                return true;
            }
        }
        return false;
    }

    /** Clears the stored attack values (every row of the mob's attack list), leaving behavior values alone. */
    public static void clearAttackValues(MobConfigData d, String mobId, List<Attack> attacks) {
        for (Attack a : attacks) {
            d.clearDamage(mobId, a.id());
        }
    }
}
