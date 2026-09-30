package modkeel.companion.game;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import modkeel.companion.core.Diagnosis;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Msg;
import modkeel.companion.core.Outcomes;
import modkeel.companion.core.Rules;
import modkeel.companion.core.Log;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
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
    /** Textures made for this screen, closed with it. */
    private final List<AutoCloseable> owned = new ArrayList<>();

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

        rescue(body, w);

        // Headline first (the mod), then why, then what that kind of crash means; the raw error
        // lives in the report button's tooltip
        Diagnosis.Suspect s = d.top();
        if (s != null) {
            body.addChild(text(Component.translatable("modkeel.crash.suspect", s.name)
                    .withStyle(ChatFormatting.YELLOW), w));
            MutableComponent reasons = Component.empty();
            for (String reason : s.reasons) {
                if (!reasons.getSiblings().isEmpty()) {
                    reasons.append("; ");
                }
                reasons.append(msg(reason));
            }
            body.addChild(text(Component.translatable("modkeel.crash.why", Component.translatable(
                    "modkeel.confidence." + d.confidence.name().toLowerCase(Locale.ROOT)), reasons)
                    .withStyle(ChatFormatting.GRAY), w));
        }
        body.addChild(text(Component.translatable("modkeel.kind." + d.kind.name().toLowerCase(Locale.ROOT)), w));
        if (s == null) {
            body.addChild(text(Component.translatable("modkeel.crash.no_suspect"), w));
        } else if (d.suspects.size() > 1) {
            List<String> others = new ArrayList<>();
            for (Diagnosis.Suspect o : d.suspects.subList(1, d.suspects.size())) {
                others.add(o.name);
            }
            body.addChild(text(Component.translatable("modkeel.crash.others", String.join(", ", others))
                    .withStyle(ChatFormatting.GRAY), w));
        }
        if (stuck(g)) {
            body.addChild(text(Component.translatable("modkeel.cta").withStyle(ChatFormatting.AQUA), w));
        }
        body.addChild(text(Component.translatable("modkeel.crash.later").withStyle(ChatFormatting.GRAY), w));

        scroll = layout.addToContents(new Scroll(minecraft, body, Compat.contentHeight(layout)));

        Stack footer = layout.addToFooter(Stack.vertical(4));
        Stack row1 = footer.addChild(Stack.horizontal(8));
        Stack row2 = footer.addChild(Stack.horizontal(8));

        Button disable = row1.addChild(Button.builder(Component.translatable(s == null
                        ? "modkeel.crash.disable_none" : "modkeel.crash.disable"),
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

        Button report = row2.addChild(Button.builder(Component.translatable("modkeel.crash.open_report"),
                b -> Compat.openPath(g.crashFile)).width(150).build());
        if (!d.error.isEmpty()) {
            report.setTooltip(Tooltip.create(Component.literal(clip(d.error, 300))));
        }
        row2.addChild(Button.builder(Component.translatable("modkeel.crash.continue"),
                b -> onClose()).width(150).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    /** The world that was open: it is still there, and how much of it Minecraft had saved. */
    private void rescue(Stack body, int w) {
        Guardian.WorldAtCrash world = g.worldAtCrash;
        if (world == null) {
            return;
        }
        AbstractWidget icon = Compat.worldIcon(minecraft, world.name, world.dir.resolve("icon.png"),
                48, owned);
        if (icon != null) {
            body.addChild(icon);
        }
        body.addChild(text(Component.translatable("modkeel.rescue.safe", world.name)
                .withStyle(ChatFormatting.GREEN), w));
        long minutes = world.unsavedMinutes();
        if (minutes == 0) {
            body.addChild(text(Component.translatable("modkeel.rescue.saved_at_crash"), w));
        } else if (minutes > 0) {
            body.addChild(text(Component.translatable("modkeel.rescue.saved_before", minutes), w));
        }
        if (world.backup != null) {
            body.addChild(text(Component.translatable("modkeel.rescue.backup",
                    backupTime(world.backup.getFileName().toString()))
                    .withStyle(ChatFormatting.GRAY), w));
        }
    }

    /** "20260930-184012-123.zip" -> the player's own date format. */
    static String backupTime(String file) {
        try {
            // the sentence ends with its own period ("p.m." would give "p.m..")
            return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(
                    new SimpleDateFormat("yyyyMMdd-HHmmss").parse(file.substring(0, 15)))
                    .replaceAll("\\.$", "");
        } catch (ParseException | IndexOutOfBoundsException e) {
            return file;
        }
    }

    @Override
    public void removed() {
        for (AutoCloseable c : owned) {
            try {
                c.close();
            } catch (Exception e) {
                Log.warn("cannot free a texture", e);
            }
        }
        owned.clear();
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
