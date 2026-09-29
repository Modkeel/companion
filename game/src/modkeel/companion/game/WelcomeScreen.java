package modkeel.companion.game;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
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
        layout = new HeaderAndFooterLayout(this, 33, 36);
        Compat.titleHeader(layout, title, font);
        int w = Math.min(width - 40, 360);
        Stack body = Stack.vertical(8);
        body.defaultCellSetting().alignHorizontallyCenter();
        body.addChild(CrashScreen.text(Component.translatable("modkeel.welcome.intro")
                .withStyle(ChatFormatting.YELLOW), w));
        for (String k : new String[] {"backup", "crash", "good"}) {
            body.addChild(CrashScreen.text(Component.translatable("modkeel.welcome." + k), w));
        }
        body.addChild(CrashScreen.text(Component.translatable("modkeel.welcome.privacy")
                .withStyle(ChatFormatting.GRAY), w));
        body.addChild(CrashScreen.text(Component.translatable("modkeel.welcome.later")
                .withStyle(ChatFormatting.GRAY), w));
        scroll = layout.addToContents(new Scroll(minecraft, body, Compat.contentHeight(layout)));

        Stack footer = layout.addToFooter(Stack.horizontal(8));
        footer.addChild(Button.builder(Component.translatable("modkeel.health.app"),
                b -> Compat.openLink(this, HealthScreen.APP_URL)).width(150).build());
        footer.addChild(Button.builder(Component.translatable("modkeel.welcome.ok"),
                b -> onClose()).width(150).build());

        layout.visitWidgets(this::addRenderableWidget);
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
