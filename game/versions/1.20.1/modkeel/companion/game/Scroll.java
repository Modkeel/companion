package modkeel.companion.game;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.util.Mth;

/**
 * A scrollable screen body for 1.20.1, which has neither ScrollableLayout nor a container
 * widget. Works like the later vanilla one: the content moves up as it scrolls, so its widgets
 * keep their real positions and take clicks as usual, and a scissor hides what is outside.
 */
final class Scroll implements Layout {
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

    /** A widget that hands events to its children, like the later AbstractContainerWidget. */
    private final class Box extends AbstractWidget implements ContainerEventHandler {
        final List<AbstractWidget> children = new ArrayList<>();
        double scroll;
        private boolean draggingBar;
        private boolean dragging;
        private GuiEventListener focused;

        Box() {
            super(0, 0, 0, 0, CommonComponents.EMPTY);
        }

        public void setHeight(int h) {
            height = h;
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
        public boolean isDragging() {
            return dragging;
        }

        @Override
        public void setDragging(boolean dragging) {
            this.dragging = dragging;
        }

        @Override
        public GuiEventListener getFocused() {
            return focused;
        }

        @Override
        public void setFocused(GuiEventListener listener) {
            if (focused != null) {
                focused.setFocused(false);
            }
            if (listener != null) {
                listener.setFocused(true);
            }
            focused = listener;
        }

        @Override
        public void setFocused(boolean focus) {
            ContainerEventHandler.super.setFocused(focus);
        }

        @Override
        public boolean isFocused() {
            return ContainerEventHandler.super.isFocused();
        }

        @Override
        public ComponentPath getCurrentFocusPath() {
            return ContainerEventHandler.super.getCurrentFocusPath();
        }

        @Override
        public ComponentPath nextFocusPath(FocusNavigationEvent event) {
            return ContainerEventHandler.super.nextFocusPath(event);
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            return ContainerEventHandler.super.keyPressed(key, scan, mods);
        }

        @Override
        public boolean keyReleased(int key, int scan, int mods) {
            return ContainerEventHandler.super.keyReleased(key, scan, mods);
        }

        @Override
        public boolean charTyped(char c, int mods) {
            return ContainerEventHandler.super.charTyped(c, mods);
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
                // a black track, like the later vanilla scroller, marks where the view ends;
                // the thumb has the same two greys as 1.20.1's own scroll bars
                g.fill(barX(), getY(), barX() + BAR, getY() + height, 0xFF000000);
                int barY = getY() + (int) (scroll * (height - barHeight()) / maxScroll());
                g.fill(barX(), barY, barX() + BAR, barY + barHeight(), 0xFF808080);
                g.fill(barX(), barY, barX() + BAR - 1, barY + barHeight() - 1, 0xFFC0C0C0);
            }
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            if (!isMouseOver(x, y)) {
                return false;
            }
            if (button == 0 && maxScroll() > 0 && x >= barX()) {
                draggingBar = true;
                return true;
            }
            return ContainerEventHandler.super.mouseClicked(x, y, button);
        }

        @Override
        public boolean mouseReleased(double x, double y, int button) {
            if (button == 0) {
                draggingBar = false;
            }
            return ContainerEventHandler.super.mouseReleased(x, y, button);
        }

        @Override
        public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
            if (draggingBar) {
                setScroll(scroll + dy * Math.max(1, maxScroll() / (double) Math.max(1, height - barHeight())));
                return true;
            }
            return ContainerEventHandler.super.mouseDragged(x, y, button, dx, dy);
        }

        @Override
        public boolean mouseScrolled(double x, double y, double delta) {
            if (!isMouseOver(x, y) || maxScroll() == 0) {
                return false;
            }
            setScroll(scroll - delta * RATE);
            return true;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
}
