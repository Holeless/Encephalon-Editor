package com.example.mobsmith.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

/** A plain scrolling list of items that draw themselves; clicking one selects it and calls picked(). */
public class PickList extends ObjectSelectionList<PickList.Row> {
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

    @Override
    public int getRowWidth() {
        return this.width - 24;
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
            if (getSelected() == this) {
                g.fill(left, top - 2, left + width, top + height + 2, 0x40FF5555);
            } else if (hovering) {
                g.fill(left, top - 2, left + width, top + height + 2, 0x20FFFFFF);
            }
            item.draw(g, Minecraft.getInstance().font, left, top, width, height);
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
