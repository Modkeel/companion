package modkeel.companion.game;

import java.util.function.Consumer;

import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;

/**
 * A Keel card holding other elements: a heading, text, rows with buttons. Its background is a
 * widget visited before the content, so it draws first and scrolls with it.
 */
final class Card implements Layout {
    static final int PAD = 6;

    private final Stack inner = Stack.vertical(4);
    private final Bg bg;
    private final int width;
    private int x;
    private int y;

    Card(int width) {
        this.width = width;
        this.bg = new Bg(width);
    }

    /** The width left for content. */
    int inner() {
        return width - 2 * PAD;
    }

    <T extends LayoutElement> T add(T element) {
        return inner.addChild(element);
    }

    @Override
    public void arrangeElements() {
        inner.arrangeElements();
        bg.resize(width, inner.getHeight() + 2 * PAD);
        setX(x);
        setY(y);
    }

    @Override
    public void visitChildren(Consumer<LayoutElement> visitor) {
        visitor.accept(bg);
        visitor.accept(inner);
    }

    /** Minecraft 1.21.2+ asks layouts for this; a card's children are fixed. */
    public void removeChildren() {
    }

    @Override
    public void setX(int x) {
        this.x = x;
        bg.setX(x);
        inner.setX(x + PAD);
    }

    @Override
    public void setY(int y) {
        this.y = y;
        bg.setY(y);
        inner.setY(y + PAD);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return bg.getHeight();
    }

    private static final class Bg extends Canvas {
        Bg(int width) {
            super(width, 0);
        }

        @Override
        protected void paint(Paint p, int mouseX, int mouseY) {
            Keel.card(p, getX(), getY(), getWidth(), getHeight());
        }
    }
}
