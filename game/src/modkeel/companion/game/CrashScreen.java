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
import modkeel.companion.core.Reports;
import modkeel.companion.core.Rules;
import modkeel.companion.core.Log;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
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
    /** "Share this crash": kept across re-inits (a confirm screen and back). */
    private boolean share;
    private Checkbox shareBox;
    /** "Also share play sessions", offered from the second shared crash on. */
    private boolean sessions;
    private Checkbox sessionsBox;
    private final boolean offerSessions;
    private boolean decided;
    /** Textures made for this screen, closed with it. */
    private final List<AutoCloseable> owned = new ArrayList<>();

    public CrashScreen(Screen next, Guardian g) {
        super(Component.translatable("modkeel.crash.title"));
        this.next = next;
        this.g = g;
        this.d = g.crash;
        this.share = g.shareChoice();
        this.offerSessions = g.offerSessions();
    }

    @Override
    protected void init() {
        Compat.background(this, this::addRenderableOnly);
        layout = new HeaderAndFooterLayout(this, 33, footerHeight());
        Compat.titleHeader(layout, title, font);
        int w = Math.min(width - 40, 380);
        Stack body = Stack.vertical(6);
        body.defaultCellSetting().alignHorizontallyCenter();

        rescue(body, w);

        // The likely cause as the card's title, how sure as a pill, then why and what that kind of crash
        // means; the raw error lives in the report button's tooltip
        Diagnosis.Suspect s = d.top();
        Card cause = body.addChild(new Card(w));
        int in = cause.inner();
        if (s != null) {
            cause.add(new Heading(Component.translatable("modkeel.crash.suspect", s.name), in, Keel.YELLOW,
                    Component.translatable("modkeel.confidence.pill", Component.translatable(
                            "modkeel.confidence." + d.confidence.name().toLowerCase(Locale.ROOT)))));
            MutableComponent reasons = Component.empty();
            for (String reason : s.reasons) {
                if (!reasons.getSiblings().isEmpty()) {
                    reasons.append("; ");
                }
                reasons.append(msg(reason));
            }
            cause.add(Text.in(reasons, in, Keel.GRAY));
        } else {
            cause.add(new Heading(Component.translatable("modkeel.crash.no_suspect_title"), in, Keel.YELLOW));
        }
        cause.add(Text.in(Component.translatable("modkeel.kind." + d.kind.name().toLowerCase(Locale.ROOT)),
                in, Keel.SOFT));
        if (s == null) {
            cause.add(Text.in(Component.translatable("modkeel.crash.no_suspect"), in, Keel.SOFT));
        } else if (d.suspects.size() > 1) {
            List<String> others = new ArrayList<>();
            for (Diagnosis.Suspect o : d.suspects.subList(1, d.suspects.size())) {
                others.add(o.name);
            }
            cause.add(Text.in(Component.translatable("modkeel.crash.others", String.join(", ", others)),
                    in, Keel.GRAY));
        }
        if (stuck(g)) {
            body.addChild(Text.loose(Component.translatable("modkeel.cta"), w, Keel.AQUA));
        }
        body.addChild(Text.loose(Component.translatable("modkeel.crash.later"), w, Keel.GRAY));

        scroll = Compat.contents(layout, new Scroll(minecraft, body, Compat.contentHeight(layout)));

        Stack footer = layout.addToFooter(Stack.vertical(4));
        shareBox = null;
        if (g.reports.enabled()) {
            Stack row0 = footer.addChild(Stack.horizontal(8));
            shareBox = row0.addChild(Compat.checkbox(Component.translatable("modkeel.share.checkbox"),
                    font, share));
            shareBox.setTooltip(Tooltip.create(Component.translatable("modkeel.share.tooltip")));
            row0.addChild(new KeelButton(100, Component.translatable("modkeel.share.what"),
                    b -> Compat.setScreen(minecraft, new ReportScreen(this,
                            g.reports.readable(g.reports.crash(d, s == null || s.file == null
                                    ? null : g.reports.disableFix(s)))))));
        }
        sessionsBox = null;
        if (offerSessions) {
            Stack row = footer.addChild(Stack.horizontal(8));
            sessionsBox = row.addChild(Compat.checkbox(Component.translatable("modkeel.sessions.checkbox"),
                    font, sessions));
            sessionsBox.setTooltip(Tooltip.create(Component.translatable("modkeel.sessions.tooltip")));
            row.addChild(whatIsSent(this, g));
        }
        Stack row1 = footer.addChild(Stack.horizontal(8));
        Stack row2 = footer.addChild(Stack.horizontal(8));

        Button disable = row1.addChild(new KeelButton(150, Component.translatable(s == null
                        ? "modkeel.crash.disable_none" : "modkeel.crash.disable"),
                b -> confirmDisable(s)));
        disable.active = s != null && s.file != null;

        Button revert = row1.addChild(new KeelButton(150, Component.translatable("modkeel.revert"),
                b -> Compat.setScreen(minecraft, Client.confirm(this,
                        Component.translatable("modkeel.revert.confirm_title"),
                        revertMessage(g),
                        () -> {
                            decide(g.reports.revertFix());
                            Client.applyCrashFixAndQuit(minecraft, g, g.revertPlan());
                        }))));
        revert.active = g.canRevert();
        if (!revert.active) {
            revert.setTooltip(Tooltip.create(Component.translatable("modkeel.revert.none")));
        }

        Button report = row2.addChild(new KeelButton(150, Component.translatable("modkeel.crash.open_report"),
                b -> Compat.openPath(g.crashFile)));
        if (!d.error.isEmpty()) {
            report.setTooltip(Tooltip.create(Component.literal(clip(d.error, 300))));
        }
        row2.addChild(new KeelButton(150, Component.translatable("modkeel.crash.continue"),
                b -> onClose()));

        addRenderableOnly(new Backdrop(width, height, 33, footerHeight(), scroll));
        layout.visitWidgets(this::addRenderableWidget);
        addRenderableOnly(new Edges(width, height, scroll, body));
        repositionElements();
    }

    private int footerHeight() {
        return !g.reports.enabled() ? 60 : offerSessions ? 108 : 84;
    }

    /** The world that was open: it is still there, and how much of it Minecraft had saved. */
    private void rescue(Stack body, int w) {
        Guardian.WorldAtCrash world = g.worldAtCrash;
        if (world == null) {
            return;
        }
        Card card = body.addChild(new Card(w));
        Stack row = card.add(Stack.horizontal(8));
        int icon = 32;
        AbstractWidget image = Compat.worldIcon(minecraft, world.name, world.dir.resolve("icon.png"),
                icon, owned);
        int in = card.inner();
        if (image != null) {
            row.addChild(image);
            in -= icon + 8;
        }
        Stack lines = row.addChild(Stack.vertical(4));
        lines.addChild(new Heading(Component.literal(world.name), in,
                Component.translatable("modkeel.rescue.pill_safe")));
        long minutes = world.unsavedMinutes();
        if (minutes == 0) {
            lines.addChild(Text.in(Component.translatable("modkeel.rescue.saved_at_crash"), in, Keel.SOFT));
        } else if (minutes > 0) {
            lines.addChild(Text.in(Component.translatable("modkeel.rescue.saved_before", minutes), in, Keel.SOFT));
        }
        if (world.backup != null) {
            lines.addChild(Text.in(Component.translatable("modkeel.rescue.backup",
                    backupTime(world.backup.getFileName().toString(), minecraft.options.languageCode)),
                    in, Keel.GRAY));
        }
    }

    /** "20260930-184012-123.zip" -> a date in the game's language ("en_us"), not the system's. */
    static String backupTime(String file, String language) {
        Locale locale = Locale.forLanguageTag(language.replace('_', '-'));
        try {
            // the sentence ends with its own period ("p.m." would give "p.m..")
            return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(
                    new SimpleDateFormat("yyyyMMdd-HHmmss").parse(file.substring(0, 15)))
                    .replaceAll("\\.$", "");
        } catch (ParseException | IndexOutOfBoundsException e) {
            return file;
        }
    }

    /** "What is sent" for session summaries: this session so far. */
    static Button whatIsSent(Screen back, Guardian g) {
        return new KeelButton(100, Component.translatable("modkeel.share.what"),
                b -> Compat.setScreen(net.minecraft.client.Minecraft.getInstance(), new ReportScreen(back,
                        g.reports.readable(g.sessions.preview()), "modkeel.sessions.note")));
    }

    /** The player picked a fix (null: none); the crash is shared with it if they chose to. */
    private void decide(Reports.Fix fix) {
        if (!decided) {
            decided = true;
            keepChoices();
            g.shareCrash(share, fix);
            if (sessions) {
                g.setShareSessions(true);
            }
        }
    }

    private void keepChoices() {
        if (shareBox != null) {
            share = shareBox.selected();
        }
        if (sessionsBox != null) {
            sessions = sessionsBox.selected();
        }
    }

    @Override
    public void removed() {
        keepChoices();
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
                () -> {
                    decide(g.reports.disableFix(s));
                    Client.applyCrashFixAndQuit(minecraft, g, g.disablePlan(s));
                }));
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
        decide(null);
        Compat.setScreen(minecraft, next);
    }
}
