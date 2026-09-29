package modkeel.companion.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import modkeel.companion.core.Diagnosis;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Msg;
import modkeel.companion.core.Outcomes;
import modkeel.companion.core.Rules;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Shown once before the title screen when the last session crashed. */
public final class CrashScreen extends Screen {
    private final Screen next;
    private final Guardian g;
    private final Diagnosis d;
    private HeaderAndFooterLayout layout;
    private Scroll scroll;

    public CrashScreen(Screen next, Guardian g) {
        super(Component.translatable("modkeel.crash.title"));
        this.next = next;
        this.g = g;
        this.d = g.crash;
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this, 33, 60);
        Compat.titleHeader(layout, title, font);
        int w = Math.min(width - 40, 380);
        Stack body = Stack.vertical(8);
        body.defaultCellSetting().alignHorizontallyCenter();

        body.addChild(text(Component.translatable("modkeel.kind." + d.kind.name().toLowerCase(Locale.ROOT)), w));
        if (!d.error.isEmpty()) {
            body.addChild(text(Component.literal(clip(d.error, 200)).withStyle(ChatFormatting.GRAY), w));
        }
        Diagnosis.Suspect s = d.top();
        if (s != null) {
            body.addChild(text(Component.translatable("modkeel.crash.suspect", s.name,
                    Component.translatable("modkeel.confidence." + d.confidence.name().toLowerCase(Locale.ROOT))), w));
            MutableComponent why = Component.empty();
            for (String reason : s.reasons) {
                if (!why.getSiblings().isEmpty()) {
                    why.append("; ");
                }
                why.append(msg(reason));
            }
            body.addChild(text(why.withStyle(ChatFormatting.GRAY), w));
            if (d.suspects.size() > 1) {
                List<String> others = new ArrayList<>();
                for (Diagnosis.Suspect o : d.suspects.subList(1, d.suspects.size())) {
                    others.add(o.name);
                }
                body.addChild(text(Component.translatable("modkeel.crash.others", String.join(", ", others)), w));
            }
        } else {
            body.addChild(text(Component.translatable("modkeel.crash.no_suspect"), w));
        }
        if (stuck(g)) {
            body.addChild(text(Component.translatable("modkeel.cta").withStyle(ChatFormatting.AQUA), w));
        }
        body.addChild(text(Component.translatable("modkeel.crash.later").withStyle(ChatFormatting.GRAY), w));

        scroll = layout.addToContents(new Scroll(minecraft, body, Compat.contentHeight(layout)));

        Stack footer = layout.addToFooter(Stack.vertical(4));
        Stack row1 = footer.addChild(Stack.horizontal(8));
        Stack row2 = footer.addChild(Stack.horizontal(8));

        Button disable = row1.addChild(Button.builder(s == null
                        ? Component.translatable("modkeel.crash.disable_none")
                        : fit("modkeel.crash.disable", s.name, 150 - 12),
                b -> confirmDisable(s)).width(150).build());
        disable.active = s != null && s.file != null;

        Button revert = row1.addChild(Button.builder(Component.translatable("modkeel.revert"),
                b -> Compat.setScreen(minecraft, Client.confirm(this,
                        Component.translatable("modkeel.revert.confirm_title"),
                        revertMessage(g),
                        () -> Client.applyCrashFixAndQuit(minecraft, g, g.revertPlan()))))
                .width(150).build());
        revert.active = g.canRevert();
        if (!revert.active) {
            revert.setTooltip(Tooltip.create(Component.translatable("modkeel.revert.none")));
        }

        row2.addChild(Button.builder(Component.translatable("modkeel.crash.open_report"),
                b -> Compat.openPath(g.crashFile)).width(150).build());
        row2.addChild(Button.builder(Component.translatable("modkeel.crash.continue"),
                b -> onClose()).width(150).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private void confirmDisable(Diagnosis.Suspect s) {
        MutableComponent msg = Component.translatable("modkeel.disable.confirm", s.file);
        List<String> deps = g.dependents(s.id);
        if (!deps.isEmpty()) {
            msg.append("\n\n").append(Component.translatable("modkeel.disable.dependents",
                    String.join(", ", deps)).withStyle(ChatFormatting.YELLOW));
        }
        msg.append("\n\n").append(Component.translatable("modkeel.restart_note"));
        Compat.setScreen(minecraft, Client.confirm(this,
                Component.translatable("modkeel.disable.confirm_title", s.name), msg,
                () -> Client.applyCrashFixAndQuit(minecraft, g, g.disablePlan(s))));
    }

    static Component revertMessage(Guardian g) {
        List<String> names = new ArrayList<>();
        g.changedSinceGood().forEach(j -> names.add(j.file));
        MutableComponent msg = Component.translatable("modkeel.revert.confirm",
                names.isEmpty() ? "-" : clip(String.join(", ", names), 300));
        return msg.append("\n\n").append(Component.translatable("modkeel.restart_note"));
    }

    /**
     * When no one-click fix gets the pack going: no mod to blame, a fix that did not hold, or
     * the lab saw the blamed mod crash on this version. Only then Modkeel points at the app.
     */
    static boolean stuck(Guardian g) {
        Diagnosis d = g.crash;
        if (d != null && d.top() == null) {
            return true;
        }
        for (Outcomes.Fix f : g.outcomes.fixes) {
            if (f.status == Outcomes.Status.RECURRED && (d == null || f.signature.equals(d.signature))) {
                return true;
            }
        }
        for (Guardian.LabMod m : g.labMods()) {
            if (m.rule != null && m.rule.status == Rules.Status.CRASHES
                    && (d == null || d.top() == null || m.id.equals(d.top().id))) {
                return true;
            }
        }
        return false;
    }

    /** A stored message: a translation key with its arguments, or plain text. */
    static Component msg(String m) {
        if (!Msg.isKey(m)) {
            return Component.literal(m);
        }
        return Component.translatable(Msg.key(m), (Object[]) Msg.args(m));
    }

    static MultiLineTextWidget text(Component c, int width) {
        return new MultiLineTextWidget(c, net.minecraft.client.Minecraft.getInstance().font)
                .setMaxWidth(width).setCentered(true);
    }

    /** A translated label with {@code name} shortened until it fits {@code px} pixels. */
    static Component fit(String key, String name, int px) {
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        String n = name;
        Component c = Component.translatable(key, n);
        while (font.width(c) > px && n.length() > 4) {
            n = n.substring(0, n.length() - (n.endsWith("…") ? 2 : 1)) + "…";
            c = Component.translatable(key, n);
        }
        return c;
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
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
