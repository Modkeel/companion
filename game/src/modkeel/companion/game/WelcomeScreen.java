package modkeel.companion.game;

import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shown once, on the first start with Modkeel: what it does, what it never does, and the app. */
public final class WelcomeScreen extends Screen {
    private final Screen next;
    private HeaderAndFooterLayout layout;
    private Scroll scroll;

    public WelcomeScreen(Screen next) {
        super(Component.translatable("modkeel.welcome.title"));
        this.next = next;
    }

    @Override
    protected void init() {
        Compat.background(this, this::addRenderableOnly);
        layout = new HeaderAndFooterLayout(this, 33, 36);
        Compat.titleHeader(layout, title, font);
        int w = Math.min(width - 40, 360);
        Stack body = Stack.vertical(6);
        body.defaultCellSetting().alignHorizontallyCenter();
        Card c = body.addChild(new Card(w));
        c.add(new Heading(Component.translatable("modkeel.welcome.intro"), c.inner(), Keel.YELLOW));
        for (String k : new String[] {"backup", "crash", "good"}) {
            c.add(Text.in(Component.translatable("modkeel.welcome." + k), c.inner(), Keel.SOFT));
        }
        body.addChild(Text.loose(Component.translatable("modkeel.welcome.privacy"), w, Keel.GRAY));
        body.addChild(Text.loose(Component.translatable("modkeel.welcome.later"), w, Keel.GRAY));
        scroll = Compat.contents(layout, new Scroll(minecraft, body, Compat.contentHeight(layout)));

        Stack footer = layout.addToFooter(Stack.horizontal(8));
        footer.addChild(new KeelButton(150, Component.translatable("modkeel.health.app"),
                b -> Compat.openLink(this, HealthScreen.APP_URL)));
        footer.addChild(new KeelButton(150, Component.translatable("modkeel.welcome.ok"),
                b -> onClose()));

        addRenderableOnly(new Backdrop(width, height, 33, 36, scroll));
        layout.visitWidgets(this::addRenderableWidget);
        addRenderableOnly(new Edges(width, height, scroll, body));
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        scroll.arrangeElements();
        scroll.setMaxHeight(Compat.contentHeight(layout));
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, next);
    }
}
