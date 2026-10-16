package com.example.encephalon.client;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.example.encephalon.EncephalonMod;
import com.example.encephalon.behavior.Behavior;
import com.example.encephalon.behavior.Behaviors;
import com.example.encephalon.behavior.Setting;
import com.example.encephalon.config.ConfigResolver;
import com.example.encephalon.config.EncephalonConfig;
import com.example.encephalon.config.MobConfigData;
import com.example.encephalon.config.Tri;
import com.example.encephalon.scope.MobGroups;
import com.example.encephalon.scope.Scope;
import com.example.encephalon.scope.ScopeType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
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
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
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
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Mob Editor. Left: collapsible scope tree (All Mobs > Hostile / Neutral / Passive > mob), with a mob search.
 * Middle: behaviors for the selected scope, with a behavior search. Right: scrollable details and settings,
 * or an animated 3D mob preview while no behavior is selected.
 * Singleplayer only for now: it edits the integrated server's MobConfigData directly (networking is a TODO).
 */
public class MobEditorScreen extends Screen {
    private static final int TOP = 50;
    private static final int BOTTOM_RESERVE = 34;
    private static final int GREY = 0xAAAAAA; // vanilla ChatFormatting.GRAY

    /** Mobs without a spawn egg that are still always listed (vanilla has no egg for these). */
    private static final Set<String> ALWAYS_LISTED = Set.of("ender_dragon", "wither", "iron_golem", "snow_golem");

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

    // Shulkers only open when the server tells them to, so the lid is driven by hand. A shulker that sees its position
    // change also plays a 6-tick "slide in from the old spot" animation, which is pinned off.
    private static final Field SHULKER_PEEK = field(Shulker.class, "currentPeekAmount");
    private static final Field SHULKER_PEEK_O = field(Shulker.class, "currentPeekAmountO");
    private static final Field SHULKER_SLIDE = field(Shulker.class, "clientSideTeleportInterpolation");

    // Fish are only upright when the game thinks they are in water: out of water the renderer lays them on their side.
    // The preview mob is far above the world, so the flag is forced on just before drawing.
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

    private PickList scopeList;
    private PickList behaviorList;
    private EditBox mobSearch;
    private EditBox behaviorSearch;
    private Button stateButton;
    private final List<Placed> placed = new ArrayList<>();
    private final List<SettingRow> rows = new ArrayList<>();
    private List<FormattedCharSequence> descLines = List.of();
    /** Vanilla mobs by category (hostile / neutral / passive). */
    private final Map<String, List<EntityType<?>>> vanillaByCategory = new HashMap<>();
    /** Modded mobs by mod id (sorted by mod name), then by category. */
    private final Map<String, Map<String, List<EntityType<?>>>> moddedByMod = new LinkedHashMap<>();
    private final List<EntityType<?>> vanillaTypes = new ArrayList<>();
    private final List<EntityType<?>> moddedTypes = new ArrayList<>();
    private final List<EntityType<?>> allTypes = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>(Set.of(Scope.VANILLA.key()));

    // ------------------------------------------------------------------ 3D preview state
    // Animation libraries (GeckoLib and the like) drive a mob from its first render: they start from the bind pose and blend
    // into idle, then blend again into the walk once the walk state kicks in. A modded mob is therefore drawn off-screen
    // (empty scissor) for this long first, so those blends finish before anyone sees it.
    private static final int WARMUP_TICKS = 10;      // simulated ticks of animation before a modded mob may be shown
    private static final int FAST_STEPS = 6;         // warm-up ticks simulated (and rendered off-screen) per frame
    // Mods often build a mob out of other entities that only the server creates (body segments, a detached head, ...).
    // For modded mobs a hidden, AI-less twin is spawned on the integrated server at the same spot; whatever extra
    // entities it makes show up in the client level near the anchor and are drawn together with the preview mob.
    // Each twin lives in its own "slot", stacked vertically, so the parts of different mobs never mix and several mobs
    // can be warmed up at once.
    private static final int SLOTS = 6;
    private static final double SLOT_SPACING = 96.0;
    private static final String PREVIEW_TAG = "encephalon_preview";
    // Companions are spawned all at once at their starting spots and then drift into place (the Void Worm even staggers
    // them out of a portal), which looks like pieces popping in. They are only drawn once the group has stopped changing.
    private static final int SETTLE_STABLE_TICKS = 10;  // the number of companions must not change for this long
    private static final int SETTLE_MIN_TICKS = 20;      // and at least this long must have passed since the twin spawned
    private static final int SOLO_TICKS = 70;           // a twin that made no companions by now never will: drop it
    private static final int MAX_IN_FLIGHT = 4;          // mobs being warmed up at the same time
    private static final int MAX_CACHED = 12;            // warmed mobs kept around for instant picking

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
        long sim = System.nanoTime();
        long firstRender;
        long twinStartTick;
        int lastCount = -1;
        long countChangedTick;
        boolean settled;
        boolean tickFailed;
        int warmTicks;
        // How far the model really reaches, in blocks (recorded while it is drawn off-screen during warm-up).
        float mMinY = Float.MAX_VALUE;
        float mMaxY = -Float.MAX_VALUE;
        float mMaxR2;

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

        /** Records a vertex of the model: height above the feet, and distance from the mob's vertical axis. */
        void measure(float wx, float wy, float wz) {
            // The first warm-up ticks are the bind pose (limbs wherever the model file left them); only the settled ones count.
            if (warmTicks < WARMUP_TICKS - 4 || Float.isNaN(wx + wy + wz) || Math.abs(wy) > 300F || wx * wx + wz * wz > 90000F) {
                return;
            }
            mMinY = Math.min(mMinY, wy);
            mMaxY = Math.max(mMaxY, wy);
            mMaxR2 = Math.max(mMaxR2, wx * wx + wz * wz);
        }

        boolean hasMeasure() {
            return mMaxY > mMinY;
        }
    }

    private Preview active;
    /** Mobs warmed (or warming) off-screen, oldest first. */
    private final Map<EntityType<?>, Preview> warm = new LinkedHashMap<>();
    private final Set<EntityType<?>> wantedNow = new LinkedHashSet<>();
    private final boolean[] slotUsed = new boolean[SLOTS];
    private final Set<EntityType<?>> badTypes = new HashSet<>();
    private EntityType<?> hoverType;
    private long hoverStamp;
    private EntityType<?> rotationNext;
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

    private int viewBottom() {
        return height - BOTTOM_RESERVE;
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void init() {
        placed.clear();
        rows.clear();
        stateButton = null;
        scroll = 0;

        server = this.minecraft.getSingleplayerServer();
        editable = server != null && this.minecraft.player != null && this.minecraft.player.hasPermissions(2);
        if (server != null && data == null) {
            MobConfigData[] box = new MobConfigData[1];
            server.executeBlocking(() -> box[0] = MobConfigData.get(server));
            data = box[0];
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

        buildTypes();

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

    /** Mobs with a spawn egg are always listed; others only when enabled in the mod config (plus a small vanilla whitelist). */
    private static boolean isListed(EntityType<?> t) {
        if (EncephalonConfig.showMobsWithoutEgg() || SpawnEggItem.byId(t) != null) {
            return true;
        }
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(t);
        return key.getNamespace().equals("minecraft") && ALWAYS_LISTED.contains(key.getPath());
    }

    private static String modName(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getDisplayName())
                .orElse(modId);
    }

    private void buildTypes() {
        vanillaByCategory.clear();
        moddedByMod.clear();
        vanillaTypes.clear();
        moddedTypes.clear();
        allTypes.clear();
        for (String c : MobGroups.CATEGORIES) {
            vanillaByCategory.put(c, new ArrayList<>());
        }
        Map<String, Map<String, List<EntityType<?>>>> byMod = new HashMap<>();
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            String c = MobGroups.categoryOf(t);
            if (c == null || !isListed(t)) {
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
            vanillaTypes.addAll(list);
        }
        List<String> mods = new ArrayList<>(byMod.keySet());
        mods.sort(Comparator.comparing(MobEditorScreen::modName, String.CASE_INSENSITIVE_ORDER));
        for (String mod : mods) {
            Map<String, List<EntityType<?>>> cats = byMod.get(mod);
            for (String c : MobGroups.CATEGORIES) {
                List<EntityType<?>> list = cats.get(c);
                if (list != null) {
                    list.sort(byName);
                    moddedTypes.addAll(list);
                }
            }
            moddedByMod.put(mod, cats);
        }
        allTypes.addAll(vanillaTypes);
        allTypes.addAll(moddedTypes);
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
            g.drawString(f, fit(label.getString(), w - (tx - x) - 2 - badgeW), tx, ty, n > 0 ? 0xFFFFFF : 0xC8C8C8, true);
            if (n > 0) {
                g.drawString(f, badge, x + w - f.width(badge) - 2, ty, 0xFFAA00, true);
            }
        }

        @Override
        public void hovered() {
            if (s.type() == ScopeType.MOB_TYPE) {
                hoverPrewarm(BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(s.id())));
            }
        }

        @Override
        public void picked() {
            scope = s;
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
        behaviorList.setItems(items);
        if (behavior != null) {
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
                boolean on = ConfigResolver.toggleFor(data, ConfigResolver.chainFor(scope), b).on();
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
        stateButton = null;
        descLines = List.of();
        scroll = 0;
        contentHeight = 0;
        if (behavior == null) {
            return;
        }

        descLines = this.font.split(behavior.description(), rw);
        int y = TOP + 2 + descLines.size() * 10 + 8;
        stateButton = track(Button.builder(Component.empty(), b -> cycleState()).bounds(rx, y, rw, 20).build());
        stateButton.active = editable;
        refreshState();
        explainY = y + 24;
        y += 44;

        List<Scope> chain = ConfigResolver.chainFor(scope);
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
        if (mouseX >= rx && mouseY >= TOP && mouseY < viewBottom() && maxScroll() > 0) {
            scroll = Mth.clamp(scroll - (int) (scrollY * 12), 0, maxScroll());
            layoutDetail();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
        edit(() -> data.clearScope(sc));
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

    /**
     * Modded swimmers are often filed under MONSTER (the Skelewag), so category alone misses them and they get drawn as if
     * stranded on land (lying on their side). Their spawn placement says where they really live.
     */
    private static boolean placedInWater(EntityType<?> t) {
        try {
            return net.minecraft.world.entity.SpawnPlacements.getPlacementType(t) == net.minecraft.world.entity.SpawnPlacementTypes.IN_WATER;
        } catch (RuntimeException ex) {
            return false;
        }
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
        return new Vec3(0.0, 0.0, e instanceof EnderDragon ? -0.5 : e instanceof Dolphin ? -0.05 : 0.0);
    }

    private static boolean isVanilla(EntityType<?> t) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(t).getNamespace().equals("minecraft");
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
            server.executeBlocking(() -> {
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

    /** A slot for a new twin. With evict, a cached mob gives its slot up (one nobody is looking at first). */
    private int takeSlot(boolean evict) {
        int s = freeSlot();
        if (s >= 0 || !evict) {
            return s;
        }
        Preview victim = null;
        for (Preview p : warm.values()) {
            if (p.slot < 0) {
                continue;
            }
            if (!wantedNow.contains(p.type)) {
                victim = p;
                break;
            }
            if (victim == null) {
                victim = p;
            }
        }
        if (victim == null) {
            return -1;
        }
        dispose(victim);
        return freeSlot();
    }

    /** Frees the slot of a cached mob nobody needs any more. */
    private boolean evictUnwanted() {
        for (Preview p : warm.values()) {
            if (p.slot >= 0 && !wantedNow.contains(p.type)) {
                dispose(p);
                return true;
            }
        }
        return false;
    }

    /** Spawns the hidden twin of a modded mob so the entities its server logic creates exist for the preview. */
    private void startTwin(Preview p) {
        EntityType<?> type = p.type;
        var dim = this.minecraft.level.dimension();
        final double x = p.ax;
        final double y = p.ay;
        final double z = p.az;
        final AABB box = p.box();
        final int[] id = {-1};
        try {
            server.executeBlocking(() -> {
                PreviewZone.beginSpawn(); // anything this mob creates outside the preview column is vetoed
                try {
                ServerLevel sl = server.getLevel(dim);
                if (sl == null) {
                    return;
                }
                discardAll(sl, box);
                Entity made = type.create(sl);
                if (!(made instanceof Mob mob)) {
                    if (made != null) {
                        made.discard();
                    }
                    return;
                }
                try {
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
                        id[0] = mob.getId();
                        PreviewZone.pinTwin(mob, new Vec3(x, y, z)); // it must not drift: its parts are drawn relative to this spot
                    }
                } catch (RuntimeException ex) {
                    EncephalonMod.LOGGER.warn("Mob Editor preview: could not spawn the server twin of {}", type, ex);
                    mob.discard();
                    discardAll(sl, box);
                }
                } finally {
                    PreviewZone.endSpawn();
                }
            });
        } catch (RuntimeException ex) {
            EncephalonMod.LOGGER.warn("Mob Editor preview: server twin failed for {}", type, ex);
        }
        if (id[0] == -1) {
            NO_TWIN.add(type);
            slotUsed[p.slot] = false;
            p.slot = -1;
            p.settled = true;
            return;
        }
        p.twinId = id[0];
        p.twinStartTick = this.minecraft.level.getGameTime();
        p.lastCount = -1;
        p.countChangedTick = p.twinStartTick;
        p.settled = false;
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
            server.executeBlocking(() -> {
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
        warm.remove(p.type, p);
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
                server.executeBlocking(() -> {
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
        warm.clear();
        active = null;
        PreviewZone.close();
        super.removed();
    }

    /**
     * Entities around a preview mob that belong to it: parts, a head, tails. Also decides when the group has settled
     * (and learns which mob types have companions at all, so later previews know whether a twin is worth it).
     */
    private List<Entity> rawCompanions(Preview p) {
        if (p.slot < 0 || this.minecraft.level == null) {
            return List.of();
        }
        // Living entities only: those are the segments and heads. Other things the mob makes (the Void Worm's portal) are
        // effects of the twin standing still up there, not part of the mob.
        List<Entity> found = this.minecraft.level.getEntities((Entity) null, p.box(),
                en -> en instanceof LivingEntity && en.getId() != p.twinId && en != p.entity && en != this.minecraft.player);
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
        return found;
    }

    // ------------------------------------------------------------------ preview selection and caching

    private static boolean warmedUp(Preview p) {
        if (isVanilla(p.type)) {
            return true;
        }
        return p.warmTicks >= WARMUP_TICKS;
    }

    /** Finished warming: animation blends done and, for mobs with parts, the group has settled. Nothing more to do off-screen. */
    private boolean isDone(Preview p) {
        return warmedUp(p) && (p.slot < 0 || p.settled);
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

    /** The mob to show: the selected mob type, or a rotating pick from the selected category / all mobs. */
    private Preview current() {
        rotationNext = null;
        if (this.minecraft.level == null) {
            return null;
        }
        List<EntityType<?>> list;
        int start = 0;
        switch (scope.type()) {
            case MOB_TYPE -> {
                list = new ArrayList<>(1);
                list.add(BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(scope.id())));
            }
            case CATEGORY -> list = vanillaByCategory.getOrDefault(scope.id(), List.of());
            case ORIGIN -> list = scope.id().equals("vanilla") ? vanillaTypes : moddedTypes;
            case MOD -> {
                list = new ArrayList<>();
                for (List<EntityType<?>> types : moddedByMod.getOrDefault(scope.id(), Map.of()).values()) {
                    list.addAll(types);
                }
            }
            case MOD_CATEGORY -> {
                int slash = scope.id().indexOf('/');
                list = moddedByMod.getOrDefault(scope.id().substring(0, slash), Map.of())
                        .getOrDefault(scope.id().substring(slash + 1), List.of());
            }
            case ALL -> list = allTypes;
            default -> list = List.of();
        }
        if (list.isEmpty()) {
            return null;
        }
        if (scope.type() != ScopeType.MOB_TYPE) {
            start = (int) ((Util.getMillis() / 4000L) % list.size());
            if (list.size() > 1) {
                // Warm the next mob of the rotation for the whole turn of this one, so the swap is seamless.
                rotationNext = list.get((start + 1) % list.size());
            }
        }
        for (int i = 0; i < list.size(); i++) {
            EntityType<?> t = list.get((start + i) % list.size());
            if (active != null && active.type == t) {
                return active;
            }
            if (badTypes.contains(t)) {
                continue;
            }
            Preview p = warm.remove(t);
            if (p == null) {
                p = create(t, true);
            }
            if (p == null) {
                continue;
            }
            demote(active);
            active = p;
            return p;
        }
        return null;
    }

    /** A mob that is no longer shown goes back into the cache if it is fully warmed, so picking it again is instant. */
    private void demote(Preview old) {
        if (old == null) {
            return;
        }
        if (isVanilla(old.type) || !isDone(old) || badTypes.contains(old.type)) {
            stopTwin(old);
            return;
        }
        warm.remove(old.type);
        warm.put(old.type, old);
        trimCache();
    }

    private void trimCache() {
        while (warm.size() > MAX_CACHED) {
            Preview oldest = warm.values().iterator().next();
            dispose(oldest);
        }
    }

    /** Creates and sets up a preview mob (and its twin when it may need one), or returns null if it can't be made. */
    private Preview create(EntityType<?> t, boolean evict) {
        if (this.minecraft.level == null || badTypes.contains(t)) {
            return null;
        }
        int slot = -1;
        if (server != null && !isVanilla(t) && !SOLO.contains(t) && !NO_TWIN.contains(t)) {
            slot = takeSlot(evict);
            if (slot < 0 && !evict) {
                return null; // no room to warm it yet
            }
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
            Entity e = t.create(this.minecraft.level);
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
        } catch (Exception ex) {
            // fall through and blacklist
        }
        badTypes.add(t);
        return null;
    }

    // ------------------------------------------------------------------ background warm-up

    /** Called every frame a mob row is hovered: that mob goes to the front of the warm-up queue straight away. */
    void hoverPrewarm(EntityType<?> t) {
        hoverType = t;
        hoverStamp = Util.getMillis();
    }

    /** Mobs worth having warm, most important first: the hovered row, the next in a rotation, then everything in view. */
    private void computeWanted() {
        wantedNow.clear();
        if (hoverType != null && Util.getMillis() - hoverStamp < 250L) {
            wantedNow.add(hoverType);
        }
        if (rotationNext != null) {
            wantedNow.add(rotationNext);
        }
        if (scopeList != null) {
            scopeList.forEachVisible(4, it -> {
                if (it instanceof ScopeItem si && si.s.type() == ScopeType.MOB_TYPE) {
                    wantedNow.add(BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(si.s.id())));
                }
            });
        }
        wantedNow.removeIf(MobEditorScreen::isVanilla);
    }

    /** Starts at most one new warm-up per frame (a few run at once) and steps / draws the running ones off-screen. */
    private void pumpWarm(GuiGraphics g, float realPartial) {
        if (this.minecraft.level == null) {
            return;
        }
        int inFlight = 0;
        for (Preview p : warm.values()) {
            if (!isDone(p)) {
                inFlight++;
            }
        }
        if (inFlight < MAX_IN_FLIGHT) {
            for (EntityType<?> t : wantedNow) {
                if ((active != null && active.type == t) || warm.containsKey(t) || badTypes.contains(t)) {
                    continue;
                }
                Preview p = create(t, false);
                if (p == null && !badTypes.contains(t) && !SOLO.contains(t) && !NO_TWIN.contains(t) && evictUnwanted()) {
                    p = create(t, false);
                }
                if (p != null) {
                    warm.put(t, p);
                    trimCache();
                }
                break;
            }
        }
        for (Preview p : new ArrayList<>(warm.values())) {
            if (isDone(p)) {
                continue;
            }
            long now = System.nanoTime();
            if (p.firstRender == 0L) {
                p.firstRender = now;
            }
            List<Entity> raw = rawCompanions(p);
            if (!warmedUp(p)) {
                if (!fastForward(g, p, raw, realPartial)) {
                    badTypes.add(p.type);
                    dispose(p);
                }
                continue;
            }
            stepFrames(p, now);
            float partial = (now - p.sim) / (float) TICK_NANOS;
            if (!hiddenRender(g, p, true, partial, raw, realPartial)) {
                badTypes.add(p.type);
                dispose(p);
            }
        }
    }

    /** Draws with an empty clip area: the animation library runs, nothing is shown. Returns false if drawing failed. */
    private boolean hiddenRender(GuiGraphics g, Preview p, boolean withMain, float partial, List<Entity> extras, float realPartial) {
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
            measuring = withMain && !isVanilla(p.type);
            RenderSystem.runAsFancy(() -> drawModels(g, p, withMain, partial, extras, realPartial, pose, dispatcher));
            g.flush();
        } catch (Exception ex) {
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
                Vector3f o = pose.last().pose().transformPosition(new Vector3f());
                source = new ExtentProbe(source, p, o.x(), o.y(), o.z());
            }
            dispatcher.render(p.entity, 0.0, 0.0, 0.0, 0.0F, partial, pose, source, 15728880);
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
     * Instead of waiting for that in real time, a few ticks are simulated and rendered off-screen in one frame, each one
     * advancing tickCount, so the blend is over before the mob is ever shown. Rendered at partial 0 so the animation
     * clock never runs backwards when the real-time stepping takes over. Returns false if drawing failed.
     */
    private boolean fastForward(GuiGraphics g, Preview p, List<Entity> raw, float realPartial) {
        boolean ok = true;
        for (int i = 0; i < FAST_STEPS && ok && !warmedUp(p); i++) {
            stepPreview(p);
            ok = hiddenRender(g, p, true, 0F, raw, realPartial);
        }
        p.sim = System.nanoTime();
        return ok;
    }

    /**
     * Passes everything through to the real buffer and records where the vertices land. The off-screen warm-up pose is a plain
     * 1 pixel = 1 block flip around the mob's origin, so (origin - vertex) is the model's own position in blocks.
     */
    private static final class ExtentProbe implements MultiBufferSource {
        private final MultiBufferSource delegate;
        private final Preview p;
        private final float ox;
        private final float oy;
        private final float oz;

        ExtentProbe(MultiBufferSource delegate, Preview p, float ox, float oy, float oz) {
            this.delegate = delegate;
            this.p = p;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return new Tap(delegate.getBuffer(type));
        }

        private final class Tap implements VertexConsumer {
            private final VertexConsumer out;

            Tap(VertexConsumer out) {
                this.out = out;
            }

            @Override
            public VertexConsumer addVertex(float x, float y, float z) {
                p.measure(ox - x, oy - y, oz - z);
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
            if (steps++ < 4) {
                stepPreview(p);
            }
        }
    }

    /**
     * One 20 Hz step of the preview mob. The entity is ticked so its own animations run (dragon wings, bat, guardian, ...),
     * but the walk cycle is driven here at a fixed speed. If the walk state can't be pinned, only the dragon is
     * ticked and everything else is animated by hand.
     * The level normally does the per-tick bookkeeping (old position and tickCount) before entity.tick(); tickCount is the
     * "age" that rods, tentacles, wings and segments animate from, so it must advance here or those mobs vibrate.
     */
    private void stepPreview(Preview p) {
        if (this.minecraft.level == null || this.minecraft.player == null) {
            return;
        }
        LivingEntity e = p.entity;
        boolean dragon = e instanceof EnderDragon;
        e.tickCount++;
        p.warmTicks++;
        // A modded fish is not ticked at all: the game resets the water flag to dry at the start of every tick, before the
        // mod's own tick code runs, and code like "if (!isInWaterOrBubble()) onLandProgress++" then lays the model on its side.
        boolean skipTick = (e instanceof Guardian && !CAN_PIN_GUARDIAN) || (!isVanilla(e.getType()) && isFish(e));
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
                e.tick();
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
            } catch (Exception ex) {
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
        computeWanted();
        if (scope.type() == ScopeType.MOB_TYPE) {
            Preview p = current();
            if (p != null) {
                drawActive(g, p, realPartial);
            }
        } else {
            // Vanilla Mobs / a category / a mod: no mob, just the mod's icon (or the Minecraft logo).
            rotationNext = null;
            if (active != null) {
                demote(active);
                active = null;
            }
            drawScopeIcon(g);
        }
        pumpWarm(g, realPartial);
    }

    private record ModLogo(ResourceLocation texture, int w, int h) {
    }

    private static final Map<String, ModLogo> LOGOS = new HashMap<>();
    private static LogoRenderer mcLogo;

    /** The mod's own logo (the one the mod list shows), loaded once. Null if it has none. */
    private static ModLogo logoOf(String modId) {
        if (LOGOS.containsKey(modId)) {
            return LOGOS.get(modId);
        }
        ModLogo logo = null;
        try {
            var info = ModList.get().getModContainerById(modId).map(c -> c.getModInfo()).orElse(null);
            if (info != null && info.getLogoFile().isPresent()) {
                Path path = info.getOwningFile().getFile().findResource(info.getLogoFile().get());
                try (InputStream in = Files.newInputStream(path)) {
                    NativeImage img = NativeImage.read(in);
                    int w = img.getWidth();
                    int h = img.getHeight();
                    DynamicTexture tex = new DynamicTexture(img);
                    tex.setFilter(true, false);
                    ResourceLocation id = EncephalonMod.id("logo/" + modId.replaceAll("[^a-z0-9_.-]", "_"));
                    net.minecraft.client.Minecraft.getInstance().getTextureManager().register(id, tex);
                    logo = new ModLogo(id, w, h);
                }
            }
        } catch (Exception ex) {
            EncephalonMod.LOGGER.debug("Mob Editor: no logo for {}", modId, ex);
        }
        LOGOS.put(modId, logo);
        return logo;
    }

    /** Mod and mod-category scopes show that mod's logo; vanilla scopes the Minecraft logo; All / Modded Mobs show nothing. */
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
        ModLogo logo = logoOf(mod);
        if (logo == null) {
            g.drawCenteredString(this.font, labelOf(new Scope(ScopeType.MOD, mod)), (int) cx, (int) cy - 4, 0xC8C8C8);
            return;
        }
        float box = Math.min(Math.min(rw - 24, (bottom - top) - 24), 192);
        float s = Math.min(box / Math.max(logo.w(), logo.h()), 2F);
        int dw = Math.max(1, Math.round(logo.w() * s));
        int dh = Math.max(1, Math.round(logo.h() * s));
        g.blit(logo.texture(), (int) (cx - dw / 2F), (int) (cy - dh / 2F), dw, dh, 0F, 0F, logo.w(), logo.h(), logo.w(), logo.h());
    }

    private void drawActive(GuiGraphics g, Preview p, float realPartial) {
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
        // Companions are only drawn together once the group has settled; until then they are just warmed up hidden.
        List<Entity> extras = p.settled && !raw.isEmpty() ? (raw.size() > 64 ? raw.subList(0, 64) : raw) : List.of();
        boolean visible = revealable(p);

        int top = TOP + 6;
        int bottom = viewBottom() - 16;
        float bbH = Math.max(e.getBbHeight(), 0.4F);
        float bbW = Math.max(e.getBbWidth(), 0.4F);
        float extentH;
        float extentW;
        float fillH;
        float fillW;
        boolean dragon = e instanceof EnderDragon;
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
            fillH = 0.7F;
            fillW = 0.8F;
        }
        float centerY = dragon ? DRAGON_CENTER_Y
                : e instanceof Squid ? SQUID_CENTER_Y
                : e instanceof Ghast ? GHAST_CENTER_Y
                : bbH / 2F;
        // The hitbox is only a guess at the size of the model. A mob whose model reaches well past it (a kraken's tentacles) is
        // fitted by the extent that was measured while it was drawn off-screen; mobs that fit their hitbox keep the usual fit.
        // (Mobs with parts are fitted from the union of their parts' hitboxes below.)
        if (!dragon && !(e instanceof Squid) && !(e instanceof Ghast) && p.hasMeasure()) {
            float measuredH = p.mMaxY - p.mMinY;
            float measuredW = 2F * (float) Math.sqrt(p.mMaxR2);
            if (measuredH > extentH || measuredW > extentW) {
                extentH = Math.max(extentH, measuredH);
                extentW = Math.max(extentW, measuredW);
                centerY = (p.mMinY + p.mMaxY) / 2F;
            }
        }

        // When the mob has companions (body segments, a detached head) the whole group is fitted and centred, and turned
        // side-on so a long body is seen along its length.
        boolean group = !extras.isEmpty();
        float pivotX = 0F;
        float pivotZ = 0F;
        if (group) {
            double minX = -bbW / 2.0;
            double maxX = bbW / 2.0;
            double minZ = -bbW / 2.0;
            double maxZ = bbW / 2.0;
            double minY = 0.0;
            double maxY = bbH;
            for (Entity c : extras) {
                AABB b = c.getBoundingBox();
                minX = Math.min(minX, b.minX - p.ax);
                maxX = Math.max(maxX, b.maxX - p.ax);
                minZ = Math.min(minZ, b.minZ - p.az);
                maxZ = Math.max(maxZ, b.maxZ - p.az);
                minY = Math.min(minY, b.minY - p.ay);
                maxY = Math.max(maxY, b.maxY - p.ay);
            }
            pivotX = (float) ((minX + maxX) / 2.0);
            pivotZ = (float) ((minZ + maxZ) / 2.0);
            extentH = Math.max(extentH, (float) (maxY - minY));
            extentW = (float) Math.hypot(maxX - minX, maxZ - minZ) + bbW;
            centerY = (float) ((minY + maxY) / 2.0);
        }
        float scale = Math.min(55F, Math.min((bottom - top) * fillH / extentH, rw * fillW / extentW));
        float cx = rx + rw / 2F;
        float feetY = (top + bottom) / 2F + centerY * scale;

        // Entity faces the viewer; the sway is applied to the pose so it is perfectly smooth.
        faceViewer(e);
        forceWet(e); // the fish renderer lays fish on their side unless they are in water
        e.setInvisible(false);
        float sway = (float) Math.toRadians((dragon ? 12.0 : 40.0) * Math.sin(Util.getMillis() / 1500.0));
        float sideOn = group ? (float) (Math.PI / 2.0) : 0F;
        final float px = pivotX;
        final float pz = pivotZ;
        final List<Entity> drawExtras = extras;

        boolean failed = false;
        g.enableScissor(rx, TOP, visible ? rx + rw + 6 : rx, viewBottom()); // zero width while warming up: rendered, not shown
        PoseStack pose = g.pose();
        pose.pushPose();
        EntityRenderDispatcher dispatcher = this.minecraft.getEntityRenderDispatcher();
        try {
            pose.translate(cx, feetY, 50.0);
            pose.scale(scale, scale, -scale);
            pose.mulPose(new Quaternionf().rotateZ((float) Math.PI).rotateY(sway + sideOn + (dragon ? (float) Math.PI : 0F))); // the dragon model faces the other way
            pose.translate(-px, 0.0F, -pz); // turn around the middle of the group, not the head
            Lighting.setupForEntityInInventory();
            dispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() -> drawModels(g, p, true, partial, drawExtras, realPartial, pose, dispatcher));
            g.flush();
        } catch (Exception ex) {
            failed = true;
        } finally {
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
        g.drawCenteredString(this.font, p.type.getDescription(), (int) cx, viewBottom() - 12, 0xC8C8C8);
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
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
        if (behavior == null) {
            g.drawString(this.font, Component.translatable("encephalon.screen.select"), rx, 39, 0xA0A0A0, true);
        } else {
            g.drawString(this.font, behavior.name().copy().withStyle(ChatFormatting.YELLOW), rx, 39, 0xFFFFFF, true);
        }
        g.disableScissor();
        if (behavior == null) {
            renderPreview(g, partialTick);
            return;
        }
        // No preview while a behavior is open, but mobs in view keep warming up in the background.
        computeWanted();
        pumpWarm(g, partialTick);

        g.enableScissor(rx, TOP, rx + rw + 6, viewBottom());
        for (int i = 0; i < descLines.size(); i++) {
            g.drawString(this.font, descLines.get(i), rx, TOP + 2 + i * 10 - scroll, 0xC8C8C8, true);
        }

        ConfigResolver.Toggle t = ConfigResolver.toggleFor(data, ConfigResolver.chainFor(scope), behavior);
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

        // Scrollbar for the right panel: spans the whole panel box, same look as the lists.
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

    @Override
    public void onClose() {
        setFocused(null);
        this.minecraft.setScreen(null);
    }
}
