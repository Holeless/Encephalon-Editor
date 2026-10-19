package com.example.encephalon.config;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Lists what a mob's built-in AI does, for the reference rows of the editor's Behaviors panel. Three sources, deepest last:
 * <ol>
 * <li>short hand-written notes for mobs whose most interesting behavior lives in plain methods (the allay's dancing and
 * duplication), see NOTES;</li>
 * <li>the goals really attached to a throw-away instance of the mob (goalSelector / targetSelector);</li>
 * <li>the bytecode of the mob's class, followed into its brain class (AllayAi, PiglinAi, ... any class named *Ai that the mob
 * refers to, two levels deep): every behavior / goal class it creates is listed. Brain-driven mobs have no goals at all, so
 * without this step they would show nothing. Nothing is loaded or run, the class files are only read (ASM).</li>
 * </ol>
 * Every row is reference only (nothing to edit). Names come from the class names, descriptions from DESC when the class is
 * known, else a generic line.
 */
final class BehaviorScan {
    /** One reference row. */
    record Info(String label, String desc) {
    }

    private static final int MAX_ROWS = 24;
    private static final int MAX_CLASSES = 12;
    private static final String BEHAVIOR_PKG = "net/minecraft/world/entity/ai/behavior/";
    private static final String GOAL_PKG = "net/minecraft/world/entity/ai/goal/";
    private static final String GENERIC = "Part of this mob's built-in AI.";

    /** Classes that only glue other behaviors together or do nothing visible: never listed. */
    private static final Set<String> SKIP = Set.of("DoNothing", "RunOne", "RunSometimes", "GateBehavior", "OneShot", "Behavior",
            "BehaviorControl", "BehaviorUtils", "LookAtTargetSink", "MoveToTargetSink", "CountDownCooldownTicks",
            "SetEntityLookTarget", "SetWalkTargetFromLookTarget", "UpdateActivityFromSchedule", "Swim", "FloatGoal",
            "LookAtPlayerGoal", "RandomLookAroundGoal", "Goal", "WrappedGoal", "GoalSelector", "EntityTracker", "BlockPosTracker",
            "PositionTracker", "ShufflingList", "TargetingConditions");

    /** Names of the classes the mob's own base classes stop at (their code is the shared engine, not the mob). */
    private static final Set<String> BASE = Set.of("net/minecraft/world/entity/Entity", "net/minecraft/world/entity/LivingEntity",
            "net/minecraft/world/entity/Mob", "net/minecraft/world/entity/PathfinderMob", "net/minecraft/world/entity/AgeableMob");

    private static final Map<String, String> DESC = Map.ofEntries(
            Map.entry("GoToWantedItem", "Flies or walks to items it wants and picks them up."),
            Map.entry("GoAndGiveItemsToTarget", "Carries what it holds to the player it likes, or to a note block, and hands it over."),
            Map.entry("StayCloseToTarget", "Keeps close to the player or note block it follows."),
            Map.entry("AnimalPanic", "Runs away in panic when hurt."),
            Map.entry("PanicGoal", "Runs away in panic when hurt."),
            Map.entry("RandomStroll", "Wanders around at random."),
            Map.entry("RandomStrollGoal", "Wanders around at random."),
            Map.entry("WaterAvoidingRandomStrollGoal", "Wanders around at random, staying out of water."),
            Map.entry("WaterAvoidingRandomFlyingGoal", "Flies around at random."),
            Map.entry("RandomSwimmingGoal", "Swims around at random."),
            Map.entry("TemptGoal", "Follows players holding its favourite food."),
            Map.entry("FollowTemptation", "Follows players holding its favourite food."),
            Map.entry("BreedGoal", "Breeds with another animal of its kind when both are fed."),
            Map.entry("AnimalMakeLove", "Breeds with another animal of its kind when both are fed."),
            Map.entry("FollowParentGoal", "Babies follow their parents."),
            Map.entry("BabyFollowAdult", "Babies follow an adult."),
            Map.entry("MeleeAttackGoal", "Attacks in melee."),
            Map.entry("MeleeAttack", "Attacks in melee."),
            Map.entry("RangedAttackGoal", "Attacks from a distance."),
            Map.entry("RangedBowAttackGoal", "Shoots arrows from a distance."),
            Map.entry("RangedCrossbowAttackGoal", "Shoots a crossbow from a distance."),
            Map.entry("HurtByTargetGoal", "Fights back against whatever hurt it."),
            Map.entry("NearestAttackableTargetGoal", "Picks the nearest valid target to attack."),
            Map.entry("StartAttacking", "Picks a target to attack."),
            Map.entry("StopAttackingIfTargetInvalid", "Gives up on targets that are gone or no longer valid."),
            Map.entry("AvoidEntityGoal", "Keeps away from certain creatures."),
            Map.entry("SetWalkTargetAwayFrom", "Runs away from what scares it."),
            Map.entry("EatBlockGoal", "Eats grass to heal and regrow its wool."),
            Map.entry("BreakDoorGoal", "Breaks down doors."),
            Map.entry("OpenDoorGoal", "Opens doors."),
            Map.entry("RestrictSunGoal", "Avoids sunlight."),
            Map.entry("FleeSunGoal", "Runs for shade in daylight."),
            Map.entry("LeapAtTargetGoal", "Leaps at its target."),
            Map.entry("FollowOwnerGoal", "Follows its owner."),
            Map.entry("SitWhenOrderedToGoal", "Sits down when told to."),
            Map.entry("BegGoal", "Begs when a player holds its favourite food."),
            Map.entry("InteractWith", "Interacts with other mobs nearby."),
            Map.entry("RamTarget", "Charges and rams its target."),
            Map.entry("PrepareRamNearestTarget", "Lines up a charge at the nearest target."),
            Map.entry("SonicBoom", "Fires its sonic boom at its target."),
            Map.entry("Roar", "Roars when it finds something."),
            Map.entry("Sniffing", "Sniffs out nearby players."),
            Map.entry("TryToSniff", "Sniffs out nearby players."),
            Map.entry("Digging", "Digs itself into the ground."),
            Map.entry("Emerging", "Climbs out of the ground."));

    /** Behavior that lives in plain methods, so neither goals nor brain classes show it. */
    private static final Map<String, List<Info>> NOTES = Map.of(
            "minecraft:allay", List.of(
                    new Info("Dancing", "Dances while a jukebox plays nearby."),
                    new Info("Duplication", "While dancing, a player can feed it an amethyst shard to make a copy of it. Then it needs a long cooldown."),
                    new Info("Note block memory", "Remembers a note block it heard and gathers matching items near it.")));

    private BehaviorScan() {
    }

    /** goals: the goals of a throw-away instance (may be empty). entityId: e.g. "minecraft:allay". */
    static List<Info> scan(Class<?> entityClass, Collection<Goal> goals, String entityId) {
        Map<String, Info> out = new LinkedHashMap<>();
        List<Info> notes = NOTES.get(entityId);
        if (notes != null) {
            for (Info n : notes) {
                out.put(n.label().toLowerCase(), n);
            }
        }
        for (Goal g : goals) {
            addClass(out, g.getClass().getName().replace('.', '/'));
        }
        if (entityClass != null) {
            try {
                followBytecode(out, entityClass);
            } catch (RuntimeException | LinkageError ex) {
                // best effort: what was found so far is kept
            }
        }
        return new ArrayList<>(out.values());
    }

    private static String simple(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    private static String readable(String simple) {
        String n = simple;
        if (n.endsWith("Goal") && n.length() > 4) {
            n = n.substring(0, n.length() - 4);
        }
        String spaced = n.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").trim();
        return spaced.isEmpty() ? simple : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static void addClass(Map<String, Info> out, String internal) {
        if (out.size() >= MAX_ROWS || internal.startsWith("java/") || internal.startsWith("com/example/encephalon/")
                || internal.contains("/declarative/")) {
            return;
        }
        String s = simple(internal);
        if (s.isEmpty() || s.indexOf('$') >= 0 || Character.isDigit(s.charAt(0)) || SKIP.contains(s)) {
            return;
        }
        String label = readable(s);
        out.putIfAbsent(label.toLowerCase(), new Info(label, DESC.getOrDefault(s, GENERIC)));
    }

    private static boolean isAi(String internal) {
        if (internal.startsWith("java/") || internal.startsWith("com/mojang/") || internal.startsWith("net/neoforged/")) {
            return false;
        }
        String s = simple(internal);
        return s.endsWith("Ai") || s.endsWith("AI");
    }

    private static boolean isBehaviorClass(String internal) {
        if (internal.startsWith(BEHAVIOR_PKG)) {
            return !internal.contains("/declarative/");
        }
        if (internal.startsWith(GOAL_PKG)) {
            return true;
        }
        // a mod's own behavior / goal packages
        return !internal.startsWith("net/minecraft/") && !internal.startsWith("java/") && !internal.startsWith("net/neoforged/")
                && !internal.startsWith("com/mojang/") && (internal.contains("/goal/") || internal.contains("/behavior/")
                || internal.contains("/behaviour/"));
    }

    private static void followBytecode(Map<String, Info> out, Class<?> entityClass) {
        ClassLoader cl = entityClass.getClassLoader();
        Deque<String> queue = new ArrayDeque<>();
        Map<String, Integer> depth = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int levels = 0;
        for (Class<?> k = entityClass; k != null && k != Object.class && levels < 4; k = k.getSuperclass(), levels++) {
            String n = k.getName().replace('.', '/');
            if (BASE.contains(n)) {
                break;
            }
            if (seen.add(n)) {
                queue.add(n);
                depth.put(n, 0);
            }
        }
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_CLASSES) {
            String cn = queue.poll();
            int d = depth.getOrDefault(cn, 0);
            for (String r : refsOf(cn, cl)) {
                if (isBehaviorClass(r)) {
                    addClass(out, r);
                } else if (d < 2 && isAi(r) && seen.add(r)) {
                    depth.put(r, d + 1);
                    queue.add(r);
                }
            }
        }
    }

    /** Every class a class creates, calls into or reads a static from (owners of its instructions). */
    private static Set<String> refsOf(String internal, ClassLoader cl) {
        Set<String> refs = new LinkedHashSet<>();
        ClassLoader use = cl != null ? cl : BehaviorScan.class.getClassLoader();
        try (InputStream in = use.getResourceAsStream(internal + ".class")) {
            if (in == null) {
                return refs;
            }
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
                            refs.add(owner);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                            refs.add(owner);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            refs.add(type);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String dname, String ddesc, Handle bsm, Object... bsmArgs) {
                            for (Object a : bsmArgs) {
                                if (a instanceof Handle h) {
                                    refs.add(h.getOwner());
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception | LinkageError ex) {
            // class not readable: nothing from it
        }
        return refs;
    }
}
