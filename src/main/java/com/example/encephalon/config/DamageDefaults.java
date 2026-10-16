package com.example.encephalon.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.encephalon.EncephalonMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;

/**
 * Default attack damage of mobs.
 * <p>
 * A mob that already has an attack damage (its ATTACK_DAMAGE attribute) keeps it as its default. A mob that has none (a cow, a
 * chicken, a bat) gets a fallback from the size of its hitbox (width * width * height, in blocks cubed), on Normal difficulty:
 * micro (under 0.125) half a heart, small (under 0.45) one heart, normal two hearts, big (over 1.75) three hearts.
 * The fallback is given to the mob as a real attribute, so anything that makes the mob attack (a goal added by the editor's
 * category setting, or another mod) just works instead of crashing on a missing attribute.
 * <p>
 * Mobs with several attacks that hurt for different amounts list every attack of the built-in table below (vanilla only);
 * modded mobs list their melee attack. Each listed attack can be overridden per mob type (see DamageOverrides).
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class DamageDefaults {
    public static final String MELEE = "melee";

    /** One attack of a mob. type is the damage type it hurts with; null for the melee attack. */
    public record Attack(String id, double def, ResourceKey<DamageType> type) {
    }

    /** Attacks besides melee, for the vanilla mobs that have some with a fixed damage. */
    private static final Map<String, List<Attack>> EXTRAS = Map.of(
            "minecraft:warden", List.of(new Attack("sonic_boom", 10.0, DamageTypes.SONIC_BOOM)),
            "minecraft:blaze", List.of(new Attack("fireball", 5.0, DamageTypes.FIREBALL)),
            "minecraft:shulker", List.of(new Attack("bullet", 4.0, DamageTypes.MOB_PROJECTILE)),
            "minecraft:evoker", List.of(new Attack("fangs", 6.0, DamageTypes.INDIRECT_MAGIC)),
            "minecraft:llama", List.of(new Attack("spit", 1.0, DamageTypes.SPIT)),
            "minecraft:trader_llama", List.of(new Attack("spit", 1.0, DamageTypes.SPIT)),
            "minecraft:wither", List.of(new Attack("skull", 8.0, DamageTypes.WITHER_SKULL)));

    /** Types that had no attack damage and were given the size fallback. */
    private static final Map<EntityType<?>, Double> FALLBACK = new ConcurrentHashMap<>();

    private DamageDefaults() {
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

    /** Every attack of the type that can be edited: melee first, then the extra ones. */
    public static List<Attack> attacksOf(EntityType<?> t) {
        List<Attack> out = new ArrayList<>();
        out.add(new Attack(MELEE, defaultMelee(t), null));
        List<Attack> extra = EXTRAS.get(BuiltInRegistries.ENTITY_TYPE.getKey(t).toString());
        if (extra != null) {
            out.addAll(extra);
        }
        return out;
    }

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
        return null;
    }
}
