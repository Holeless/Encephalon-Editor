package com.example.encephalon.client;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Dolphin;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

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
    private final Map<String, List<EntityType<?>>> typesByCategory = new HashMap<>();
    private final List<EntityType<?>> allTypes = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>(Set.of(Scope.ALL.key()));

    // 3D preview
    private EntityType<?> previewType;
    private LivingEntity previewEntity;
    private final Set<EntityType<?>> badTypes = new HashSet<>();
    private long simTime = System.nanoTime();
    private boolean tickFailed;

    private Scope scope = Scope.ALL;
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

    private void buildTypes() {
        typesByCategory.clear();
        allTypes.clear();
        for (String c : MobGroups.CATEGORIES) {
            typesByCategory.put(c, new ArrayList<>());
        }
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            String c = MobGroups.categoryOf(t);
            if (c != null && isListed(t)) {
                typesByCategory.get(c).add(t);
            }
        }
        for (String c : MobGroups.CATEGORIES) {
            List<EntityType<?>> list = typesByCategory.get(c);
            list.sort(Comparator.comparing(t -> t.getDescription().getString()));
            allTypes.addAll(list);
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
            case CATEGORY -> Component.translatable("encephalon.category." + s.id());
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

    private void rebuildScopes() {
        boolean filtering = !mobFilter.isEmpty();
        List<PickList.Item> items = new ArrayList<>();
        if (matches(Scope.WORLD)) {
            items.add(new ScopeItem(Scope.WORLD, 0, false, false));
        }
        boolean allOpen = filtering || expanded.contains(Scope.ALL.key());
        if (!filtering || matches(Scope.ALL)) {
            items.add(new ScopeItem(Scope.ALL, 0, true, allOpen));
        }
        if (allOpen) {
            for (String cat : MobGroups.CATEGORIES) {
                Scope cs = new Scope(ScopeType.CATEGORY, cat);
                boolean catMatch = matches(cs);
                List<EntityType<?>> shown = new ArrayList<>();
                for (EntityType<?> t : typesByCategory.get(cat)) {
                    if (!filtering || catMatch || matches(mobScope(t))) {
                        shown.add(t);
                    }
                }
                if (filtering && shown.isEmpty()) {
                    continue;
                }
                boolean open = filtering || expanded.contains(cs.key());
                items.add(new ScopeItem(cs, 1, true, open));
                if (open) {
                    for (EntityType<?> t : shown) {
                        items.add(new ScopeItem(mobScope(t), 2, false, false));
                    }
                }
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
            case CATEGORY -> list = typesByCategory.getOrDefault(scope.id(), List.of());
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
                    tickFailed = false;
                    previewType = t;
                    previewEntity = living;
                    simTime = System.nanoTime();
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
                double hx = this.minecraft.player.getX();
                double hy = this.minecraft.level.getMaxBuildHeight() + 64;
                double hz = this.minecraft.player.getZ();
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
                e.setDeltaMovement(0.0, 0.0, dragon ? -0.5 : e instanceof Dolphin ? -0.05 : 0.0);
                // Slimes only squish on ground transitions, so make them hop in place.
                boolean grounded = e instanceof Slime ? e.tickCount % 24 < 16 : !isFlyer(e);
                e.setOnGround(grounded);
                float tail = 0F;
                boolean pinGuardian = e instanceof Guardian && CAN_PIN_GUARDIAN;
                if (pinGuardian) {
                    tail = GUARDIAN_TAIL.getFloat(e);
                }
                // Slimes set oSquish = squish every tick, which makes the squish step at 20 Hz; keep the previous value to lerp.
                float squish = e instanceof Slime sl ? sl.squish : 0F;
                e.tick();
                e.setPos(hx, hy, hz);
                if (e instanceof Slime sl) {
                    sl.oSquish = squish;
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
        e.walkAnimation.update(WALK_SPEED, 0.4F);
    }

    private void renderPreview(GuiGraphics g) {
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
        } else {
            // Models are often longer/wider than their hitbox (camel), so fit against a padded extent.
            extentH = Math.max(bbH, bbW * 1.2F);
            extentW = bbW * 2.2F;
            fillH = 0.7F;
            fillW = 0.8F;
        }
        float scale = Math.min(55F, Math.min((bottom - top) * fillH / extentH, rw * fillW / extentW));
        float cx = rx + rw / 2F;
        float centerY = dragon ? DRAGON_CENTER_Y : bbH / 2F;
        float feetY = (top + bottom) / 2F + centerY * scale;

        // Entity faces the viewer; the sway is applied to the pose so it is perfectly smooth.
        faceViewer(e);
        float sway = (float) Math.toRadians((dragon ? 12.0 : 40.0) * Math.sin(Util.getMillis() / 1500.0));

        g.enableScissor(rx, TOP, rx + rw + 6, viewBottom());
        PoseStack pose = g.pose();
        pose.pushPose();
        EntityRenderDispatcher dispatcher = this.minecraft.getEntityRenderDispatcher();
        try {
            pose.translate(cx, feetY, 50.0);
            pose.scale(scale, scale, -scale);
            pose.mulPose(new Quaternionf().rotateZ((float) Math.PI).rotateY(sway + (dragon ? (float) Math.PI : 0F))); // the dragon model faces the other way
            Lighting.setupForEntityInInventory();
            dispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() -> dispatcher.render(e, 0.0, 0.0, 0.0, 0.0F, partial, pose, g.bufferSource(), 15728880));
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
            renderPreview(g);
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
