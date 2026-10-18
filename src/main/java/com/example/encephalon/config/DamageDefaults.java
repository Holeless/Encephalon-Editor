package com.example.encephalon.config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
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
 * nothing to edit. Modded ones are found by several methods (see moddedNames): goal classes (and what they hold), classes
 * nested in the entity, classes of the mod's jar that carry the mob's name or sit in a package named after it, attack-like
 * methods and animation fields of the entity class.</li>
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
    public record Attack(String id, double def, ResourceKey<DamageType> type, Kind kind, String label, String desc) {
        public Attack(String id, double def, ResourceKey<DamageType> type, Kind kind, String label) {
            this(id, def, type, kind, label, null);
        }

        public Attack(String id, double def, ResourceKey<DamageType> type) {
            this(id, def, type, Kind.DAMAGE, null, null);
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

    /** Words in the name of a modded goal / class / method that mark it as an attack, and words that rule it out. */
    private static final String[] ATTACK_WORDS = {"attack", "shoot", "spit", "breath", "sting", "blast", "explo", "fireball", "beam",
            "laser", "summon", "cast", "spell", "bite", "slam", "stomp", "roar", "charge", "pounce", "leap", "swoop", "smash",
            "strike", "throw", "lunge", "ram", "teleport", "poison", "action", "ability", "skill", "special",
            "missile", "projectile", "bolt", "nova", "wave", "barrage", "rain", "burst", "pulse", "slash", "swipe", "claw",
            "dash", "dive", "crush", "sweep", "volley", "orb", "meteor", "lightning", "curse", "drain", "shockwave", "quake",
            "grab", "fling", "hurl", "bomb", "spawn_", "minion", "ultimate", "combo", "rush", "cleave", "impale", "spike"};
    private static final String[] NOT_ATTACK_WORDS = {"target", "avoid", "flee", "retreat", "panic", "look", "follow", "wander",
            "stroll", "float", "swim", "breed", "sleep", "tempt", "eat", "attackable"};
    /** Class names with these words are rendering, networking or registration code, never an attack. */
    private static final String[] NOT_CLASS_WORDS = {"render", "model", "layer", "packet", "message", "event", "sound", "particle",
            "registry", "register", "handler", "config", "entity", "client", "screen", "gui", "data", "provider", "recipe",
            "loot", "tag", "mixin", "attribute", "effect", "item", "block", "spawnegg", "egg", "bossbar", "bar"};
    private static final int MAX_SPECIAL = 12;
    /** Goal names that only wrap the real attacks (an "ActionGoal" holding a list of actions): the actions are looked for inside. */
    private static final String[] WRAPPER_WORDS = {"action", "ability", "skill", "special", "spell"};
    /** Names too generic to show as an attack. */
    private static final Set<String> GENERIC = Set.of("action", "ability", "skill", "special", "attack", "spell", "boss", "custom",
            "cooldown", "mob", "entity", "default", "phase", "move", "ai", "task");
    /** Verbs an entity class's attack methods often start with. */
    private static final String[] METHOD_PREFIXES = {"do", "start", "perform", "cast", "use", "shoot", "summon", "trigger",
            "begin", "execute", "fire", "launch", "spawn", "throw", "unleash", "release", "tryto", "try"};
    /** Package segments that usually hold a mod's attacks / AI. */
    private static final String[] ATTACK_PACKAGES = {".goal", ".goals", ".ai", ".attack", ".attacks", ".ability", ".abilities",
            ".action", ".actions", ".move", ".moves", ".skill", ".skills", ".spell", ".spells", ".behavior", ".behaviour",
            ".behaviors", ".behaviours", ".phase", ".phases"};

    /** Types that had no attack damage and were given the size fallback. */
    private static final Map<EntityType<?>, Double> FALLBACK = new ConcurrentHashMap<>();
    /** Scan results, per type (one throw-away mob each, the first time the editor asks). */
    private static final Map<EntityType<?>, Profile> PROFILES = new ConcurrentHashMap<>();
    /** Class names of one mod's jar, per mod id (read once from the mod's scan data, nothing is loaded). */
    private static final Map<String, List<String>> MOD_CLASSES = new ConcurrentHashMap<>();
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
        List<String[]> configured = EncephalonConfig.extraAttacks(t);
        for (String[] c : configured) {
            others.add(new Attack("custom_" + c[0].toLowerCase().replaceAll("[^a-z0-9]+", "_"), -1, null, Kind.EFFECT, c[0],
                    c[1].isEmpty() ? null : c[1]));
        }
        boolean meleeGoal = false;
        boolean ranged = false;
        boolean swell = false;
        boolean isMob = false;
        double blast = 0.0;
        List<String> goalClasses = new ArrayList<>();
        // Found names, in order, without repeats (a name found by two methods is listed once).
        Map<String, String> found = new java.util.LinkedHashMap<>();
        Entity e = null;
        try {
            e = t.create(server.overworld());
            if (e instanceof Mob mob) {
                isMob = true;
                for (WrappedGoal w : mob.goalSelector.getAvailableGoals()) {
                    Goal g = w.getGoal();
                    if (g instanceof MeleeAttackGoal) {
                        meleeGoal = true;
                    } else if (g instanceof RangedAttackGoal || g instanceof RangedBowAttackGoal<?>
                            || g instanceof RangedCrossbowAttackGoal<?>) {
                        ranged = true;
                    } else if (g instanceof SwellGoal) {
                        swell = true;
                    } else if (!vanilla) {
                        goalClasses.add(g.getClass().getName());
                        for (String name : specialNames(g)) {
                            addFound(found, name);
                        }
                    }
                }
                if (swell) {
                    blast = Math.round(explosionMax(mob));
                }
            }
            if (!vanilla && e != null) {
                for (String name : moddedNames(t, e.getClass(), id)) {
                    addFound(found, name);
                }
            } else if (!vanilla) {
                for (String name : moddedNames(t, null, id)) {
                    addFound(found, name);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not work out the attacks of {}", id, ex);
            if (!vanilla && found.isEmpty()) {
                try {
                    for (String name : moddedNames(t, null, id)) {
                        addFound(found, name);
                    }
                } catch (RuntimeException | LinkageError ex2) {
                    // nothing more to try
                }
            }
        } finally {
            if (e != null) {
                e.discard();
            }
        }
        if (!vanilla) {
            EncephalonMod.LOGGER.info("Mob Editor attacks: {} has goals {} -> found {}", id, goalClasses, found.values());
        }
        if (swell) {
            others.add(0, new Attack(EXPLOSION, blast, DamageTypes.EXPLOSION, Kind.EXPLOSION, null));
        }
        // Potions and snowballs are not projectile damage, so the generic ranged row is only for the rest.
        if (ranged && extra == null && special == null) {
            others.add(new Attack(RANGED, -1, null, Kind.DAMAGE, null));
        }
        for (Map.Entry<String, String> f : found.entrySet()) {
            if (others.size() >= MAX_SPECIAL + 4) {
                break;
            }
            others.add(new Attack("special_" + f.getKey().replaceAll("[^a-z0-9]+", "_"), -1, null, Kind.EFFECT, f.getValue()));
        }
        if (!vanilla && others.isEmpty() && EncephalonConfig.isQuiet(t)) {
            // A modded boss whose attacks live in the mod's own code: say so instead of showing only a made-up default.
            others.add(new Attack("custom_code", -1, null, Kind.EFFECT, null));
        }
        if (!isMob) {
            return new Profile(null, List.copyOf(others));
        }
        // Melee: a melee goal, or a real attack damage on a mob that does not explode (a blaze, warden, phantom or vex
        // fight up close with their own code, not with a melee goal). A few vanilla mobs have the attribute but never melee.
        Double fixed = MELEE_DEFAULTS.get(id);
        // A mob that explodes (a creeper has a melee goal too) never lists melee.
        boolean ownAttack = fixed != null
                || (!swell && (meleeGoal || (!NO_MELEE.contains(id) && hasOwnAttackDamage(t))));
        Attack melee = ownAttack
                ? new Attack(MELEE, fixed != null ? fixed : defaultMelee(t), null, Kind.MELEE, null) : null;
        return new Profile(melee, List.copyOf(others));
    }

    private static void addFound(Map<String, String> found, String name) {
        if (name == null || name.isBlank() || found.size() >= MAX_SPECIAL) {
            return;
        }
        found.putIfAbsent(name.toLowerCase(Locale.ROOT).trim(), name.trim());
    }

    private static boolean isEngine(String cn) {
        return cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.") || cn.startsWith("sun.")
                || cn.startsWith("net.minecraft.") || cn.startsWith("net.neoforged.") || cn.startsWith("com.mojang.")
                || cn.startsWith("kotlin.") || cn.startsWith("org.slf4j.") || cn.startsWith("it.unimi.") || cn.startsWith("com.google.")
                || cn.startsWith("software.bernie.") || cn.startsWith("org.joml.") || cn.startsWith("org.objectweb.");
    }

    /** The plain class name ("MissileAction"), or null for anonymous classes and lambdas. */
    private static String simpleName(String raw) {
        String n = raw.substring(Math.max(raw.lastIndexOf('.'), raw.lastIndexOf('$')) + 1);
        if (n.isEmpty() || Character.isDigit(n.charAt(0)) || raw.contains("Lambda")) {
            return null;
        }
        return n;
    }

    private static boolean hasWord(String lower, String[] words) {
        for (String w : words) {
            if (lower.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** "FireBreathGoal" -> "Fire Breath", "MissileAction" -> "Missile", "SLAM_ATTACK" -> "Slam Attack". */
    private static String readable(String n) {
        if (n.endsWith("Goal") && n.length() > 4) {
            n = n.substring(0, n.length() - 4);
        }
        if (n.endsWith("Action") && n.length() > 6) {
            n = n.substring(0, n.length() - 6);
        }
        if (n.indexOf('_') >= 0 || n.equals(n.toUpperCase(Locale.ROOT))) {
            StringBuilder sb = new StringBuilder();
            for (String part : n.toLowerCase(Locale.ROOT).split("_+")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            return sb.toString();
        }
        String spaced = n.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static boolean genericName(String readable) {
        return GENERIC.contains(readable.toLowerCase(Locale.ROOT).replace(" ", ""));
    }

    /**
     * Readable names for a modded goal that looks like an attack. A goal named after an attack gives its own name. A goal that
     * only wraps actions (an ActionGoal) is searched: the classes of the objects it holds, two levels down, are the attacks.
     * Any other modded goal is searched one level down too, keeping only the held objects whose class names look like attacks
     * (a "BossAttackGoal" holding a list of AttackPattern objects, say).
     */
    private static List<String> specialNames(Goal g) {
        String raw = g.getClass().getName();
        if (isEngine(raw)) {
            return List.of();
        }
        String n = simpleName(raw);
        if (n == null) {
            return List.of();
        }
        String lower = n.toLowerCase(Locale.ROOT);
        if (hasWord(lower, NOT_ATTACK_WORDS)) {
            return List.of();
        }
        boolean attackLike = hasWord(lower, ATTACK_WORDS);
        List<String> out = new ArrayList<>();
        if (attackLike) {
            String base = readable(n);
            if (!genericName(base)) {
                out.add(base);
            }
        }
        boolean wrapper = hasWord(lower, WRAPPER_WORDS);
        Set<String> dug = new LinkedHashSet<>();
        dive(g, wrapper ? 2 : 1, wrapper ? 2 : 1, !wrapper, dug, Collections.newSetFromMap(new IdentityHashMap<>()));
        for (String d : dug) {
            if (!out.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * Collects readable class names of the mod's objects held (in fields, lists) by o, down to the given depth.
     * top is the depth the goal itself is at (its own name is not collected). onlyAttackLike keeps just the names that
     * contain an attack word.
     */
    private static void dive(Object o, int depth, int top, boolean onlyAttackLike, Set<String> out, Set<Object> seen) {
        if (o == null || depth < 0 || out.size() >= MAX_SPECIAL || !seen.add(o) || o instanceof Entity || o instanceof Level) {
            return;
        }
        String cn = o.getClass().getName();
        if (isEngine(cn)) {
            return;
        }
        if (depth < top) {
            String n = simpleName(cn);
            if (n != null) {
                String lower = n.toLowerCase(Locale.ROOT);
                if (!hasWord(lower, NOT_ATTACK_WORDS) && (!onlyAttackLike || hasWord(lower, ATTACK_WORDS))) {
                    String r = readable(n);
                    if (r.length() >= 3 && !genericName(r)) {
                        out.add(r);
                    }
                }
            }
        }
        if (depth == 0) {
            return;
        }
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fields;
            try {
                fields = k.getDeclaredFields();
            } catch (LinkageError | RuntimeException ex) {
                continue;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof Iterable<?> it) {
                        int count = 0;
                        for (Object x : it) {
                            if (count++ >= 20) {
                                break;
                            }
                            dive(x, depth - 1, top, onlyAttackLike, out, seen);
                        }
                    } else if (v instanceof Map<?, ?> map) {
                        int count = 0;
                        for (Object x : map.values()) {
                            if (count++ >= 20) {
                                break;
                            }
                            dive(x, depth - 1, top, onlyAttackLike, out, seen);
                        }
                    } else {
                        dive(v, depth - 1, top, onlyAttackLike, out, seen);
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    // a field that cannot be read: skip it
                }
            }
        }
    }

    // ------------------------------------------------------------------ modded mobs: everything beyond the goal list

    /**
     * Attack names for a modded mob, found without any table and without a goal list (many boss mods run their attacks from
     * their own state machine, a brain, or a GeckoLib controller, so the goal list says nothing):
     * <ol>
     * <li>classes of the mod's jar (read from its scan data, nothing is loaded) that carry the mob's name or are nested in the
     * mob's class, or that sit in a package named after the mob, and whose names look like attacks;</li>
     * <li>methods of the entity class (and its modded superclasses) like startSlam / performLaserBeam / castMissile;</li>
     * <li>fields of the entity class that hold an animation and are named like an attack (SLAM_ATTACK, laserBeamAnim);</li>
     * <li>classes declared inside the entity class.</li>
     * </ol>
     * Everything found is only listed (nothing to edit); the config option extraAttacks overrides any miss or wrong guess.
     */
    private static List<String> moddedNames(EntityType<?> type, Class<?> entityClass, String id) {
        Set<String> out = new LinkedHashSet<>();
        ResourceLocationParts parts = new ResourceLocationParts(id);
        String ownClass = entityClass == null ? "" : entityClass.getName();
        String classKey = entityClass == null ? "" : stripEntityWord(entityClass.getSimpleName().toLowerCase(Locale.ROOT));

        // 1. the mod's jar
        try {
            for (String cn : classesOfMod(parts.namespace)) {
                if (cn.equals(ownClass) || out.size() >= MAX_SPECIAL) {
                    continue;
                }
                String simple = simpleName(cn);
                if (simple == null) {
                    continue;
                }
                String lowerSimple = simple.toLowerCase(Locale.ROOT);
                String lowerFull = cn.toLowerCase(Locale.ROOT);
                if (hasWord(lowerSimple, NOT_ATTACK_WORDS) || !hasWord(lowerSimple, ATTACK_WORDS)) {
                    continue;
                }
                if (hasWord(lowerSimple.replace("entity", ""), NOT_CLASS_WORDS) && !hasWord(lowerSimple, WRAPPER_WORDS)) {
                    if (hasNotClassWord(lowerSimple)) {
                        continue;
                    }
                }
                boolean nested = !ownClass.isEmpty() && cn.startsWith(ownClass + "$");
                boolean named = (parts.key.length() >= 3 && lowerFull.contains(parts.key))
                        || (classKey.length() >= 3 && lowerFull.contains(classKey));
                boolean packaged = false;
                if (!named) {
                    // a package named after the mob, e.g. com.mod.entity.lich.ai.SpellGoal
                    String pkg = lowerFull.substring(0, Math.max(0, lowerFull.lastIndexOf('.')));
                    for (String seg : pkg.split("\\.")) {
                        if ((parts.key.length() >= 3 && seg.equals(parts.key)) || (classKey.length() >= 3 && seg.equals(classKey))) {
                            packaged = true;
                            break;
                        }
                    }
                }
                if (nested || named || packaged) {
                    String r = cleanName(readable(simple), parts.key, classKey);
                    if (r.length() >= 3 && !genericName(r)) {
                        out.add(r);
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            // scan data not available: the other methods still run
        }

        if (entityClass != null) {
            // 2 + 3. methods and animation fields of the entity class hierarchy (modded classes only)
            for (Class<?> k = entityClass; k != null && k != Object.class && !isEngine(k.getName()); k = k.getSuperclass()) {
                try {
                    for (Method m : k.getDeclaredMethods()) {
                        if (m.isSynthetic() || m.isBridge() || m.getName().startsWith("lambda$") || m.getName().contains("$")) {
                            continue;
                        }
                        String name = m.getName();
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (!startsWithPrefix(lower) || !hasWord(lower, ATTACK_WORDS) || hasWord(lower, NOT_ATTACK_WORDS)
                                || lower.startsWith("is") || lower.startsWith("get") || lower.startsWith("set")
                                || lower.startsWith("can") || lower.startsWith("should") || lower.startsWith("has")
                                || lower.contains("hurt") || lower.contains("target")) {
                            continue;
                        }
                        String r = readable(stripPrefix(name));
                        if (r.length() >= 3 && !genericName(r) && out.size() < MAX_SPECIAL) {
                            out.add(r);
                        }
                    }
                    for (Field f : k.getDeclaredFields()) {
                        String tn = f.getType().getSimpleName().toLowerCase(Locale.ROOT);
                        String name = f.getName();
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (!(tn.contains("animation") || tn.contains("animstate")) || !hasWord(lower, ATTACK_WORDS)
                                || hasWord(lower, NOT_ATTACK_WORDS)) {
                            continue;
                        }
                        String r = readable(name.replaceAll("(?i)(anim|animation|state)$", ""));
                        if (r.length() >= 3 && !genericName(r) && out.size() < MAX_SPECIAL) {
                            out.add(r);
                        }
                    }
                    // 4. classes declared inside the entity class
                    for (Class<?> inner : k.getDeclaredClasses()) {
                        String n = simpleName(inner.getName());
                        if (n == null) {
                            continue;
                        }
                        String lower = n.toLowerCase(Locale.ROOT);
                        if (hasWord(lower, ATTACK_WORDS) && !hasWord(lower, NOT_ATTACK_WORDS) && !hasNotClassWord(lower)) {
                            String r = readable(n);
                            if (r.length() >= 3 && !genericName(r) && out.size() < MAX_SPECIAL) {
                                out.add(r);
                            }
                        }
                    }
                } catch (LinkageError | RuntimeException ex) {
                    // a member type from a mod that is not installed: skip this class level
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean hasNotClassWord(String lower) {
        for (String w : NOT_CLASS_WORDS) {
            if (lower.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithPrefix(String lower) {
        for (String p : METHOD_PREFIXES) {
            if (lower.startsWith(p) && lower.length() > p.length()) {
                return true;
            }
        }
        return false;
    }

    private static String stripPrefix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String p : METHOD_PREFIXES) {
            if (lower.startsWith(p) && name.length() > p.length() && Character.isUpperCase(name.charAt(p.length()))) {
                return name.substring(p.length());
            }
        }
        return name;
    }

    private static String stripEntityWord(String lower) {
        return lower.endsWith("entity") && lower.length() > 6 ? lower.substring(0, lower.length() - 6) : lower;
    }

    /** Removes the mob's own name from an attack name ("Lich Missile" -> "Missile"), if something is left. */
    private static String cleanName(String readable, String key, String classKey) {
        String result = readable;
        for (String k : new String[] {key, classKey}) {
            if (k == null || k.length() < 3) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (String word : result.split(" ")) {
                if (!word.toLowerCase(Locale.ROOT).equals(k)) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(word);
                }
            }
            if (sb.length() >= 3) {
                result = sb.toString();
            }
        }
        return result;
    }

    /** namespace and a lowercase, underscore-free key of an entity id ("chaos:ender_king" -> "chaos", "enderking"). */
    private static final class ResourceLocationParts {
        final String namespace;
        final String key;

        ResourceLocationParts(String id) {
            int c = id.indexOf(':');
            this.namespace = c < 0 ? "minecraft" : id.substring(0, c);
            this.key = (c < 0 ? id : id.substring(c + 1)).toLowerCase(Locale.ROOT).replace("_", "");
        }
    }

    /** Names of every class in the jar of the mod (from its scan data, so nothing is loaded). Empty if unavailable. */
    private static List<String> classesOfMod(String modId) {
        return MOD_CLASSES.computeIfAbsent(modId, k -> {
            List<String> names = new ArrayList<>();
            try {
                var info = ModList.get().getModFileById(k);
                if (info != null) {
                    var scan = info.getFile().getScanResult();
                    if (scan != null) {
                        for (var data : scan.getClasses()) {
                            names.add(data.clazz().getClassName());
                        }
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                EncephalonMod.LOGGER.warn("Mob Editor: could not read the classes of mod {}", k);
            }
            return List.copyOf(names);
        });
    }

    // ------------------------------------------------------------------ names

    /** The name of an attack in the editor. */
    public static Component nameOf(Attack a) {
        return a.label() != null ? Component.literal(a.label()) : Component.translatable("encephalon.attack." + a.id());
    }

    /** The short description of a special attack (the ones that do no damage). */
    public static Component describe(Attack a) {
        if (a.desc() != null) {
            return Component.literal(a.desc());
        }
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
