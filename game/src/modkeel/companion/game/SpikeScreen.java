package modkeel.companion.game;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

import modkeel.companion.core.Spikes;
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
        Compat.background(this, this::addRenderableOnly);
        layout = new HeaderAndFooterLayout(this, 33, 33);
        Compat.titleHeader(layout, title, font);
        int w = Math.min(width - 40, 380);
        Stack body = Stack.vertical(6);
        body.defaultCellSetting().alignHorizontallyCenter();

        List<Spikes.Spike> spikes = Common.spikes.recent();
        body.addChild(Text.loose(Component.translatable("modkeel.spikes.intro",
                seconds(Common.spikes.reportMs)), w, Keel.GRAY));
        if (spikes.isEmpty()) {
            body.addChild(Text.loose(Component.translatable("modkeel.spikes.none"), w, Keel.SOFT));
        }
        boolean memory = false;
        boolean graphics = false;
        for (Spikes.Spike s : spikes) {
            memory |= s.gcPercent >= HEAVY;
            graphics |= s.gpuPercent >= HEAVY;
            body.addChild(new SpikeCard(s, w, minecraft.options.languageCode));
        }
        if (memory) {
            body.addChild(Text.loose(Component.translatable("modkeel.spikes.gc_hint"), w, Keel.YELLOW));
        }
        if (graphics) {
            body.addChild(Text.loose(Component.translatable("modkeel.spikes.gpu_hint"), w, Keel.YELLOW));
        }
        scroll = layout.addToContents(new Scroll(minecraft, body, Compat.contentHeight(layout)));

        Stack footer = layout.addToFooter(Stack.horizontal(8));
        footer.addChild(new KeelButton(150, alertsLabel(), b -> {
            Client.setSpikeAlerts(!Client.spikeAlerts());
            b.setMessage(alertsLabel());
        }));
        footer.addChild(new KeelButton(150, Component.translatable("gui.done"), b -> onClose()));

        addRenderableOnly(new Backdrop(width, height, 33, 33, scroll));
        layout.visitWidgets(this::addRenderableWidget);
        addRenderableOnly(new Edges(width, height, scroll, body));
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

    /** For a bar's legend: a mod's name, the part of Minecraft ("mobs and entities"), or "Minecraft". */
    static Component shortName(Spikes.Share s) {
        if (s.name != null) {
            return Component.literal(s.name);
        }
        return s.section == null ? Component.literal("Minecraft")
                : Component.translatable("modkeel.spikes.section." + s.section);
    }

    /** A cause big enough to act on: the card's cause line turns yellow. */
    static boolean heavy(Spikes.Spike s) {
        return s.gcPercent >= HEAVY || s.gpuPercent >= HEAVY || s.diskPercent >= HEAVY
                || s.waitPercent >= HEAVY || s.otherPrograms;
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
