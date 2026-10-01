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

/**
 * Shown once before the title screen when the last session crashed. Calm first (the world is
 * safe), then the cause in one sentence, then one green button; the rest waits behind
 * {@link CrashOptionsScreen}.
 */
public final class CrashScreen extends Screen {
    private final Screen next;
    final Guardian g;
    final Diagnosis d;
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
        int w = Keel.bodyWidth(width, 380);
        Stack body = Stack.vertical(6);
        body.defaultCellSetting().alignHorizontallyCenter();

        rescue(body, w);
        cause(body, w);
        if (stuck(g)) {
            body.addChild(Text.loose(Component.translatable("modkeel.cta"), w, Keel.AQUA));
        }

        scroll = Compat.contents(layout, new Scroll(minecraft, body, Compat.contentHeight(layout)));

        Stack footer = layout.addToFooter(Stack.vertical(4));
        Diagnosis.Suspect s = d.top();
        if (canDisable(s)) {
            footer.addChild(new KeelButton(304, Component.translatable("modkeel.crash.fix", s.name),
                    b -> confirmDisable(this, s)).primary());
        } else if (g.canRevert()) {
            footer.addChild(new KeelButton(304, Component.translatable("modkeel.crash.fix_revert"),
                    b -> confirmRevert(this)).primary());
        }
        Stack row = footer.addChild(Stack.horizontal(4));
        row.addChild(new KeelButton(150, Component.translatable("modkeel.crash.more"),
                b -> Compat.setScreen(minecraft, new CrashOptionsScreen(this))));
        Button later = row.addChild(new KeelButton(150, Component.translatable("modkeel.crash.not_now"),
                b -> onClose()));
        later.setTooltip(Tooltip.create(Component.translatable("modkeel.crash.later")));

        shareBox = null;
        if (g.reports.enabled()) {
            Stack row0 = footer.addChild(Stack.horizontal(8));
            shareBox = row0.addChild(Compat.checkbox(Component.translatable("modkeel.share.checkbox"),
                    font, share));
            shareBox.setTooltip(Tooltip.create(Component.translatable("modkeel.share.tooltip")));
            row0.addChild(new KeelButton(100, Component.translatable("modkeel.share.what"),
                    b -> Compat.setScreen(minecraft, new ReportScreen(this,
                            g.reports.readable(g.reports.crash(d, canDisable(s)
                                    ? g.reports.disableFix(s) : null))))));
        }
        sessionsBox = null;
        if (offerSessions) {
            Stack row1 = footer.addChild(Stack.horizontal(8));
            sessionsBox = row1.addChild(Compat.checkbox(Component.translatable("modkeel.sessions.checkbox"),
                    font, sessions));
            sessionsBox.setTooltip(Tooltip.create(Component.translatable("modkeel.sessions.tooltip")));
            row1.addChild(whatIsSent(this, g));
        }

        addRenderableOnly(new Backdrop(width, height, 33, footerHeight(), scroll));
        layout.visitWidgets(this::addRenderableWidget);
        addRenderableOnly(new Edges(width, height, scroll, body));
        repositionElements();
    }

    static boolean canDisable(Diagnosis.Suspect s) {
        return s != null && s.file != null;
    }

    /** One row per line of buttons: the fix (if any), More/Not now, sharing, sessions. */
    private int footerHeight() {
        int rows = 1;
        if (canDisable(d.top()) || g.canRevert()) {
            rows++;
        }
        if (g.reports.enabled()) {
            rows++;
        }
        if (offerSessions) {
            rows++;
        }
        return 12 + 24 * rows;
    }

    /** The likely cause as the card's title, how sure in the words above it, then why. */
    private void cause(Stack body, int w) {
        Diagnosis.Suspect s = d.top();
        Card card = body.addChild(new Card(w, Keel.EDGE_WARN));
        int in = card.inner();
        if (s != null) {
            String sure = switch (d.confidence) {
                case HIGH -> "high";
                case MEDIUM -> "medium";
                default -> "low";
            };
            card.add(Text.in(Component.translatable("modkeel.crash.likely." + sure), in, Keel.GRAY));
            card.add(new Heading(Component.literal(s.name), in));
            MutableComponent reasons = Component.empty();
            for (String reason : s.reasons) {
                if (!reasons.getSiblings().isEmpty()) {
                    reasons.append("; ");
                }
                reasons.append(msg(reason));
            }
            card.add(Text.in(Component.literal(sentence(reasons.getString())), in, Keel.SOFT));
        } else {
            card.add(new Heading(Component.translatable("modkeel.crash.unclear"), in));
            if (g.lastGood() != null) {
                int changed = g.changedSinceGood().size();
                card.add(Text.in(changed > 0 ? Component.translatable("modkeel.crash.changed", changed)
                        : Component.translatable("modkeel.crash.unchanged"), in, Keel.SOFT));
            }
        }
        if (d.kind == Diagnosis.Kind.OUT_OF_MEMORY) {
            card.add(Text.in(Component.translatable("modkeel.kind.out_of_memory"), in, Keel.GRAY));
        }
    }

    /** "its code is in the error" -> "Its code is in the error." */
    static String sentence(String s) {
        if (s.isEmpty()) {
            return s;
        }
        String first = s.substring(0, s.offsetByCodePoints(0, 1));
        return first.toUpperCase(Locale.ROOT) + s.substring(first.length())
                + (s.endsWith(".") ? "" : ".");
    }

    /** The world that was open: it is still there, and how much of it Minecraft had saved. */
    private void rescue(Stack body, int w) {
        Guardian.WorldAtCrash world = g.worldAtCrash;
        if (world == null) {
            return;
        }
        Card card = body.addChild(new Card(w, Keel.EDGE_OK));
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
        lines.addChild(new Heading(Component.translatable("modkeel.rescue.title"), in));
        long minutes = world.unsavedMinutes();
        if (minutes == 0) {
            lines.addChild(Text.in(Component.translatable("modkeel.rescue.saved", world.name),
                    in, Keel.SOFT));
        } else if (minutes > 0) {
            lines.addChild(Text.in(Component.translatable("modkeel.rescue.saved_min", world.name,
                    minutes), in, Keel.SOFT));
        }
        if (world.backup != null) {
            lines.addChild(Text.in(Component.translatable("modkeel.rescue.copy",
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

    /** Asks before disabling {@code s}: what happens, and that it can be undone. */
    void confirmDisable(Screen back, Diagnosis.Suspect s) {
        MutableComponent msg = Component.translatable("modkeel.disable.points");
        List<String> deps = g.dependents(s.id);
        if (!deps.isEmpty()) {
            msg.append("\n\n").append(Component.translatable("modkeel.disable.dependents",
                    String.join(", ", deps)).withStyle(ChatFormatting.YELLOW));
        }
        Compat.setScreen(minecraft, Client.confirm(back,
                Component.translatable("modkeel.disable.confirm_title", s.name), msg,
                Component.translatable("modkeel.disable.go"),
                () -> {
                    decide(g.reports.disableFix(s));
                    Client.applyCrashFixAndQuit(minecraft, g, g.disablePlan(s));
                }));
    }

    void confirmRevert(Screen back) {
        Compat.setScreen(minecraft, Client.confirm(back,
                Component.translatable("modkeel.revert.confirm_title"), revertMessage(g),
                Component.translatable("modkeel.revert.go"),
                () -> {
                    decide(g.reports.revertFix());
                    Client.applyCrashFixAndQuit(minecraft, g, g.revertPlan());
                }));
    }

    static Component revertMessage(Guardian g) {
        List<String> names = new ArrayList<>();
        g.changedSinceGood().forEach(j -> names.add(j.file));
        return Component.translatable("modkeel.revert.points",
                names.isEmpty() ? "-" : clip(String.join(", ", names), 300));
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
