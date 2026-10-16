package com.example.encephalon.client;

import com.example.encephalon.config.EncephalonConfig;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Plain config screen (Mods > Encephalon > Config), so the hover text is just the description. */
public class EncephalonConfigScreen extends Screen {
    private final Screen parent;
    private boolean showNoEgg;

    public EncephalonConfigScreen(Screen parent) {
        super(Component.translatable("encephalon.configuration.title"));
        this.parent = parent;
    }

    private Component label() {
        return Component.translatable("encephalon.configuration.showMobsWithoutEgg")
                .append(": ")
                .append(Component.translatable(showNoEgg ? "options.on" : "options.off"));
    }

    @Override
    protected void init() {
        showNoEgg = EncephalonConfig.showMobsWithoutEgg();
        addRenderableWidget(Button.builder(label(), b -> {
            showNoEgg = !showNoEgg;
            EncephalonConfig.SHOW_MOBS_WITHOUT_EGG.set(showNoEgg);
            EncephalonConfig.SHOW_MOBS_WITHOUT_EGG.save();
            b.setMessage(label());
        }).bounds(width / 2 - 100, height / 2 - 10, 200, 20)
                .tooltip(Tooltip.create(Component.translatable("encephalon.configuration.showMobsWithoutEgg.tooltip"))).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, width / 2, 20, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
