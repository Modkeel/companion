package modkeel.companion.game;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import modkeel.companion.core.Backups;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Log;
import modkeel.companion.core.ModSet;
import modkeel.companion.core.Msg;
import modkeel.companion.core.Outcomes;
import modkeel.companion.core.Rules;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** "Pack health": the mod set, world backups, and what Modkeel changed, each reversible. */
public final class HealthScreen extends Screen {
    static final String APP_URL = "https://modkeel.com";
    private static final int MAX_ROWS = 4;

    private final Screen parent;
    private final Guardian g;
    private HeaderAndFooterLayout layout;
    private Scroll scroll;

    public HealthScreen(Screen parent, Guardian g) {
        super(Component.translatable("modkeel.health.title"));
        this.parent = parent;
        this.g = g;
    }

    @Override
    protected void init() {
        Compat.background(this, this::addRenderableOnly);
        layout = new HeaderAndFooterLayout(this, 33, 36);
        Compat.titleHeader(layout, title, font);
        int w = Keel.bodyWidth(width, 380);
        Stack body = Stack.vertical(6);
        body.defaultCellSetting().alignHorizontallyCenter();

        if (g.crash != null) {
            // Continue on the crash screen is not final: the diagnosis and its fixes stay here
            Card c = body.addChild(new Card(w));
            row(c, Component.translatable("modkeel.health.crash"), Keel.RED,
                    new KeelButton(100, Component.translatable("modkeel.health.crash_open"),
                            b -> Compat.setScreen(minecraft, new CrashScreen(this, g))));
        }
        addMods(body, w);
        addBackups(body, w);

        List<String> disabled = g.disabledByUs();
        if (!disabled.isEmpty()) {
            Card c = body.addChild(new Card(w));
            c.add(new Heading(Component.translatable("modkeel.health.disabled"), c.inner()));
            for (String f : disabled.subList(0, Math.min(disabled.size(), MAX_ROWS))) {
                row(c, Component.literal(CrashScreen.clip(f.replaceAll("(\\.\\d+)?\\.disabled$", ""), 40)),
                        Keel.SOFT, new KeelButton(100, Component.translatable("modkeel.health.enable"),
                                b -> Compat.setScreen(minecraft, Client.confirm(this,
                                        Component.translatable("modkeel.health.enable"),
                                        Component.translatable("modkeel.restart_note"),
                                        () -> Client.applyAndQuit(minecraft, g, g.enablePlan(f))))));
            }
        }

        if (g.rules.size > 0) {
            addLab(body, w);
        }

        List<Outcomes.Fix> fixes = g.outcomes.fixes;
        if (!fixes.isEmpty()) {
            Card c = body.addChild(new Card(w));
            c.add(new Heading(Component.translatable("modkeel.health.fixes"), c.inner()));
            for (Outcomes.Fix f : fixes.subList(0, Math.min(fixes.size(), MAX_ROWS))) {
                c.add(Text.in(Component.translatable("modkeel.health.fix_row", CrashScreen.msg(f.title),
                        Component.translatable("modkeel.outcome." + f.status.name().toLowerCase(java.util.Locale.ROOT),
                                f.ticks / 72000, f.ticks / 1200 % 60)), c.inner(), Keel.SOFT));
            }
        }
        if (g.reports.enabled()) {
            boolean on = g.shareSessions();
            Card c = body.addChild(new Card(w));
            Button toggle = new KeelButton(100, Component.translatable(on ? "modkeel.sessions.stop" : "modkeel.sessions.start"),
                    b -> {
                        g.setShareSessions(!on);
                        Compat.setScreen(minecraft, this);
                    });
            toggle.setTooltip(Tooltip.create(Component.translatable("modkeel.sessions.tooltip")));
            row(c, Component.translatable(on ? "modkeel.sessions.on" : "modkeel.sessions.off"), Keel.SOFT,
                    toggle, CrashScreen.whatIsSent(this, g));
        }
        if (g.crash == null && CrashScreen.stuck(g)) {
            body.addChild(Text.loose(Component.translatable("modkeel.cta"), w, Keel.AQUA));
        }
        scroll = Compat.contents(layout, new Scroll(minecraft, body, Compat.contentHeight(layout)));

        Stack footer = layout.addToFooter(Stack.horizontal(8));
        Button revert = footer.addChild(new KeelButton(120, Component.translatable("modkeel.revert"),
                b -> Compat.setScreen(minecraft, Client.confirm(this,
                        Component.translatable("modkeel.revert.confirm_title"),
                        CrashScreen.revertMessage(g),
                        () -> Client.applyAndQuit(minecraft, g, g.revertPlan())))));
        revert.active = g.canRevert();
        if (g.lastGood() == null) {
            revert.setTooltip(Tooltip.create(Component.translatable("modkeel.revert.none")));
        }
        footer.addChild(new KeelButton(120, Component.translatable("modkeel.health.app"),
                b -> Compat.openLink(this, APP_URL)));
        footer.addChild(new KeelButton(120, CommonComponents.GUI_DONE, b -> onClose()));

        addRenderableOnly(new Backdrop(width, height, 33, 36, scroll));
        layout.visitWidgets(this::addRenderableWidget);
        addRenderableOnly(new Edges(width, height, scroll, body));
        repositionElements();
    }

    /** A line of text and its buttons on the right, inside a card. */
    private static void row(Card card, Component text, int color, Button... buttons) {
        Stack row = card.add(Stack.horizontal(8));
        row.defaultCellSetting().alignVerticallyMiddle();
        int left = card.inner();
        for (Button b : buttons) {
            left -= b.getWidth() + 8;
        }
        row.addChild(Text.in(text, left, color));
        for (Button b : buttons) {
            row.addChild(b);
        }
    }

    /** The mod count, how it stands against the last good set, and the last change made. */
    private void addMods(Stack body, int w) {
        Card c = body.addChild(new Card(w));
        ModSet now = g.current();
        List<String> changed = new ArrayList<>();
        Component pill;
        if (g.lastGood() == null) {
            pill = Component.translatable("modkeel.health.pill_no_good");
        } else {
            g.changedSinceGood().forEach(j -> changed.add(j.file));
            pill = changed.isEmpty() ? Component.translatable("modkeel.health.pill_same")
                    : Component.translatable("modkeel.health.pill_changed", changed.size());
        }
        c.add(new Heading(Component.translatable("modkeel.health.mods", now.jars.size()), c.inner(), pill));
        if (!changed.isEmpty()) {
            c.add(Text.in(Component.literal(CrashScreen.clip(String.join(", ", changed), 200)),
                    c.inner(), Keel.GRAY));
        }
        List<String> last = g.state.getList("lastResult");
        String action = g.state.get("lastAction", "");
        if (!last.isEmpty() && !action.isEmpty()) {
            MutableComponent line = Component.translatable("modkeel.health.last_action", CrashScreen.msg(action));
            boolean failed = last.stream().anyMatch(l -> l.startsWith("fail"));
            if (failed) {
                line.append(" ").append(Component.translatable("modkeel.health.last_fail"));
            }
            c.add(Text.in(line, c.inner(), failed ? Keel.GOLD : Keel.GRAY));
        }
    }

    /** The newest backup of each world, with a button to restore it. */
    private void addBackups(Stack body, int w) {
        Card c = body.addChild(new Card(w));
        List<String> worlds = Backups.worlds(g.gameDir);
        if (worlds.isEmpty()) {
            c.add(new Heading(Component.translatable("modkeel.health.backups"), c.inner(),
                    Component.translatable("modkeel.health.pill_none")));
            return;
        }
        c.add(new Heading(Component.translatable("modkeel.health.backups"), c.inner()));
        for (String world : worlds.subList(0, Math.min(worlds.size(), MAX_ROWS))) {
            List<Path> zips = Backups.list(g.gameDir, world);
            if (zips.isEmpty()) {
                continue;
            }
            row(c, Component.translatable("modkeel.health.backup_row", CrashScreen.clip(world, 24),
                    when(zips.get(0)), zips.size()), Keel.SOFT,
                    new KeelButton(100, Component.translatable("modkeel.health.restore"),
                            b -> confirmRestore(world, zips.get(0))));
        }
    }

    /** What the Modkeel lab knows about the installed mods on this Minecraft version. */
    private void addLab(Stack body, int w) {
        List<Guardian.LabMod> mods = g.labMods();
        List<Rules.Rule> clashes = g.labClashes();
        Card c = body.addChild(new Card(w));
        int in = c.inner();
        c.add(new Heading(Component.translatable("modkeel.lab.title", g.mcVersion), in));
        if (clashes.isEmpty() && mods.stream().allMatch(m -> m.rule == null && m.otherVersion == null)) {
            c.add(Text.in(Component.translatable("modkeel.lab.none", mods.size()), in, Keel.GRAY));
            return;
        }
        List<String> verified = new ArrayList<>();
        List<String> otherVersion = new ArrayList<>();
        for (Guardian.LabMod m : mods) {
            if (m.rule != null && m.rule.status == Rules.Status.CRASHES) {
                c.add(Text.in(Component.translatable("modkeel.lab.crashes", m.name, g.mcVersion), in, Keel.RED));
            } else if (m.rule != null) {
                verified.add(m.name + " (" + Component.translatable("modkeel.lab." + m.rule.status.name()
                        .toLowerCase(java.util.Locale.ROOT)).getString() + ")");
            } else if (m.otherVersion != null) {
                otherVersion.add(m.name + " (" + m.otherVersion + ")");
            }
        }
        for (Rules.Rule r : clashes) {
            c.add(Text.in(Component.translatable("modkeel.lab.clash", r.a, r.b), in, Keel.RED));
        }
        if (!verified.isEmpty()) {
            c.add(Text.in(Component.translatable("modkeel.lab.verified", verified.size(),
                    mods.size(), CrashScreen.clip(String.join(", ", verified), 300)), in, Keel.SOFT));
        }
        if (!otherVersion.isEmpty()) {
            c.add(Text.in(Component.translatable("modkeel.lab.other_version",
                    CrashScreen.clip(String.join(", ", otherVersion), 300)), in, Keel.GOLD));
        }
    }

    private void confirmRestore(String world, Path zip) {
        Compat.setScreen(minecraft, Client.confirm(this,
                Component.translatable("modkeel.restore.confirm_title", world),
                Component.translatable("modkeel.restore.confirm", when(zip)),
                () -> {
                    try {
                        Path aside = Backups.restore(g.gameDir, g.gameDir.resolve("saves"), world, zip);
                        g.state.set("lastAction", Msg.of("modkeel.action.restored", world, when(zip)));
                        g.state.setList("lastResult", List.of("ok" + (aside == null ? "" : " " + aside)));
                        Log.info("restored " + world + " from " + zip);
                    } catch (IOException e) {
                        g.state.set("lastAction", Msg.of("modkeel.action.restored", world, when(zip)));
                        g.state.setList("lastResult", List.of("fail " + e));
                        Log.warn("restore of " + world + " failed", e);
                    }
                    g.state.save();
                    Compat.setScreen(minecraft, this);
                }));
    }

    /** "20260928-143005.zip" as "2026-09-28 14:30". */
    static String when(Path zip) {
        String n = zip.getFileName().toString();
        if (n.length() < 15) {
            return n;
        }
        return n.substring(0, 4) + "-" + n.substring(4, 6) + "-" + n.substring(6, 8) + " "
                + n.substring(9, 11) + ":" + n.substring(11, 13);
    }

    @Override
    protected void repositionElements() {
        scroll.arrangeElements();
        scroll.setMaxHeight(Compat.contentHeight(layout));
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, parent);
    }
}
