package modkeel.companion.game;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractContainerWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * A scrollable screen body for 1.21.1, which has no ScrollableLayout yet. Works like the later
 * vanilla one: the content moves up as it scrolls, so its widgets keep their real positions and
 * take clicks as usual, and a scissor hides what is outside.
 */
final class Scroll implements Layout {
    private static final ResourceLocation SCROLLER = ResourceLocation.withDefaultNamespace("widget/scroller");
    private static final int BAR = 6;
    private static final int SPACING = 4;
    private static final int RATE = 10;

    private final Layout content;
    private final Box box = new Box();
    private int maxHeight;

    Scroll(Minecraft minecraft, Layout content, int maxHeight) {
        this.content = Keel.inset(content);
        this.maxHeight = maxHeight;
    }

    void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
        box.setHeight(Math.min(content.getHeight(), maxHeight));
        box.setScroll(box.scroll);
    }

    @Override
    public void arrangeElements() {
        content.arrangeElements();
        box.setWidth(content.getWidth() + 2 * (SPACING + BAR));
        box.setHeight(Math.min(content.getHeight(), maxHeight));
        box.children.clear();
        content.visitWidgets(box.children::add);
        box.setScroll(box.scroll);
    }

    @Override
    public void visitChildren(Consumer<LayoutElement> visitor) {
        visitor.accept(box);
    }

    @Override
    public void setX(int x) {
        box.setX(x);
    }

    @Override
    public void setY(int y) {
        box.setY(y);
    }

    @Override
    public int getX() {
        return box.getX();
    }

    @Override
    public int getY() {
        return box.getY();
    }

    @Override
    public int getWidth() {
        return box.getWidth();
    }

    @Override
    public int getHeight() {
        return box.getHeight();
    }

    private final class Box extends AbstractContainerWidget {
        final List<AbstractWidget> children = new ArrayList<>();
        double scroll;
        private boolean dragging;

        Box() {
            super(0, 0, 0, 0, CommonComponents.EMPTY);
        }

        int maxScroll() {
            return Math.max(0, content.getHeight() - height);
        }

        void setScroll(double amount) {
            scroll = Mth.clamp(amount, 0, maxScroll());
            content.setY(getY() - (int) scroll);
        }

        private int barX() {
            return getX() + width - BAR;
        }

        private int barHeight() {
            return Mth.clamp(height * height / Math.max(1, content.getHeight()), 32, height);
        }

        @Override
        public void setX(int x) {
            super.setX(x);
            content.setX(x + SPACING + BAR);
        }

        @Override
        public void setY(int y) {
            super.setY(y);
            content.setY(y - (int) scroll);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return children;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            boolean inside = isMouseOver(mouseX, mouseY);
            g.enableScissor(getX(), getY(), getX() + width, getY() + height);
            for (AbstractWidget child : children) {
                child.render(g, inside ? mouseX : -1, inside ? mouseY : -1, delta);
            }
            g.disableScissor();
            if (maxScroll() > 0) {
                int barY = getY() + (int) (scroll * (height - barHeight()) / maxScroll());
                g.blitSprite(SCROLLER, barX(), barY, BAR, barHeight());
            }
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            if (!isMouseOver(x, y)) {
                return false;
            }
            if (button == 0 && maxScroll() > 0 && x >= barX()) {
                dragging = true;
                return true;
            }
            return super.mouseClicked(x, y, button);
        }

        @Override
        public boolean mouseReleased(double x, double y, int button) {
            if (button == 0) {
                dragging = false;
            }
            return super.mouseReleased(x, y, button);
        }

        @Override
        public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
            if (dragging) {
                setScroll(scroll + dy * Math.max(1, maxScroll() / (double) Math.max(1, height - barHeight())));
                return true;
            }
            return super.mouseDragged(x, y, button, dx, dy);
        }

        @Override
        public boolean mouseScrolled(double x, double y, double dx, double dy) {
            if (!isMouseOver(x, y) || maxScroll() == 0) {
                return false;
            }
            setScroll(scroll - dy * RATE);
            return true;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
}
