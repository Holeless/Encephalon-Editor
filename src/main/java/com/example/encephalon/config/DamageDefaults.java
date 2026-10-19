package com.example.encephalon.config;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.electronwill.nightconfig.core.UnmodifiableConfig;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.example.encephalon.EncephalonMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

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
 * <li>EFFECT: special attacks that do no damage of their own (teleporting, poison, summoning), and every attack found in a
 * modded mob. Listed with a description, nothing to edit.</li>
 * </ul>
 * <b>Modded mobs.</b> Names are not trusted alone. The bytecode of the candidate classes is read (ASM, nothing is loaded or run)
 * and what the code does is collected: hurts, explodes, fires a fireball, applies Slowness, summons something, teleports,
 * sets on fire, ... A class that does such things counts as an attack whatever it is called, and the description of the attack
 * is written from those facts. Candidates are: the mob's goals (and what they hold), classes nested in the mob, classes of the
 * mod's jar that carry the mob's name, sit in a package named after it, or take the mob as a parameter, methods of the entity
 * class such as startSlam / castMissile (with everything they call), and animation / attack strings in the entity's code.
 * <b>Hardcoded attacks.</b> A mob that has attacks of its own (a goal, an explosion, a modded attack found in its code) gets no
 * extra melee row and no melee goal from this mod, like the creeper. <b>Config values.</b> Numbers the mod exposes in its own
 * config (damage, cooldown, interval, duration, ...) that belong to the mob are listed as editable rows (id prefix cfg_) and
 * written to the mod's live config value.
 * A mob with no ATTACK_DAMAGE attribute is given a fallback from the size of its hitbox (micro / small / normal / big) as a real
 * attribute, so anything that makes it attack (the editor's category setting, or another mod) does not crash on a missing one.
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class DamageDefaults {
    public static final String MELEE = "melee";
    public static final String EXPLOSION = "explosion";
    public static final String RANGED = "ranged";
    /** Prefix of the attack ids that stand for a value of the mod's own config. */
    public static final String CONFIG_PREFIX = "cfg_";

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
    private record Profile(Attack melee, List<Attack> others, boolean hardcoded) {
    }

    /** A modded attack that was found: the name shown, and what it does (null when nothing could be told). */
    private record Found(String label, String desc) {
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

    /**
     * Words that mark a name as an attack. Matched against whole words of the name (split at capitals, _ and .): a word of up to
     * 3 letters must be equal, a longer one only has to start with it ("explo" matches "explosion"). So "ram" matches "Ram" but
     * not "Program", and "rain" does not match "Brain".
     */
    private static final String[] ATTACK_WORDS = {"attack", "shoot", "spit", "breath", "sting", "blast", "explo", "fireball", "beam",
            "laser", "summon", "cast", "spell", "bite", "slam", "stomp", "roar", "charge", "pounce", "leap", "swoop", "smash",
            "strike", "throw", "lunge", "ram", "teleport", "poison", "action", "ability", "skill", "special",
            "missile", "projectile", "bolt", "nova", "wave", "barrage", "rain", "burst", "pulse", "slash", "swipe", "claw",
            "dash", "dive", "crush", "sweep", "volley", "orb", "meteor", "lightning", "curse", "drain", "shockwave", "quake",
            "grab", "fling", "hurl", "bomb", "minion", "ultimate", "combo", "rush", "cleave", "impale", "spike", "flame", "fire",
            "blow", "kick", "punch", "pound", "tail", "gaze", "scream", "howl", "bash", "stab", "shatter", "erupt", "vortex"};
    /** Words that rule a name out (movement, targeting, idle behaviour). Same whole-word matching. */
    private static final String[] NOT_ATTACK_WORDS = {"target", "avoid", "flee", "retreat", "panic", "look", "follow", "wander",
            "stroll", "float", "swim", "breed", "sleep", "tempt", "eat", "attackable", "hover", "idle", "random", "fly", "move"};
    /** Class names with these words are rendering, networking or registration code, never an attack. */
    private static final String[] NOT_CLASS_WORDS = {"render", "model", "layer", "packet", "message", "event", "sound", "particle",
            "registry", "register", "handler", "config", "entity", "client", "screen", "provider", "recipe", "loot", "mixin",
            "attribute", "item", "block", "egg", "bossbar", "effect"};
    /** Strings with these words are timers and settings, not attack names. */
    private static final String[] NOT_STRING_WORDS = {"cooldown", "timer", "tick", "time", "count", "damage", "speed", "range",
            "delay", "chance", "duration", "length", "radius", "height", "texture", "geo", "key", "tag", "state"};
    /** Names that are never an attack themselves (they manage, track or hold attacks); what they hold is searched instead. */
    private static final String[] HARD_NOT_WORDS = {"controller", "manager", "helper", "util", "tracker", "holder", "context",
            "selector", "machine", "registry", "handler", "state", "stage", "brain", "timer", "data", "cooldowns"};
    /** Words of a config path that mark a setting of an attack or a mob. */
    private static final String[] SETTING_WORDS = {"damage", "dmg", "attack", "cooldown", "interval", "delay", "duration", "time",
            "tick", "chance", "radius", "range", "amplifier", "power", "strength", "speed", "count", "amount", "health", "hp",
            "armor", "armour", "knockback", "rate", "frequency", "level", "size", "length", "distance", "windup", "warmup",
            "recharge", "charge"};
    private static final String[] NOT_SETTING_WORDS = {"spawn", "weight", "biome", "group", "dimension"};
    private static final int MAX_CONFIG_ROWS = 16;
    private static final int MAX_SPECIAL = 12;
    private static final int MAX_CANDIDATES = 600;
    /** Goal names that only wrap the real attacks (an "ActionGoal" holding a list of actions): the actions are looked for inside. */
    private static final String[] WRAPPER_WORDS = {"action", "ability", "skill", "special", "spell", "behavior", "behaviour", "phase",
            "controller", "manager", "selector", "machine", "state"};
    /** Names too generic to show as an attack. */
    private static final Set<String> GENERIC = Set.of("action", "ability", "skill", "special", "attack", "spell", "boss", "custom",
            "cooldown", "mob", "entity", "default", "phase", "move", "ai", "task", "behavior", "behaviour");
    /** Verbs an entity class's attack methods often start with. */
    private static final String[] METHOD_PREFIXES = {"do", "start", "perform", "cast", "use", "shoot", "summon", "trigger",
            "begin", "execute", "fire", "launch", "spawn", "throw", "unleash", "release", "tryto", "try"};
    /** Package segments that usually hold a mod's attacks / AI. */
    private static final String[] ATTACK_PACKAGES = {".goal", ".ai", ".attack", ".ability", ".abilities", ".action", ".move",
            ".skill", ".spell", ".behavior", ".behaviour", ".phase"};

    /** Engine projectile / area classes, by simple name, with how they read in a description. */
    private static final Map<String, String> ENGINE_THINGS = Map.ofEntries(
            Map.entry("LargeFireball", "large fireballs"), Map.entry("SmallFireball", "small fireballs"),
            Map.entry("DragonFireball", "dragon fireballs"), Map.entry("WitherSkull", "wither skulls"),
            Map.entry("Arrow", "arrows"), Map.entry("SpectralArrow", "spectral arrows"), Map.entry("ThrownTrident", "tridents"),
            Map.entry("ShulkerBullet", "shulker bullets"), Map.entry("Snowball", "snowballs"), Map.entry("ThrownPotion", "potions"),
            Map.entry("ThrownEgg", "eggs"), Map.entry("LlamaSpit", "spit"), Map.entry("WindCharge", "wind charges"),
            Map.entry("BreezeWindCharge", "wind charges"), Map.entry("EvokerFangs", "evoker fangs"),
            Map.entry("LightningBolt", "lightning bolts"), Map.entry("AreaEffectCloud", "lingering effect clouds"),
            Map.entry("PrimedTnt", "primed TNT"), Map.entry("FireworkRocketEntity", "firework rockets"));

    /** MobEffects field names that read differently in the game. */
    private static final Map<String, String> EFFECT_NAMES = Map.ofEntries(
            Map.entry("MOVEMENT_SLOWDOWN", "Slowness"), Map.entry("MOVEMENT_SPEED", "Speed"),
            Map.entry("DIG_SLOWDOWN", "Mining Fatigue"), Map.entry("DIG_SPEED", "Haste"),
            Map.entry("DAMAGE_BOOST", "Strength"), Map.entry("DAMAGE_RESISTANCE", "Resistance"),
            Map.entry("JUMP", "Jump Boost"), Map.entry("HARM", "Instant Damage"), Map.entry("HEAL", "Instant Health"),
            Map.entry("CONFUSION", "Nausea"), Map.entry("UNLUCK", "Bad Luck"));

    /** What a name suggests when the code says nothing. */
    private static final String[][] NAME_HINTS = {
            {"slam", "a heavy slam, probably damaging and knocking back nearby targets"},
            {"stomp", "a ground stomp that probably hits everything near its feet"},
            {"smash", "a smashing blow"}, {"crush", "a crushing blow"},
            {"roar", "a roar, probably pushing back or weakening nearby targets"},
            {"scream", "a scream, probably hurting or pushing back nearby targets"},
            {"howl", "a howl, probably affecting nearby targets"},
            {"charge", "a charge at its target"}, {"dash", "a fast dash at its target"}, {"rush", "a rush at its target"},
            {"leap", "a leap onto its target"}, {"pounce", "a pounce onto its target"},
            {"dive", "a dive at its target"}, {"swoop", "a swooping dive at its target"},
            {"beam", "a beam fired at its target"}, {"laser", "a laser fired at its target"},
            {"missile", "missiles fired at its target"}, {"fireball", "fireballs fired at its target"},
            {"projectile", "projectiles fired at its target"}, {"volley", "a volley of projectiles"},
            {"barrage", "a barrage of projectiles"}, {"shoot", "shots fired at its target"},
            {"summon", "summoning other creatures to fight for it"}, {"minion", "calling in minions"},
            {"teleport", "teleporting"}, {"drain", "draining health from its target"},
            {"nova", "an expanding blast around itself"}, {"pulse", "a pulse around itself"},
            {"shockwave", "a shockwave around itself"}, {"quake", "a quake that shakes the ground around it"},
            {"claw", "a clawing strike"}, {"slash", "a slashing strike"}, {"swipe", "a sweeping swipe"},
            {"bite", "a bite"}, {"sting", "a sting"}, {"poison", "a poisoning attack"}, {"curse", "a curse on its target"},
            {"lightning", "calling down lightning"}, {"meteor", "meteors raining down"}, {"explo", "an explosion"},
            {"bomb", "a bomb"}, {"breath", "a breath attack"}, {"flame", "flames"}, {"fire", "fire"}};

    private static final Pattern DESC_REF = Pattern.compile("L([^;]+);");

    /** Types that had no attack damage and were given the size fallback. */
    private static final Map<EntityType<?>, Double> FALLBACK = new ConcurrentHashMap<>();
    /** Scan results, per type (one throw-away mob each, the first time the editor asks). */
    private static final Map<EntityType<?>, Profile> PROFILES = new ConcurrentHashMap<>();
    /** Class names of one mod's jar, per mod id (read once from the mod's scan data, nothing is loaded). */
    private static final Map<String, List<String>> MOD_CLASSES = new ConcurrentHashMap<>();
    /** Bytecode facts per class name (class files are read once). */
    private static final Map<String, ClassInfo> CLASS_INFO = new ConcurrentHashMap<>();
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

    private static Profile profileOf(MinecraftServer server, EntityType<?> t) {
        Profile p = PROFILES.get(t);
        if (p == null) {
            p = scan(server, t);
            PROFILES.put(t, p);
        }
        return p;
    }

    /**
     * True if the type has attacks of its own in its code (a melee / ranged / swell goal, a modded attack found in its code, an
     * attack named in the config). Such a mob gets no melee row and no melee goal from this mod: its own code attacks, like a
     * creeper that only explodes.
     */
    public static boolean hardcodedAttack(MinecraftServer server, EntityType<?> t) {
        try {
            return profileOf(server, t).hardcoded();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /**
     * Every attack of the type that is listed in the editor: melee first, then the others. Server thread (it may create one
     * throw-away mob the first time). A mob with attacks of its own gets no melee row, whatever its category is set to.
     */
    public static List<Attack> attacksOf(MinecraftServer server, EntityType<?> t, boolean forcedHostile) {
        Profile p = profileOf(server, t);
        List<Attack> out = new ArrayList<>();
        boolean realOthers = false;
        for (Attack a : p.others()) {
            if (!a.id().startsWith(CONFIG_PREFIX)) {
                realOthers = true;
                break;
            }
        }
        if (p.melee() != null) {
            out.add(p.melee());
        } else if (!p.hardcoded() && !realOthers) {
            out.add(new Attack(MELEE, defaultMelee(t), null, Kind.MELEE, null));
        }
        out.addAll(p.others());
        return out;
    }

    private static Profile scan(MinecraftServer server, EntityType<?> t) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(t).toString();
        boolean vanilla = id.startsWith("minecraft:");
        String namespace = id.substring(0, id.indexOf(':'));
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
        Map<String, Found> found = new LinkedHashMap<>();
        Entity e = null;
        Class<?> entityClass = null;
        try {
            e = t.create(server.overworld());
            if (e != null) {
                entityClass = e.getClass();
            }
            if (e instanceof Mob mob) {
                isMob = true;
                List<String> mods = vanilla ? List.of() : classesOfMod(namespace);
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
                        try {
                            goalFinds(g, mods, found);
                        } catch (RuntimeException | LinkageError ex) {
                            // a goal that cannot be read: skip it
                        }
                    }
                }
                if (swell) {
                    blast = Math.round(explosionMax(mob));
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not work out the attacks of {}", id, ex);
        } finally {
            if (e != null) {
                e.discard();
            }
        }
        if (!vanilla) {
            // Not tied to the mob being creatable: even without an instance the mod's jar can be searched.
            try {
                moddedFinds(entityClass, namespace, id, found);
            } catch (RuntimeException | LinkageError ex) {
                EncephalonMod.LOGGER.warn("Mob Editor: could not search the code of {} for attacks", id, ex);
            }
            EncephalonMod.LOGGER.info("Mob Editor attacks: {} has goals {} -> found {}", id, goalClasses,
                    found.values().stream().map(Found::label).toList());
        }
        // Attacks of its own: nothing is added on top of them (no melee row, no melee goal), like a creeper that only explodes.
        boolean hardcoded = meleeGoal || ranged || swell || extra != null || !configured.isEmpty()
                || (!vanilla && !found.isEmpty());
        if (swell) {
            others.add(0, new Attack(EXPLOSION, blast, DamageTypes.EXPLOSION, Kind.EXPLOSION, null));
        }
        // Potions and snowballs are not projectile damage, so the generic ranged row is only for the rest.
        if (ranged && extra == null && special == null) {
            others.add(new Attack(RANGED, -1, null, Kind.DAMAGE, null));
        }
        for (Map.Entry<String, Found> f : found.entrySet()) {
            others.add(new Attack("special_" + f.getKey().replaceAll("[^a-z0-9]+", "_"), -1, null, Kind.EFFECT,
                    f.getValue().label(), f.getValue().desc()));
        }
        if (!vanilla && found.isEmpty() && configured.isEmpty() && EncephalonConfig.isQuiet(t)) {
            // A modded boss whose attacks live in the mod's own code: say so instead of showing only a made-up default.
            others.add(new Attack("custom_code", -1, null, Kind.EFFECT, null));
            hardcoded = true;
        }
        if (!vanilla) {
            try {
                List<Attack> cfg = configRows(namespace, id, entityClass, found.values().stream().map(Found::label).toList());
                if (!cfg.isEmpty()) {
                    MobConfigData data = MobConfigData.get(server);
                    for (Attack a : cfg) {
                        Double stored = data.damage(id, a.id());
                        if (stored != null) {
                            applyConfig(a.id(), stored);
                        }
                    }
                    others.addAll(cfg);
                }
            } catch (RuntimeException | LinkageError ex) {
                EncephalonMod.LOGGER.warn("Mob Editor: could not read the config values of {}", id, ex);
            }
        }
        if (!isMob) {
            return new Profile(null, List.copyOf(others), hardcoded);
        }
        // Melee: a melee goal, or a real attack damage on a mob that does not explode (a blaze, warden, phantom or vex
        // fight up close with their own code, not with a melee goal). A few vanilla mobs have the attribute but never melee.
        // A modded mob with attacks found in its code is left without a melee row: those are its attacks.
        Double fixed = MELEE_DEFAULTS.get(id);
        boolean ownAttack = fixed != null
                || (!swell && (meleeGoal || (!NO_MELEE.contains(id) && hasOwnAttackDamage(t) && (vanilla || !hardcoded))));
        Attack melee = ownAttack
                ? new Attack(MELEE, fixed != null ? fixed : defaultMelee(t), null, Kind.MELEE, null) : null;
        return new Profile(melee, List.copyOf(others), hardcoded);
    }

    // ------------------------------------------------------------------ the mod's own config values

    /** A value of a mod's config that is editable from the Mob Editor; orig is the value it had when first seen. */
    private record ConfigRef(ModConfigSpec spec, ModConfigSpec.ConfigValue<?> value, List<String> path, Object orig) {
    }

    private static final Map<String, ConfigRef> CONFIG_REFS = new ConcurrentHashMap<>();

    private static void flatten(Object o, List<ModConfig> out) {
        if (o instanceof ModConfig mc) {
            out.add(mc);
        } else if (o instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                flatten(v, out);
            }
        } else if (o instanceof Iterable<?> it) {
            for (Object v : it) {
                flatten(v, out);
            }
        }
    }

    /** The configs a mod registered. Read by reflection, so a different FML version only costs this feature, not the build. */
    private static List<ModConfig> modConfigs(String modId) {
        List<ModConfig> out = new ArrayList<>();
        try {
            Class<?> tracker = Class.forName("net.neoforged.fml.config.ConfigTracker");
            Object instance = tracker.getField("INSTANCE").get(null);
            Object result;
            try {
                result = tracker.getMethod("getConfigsForMod", String.class).invoke(instance, modId);
            } catch (NoSuchMethodException ex) {
                result = tracker.getMethod("configSets").invoke(instance);
                List<ModConfig> all = new ArrayList<>();
                flatten(result, all);
                for (ModConfig mc : all) {
                    if (mc.getModId().equals(modId)) {
                        out.add(mc);
                    }
                }
                return out;
            }
            flatten(result, out);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not list the configs of mod {}", modId);
        }
        return out;
    }

    private static void walk(UnmodifiableConfig cfg, List<ModConfigSpec.ConfigValue<?>> out, int depth) {
        for (UnmodifiableConfig.Entry en : cfg.entrySet()) {
            Object v = en.getValue();
            if (v instanceof ModConfigSpec.ConfigValue<?> cv) {
                out.add(cv);
            } else if (v instanceof UnmodifiableConfig sub && depth < 6) {
                walk(sub, out, depth + 1);
            }
        }
    }

    private static Object readValue(ModConfigSpec.ConfigValue<?> cv) {
        try {
            return cv.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String numText(Object o) {
        if (o instanceof Double d && d != Math.rint(d)) {
            return String.valueOf(d);
        }
        return o instanceof Double d ? String.valueOf((long) d.doubleValue()) : String.valueOf(o);
    }

    /**
     * Numbers in the mod's own config (damage, cooldown, interval, duration, radius, ...) that belong to this mob: the path or the
     * file name carries its name, or the name of one of its attacks. Each becomes an editable row; a value typed there is written
     * to the mod's live config value (not to its file), and the stored value is applied again when a world starts. Hardcoded
     * numbers inside a mod's code cannot be changed, so only what the mod exposes in its config is listed.
     */
    private static List<Attack> configRows(String ns, String id, Class<?> entityClass, List<String> attackLabels) {
        List<Attack> out = new ArrayList<>();
        String key = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT).replace("_", "");
        String classKey = entityClass == null ? "" : stripEntityWord(entityClass.getSimpleName()).toLowerCase(Locale.ROOT);
        List<String> labelKeys = new ArrayList<>();
        for (String l : attackLabels) {
            String k = l.toLowerCase(Locale.ROOT).replace(" ", "");
            if (k.length() >= 4) {
                labelKeys.add(k);
            }
        }
        for (ModConfig mc : modConfigs(ns)) {
            try {
                Object sp = mc.getSpec();
                if (!(sp instanceof ModConfigSpec spec) || !spec.isLoaded()) {
                    continue;
                }
                String file = mc.getFileName().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                List<ModConfigSpec.ConfigValue<?>> values = new ArrayList<>();
                walk(spec.getValues(), values, 0);
                for (ModConfigSpec.ConfigValue<?> cv : values) {
                    if (out.size() >= MAX_CONFIG_ROWS) {
                        break;
                    }
                    Object cur = readValue(cv);
                    if (!(cur instanceof Integer || cur instanceof Long || cur instanceof Double)) {
                        continue;
                    }
                    List<String> path = cv.getPath();
                    String joined = String.join(".", path);
                    if (!tokenHit(joined, SETTING_WORDS) || tokenHit(joined, NOT_SETTING_WORDS)) {
                        continue;
                    }
                    String flat = joined.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                    boolean mine = (key.length() >= 3 && (flat.contains(key) || file.contains(key)))
                            || (classKey.length() >= 3 && (flat.contains(classKey) || file.contains(classKey)));
                    for (String lk : labelKeys) {
                        if (flat.contains(lk)) {
                            mine = true;
                        }
                    }
                    if (!mine) {
                        continue;
                    }
                    String rid = CONFIG_PREFIX + (mc.getModId() + "." + joined).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.]+", "_");
                    final Object first = cur;
                    ConfigRef ref = CONFIG_REFS.computeIfAbsent(rid, k -> new ConfigRef(spec, cv, List.copyOf(path), first));
                    String last = path.get(path.size() - 1);
                    String prev = path.size() >= 2 ? path.get(path.size() - 2) : "";
                    String base = cleanName(prev.isEmpty() ? readable(last) : readable(prev) + " " + readable(last), key, classKey);
                    out.add(new Attack(rid, -1, null, Kind.DAMAGE, base + " (default " + numText(ref.orig()) + ")", null));
                }
            } catch (RuntimeException | LinkageError ex) {
                // a config that cannot be read: skip it
            }
        }
        return out;
    }

    private static boolean valid(ConfigRef r, Object nv) {
        try {
            Object vs = r.spec().getSpec().get(r.path());
            if (vs != null) {
                Object res = vs.getClass().getMethod("test", Object.class).invoke(vs, nv);
                return !(res instanceof Boolean b) || b;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            // cannot check: let it through
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setConfig(String id, ConfigRef r, Object nv) {
        try {
            if (!valid(r, nv)) {
                EncephalonMod.LOGGER.warn("Mob Editor: {} is not a valid value for config {}", nv, id);
                return;
            }
            ((ModConfigSpec.ConfigValue) r.value()).set(nv);
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not set config {}", id, ex);
        }
    }

    /** Writes a value typed in the editor to the mod's live config value (ids that are not config rows are ignored). */
    public static void applyConfig(String id, double v) {
        ConfigRef r = CONFIG_REFS.get(id);
        if (r == null) {
            return;
        }
        Object o = r.orig();
        Object nv;
        if (o instanceof Integer) {
            nv = (int) Math.round(v);
        } else if (o instanceof Long) {
            nv = Math.round(v);
        } else if (o instanceof Double) {
            nv = v;
        } else {
            return;
        }
        setConfig(id, r, nv);
    }

    /** Puts the config value back to what it was before the editor changed it. */
    public static void resetConfig(String id) {
        ConfigRef r = CONFIG_REFS.get(id);
        if (r != null) {
            setConfig(id, r, r.orig());
        }
    }

    /** Server started: re-applies stored config values (the mod's configs are loaded again with every world). */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            for (String mobId : MobConfigData.get(server).configOverrideMobs()) {
                EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(mobId)).orElse(null);
                if (t != null) {
                    profileOf(server, t); // scanning applies the stored values
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not re-apply stored config values", ex);
        }
    }

    private static void addFound(Map<String, Found> found, String label, String desc) {
        if (label == null || label.isBlank()) {
            return;
        }
        String key = label.toLowerCase(Locale.ROOT).trim();
        Found old = found.get(key);
        if (old == null) {
            if (found.size() < MAX_SPECIAL) {
                found.put(key, new Found(label.trim(), desc));
            }
        } else if (old.desc() == null && desc != null) {
            found.put(key, new Found(old.label(), desc));
        }
    }

    private static boolean isEngine(String cn) {
        return cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.") || cn.startsWith("sun.")
                || cn.startsWith("net.minecraft.") || cn.startsWith("net.neoforged.") || cn.startsWith("com.mojang.")
                || cn.startsWith("kotlin.") || cn.startsWith("org.slf4j.") || cn.startsWith("it.unimi.") || cn.startsWith("com.google.")
                || cn.startsWith("software.bernie.") || cn.startsWith("org.joml.") || cn.startsWith("org.objectweb.")
                || cn.startsWith("com.example.encephalon.");
    }

    /** The plain class name ("MissileAction"), or null for anonymous classes and lambdas. */
    private static String simpleName(String raw) {
        String n = raw.substring(Math.max(raw.lastIndexOf('.'), raw.lastIndexOf('$')) + 1);
        if (n.isEmpty() || Character.isDigit(n.charAt(0)) || raw.contains("Lambda")) {
            return null;
        }
        return n;
    }

    // ------------------------------------------------------------------ names

    /** Lower-case words of a name, split at capitals, digits-to-letters and any non-alphanumeric. */
    private static String[] tokens(String raw) {
        return raw.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ")
                .toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
    }

    /** True if a whole word of the name equals (3 letters or fewer) or starts with (longer) one of the words. */
    private static boolean tokenHit(String raw, String[] words) {
        for (String t : tokens(raw)) {
            if (t.isEmpty()) {
                continue;
            }
            for (String w : words) {
                if (w.length() <= 3 ? t.equals(w) : t.startsWith(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean looksAttack(String raw) {
        return tokenHit(raw, ATTACK_WORDS);
    }

    private static boolean notAttack(String raw) {
        return tokenHit(raw, NOT_ATTACK_WORDS);
    }

    /** "FireBreathGoal" -> "Fire Breath", "MissileAction" -> "Missile", "SLAM_ATTACK" -> "Slam Attack". */
    private static String readable(String n) {
        if (n.endsWith("Goal") && n.length() > 4) {
            n = n.substring(0, n.length() - 4);
        }
        if (n.endsWith("Action") && n.length() > 6) {
            n = n.substring(0, n.length() - 6);
        }
        if (n.indexOf('_') >= 0 || n.indexOf('.') >= 0 || n.equals(n.toUpperCase(Locale.ROOT)) || n.equals(n.toLowerCase(Locale.ROOT))) {
            StringBuilder sb = new StringBuilder();
            for (String part : n.toLowerCase(Locale.ROOT).split("[_.\\- ]+")) {
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

    private static String stripEntityWord(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.endsWith("entity") && s.length() > 6 ? s.substring(0, s.length() - 6) : s;
    }

    /** Removes the mob's own name from an attack name ("Lich Missile" -> "Missile"), if something is left. */
    private static String cleanName(String readable, String... keys) {
        String result = readable;
        for (String k : keys) {
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

    /** The attack name in a string constant ("animation.boss.slam" -> "Slam"), or null if the string is not one. */
    private static String attackString(String s) {
        if (s.length() < 3 || s.length() > 40 || !s.matches("[a-zA-Z][a-zA-Z0-9_.\\- ]*")) {
            return null;
        }
        String last = s.substring(s.lastIndexOf('.') + 1);
        if (last.length() < 3 || !looksAttack(last) || notAttack(last) || tokenHit(s, NOT_STRING_WORDS)) {
            return null;
        }
        String r = readable(last);
        return r.length() < 3 || genericName(r) ? null : r;
    }

    // ------------------------------------------------------------------ bytecode facts

    /** What a piece of code does, read from its bytecode (nothing is run). */
    private static final class Facts {
        boolean hurts;
        boolean explodes;
        boolean fire;
        boolean knock;
        boolean teleport;
        boolean heal;
        boolean area;
        boolean blocks;
        boolean shoots;
        boolean spawnsAny;
        final Set<String> effects = new LinkedHashSet<>();
        final Set<String> things = new LinkedHashSet<>();
        final Set<String> spawns = new LinkedHashSet<>();
        final Set<String> strings = new LinkedHashSet<>();
        /** Methods of the same class this code calls (so a method can be followed into its helpers). */
        final Set<String> calls = new LinkedHashSet<>();

        void merge(Facts o) {
            hurts |= o.hurts;
            explodes |= o.explodes;
            fire |= o.fire;
            knock |= o.knock;
            teleport |= o.teleport;
            heal |= o.heal;
            area |= o.area;
            blocks |= o.blocks;
            shoots |= o.shoots;
            spawnsAny |= o.spawnsAny;
            effects.addAll(o.effects);
            things.addAll(o.things);
            spawns.addAll(o.spawns);
            strings.addAll(o.strings);
        }

        /** Does something that only an attack does. Knockback and area scans alone do not count (movement code has them too). */
        boolean offensive() {
            return hurts || explodes || fire || shoots || !effects.isEmpty() || !things.isEmpty() || !spawns.isEmpty();
        }

        /** True if the code of the class could be read at all. */
        boolean any;
    }

    private static final class ClassInfo {
        final String internal;
        String superName;
        final Map<String, Facts> methods = new LinkedHashMap<>();
        /** Every class named in a field or method type, or used by an instruction. */
        final Set<String> refs = new HashSet<>();

        ClassInfo(String name) {
            this.internal = name.replace('.', '/');
        }
    }

    private static void descRefs(Set<String> refs, String desc) {
        Matcher m = DESC_REF.matcher(desc);
        while (m.find()) {
            refs.add(m.group(1));
        }
    }

    private static ClassInfo classInfo(String cn, ClassLoader cl) {
        ClassInfo cached = CLASS_INFO.get(cn);
        if (cached != null) {
            return cached;
        }
        ClassInfo ci = read(cn, cl);
        CLASS_INFO.put(cn, ci);
        return ci;
    }

    private static ClassInfo read(String cn, ClassLoader cl) {
        ClassInfo ci = new ClassInfo(cn);
        ClassLoader use = cl != null ? cl : DamageDefaults.class.getClassLoader();
        try (InputStream in = use.getResourceAsStream(cn.replace('.', '/') + ".class")) {
            if (in == null) {
                return ci;
            }
            ClassReader reader = new ClassReader(in);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    ci.superName = superName;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    descRefs(ci.refs, desc);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    descRefs(ci.refs, desc);
                    Facts f = ci.methods.computeIfAbsent(name, k -> new Facts());
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
                            ci.refs.add(owner);
                            descRefs(ci.refs, mdesc);
                            if (owner.equals(ci.internal)) {
                                f.calls.add(mname);
                            }
                            methodHit(f, mname);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                            ci.refs.add(owner);
                            if (opcode == Opcodes.GETSTATIC) {
                                fieldHit(f, owner, fname);
                            }
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            ci.refs.add(type);
                            if (opcode == Opcodes.NEW) {
                                newHit(f, type, ci.internal);
                            }
                        }

                        @Override
                        public void visitLdcInsn(Object v) {
                            if (v instanceof String s && f.strings.size() < 10 && attackString(s) != null) {
                                f.strings.add(s);
                            }
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String dname, String ddesc, Handle bsm, Object... bsmArgs) {
                            for (Object a : bsmArgs) {
                                if (a instanceof Handle h && h.getOwner().equals(ci.internal)) {
                                    f.calls.add(h.getName());
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception | LinkageError ex) {
            // class not readable: no facts
        }
        return ci;
    }

    private static void methodHit(Facts f, String name) {
        switch (name) {
            case "explode" -> f.explodes = true;
            case "hurt", "doHurtTarget" -> f.hurts = true;
            case "igniteForSeconds", "setRemainingFireTicks", "setSecondsOnFire", "setSecondsOnFire$" -> f.fire = true;
            case "setDeltaMovement", "push", "hurtMarked", "knockback", "addDeltaMovement" -> f.knock = true;
            case "heal", "setHealth" -> f.heal = true;
            case "getEntitiesOfClass", "getEntities", "getNearbyEntities" -> f.area = true;
            case "addFreshEntity", "addEntity" -> f.spawnsAny = true;
            case "shoot", "shootFromRotation" -> f.shoots = true;
            case "setBlock", "destroyBlock", "removeBlock", "setBlockAndUpdate" -> f.blocks = true;
            default -> {
                if (name.toLowerCase(Locale.ROOT).contains("teleport")) {
                    f.teleport = true;
                }
            }
        }
    }

    private static String effectName(String field) {
        String n = EFFECT_NAMES.get(field);
        return n != null ? n : readable(field);
    }

    private static void fieldHit(Facts f, String owner, String name) {
        if (owner.equals("net/minecraft/world/effect/MobEffects")) {
            f.effects.add(effectName(name));
        } else if (owner.equals("net/minecraft/world/entity/EntityType")) {
            if (!name.equals("PLAYER")) {
                f.spawns.add(readable(name).toLowerCase(Locale.ROOT));
            }
        } else if (!owner.startsWith("net/minecraft/") && !owner.startsWith("java/")) {
            String so = owner.substring(owner.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (so.contains("entit")) {
                f.spawns.add(readable(name).toLowerCase(Locale.ROOT));
            } else if (so.contains("effect") && !so.contains("particle")) {
                f.effects.add(readable(name));
            }
        }
    }

    private static void newHit(Facts f, String type, String own) {
        String simple = type.substring(type.lastIndexOf('/') + 1);
        if (type.startsWith("net/minecraft/")) {
            String p = ENGINE_THINGS.get(simple);
            if (p != null) {
                f.things.add(p);
            }
            return;
        }
        if (isEngine(type.replace('/', '.')) || type.equals(own) || type.startsWith(own + "$") || simple.indexOf('$') >= 0) {
            return;
        }
        String ls = simple.toLowerCase(Locale.ROOT);
        if (ls.contains("goal") || ls.contains("packet") || ls.contains("event") || ls.contains("render") || ls.contains("exception")
                || ls.contains("builder") || ls.contains("supplier")) {
            return;
        }
        if (ls.contains("entity") || ls.contains("projectile") || looksAttack(simple)) {
            String r = readable(stripEntityWord(simple)).toLowerCase(Locale.ROOT);
            if (r.length() >= 3) {
                f.spawns.add(r);
            }
        }
    }

    private static String join(Set<String> set) {
        List<String> l = new ArrayList<>(set);
        if (l.size() > 4) {
            l = l.subList(0, 4);
        }
        if (l.size() == 1) {
            return l.get(0);
        }
        return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1);
    }

    /** A sentence or two about what the code does, or null if nothing recognisable was found. */
    private static String describeFacts(Facts f) {
        List<String> s = new ArrayList<>();
        if (!f.things.isEmpty()) {
            s.add("Uses " + join(f.things) + ".");
        } else if (f.shoots) {
            s.add("Fires projectiles.");
        }
        if (!f.spawns.isEmpty()) {
            s.add("Creates or summons " + join(f.spawns) + ".");
        } else if (f.spawnsAny) {
            s.add("Creates extra entities.");
        }
        if (f.explodes) {
            s.add("Causes an explosion.");
        }
        if (f.hurts) {
            s.add("Damages what it hits.");
        }
        if (!f.effects.isEmpty()) {
            s.add("Applies " + join(f.effects) + ".");
        }
        if (f.fire) {
            s.add("Sets targets on fire.");
        }
        if (f.knock) {
            s.add("Pushes or launches (knockback or a dash).");
        }
        if (f.teleport) {
            s.add("Teleports.");
        }
        if (f.heal) {
            s.add("Heals or changes its own health.");
        }
        if (f.area) {
            s.add("Affects entities in an area around it.");
        }
        if (f.blocks) {
            s.add("Breaks or places blocks.");
        }
        if (s.isEmpty()) {
            return null;
        }
        String out = String.join(" ", s);
        return out.length() > 230 ? out.substring(0, 227) + "..." : out;
    }

    private static String hintFor(String name) {
        for (String[] h : NAME_HINTS) {
            if (tokenHit(name, new String[] {h[0]})) {
                return h[1];
            }
        }
        return null;
    }

    /** What to tell about an attack: what its code does, else what its name suggests, else nothing. */
    private static String describe(Facts f, String name) {
        String d = describeFacts(f);
        if (d != null) {
            return d + " (read from its code)";
        }
        String h = name == null ? null : hintFor(name);
        return h == null ? null : "Guessed from its name: " + h + ".";
    }

    private static String labelFromFacts(Facts f) {
        if (!f.things.isEmpty() || f.shoots) {
            return "Projectile Attack";
        }
        if (f.explodes) {
            return "Explosion Attack";
        }
        if (!f.spawns.isEmpty() || f.spawnsAny) {
            return "Summon";
        }
        if (!f.effects.isEmpty()) {
            return "Effect Attack";
        }
        if (f.fire) {
            return "Fire Attack";
        }
        return "Strike";
    }

    /** The class and the modded classes it extends. */
    private static List<ClassInfo> chain(String cn, ClassLoader cl) {
        List<ClassInfo> out = new ArrayList<>();
        String cur = cn;
        for (int i = 0; i < 6 && cur != null && !isEngine(cur); i++) {
            ClassInfo ci = classInfo(cur, cl);
            out.add(ci);
            cur = ci.superName == null ? null : ci.superName.replace('/', '.');
        }
        return out;
    }

    /** Everything a class does: all its methods, its modded superclasses, and the classes nested in it. */
    private static Facts classFacts(String cn, ClassLoader cl, List<String> modClasses) {
        Facts all = new Facts();
        for (ClassInfo ci : chain(cn, cl)) {
            if (!ci.methods.isEmpty()) {
                all.any = true;
            }
            for (Facts f : ci.methods.values()) {
                all.merge(f);
            }
        }
        int nested = 0;
        for (String mc : modClasses) {
            if (mc.startsWith(cn + "$") && nested++ < 12) {
                for (ClassInfo ci : chain(mc, cl)) {
                    for (Facts f : ci.methods.values()) {
                        all.merge(f);
                    }
                }
            }
        }
        return all;
    }

    /** What one method does, following the calls it makes inside the class (and its modded superclasses). */
    private static void closure(List<ClassInfo> chain, String method, int depth, Set<String> seen, Facts out) {
        if (depth < 0 || !seen.add(method)) {
            return;
        }
        for (ClassInfo ci : chain) {
            Facts f = ci.methods.get(method);
            if (f != null) {
                out.merge(f);
                for (String c : new ArrayList<>(f.calls)) {
                    closure(chain, c, depth - 1, seen, out);
                }
            }
        }
    }

    // ------------------------------------------------------------------ modded mobs: finding attacks

    private static boolean hardNot(String simple) {
        return tokenHit(simple, HARD_NOT_WORDS);
    }

    /**
     * Adds the attack a class stands for, if it is one. The code must do something only an attack does (hurt, explode, fire
     * something, apply an effect, summon). nameRequired: the name has to look like an attack too (classes found by searching the
     * jar); a goal that really runs on the mob needs no attack name. Controllers, managers and the like are never attacks.
     * Anonymous classes are named after what they do (allowAnon: only for goals that really run on the mob).
     */
    private static void addClassFind(Map<String, Found> found, String cn, ClassLoader cl, List<String> mods,
                                     boolean nameRequired, boolean allowAnon, String... keys) {
        if (isEngine(cn)) {
            return;
        }
        String simple = simpleName(cn);
        Facts f = classFacts(cn, cl, mods);
        if (simple == null) {
            if (allowAnon && f.offensive()) {
                addFound(found, labelFromFacts(f), describe(f, null));
            }
            return;
        }
        if (hardNot(simple) || notAttack(simple) || (notClass(simple) && !goalish(simple))) {
            return;
        }
        boolean attackName = looksAttack(simple);
        boolean ok = f.any ? (f.offensive() && (attackName || !nameRequired)) : attackName;
        if (!ok) {
            return;
        }
        String r = cleanName(readable(simple), keys);
        if (r.length() < 3 || genericName(r)) {
            if (!f.offensive()) {
                return;
            }
            r = labelFromFacts(f);
        }
        addFound(found, r, describe(f, simple));
    }

    private static void goalFinds(Goal g, List<String> mods, Map<String, Found> found) {
        String raw = g.getClass().getName();
        if (isEngine(raw)) {
            return;
        }
        ClassLoader cl = g.getClass().getClassLoader();
        String n = simpleName(raw);
        boolean wrapper = n != null && tokenHit(n, WRAPPER_WORDS);
        addClassFind(found, raw, cl, mods, false, true);
        Set<String> held = new LinkedHashSet<>();
        dive(g, wrapper ? 2 : 1, wrapper ? 2 : 1, held, Collections.newSetFromMap(new IdentityHashMap<>()));
        for (String hc : held) {
            addClassFind(found, hc, cl, mods, !wrapper, false);
        }
    }

    /** Collects the class names of the mod's objects held (in fields, lists, maps) by o, down to the given depth. */
    private static void dive(Object o, int depth, int top, Set<String> out, Set<Object> seen) {
        if (o == null || depth < 0 || out.size() >= 40 || !seen.add(o) || o instanceof Entity || o instanceof Level) {
            return;
        }
        String cn = o.getClass().getName();
        if (isEngine(cn)) {
            return;
        }
        if (depth < top) {
            out.add(cn);
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
                            dive(x, depth - 1, top, out, seen);
                        }
                    } else if (v instanceof Map<?, ?> map) {
                        int count = 0;
                        for (Object x : map.values()) {
                            if (count++ >= 20) {
                                break;
                            }
                            dive(x, depth - 1, top, out, seen);
                        }
                    } else {
                        dive(v, depth - 1, top, out, seen);
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    // a field that cannot be read: skip it
                }
            }
        }
    }

    private static boolean notClass(String simple) {
        return tokenHit(simple, NOT_CLASS_WORDS);
    }

    private static boolean goalish(String simple) {
        return tokenHit(simple, new String[] {"goal", "action", "attack", "ability", "behavior", "behaviour", "skill", "spell"});
    }

    /**
     * Attacks of a modded mob that its goal list does not show (many boss mods run their attacks from a state machine, a brain
     * or an animation controller):
     * 1. classes of the mod's jar (from its scan data, nothing is loaded) that belong to the mob, that is: nested in its class,
     *    named after it, in a package named after it, or taking it as a parameter or field; they count if they look like an
     *    attack or do something only an attack does;
     * 2. methods of the entity class like startSlam / castMissile, with everything they call;
     * 3. attack-like animation fields and string constants of the entity class (animation triggers).
     * Anything found is only listed (nothing to edit); extraAttacks in the config overrides any miss or wrong guess.
     */
    private static void moddedFinds(Class<?> entityClass, String namespace, String id, Map<String, Found> found) {
        String path = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT).replace("_", "");
        String ownClass = entityClass == null ? "" : entityClass.getName();
        String classKey = entityClass == null ? "" : stripEntityWord(entityClass.getSimpleName()).toLowerCase(Locale.ROOT);
        String ownInternal = ownClass.replace('.', '/');
        ClassLoader cl = entityClass != null ? entityClass.getClassLoader() : DamageDefaults.class.getClassLoader();
        List<String> mods = classesOfMod(namespace);

        // 1. the mod's jar
        int checked = 0;
        for (String cn : mods) {
            if (found.size() >= MAX_SPECIAL || checked >= MAX_CANDIDATES) {
                break;
            }
            if (cn.equals(ownClass) || isEngine(cn)) {
                continue;
            }
            String simple = simpleName(cn);
            String lowerFull = cn.toLowerCase(Locale.ROOT);
            String pkg = lowerFull.substring(0, Math.max(0, lowerFull.lastIndexOf('.')));
            boolean nested = !ownClass.isEmpty() && cn.startsWith(ownClass + "$");
            boolean named = (path.length() >= 3 && lowerFull.contains(path)) || (classKey.length() >= 3 && lowerFull.contains(classKey));
            boolean packaged = false;
            for (String seg : pkg.split("\\.")) {
                if ((path.length() >= 3 && seg.equals(path)) || (classKey.length() >= 3 && seg.equals(classKey))) {
                    packaged = true;
                    break;
                }
            }
            boolean attackPkg = false;
            for (String p : ATTACK_PACKAGES) {
                if (pkg.contains(p)) {
                    attackPkg = true;
                    break;
                }
            }
            boolean attackName = simple != null && looksAttack(simple);
            if (!(nested || named || packaged || attackPkg || attackName)) {
                continue; // not a candidate: its class file is not even read
            }
            if (simple == null || hardNot(simple) || notAttack(simple) || (notClass(simple) && !goalish(simple))) {
                continue;
            }
            boolean associated = nested || named || packaged;
            checked++;
            if (!associated && !ownInternal.isEmpty()) {
                // A goal or ability that takes the mob as a parameter or field belongs to it, whatever it is called.
                associated = classInfo(cn, cl).refs.contains(ownInternal);
            }
            if (!associated) {
                continue;
            }
            addClassFind(found, cn, cl, mods, true, false, path, classKey);
        }

        if (entityClass == null) {
            return;
        }
        // 2 + 3. the entity class and its modded superclasses
        List<ClassInfo> chain = chain(ownClass, cl);
        boolean readableCode = false;
        for (ClassInfo ci : chain) {
            readableCode |= !ci.methods.isEmpty();
        }
        for (Class<?> k = entityClass; k != null && k != Object.class && !isEngine(k.getName()); k = k.getSuperclass()) {
            try {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.isSynthetic() || m.isBridge() || m.getName().contains("$")) {
                        continue;
                    }
                    String name = m.getName();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!startsWithPrefix(lower) || !looksAttack(name) || notAttack(name)
                            || lower.startsWith("is") || lower.startsWith("get") || lower.startsWith("set")
                            || lower.startsWith("can") || lower.startsWith("should") || lower.startsWith("has")
                            || lower.contains("hurt") || lower.contains("target")) {
                        continue;
                    }
                    String r = readable(stripPrefix(name));
                    if (r.length() < 3 || genericName(r)) {
                        continue;
                    }
                    Facts f = new Facts();
                    closure(chain, name, 4, new HashSet<>(), f);
                    if (readableCode && !f.offensive()) {
                        continue;
                    }
                    addFound(found, r, describe(f, stripPrefix(name)));
                }
                for (Field f : k.getDeclaredFields()) {
                    String tn = f.getType().getSimpleName().toLowerCase(Locale.ROOT);
                    String name = f.getName();
                    if (!(tn.contains("animation") || tn.contains("animstate")) || !looksAttack(name) || notAttack(name)) {
                        continue;
                    }
                    String r = readable(name.replaceAll("(?i)(anim|animation|state)$", ""));
                    if (r.length() >= 3 && !genericName(r)) {
                        addFound(found, r, describe(new Facts(), r));
                    }
                }
                for (Class<?> inner : k.getDeclaredClasses()) {
                    addClassFind(found, inner.getName(), cl, mods, true, false, path, classKey);
                }
            } catch (LinkageError | RuntimeException ex) {
                // a member type from a mod that is not installed: skip this class level
            }
        }
        if (found.isEmpty()) {
            // Last resort: animation / attack names in the entity's string constants.
            int strings = 0;
            for (ClassInfo ci : chain) {
                for (Facts f : ci.methods.values()) {
                    for (String str : f.strings) {
                        String name = attackString(str);
                        if (name != null && strings++ < 4) {
                            addFound(found, name, describe(new Facts(), name));
                        }
                    }
                }
            }
        }
    }

    /** Names of every class in the jar of the mod (from its scan data, so nothing is loaded). Empty if unavailable. */
    private static List<String> classesOfMod(String modId) {
        List<String> cached = MOD_CLASSES.get(modId);
        if (cached != null) {
            return cached;
        }
        List<String> names = new ArrayList<>();
        try {
            var info = ModList.get().getModFileById(modId);
            if (info != null) {
                var scan = info.getFile().getScanResult();
                if (scan != null) {
                    for (var data : scan.getClasses()) {
                        names.add(data.clazz().getClassName());
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not read the classes of mod {}", modId);
        }
        List<String> result = List.copyOf(names);
        MOD_CLASSES.put(modId, result);
        return result;
    }

    // ------------------------------------------------------------------ names (display)

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
