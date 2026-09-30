package modkeel.companion.game;

import java.text.NumberFormat;
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
    /** A cause at least this big leads the toast instead of the top mod. */
    static final int HEAVY = 50;
    /** A cause at least this big is listed under its spike. */
    static final int SHOWN = 10;

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
        boolean graphics = false;
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        for (Spikes.Spike s : spikes) {
            memory |= s.gcPercent >= HEAVY;
            graphics |= s.gpuPercent >= HEAVY;
            Stack row = Stack.vertical(1);
            row.defaultCellSetting().alignHorizontallyCenter();
            row.addChild(CrashScreen.text(Component.translatable("modkeel.spikes.row",
                    clock.format(new Date(s.at)), seconds(s.millis),
                    Component.translatable("modkeel.spikes." + s.where.name().toLowerCase(Locale.ROOT)),
                    shares(s)), w));
            Component causes = causes(s);
            if (causes != null) {
                row.addChild(CrashScreen.text(causes.copy().withStyle(ChatFormatting.GRAY), w));
            }
            Component crowds = crowds(s, minecraft.options.languageCode);
            if (crowds != null) {
                row.addChild(CrashScreen.text(crowds.copy().withStyle(ChatFormatting.GRAY), w));
            }
            body.addChild(row);
        }
        if (memory) {
            body.addChild(CrashScreen.text(Component.translatable("modkeel.spikes.gc_hint")
                    .withStyle(ChatFormatting.YELLOW), w));
        }
        if (graphics) {
            body.addChild(CrashScreen.text(Component.translatable("modkeel.spikes.gpu_hint")
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

    /** A mod's name, "Minecraft: entities", or "Minecraft itself". */
    static Component name(Spikes.Share s) {
        if (s.name != null) {
            return Component.literal(s.name);
        }
        return s.section == null ? Component.translatable("modkeel.spikes.vanilla")
                : Component.translatable("modkeel.spikes.vanilla_section",
                        Component.translatable("modkeel.spikes.section." + s.section));
    }

    /** "Create 70%, Minecraft: entities 20%". */
    static Component shares(Spikes.Spike s) {
        MutableComponent out = Component.empty();
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

    /**
     * What the game waited on, beyond the processor: "Graphics card wait 60% · Java memory
     * cleanup 20%". Null when it was all game code.
     */
    static Component causes(Spikes.Spike s) {
        MutableComponent out = Component.empty();
        String[][] items = {{"modkeel.spikes.gc", "" + s.gcPercent},
                            {"modkeel.spikes.res.gpu", "" + s.gpuPercent},
                            {"modkeel.spikes.res.disk", "" + s.diskPercent},
                            {"modkeel.spikes.res.wait", "" + s.waitPercent}};
        for (String[] item : items) {
            if (Integer.parseInt(item[1]) >= SHOWN) {
                if (!out.getSiblings().isEmpty()) {
                    out.append(" · ");
                }
                out.append(Component.translatable(item[0])).append(" " + item[1] + "%");
            }
        }
        if (s.otherPrograms) {
            if (!out.getSiblings().isEmpty()) {
                out.append(" · ");
            }
            out.append(Component.translatable("modkeel.spikes.other_programs"));
        }
        return out.getSiblings().isEmpty() ? null : out;
    }

    /**
     * "Most common in the world: Item ×1,200 · Zombie ×400", when Minecraft's own entities took
     * part of the spike. Null otherwise.
     */
    static Component crowds(Spikes.Spike s, String languageCode) {
        boolean entities = s.shares.stream().anyMatch(
                sh -> sh.name == null && "entities".equals(sh.section) && sh.percent >= SHOWN);
        List<Spikes.Crowd> crowds = s.crowds;
        if (!entities || crowds.isEmpty()) {
            return null;
        }
        NumberFormat n = NumberFormat.getIntegerInstance(
                Locale.forLanguageTag(languageCode.replace('_', '-')));
        MutableComponent list = Component.empty();
        for (Spikes.Crowd c : crowds) {
            if (!list.getSiblings().isEmpty()) {
                list.append(" · ");
            }
            list.append(Component.translatable(c.type)).append(" ×" + n.format(c.count));
        }
        return Component.translatable("modkeel.spikes.crowds", list);
    }

    /** The toast's second line: the biggest cause, or the biggest share. */
    static Component mostly(Spikes.Spike s) {
        if (s.gcPercent >= HEAVY) {
            return Component.translatable("modkeel.spikes.toast_cause",
                    Component.translatable("modkeel.spikes.gc"), s.gcPercent);
        }
        if (s.gpuPercent >= HEAVY) {
            return Component.translatable("modkeel.spikes.toast_cause",
                    Component.translatable("modkeel.spikes.res.gpu"), s.gpuPercent);
        }
        if (s.diskPercent >= HEAVY) {
            return Component.translatable("modkeel.spikes.toast_cause",
                    Component.translatable("modkeel.spikes.res.disk"), s.diskPercent);
        }
        if (s.waitPercent >= HEAVY) {
            return Component.translatable("modkeel.spikes.toast_cause",
                    Component.translatable("modkeel.spikes.res.wait"), s.waitPercent);
        }
        if (s.otherPrograms) {
            return Component.translatable("modkeel.spikes.other_programs");
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
