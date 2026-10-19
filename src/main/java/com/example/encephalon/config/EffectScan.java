package com.example.encephalon.config;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;

/**
 * Finds the effects a piece of code applies, and the duration / level written in it, by reading its bytecode (nothing is run):
 * the pattern new MobEffectInstance(EFFECT, duration, amplifier, ...). A number that is a plain constant is reported; one that is
 * computed (the bee's poison depends on the difficulty) is reported as unknown (-1), and the effect is still listed so its duration
 * and level can be overridden (see EffectOverrides). Nothing that is not an effect applied by the mob is listed.
 */
final class EffectScan {
    private static final String INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    private static final int WINDOW = 14;

    /** ticks / amp are -1 when the code computes them. */
    record Use(String effectId, int ticks, int amp) {
    }

    private EffectScan() {
    }

    static List<Use> scan(Collection<Class<?>> classes) {
        Map<String, Use> out = new LinkedHashMap<>();
        for (Class<?> c : classes) {
            try {
                read(c, out);
            } catch (Exception | LinkageError ex) {
                // unreadable class: nothing from it
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void add(Map<String, Use> out, Use u) {
        Use old = out.get(u.effectId());
        if (old == null || (old.ticks() < 0 && u.ticks() >= 0)) {
            out.put(u.effectId(), u);
        }
    }

    private static String resolve(String owner, String name, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(owner.replace('/', '.'), false, cl);
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof Holder<?> h) {
                return h.unwrapKey().map(k -> k.location().toString()).orElse(null);
            }
            if (v instanceof MobEffect m) {
                var key = BuiltInRegistries.MOB_EFFECT.getKey(m);
                return key == null ? null : key.toString();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            // not an effect holder
        }
        return null;
    }

    private static void read(Class<?> cls, Map<String, Use> out) throws Exception {
        ClassLoader cl = cls.getClassLoader();
        ClassLoader use = cl != null ? cl : EffectScan.class.getClassLoader();
        try (InputStream in = use.getResourceAsStream(cls.getName().replace('.', '/') + ".class")) {
            if (in == null) {
                return;
            }
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] ex) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        String owner;
                        String field;
                        final List<Integer> ints = new ArrayList<>();
                        boolean dyn;
                        int n;

                        private void tick() {
                            if (owner != null && ++n > WINDOW) {
                                owner = null;
                            }
                        }

                        @Override
                        public void visitFieldInsn(int op, String o, String name, String fdesc) {
                            if (op == Opcodes.GETSTATIC && fdesc.contains("Holder")) {
                                owner = o;
                                field = name;
                                ints.clear();
                                dyn = false;
                                n = 0;
                                return;
                            }
                            if (owner != null) {
                                dyn = true;
                                tick();
                            }
                        }

                        @Override
                        public void visitInsn(int op) {
                            if (owner == null) {
                                return;
                            }
                            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
                                ints.add(op - Opcodes.ICONST_0);
                            } else if (op != Opcodes.DUP) {
                                dyn = true;
                            }
                            tick();
                        }

                        @Override
                        public void visitIntInsn(int op, int v) {
                            if (owner != null) {
                                if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) {
                                    ints.add(v);
                                } else {
                                    dyn = true;
                                }
                                tick();
                            }
                        }

                        @Override
                        public void visitLdcInsn(Object v) {
                            if (owner != null) {
                                if (v instanceof Integer i) {
                                    ints.add(i);
                                } else {
                                    dyn = true;
                                }
                                tick();
                            }
                        }

                        @Override
                        public void visitVarInsn(int op, int var) {
                            if (owner != null) {
                                dyn = true;
                                tick();
                            }
                        }

                        @Override
                        public void visitMethodInsn(int op, String o, String name, String d, boolean itf) {
                            if (owner == null) {
                                return;
                            }
                            if (o.equals(INSTANCE) && name.equals("<init>")) {
                                String id = resolve(owner, field, use);
                                if (id != null) {
                                    boolean known = !dyn && !ints.isEmpty();
                                    int ticks = known ? ints.get(0) : -1;
                                    int amp = known ? (ints.size() > 1 ? ints.get(1) : 0) : -1;
                                    add(out, new Use(id, ticks, amp));
                                }
                                owner = null;
                            } else {
                                dyn = true;
                                tick();
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }

    static Set<String> stopNames() {
        return Set.of("Entity", "LivingEntity", "Mob", "PathfinderMob", "AgeableMob");
    }
}
