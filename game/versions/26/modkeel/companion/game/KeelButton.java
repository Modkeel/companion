package modkeel.companion.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A button in Modkeel's look ({@link Keel#button}); Minecraft 26.x drawing hook. */
class KeelButton extends Button {
    KeelButton(int width, Component message, OnPress onPress) {
        super(0, 0, width, 20, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Keel.button(Canvas.paint(g), Minecraft.getInstance().font, getX(), getY(), getWidth(),
                    getHeight(), isHoveredOrFocused(), active, getMessage());
    }
}
