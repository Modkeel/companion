package modkeel.companion.game;

import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** A Keel screen: title, a scrolling column of cards, one row of buttons below. */
abstract class Page extends Screen {
    static final int WIDTH = 380;

    final Screen back;
    private HeaderAndFooterLayout layout;
    private Scroll scroll;

    Page(Component title, Screen back) {
        super(title);
        this.back = back;
    }

    /** The cards, {@code w} wide. */
    protected abstract void body(Stack body, int w);

    /** The buttons below; Back by default. */
    protected void footer(Stack footer) {
        footer.addChild(new KeelButton(150, CommonComponents.GUI_BACK, b -> onClose()));
    }

    @Override
    protected void init() {
        Compat.background(this, this::addRenderableOnly);
        layout = new HeaderAndFooterLayout(this, 33, 36);
        Compat.titleHeader(layout, title, font);
        int w = Keel.bodyWidth(width, WIDTH);
        Stack body = Stack.vertical(6);
        body.defaultCellSetting().alignHorizontallyCenter();
        body(body, w);
        scroll = Compat.contents(layout, new Scroll(minecraft, body, Compat.contentHeight(layout)));
        footer(layout.addToFooter(Stack.horizontal(8)));

        addRenderableOnly(new Backdrop(width, height, 33, 36, scroll));
        layout.visitWidgets(this::addRenderableWidget);
        addRenderableOnly(new Edges(width, height, scroll, body));
        repositionElements();
    }

    /** Opens {@code next}; Back there comes here. */
    void open(Screen next) {
        Compat.setScreen(minecraft, next);
    }

    @Override
    protected void repositionElements() {
        scroll.arrangeElements();
        scroll.setMaxHeight(Compat.contentHeight(layout));
        Keel.arrange(layout, scroll);
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, back);
    }
}
