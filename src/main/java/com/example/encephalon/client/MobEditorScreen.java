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

import org.joml.Quaternionf;

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
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
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

    // 3D preview
    private EntityType<?> previewType;
    private LivingEntity previewEntity;
    private final Set<EntityType<?>> badTypes = new HashSet<>();
    private long simTime = System.nanoTime();
    private boolean tickFailed;
    // Where the preview mob is parked, fixed when it is created so it never counts as having moved.
    private double anchorX;
    private double anchorY;
    private double anchorZ;
    // Mods often build a mob out of other entities that only the server creates (body segments, a detached head, ...).
    // For modded mobs a hidden, AI-less twin is spawned on the integrated server at the same spot; whatever extra
    // entities it makes show up in the client level near the anchor and are drawn together with the preview mob.
    private static final String PREVIEW_TAG = "encephalon_preview";
    private final Set<EntityType<?>> noServerTwin = new HashSet<>();
    private boolean twinActive;
    private int twinId = -1;

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

    // ------------------------------------------------------------------ 3D preview

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
        return c == MobCategory.WATER_CREATURE || c == MobCategory.WATER_AMBIENT || c == MobCategory.UNDERGROUND_WATER_CREATURE;
    }

    private static void forceWet(LivingEntity e) {
        if (WET != null && isFish(e)) {
            try {
                WET.setBoolean(e, true);
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

    // ------------------------------------------------------------------ server twin (parts / companions)

    private AABB twinBox() {
        return new AABB(anchorX - 64, anchorY - 48, anchorZ - 64, anchorX + 64, anchorY + 48, anchorZ + 64);
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
        try {
            server.executeBlocking(() -> {
                ServerLevel sl = server.getLevel(dim);
                if (sl != null) {
                    discardAll(sl, new AABB(px - 160, low, pz - 160, px + 160, low + 400, pz + 160));
                }
            });
        } catch (RuntimeException ex) {
            EncephalonMod.LOGGER.warn("Mob Editor preview: could not sweep leftover preview entities", ex);
        }
    }

    /** Spawns the hidden twin of a modded mob so the entities its server logic creates exist for the preview. */
    private void startTwin(EntityType<?> type) {
        stopTwin();
        if (server == null || this.minecraft.level == null
                || BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace().equals("minecraft") || noServerTwin.contains(type)) {
            return;
        }
        var dim = this.minecraft.level.dimension();
        final double x = anchorX;
        final double y = anchorY;
        final double z = anchorZ;
        final AABB box = twinBox();
        final int[] id = {-1};
        try {
            server.executeBlocking(() -> {
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
                    }
                } catch (RuntimeException ex) {
                    EncephalonMod.LOGGER.warn("Mob Editor preview: could not spawn the server twin of {}", type, ex);
                    mob.discard();
                    discardAll(sl, box);
                }
            });
        } catch (RuntimeException ex) {
            EncephalonMod.LOGGER.warn("Mob Editor preview: server twin failed for {}", type, ex);
        }
        if (id[0] == -1) {
            noServerTwin.add(type);
            return;
        }
        twinId = id[0];
        twinActive = true;
    }

    /** Removes the twin and everything it spawned. */
    private void stopTwin() {
        if (!twinActive) {
            return;
        }
        twinActive = false;
        twinId = -1;
        if (server == null || this.minecraft.level == null) {
            return;
        }
        var dim = this.minecraft.level.dimension();
        final AABB box = twinBox();
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

    /** Entities around the preview mob that belong to it: parts, a head, tails. The twin's own client copy is skipped. */
    private List<Entity> companions() {
        if (!twinActive || this.minecraft.level == null) {
            return List.of();
        }
        List<Entity> found = this.minecraft.level.getEntities((Entity) null, twinBox(),
                en -> en.getId() != twinId && en != previewEntity && en != this.minecraft.player);
        return found.size() > 64 ? found.subList(0, 64) : found;
    }

    @Override
    public void removed() {
        stopTwin();
        super.removed();
    }

    /** The mob to show: the selected mob type, or a rotating pick from the selected category / all mobs. */
    private LivingEntity previewEntity() {
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
        }
        for (int i = 0; i < list.size(); i++) {
            EntityType<?> t = list.get((start + i) % list.size());
            if (t == previewType && previewEntity != null) {
                return previewEntity;
            }
            if (badTypes.contains(t)) {
                continue;
            }
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
                    if (this.minecraft.player != null) {
                        anchorX = this.minecraft.player.getX();
                        anchorY = this.minecraft.level.getMaxBuildHeight() + 64;
                        anchorZ = this.minecraft.player.getZ();
                        living.setPos(anchorX, anchorY, anchorZ);
                        living.setOldPosAndRot();
                    }
                    living.setDeltaMovement(previewMotion(living));
                    tickFailed = false;
                    previewType = t;
                    previewEntity = living;
                    simTime = System.nanoTime();
                    startTwin(t);
                    return living;
                }
            } catch (Exception ex) {
                // fall through and blacklist
            }
            badTypes.add(t);
        }
        return null;
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

    /**
     * One 20 Hz step of the preview mob. The entity is ticked so its own animations run (dragon wings, bat, guardian, ...),
     * but the walk cycle is driven here at a fixed speed. If the walk state can't be pinned, only the dragon is
     * ticked and everything else is animated by hand.
     * The level normally does the per-tick bookkeeping (old position and tickCount) before entity.tick(); tickCount is the
     * "age" that rods, tentacles, wings and segments animate from, so it must advance here or those mobs vibrate.
     */
    private void stepPreview(LivingEntity e) {
        if (this.minecraft.level == null || this.minecraft.player == null) {
            return;
        }
        boolean dragon = e instanceof EnderDragon;
        e.tickCount++;
        boolean skipTick = e instanceof Guardian && !CAN_PIN_GUARDIAN;
        if (!tickFailed && !skipTick && (CAN_PIN_WALK || dragon)) {
            try {
                double hx = anchorX;
                double hy = anchorY;
                double hz = anchorZ;
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
                tickFailed = true;
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

    private void renderPreview(GuiGraphics g, float realPartial) {
        LivingEntity e = previewEntity();
        if (e == null) {
            return;
        }
        // Fixed-timestep simulation driven by the real clock, so age = tickCount + partial never goes backwards.
        long now = System.nanoTime();
        if (now - simTime > 1_000_000_000L) {
            simTime = now; // was not rendering for a while (e.g. a behavior was open); don't fast-forward
        }
        int steps = 0;
        while (now - simTime >= TICK_NANOS) {
            simTime += TICK_NANOS;
            if (steps++ < 4) {
                stepPreview(e);
            }
        }
        float partial = (now - simTime) / (float) TICK_NANOS;

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

        // Entities that belong to the mob but are separate (body segments, a detached head). When there are any, the whole
        // group is fitted and centred, and turned side-on so a long body is seen along its length.
        List<Entity> extras = companions();
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
                minX = Math.min(minX, b.minX - anchorX);
                maxX = Math.max(maxX, b.maxX - anchorX);
                minZ = Math.min(minZ, b.minZ - anchorZ);
                maxZ = Math.max(maxZ, b.maxZ - anchorZ);
                minY = Math.min(minY, b.minY - anchorY);
                maxY = Math.max(maxY, b.maxY - anchorY);
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

        g.enableScissor(rx, TOP, rx + rw + 6, viewBottom());
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
            RenderSystem.runAsFancy(() -> {
                dispatcher.render(e, 0.0, 0.0, 0.0, 0.0F, partial, pose, g.bufferSource(), 15728880);
                for (Entity c : drawExtras) {
                    // Placed relative to the preview mob, which sits at the anchor. These are ticked by the real client clock.
                    // A companion that fails to draw is skipped; it should not take the whole preview down with it.
                    try {
                        double dx = Mth.lerp(realPartial, c.xOld, c.getX()) - anchorX;
                        double dy = Mth.lerp(realPartial, c.yOld, c.getY()) - anchorY;
                        double dz = Mth.lerp(realPartial, c.zOld, c.getZ()) - anchorZ;
                        dispatcher.render(c, dx, dy, dz, Mth.lerp(realPartial, c.yRotO, c.getYRot()), realPartial, pose,
                                g.bufferSource(), 15728880);
                    } catch (RuntimeException ex) {
                        // skip this one
                    }
                }
            });
            g.flush();
        } catch (Exception ex) {
            badTypes.add(previewType);
            previewEntity = null;
            previewType = null;
        } finally {
            dispatcher.setRenderShadow(true);
            pose.popPose();
            Lighting.setupFor3DItems();
        }
        g.disableScissor();

        if (previewType != null) {
            g.drawCenteredString(this.font, previewType.getDescription(), (int) cx, viewBottom() - 12, 0xC8C8C8);
        }
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
