package modkeel.companion.game;

import java.util.List;

import modkeel.companion.core.Diagnosis;
import modkeel.companion.core.Guardian;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** "More options" after a crash: going back to the mods that worked, another mod, the report. */
final class CrashOptionsScreen extends Screen {
    private static final int GO = 80;

    private final CrashScreen crash;
    private HeaderAndFooterLayout layout;
    private Scroll scroll;

    CrashOptionsScreen(CrashScreen crash) {
        super(Component.translatable("modkeel.more.title"));
        this.crash = crash;
    }

    @Override
    protected void init() {
        Guardian g = crash.g;
        Diagnosis d = crash.d;
        Compat.background(this, this::addRenderableOnly);
        layout = new HeaderAndFooterLayout(this, 33, 36);
        Compat.titleHeader(layout, title, font);
        int w = Keel.bodyWidth(width, 380);
        Stack body = Stack.vertical(6);
        body.defaultCellSetting().alignHorizontallyCenter();

        // Each option says what it does in words, and its button says the action
        Card revert = body.addChild(new Card(w));
        revert.add(new Heading(Component.translatable("modkeel.more.revert"), revert.inner()));
        boolean canRevert = g.canRevert();
        revert.add(Text.in(canRevert
                ? Component.translatable("modkeel.more.revert_text", g.changedSinceGood().size())
                : Component.translatable("modkeel.revert.none"), revert.inner(), Keel.SOFT));
        Button back = revert.add(new KeelButton(GO, Component.translatable("modkeel.more.revert_go"),
                b -> crash.confirmRevert(this)));
        back.active = canRevert;

        List<Diagnosis.Suspect> others = d.suspects.size() > 1
                ? d.suspects.subList(1, d.suspects.size()) : List.of();
        if (others.stream().anyMatch(CrashScreen::canDisable)) {
            Card other = body.addChild(new Card(w));
            int in = other.inner();
            other.add(new Heading(Component.translatable("modkeel.more.other"), in));
            other.add(Text.in(Component.translatable("modkeel.more.other_text"), in, Keel.SOFT));
            for (Diagnosis.Suspect s : others) {
                if (!CrashScreen.canDisable(s)) {
                    continue;
                }
                Stack row = other.add(Stack.horizontal(8));
                row.defaultCellSetting().alignVerticallyMiddle();
                row.addChild(Text.in(Component.literal(s.name), in - GO - 8, Keel.TEXT));
                row.addChild(new KeelButton(GO, Component.translatable("modkeel.more.disable_go"),
                        b -> crash.confirmDisable(this, s)));
            }
        }

        Card report = body.addChild(new Card(w));
        report.add(new Heading(Component.translatable("modkeel.more.report"), report.inner()));
        report.add(Text.in(Component.translatable("modkeel.more.report_text"), report.inner(), Keel.SOFT));
        Button open = report.add(new KeelButton(GO, Component.translatable("modkeel.more.open"),
                b -> Compat.openPath(g.crashFile)));
        if (!d.error.isEmpty()) {
            open.setTooltip(Tooltip.create(Component.literal(CrashScreen.clip(d.error, 300))));
        }

        scroll = Compat.contents(layout, new Scroll(minecraft, body, Compat.contentHeight(layout)));
        layout.addToFooter(new KeelButton(150, CommonComponents.GUI_BACK, b -> onClose()));

        addRenderableOnly(new Backdrop(width, height, 33, 36, scroll));
        layout.visitWidgets(this::addRenderableWidget);
        addRenderableOnly(new Edges(width, height, scroll, body));
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        scroll.arrangeElements();
        scroll.setMaxHeight(Compat.contentHeight(layout));
        Keel.arrange(layout, scroll);
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, crash);
    }
}
