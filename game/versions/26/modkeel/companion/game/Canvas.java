package modkeel.companion.game;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** A widget that draws itself through {@link Paint}; Minecraft 26.x drawing calls. */
abstract class Canvas extends AbstractWidget {
    Canvas(int width, int height) {
        super(0, 0, width, height, Component.empty());
        // not clickable: no click sound, no focus
        active = false;
    }

    void resize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    protected abstract void paint(Paint p, int mouseX, int mouseY);

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        paint(paint(g), mouseX, mouseY);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, getMessage());
    }

    static Paint paint(GuiGraphicsExtractor g) {
        return new Paint() {
            @Override
            public void fill(int x1, int y1, int x2, int y2, int argb) {
                g.fill(x1, y1, x2, y2, argb);
            }

            @Override
            public void text(Font font, FormattedCharSequence text, int x, int y, int argb,
                             boolean shadow) {
                g.text(font, text, x, y, argb, shadow);
            }
        };
    }
}
