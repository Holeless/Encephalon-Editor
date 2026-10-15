package com.example.mobsmith.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.example.mobsmith.behavior.Behavior;
import com.example.mobsmith.behavior.Behaviors;
import com.example.mobsmith.behavior.Setting;
import com.example.mobsmith.config.ConfigResolver;
import com.example.mobsmith.config.MobConfigData;
import com.example.mobsmith.config.Tri;
import com.example.mobsmith.scope.Scope;
import com.example.mobsmith.scope.ScopeType;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;

/**
 * Mob Editor: scope list (left), behavior list for that scope (middle), details and settings (right).
 * Singleplayer only for now: it edits the integrated server's MobConfigData directly (networking is a TODO).
 */
public class MobEditorScreen extends Screen {
    private static final int TOP = 48;

    private MinecraftServer server;
    private MobConfigData data;
    private boolean editable;

    private PickList scopeList;
    private PickList behaviorList;
    private EditBox search;
    private Button stateButton;
    private final List<AbstractWidget> detail = new ArrayList<>();
    private final List<SettingRow> rows = new ArrayList<>();
    private List<FormattedCharSequence> descLines = List.of();

    private Scope scope = Scope.ALL;
    private Behavior behavior;
    private String filter = "";
    private int mx;
    private int rx;
    private int rw;
    private int explainY;

    private record SettingRow(Setting setting, int y) {
    }

    public MobEditorScreen() {
        super(Component.translatable("mobsmith.screen.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void init() {
        detail.clear();
        rows.clear();
        stateButton = null;

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
        addRenderableWidget(Button.builder(Component.translatable("mobsmith.screen.reset_scope"), b -> resetScope())
                .bounds(width / 2 - 155, height - 28, 150, 20).build()).active = editable;

        int lw = Mth.clamp(width * 30 / 100, 130, 200);
        int mw = Mth.clamp(width * 30 / 100, 130, 200);
        int lx = 8;
        mx = lx + lw + 6;
        rx = mx + mw + 6;
        rw = Math.max(80, width - 8 - rx);
        int listH = height - TOP - 34;

        search = new EditBox(this.font, lx, 28, lw, 16, Component.empty());
        search.setHint(Component.translatable("mobsmith.screen.search"));
        search.setValue(filter);
        search.setResponder(s -> {
            filter = s.toLowerCase().trim();
            rebuildScopes();
        });
        addRenderableWidget(search);

        scopeList = addRenderableWidget(new PickList(this.minecraft, lx, TOP, lw, listH, 18));
        behaviorList = addRenderableWidget(new PickList(this.minecraft, mx, TOP, mw, listH, 22));
        rebuildScopes();
        rebuildBehaviors();
        rebuildDetail();
    }

    private void edit(Runnable r) {
        server.executeBlocking(r);
    }

    private <T extends AbstractWidget> T track(T widget) {
        detail.add(widget);
        return addRenderableWidget(widget);
    }

    // ------------------------------------------------------------------ labels

    private static Component labelOf(Scope s) {
        return switch (s.type()) {
            case WORLD -> Component.translatable("mobsmith.scope.world");
            case ALL -> Component.translatable("mobsmith.scope.all");
            case CATEGORY -> Component.translatable("mobsmith.category." + s.id());
            case MOB_TYPE -> BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(s.id())).getDescription();
            default -> Component.literal(s.id());
        };
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private String fit(String text, int width) {
        return this.font.width(text) <= width ? text : this.font.plainSubstrByWidth(text, width - this.font.width("...")) + "...";
    }

    // ------------------------------------------------------------------ scope list

    private void rebuildScopes() {
        List<PickList.Item> items = new ArrayList<>();
        addScope(items, Scope.WORLD, 0);
        addScope(items, Scope.ALL, 0);
        for (MobCategory c : MobCategory.values()) {
            if (c != MobCategory.MISC) {
                addScope(items, new Scope(ScopeType.CATEGORY, c.getName()), 1);
            }
        }
        List<EntityType<?>> types = new ArrayList<>();
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            if (t.getCategory() != MobCategory.MISC) {
                types.add(t);
            }
        }
        types.sort(Comparator.comparing(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()));
        for (EntityType<?> t : types) {
            addScope(items, new Scope(ScopeType.MOB_TYPE, BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()), 2);
        }
        scopeList.setItems(items);
        scopeList.select(scope);
    }

    private void addScope(List<PickList.Item> items, Scope s, int indent) {
        Component label = labelOf(s);
        if (filter.isEmpty() || label.getString().toLowerCase().contains(filter)) {
            items.add(new ScopeItem(s, label, indent));
        }
    }

    private final class ScopeItem implements PickList.Item {
        private final Scope s;
        private final Component label;
        private final int indent;

        ScopeItem(Scope s, Component label, int indent) {
            this.s = s;
            this.label = label;
            this.indent = indent * 6;
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
            g.drawString(f, fit(label.getString(), w - indent - 6 - badgeW), x + 3 + indent, y + (h - 8) / 2 + 1,
                    n > 0 ? 0xFFFFFF : 0xC8C8C8, true);
            if (n > 0) {
                g.drawString(f, badge, x + w - f.width(badge) - 2, y + (h - 8) / 2 + 1, 0xFFAA00, true);
            }
        }

        @Override
        public void picked() {
            scope = s;
            rebuildBehaviors();
            rebuildDetail();
        }
    }

    // ------------------------------------------------------------------ behavior list

    private void rebuildBehaviors() {
        List<PickList.Item> items = new ArrayList<>();
        for (Behavior b : Behaviors.all()) {
            if (b.scopes().contains(scope.type())) {
                items.add(new BehaviorItem(b));
            }
        }
        if (behavior != null && !behavior.scopes().contains(scope.type())) {
            behavior = null;
        }
        behaviorList.setItems(items);
        if (behavior != null) {
            behaviorList.select(behavior);
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
            g.renderFakeItem(new ItemStack(b.icon()), x + 2, y + (h - 16) / 2);
            Tri own = data.toggle(scope, b);
            Component state;
            int color;
            if (own != Tri.INHERIT) {
                state = Component.translatable("mobsmith.state." + own.name().toLowerCase());
                color = own == Tri.ON ? 0x55FF55 : 0xFF5555;
            } else {
                boolean on = ConfigResolver.toggleFor(data, ConfigResolver.chainFor(scope), b).on();
                state = Component.translatable("mobsmith.state.inherit_" + (on ? "on" : "off"));
                color = 0xA0A0A0;
            }
            int stateW = f.width(state);
            int ty = y + (h - 8) / 2 + 1;
            g.drawString(f, fit(b.name().getString(), w - 24 - stateW - 8), x + 24, ty, 0xFFFFFF, true);
            g.drawString(f, state, x + w - stateW - 2, ty, color, true);
        }

        @Override
        public void picked() {
            behavior = b;
            rebuildDetail();
        }
    }

    // ------------------------------------------------------------------ detail panel

    private void rebuildDetail() {
        setFocused(null); // commits any box being edited, against the behavior it was created for
        for (AbstractWidget w : detail) {
            removeWidget(w);
        }
        detail.clear();
        rows.clear();
        stateButton = null;
        descLines = List.of();
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
                    ? Component.translatable("mobsmith.screen.default")
                    : Component.literal(fmt(inherited.value()));
            NumBox box = track(new NumBox(this.font, rx, y + 20, rw - 24, s, hint, data.value(scope, behavior, s.key())));
            box.setEditable(editable);
            Button reset = track(Button.builder(Component.literal("R"), b -> box.reset())
                    .bounds(rx + rw - 20, y + 20, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("mobsmith.screen.reset_tip"))).build());
            reset.active = editable;
            rows.add(new SettingRow(s, y));
            y += 48;
        }
    }

    private void refreshState() {
        Tri own = data.toggle(scope, behavior);
        stateButton.setMessage(Component.translatable("mobsmith.screen.state",
                Component.translatable("mobsmith.state." + own.name().toLowerCase())));
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

    /** Number field: greyed hint shows the inherited value; Enter or clicking away applies it, clamped to the max/min. */
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

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, width / 2, 5, 0xFFFFFF);
        if (data == null) {
            g.drawCenteredString(this.font, Component.translatable("mobsmith.screen.nodata"), width / 2, height / 2, 0xFF5555);
            return;
        }
        if (!editable) {
            g.drawCenteredString(this.font, Component.translatable("mobsmith.screen.readonly"), width / 2, 16, 0xFFAA00);
        }

        int n = data.count(scope);
        Component scopeTitle = labelOf(scope).copy().withStyle(ChatFormatting.RED);
        if (n > 0) {
            scopeTitle = scopeTitle.copy().append(Component.literal(" ")
                    .append(Component.translatable("mobsmith.screen.overrides", n)).withStyle(ChatFormatting.GOLD));
        }
        g.drawString(this.font, scopeTitle, mx, 34, 0xFFFFFF, true);

        if (behavior == null) {
            g.drawString(this.font, Component.translatable("mobsmith.screen.select"), rx, 34, 0xA0A0A0, true);
            return;
        }
        g.drawString(this.font, behavior.name().copy().withStyle(ChatFormatting.RED), rx, 34, 0xFFFFFF, true);
        for (int i = 0; i < descLines.size(); i++) {
            g.drawString(this.font, descLines.get(i), rx, TOP + 2 + i * 10, 0xC8C8C8, true);
        }

        List<Scope> chain = ConfigResolver.chainFor(scope);
        ConfigResolver.Toggle t = ConfigResolver.toggleFor(data, chain, behavior);
        Component why;
        int whyColor;
        if (t.from() == null) {
            why = Component.translatable("mobsmith.screen.from_none");
            whyColor = 0xA0A0A0;
        } else if (t.from().equals(scope)) {
            why = Component.translatable("mobsmith.screen.from_here");
            whyColor = 0xFFFFFF;
        } else {
            why = Component.translatable("mobsmith.screen.from_other", labelOf(t.from()));
            whyColor = 0xFFFF55;
        }
        g.drawString(this.font, fit(why.getString(), rw), rx, explainY, whyColor, true);

        for (SettingRow row : rows) {
            Setting s = row.setting();
            boolean set = data.value(scope, behavior, s.key()) != null;
            g.drawString(this.font, Component.translatable("setting.mobsmith." + behavior.id().getPath() + "." + s.key()),
                    rx, row.y(), set ? 0xFFFFFF : 0xA0A0A0, true);
            g.drawString(this.font, fit(Component.translatable("mobsmith.screen.info",
                            fmt(s.def()), s.unit(), fmt(s.min()), fmt(s.max())).getString(), rw),
                    rx, row.y() + 10, 0x808080, true);
        }
    }

    @Override
    public void onClose() {
        setFocused(null);
        this.minecraft.setScreen(null);
    }
}
