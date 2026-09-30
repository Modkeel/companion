package modkeel.companion.game;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** "What is sent": the exact report, as the server will get it. */
public final class ReportScreen extends Screen {
    private final Screen back;
    private final String payload;
    private HeaderAndFooterLayout layout;
    private Scroll scroll;

    public ReportScreen(Screen back, String payload) {
        super(Component.translatable("modkeel.share.what"));
        this.back = back;
        this.payload = payload;
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this, 33, 33);
        Compat.titleHeader(layout, title, font);
        int w = Math.min(width - 40, 380);
        Stack body = Stack.vertical(8);
        body.addChild(CrashScreen.text(Component.translatable("modkeel.share.note")
                .withStyle(ChatFormatting.GRAY), w));
        body.addChild(new MultiLineTextWidget(Component.literal(payload), font).setMaxWidth(w));
        scroll = layout.addToContents(new Scroll(minecraft, body, Compat.contentHeight(layout)));
        layout.addToFooter(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).width(150).build());
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
        Compat.setScreen(minecraft, back);
    }
}
