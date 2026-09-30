package modkeel.companion.game;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import modkeel.companion.core.Spikes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** The lag spikes of this session and whose code was running during each, over the open world. */
public final class SpikeScreen extends Screen {
    /** A spike that heavy on garbage collection is shown as memory cleanup, not as a mod. */
    static final int GC_HEAVY = 50;

    private final Screen back;
    private HeaderAndFooterLayout layout;
    private Scroll scroll;

    public SpikeScreen(Screen back) {
        super(Component.translatable("modkeel.spikes.title"));
        this.back = back;
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this, 33, 33);
        Compat.titleHeader(layout, title, font);
        int w = Math.min(width - 40, 380);
        Stack body = Stack.vertical(6);
        body.defaultCellSetting().alignHorizontallyCenter();

        List<Spikes.Spike> spikes = Common.spikes.recent();
        body.addChild(CrashScreen.text(Component.translatable("modkeel.spikes.intro",
                seconds(Common.spikes.reportMs)).withStyle(ChatFormatting.GRAY), w));
        if (spikes.isEmpty()) {
            body.addChild(CrashScreen.text(Component.translatable("modkeel.spikes.none"), w));
        }
        boolean memory = false;
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        for (Spikes.Spike s : spikes) {
            memory |= s.gcPercent >= GC_HEAVY;
            body.addChild(CrashScreen.text(Component.translatable("modkeel.spikes.row",
                    clock.format(new Date(s.at)), seconds(s.millis),
                    Component.translatable("modkeel.spikes." + s.where.name().toLowerCase(Locale.ROOT)),
                    shares(s)), w));
        }
        if (memory) {
            body.addChild(CrashScreen.text(Component.translatable("modkeel.spikes.gc_hint")
                    .withStyle(ChatFormatting.YELLOW), w));
        }
        scroll = layout.addToContents(new Scroll(minecraft, body, Compat.contentHeight(layout)));

        Stack footer = layout.addToFooter(Stack.horizontal(8));
        footer.addChild(Button.builder(alertsLabel(), b -> {
            Client.setSpikeAlerts(!Client.spikeAlerts());
            b.setMessage(alertsLabel());
        }).width(150).build());
        footer.addChild(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .width(150).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private static Component alertsLabel() {
        return Component.translatable(Client.spikeAlerts() ? "modkeel.spikes.alerts_on"
                                                           : "modkeel.spikes.alerts_off");
    }

    static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }

    static Component name(Spikes.Share s) {
        return s.name == null ? Component.translatable("modkeel.spikes.vanilla")
                              : Component.literal(s.name);
    }

    /** "Create 70%, Sodium 20%", or memory cleanup when that took most of it. */
    static Component shares(Spikes.Spike s) {
        MutableComponent out = Component.empty();
        if (s.gcPercent >= GC_HEAVY) {
            return out.append(Component.translatable("modkeel.spikes.gc"))
                    .append(" " + s.gcPercent + "%");
        }
        for (Spikes.Share share : s.shares) {
            if (!out.getSiblings().isEmpty()) {
                out.append(", ");
            }
            out.append(name(share).copy().withStyle(share.name == null ? ChatFormatting.GRAY
                                                                        : ChatFormatting.YELLOW))
                    .append(" " + share.percent + "%");
        }
        return out;
    }

    /** The toast's second line: the biggest share. */
    static Component mostly(Spikes.Spike s) {
        if (s.gcPercent >= GC_HEAVY) {
            return Component.translatable("modkeel.spikes.toast_mostly",
                    Component.translatable("modkeel.spikes.gc"), s.gcPercent);
        }
        Spikes.Share top = s.top();
        return top == null ? Component.translatable("modkeel.spikes.toast_details")
                           : Component.translatable("modkeel.spikes.toast_mostly", name(top), top.percent);
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
