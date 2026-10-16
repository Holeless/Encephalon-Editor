package com.example.encephalon.config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.encephalon.EncephalonMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import net.minecraft.world.entity.ai.goal.SwellGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;

/**
 * Attacks of mobs, for the editor's "Attacks" entry and for the damage overrides.
 * <p>
 * The attacks of a mob type are worked out from the mob itself (one throw-away instance on the server, once per type, goals
 * included), so modded mobs get theirs without a table:
 * <ul>
 * <li>MELEE: a melee goal, or (for a brain-driven mob such as a hoglin) its own attack damage. A mob that has no attack at all
 * (a cow, a villager) lists a melee row too, because it bites if it is made hostile; its default then comes from its size.
 * A mob that has other attacks (a creeper) does not list melee, since its attack damage attribute is never used.</li>
 * <li>EXPLOSION: a swell goal (creeper and mobs like it). The value is the most damage the blast does, at point blank; the
 * damage of a victim farther away is scaled by the same factor, the radius is not changed.</li>
 * <li>DAMAGE: attacks that hurt in another way (arrows, fireballs, sonic boom, ...). The built-in table below names the vanilla
 * ones with their damage; a ranged goal of any other mob is listed as "ranged" with a default that varies.</li>
 * <li>EFFECT: special attacks that do no damage of their own (teleporting, poison, summoning). Listed with a short description,
 * nothing to edit. Modded ones are found by the name of their goal class.</li>
 * </ul>
 * A mob with no ATTACK_DAMAGE attribute is given a fallback from the size of its hitbox (micro / small / normal / big) as a real
 * attribute, so anything that makes it attack (the editor's category setting, or another mod) does not crash on a missing one.
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class DamageDefaults {
    public static final String MELEE = "melee";
    public static final String EXPLOSION = "explosion";
    public static final String RANGED = "ranged";

    public enum Kind { MELEE, DAMAGE, EXPLOSION, EFFECT }

    /**
     * One attack of a mob. def is the default damage (negative: varies). type is the damage type it hurts with, null when it is
     * not one fixed type. label is a literal name for attacks found in a mod (null: the name comes from the lang file).
     */
    public record Attack(String id, double def, ResourceKey<DamageType> type, Kind kind, String label) {
        public Attack(String id, double def, ResourceKey<DamageType> type) {
            this(id, def, type, Kind.DAMAGE, null);
        }

        /** True if the attack has a damage value that can be set. */
        public boolean editable() {
            return kind != Kind.EFFECT;
        }
    }

    /** What a scan found for one mob type. melee is null when the mob has no melee attack of its own. */
    private record Profile(Attack melee, List<Attack> others) {
    }

    /** Vanilla attacks that hurt in another way than melee, with their damage. */
    private static final Map<String, List<Attack>> EXTRAS = Map.ofEntries(
            Map.entry("minecraft:warden", List.of(new Attack("sonic_boom", 10.0, DamageTypes.SONIC_BOOM))),
            Map.entry("minecraft:blaze", List.of(new Attack("fireball", 5.0, DamageTypes.FIREBALL))),
            Map.entry("minecraft:ghast", List.of(new Attack("fireball", 6.0, DamageTypes.FIREBALL))),
            Map.entry("minecraft:shulker", List.of(new Attack("bullet", 4.0, DamageTypes.MOB_PROJECTILE))),
            Map.entry("minecraft:evoker", List.of(new Attack("fangs", 6.0, DamageTypes.INDIRECT_MAGIC))),
            Map.entry("minecraft:guardian", List.of(new Attack("beam", 6.0, DamageTypes.INDIRECT_MAGIC))),
            Map.entry("minecraft:elder_guardian", List.of(new Attack("beam", 8.0, DamageTypes.INDIRECT_MAGIC))),
            Map.entry("minecraft:llama", List.of(new Attack("spit", 1.0, DamageTypes.SPIT))),
            Map.entry("minecraft:trader_llama", List.of(new Attack("spit", 1.0, DamageTypes.SPIT))),
            Map.entry("minecraft:wither", List.of(new Attack("skull", 8.0, DamageTypes.WITHER_SKULL))),
            Map.entry("minecraft:ender_dragon", List.of(new Attack("breath", -1, DamageTypes.INDIRECT_MAGIC))));

    /** Vanilla special attacks that do no damage of their own (ids have a name and a .desc in the lang file). */
    private static final Map<String, List<String>> SPECIAL = Map.ofEntries(
            Map.entry("minecraft:enderman", List.of("teleport")),
            Map.entry("minecraft:witch", List.of("potions")),
            Map.entry("minecraft:evoker", List.of("summon_vex")),
            Map.entry("minecraft:elder_guardian", List.of("curse")),
            Map.entry("minecraft:cave_spider", List.of("poison")),
            Map.entry("minecraft:husk", List.of("hunger")),
            Map.entry("minecraft:wither_skeleton", List.of("wither_effect")),
            Map.entry("minecraft:pufferfish", List.of("puff_poison")),
            Map.entry("minecraft:bee", List.of("bee_poison")),
            Map.entry("minecraft:snow_golem", List.of("snowball")),
            Map.entry("minecraft:ravager", List.of("roar")),
            Map.entry("minecraft:warden", List.of("darkness")),
            Map.entry("minecraft:ghast", List.of("ghast_explosion")),
            Map.entry("minecraft:wither", List.of("spawn_blast")),
            Map.entry("minecraft:ender_dragon", List.of("dragon_fireball", "dragon_charge", "dragon_crystals")));

    /** Mobs that have an attack damage attribute but never melee (they shoot, cast, explode or breathe instead). */
    private static final Set<String> NO_MELEE = Set.of("minecraft:creeper", "minecraft:wither", "minecraft:guardian",
            "minecraft:elder_guardian", "minecraft:evoker", "minecraft:shulker", "minecraft:witch", "minecraft:pillager",
            "minecraft:breeze", "minecraft:ghast");

    /** Melee damage of vanilla mobs whose hits are not a plain attack damage attribute. */
    private static final Map<String, Double> MELEE_DEFAULTS = Map.of("minecraft:ender_dragon", 10.0);

    /** Words in the name of a modded goal class that mark it as an attack, and words that rule it out. */
    private static final String[] ATTACK_WORDS = {"attack", "shoot", "spit", "breath", "sting", "blast", "explo", "fireball", "beam",
            "laser", "summon", "cast", "spell", "bite", "slam", "stomp", "roar", "charge", "pounce", "leap", "swoop", "smash",
            "strike", "throw", "lunge", "ram", "teleport", "poison"};
    private static final String[] NOT_ATTACK_WORDS = {"target", "avoid", "flee", "retreat", "panic", "look", "follow", "wander",
            "stroll", "float", "swim", "breed", "sleep", "tempt", "eat", "attackable"};
    private static final int MAX_SPECIAL = 6;

    /** Types that had no attack damage and were given the size fallback. */
    private static final Map<EntityType<?>, Double> FALLBACK = new ConcurrentHashMap<>();
    /** Scan results, per type (one throw-away mob each, the first time the editor asks). */
    private static final Map<EntityType<?>, Profile> PROFILES = new ConcurrentHashMap<>();
    private static final Field CREEPER_RADIUS = radiusField();

    private DamageDefaults() {
    }

    private static Field radiusField() {
        try {
            Field f = Creeper.class.getDeclaredField("explosionRadius");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /** Mod bus: gives every living type without an attack damage the fallback one. */
    @SubscribeEvent
    public static void onAttributes(EntityAttributeModificationEvent event) {
        for (EntityType<? extends LivingEntity> type : event.getTypes()) {
            if (type == EntityType.PLAYER || type == EntityType.ARMOR_STAND) {
                continue;
            }
            if (!event.has(type, Attributes.ATTACK_DAMAGE)) {
                double d = tierDamage(type);
                event.add(type, Attributes.ATTACK_DAMAGE, d);
                FALLBACK.put(type, d);
                // Attacking also reads these two; most mobs have them already.
                if (!event.has(type, Attributes.ATTACK_KNOCKBACK)) {
                    event.add(type, Attributes.ATTACK_KNOCKBACK);
                }
                if (!event.has(type, Attributes.FOLLOW_RANGE)) {
                    event.add(type, Attributes.FOLLOW_RANGE, 16.0);
                }
            }
        }
    }

    public static double volume(EntityType<?> t) {
        float w = t.getWidth();
        float h = t.getHeight();
        return (double) w * w * h;
    }

    /** "micro", "small", "normal" or "big". */
    public static String sizeClass(EntityType<?> t) {
        double v = volume(t);
        if (v < 0.125) {
            return "micro";
        }
        if (v < 0.45) {
            return "small";
        }
        if (v > 1.75) {
            return "big";
        }
        return "normal";
    }

    /** Fallback damage points for the size of the type (2 points = 1 heart). */
    public static double tierDamage(EntityType<?> t) {
        return switch (sizeClass(t)) {
            case "micro" -> 1.0;
            case "small" -> 2.0;
            case "big" -> 6.0;
            default -> 4.0;
        };
    }

    /** True if the type has no attack damage of its own, so its default comes from its size. */
    public static boolean isFallback(EntityType<?> t) {
        return FALLBACK.containsKey(t);
    }

    /** The default melee damage: the mob's own attribute if it has one, else the size fallback. */
    public static double defaultMelee(EntityType<?> t) {
        Double f = FALLBACK.get(t);
        if (f != null) {
            return f;
        }
        try {
            @SuppressWarnings("unchecked")
            AttributeSupplier s = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) t);
            if (s != null && s.hasAttribute(Attributes.ATTACK_DAMAGE)) {
                return s.getBaseValue(Attributes.ATTACK_DAMAGE);
            }
        } catch (RuntimeException | LinkageError ex) {
            // not a living type, or a mod's supplier misbehaves: use the size
        }
        return tierDamage(t);
    }

    private static boolean hasOwnAttackDamage(EntityType<?> t) {
        if (FALLBACK.containsKey(t)) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            AttributeSupplier s = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) t);
            return s != null && s.hasAttribute(Attributes.ATTACK_DAMAGE);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------ explosions

    /**
     * Most damage the blast of this mob does (at point blank, before armor and difficulty): vanilla's formula gives
     * 7 * (2 * radius) + 1. A creeper's radius is read from the creeper (and doubles when charged); anything else counts as 3.
     */
    public static double explosionMax(Mob mob) {
        float r = 3F;
        if (mob instanceof Creeper c) {
            if (CREEPER_RADIUS != null) {
                try {
                    r = CREEPER_RADIUS.getInt(c);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    r = 3F;
                }
            }
            if (c.isPowered()) {
                r *= 2F;
            }
        }
        return 14.0 * r + 1.0;
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Every attack of the type that is listed in the editor: melee first, then the others. Server thread (it may create one
     * throw-away mob the first time). forcedHostile: the category setting makes the mob attack although it normally does not,
     * so a melee row is listed even if it has other attacks.
     */
    public static List<Attack> attacksOf(MinecraftServer server, EntityType<?> t, boolean forcedHostile) {
        Profile p = PROFILES.get(t);
        if (p == null) {
            p = scan(server, t);
            PROFILES.put(t, p);
        }
        List<Attack> out = new ArrayList<>();
        if (p.melee() != null) {
            out.add(p.melee());
        } else if (p.others().isEmpty() || forcedHostile) {
            out.add(new Attack(MELEE, defaultMelee(t), null, Kind.MELEE, null));
        }
        out.addAll(p.others());
        return out;
    }

    private static Profile scan(MinecraftServer server, EntityType<?> t) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(t).toString();
        boolean vanilla = id.startsWith("minecraft:");
        List<Attack> others = new ArrayList<>();
        List<Attack> extra = EXTRAS.get(id);
        if (extra != null) {
            others.addAll(extra);
        }
        List<String> special = SPECIAL.get(id);
        if (special != null) {
            for (String s : special) {
                others.add(new Attack(s, -1, null, Kind.EFFECT, null));
            }
        }
        boolean meleeGoal = false;
        Entity e = null;
        try {
            e = t.create(server.overworld());
            if (e instanceof Mob mob) {
                boolean ranged = false;
                boolean swell = false;
                Set<String> seen = new HashSet<>();
                List<Attack> found = new ArrayList<>();
                for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) {
                    Goal g = w.getGoal();
                    if (g instanceof MeleeAttackGoal) {
                        meleeGoal = true;
                    } else if (g instanceof RangedAttackGoal || g instanceof RangedBowAttackGoal<?>
                            || g instanceof RangedCrossbowAttackGoal<?>) {
                        ranged = true;
                    } else if (g instanceof SwellGoal) {
                        swell = true;
                    } else if (!vanilla && found.size() < MAX_SPECIAL) {
                        String name = specialName(g);
                        if (name != null && seen.add(name)) {
                            found.add(new Attack("special_" + name.toLowerCase().replaceAll("[^a-z0-9]+", "_"), -1, null,
                                    Kind.EFFECT, name));
                        }
                    }
                }
                if (swell) {
                    others.add(0, new Attack(EXPLOSION, Math.round(explosionMax(mob)), DamageTypes.EXPLOSION, Kind.EXPLOSION, null));
                }
                // Potions and snowballs are not projectile damage, so the generic ranged row is only for the rest.
                if (ranged && extra == null && special == null) {
                    others.add(new Attack(RANGED, -1, null, Kind.DAMAGE, null));
                }
                others.addAll(found);
                // Melee: a melee goal, or a real attack damage on a mob that does not explode (a blaze, warden, phantom or vex
                // fight up close with their own code, not with a melee goal). A few vanilla mobs have the attribute but never melee.
                Double fixed = MELEE_DEFAULTS.get(id);
                boolean ownAttack = fixed != null || meleeGoal
                        || (!swell && !NO_MELEE.contains(id) && hasOwnAttackDamage(t));
                Attack melee = ownAttack
                        ? new Attack(MELEE, fixed != null ? fixed : defaultMelee(t), null, Kind.MELEE, null) : null;
                return new Profile(melee, List.copyOf(others));
            }
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not work out the attacks of {}", id, ex);
        } finally {
            if (e != null) {
                e.discard();
            }
        }
        return new Profile(null, List.copyOf(others));
    }

    /** A readable name for a modded goal that looks like an attack ("FireBreathGoal" -> "Fire Breath"), else null. */
    private static String specialName(Goal g) {
        String raw = g.getClass().getName();
        if (raw.startsWith("net.minecraft.")) {
            return null;
        }
        String n = raw.substring(Math.max(raw.lastIndexOf('.'), raw.lastIndexOf('$')) + 1);
        if (n.isEmpty() || Character.isDigit(n.charAt(0))) {
            return null; // anonymous class: nothing readable
        }
        String lower = n.toLowerCase();
        for (String bad : NOT_ATTACK_WORDS) {
            if (lower.contains(bad)) {
                return null;
            }
        }
        boolean match = false;
        for (String word : ATTACK_WORDS) {
            if (lower.contains(word)) {
                match = true;
                break;
            }
        }
        if (!match) {
            return null;
        }
        if (n.endsWith("Goal") && n.length() > 4) {
            n = n.substring(0, n.length() - 4);
        }
        return n.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
    }

    // ------------------------------------------------------------------ names

    /** The name of an attack in the editor. */
    public static Component nameOf(Attack a) {
        return a.label() != null ? Component.literal(a.label()) : Component.translatable("encephalon.attack." + a.id());
    }

    /** The short description of a special attack (the ones that do no damage). */
    public static Component describe(Attack a) {
        return a.label() != null
                ? Component.translatable("encephalon.attack.generic_special", a.label())
                : Component.translatable("encephalon.attack." + a.id() + ".desc");
    }

    // ------------------------------------------------------------------ damage sources

    /** Which attack of the mob a damage source is, or null if it is none of the listed ones. */
    public static String attackOf(Mob attacker, DamageSource src) {
        List<Attack> extra = EXTRAS.get(BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString());
        if (extra != null) {
            for (Attack a : extra) {
                if (src.is(a.type())) {
                    return a.id();
                }
            }
        }
        if (src.getDirectEntity() == attacker
                && (src.is(DamageTypes.MOB_ATTACK) || src.is(DamageTypes.MOB_ATTACK_NO_AGGRO) || src.is(DamageTypes.STING))) {
            return MELEE;
        }
        if (src.getEntity() == attacker && (src.is(DamageTypes.EXPLOSION) || src.is(DamageTypes.PLAYER_EXPLOSION))) {
            return EXPLOSION;
        }
        if (src.getEntity() == attacker && src.getDirectEntity() instanceof Projectile) {
            return RANGED;
        }
        return null;
    }
}
