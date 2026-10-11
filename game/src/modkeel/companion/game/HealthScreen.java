package modkeel.companion.game;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import modkeel.companion.core.Backups;
import modkeel.companion.core.Diagnosis;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Memory;
import modkeel.companion.core.Spikes;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Modkeel's home: how things stand in one card, then a door to worlds, mods and lag spikes. */
public final class HealthScreen extends Page {
    static final String APP_URL = "https://modkeel.com";
    private static final int GAP = 6;

    private final Guardian g;
    /** The clash cards show their technical details (the package) only when asked. */
    private boolean details;
    /** The warning shown in full when they do not all fit; the others fold to one line. */
    private int open;
    /** The player opened a warning: it stays open even if the screen must scroll for it. */
    private boolean chose;

    public HealthScreen(Screen back, Guardian g) {
        super(Component.translatable("modkeel.health.title"), back);
        this.g = g;
    }

    @Override
    protected void body(Stack body, int w) {
        List<Warning> warnings = warnings(w);
        open = Math.min(open, Math.max(0, warnings.size() - 1));
        Fit last = chose ? Fit.TIGHT : Fit.LINES;
        Fit fit = Fit.FULL;
        while (fit != last && tooTall(warnings, w, fit)) {
            fit = Fit.values()[fit.ordinal() + 1];
        }
        fill(body, w, warnings, fit);
    }

    /**
     * How much the screen gives up to fit without scrolling, tried in order: everything; one
     * warning open and the others on one line each; that, and the three doors as plain buttons
     * whose text moves to a tooltip (a small window); every warning on one line. Past that
     * (a tiny window) the screen scrolls.
     */
    private enum Fit { FULL, FOLDED, TIGHT, LINES }

    /**
     * Something the player should know, as a card: its colored edge, its one-line title (what
     * a folded warning shows) and the full card.
     */
    private record Warning(int edge, Component title, Consumer<Stack> full) {
    }

    /**
     * Status first, then the cards that are only shown when something needs a look, then the
     * three doors, as compact as {@code fit} asks: below the scroll edge nobody sees them.
     */
    private void fill(Stack body, int w, List<Warning> warnings, Fit fit) {
        if (!statusWarns()) {
            ok(body, w);
        }
        for (int i = 0; i < warnings.size(); i++) {
            Warning warning = warnings.get(i);
            if (fit == Fit.FULL || i == open && fit != Fit.LINES) {
                warning.full.accept(body);
            } else {
                int index = i;
                Card c = body.addChild(new Card(w, warning.edge));
                c.row(warning.title, Keel.TEXT, new KeelButton(60,
                        Component.translatable("modkeel.health.show"), b -> {
                    open = index;
                    chose = true;
                    rebuildWidgets();
                }));
            }
        }
        tiles(body, w, fit.compareTo(Fit.TIGHT) >= 0);
        if (g.crash == null && CrashScreen.stuck(g)) {
            body.addChild(Text.loose(Component.translatable("modkeel.cta"), w, Keel.AQUA));
        }
    }

    /** Laid out in full on a scratch column: does it need the scroll bar? */
    private boolean tooTall(List<Warning> warnings, int w, Fit fit) {
        Stack test = Stack.vertical(6);
        fill(test, w, warnings, fit);
        test.arrangeElements();
        return test.getHeight() > room();
    }

    @Override
    protected void footer(Stack footer) {
        footer.addChild(new KeelButton(150, Component.translatable("modkeel.home.settings"),
                b -> open(new SettingsScreen(this, g))));
        footer.addChild(new KeelButton(150, CommonComponents.GUI_DONE, b -> onClose()));
    }

    private String action() {
        return g.state.get("lastAction", "");
    }

    /** The last change did not finish: some files were held open. */
    private boolean failed() {
        return !action().isEmpty()
                && g.state.getList("lastResult").stream().anyMatch(l -> l.startsWith("fail"));
    }

    /** Whether the status itself is a warning (else it is the green card). */
    private boolean statusWarns() {
        return g.crash != null || !g.clashes.isEmpty() || !g.turnedOff.isEmpty() || failed();
    }

    /**
     * Red when the last launch crashed; amber when a mod was turned off to let this one start,
     * a change did not finish, Java has too little (or too much) memory, or the game draws on a
     * slow (or no) graphics driver.
     */
    private List<Warning> warnings(int w) {
        List<Warning> warnings = new ArrayList<>();
        Component last = Component.translatable("modkeel.health.last_action", CrashScreen.msg(action()));
        if (g.crash != null) {
            Component title = Component.translatable("modkeel.health.crash");
            warnings.add(new Warning(Keel.EDGE_BAD, title, body -> {
                Card c = body.addChild(new Card(w, Keel.EDGE_BAD));
                Diagnosis.Suspect s = g.crash.top();
                Button open = new KeelButton(120, Component.translatable("modkeel.health.crash_open"),
                        b -> open(new CrashScreen(this, g)));
                c.add(new Heading(title, c.inner()));
                c.row(s == null ? Component.translatable("modkeel.crash.unclear")
                        : Component.translatable("modkeel.home.likely", CrashScreen.likely(g.crash), s.name),
                        Keel.SOFT, open);
            }));
        } else if (!g.clashes.isEmpty()) {
            for (Guardian.Clash clash : g.clashes.subList(0, Math.min(g.clashes.size(), 3))) {
                warnings.add(new Warning(Keel.EDGE_WARN, clashTitle(clash), body -> clash(body, w, clash)));
            }
        } else if (!g.turnedOff.isEmpty() || failed()) {
            Component text = Component.translatable(g.turnedOff.isEmpty() ? "modkeel.health.last_fail"
                    : "modkeel.health.turned_off");
            warnings.add(new Warning(Keel.EDGE_WARN, last, body -> {
                Card c = body.addChild(new Card(w, Keel.EDGE_WARN));
                c.add(new Heading(last, c.inner()));
                c.add(Text.in(text, c.inner(), Keel.SOFT));
            }));
        }
        Memory memory = MemoryCard.read(g);
        if (MemoryCard.shown(g, memory)) {
            warnings.add(new Warning(Keel.EDGE_WARN, MemoryCard.title(memory),
                    body -> MemoryCard.card(this, body, w, g, memory)));
        }
        if (Graphics.shown(g)) {
            warnings.add(new Warning(Keel.EDGE_WARN, Graphics.title(), body -> Graphics.card(this, body, w, g)));
        }
        return warnings;
    }

    /** All is well: the green card, with the last change Modkeel made. */
    private void ok(Stack body, int w) {
        Card c = body.addChild(new Card(w, Keel.EDGE_OK));
        c.add(new Heading(Component.translatable("modkeel.home.ok"), c.inner()));
        if (!action().isEmpty()) {
            c.add(Text.in(Component.translatable("modkeel.health.last_action", CrashScreen.msg(action())),
                    c.inner(), Keel.GRAY));
        }
    }

    private static Component clashTitle(Guardian.Clash clash) {
        return clash.otherFile().isEmpty()
                ? Component.translatable("modkeel.health.clash_game_title", clash.name())
                : Component.translatable("modkeel.health.clash_title", clash.otherName(), clash.name());
    }

    /**
     * Two mods that cannot be used together: which one Modkeel turned off so the game could
     * start, a button to keep that one instead, and the technical reason only on request.
     */
    private void clash(Stack body, int w, Guardian.Clash clash) {
        Card c = body.addChild(new Card(w, Keel.EDGE_WARN));
        boolean withGame = clash.otherFile().isEmpty();
        c.add(new Heading(clashTitle(clash), c.inner()));
        c.add(Text.in(withGame
                ? Component.translatable("modkeel.health.clash_game_body")
                : Component.translatable("modkeel.health.clash_body", clash.name()), c.inner(), Keel.SOFT));
        Button more = new KeelButton(110, Component.translatable(
                details ? "modkeel.health.hide_details" : "modkeel.health.details"), b -> {
            details = !details;
            rebuildWidgets();
        });
        if (withGame) {
            c.row(Component.empty(), Keel.SOFT, more);
        } else {
            Component swap = Component.translatable("modkeel.health.clash_swap",
                    CrashScreen.clip(clash.name(), 22));
            c.row(Component.empty(), Keel.SOFT, more, new KeelButton(170, swap,
                    b -> open(Client.confirm(this,
                            Component.translatable("modkeel.health.clash_swap_title", clash.name(),
                                    clash.otherName()),
                            Component.translatable("modkeel.health.clash_swap_note", clash.otherName()),
                            Component.translatable("modkeel.health.clash_swap_action"),
                            () -> Client.applyAndQuit(minecraft, g, g.swapPlan(clash))))));
        }
        if (details) {
            c.add(Text.in(Component.translatable("modkeel.health.clash_details", clash.pkg()),
                    c.inner(), Keel.GRAY));
        }
    }

    private record Tile(Component title, Component text, Component go, Button.OnPress press) {
    }

    /** Three cards side by side, the same height, their buttons on one line. */
    private void tiles(Stack body, int w, boolean tight) {
        List<Tile> tiles = List.of(
                new Tile(Component.translatable("modkeel.home.worlds"), worldsText(),
                        Component.translatable("modkeel.home.worlds_go"), b -> open(new WorldsScreen(this, g))),
                new Tile(Component.translatable("modkeel.home.mods"), modsText(),
                        Component.translatable("modkeel.home.mods_go"), b -> open(new ModsScreen(this, g))),
                new Tile(Component.translatable("modkeel.home.spikes"), spikesText(),
                        Component.translatable("modkeel.home.spikes_go"), b -> open(new SpikeScreen(this))));
        int tw = (w - 2 * GAP) / 3;
        if (tight) {
            Stack row = body.addChild(Stack.horizontal(GAP));
            for (Tile t : tiles) {
                Button b = row.addChild(new KeelButton(tw, t.title, t.press));
                b.setTooltip(Tooltip.create(t.text));
            }
            return;
        }
        int in = tw - 2 * Card.PAD;
        List<Heading> heads = new ArrayList<>();
        List<Text> texts = new ArrayList<>();
        int tall = 0;
        for (Tile t : tiles) {
            Heading h = new Heading(t.title, in);
            Text x = Text.in(t.text, in, Keel.GRAY);
            heads.add(h);
            texts.add(x);
            tall = Math.max(tall, h.getHeight() + x.getHeight());
        }
        Stack row = body.addChild(Stack.horizontal(GAP));
        for (int i = 0; i < tiles.size(); i++) {
            Card c = row.addChild(new Card(tw));
            c.add(heads.get(i));
            c.add(texts.get(i));
            c.add(SpacerElement.height(tall - heads.get(i).getHeight() - texts.get(i).getHeight()));
            c.add(new KeelButton(in, tiles.get(i).go, tiles.get(i).press));
        }
    }

    private Component worldsText() {
        List<String> worlds = Backups.worlds(g.gameDir);
        Path newest = null;
        for (String world : worlds) {
            for (Path zip : Backups.list(g.gameDir, world)) {
                if (newest == null || zip.getFileName().toString().compareTo(newest.getFileName().toString()) > 0) {
                    newest = zip;
                }
            }
        }
        if (newest == null) {
            return Component.translatable("modkeel.home.worlds_none");
        }
        return Component.translatable("modkeel.home.worlds_text", CrashScreen.backupTime(newest.getFileName().toString(), minecraft.options.languageCode));
    }

    private Component modsText() {
        int mods = g.current().jars.size();
        if (g.lastGood() == null) {
            return Component.translatable("modkeel.home.mods_new", mods);
        }
        int changed = g.changedSinceGood().size();
        return changed == 0 ? Component.translatable("modkeel.home.mods_same", mods)
                : Component.translatable("modkeel.home.mods_changed", mods, changed);
    }

    private Component spikesText() {
        List<Spikes.Spike> spikes = Common.spikes.recent();
        if (spikes.isEmpty()) {
            return Component.translatable("modkeel.home.spikes_none");
        }
        Spikes.Spike last = spikes.get(0);
        Component mostly = last.shares.isEmpty() ? Component.literal("Minecraft")
                : SpikeScreen.shortName(last.shares.get(0));
        return Component.translatable("modkeel.home.spikes_text", spikes.size(), mostly);
    }
}
