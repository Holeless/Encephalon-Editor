package com.example.mobsmith.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** A plain scrolling list of items that draw themselves; clicking one selects it and calls picked(). */
public class PickList extends ObjectSelectionList<PickList.Row> {
    /** Vanilla grey selection outline (what vanilla uses for an unfocused selected entry). */
    private static final int SELECT_BORDER = 0xFF808080;
    private static final int SELECT_FILL = 0xFF2B1111;
    private static final int SCROLL_W = 6;

    public interface Item {
        Object key();

        void draw(GuiGraphics g, Font font, int x, int y, int w, int h);

        void picked();
    }

    public PickList(Minecraft mc, int x, int y, int w, int h, int itemHeight) {
        super(mc, w, h, y, itemHeight);
        setX(x);
    }

    public void setItems(List<Item> items) {
        double scroll = getScrollAmount();
        clearEntries();
        for (Item item : items) {
            addEntry(new Row(item));
        }
        setScrollAmount(scroll);
        setSelected(null);
    }

    public void select(Object key) {
        for (Row row : children()) {
            if (key.equals(row.item.key())) {
                setSelected(row);
                return;
            }
        }
    }

    /** Leaves a 6px gutter on the right for the scrollbar plus a 1px gap. */
    @Override
    public int getRowWidth() {
        return this.width - SCROLL_W - 8;
    }

    @Override
    protected int getScrollbarPosition() {
        return getX() + this.width - SCROLL_W;
    }

    /** Same outline box vanilla draws, but always the vanilla grey and with our dark fill. */
    @Override
    protected void renderSelection(GuiGraphics g, int top, int width, int height, int outerColor, int innerColor) {
        super.renderSelection(g, top, width, height, SELECT_BORDER, SELECT_FILL);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(g, mouseX, mouseY, partialTick);
        int max = getMaxScroll();
        if (max > 0) {
            // Cover the vanilla bar and draw one that spans the whole box, separators included.
            int top = getY() - 2;
            int bottom = getBottom() + 2;
            int view = bottom - top;
            int content = Math.max(1, children().size() * this.itemHeight);
            int thumbH = Mth.clamp(view * view / content, 32, view - 8);
            int thumbY = top + (int) (getScrollAmount() * (view - thumbH) / max);
            drawScrollbar(g, getScrollbarPosition(), top, bottom, thumbY, thumbH);
        }
    }

    public static void drawScrollbar(GuiGraphics g, int x, int top, int bottom, int thumbY, int thumbH) {
        g.fill(x, top, x + SCROLL_W, bottom, 0xFF000000);
        g.fill(x, thumbY, x + SCROLL_W, thumbY + thumbH, 0xFF808080);
        g.fill(x, thumbY, x + SCROLL_W - 1, thumbY + thumbH - 1, 0xFFC0C0C0);
    }

    public class Row extends ObjectSelectionList.Entry<Row> {
        final Item item;

        Row(Item item) {
            this.item = item;
        }

        @Override
        public Component getNarration() {
            return Component.literal(String.valueOf(item.key()));
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
            // The selection box starts 2px left of "left" and is "width" wide, so keep everything inside that.
            if (hovering && getSelected() != this) {
                g.fill(left - 2, top - 2, left - 2 + width, top + height + 2, 0x20FFFFFF);
            }
            item.draw(g, Minecraft.getInstance().font, left, top, width - 2, height);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                setSelected(this);
                item.picked();
                return true;
            }
            return false;
        }
    }
}
