package com.example.encephalon.client;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.example.encephalon.EncephalonMod;
import com.example.encephalon.behavior.Behavior;
import com.example.encephalon.behavior.Behaviors;
import com.example.encephalon.behavior.Setting;
import com.example.encephalon.config.CategoryBehavior;
import com.example.encephalon.config.ConfigResolver;
import com.example.encephalon.config.DamageDefaults;
import com.example.encephalon.config.EncephalonConfig;
import com.example.encephalon.config.MobConfigData;
import com.example.encephalon.config.Tri;
import com.example.encephalon.scope.MobGroups;
import com.example.encephalon.scope.Scope;
import com.example.encephalon.scope.ScopeType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.Dolphin;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Mob Editor. Left: collapsible scope tree (All Mobs > Hostile / Neutral / Passive > mob), with a mob search.
 * Middle: behaviors for the selected scope, with a behavior search (a mob type also gets "Drops", "Category" and "Damage" entries there).
 * Right: scrollable details and settings (or the drops / category / damage editor), or an animated 3D mob preview while nothing is selected.
 * The tree always files a mob under its default category; the Category entry changes how the mob acts in the game, not where it is listed.
 * Singleplayer only for now: it edits the integrated server's MobConfigData directly (networking is a TODO).
 * Preview mobs live in dummy worlds (PreviewLevel) and every piece of their code runs inside PreviewSandbox.
 */
public class MobEditorScreen extends Screen {
    private static final int TOP = 50;
    private static final int BOTTOM_RESERVE = 34;
    private static final int GREY = 0xAAAAAA; // vanilla ChatFormatting.GRAY

    // 3D preview tuning
    private static final long TICK_NANOS = 50_000_000L;
    private static final float WALK_SPEED = 0.5F;       // limb swing amount; the walk cycle advances about this much per tick
    private static final float DRAGON_W = 16.5F;          // blocks: wingspan plus a little sway room
    private static final float DRAGON_H = 11F;          // blocks: wing flap range
    private static final float DRAGON_CENTER_Y = 1F;    // blocks above the dragon's feet where the model's visual middle sits
    // The turtle model multiplies the walk position by about 5, so at the normal speed its legs blur; use a slow walk.
    private static final float TURTLE_WALK_SPEED = 0.15F;
    // The squid's tentacles hang well below its hitbox, so it is drawn smaller and higher to stay clear of the label.
    private static final float SQUID_EXTENT_H = 1.7F;   // blocks: body plus tentacles
    private static final float SQUID_CENTER_Y = -0.2F;  // blocks above the squid's feet where its visual middle sits
    private static final int RABBIT_HOP_INTERVAL = 20;  // ticks between hops (a hop lasts 10)
    // A ghast's tentacles hang far below its hitbox, so like the squid it is drawn smaller and higher.
    private static final float GHAST_EXTENT_H = 6.5F;   // blocks: body plus tentacles
    private static final float GHAST_CENTER_Y = 1F;     // blocks above the ghast's feet where its visual middle sits

    // Modded mobs are fitted from the extent of the vertices that are really drawn (not from the hitbox). A mob that is much
    // longer than it is tall / thick (a worm, snake or leviathan) is "elongated": only the first LENGTH_CAP times its core
    // size of length is fitted, the rest runs off the edge of the panel, and it is turned so that length mostly runs into the
    // screen (GROUP_VIEW_ANGLE radians away from looking straight along it). So a huge snake is shown big and clipped instead
    // of tiny and complete, and no mob is ever scaled up past what fits.
    private static final float ELONGATED_RATIO = 1.6F;
    private static final float LENGTH_CAP = 1.7F;
    // How much of the panel a measured modded mob may fill (the measurement is accurate, so little padding is needed). The
    // panel it is fitted to stops above the name label, so nothing overlaps the text.
    private static final float FILL_H = 0.90F;
    private static final int LABEL_STRIP = 18;          // pixels kept free above the bottom of the panel for the name label
    private static final float FILL_W = 0.95F;
    private static final float GROUP_VIEW_ANGLE = 0.55F;
    private static final double GROUP_SWAY_DEG = 15.0;
    private static final float SMOOTH = 0.15F;          // how fast the shown size / angle follows the fitted one (per frame)
    // A modded mob is kept hidden (and measured) until its size has stopped changing for this many ticks (about one walk cycle),
    // or this many ticks after the warm-up at the latest. So it appears at its final size instead of zooming in and out.
    private static final int STABLE_TICKS = 20;
    private static final int MAX_STABLE_WAIT = 60;
    private static final int MB_BINS = 2048;            // measuring histogram: bins per axis...
    private static final float MB_BIN = 0.25F;          // ...of this many blocks...
    private static final float MB_OFF = 256F;           // ...starting this far below zero

    // Shulkers only open when the server tells them to, so the lid is driven by hand. A shulker that sees its position
    // change also plays a 6-tick "slide in from the old spot" animation, which is pinned off.
    private static final Field SHULKER_PEEK = field(Shulker.class, "currentPeekAmount");
    private static final Field SHULKER_PEEK_O = field(Shulker.class, "currentPeekAmountO");
    private static final Field SHULKER_SLIDE = field(Shulker.class, "clientSideTeleportInterpolation");

    // Vanilla fish are only upright when the game thinks they are in water: out of water the renderer lays them on their side.
    // Vanilla mobs live in the dry dummy world, so the flag is forced on just before drawing. (Modded swimmers live in the
    // flooded dummy world and are really in water.)
    private static final Field WET = field(Entity.class, "wasTouchingWater");
    private static final Field WET_EYES = field(Entity.class, "wasEyeInWater");

    // The walk animation is advanced by hand, once per preview tick. The entity's own tick also updates it (twice-updating
    // makes limbs flutter), so its state is saved before the tick and put back after, through these fields.
    private static final Field WALK_SPEED_OLD = field(WalkAnimationState.class, "speedOld");
    private static final Field WALK_SPEED_NOW = field(WalkAnimationState.class, "speed");
    private static final Field WALK_POS = field(WalkAnimationState.class, "position");
    // Out of water a guardian's tail advances 2 radians per tick; pin it to a gentle swim instead.
    private static final float GUARDIAN_TAIL_SPEED = 0.15F;
    private static final Field GUARDIAN_TAIL = field(Guardian.class, "clientSideTailAnimation");
    private static final Field GUARDIAN_TAIL_O = field(Guardian.class, "clientSideTailAnimationO");
    // Out of water the spikes also snap to a new random value every tick; hold them still.
    private static final float GUARDIAN_SPIKES = 0.45F;
    private static final Field GUARDIAN_SPIKES_F = field(Guardian.class, "clientSideSpikesAnimation");
    private static final Field GUARDIAN_SPIKES_O = field(Guardian.class, "clientSideSpikesAnimationO");
    private static final boolean CAN_PIN_GUARDIAN = GUARDIAN_TAIL != null && GUARDIAN_TAIL_O != null
            && GUARDIAN_SPIKES_F != null && GUARDIAN_SPIKES_O != null;
    private static final boolean CAN_PIN_WALK = WALK_SPEED_OLD != null && WALK_SPEED_NOW != null && WALK_POS != null;

    static {
        if (!CAN_PIN_WALK) {
            EncephalonMod.LOGGER.warn("Mob Editor preview: WalkAnimationState fields not found; non-dragon mobs are animated by hand");
        }
        if (!CAN_PIN_GUARDIAN) {
            EncephalonMod.LOGGER.warn("Mob Editor preview: Guardian animation fields not found; guardians are not animated");
        }
    }

    /** True while the main mob is drawn through an ExtentProbe (off-screen warm-up of a modded mob). */
    private boolean measuring;

    private MinecraftServer server;
    private MobConfigData data;
    private boolean editable;
    /** Which mobs are listed in the tree (see MobListing). Built once when the editor opens. */
    private MobListing listing;

    private PickList scopeList;
    private PickList behaviorList;
    private EditBox mobSearch;
    private EditBox behaviorSearch;
    private Button stateButton;
    private final List<Placed> placed = new ArrayList<>();
    private final List<SettingRow> rows = new ArrayList<>();
    private List<FormattedCharSequence> descLines = List.of();
    /** Warning shown for internal mobs (body parts and the like) at the top of the right panel. */
    private List<FormattedCharSequence> warnLines = List.of();
    private int warnH;
    /** Vanilla mobs by category (hostile / neutral / passive). */
    private final Map<String, List<EntityType<?>>> vanillaByCategory = new HashMap<>();
    /** Modded mobs by mod id (sorted by mod name), then by category. */
    private final Map<String, Map<String, List<EntityType<?>>>> moddedByMod = new LinkedHashMap<>();
    private final Set<String> expanded = new HashSet<>(Set.of(Scope.VANILLA.key()));

    // ------------------------------------------------------------------ drops editor state
    /** Key of the "Drops" row in the behavior list. */
    private static final Object DROPS_KEY = new Object();
    private static final int PICK_CELL = 18;
    private static final int DROP_ROW_H = 24;
    private static final int DROP_MAX_AMOUNT = 64;
    /** Key of the "Category" row in the behavior list. */
    private static final Object CATEGORY_KEY = new Object();
    /** Key of the "Damage" row in the behavior list. */
    private static final Object DAMAGE_KEY = new Object();
    private static final double DAMAGE_MAX = 1000.0;
    /** True while the "Category" row is the open entry (only for a mob type; behavior is null then). */
    private boolean categoryOpen;
    /** True while the "Damage" row is the open entry (only for a mob type; behavior is null then). */
    private boolean damageOpen;
    /** When the default drops of the shown mob were first asked for (0 = not waiting); the loading text only shows after a pause. */
    private long loadingSince;

    /** True while the "Drops" row of the behavior list is the open entry (only for a mob type; behavior is null then). */
    private boolean dropsOpen;
    /** True while the item picker (add a drop) is shown instead of the drop rows. */
    private boolean addMode;
    /** The drops shown: the custom list, or the default loot table worked out as a list. Null while that is still being rolled. */
    private List<MobConfigData.DropEntry> dropEntries;
    /** What dropEntries was made from (the custom list, or the default drops of DropsPanel); a change means: rebuild. */
    private Object dropsSource;
    private final DropsPanel dropsPanel = new DropsPanel();
    private List<FormattedCharSequence> dropsDesc = List.of();
    private int dropsStateY;
    private int dropsHeaderY;
    private final List<Integer> dropRowYs = new ArrayList<>();
    private final List<ItemStack> dropStacks = new ArrayList<>();
    private final List<DropBox> dropBoxes = new ArrayList<>();
    private Button dropsReset;
    private int colChanceX;
    private int colChanceW;
    private int colMinX;
    private int colMinW;
    private int colMaxX;
    private int colMaxW;
    private int colRemoveX;
    private EditBox pickSearch;
    private String pickFilter = "";
    private List<Item> pickResults = List.of();
    private List<PickEntry> allItems;
    private int gridY;
    private int gridScroll;
    /** The item the mouse is over in the drops editor; its name is drawn as a tooltip after everything else. */
    private ItemStack hovered = ItemStack.EMPTY;

    private record PickEntry(Item item, String name, String id) {
    }

    // ------------------------------------------------------------------ damage editor state
    private List<FormattedCharSequence> damageDesc = List.of();
    private int damageStateY;
    /** True when the shown mob has no attack of its own, so its default melee damage comes from its size. */
    private boolean damageFallback;
    private final List<DamageRow> damageRows = new ArrayList<>();
    private Button damageReset;    private List<DamageDefaults.Attack> damageAttacks = List.of();

        private record DamageRow(DamageDefaults.Attack attack, int y, List<FormattedCharSequence> info) {
    }

    // ------------------------------------------------------------------ 3D preview state
    // Animation libraries (GeckoLib and the like) drive a mob from its first render: they start from the bind pose and blend
    // into idle, then blend again into the walk once the walk state kicks in. A modded mob is therefore drawn off-screen
    // (empty scissor) for this long first, so those blends finish before anyone sees it.
    private static final int WARMUP_TICKS = 10;      // simulated ticks of animation before a modded mob may be shown
    // Warm-up ticks simulated per frame, at most. They are all stepped first and the mob is then drawn off-screen ONCE for the
    // whole batch: the draw is by far the expensive part (a big GeckoLib model costs far more than its tick), and animation
    // libraries read the mob's tick count when they draw, so a draw per batch blends exactly as well as a draw per tick.
    private static final int FAST_STEPS = 5;
    /** Counts rendered editor frames; lets per-frame work (the companion scan) be done once and shared. */
    private long frame;
    // Mods often build a mob out of other entities that only the server creates (body segments, a detached head, ...).
    // For modded mobs a hidden, AI-less twin is spawned on the integrated server at the same spot; whatever extra
    // entities it makes show up in the client level near the anchor and are drawn together with the preview mob.
    // Each twin lives in its own "slot", stacked vertically, so the parts of the old and the new mob never mix while the old
    // one is being removed.
    private static final int SLOTS = 2;
    private static final double SLOT_SPACING = 96.0;
    private static final String PREVIEW_TAG = "encephalon_preview";
    // Companions are spawned all at once at their starting spots and then drift into place (the Void Worm even staggers
    // them out of a portal), which looks like pieces popping in. They are only drawn once the group has stopped changing.
    // The twin is fast-forwarded on the server right after it is spawned (see preTickTwin), so all of that has already
    // happened by the time the client first sees it, and these waits only have to cover the network round trip.
    private static final int SETTLE_STABLE_TICKS = 5;  // the number of companions must not change for this long
    private static final int SETTLE_MIN_TICKS = 8;      // and at least this long must have passed since the twin spawned
    private static final int SOLO_TICKS = 30;           // a twin that made no companions by now never will: drop it
    // Server-side fast-forward of a fresh twin: up to this many ticks of the twin and everything it made, within this time.
    // It runs on the server thread without blocking the render thread.
    private static final int TWIN_PRETICKS = 48;
    private static final long TWIN_PRETICK_BUDGET_NANOS = 30_000_000L;
    // Result of the asynchronous twin spawn: pending, failed, or the entity id (>= 0).
    private static final int TWIN_PENDING = -2;
    private static final int TWIN_FAILED = -1;
    // Frame-time governor for the shown mob (see govern()): above FREEZE its own tick is switched off, above OFF its preview is.
    private static final long FREEZE_NANOS = 10_000_000L;
    private static final long OFF_NANOS = 35_000_000L;
    private static final int GOVERN_SETTLE_FRAMES = 20;
    // A preview mob whose own tick takes longer than this several times is drawn without being ticked from then on.
    private static final long SLOW_TICK_NANOS = 8_000_000L;
    private static final int SLOW_TICK_LIMIT = 4;

    /** Modded types known to have no companions (no twin needed) / to have companions (hold the reveal until settled). */
    private static final Set<EntityType<?>> SOLO = ConcurrentHashMap.newKeySet();
    private static final Set<EntityType<?>> MULTI = ConcurrentHashMap.newKeySet();
    /** Types a server twin could not be made for. */
    private static final Set<EntityType<?>> NO_TWIN = ConcurrentHashMap.newKeySet();

    /** One preview mob: the client entity, the animation clock it is stepped with, and its server twin (if any). */
    private final class Preview {
        final EntityType<?> type;
        final LivingEntity entity;
        final double ax;
        final double ay;
        final double az;
        int slot;
        int twinId = -1;
        final java.util.concurrent.atomic.AtomicInteger twinResult = new java.util.concurrent.atomic.AtomicInteger(TWIN_PENDING);
        /** Frame-time governor state (see govern()). */
        double avgNanos;
        int govFrames;
        int hugeFrames;
        long sim = System.nanoTime();
        long firstRender;
        long twinStartTick;
        int lastCount = -1;
        long countChangedTick;
        boolean settled;
        boolean tickFailed;
        /** Entity ticks that were slow (see SLOW_TICK_NANOS); at SLOW_TICK_LIMIT the mob stops being ticked. */
        int slowTicks;
        /** The companion list of the last scan and the frame it was made in (one scan per frame, shared by every caller). */
        long compFrame = -1;
        List<Entity> compCache = List.of();
        int warmTicks;
        // How far the model really reaches along each axis (x, y, z), in blocks from the mob's anchor, recorded from the
        // vertices that are actually drawn. The histograms let a few stray vertices be ignored.
        final int[][] hist = new int[3][MB_BINS];
        final float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        final float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        int samples;
        // Distance of the vertices from the mob's vertical axis (its anchor): a round mob is narrower than its box.
        final int[] rhist = new int[MB_BINS];
        float rmax;
        // What is shown eases toward the fitted values, so a measurement that is still growing does not make the mob jump.
        float sScale = -1F;
        float sCenterY;
        float sPivX;
        float sPivZ;
        float sYaw;
        // Size tracking for the reveal: the model's extent is snapshotted whenever it changes, and the mob counts as steady
        // once it has stayed within a few percent of the snapshot for STABLE_TICKS ticks.
        float[] snap;
        int stableTicks;
        boolean stable;
        // The space the settled companions (body segments) have ever occupied, in blocks from the anchor: minX, minY, minZ,
        // maxX, maxY, maxZ. It only grows, so a worm that wiggles does not make the fit (and the zoom) wiggle with it.
        final float[] env = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean hasEnv;
        // Once a mob has been shown as "elongated" it stays that way, and keeps its long axis, so it never flips back and forth.
        boolean elongLatched;
        boolean longX;

        /** Adds every companion (not just the ones that are drawn) to the envelope, once the group has settled. */
        void growEnv(List<Entity> companions) {
            if (!settled) {
                return;
            }
            for (Entity c : companions) {
                AABB b = c.getBoundingBox();
                env[0] = Math.min(env[0], (float) (b.minX - ax));
                env[1] = Math.min(env[1], (float) (b.minY - ay));
                env[2] = Math.min(env[2], (float) (b.minZ - az));
                env[3] = Math.max(env[3], (float) (b.maxX - ax));
                env[4] = Math.max(env[4], (float) (b.maxY - ay));
                env[5] = Math.max(env[5], (float) (b.maxZ - az));
                hasEnv = true;
            }
        }

        Preview(EntityType<?> type, LivingEntity entity, double ay, int slot) {
            this.type = type;
            this.entity = entity;
            this.ax = anchorX;
            this.ay = ay;
            this.az = anchorZ;
            this.slot = slot;
            this.settled = slot < 0;
        }

        AABB box() {
            return new AABB(ax - 64, ay - 48, az - 64, ax + 64, ay + 48, az + 64);
        }

        /** Vanilla mobs are measured once, off-screen, with this set (see drawActive), so their size is the drawn size. */
        boolean forceMeasure;
        boolean vanillaMeasured;

        boolean canMeasure() {
            return forceMeasure || (!isVanilla(type) && !stable);
        }

        /**
         * Called after each rendered warm-up batch of `steps` simulated ticks: decides when the model's size has settled.
         * The extent only changes when the mob is drawn, so a batch counts as `steps` ticks of steadiness or none.
         */
        void trackStable(int steps) {
            if (stable) {
                return;
            }
            if (warmTicks >= WARMUP_TICKS + MAX_STABLE_WAIT) {
                stable = true;
                return;
            }
            if (warmTicks < WARMUP_TICKS || !hasMeasure()) {
                return;
            }
            float[] cur = new float[6];
            float[] r = new float[2];
            float size = 0F;
            for (int a = 0; a < 3; a++) {
                range(a, r);
                cur[2 * a] = r[0];
                cur[2 * a + 1] = r[1];
                size = Math.max(size, r[1] - r[0]);
            }
            float tol = Math.max(0.05F * size, 0.05F);
            boolean same = snap != null;
            if (same) {
                for (int i = 0; i < 6; i++) {
                    if (Math.abs(cur[i] - snap[i]) > tol) {
                        same = false;
                        break;
                    }
                }
            }
            if (same) {
                stableTicks += Math.max(1, steps);
                if (stableTicks >= STABLE_TICKS) {
                    stable = true;
                }
            } else {
                snap = cur;
                stableTicks = 0;
            }
        }

        /** Records a vertex of the model, in blocks from the anchor (x, z horizontal, y up). */
        void measure(float wx, float wy, float wz) {
            // The first warm-up ticks are the bind pose (limbs wherever the model file left them); only the settled ones count.
            if ((!forceMeasure && warmTicks < WARMUP_TICKS - 4) || Float.isNaN(wx + wy + wz)
                    || Math.abs(wx) > 250F || Math.abs(wy) > 250F || Math.abs(wz) > 250F) {
                return;
            }
            add(0, wx);
            add(1, wy);
            add(2, wz);
            float rr = (float) Math.sqrt(wx * wx + wz * wz);
            rhist[Math.min(MB_BINS - 1, (int) (rr / MB_BIN))]++;
            rmax = Math.max(rmax, rr);
            samples++;
        }

        private void add(int axis, float v) {
            int bin = Math.max(0, Math.min(MB_BINS - 1, (int) ((v + MB_OFF) / MB_BIN)));
            hist[axis][bin]++;
            lo[axis] = Math.min(lo[axis], v);
            hi[axis] = Math.max(hi[axis], v);
        }

        boolean hasMeasure() {
            return samples >= (isVanilla(type) ? 24 : 200);
        }

        /** Distance from the anchor's vertical axis that nearly all vertices are within (outermost 0.2% ignored). */
        float radius() {
            int cut = Math.max(1, samples / 500);
            int c = 0;
            int j = MB_BINS - 1;
            while (j > 0 && (c += rhist[j]) < cut) {
                j--;
            }
            return Math.min(rmax, (j + 1) * MB_BIN);
        }

        /** The extent along an axis, ignoring the outermost 0.2% of vertices at each end (stray bits of a model). */
        void range(int axis, float[] out) {
            int[] h = hist[axis];
            int cut = Math.max(1, samples / 500);
            int c = 0;
            int i = 0;
            while (i < MB_BINS - 1 && (c += h[i]) < cut) {
                i++;
            }
            c = 0;
            int j = MB_BINS - 1;
            while (j > 0 && (c += h[j]) < cut) {
                j--;
            }
            float qLo = i * MB_BIN - MB_OFF;
            float qHi = (j + 1) * MB_BIN - MB_OFF;
            out[0] = Math.max(lo[axis], qLo);
            out[1] = Math.min(hi[axis], qHi);
            if (out[1] <= out[0]) {
                out[0] = lo[axis];
                out[1] = hi[axis];
            }
        }
    }

    private Preview active;
    private final boolean[] slotUsed = new boolean[SLOTS];
    private final Set<EntityType<?>> badTypes = new HashSet<>();
    /** quiet() answers, per type (the config lookup and tag check are too slow to repeat on every tick). */
    private final Map<EntityType<?>, Boolean> quietCache = new HashMap<>();
    // Where the previews are parked (x/z fixed when the editor opens, y is slot 0), so they never count as having moved.
    private boolean anchorReady;
    private double anchorX;
    private double anchorY;
    private double anchorZ;

    private Scope scope = Scope.VANILLA;
    private Behavior behavior;
    private String mobFilter = "";
    private String behaviorFilter = "";
    private int mx;
    private int mw;
    private int rx;
    private int rw;
    private int explainY;
    private int scroll;
    private int contentHeight;

    private record Placed(AbstractWidget widget, int baseY) {
    }

    private record SettingRow(Setting setting, int y) {
    }

    public MobEditorScreen() {
        super(Component.translatable("encephalon.screen.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The mob preview takes the whole right panel (the drops live in the behavior list now). */
    private int previewBottom() {
        return viewBottom();
    }

    private int viewBottom() {
        return height - BOTTOM_RESERVE;
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void init() {
        placed.clear();
        rows.clear();
        dropBoxes.clear();
        damageRows.clear();
        dropsReset = null;
        damageReset = null;
        pickSearch = null;
        stateButton = null;
        scroll = 0;

        server = this.minecraft.getSingleplayerServer();
        editable = server != null && this.minecraft.player != null && this.minecraft.player.hasPermissions(2);
        if (server != null && data == null) {
            MobConfigData[] box = new MobConfigData[1];
            server.executeBlocking(() -> box[0] = MobConfigData.get(server));
            data = box[0];
            listing = MobListing.scan(this.minecraft, server);
            setupPreviewZone();
            sweepOrphans();
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 + 5, height - 28, 150, 20).build());
        if (data == null) {
            return;
        }
        addRenderableWidget(Button.builder(Component.translatable("encephalon.screen.reset_scope"), b -> resetScope())
                .bounds(width / 2 - 155, height - 28, 150, 20).build()).active = editable;

        PreviewSandbox.run(this::buildTypes);

        int lw = Mth.clamp(width * 27 / 100, 100, 190);
        mw = Mth.clamp(width * 27 / 100, 100, 190);
        int lx = 8;
        mx = lx + lw + 6;
        rx = mx + mw + 6;
        rw = Math.max(100, width - 14 - rx);
        int listH = height - TOP - BOTTOM_RESERVE;

        mobSearch = new EditBox(this.font, lx, 28, lw, 16, Component.empty());
        mobSearch.setHint(Component.translatable("encephalon.screen.search"));
        mobSearch.setValue(mobFilter);
        mobSearch.setResponder(s -> {
            mobFilter = s.toLowerCase().trim();
            rebuildScopes();
        });
        addRenderableWidget(mobSearch);

        behaviorSearch = new EditBox(this.font, mx, 28, mw, 16, Component.empty());
        behaviorSearch.setHint(Component.translatable("encephalon.screen.search_behaviors"));
        behaviorSearch.setValue(behaviorFilter);
        behaviorSearch.setResponder(s -> {
            behaviorFilter = s.toLowerCase().trim();
            rebuildBehaviors();
        });
        addRenderableWidget(behaviorSearch);

        // 20px rows so the 16px spawn egg icons fit inside the selection box.
        scopeList = addRenderableWidget(new PickList(this.minecraft, lx, TOP, lw, listH, 20));
        behaviorList = addRenderableWidget(new PickList(this.minecraft, mx, TOP, mw, listH, 22));
        rebuildScopes();
        rebuildBehaviors();
        rebuildDetail();
    }

    private static String modName(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getDisplayName())
                .orElse(modId);
    }

    private void buildTypes() {
        vanillaByCategory.clear();
        moddedByMod.clear();
        for (String c : MobGroups.CATEGORIES) {
            vanillaByCategory.put(c, new ArrayList<>());
        }
        Map<String, Map<String, List<EntityType<?>>>> byMod = new HashMap<>();
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            String c = categoryOf(t);
            if (c == null || !listing.isListed(t)) {
                continue;
            }
            // Modded mobs filed under MISC are kept (see MobGroups), but MISC also holds things that are not mobs at all.
            if (t.getCategory() == MobCategory.MISC && !isVanilla(t) && !isMobClass(t)) {
                continue;
            }
            String ns = BuiltInRegistries.ENTITY_TYPE.getKey(t).getNamespace();
            if (ns.equals("minecraft")) {
                vanillaByCategory.get(c).add(t);
            } else {
                byMod.computeIfAbsent(ns, k -> new HashMap<>()).computeIfAbsent(c, k -> new ArrayList<>()).add(t);
            }
        }
        Comparator<EntityType<?>> byName = Comparator.comparing(t -> t.getDescription().getString());
        for (String c : MobGroups.CATEGORIES) {
            List<EntityType<?>> list = vanillaByCategory.get(c);
            list.sort(byName);
        }
        List<String> mods = new ArrayList<>(byMod.keySet());
        mods.sort(Comparator.comparing(MobEditorScreen::modName, String.CASE_INSENSITIVE_ORDER));
        for (String mod : mods) {
            Map<String, List<EntityType<?>>> cats = byMod.get(mod);
            for (String c : MobGroups.CATEGORIES) {
                List<EntityType<?>> list = cats.get(c);
                if (list != null) {
                    list.sort(byName);
                }
            }
            moddedByMod.put(mod, cats);
        }
    }

    private void edit(Runnable r) {
        server.executeBlocking(r);
    }

    private <T extends AbstractWidget> T track(T widget) {
        placed.add(new Placed(widget, widget.getY()));
        return addRenderableWidget(widget);
    }

    // ------------------------------------------------------------------ labels

    private static Component labelOf(Scope s) {
        return switch (s.type()) {
            case WORLD -> Component.translatable("encephalon.scope.world");
            case ALL -> Component.translatable("encephalon.scope.all");
            case ORIGIN -> Component.translatable("encephalon.scope." + s.id());
            case MOD -> Component.literal(modName(s.id()));
            case CATEGORY -> Component.translatable("encephalon.category." + s.id());
            case MOD_CATEGORY -> Component.translatable("encephalon.category." + s.id().substring(s.id().indexOf('/') + 1));
            case MOB_TYPE -> BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(s.id())).getDescription();
            default -> Component.literal(s.id());
        };
    }

    private static Scope mobScope(EntityType<?> t) {
        return new Scope(ScopeType.MOB_TYPE, BuiltInRegistries.ENTITY_TYPE.getKey(t).toString());
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private String fit(String text, int w) {
        return this.font.width(text) <= w ? text : this.font.plainSubstrByWidth(text, Math.max(0, w - this.font.width("..."))) + "...";
    }

    // ------------------------------------------------------------------ scope tree

    private boolean matches(Scope s) {
        return mobFilter.isEmpty() || labelOf(s).getString().toLowerCase().contains(mobFilter);
    }

    /**
     * Adds a category row and, when open, its mobs. While searching, a category is skipped if nothing in it matches;
     * parentMatch means an ancestor row (Vanilla Mobs, a mod) matched, so everything under it is kept.
     */
    private void addCategory(List<PickList.Item> items, Scope cs, List<EntityType<?>> types, int level,
                             boolean filtering, boolean parentMatch) {
        boolean catMatch = parentMatch || matches(cs);
        List<EntityType<?>> shown = new ArrayList<>();
        for (EntityType<?> t : types) {
            if (!filtering || catMatch || matches(mobScope(t))) {
                shown.add(t);
            }
        }
        if (filtering && shown.isEmpty()) {
            return;
        }
        boolean open = filtering || expanded.contains(cs.key());
        items.add(new ScopeItem(cs, level, true, open));
        if (open) {
            for (EntityType<?> t : shown) {
                items.add(new ScopeItem(mobScope(t), level + 1, false, false));
            }
        }
    }

    private void rebuildScopes() {
        boolean filtering = !mobFilter.isEmpty();
        List<PickList.Item> items = new ArrayList<>();
        if (matches(Scope.WORLD)) {
            items.add(new ScopeItem(Scope.WORLD, 0, false, false));
        }

        // Vanilla Mobs > Hostile / Neutral / Passive > mob
        boolean vanillaMatch = filtering && matches(Scope.VANILLA);
        boolean vanillaOpen = filtering || expanded.contains(Scope.VANILLA.key());
        List<PickList.Item> vanilla = new ArrayList<>();
        if (vanillaOpen) {
            for (String cat : MobGroups.CATEGORIES) {
                addCategory(vanilla, new Scope(ScopeType.CATEGORY, cat), vanillaByCategory.get(cat), 1, filtering, vanillaMatch);
            }
        }
        if (!filtering || vanillaMatch || !vanilla.isEmpty()) {
            items.add(new ScopeItem(Scope.VANILLA, 0, true, vanillaOpen));
            items.addAll(vanilla);
        }

        // Modded Mobs > each mod > Hostile / Neutral / Passive > mob
        if (!moddedByMod.isEmpty()) {
            boolean moddedMatch = filtering && matches(Scope.MODDED);
            boolean moddedOpen = filtering || expanded.contains(Scope.MODDED.key());
            List<PickList.Item> modded = new ArrayList<>();
            if (moddedOpen) {
                for (Map.Entry<String, Map<String, List<EntityType<?>>>> entry : moddedByMod.entrySet()) {
                    String mod = entry.getKey();
                    Scope ms = new Scope(ScopeType.MOD, mod);
                    boolean modMatch = filtering && (moddedMatch || matches(ms));
                    boolean modOpen = filtering || expanded.contains(ms.key());
                    List<PickList.Item> cats = new ArrayList<>();
                    if (modOpen) {
                        for (String cat : MobGroups.CATEGORIES) {
                            List<EntityType<?>> types = entry.getValue().get(cat);
                            if (types != null) {
                                addCategory(cats, new Scope(ScopeType.MOD_CATEGORY, mod + "/" + cat), types, 2, filtering, modMatch);
                            }
                        }
                    }
                    if (!filtering || !cats.isEmpty()) {
                        modded.add(new ScopeItem(ms, 1, true, modOpen));
                        modded.addAll(cats);
                    }
                }
            }
            if (!filtering || moddedMatch || !modded.isEmpty()) {
                items.add(new ScopeItem(Scope.MODDED, 0, true, moddedOpen));
                items.addAll(modded);
            }
        }
        scopeList.setItems(items);
        scopeList.select(scope);
    }

    private final class ScopeItem implements PickList.Item {
        private final Scope s;
        private final Component label;
        private final int indent;
        private final boolean expandable;
        private final boolean open;
        private final ItemStack egg;
        private final boolean internal;

        ScopeItem(Scope s, int level, boolean expandable, boolean open) {
            this.s = s;
            this.label = labelOf(s);
            this.indent = level * 8;
            this.expandable = expandable;
            this.open = open;
            ItemStack stack = ItemStack.EMPTY;
            if (s.type() == ScopeType.MOB_TYPE) {
                SpawnEggItem item = SpawnEggItem.byId(BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(s.id())));
                if (item != null) {
                    stack = new ItemStack(item);
                }
            }
            this.egg = stack;
            this.internal = s.type() == ScopeType.MOB_TYPE
                    && listing.isInternal(BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(s.id())));
        }

        @Override
        public Object key() {
            return s;
        }

        @Override
        public void draw(GuiGraphics g, Font f, int x, int y, int w, int h) {
            int n = data.count(s);
            String badge = n > 0 ? String.valueOf(n) : "";
            int badgeW = n > 0 ? f.width(badge) + 4 : 0;
            int ty = y + (h - 8) / 2 + 1;
            int px = x + 2 + indent;
            int tx;
            if (s.type() == ScopeType.MOB_TYPE) {
                if (!egg.isEmpty()) {
                    g.renderFakeItem(egg, px, y + (h - 16) / 2);
                }
                tx = px + 18;
            } else {
                if (expandable) {
                    g.drawString(f, open ? "-" : "+", px + 1, ty, GREY, true);
                }
                tx = px + 9;
            }
            g.drawString(f, fit(label.getString(), w - (tx - x) - 2 - badgeW), tx, ty, internal ? 0xFFB060 : n > 0 ? 0xFFFFFF : 0xC8C8C8, true);
            if (n > 0) {
                g.drawString(f, badge, x + w - f.width(badge) - 2, ty, 0xFFAA00, true);
            }
        }

        @Override
        public void picked() {
            scope = s;
            addMode = false;
            loadingSince = 0L;
            gridScroll = 0;
            pickFilter = "";
            if (expandable && !expanded.remove(s.key())) {
                expanded.add(s.key());
            }
            rebuildScopes();
            rebuildBehaviors();
            rebuildDetail();
        }
    }

    // ------------------------------------------------------------------ behavior list

    private void rebuildBehaviors() {
        List<PickList.Item> items = new ArrayList<>();
        // A mob type gets "Drops", "Category" and "Damage" entries at the top of its behaviors.
        if (scope.type() == ScopeType.MOB_TYPE && (behaviorFilter.isEmpty()
                || Component.translatable("encephalon.screen.drops_title").getString().toLowerCase().contains(behaviorFilter))) {
            items.add(new DropsItem());
        }
        if (scope.type() == ScopeType.MOB_TYPE && (behaviorFilter.isEmpty()
                || Component.translatable("encephalon.screen.category_title").getString().toLowerCase().contains(behaviorFilter))) {
            items.add(new CategoryItem());
        }
        if (scope.type() == ScopeType.MOB_TYPE && (behaviorFilter.isEmpty()
                || Component.translatable("encephalon.screen.damage_title").getString().toLowerCase().contains(behaviorFilter))) {
            items.add(new DamageItem());
        }
        for (Behavior b : Behaviors.all()) {
            if (b.scopes().contains(scope.type())
                    && (behaviorFilter.isEmpty() || b.name().getString().toLowerCase().contains(behaviorFilter))) {
                items.add(new BehaviorItem(b));
            }
        }
        boolean changed = false;
        if (behavior != null && !behavior.scopes().contains(scope.type())) {
            behavior = null;
            changed = true;
        }
        if (dropsOpen && scope.type() != ScopeType.MOB_TYPE) {
            dropsOpen = false;
            addMode = false;
            changed = true;
        }
        if (categoryOpen && scope.type() != ScopeType.MOB_TYPE) {
            categoryOpen = false;
            changed = true;
        }
        if (damageOpen && scope.type() != ScopeType.MOB_TYPE) {
            damageOpen = false;
            changed = true;
        }
        behaviorList.setItems(items);
        if (dropsOpen) {
            behaviorList.select(DROPS_KEY);
        } else if (categoryOpen) {
            behaviorList.select(CATEGORY_KEY);
        } else if (damageOpen) {
            behaviorList.select(DAMAGE_KEY);
        } else if (behavior != null) {
            behaviorList.select(behavior);
        }
        if (changed && placed != null && behaviorList != null && stateButton != null) {
            rebuildDetail();
        }
    }

    private final class BehaviorItem implements PickList.Item {
        private final Behavior b;

        BehaviorItem(Behavior b) {
            this.b = b;
        }

        @Override
        public Object key() {
            return b;
        }

        @Override
        public void draw(GuiGraphics g, Font f, int x, int y, int w, int h) {
            g.renderFakeItem(new ItemStack(b.icon()), x + 1, y + (h - 16) / 2);
            Tri own = data.toggle(scope, b);
            Component state;
            int color;
            if (own != Tri.INHERIT) {
                state = Component.translatable("encephalon.state." + own.name().toLowerCase());
                color = own == Tri.ON ? 0x55FF55 : 0xFF5555;
            } else {
                boolean on = ConfigResolver.toggleFor(data, ConfigResolver.chainFor(scope, data), b).on();
                state = Component.translatable("encephalon.state.inherit_" + (on ? "on" : "off"));
                color = 0xA0A0A0;
            }
            int stateW = f.width(state);
            int ty = y + (h - 8) / 2 + 1;
            g.drawString(f, fit(b.name().getString(), w - 20 - stateW - 6), x + 20, ty, 0xFFFFFF, true);
            g.drawString(f, state, x + w - stateW - 2, ty, color, true);
        }

        @Override
        public void picked() {
            behavior = b;
            dropsOpen = false;
            categoryOpen = false;
            damageOpen = false;
            addMode = false;
            rebuildDetail();
        }
    }

    /** The "Drops" entry of a mob type's behavior list: opens the drops editor in the right panel. */
    private final class DropsItem implements PickList.Item {
        @Override
        public Object key() {
            return DROPS_KEY;
        }

        @Override
        public void draw(GuiGraphics g, Font f, int x, int y, int w, int h) {
            g.renderFakeItem(new ItemStack(Items.CHEST), x + 1, y + (h - 16) / 2);
            boolean custom = scope.type() == ScopeType.MOB_TYPE && data.drops(scope.id()) != null;
            Component state = Component.translatable(custom ? "encephalon.state.custom" : "encephalon.screen.default");
            int color = custom ? 0xFFAA00 : 0xA0A0A0;
            int stateW = f.width(state);
            int ty = y + (h - 8) / 2 + 1;
            g.drawString(f, fit(Component.translatable("encephalon.screen.drops_title").getString(), w - 20 - stateW - 6),
                    x + 20, ty, 0xFFFFFF, true);
            g.drawString(f, state, x + w - stateW - 2, ty, color, true);
        }

        @Override
        public void picked() {
            behavior = null;
            dropsOpen = true;
            categoryOpen = false;
            damageOpen = false;
            addMode = false;
            gridScroll = 0;
            pickFilter = "";
            rebuildDetail();
        }
    }

    /** The "Category" entry of a mob type's behavior list: opens the category picker in the right panel. */
    private final class CategoryItem implements PickList.Item {
        @Override
        public Object key() {
            return CATEGORY_KEY;
        }

        @Override
        public void draw(GuiGraphics g, Font f, int x, int y, int w, int h) {
            g.renderFakeItem(new ItemStack(Items.NAME_TAG), x + 1, y + (h - 16) / 2);
            boolean custom = scope.type() == ScopeType.MOB_TYPE && data.category(scope.id()) != null;
            String eff = effectiveCategory();
            Component state = Component.translatable("encephalon.category." + eff);
            int color = !custom ? 0xA0A0A0 : eff.equals("hostile") ? 0xFF5555 : eff.equals("passive") ? 0x55FF55 : 0xFFFF55;
            int stateW = f.width(state);
            int ty = y + (h - 8) / 2 + 1;
            g.drawString(f, fit(Component.translatable("encephalon.screen.category_title").getString(), w - 20 - stateW - 6),
                    x + 20, ty, 0xFFFFFF, true);
            g.drawString(f, state, x + w - stateW - 2, ty, color, true);
        }

        @Override
        public void picked() {
            behavior = null;
            dropsOpen = false;
            categoryOpen = true;
            damageOpen = false;
            addMode = false;
            rebuildDetail();
        }
    }

    /** The "Damage" entry of a mob type's behavior list: opens the damage editor in the right panel. */
    private final class DamageItem implements PickList.Item {
        @Override
        public Object key() {
            return DAMAGE_KEY;
        }

        @Override
        public void draw(GuiGraphics g, Font f, int x, int y, int w, int h) {
            g.renderFakeItem(new ItemStack(Items.IRON_SWORD), x + 1, y + (h - 16) / 2);
            boolean custom = scope.type() == ScopeType.MOB_TYPE && data.hasDamages(scope.id());
            Component state = Component.translatable(custom ? "encephalon.state.custom" : "encephalon.screen.default");
            int color = custom ? 0xFFAA00 : 0xA0A0A0;
            int stateW = f.width(state);
            int ty = y + (h - 8) / 2 + 1;
            g.drawString(f, fit(Component.translatable("encephalon.screen.damage_title").getString(), w - 20 - stateW - 6),
                    x + 20, ty, 0xFFFFFF, true);
            g.drawString(f, state, x + w - stateW - 2, ty, color, true);
        }

        @Override
        public void picked() {
            behavior = null;
            dropsOpen = false;
            categoryOpen = false;
            damageOpen = true;
            addMode = false;
            rebuildDetail();
        }
    }

    // ------------------------------------------------------------------ detail panel (scrollable)

    private void rebuildDetail() {
        setFocused(null); // commits any box being edited, against the behavior it was created for
        for (Placed p : placed) {
            removeWidget(p.widget());
        }
        placed.clear();
        rows.clear();
        dropBoxes.clear();
        damageRows.clear();
        dropsReset = null;
        damageReset = null;
        pickSearch = null;
        stateButton = null;
        descLines = List.of();
        scroll = 0;
        contentHeight = 0;
        warnLines = internalScope() ? this.font.split(Component.translatable("encephalon.screen.internal_warning"), rw) : List.of();
        warnH = warnLines.isEmpty() ? 0 : warnLines.size() * 10 + 6;
        if (dropsOpen && scope.type() == ScopeType.MOB_TYPE && data != null) {
            buildDropsPanel();
            return;
        }
        if (categoryOpen && scope.type() == ScopeType.MOB_TYPE && data != null) {
            buildCategoryPanel();
            return;
        }
        if (damageOpen && scope.type() == ScopeType.MOB_TYPE && data != null) {
            buildDamagePanel();
            return;
        }
        if (behavior == null) {
            return;
        }

        descLines = this.font.split(behavior.description(), rw);
        int y = TOP + 2 + warnH + descLines.size() * 10 + 8;
        stateButton = track(Button.builder(Component.empty(), b -> cycleState()).bounds(rx, y, rw, 20).build());
        stateButton.active = editable;
        refreshState();
        explainY = y + 24;
        y += 44;

        List<Scope> chain = ConfigResolver.chainFor(scope, data);
        List<Scope> above = chain.subList(0, chain.size() - 1);
        for (Setting s : behavior.settings()) {
            ConfigResolver.Val inherited = ConfigResolver.valueFor(data, above, behavior, s);
            Component hint = inherited.from() == null
                    ? Component.translatable("encephalon.screen.default")
                    : Component.literal(fmt(inherited.value()));
            NumBox box = track(new NumBox(this.font, rx, y + 20, rw - 24, s, hint, data.value(scope, behavior, s.key())));
            box.setEditable(editable);
            Button reset = track(Button.builder(Component.literal("R"), b -> box.reset())
                    .bounds(rx + rw - 20, y + 20, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("encephalon.screen.reset_tip"))).build());
            reset.active = editable;
            rows.add(new SettingRow(s, y));
            y += 48;
        }
        contentHeight = y - TOP;
        layoutDetail();
    }

    /** True when the selected scope is a mob that is only listed because "show hidden / internal mobs" is on. */
    private boolean internalScope() {
        if (scope.type() != ScopeType.MOB_TYPE || listing == null) {
            return false;
        }
        return listing.isInternal(BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(scope.id())));
    }

    private void drawWarning(GuiGraphics g, int scrollOffset) {
        for (int i = 0; i < warnLines.size(); i++) {
            g.drawString(this.font, warnLines.get(i), rx, TOP + 2 + i * 10 - scrollOffset, 0xFFAA00, true);
        }
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (viewBottom() - TOP));
    }

    /** Moves every detail widget by the scroll offset and hides the ones that would spill out of the panel. */
    private void layoutDetail() {
        scroll = Mth.clamp(scroll, 0, maxScroll());
        for (Placed p : placed) {
            int y = p.baseY() - scroll;
            p.widget().setY(y);
            p.widget().visible = y >= TOP && y + p.widget().getHeight() <= viewBottom();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (dropsOpen && addMode && mouseX >= rx && mouseY >= gridY && mouseY < viewBottom()) {
            int cols = pickCols();
            int totalRows = (pickResults.size() + cols - 1) / cols;
            int maxRow = Math.max(0, totalRows - pickRows());
            gridScroll = Mth.clamp(gridScroll - (int) Math.signum(scrollY), 0, maxRow);
            return true;
        }
        if (mouseX >= rx && mouseY >= TOP && mouseY < viewBottom() && maxScroll() > 0) {
            scroll = Mth.clamp(scroll - (int) (scrollY * 12), 0, maxScroll());
            layoutDetail();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (data != null && dropsOpen && addMode && editable && button == 0 && mouseX >= rx && mouseY >= gridY
                && mouseY < viewBottom()) {
            int col = (int) Math.floor((mouseX - rx) / PICK_CELL);
            int row = (int) Math.floor((mouseY - gridY) / PICK_CELL);
            if (col >= 0 && col < pickCols() && row >= 0 && row < pickRows()) {
                int idx = (gridScroll + row) * pickCols() + col;
                if (idx < pickResults.size()) {
                    addDrop(pickResults.get(idx));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void refreshState() {
        Tri own = data.toggle(scope, behavior);
        stateButton.setMessage(Component.translatable("encephalon.screen.state",
                Component.translatable("encephalon.state." + own.name().toLowerCase())));
    }

    private void cycleState() {
        Behavior b = behavior;
        Scope sc = scope;
        Tri next = Tri.values()[(data.toggle(sc, b).ordinal() + 1) % Tri.values().length];
        edit(() -> data.setToggle(sc, b, next));
        refreshState();
    }

    private void resetScope() {
        setFocused(null);
        Scope sc = scope;
        String mobId = sc.type() == ScopeType.MOB_TYPE ? sc.id() : null;
        edit(() -> {
            data.clearScope(sc);
            if (mobId != null) {
                CategoryBehavior.refresh(server, mobId);
            }
        });
        rebuildDetail();
    }

    /** Number field: greyed hint shows the inherited value; Enter or clicking away applies it, clamped to min/max. */
    private final class NumBox extends EditBox {
        private final Scope sc;
        private final Behavior bh;
        private final Setting st;

        NumBox(Font font, int x, int y, int w, Setting st, Component hint, Double current) {
            super(font, x, y, w, 20, Component.literal(st.key()));
            this.sc = scope;
            this.bh = behavior;
            this.st = st;
            if (current != null) {
                setValue(fmt(current));
            }
            setFilter(s -> s.matches("\\d*\\.?\\d*"));
            setHint(hint);
        }

        void commit() {
            if (!editable) {
                return;
            }
            Double old = data.value(sc, bh, st.key());
            String txt = getValue().trim();
            Double nv = null;
            if (!txt.isEmpty()) {
                try {
                    nv = st.clamp(Double.parseDouble(txt));
                } catch (NumberFormatException e) {
                    setValue(old == null ? "" : fmt(old));
                    return;
                }
            }
            if (!Objects.equals(old, nv)) {
                Double v = nv;
                edit(() -> data.setValue(sc, bh, st.key(), v));
            }
            setValue(nv == null ? "" : fmt(nv));
        }

        void reset() {
            setValue("");
            commit();
        }

        @Override
        public void setFocused(boolean focused) {
            boolean was = isFocused();
            super.setFocused(focused);
            if (was && !focused) {
                commit();
            }
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (isFocused() && (key == 257 || key == 335)) { // Enter / keypad Enter
                commit();
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
    }

    // ------------------------------------------------------------------ drops editor

    private static ItemStack stackOf(String id) {
        try {
            Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElse(null);
            return new ItemStack(item == null || item == Items.AIR ? Items.BARRIER : item);
        } catch (RuntimeException ex) {
            return new ItemStack(Items.BARRIER);
        }
    }

    private int pickCols() {
        return Math.max(1, rw / PICK_CELL);
    }

    private int pickRows() {
        return Math.max(1, (viewBottom() - gridY) / PICK_CELL);
    }

    /** The default drops of the selected mob type, or null while they are still being rolled on the server. */
    private List<DropsPanel.Drop> defaultDrops() {
        if (server == null || this.minecraft.level == null || this.minecraft.player == null) {
            return null;
        }
        return dropsPanel.defaults(server, this.minecraft.level.dimension(), this.minecraft.player.getUUID(),
                BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(scope.id())));
    }

    /** What the drops shown should currently be made from: the custom list, the default drops, or null (still loading). */
    private Object currentDropsSource() {
        List<MobConfigData.DropEntry> custom = data.drops(scope.id());
        return custom != null ? custom : defaultDrops();
    }

    /** The drops to show: the custom list, or the default loot table turned into a list. Null while that is loading. */
    private List<MobConfigData.DropEntry> loadDropEntries() {
        List<MobConfigData.DropEntry> custom = data.drops(scope.id());
        if (custom != null) {
            dropsSource = custom;
            return custom;
        }
        List<DropsPanel.Drop> def = defaultDrops();
        dropsSource = def;
        if (def == null) {
            return null;
        }
        List<MobConfigData.DropEntry> out = new ArrayList<>();
        for (DropsPanel.Drop d : def) {
            double pct = Math.max(0.1, Math.round(d.chance() * 1000.0) / 10.0);
            out.add(new MobConfigData.DropEntry(BuiltInRegistries.ITEM.getKey(d.stack().getItem()).toString(), pct, d.min(), d.max()));
        }
        return out;
    }

    /** Builds the drops editor in the right panel: description, add / reset buttons, then the drop rows or the item picker. */
    private void buildDropsPanel() {
        dropRowYs.clear();
        dropStacks.clear();
        dropEntries = loadDropEntries();
        if (dropEntries == null) {
            if (loadingSince == 0L) {
                loadingSince = System.nanoTime();
            }
        } else {
            loadingSince = 0L;
        }
        int y = TOP + 2 + warnH;
        dropsDesc = this.font.split(Component.translatable("encephalon.screen.drops_desc"), rw);
        y += dropsDesc.size() * 10 + 4;
        dropsStateY = y;
        y += 14;

        int half = (rw - 4) / 2;
        Button add = track(Button.builder(Component.translatable(addMode ? "encephalon.screen.drops_back" : "encephalon.screen.drops_add"),
                b -> toggleAddMode()).bounds(rx, y, half, 20).build());
        add.active = editable; // not tied to the loading state: the button must not flash grey while the drops are worked out
        dropsReset = track(Button.builder(Component.translatable("encephalon.screen.drops_reset"), b -> resetDrops())
                .bounds(rx + half + 4, y, rw - half - 4, 20).build());
        dropsReset.active = editable && data.drops(scope.id()) != null;
        y += 24;

        if (addMode) {
            pickSearch = track(new EditBox(this.font, rx, y, rw, 16, Component.empty()));
            pickSearch.setHint(Component.translatable("encephalon.screen.drops_search"));
            pickSearch.setValue(pickFilter);
            pickSearch.setResponder(s -> {
                pickFilter = s.toLowerCase().trim();
                gridScroll = 0;
                refreshPick();
            });
            refreshPick();
            gridY = y + 20;
            contentHeight = 0; // the picker scrolls its own grid
            layoutDetail();
            setFocused(pickSearch);
            return;
        }

        dropsHeaderY = y;
        y += 12;
        if (dropEntries == null) {
            contentHeight = y + 14 - TOP;
            layoutDetail();
            return;
        }

        int rem = Math.max(30, rw - 20 - 20 - 6);
        colChanceX = rx + 20;
        colChanceW = rem * 42 / 100;
        colMinX = colChanceX + colChanceW + 2;
        colMinW = rem * 29 / 100;
        colMaxX = colMinX + colMinW + 2;
        colMaxW = rem - colChanceW - colMinW;
        colRemoveX = colMaxX + colMaxW + 2;

        for (int i = 0; i < dropEntries.size(); i++) {
            final int idx = i;
            MobConfigData.DropEntry e = dropEntries.get(i);
            dropRowYs.add(y);
            dropStacks.add(stackOf(e.item()));
            DropBox chance = track(new DropBox(this.font, colChanceX, y, colChanceW, i, 0, e));
            DropBox min = track(new DropBox(this.font, colMinX, y, colMinW, i, 1, e));
            DropBox max = track(new DropBox(this.font, colMaxX, y, colMaxW, i, 2, e));
            dropBoxes.add(chance);
            dropBoxes.add(min);
            dropBoxes.add(max);
            chance.setEditable(editable);
            min.setEditable(editable);
            max.setEditable(editable);
            Button remove = track(Button.builder(Component.literal("X"), b -> removeDrop(idx))
                    .bounds(colRemoveX, y, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("encephalon.screen.drops_remove"))).build());
            remove.active = editable;
            y += DROP_ROW_H;
        }
        if (dropEntries.isEmpty()) {
            y += 14;
        }
        contentHeight = y + 4 - TOP;
        layoutDetail();
    }

    /** Rebuilds the drops panel after an add / remove / reset without jumping back to the top. */
    private void rebuildDropsKeepScroll() {
        int keep = scroll;
        rebuildDetail();
        scroll = keep;
        layoutDetail();
    }

    /** Stores the list as the custom drops of the selected mob type (the server data is shared with this screen). */
    private void saveDrops(List<MobConfigData.DropEntry> list) {
        String id = scope.id();
        List<MobConfigData.DropEntry> copy = List.copyOf(list);
        edit(() -> data.setDrops(id, copy));
        dropEntries = data.drops(id);
        dropsSource = dropEntries; // our own edit: no rebuild needed (it would drop the focus of the box being clicked)
        if (dropsReset != null) {
            dropsReset.active = editable;
        }
    }

    private void removeDrop(int index) {
        if (dropEntries == null || index < 0 || index >= dropEntries.size()) {
            return;
        }
        List<MobConfigData.DropEntry> list = new ArrayList<>(dropEntries);
        list.remove(index);
        saveDrops(list);
        rebuildDropsKeepScroll();
    }

    private void addDrop(Item item) {
        if (dropEntries == null) {
            return;
        }
        List<MobConfigData.DropEntry> list = new ArrayList<>(dropEntries);
        list.add(new MobConfigData.DropEntry(BuiltInRegistries.ITEM.getKey(item).toString(), 100.0, 1, 1));
        saveDrops(list);
        addMode = false;
        gridScroll = 0;
        pickFilter = "";
        rebuildDetail();
        scroll = maxScroll(); // show the new drop at the bottom of the list
        layoutDetail();
    }

    private void resetDrops() {
        setFocused(null);
        String id = scope.id();
        edit(() -> data.clearDrops(id));
        addMode = false;
        rebuildDropsKeepScroll();
    }

    private void toggleAddMode() {
        addMode = !addMode;
        gridScroll = 0;
        pickFilter = "";
        rebuildDetail();
    }

    private void refreshPick() {
        if (allItems == null) {
            List<PickEntry> all = new ArrayList<>();
            for (Item it : BuiltInRegistries.ITEM) {
                if (it == Items.AIR) {
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(it).toString();
                String name;
                try {
                    name = it.getDescription().getString().toLowerCase();
                } catch (RuntimeException ex) {
                    name = id;
                }
                all.add(new PickEntry(it, name, id));
            }
            all.sort(Comparator.comparing(PickEntry::name));
            allItems = all;
        }
        List<Item> out = new ArrayList<>();
        for (PickEntry p : allItems) {
            if (pickFilter.isEmpty() || p.name().contains(pickFilter) || p.id().contains(pickFilter)) {
                out.add(p.item());
            }
        }
        pickResults = out;
    }

    private static String dropText(MobConfigData.DropEntry e, int kind) {
        return kind == 0 ? fmt(e.chance()) : String.valueOf(kind == 1 ? e.min() : e.max());
    }

    /** After a min / max edit the other box of that row may have changed too. */
    private void syncDropBoxes() {
        if (dropEntries == null) {
            return;
        }
        for (DropBox b : dropBoxes) {
            if (b.index < dropEntries.size()) {
                b.setValue(dropText(dropEntries.get(b.index), b.kind));
            }
        }
    }

    /**
     * One field of a drop row: kind 0 is the chance in percent, 1 the smallest amount, 2 the largest. Enter or clicking away
     * applies it (clamped); the first edit of a mob that still uses its default loot table turns the default drops into a
     * custom list.
     */
    private final class DropBox extends EditBox {
        private final int index;
        private final int kind;

        DropBox(Font font, int x, int y, int w, int index, int kind, MobConfigData.DropEntry entry) {
            super(font, x, y, w, 20, Component.literal("drop"));
            this.index = index;
            this.kind = kind;
            setMaxLength(kind == 0 ? 6 : 3);
            setValue(dropText(entry, kind));
            setFilter(kind == 0 ? s -> s.matches("\\d*\\.?\\d*") : s -> s.matches("\\d*"));
        }

        void commit() {
            if (!editable || dropEntries == null || index >= dropEntries.size()) {
                return;
            }
            MobConfigData.DropEntry old = dropEntries.get(index);
            MobConfigData.DropEntry now = old;
            String txt = getValue().trim();
            if (!txt.isEmpty()) {
                try {
                    if (kind == 0) {
                        now = new MobConfigData.DropEntry(old.item(), Mth.clamp(Double.parseDouble(txt), 0.0, 100.0), old.min(), old.max());
                    } else {
                        int v = Mth.clamp(Integer.parseInt(txt), 0, DROP_MAX_AMOUNT);
                        int lo = old.min();
                        int hi = old.max();
                        if (kind == 1) {
                            lo = v;
                            hi = Math.max(hi, v);
                        } else {
                            hi = v;
                            lo = Math.min(lo, v);
                        }
                        now = new MobConfigData.DropEntry(old.item(), old.chance(), lo, hi);
                    }
                } catch (NumberFormatException ex) {
                    now = old;
                }
            }
            if (!now.equals(old)) {
                List<MobConfigData.DropEntry> list = new ArrayList<>(dropEntries);
                list.set(index, now);
                saveDrops(list);
            }
            syncDropBoxes();
        }

        @Override
        public void setFocused(boolean focused) {
            boolean was = isFocused();
            super.setFocused(focused);
            if (was && !focused) {
                commit();
            }
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (isFocused() && (key == 257 || key == 335)) { // Enter / keypad Enter
                commit();
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
    }

    /** Draws the text and icons of the drops editor (the buttons and boxes are widgets). Inside the panel's scissor. */
    private void drawDropsEditor(GuiGraphics g, int mouseX, int mouseY) {
        hovered = ItemStack.EMPTY;
        for (int i = 0; i < dropsDesc.size(); i++) {
            g.drawString(this.font, dropsDesc.get(i), rx, TOP + 2 + warnH + i * 10 - scroll, 0xC8C8C8, true);
        }
        boolean custom = data.drops(scope.id()) != null;
        Component state = Component.translatable(custom ? "encephalon.screen.drops_state_custom" : "encephalon.screen.drops_state_default");
        g.drawString(this.font, fit(state.getString(), rw), rx, dropsStateY - scroll, custom ? 0xFFAA00 : 0xA0A0A0, true);

        if (addMode) {
            drawPicker(g, mouseX, mouseY);
            return;
        }
        if (dropEntries == null) {
            // Usually answered within a few frames: only say "working" if it really takes a while, so switching mobs does not flash text.
            if (loadingSince != 0L && System.nanoTime() - loadingSince > 400_000_000L) {
                g.drawString(this.font, Component.translatable("encephalon.screen.drops_loading"), rx, dropsHeaderY + 2 - scroll, 0x808080, true);
            }
            return;
        }
        int hy = dropsHeaderY + 2 - scroll;
        g.drawCenteredString(this.font, Component.translatable("encephalon.screen.drops_col_chance"), colChanceX + colChanceW / 2, hy, 0xA0A0A0);
        g.drawCenteredString(this.font, Component.translatable("encephalon.screen.drops_col_min"), colMinX + colMinW / 2, hy, 0xA0A0A0);
        g.drawCenteredString(this.font, Component.translatable("encephalon.screen.drops_col_max"), colMaxX + colMaxW / 2, hy, 0xA0A0A0);
        if (dropEntries.isEmpty()) {
            g.drawString(this.font, Component.translatable("encephalon.screen.drops_none"), rx, dropsHeaderY + 16 - scroll, 0x808080, true);
            return;
        }
        for (int i = 0; i < dropRowYs.size(); i++) {
            int y = dropRowYs.get(i) - scroll;
            if (y < TOP || y + 20 > viewBottom()) {
                continue;
            }
            ItemStack stack = dropStacks.get(i);
            g.renderFakeItem(stack, rx + 2, y + 2);
            if (mouseX >= rx + 2 && mouseX < rx + 18 && mouseY >= y + 2 && mouseY < y + 18) {
                hovered = stack;
            }
        }
    }

    /** The item grid of the add-a-drop mode: a click adds the item (see mouseClicked), hovering shows its name. */
    private void drawPicker(GuiGraphics g, int mouseX, int mouseY) {
        if (pickResults.isEmpty()) {
            g.drawString(this.font, Component.translatable("encephalon.screen.drops_no_results"), rx, gridY + 2, 0x808080, true);
            return;
        }
        int cols = pickCols();
        int cells = cols * pickRows();
        int first = gridScroll * cols;
        for (int i = 0; i < cells && first + i < pickResults.size(); i++) {
            int cx = rx + (i % cols) * PICK_CELL;
            int cy = gridY + (i / cols) * PICK_CELL;
            boolean over = mouseX >= cx && mouseX < cx + PICK_CELL && mouseY >= cy && mouseY < cy + PICK_CELL;
            if (over) {
                g.fill(cx, cy, cx + PICK_CELL, cy + PICK_CELL, 0x40FFFFFF);
            }
            ItemStack stack = new ItemStack(pickResults.get(first + i));
            g.renderFakeItem(stack, cx + 1, cy + 1);
            if (over) {
                hovered = stack;
            }
        }
    }

    /** Scrollbar for the right panel: spans the whole panel box, same look as the lists. */
    private void drawPanelScrollbar(GuiGraphics g) {
        int max = maxScroll();
        if (max > 0) {
            int barTop = TOP - 2;
            int barBottom = viewBottom() + 2;
            int view = barBottom - barTop;
            int thumbH = Math.max(32, Math.min(view - 8, view * (viewBottom() - TOP) / contentHeight));
            int thumbY = barTop + (view - thumbH) * scroll / max;
            PickList.drawScrollbar(g, width - 7, barTop, barBottom, thumbY, thumbH);
        }
    }

    // ------------------------------------------------------------------ category editor

    private static final String[] CATEGORY_CHOICES = {"hostile", "neutral", "passive"};
    private List<FormattedCharSequence> categoryDesc = List.of();
    private int categoryStateY;

    private EntityType<?> selectedType() {
        return BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(scope.id()));
    }

    /** The category a mob type is listed under in the tree: always its default one (null if it is not a mob). */
    private String categoryOf(EntityType<?> t) {
        return MobGroups.categoryOf(t);
    }

    /** How a mob type acts in the game: the category set in the editor, else its default one (null if it has none). */
    private String actingCategory(EntityType<?> t) {
        String own = data == null ? null : data.category(BuiltInRegistries.ENTITY_TYPE.getKey(t).toString());
        return own != null ? own : MobGroups.categoryOf(t);
    }

    private String defaultCategory() {
        String c = MobGroups.categoryOf(selectedType());
        return c == null ? "neutral" : c;
    }

    private String effectiveCategory() {
        if (scope.type() != ScopeType.MOB_TYPE) {
            return "neutral";
        }
        String c = actingCategory(selectedType());
        return c == null ? "neutral" : c;
    }

    /** A button that can be shown as the chosen one: it keeps the highlighted look vanilla gives a hovered or focused button. */
    private static final class ChoiceButton extends Button {
        private final boolean selected;

        ChoiceButton(int x, int y, int w, int h, Component message, Button.OnPress onPress, boolean selected) {
            super(x, y, w, h, message, onPress, DEFAULT_NARRATION);
            this.selected = selected;
        }

        @Override
        public boolean isHoveredOrFocused() {
            return selected || super.isHoveredOrFocused();
        }

        @Override
        public void onPress() {
            if (!selected) {
                super.onPress();
            }
        }
    }

    private void buildCategoryPanel() {
        categoryDesc = this.font.split(Component.translatable("encephalon.screen.category_desc"), rw);
        int y = TOP + 2 + warnH + categoryDesc.size() * 10 + 4;
        categoryStateY = y;
        y += 14;
        String own = data.category(scope.id());
        categoryButton(null, y, own == null);
        y += 24;
        for (String c : CATEGORY_CHOICES) {
            categoryButton(c, y, c.equals(own));
            y += 24;
        }
        contentHeight = y + 4 - TOP;
        layoutDetail();
    }

    private void categoryButton(String cat, int y, boolean current) {
        Component name = cat == null
                ? Component.translatable("encephalon.screen.category_default", Component.translatable("encephalon.category." + defaultCategory()))
                : Component.translatable("encephalon.category." + cat);
        ChoiceButton b = track(new ChoiceButton(rx, y, rw, 20, name, btn -> pickCategory(cat), current));
        b.active = editable;
    }

    /** Sets how the mob acts. The tree is not touched: the mob stays listed under its default category. */
    private void pickCategory(String cat) {
        if (!editable) {
            return;
        }
        String id = scope.id();
        edit(() -> {
            if (cat == null) {
                data.clearCategory(id);
            } else {
                data.setCategory(id, cat);
            }
            CategoryBehavior.refresh(server, id); // mobs of this type that are already in the world pick the change up at once
        });
        rebuildDetail();
    }

    private void drawCategoryEditor(GuiGraphics g) {
        for (int i = 0; i < categoryDesc.size(); i++) {
            g.drawString(this.font, categoryDesc.get(i), rx, TOP + 2 + warnH + i * 10 - scroll, 0xC8C8C8, true);
        }
        String own = data.category(scope.id());
        Component state = Component.translatable(own != null ? "encephalon.screen.category_state_custom" : "encephalon.screen.category_state_default",
                Component.translatable("encephalon.category." + effectiveCategory()));
        g.drawString(this.font, fit(state.getString(), rw), rx, categoryStateY - scroll, own != null ? 0xFFAA00 : 0xA0A0A0, true);
    }

    // ------------------------------------------------------------------ damage editor

        private void buildDamagePanel() {
        EntityType<?> type = selectedType();
        String mobId = scope.id();
        String own = data.category(mobId);
        boolean forced = own != null && !"passive".equals(own) && !own.equals(MobGroups.categoryOf(type));
        java.util.concurrent.atomic.AtomicReference<List<DamageDefaults.Attack>> found =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());
        edit(() -> found.set(DamageDefaults.attacksOf(server, type, forced)));
        damageAttacks = found.get();
        damageFallback = DamageDefaults.isFallback(type)
                && damageAttacks.stream().anyMatch(a -> a.kind() == DamageDefaults.Kind.MELEE);
        damageDesc = this.font.split(Component.translatable("encephalon.screen.damage_desc"), rw);
        int y = TOP + 2 + warnH + damageDesc.size() * 10 + 4;
        damageStateY = y;
        y += damageFallback ? 24 : 14;
        damageReset = track(Button.builder(Component.translatable("encephalon.screen.damage_reset"), b -> resetDamage())
                .bounds(rx, y, rw, 20).build());
        damageReset.active = editable && data.hasDamages(scope.id());
        y += 28;
        if (damageAttacks.isEmpty()) {
            y += 14;
        }
        for (DamageDefaults.Attack a : damageAttacks) {
            if (!a.editable()) {
                List<FormattedCharSequence> info = this.font.split(DamageDefaults.describe(a), rw);
                damageRows.add(new DamageRow(a, y, info));
                y += 14 + info.size() * 10 + 6;
                continue;
            }
            damageRows.add(new DamageRow(a, y, List.of()));
            DamageBox box = track(new DamageBox(this.font, rx, y + 20, rw - 24, mobId, a, data.damage(mobId, a.id())));
            box.setEditable(editable);
            Button reset = track(Button.builder(Component.literal("R"), b -> box.reset())
                    .bounds(rx + rw - 20, y + 20, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("encephalon.screen.reset_tip"))).build());
            reset.active = editable;
            y += 48;
        }
        contentHeight = y + 4 - TOP;
        layoutDetail();
    }

    private void resetDamage() {
        setFocused(null);
        String id = scope.id();
        edit(() -> data.clearDamages(id));
        rebuildDetail();
    }

    /** Damage field of one attack: the greyed hint is the default; Enter or clicking away applies it (0 - 1000), empty resets it. */
    private final class DamageBox extends EditBox {
        private final String mobId;
        private final DamageDefaults.Attack attack;

        DamageBox(Font font, int x, int y, int w, String mobId, DamageDefaults.Attack attack, Double current) {
            super(font, x, y, w, 20, Component.literal(attack.id()));
            this.mobId = mobId;
            this.attack = attack;
            setMaxLength(8);
            if (current != null) {
                setValue(fmt(current));
            }
            setFilter(s -> s.matches("\\d*\\.?\\d*"));
                        setHint(attack.def() < 0 ? Component.translatable("encephalon.screen.damage_varies") : Component.literal(fmt(attack.def())));
        }

        void commit() {
            if (!editable) {
                return;
            }
            Double old = data.damage(mobId, attack.id());
            String txt = getValue().trim();
            Double nv = null;
            if (!txt.isEmpty()) {
                try {
                    nv = Mth.clamp(Double.parseDouble(txt), 0.0, DAMAGE_MAX);
                } catch (NumberFormatException e) {
                    setValue(old == null ? "" : fmt(old));
                    return;
                }
            }
            if (!Objects.equals(old, nv)) {
                Double v = nv;
                String id = attack.id();
                edit(() -> {
                    if (v == null) {
                        data.clearDamage(mobId, id);
                    } else {
                        data.setDamage(mobId, id, v);
                    }
                });
                if (damageReset != null) {
                    damageReset.active = data.hasDamages(mobId);
                }
            }
            setValue(nv == null ? "" : fmt(nv));
        }

        void reset() {
            setValue("");
            commit();
        }

        @Override
        public void setFocused(boolean focused) {
            boolean was = isFocused();
            super.setFocused(focused);
            if (was && !focused) {
                commit();
            }
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (isFocused() && (key == 257 || key == 335)) { // Enter / keypad Enter
                commit();
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
    }

        private static Component attackInfo(DamageDefaults.Attack a) {
        if (a.def() < 0) {
            return Component.translatable("encephalon.screen.damage_info_varies");
        }
        if (a.kind() == DamageDefaults.Kind.EXPLOSION) {
            return Component.translatable("encephalon.screen.damage_info_explosion", fmt(a.def()), fmt(a.def() / 2.0));
        }
        return Component.translatable("encephalon.screen.damage_info", fmt(a.def()), fmt(a.def() / 2.0));
    }

    private void drawDamageEditor(GuiGraphics g) {
        for (int i = 0; i < damageDesc.size(); i++) {
            g.drawString(this.font, damageDesc.get(i), rx, TOP + 2 + warnH + i * 10 - scroll, 0xC8C8C8, true);
        }
        boolean custom = data.hasDamages(scope.id());
        Component state = Component.translatable(custom ? "encephalon.screen.damage_state_custom" : "encephalon.screen.damage_state_default");
        g.drawString(this.font, fit(state.getString(), rw), rx, damageStateY - scroll, custom ? 0xFFAA00 : 0xA0A0A0, true);
        if (damageFallback) {
            Component note = Component.translatable("encephalon.screen.damage_fallback",
                    Component.translatable("encephalon.size." + DamageDefaults.sizeClass(selectedType())));
            g.drawString(this.font, fit(note.getString(), rw), rx, damageStateY + 10 - scroll, 0x808080, true);
        }
        if (damageAttacks.isEmpty()) {
            int ny = damageStateY + (damageFallback ? 24 : 14) + 28 - scroll;
            g.drawString(this.font, Component.translatable("encephalon.screen.attacks_none"), rx, ny, 0x808080, true);
        }
        for (DamageRow row : damageRows) {
            DamageDefaults.Attack a = row.attack();
            int y = row.y() - scroll;
            Component name = DamageDefaults.nameOf(a);
            if (!a.editable()) {
                g.drawString(this.font, name, rx, y, 0xFFC878, true);
                for (int i = 0; i < row.info().size(); i++) {
                    g.drawString(this.font, row.info().get(i), rx, y + 12 + i * 10, 0x909090, true);
                }
                continue;
            }
            boolean set = data.damage(scope.id(), a.id()) != null;
            g.drawString(this.font, name, rx, y, set ? 0xFFFFFF : 0xA0A0A0, true);
            g.drawString(this.font, fit(attackInfo(a).getString(), rw), rx, y + 10, 0x808080, true);
        }
    }

    // ------------------------------------------------------------------ 3D preview helpers

    private static void faceViewer(LivingEntity e) {
        e.yBodyRot = 180F;
        e.yBodyRotO = 180F;
        e.setYRot(180F);
        e.yRotO = 180F;
        e.yHeadRot = 180F;
        e.yHeadRotO = 180F;
        e.setXRot(0F);
        e.xRotO = 0F;
    }

    private static boolean isFlyer(LivingEntity e) {
        return e instanceof FlyingAnimal || e instanceof FlyingMob || e instanceof Bat || e instanceof EnderDragon;
    }

    /**
     * Fish-like mobs, vanilla or modded: renderers lay them on their side whenever isInWater() is false, so they need the
     * wet flag. Judged by mob category rather than class so modded fish that do not extend AbstractFish are covered.
     * Dolphins and squids are left out: they are handled separately and animate fine as they are.
     */
    private static boolean isFish(LivingEntity e) {
        if (e instanceof Dolphin || e instanceof Squid) {
            return false;
        }
        if (e instanceof AbstractFish) {
            return true;
        }
        MobCategory c = e.getType().getCategory();
        return c == MobCategory.WATER_CREATURE || c == MobCategory.WATER_AMBIENT || c == MobCategory.UNDERGROUND_WATER_CREATURE
                || (!isVanilla(e.getType()) && placedInWater(e.getType()));
    }

    /** placedInWater answers, per type (asked several times per preview tick, and the answer never changes). */
    private static final Map<EntityType<?>, Boolean> IN_WATER = new ConcurrentHashMap<>();

    /**
     * Modded swimmers are often filed under MONSTER (the Skelewag), so category alone misses them and they get drawn as if
     * stranded on land (lying on their side). Their spawn placement says where they really live.
     */
    private static boolean placedInWater(EntityType<?> t) {
        return IN_WATER.computeIfAbsent(t, k -> {
            try {
                return net.minecraft.world.entity.SpawnPlacements.getPlacementType(k) == net.minecraft.world.entity.SpawnPlacementTypes.IN_WATER;
            } catch (RuntimeException ex) {
                return false;
            }
        });
    }

    /** Modded swimmers are made in the flooded dummy world (see PreviewLevel); everything else in the dry one. */
    private static final Map<EntityType<?>, Boolean> AQUATIC = new ConcurrentHashMap<>();

    private boolean aquatic(EntityType<?> t) {
        if (isVanilla(t)) {
            return false;
        }
        Boolean known = AQUATIC.get(t);
        if (known != null) {
            return known;
        }
        MobCategory c = t.getCategory();
        boolean water = c == MobCategory.WATER_CREATURE || c == MobCategory.WATER_AMBIENT
                || c == MobCategory.UNDERGROUND_WATER_CREATURE || placedInWater(t);
        if (!water) {
            Class<?> k = classOf(t);
            water = k != null && (AbstractFish.class.isAssignableFrom(k) || WaterAnimal.class.isAssignableFrom(k));
        }
        AQUATIC.put(t, water);
        return water;
    }

    /** The dummy world preview mobs live in; the real client level only if a dummy could not be built. */
    private Level levelFor(boolean flooded) {
        PreviewLevel dummy = PreviewLevel.get(flooded);
        return dummy != null ? dummy : this.minecraft.level;
    }

    /** Creates an entity of this type in a dummy world, inside the sandbox (its constructor is the mod's code). */
    private static Entity createIn(EntityType<?> t, Level level) {
        return PreviewSandbox.<Entity>asLevel(level, () -> t.create(level));
    }

    private static void forceWet(LivingEntity e) {
        if (WET != null && isFish(e)) {
            try {
                WET.setBoolean(e, true);
                if (WET_EYES != null) {
                    WET_EYES.setBoolean(e, true);
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // leave it as it is
            }
        }
    }

    /**
     * The velocity the preview mob is given each step. It is also applied when the mob is created: models such as the
     * dolphin's switch between a still and a swimming pose depending on whether the mob is moving, so a mob that starts
     * with zero velocity jolts from one pose to the other on its first step.
     */
    private static Vec3 previewMotion(LivingEntity e) {
        // Modded land animals get a slow walk too: many mods start their walk animation from the mob's own velocity, not from
        // the walk state, so a mob with zero velocity would only ever play its idle. Modded swimmers get a slow swim, forward.
        boolean moddedWalker = !isVanilla(e.getType()) && !isFlyer(e) && !isFish(e);
        boolean moddedSwimmer = !isVanilla(e.getType()) && isFish(e);
        return new Vec3(0.0, 0.0, e instanceof EnderDragon ? -0.5 : e instanceof Dolphin ? -0.05 : moddedSwimmer ? -0.05 : moddedWalker ? 0.05 : 0.0);
    }

    private static boolean isVanilla(EntityType<?> t) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(t).getNamespace().equals("minecraft");
    }

    /** The entity class of a type (made once, never added to a level), or null if one can't be made. */
    private static final Map<EntityType<?>, Class<?>> CLASS_OF = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> HAS_BOSS_BAR = new ConcurrentHashMap<>();

    private Class<?> classOf(EntityType<?> t) {
        Class<?> c = CLASS_OF.get(t);
        if (c == null && this.minecraft.level != null) {
            c = Void.class; // "unknown", so a type that can't be made is not tried again every frame
            try {
                Entity e = createIn(t, levelFor(false));
                if (e != null) {
                    c = e.getClass();
                    e.discard();
                }
            } catch (Exception | LinkageError ex) {
                // unknown
            }
            CLASS_OF.put(t, c);
        }
        return c == null || c == Void.class ? null : c;
    }

    /** Whether the class (or one it extends) holds a boss bar: a boss, whatever the mod calls it or tags it as. */
    private static boolean hasBossBar(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                for (Field f : k.getDeclaredFields()) {
                    if (BossEvent.class.isAssignableFrom(f.getType())) {
                        return true;
                    }
                }
            } catch (LinkageError | RuntimeException ex) {
                // a field type from a mod that is not installed
            }
        }
        return false;
    }

    private boolean isMobClass(EntityType<?> t) {
        Class<?> c = classOf(t);
        return c != null && Mob.class.isAssignableFrom(c);
    }

    /**
     * Modded mobs the preview must draw but never tick, and give no server twin: the ones in the built-in / config list, modded
     * bosses (tagged c:bosses), and anything whose class holds a boss bar. Their own code changes the sky, starts music and
     * so on when they merely exist. Asked on every preview tick, so the answer is cached per type for this editor session.
     */
    private boolean quiet(EntityType<?> t) {
        Boolean known = quietCache.get(t);
        if (known == null) {
            known = quietUncached(t);
            quietCache.put(t, known);
        }
        return known;
    }

    private boolean quietUncached(EntityType<?> t) {
        if (isVanilla(t)) {
            return false;
        }
        if (EncephalonConfig.isQuiet(t)) {
            return true;
        }
        Class<?> c = classOf(t);
        return c != null && HAS_BOSS_BAR.computeIfAbsent(c, MobEditorScreen::hasBossBar);
    }

    /**
     * Mobs that get no server twin (so none of their body parts exist). Only the ones named in the built-in / config lists.
     * Bosses that are merely detected as bosses (tag or boss bar) are not in that group: a multi-part boss such as the Void
     * Worm needs its twin to have a body. Its boss bar is hidden and disarmed by PreviewBossBars, and its sounds and music are
     * dropped; set bossTwins=false in the config to give detected bosses no twin again.
     */
    private boolean noTwin(EntityType<?> t) {
        if (isVanilla(t)) {
            return false;
        }
        if (EncephalonConfig.isQuietListed(t) || EncephalonConfig.isNoTwinMod(t)) {
            return true;
        }
        return !EncephalonConfig.bossTwins() && quiet(t);
    }

    // Size to screen curve: the fraction of the panel height a mob of `size` blocks gets. It is smooth (no hard minimum), so
    // relative sizes hold: a chicken is clearly smaller than a zombie, which is clearly smaller than a giant.
    // Raise SIZE_K to make everything bigger. Lower SIZE_EXP to make small mobs bigger relative to big ones.
    private static final float SIZE_K = 0.38F;
    private static final float SIZE_EXP = 0.65F;
    private static final float MIN_FRAC = 0.14F;

    /** The scale (pixels per block) a mob of this size (blocks) is drawn at, before the fit rules shrink it further. */
    private static float baseScale(float panelH, float size) {
        size = Math.max(size, 0.05F);
        float frac = Mth.clamp(SIZE_K * (float) Math.pow(size, SIZE_EXP), MIN_FRAC, 1F);
        return panelH * frac / size;
    }

    /** Logs a preview step that took long, with the mob id, so lag spikes can be traced to a mob and a phase. */
    private static void slow(String what, EntityType<?> t, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        if (ms >= 25) {
            EncephalonMod.LOGGER.warn("Mob Editor preview: {} took {} ms for {}", what, ms, BuiltInRegistries.ENTITY_TYPE.getKey(t));
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ preview zone / server twins

    /** Fixes where previews are parked and tells PreviewZone to silence / protect everything that appears there. */
    private void setupPreviewZone() {
        if (anchorReady || this.minecraft.player == null || this.minecraft.level == null) {
            return;
        }
        anchorX = this.minecraft.player.getX();
        anchorZ = this.minecraft.player.getZ();
        anchorY = this.minecraft.level.getMaxBuildHeight() + 64;
        anchorReady = true;
        PreviewZone.zone = new AABB(anchorX - 80, anchorY - 60, anchorZ - 80,
                anchorX + 80, anchorY + SLOT_SPACING * SLOTS + 60, anchorZ + 80);
    }

    private static void discardAll(ServerLevel sl, AABB box) {
        for (Entity victim : sl.getEntities((Entity) null, box, candidate -> !(candidate instanceof Player))) {
            victim.discard();
        }
    }

    /** Removes anything left over from a preview that never got cleaned up (a crash, say): nothing else lives this high. */
    private void sweepOrphans() {
        if (server == null || this.minecraft.level == null || this.minecraft.player == null) {
            return;
        }
        var dim = this.minecraft.level.dimension();
        double px = this.minecraft.player.getX();
        double pz = this.minecraft.player.getZ();
        double low = this.minecraft.level.getMaxBuildHeight() + 16;
        double high = low + 48 + SLOT_SPACING * SLOTS + 80;
        try {
            server.execute(() -> {
                ServerLevel sl = server.getLevel(dim);
                if (sl != null) {
                    discardAll(sl, new AABB(px - 160, low, pz - 160, px + 160, high, pz + 160));
                }
            });
        } catch (RuntimeException ex) {
            EncephalonMod.LOGGER.warn("Mob Editor preview: could not sweep leftover preview entities", ex);
        }
    }

    private int freeSlot() {
        for (int i = 0; i < SLOTS; i++) {
            if (!slotUsed[i]) {
                slotUsed[i] = true;
                return i;
            }
        }
        return -1;
    }

    /**
     * Queues the hidden twin of a modded mob so the entities its server logic creates exist for the preview. Nothing here waits
     * for the server: the result is picked up by twinReady() on a later frame.
     */
    private void startTwin(Preview p) {
        EntityType<?> type = p.type;
        var dim = this.minecraft.level.dimension();
        final double x = p.ax;
        final double y = p.ay;
        final double z = p.az;
        final AABB box = p.box();
        final String namespace = BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace();
        final java.util.concurrent.atomic.AtomicInteger result = p.twinResult;
        try {
            server.execute(() -> result.set(spawnTwin(dim, type, box, x, y, z, namespace)));
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor preview: server twin failed for {}", type, ex);
            result.set(TWIN_FAILED);
        }
    }

    /** Server thread. Spawns and fast-forwards the twin; returns its entity id, or TWIN_FAILED. */
    private int spawnTwin(ResourceKey<Level> dim, EntityType<?> type, AABB box, double x, double y, double z, String namespace) {
        if (PreviewZone.zone == null || server == null) {
            return TWIN_FAILED; // the editor was closed before this ran
        }
        int id = TWIN_FAILED;
        PreviewZone.beginSpawn(namespace); // anything this mob creates outside the column, or that is not from its own mod, is vetoed
        try {
            ServerLevel sl = server.getLevel(dim);
            if (sl == null) {
                return TWIN_FAILED;
            }
            discardAll(sl, box);
            Entity made = type.create(sl);
            if (!(made instanceof Mob mob)) {
                if (made != null) {
                    made.discard();
                }
                return TWIN_FAILED;
            }
            try {
                PreviewZone.expectTwin(mob);
                mob.moveTo(x, y, z, 180F, 0F);
                mob.setNoAi(true);
                mob.setNoGravity(true);
                mob.setInvulnerable(true);
                mob.setSilent(true);
                mob.setPersistenceRequired();
                mob.addTag(PREVIEW_TAG);
                // Same as /summon: lets mods set up what they normally set up on spawn (emerge state, riders, ...).
                mob.finalizeSpawn(sl, sl.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.COMMAND, null);
                if (sl.addFreshEntity(mob)) {
                    id = mob.getId();
                    PreviewZone.pinTwin(mob, new Vec3(x, y, z)); // it must not drift: its parts are drawn relative to this spot
                }
            } catch (RuntimeException | LinkageError ex) {
                EncephalonMod.LOGGER.warn("Mob Editor preview: could not spawn the server twin of {}", type, ex);
                mob.discard();
                discardAll(sl, box);
            }
        } catch (RuntimeException | LinkageError ex) {
            EncephalonMod.LOGGER.warn("Mob Editor preview: server twin failed for {}", type, ex);
        } finally {
            PreviewZone.endSpawn();
        }
        if (id >= 0) {
            // Do not wait for the real-time server ticks that build the body (segments, heads, ...): run them now.
            preTickTwin(dim, box, x, y, z, id, namespace);
        }
        return id;
    }

    /** Render thread. True once the twin exists; a failed twin is dropped here (and the mob is shown without one). */
    private boolean twinReady(Preview p) {
        if (p.twinId >= 0) {
            return true;
        }
        int r = p.twinResult.get();
        if (r == TWIN_PENDING) {
            return false;
        }
        if (r == TWIN_FAILED) {
            NO_TWIN.add(p.type);
            if (p.slot >= 0) {
                slotUsed[p.slot] = false;
                p.slot = -1;
            }
            p.settled = true;
            return false;
        }
        p.twinId = r;
        p.twinStartTick = this.minecraft.level.getGameTime();
        p.lastCount = -1;
        p.countChangedTick = p.twinStartTick;
        p.settled = false;
        return true;
    }

    /**
     * Server thread. Fast-forwards a freshly spawned twin: the twin and everything it has made so far (each pass re-reads the
     * box, so parts created by earlier passes are ticked too) are ticked up to TWIN_PRETICKS times, within a time budget,
     * without waiting 50 ms per tick. A body that a mod assembles over a second or two of ticks is therefore complete (or
     * nearly) when the client first sees it. Runs inside the same containment as spawning (PreviewZone.beginSpawn), so
     * everything that is not a body part of the twin is still vetoed. A tick that throws just skips that entity this pass.
     * Works for any mod: nothing here knows what the twin makes.
     */
    private void preTickTwin(ResourceKey<Level> dim, AABB box, double x, double y, double z, int twinId, String namespace) {
        ServerLevel sl = server == null ? null : server.getLevel(dim);
        if (sl == null || PreviewZone.zone == null) {
            return;
        }
        Entity twin = sl.getEntity(twinId);
        if (twin == null || twin.isRemoved() || !twin.getTags().contains(PREVIEW_TAG)) {
            return;
        }
        long deadline = System.nanoTime() + TWIN_PRETICK_BUDGET_NANOS;
        PreviewZone.beginSpawn(namespace);
        try {
            for (int i = 0; i < TWIN_PRETICKS && System.nanoTime() < deadline && !twin.isRemoved(); i++) {
                for (Entity e : sl.getEntities((Entity) null, box, c -> !(c instanceof Player))) {
                    if (e.isRemoved()) {
                        continue;
                    }
                    try {
                        e.setOldPosAndRot();
                        e.tickCount++;
                        e.tick();
                    } catch (RuntimeException | LinkageError ex) {
                        // skip this one for this pass
                    }
                }
                if (!twin.isRemoved()) {
                    twin.setPos(x, y, z); // same pin PreviewZone applies after each real tick
                    twin.setDeltaMovement(Vec3.ZERO);
                }
            }
        } finally {
            PreviewZone.endSpawn();
        }
    }

    /** Removes a mob's twin and everything it spawned, and gives its slot back. */
    private void stopTwin(Preview p) {
        if (p.slot < 0) {
            return;
        }
        slotUsed[p.slot] = false;
        p.slot = -1;
        p.twinId = -1;
        if (server == null || this.minecraft.level == null) {
            return;
        }
        var dim = this.minecraft.level.dimension();
        final AABB box = p.box();
        try {
            server.execute(() -> {
                ServerLevel sl = server.getLevel(dim);
                if (sl != null) {
                    discardAll(sl, box);
                }
            });
        } catch (RuntimeException ex) {
            EncephalonMod.LOGGER.warn("Mob Editor preview: could not remove the server twin", ex);
        }
    }

    private void dispose(Preview p) {
        stopTwin(p);
    }

    private void clearAllTwins() {
        boolean any = false;
        for (int i = 0; i < SLOTS; i++) {
            any |= slotUsed[i];
            slotUsed[i] = false;
        }
        AABB z = PreviewZone.zone;
        if (any && z != null && server != null && this.minecraft.level != null) {
            var dim = this.minecraft.level.dimension();
            try {
                server.execute(() -> {
                    ServerLevel sl = server.getLevel(dim);
                    if (sl != null) {
                        discardAll(sl, z);
                    }
                });
            } catch (RuntimeException ex) {
                EncephalonMod.LOGGER.warn("Mob Editor preview: could not remove the server twins", ex);
            }
        }
    }

    @Override
    public void removed() {
        clearAllTwins();
        active = null;
        // Anything a preview mob's client code added to the client level up there (the server twins are removed above).
        AABB zone = PreviewZone.zone;
        if (zone != null && this.minecraft.level != null) {
            for (Entity stray : this.minecraft.level.getEntities((Entity) null, zone, c -> !(c instanceof Player))) {
                stray.discard();
            }
        }
        PreviewZone.close();
        PreviewLevel.closeAll();
        super.removed();
    }

    /**
     * Entities around a preview mob that belong to it: parts, a head, tails, orbiting bones. Also decides when the group has settled
     * (and learns which mob types have companions at all, so later previews know whether a twin is worth it).
     * Scanned once per frame and shared by every caller of that frame (the scan walks a big box of the client level).
     */
    private List<Entity> rawCompanions(Preview p) {
        if (p.slot < 0 || this.minecraft.level == null) {
            return List.of();
        }
        if (p.compFrame == frame) {
            return p.compCache;
        }
        if (p.twinId < 0 && !twinReady(p)) {
            return List.of();
        }
        // Companions of the twin's own mod (see PreviewZone.isPart). Things the twin made that are not part of the mob (the
        // Void Worm's portal) are effects of the twin standing still up there, and are left out.
        List<Entity> found = this.minecraft.level.getEntities((Entity) null, p.box(),
                en -> PreviewZone.isPart(en) && en.getId() != p.twinId && en != p.entity && en != this.minecraft.player);
        long now = this.minecraft.level.getGameTime();
        if (found.size() != p.lastCount) {
            p.lastCount = found.size();
            p.countChangedTick = now;
        }
        long age = now - p.twinStartTick;
        p.settled = now - p.countChangedTick >= SETTLE_STABLE_TICKS && age >= SETTLE_MIN_TICKS;
        if (p.settled) {
            if (!found.isEmpty()) {
                MULTI.add(p.type);
            } else if (age >= SOLO_TICKS) {
                SOLO.add(p.type);
                stopTwin(p);
                p.settled = true;
            }
        }
        p.compFrame = frame;
        p.compCache = found;
        return found;
    }

    // ------------------------------------------------------------------ preview selection and caching

    private static boolean warmedUp(Preview p) {
        if (isVanilla(p.type)) {
            return true;
        }
        return p.warmTicks >= WARMUP_TICKS && p.stable;
    }

    /**
     * Whether the mob may be shown. A mob known to have parts waits until they have settled so it never appears piecemeal;
     * one not known to have any shows after the warm-up (parts that turn up later still wait to appear together).
     */
    private boolean revealable(Preview p) {
        if (!warmedUp(p)) {
            return false;
        }
        if (p.slot < 0 || p.settled) {
            return true;
        }
        return p.lastCount <= 0 && !MULTI.contains(p.type);
    }

    /** The preview of the selected mob type: made on first use (and any previous one released), then reused every frame. */
    private Preview current() {
        if (this.minecraft.level == null || scope.type() != ScopeType.MOB_TYPE) {
            return null;
        }
        EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(scope.id()));
        if (active != null && active.type == t) {
            return active;
        }
        if (badTypes.contains(t)) {
            return null;
        }
        Preview p = create(t);
        if (p == null) {
            return null;
        }
        release(active);
        active = p;
        return p;
    }

    /** The preview of a mob that is no longer shown is dropped, together with its server twin. */
    private void release(Preview old) {
        if (old != null) {
            stopTwin(old);
        }
    }

    /** Creates and sets up a preview mob (and its twin when it may need one), or returns null if it can't be made. */
    private Preview create(EntityType<?> t) {
        long t0 = System.nanoTime();
        Preview made = createImpl(t);
        slow("creating preview (constructor + server twin)", t, t0);
        return made;
    }

    private Preview createImpl(EntityType<?> t) {
        if (this.minecraft.level == null || badTypes.contains(t)) {
            return null;
        }
        int slot = -1;
        if (server != null && !isVanilla(t) && !SOLO.contains(t) && !NO_TWIN.contains(t) && !noTwin(t)) {
            slot = freeSlot();
        }
        double ay = anchorY + Math.max(slot, 0) * SLOT_SPACING;
        LivingEntity e = makeEntity(t, anchorX, ay, anchorZ);
        if (e == null) {
            if (slot >= 0) {
                slotUsed[slot] = false;
            }
            return null;
        }
        Preview p = new Preview(t, e, ay, slot);
        if (slot >= 0) {
            startTwin(p);
        }
        return p;
    }

    private LivingEntity makeEntity(EntityType<?> t, double x, double y, double z) {
        try {
            Entity e = createIn(t, levelFor(aquatic(t)));
            if (e instanceof LivingEntity living) {
                living.setSilent(true);
                if (living instanceof Hoglin || living instanceof AbstractPiglin) {
                    // Stops them shaking as if converting to zombified in the overworld. Not for other mobs: the dragon,
                    // for one, freezes its wings when it has no AI.
                    ((Mob) living).setNoAi(true);
                }
                faceViewer(living);
                // Park it at its spot now, while tickCount is still 0: entities treat a move after that as a teleport
                // (a shulker, for one, slides in from its old position).
                living.setPos(x, y, z);
                living.setOldPosAndRot();
                living.setDeltaMovement(previewMotion(living));
                // Start in the steady walking state instead of ramping up to it after the first frame.
                living.walkAnimation.update(WALK_SPEED, 1.0F);
                return living;
            }
        } catch (Exception | LinkageError ex) {
            // fall through and blacklist
        }
        badTypes.add(t);
        return null;
    }

    // ------------------------------------------------------------------ hidden draws

    /** Draws with an empty clip area: the animation library runs, nothing is shown. Returns false if drawing failed. */
    private boolean hiddenRender(GuiGraphics g, Preview p, boolean withMain, float partial, List<Entity> extras, float realPartial) {
        long t0 = System.nanoTime();
        boolean done = hiddenRenderImpl(g, p, withMain, partial, extras, realPartial);
        slow("hidden warm-up render", p.type, t0);
        return done;
    }

    private boolean hiddenRenderImpl(GuiGraphics g, Preview p, boolean withMain, float partial, List<Entity> extras, float realPartial) {
        LivingEntity e = p.entity;
        faceViewer(e);
        forceWet(e);
        e.setInvisible(false);
        boolean ok = true;
        g.enableScissor(rx, TOP, rx, viewBottom());
        PoseStack pose = g.pose();
        pose.pushPose();
        EntityRenderDispatcher dispatcher = this.minecraft.getEntityRenderDispatcher();
        try {
            pose.translate(rx, TOP, 50.0);
            pose.scale(1F, 1F, -1F);
            pose.mulPose(new Quaternionf().rotateZ((float) Math.PI));
            Lighting.setupForEntityInInventory();
            dispatcher.setRenderShadow(false);
            measuring = withMain && p.canMeasure();
            RenderSystem.runAsFancy(() -> drawModels(g, p, withMain, partial, extras, realPartial, pose, dispatcher));
            g.flush();
        } catch (Exception | LinkageError ex) {
            ok = false;
        } finally {
            measuring = false;
            dispatcher.setRenderShadow(true);
            pose.popPose();
            Lighting.setupFor3DItems();
        }
        g.disableScissor();
        return ok;
    }

    /** Draws the mob and its companions. Companions are placed relative to the mob, which sits at its anchor. */
    private void drawModels(GuiGraphics g, Preview p, boolean withMain, float partial, List<Entity> extras,
                            float realPartial, PoseStack pose, EntityRenderDispatcher dispatcher) {
        if (withMain) {
            MultiBufferSource source = g.bufferSource();
            if (measuring) {
                source = new ExtentProbe(source, p, new Matrix4f(pose.last().pose()).invert());
            }
            final MultiBufferSource use = source;
            // The renderer is the mod's code too: it runs with the singleton pointed at the dummy world.
            PreviewSandbox.asLevelRun(p.entity.level(),
                    () -> dispatcher.render(p.entity, 0.0, 0.0, 0.0, 0.0F, partial, pose, use, 15728880));
        }
        for (Entity c : extras) {
            // These are ticked by the real client clock. A companion that fails to draw is skipped; it should not take
            // the whole preview down with it.
            try {
                double dx = Mth.lerp(realPartial, c.xOld, c.getX()) - p.ax;
                double dy = Mth.lerp(realPartial, c.yOld, c.getY()) - p.ay;
                double dz = Mth.lerp(realPartial, c.zOld, c.getZ()) - p.az;
                dispatcher.render(c, dx, dy, dz, Mth.lerp(realPartial, c.yRotO, c.getYRot()), realPartial, pose,
                        g.bufferSource(), 15728880);
            } catch (RuntimeException ex) {
                // skip this one
            }
        }
    }

    /**
     * Animation libraries (GeckoLib and the like) blend from the bind pose into idle over a number of ticks of the mob's age.
     * Instead of waiting for that in real time, a batch of ticks is simulated in one frame, each one advancing tickCount, and the
     * mob is then rendered off-screen once, so the blend is over before the mob is ever shown. The draw is the expensive part,
     * so there is one per frame whatever the batch size. Rendered at partial 0 so the animation clock never runs backwards when the real-time stepping takes over.
     * Returns false if drawing failed.
     */
    private boolean fastForward(GuiGraphics g, Preview p, List<Entity> raw, float realPartial) {
        int steps = FAST_STEPS;
        for (int i = 0; i < steps; i++) {
            stepPreview(p);
        }
        boolean ok = hiddenRender(g, p, true, 0F, raw, realPartial);
        p.trackStable(steps);
        p.sim = System.nanoTime();
        return ok;
    }

    /**
     * Passes everything through to the real buffer and records where the vertices land. Each vertex is put back through the
     * inverse of the pose the mob is drawn with, which gives its position in blocks around the mob's anchor whatever the
     * scale, sway or turn of the pose is.
     */
    private static final class ExtentProbe implements MultiBufferSource {
        private final MultiBufferSource delegate;
        private final Preview p;
        private final Matrix4f inv;

        ExtentProbe(MultiBufferSource delegate, Preview p, Matrix4f inv) {
            this.delegate = delegate;
            this.p = p;
            this.inv = inv;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return new Tap(delegate.getBuffer(type));
        }

        private final class Tap implements VertexConsumer {
            private final VertexConsumer out;
            private final Vector3f tmp = new Vector3f();

            Tap(VertexConsumer out) {
                this.out = out;
            }

            @Override
            public VertexConsumer addVertex(float x, float y, float z) {
                inv.transformPosition(x, y, z, tmp);
                p.measure(tmp.x, tmp.y, tmp.z);
                out.addVertex(x, y, z);
                return this;
            }

            @Override
            public VertexConsumer setColor(int r, int g, int b, int a) {
                out.setColor(r, g, b, a);
                return this;
            }

            @Override
            public VertexConsumer setUv(float u, float v) {
                out.setUv(u, v);
                return this;
            }

            @Override
            public VertexConsumer setUv1(int u, int v) {
                out.setUv1(u, v);
                return this;
            }

            @Override
            public VertexConsumer setUv2(int u, int v) {
                out.setUv2(u, v);
                return this;
            }

            @Override
            public VertexConsumer setNormal(float x, float y, float z) {
                out.setNormal(x, y, z);
                return this;
            }
        }
    }

    // ------------------------------------------------------------------ stepping

    /** Fixed-timestep simulation driven by the real clock, so age = tickCount + partial never goes backwards. */
    private void stepFrames(Preview p, long now) {
        if (now - p.sim > 1_000_000_000L) {
            p.sim = now; // was not rendering for a while (cached, or a behavior was open); don't fast-forward
        }
        int steps = 0;
        while (now - p.sim >= TICK_NANOS) {
            p.sim += TICK_NANOS;
            if (steps++ < 2) {
                stepPreview(p);
            }
        }
    }

    /** One preview step, run inside the sandbox: the mob's tick is the mod's code, so Minecraft.level is the dummy world meanwhile. */
    private void stepPreview(Preview p) {
        PreviewSandbox.asLevelRun(p.entity.level(), () -> stepPreviewInner(p));
    }

    /**
     * One 20 Hz step of the preview mob. The entity is ticked so its own animations run (dragon wings, bat, guardian, ...),
     * but the walk cycle is driven here at a fixed speed. If the walk state can't be pinned, only the dragon is
     * ticked and everything else is animated by hand.
     * The level normally does the per-tick bookkeeping (old position and tickCount) before entity.tick(); tickCount is the
     * "age" that rods, tentacles, wings and segments animate from, so it must advance here or those mobs vibrate.
     */
    private void stepPreviewInner(Preview p) {
        if (this.minecraft.level == null || this.minecraft.player == null) {
            return;
        }
        LivingEntity e = p.entity;
        boolean dragon = e instanceof EnderDragon;
        e.tickCount++;
        p.warmTicks++;
        // Modded swimmers live in the flooded dummy world, where the game's own water logic works, so they are ticked like any
        // other mob. Only if no dummy world could be built (the entity is in the real level) is a modded fish not ticked: the game
        // resets the water flag to dry at the start of every tick, before the mod's own tick code runs.
        // Quiet mobs (listed in the config) are not ticked either: their client tick code can start boss music or
        // change the sky, which a preview must never do.
        boolean skipTick = (e instanceof Guardian && !CAN_PIN_GUARDIAN)
                || (!isVanilla(e.getType()) && isFish(e) && !(e.level() instanceof PreviewLevel))
                || quiet(e.getType());
        if (!p.tickFailed && !skipTick && (CAN_PIN_WALK || dragon)) {
            try {
                double hx = p.ax;
                double hy = p.ay;
                double hz = p.az;
                float[] walk = null;
                if (CAN_PIN_WALK) {
                    walk = new float[] {WALK_SPEED_OLD.getFloat(e.walkAnimation), WALK_SPEED_NOW.getFloat(e.walkAnimation),
                            WALK_POS.getFloat(e.walkAnimation)};
                }
                faceViewer(e); // the dragon records its yaw every tick and renders from that history
                e.setPos(hx, hy, hz);
                e.setOldPosAndRot();
                // The dragon's wing flap rate depends on its speed (a stationary one flaps far too fast), and the dolphin
                // only animates while it is moving.
                e.setDeltaMovement(previewMotion(e));
                // Slimes only squish on ground transitions, so make them hop in place.
                // Fish stay "airborne" so they do not flop about, as they would out of water.
                // A dry dolphin on the ground also flops (random hop, random yaw), which showed as a stutter.
                boolean grounded = e instanceof Slime ? e.tickCount % 24 < 16
                        : !isFlyer(e) && !isFish(e) && !(e instanceof Dolphin);
                e.setOnGround(grounded);
                forceWet(e);
                // Rabbits only animate while hopping, and nothing starts a hop client-side, so start one every so often.
                if (e instanceof Rabbit rabbit && rabbit.getJumpCompletion(0F) == 0F && e.tickCount % RABBIT_HOP_INTERVAL == 0) {
                    rabbit.startJumping();
                }
                float tail = 0F;
                boolean pinGuardian = e instanceof Guardian && CAN_PIN_GUARDIAN;
                if (pinGuardian) {
                    tail = GUARDIAN_TAIL.getFloat(e);
                }
                // Slimes set oSquish = squish every tick, which makes the squish step at 20 Hz; keep the previous value to lerp.
                float squish = e instanceof Slime sl ? sl.squish : 0F;
                long tickStart = System.nanoTime();
                e.tick();
                // A mob whose own tick is expensive (a big modded model that scans blocks or entities around it, say) is a
                // frame-time problem on every frame, so after a few slow ticks it is drawn but no longer ticked.
                if (System.nanoTime() - tickStart > SLOW_TICK_NANOS) {
                    if (++p.slowTicks >= SLOW_TICK_LIMIT) {
                        p.tickFailed = true;
                        EncephalonMod.LOGGER.warn("Mob Editor preview: {} has a slow tick; it is drawn without being ticked",
                                BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
                    }
                } else if (p.slowTicks > 0 && (e.tickCount & 15) == 0) {
                    p.slowTicks--;
                }
                e.setPos(hx, hy, hz);
                forceWet(e);
                // Some mobs hide themselves until something server-side reveals them (the Farseer stays invisible until a
                // player walks up); the preview has no such trigger, so keep it visible.
                e.setInvisible(false);
                if (e instanceof Slime sl) {
                    sl.oSquish = squish;
                }
                if (e instanceof Squid squid) {
                    // Out of water a squid slowly tips over; keep it upright with the tentacles hanging down.
                    squid.xBodyRot = 0F;
                    squid.xBodyRotO = 0F;
                    squid.zBodyRot = 0F;
                    squid.zBodyRotO = 0F;
                }
                if (e instanceof Shulker shulker) {
                    pinShulker(shulker);
                }
                if (pinGuardian) {
                    GUARDIAN_TAIL_O.setFloat(e, tail);
                    GUARDIAN_TAIL.setFloat(e, tail + GUARDIAN_TAIL_SPEED);
                    GUARDIAN_SPIKES_O.setFloat(e, GUARDIAN_SPIKES);
                    GUARDIAN_SPIKES_F.setFloat(e, GUARDIAN_SPIKES);
                }
                if (walk != null) {
                    WALK_SPEED_OLD.setFloat(e.walkAnimation, walk[0]);
                    WALK_SPEED_NOW.setFloat(e.walkAnimation, walk[1]);
                    WALK_POS.setFloat(e.walkAnimation, walk[2]);
                }
            } catch (Exception | LinkageError ex) {
                p.tickFailed = true;
            }
        }
        e.walkAnimation.update(e instanceof Turtle ? TURTLE_WALK_SPEED : WALK_SPEED, 0.4F);
    }

    /** How far the shulker's lid is open (0 closed, 1 fully open): a slow cycle that rests briefly at each end. */
    private static float shulkerPeek(int tick) {
        return Mth.clamp(0.5F + 0.7F * (float) Math.sin(tick * 0.1), 0F, 1F);
    }

    /** Opens and closes the lid by hand and cancels the slide-in animation. Best effort: a missing field is ignored. */
    private static void pinShulker(Shulker s) {
        try {
            if (SHULKER_PEEK != null && SHULKER_PEEK_O != null) {
                SHULKER_PEEK_O.setFloat(s, shulkerPeek(s.tickCount - 1));
                SHULKER_PEEK.setFloat(s, shulkerPeek(s.tickCount));
            }
            if (SHULKER_SLIDE != null) {
                SHULKER_SLIDE.setInt(s, 0);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // leave the shulker as it is
        }
    }

    // ------------------------------------------------------------------ preview drawing

    private void renderPreview(GuiGraphics g, float realPartial) {
        if (scope.type() == ScopeType.MOB_TYPE) {
            Preview p = current();
            if (p != null) {
                drawActive(g, p, realPartial);
            } else if (badTypes.contains(BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(scope.id())))) {
                drawPreviewOff(g);
            }
        } else {
            // Vanilla Mobs / a category / a mod: no mob, just the Minecraft logo or the mod's name.
            release(active);
            active = null;
            drawScopeIcon(g);
        }
    }

    private static LogoRenderer mcLogo;

    /**
     * Vanilla scopes show the Minecraft logo. Mod and mod-category scopes show just the mod's name written out (no mod logos).
     * All / Modded Mobs show nothing.
     */
    private void drawScopeIcon(GuiGraphics g) {
        String mod = null;
        boolean vanilla = false;
        switch (scope.type()) {
            case MOD -> mod = scope.id();
            case MOD_CATEGORY -> mod = scope.id().substring(0, scope.id().indexOf('/'));
            case CATEGORY -> vanilla = true;
            case ORIGIN -> vanilla = scope.id().equals("vanilla");
            default -> {
            }
        }
        int top = TOP + 6;
        int bottom = viewBottom() - 16;
        float cx = rx + rw / 2F;
        float cy = (top + bottom) / 2F;
        if (vanilla) {
            if (mcLogo == null) {
                mcLogo = new LogoRenderer(false);
            }
            float s = Math.min(1F, (rw - 12) / 256F);
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0F);
            g.pose().scale(s, s, 1F);
            mcLogo.renderLogo(g, 0, 1F, -26); // screen width 0 centres it on the origin
            g.pose().popPose();
            return;
        }
        if (mod == null) {
            return;
        }
        List<FormattedCharSequence> lines = this.font.split(labelOf(new Scope(ScopeType.MOD, mod)), Math.max(40, rw - 16));
        int lineH = 10;
        int y0 = (int) cy - lines.size() * lineH / 2;
        for (int i = 0; i < lines.size(); i++) {
            g.drawCenteredString(this.font, lines.get(i), (int) cx, y0 + i * lineH, 0xC8C8C8);
        }
    }

    private void drawActive(GuiGraphics g, Preview p, float realPartial) {
        long t0 = System.nanoTime();
        drawActiveImpl(g, p, realPartial);
        long took = System.nanoTime() - t0;
        slow("drawing shown mob", p.type, t0);
        govern(p, took);
    }

    /**
     * Frame-time governor for the shown mob, whatever its size or mod. The cost of one frame of it (tick + draw) is tracked as a
     * moving average. Above FREEZE_NANOS its own tick is switched off (the model is still drawn and animated from its age); if it
     * is still above OFF_NANOS, or a couple of frames are enormous, the preview of this mob is switched off for the session
     * instead of freezing the game. The reason is written to the log with the mob id.
     */
    private void govern(Preview p, long took) {
        if (active != p) {
            return;
        }
        if (took > OFF_NANOS * 3) {
            if (++p.hugeFrames >= (warmedUp(p) ? 2 : 4)) {
                trip(p, took);
                return;
            }
        } else if (p.hugeFrames > 0 && (frame & 63L) == 0L) {
            p.hugeFrames--;
        }
        if (!warmedUp(p)) {
            return;
        }
        p.avgNanos += (took - p.avgNanos) * 0.1;
        if (++p.govFrames < GOVERN_SETTLE_FRAMES) {
            return;
        }
        if (!p.tickFailed && p.avgNanos > FREEZE_NANOS) {
            p.tickFailed = true;
            p.govFrames = 0;
            p.avgNanos = 0;
            EncephalonMod.LOGGER.warn("Mob Editor preview: {} costs over {} ms per frame; it is drawn without being ticked",
                    BuiltInRegistries.ENTITY_TYPE.getKey(p.type), FREEZE_NANOS / 1_000_000L);
        } else if (p.avgNanos > OFF_NANOS) {
            trip(p, took);
        }
    }

    private void trip(Preview p, long took) {
        EncephalonMod.LOGGER.warn("Mob Editor preview: {} is too slow ({} ms last frame, {} ms average); preview switched off for this mob",
                BuiltInRegistries.ENTITY_TYPE.getKey(p.type), took / 1_000_000L, (long) (p.avgNanos / 1_000_000.0));
        badTypes.add(p.type);
        dispose(p);
        if (active == p) {
            active = null;
        }
    }

    /** Panel text for a mob whose preview could not be made or was switched off (badTypes). */
    private void drawPreviewOff(GuiGraphics g) {
        List<FormattedCharSequence> lines = this.font.split(Component.translatable("encephalon.screen.preview_off"), Math.max(40, rw - 16));
        int y0 = (TOP + previewBottom()) / 2 - lines.size() * 5;
        for (int i = 0; i < lines.size(); i++) {
            g.drawCenteredString(this.font, lines.get(i), rx + rw / 2, y0 + i * 10, 0xFFAA00);
        }
    }

    private void drawActiveImpl(GuiGraphics g, Preview p, float realPartial) {
        LivingEntity e = p.entity;
        long now = System.nanoTime();
        if (p.firstRender == 0L) {
            p.firstRender = now;
        }
        if (!warmedUp(p)) {
            fastForward(g, p, rawCompanions(p), realPartial);
            now = System.nanoTime();
        }
        stepFrames(p, now);
        float partial = (now - p.sim) / (float) TICK_NANOS;

        List<Entity> raw = rawCompanions(p);
        p.growEnv(raw);
        // Companions are only drawn together once the group has settled; until then they are just warmed up hidden.
        List<Entity> extras = p.settled && !raw.isEmpty() ? (raw.size() > 64 ? raw.subList(0, 64) : raw) : List.of();
        boolean visible = revealable(p);

        float bbH = Math.max(e.getBbHeight(), 0.4F);
        float bbW = Math.max(e.getBbWidth(), 0.4F);
        boolean dragon = e instanceof EnderDragon;
        boolean modded = !isVanilla(p.type);
        if (!modded && !p.vanillaMeasured && !dragon && !(e instanceof Squid || e instanceof Ghast)) {
            // Size a vanilla mob by what is really drawn (a giant's model is six times its type's default, say), not by its
            // hitbox: one hidden draw, before anything is shown, so nothing jumps afterwards.
            p.vanillaMeasured = true;
            p.forceMeasure = true;
            hiddenRender(g, p, true, 0F, List.of(), realPartial);
            p.forceMeasure = false;
        }
        boolean measured = modded && p.hasMeasure();
        boolean fitNew = modded && (measured || p.hasEnv);
        int top = TOP + 6 + warnH;
        int bottom = previewBottom() - (fitNew ? LABEL_STRIP : 16);
        float panelH = bottom - top;
        double swayDeg = dragon ? 12.0 : 40.0;

        float tScale;
        float tCenterY;
        float tPivX = 0F;
        float tPivZ = 0F;
        float tYaw = 0F;
        if (fitNew) {
            // Bounds of what is really drawn (blocks around the anchor); the hitbox only stands in until it has been measured.
            float minX = -bbW / 2F;
            float maxX = bbW / 2F;
            float minY = 0F;
            float maxY = bbH;
            float minZ = -bbW / 2F;
            float maxZ = bbW / 2F;
            if (measured) {
                float[] r = new float[2];
                p.range(0, r);
                minX = r[0];
                maxX = r[1];
                p.range(1, r);
                minY = r[0];
                maxY = r[1];
                p.range(2, r);
                minZ = r[0];
                maxZ = r[1];
            }
            if (p.hasEnv) {
                minX = Math.min(minX, p.env[0]);
                minY = Math.min(minY, p.env[1]);
                minZ = Math.min(minZ, p.env[2]);
                maxX = Math.max(maxX, p.env[3]);
                maxY = Math.max(maxY, p.env[4]);
                maxZ = Math.max(maxZ, p.env[5]);
            }
            float spanH = Math.max(maxY - minY, 0.4F);
            float spanX = Math.max(maxX - minX, 0.4F);
            float spanZ = Math.max(maxZ - minZ, 0.4F);
            float core = Math.max(spanH, Math.min(spanX, spanZ));  // the size that must look right: height or thickness
            float longest = Math.max(spanX, spanZ);
            boolean elongatedNow = longest > ELONGATED_RATIO * core;
            boolean elongated = p.elongLatched || elongatedNow;
            boolean longX = p.elongLatched ? p.longX : spanX > spanZ;
            if (visible && elongatedNow && !p.elongLatched) {
                p.elongLatched = true;
                p.longX = longX;
            }
            float cap = elongated ? LENGTH_CAP * core : longest;    // how much of the length is fitted
            float fitX = Math.min(spanX, cap);
            float fitZ = Math.min(spanZ, cap);
            if (elongated) {
                // Turn it so the long axis mostly runs into the screen.
                tYaw = longX ? (float) (Math.PI / 2.0 - GROUP_VIEW_ANGLE) : GROUP_VIEW_ANGLE;
                swayDeg = GROUP_SWAY_DEG;
            }
            double swayRad = Math.toRadians(swayDeg);
            double worstW = 0.0;
            for (int k = -1; k <= 1; k++) {
                double a = tYaw + k * swayRad;
                worstW = Math.max(worstW, fitX * Math.abs(Math.cos(a)) + fitZ * Math.abs(Math.sin(a)));
            }
            if (!elongated && measured && !p.hasEnv) {
                // A round mob is narrower than its box: turning it about the middle of the box, no vertex gets farther from
                // the middle than the farthest one is from the anchor plus the offset of the middle.
                double radial = 2.0 * (p.radius() + Math.hypot((minX + maxX) / 2.0, (minZ + maxZ) / 2.0));
                worstW = Math.min(worstW, Math.max(radial, spanH * 0.0 + 0.4));
            }
            tScale = Math.min(baseScale(panelH, core), Math.min(panelH * FILL_H / spanH, rw * FILL_W / (float) Math.max(worstW, 0.4)));
            tCenterY = (minY + maxY) / 2F;
            tPivX = (minX + maxX) / 2F;
            tPivZ = (minZ + maxZ) / 2F;
            if (elongated && longest > cap) {
                // Part of the length will be cut off: keep the end the mob's own origin (its head) is in on screen.
                if (longX) {
                    tPivX = Mth.clamp(0F, minX + cap / 2F, maxX - cap / 2F);
                } else {
                    tPivZ = Mth.clamp(0F, minZ + cap / 2F, maxZ - cap / 2F);
                }
            }
        } else {
            float extentH;
            float extentW;
            float fillH;
            float fillW;
            boolean useMeasured = false;
            float measuredCenter = 0F;
            if (dragon) {
                extentH = DRAGON_H;
                extentW = DRAGON_W;
                fillH = 0.95F;
                fillW = 0.95F;
            } else if (e instanceof Squid || e instanceof Ghast) {
                extentH = e instanceof Ghast ? GHAST_EXTENT_H : SQUID_EXTENT_H;
                extentW = bbW * 2.2F;
                fillH = 0.7F;
                fillW = 0.8F;
            } else {
                // Models are often longer/wider than their hitbox (camel), so fit against a padded extent.
                extentH = Math.max(bbH, bbW * 1.2F);
                extentW = bbW * 2.2F;
                fillH = 0.9F;
                fillW = 0.8F;
                if (!modded && p.vanillaMeasured && p.hasMeasure()) {
                    float[] mr = new float[2];
                    p.range(1, mr);
                    extentH = Math.max(mr[1] - mr[0], 0.3F);
                    // Turning about the anchor, nothing gets farther from the middle than the farthest vertex.
                    extentW = Math.max(2F * p.radius(), 0.3F) * 1.15F;
                    useMeasured = true;
                    measuredCenter = (mr[0] + mr[1]) / 2F;
                }
            }
            tCenterY = dragon ? DRAGON_CENTER_Y
                    : e instanceof Squid ? SQUID_CENTER_Y
                    : e instanceof Ghast ? GHAST_CENTER_Y
                    : useMeasured ? measuredCenter : bbH / 2F;
            tScale = Math.min(baseScale(panelH, extentH), Math.min(panelH * fillH / extentH, rw * fillW / extentW));
        }

        if (p.sScale < 0F || !visible) { // while it is not shown yet there is nothing to ease
            p.sScale = tScale;
            p.sCenterY = tCenterY;
            p.sPivX = tPivX;
            p.sPivZ = tPivZ;
            p.sYaw = tYaw;
        } else {
            p.sScale += (tScale - p.sScale) * SMOOTH;
            p.sCenterY += (tCenterY - p.sCenterY) * SMOOTH;
            p.sPivX += (tPivX - p.sPivX) * SMOOTH;
            p.sPivZ += (tPivZ - p.sPivZ) * SMOOTH;
            p.sYaw += (tYaw - p.sYaw) * SMOOTH;
        }
        float scale = p.sScale;
        float cx = rx + rw / 2F;
        float feetY = (top + bottom) / 2F + p.sCenterY * scale;

        // Entity faces the viewer; the sway is applied to the pose so it is perfectly smooth.
        faceViewer(e);
        forceWet(e); // the fish renderer lays fish on their side unless they are in water
        e.setInvisible(false);
        float sway = (float) Math.toRadians(swayDeg * Math.sin(Util.getMillis() / 1500.0));
        final float px = p.sPivX;
        final float pz = p.sPivZ;
        final float yaw = p.sYaw;
        final List<Entity> drawExtras = extras;

        boolean failed = false;
        g.enableScissor(rx, TOP, visible ? rx + rw + 6 : rx, previewBottom()); // zero width while warming up: rendered, not shown
        PoseStack pose = g.pose();
        pose.pushPose();
        EntityRenderDispatcher dispatcher = this.minecraft.getEntityRenderDispatcher();
        try {
            pose.translate(cx, feetY, 50.0);
            pose.scale(scale, scale, -scale);
            pose.mulPose(new Quaternionf().rotateZ((float) Math.PI).rotateY(sway + yaw + (dragon ? (float) Math.PI : 0F))); // the dragon model faces the other way
            pose.translate(-px, 0.0F, -pz); // turn around the fitted middle, not the head
            Lighting.setupForEntityInInventory();
            dispatcher.setRenderShadow(false);
            measuring = p.canMeasure();
            RenderSystem.runAsFancy(() -> drawModels(g, p, true, partial, drawExtras, realPartial, pose, dispatcher));
            g.flush();
        } catch (Exception | LinkageError ex) {
            failed = true;
        } finally {
            measuring = false;
            dispatcher.setRenderShadow(true);
            pose.popPose();
            Lighting.setupFor3DItems();
        }
        g.disableScissor();
        if (failed) {
            badTypes.add(p.type);
            dispose(p);
            active = null;
            return;
        }
        // Companions that are not shown yet still have to be rendered, so their animation library has settled by the reveal.
        if (!p.settled && !raw.isEmpty()) {
            hiddenRender(g, p, false, partial, raw, realPartial);
        }
        g.drawCenteredString(this.font, p.type.getDescription(), (int) cx, previewBottom() - 12, 0xC8C8C8);
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // The default drops arrive from the server a moment after the editor asks for them (or the list was changed from
        // elsewhere): rebuild the drop rows when what they are made from has changed.
        if (data != null && dropsOpen && scope.type() == ScopeType.MOB_TYPE && currentDropsSource() != dropsSource) {
            rebuildDropsKeepScroll();
        }
        super.render(g, mouseX, mouseY, partialTick);
        frame++;
        g.drawCenteredString(this.font, this.title, width / 2, 5, 0xFFFFFF);
        if (data == null) {
            g.drawCenteredString(this.font, Component.translatable("encephalon.screen.nodata"), width / 2, height / 2, 0xFF5555);
            return;
        }
        if (!editable) {
            g.drawCenteredString(this.font, Component.translatable("encephalon.screen.readonly"), width / 2, 16, 0xFFAA00);
        }
        if (behaviorList.children().isEmpty()) {
            g.drawCenteredString(this.font, Component.translatable("encephalon.screen.no_behaviors"),
                    mx + mw / 2, TOP + 12, 0x808080);
        }

        // Right column header: scope (with override count) on the first line, behavior on the second.
        int n = data.count(scope);
        Component scopeTitle = labelOf(scope).copy().withStyle(ChatFormatting.RED);
        if (n > 0) {
            scopeTitle = scopeTitle.copy().append(Component.literal(" ")
                    .append(Component.translatable("encephalon.screen.overrides", n)).withStyle(ChatFormatting.GOLD));
        }
        g.enableScissor(rx, 26, rx + rw + 6, TOP - 1);
        g.drawString(this.font, scopeTitle, rx, 28, 0xFFFFFF, true);
        if (scope.type() == ScopeType.MOB_TYPE) {
            // The registry id next to the name tells apart mobs that share a name or look alike.
            int tw = this.font.width(scopeTitle) + 6;
            g.drawString(this.font, fit(scope.id(), rw - tw), rx + tw, 28, 0x707070, true);
        }
        if (dropsOpen) {
            g.drawString(this.font, Component.translatable("encephalon.screen.drops_title").copy().withStyle(ChatFormatting.YELLOW), rx, 39, 0xFFFFFF, true);
        } else if (categoryOpen) {
            g.drawString(this.font, Component.translatable("encephalon.screen.category_title").copy().withStyle(ChatFormatting.YELLOW), rx, 39, 0xFFFFFF, true);
        } else if (damageOpen) {
            g.drawString(this.font, Component.translatable("encephalon.screen.damage_title").copy().withStyle(ChatFormatting.YELLOW), rx, 39, 0xFFFFFF, true);
        } else if (behavior == null) {
            g.drawString(this.font, Component.translatable("encephalon.screen.select"), rx, 39, 0xA0A0A0, true);
        } else {
            g.drawString(this.font, behavior.name().copy().withStyle(ChatFormatting.YELLOW), rx, 39, 0xFFFFFF, true);
        }
        g.disableScissor();
        if (behavior == null && !dropsOpen && !categoryOpen && !damageOpen) {
            PreviewSandbox.run(() -> renderPreview(g, partialTick));
            g.enableScissor(rx, TOP, rx + rw + 6, viewBottom());
            drawWarning(g, 0);
            g.disableScissor();
            return;
        }
        // No preview while a behavior (or the drops) is open: the mob and its server twin are released until the preview is
        // needed again.
        if (active != null) {
            release(active);
            active = null;
        }

        g.enableScissor(rx, TOP, rx + rw + 6, viewBottom());
        drawWarning(g, scroll);
        if (dropsOpen) {
            drawDropsEditor(g, mouseX, mouseY);
            g.disableScissor();
            drawPanelScrollbar(g);
            if (!hovered.isEmpty()) {
                // Drawn last and outside the scissor, so the name is never cut off or covered.
                g.renderTooltip(this.font, hovered.getHoverName(), mouseX, mouseY);
            }
            return;
        }
        if (categoryOpen) {
            drawCategoryEditor(g);
            g.disableScissor();
            drawPanelScrollbar(g);
            return;
        }
        if (damageOpen) {
            drawDamageEditor(g);
            g.disableScissor();
            drawPanelScrollbar(g);
            return;
        }
        for (int i = 0; i < descLines.size(); i++) {
            g.drawString(this.font, descLines.get(i), rx, TOP + 2 + warnH + i * 10 - scroll, 0xC8C8C8, true);
        }

        ConfigResolver.Toggle t = ConfigResolver.toggleFor(data, ConfigResolver.chainFor(scope, data), behavior);
        Component why;
        int whyColor;
        if (t.from() == null) {
            why = Component.translatable("encephalon.screen.from_none");
            whyColor = 0xA0A0A0;
        } else if (t.from().equals(scope)) {
            why = Component.translatable("encephalon.screen.from_here");
            whyColor = 0xFFFFFF;
        } else {
            why = Component.translatable("encephalon.screen.from_other", labelOf(t.from()));
            whyColor = 0xFFFF55;
        }
        g.drawString(this.font, fit(why.getString(), rw), rx, explainY - scroll, whyColor, true);

        for (SettingRow row : rows) {
            Setting s = row.setting();
            boolean set = data.value(scope, behavior, s.key()) != null;
            int y = row.y() - scroll;
            g.drawString(this.font, Component.translatable("setting.encephalon." + behavior.id().getPath() + "." + s.key()),
                    rx, y, set ? 0xFFFFFF : 0xA0A0A0, true);
            g.drawString(this.font, fit(Component.translatable("encephalon.screen.info",
                            fmt(s.def()), s.unit(), fmt(s.min()), fmt(s.max())).getString(), rw),
                    rx, y + 10, 0x808080, true);
        }
        g.disableScissor();

        drawPanelScrollbar(g);
    }

    @Override
    public void onClose() {
        setFocused(null);
        this.minecraft.setScreen(null);
    }
}
