package modkeel.companion.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A button in Modkeel's look ({@link Keel#button}); Minecraft 1.20.1 drawing hook. */
class KeelButton extends Button {
    private boolean primary;

    KeelButton(int width, Component message, OnPress onPress) {
        super(0, 0, width, 20, message, onPress, DEFAULT_NARRATION);
    }

    /** The one action a screen recommends: green ({@link Keel#button}). */
    KeelButton primary() {
        primary = true;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
        Keel.button(Canvas.paint(g), Minecraft.getInstance().font, getX(), getY(), getWidth(),
                    getHeight(), isHoveredOrFocused(), active, primary, getMessage());
    }
}
