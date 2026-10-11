package modkeel.companion.game;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import modkeel.companion.core.Backups;
import modkeel.companion.core.Diagnosis;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Spikes;
import net.minecraft.client.gui.components.Button;
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

    public HealthScreen(Screen back, Guardian g) {
        super(Component.translatable("modkeel.health.title"), back);
        this.g = g;
    }

    @Override
    protected void body(Stack body, int w) {
        status(body, w);
        Graphics.card(this, body, w, g);
        tiles(body, w);
        if (g.crash == null && CrashScreen.stuck(g)) {
            body.addChild(Text.loose(Component.translatable("modkeel.cta"), w, Keel.AQUA));
        }
    }

    @Override
    protected void footer(Stack footer) {
        footer.addChild(new KeelButton(150, Component.translatable("modkeel.home.settings"),
                b -> open(new SettingsScreen(this, g))));
        footer.addChild(new KeelButton(150, CommonComponents.GUI_DONE, b -> onClose()));
    }

    /**
     * Red when the last launch crashed, amber when a mod was turned off to let this one start
     * or a change did not finish, green otherwise.
     */
    private void status(Stack body, int w) {
        String action = g.state.get("lastAction", "");
        boolean failed = !action.isEmpty()
                && g.state.getList("lastResult").stream().anyMatch(l -> l.startsWith("fail"));
        if (g.crash != null) {
            Card c = body.addChild(new Card(w, Keel.EDGE_BAD));
            Diagnosis.Suspect s = g.crash.top();
            Button open = new KeelButton(120, Component.translatable("modkeel.health.crash_open"),
                    b -> open(new CrashScreen(this, g)));
            c.add(new Heading(Component.translatable("modkeel.health.crash"), c.inner()));
            c.row(s == null ? Component.translatable("modkeel.crash.unclear")
                    : Component.translatable("modkeel.home.likely", CrashScreen.likely(g.crash), s.name),
                    Keel.SOFT, open);
        } else if (!g.clashes.isEmpty()) {
            for (Guardian.Clash clash : g.clashes.subList(0, Math.min(g.clashes.size(), 3))) {
                clash(body, w, clash);
            }
        } else if (!g.turnedOff.isEmpty()) {
            Card c = body.addChild(new Card(w, Keel.EDGE_WARN));
            c.add(new Heading(Component.translatable("modkeel.health.last_action", CrashScreen.msg(action)),
                    c.inner()));
            c.add(Text.in(Component.translatable("modkeel.health.turned_off"), c.inner(), Keel.SOFT));
        } else if (failed) {
            Card c = body.addChild(new Card(w, Keel.EDGE_WARN));
            c.add(new Heading(Component.translatable("modkeel.health.last_action", CrashScreen.msg(action)),
                    c.inner()));
            c.add(Text.in(Component.translatable("modkeel.health.last_fail"), c.inner(), Keel.SOFT));
        } else {
            Card c = body.addChild(new Card(w, Keel.EDGE_OK));
            c.add(new Heading(Component.translatable("modkeel.home.ok"), c.inner()));
            if (!action.isEmpty()) {
                c.add(Text.in(Component.translatable("modkeel.health.last_action", CrashScreen.msg(action)),
                        c.inner(), Keel.GRAY));
            }
        }
    }

    /**
     * Two mods that cannot be used together: which one Modkeel turned off so the game could
     * start, a button to keep that one instead, and the technical reason only on request.
     */
    private void clash(Stack body, int w, Guardian.Clash clash) {
        Card c = body.addChild(new Card(w, Keel.EDGE_WARN));
        boolean withGame = clash.otherFile().isEmpty();
        c.add(new Heading(withGame
                ? Component.translatable("modkeel.health.clash_game_title", clash.name())
                : Component.translatable("modkeel.health.clash_title", clash.otherName(), clash.name()),
                c.inner()));
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
    private void tiles(Stack body, int w) {
        List<Tile> tiles = List.of(
                new Tile(Component.translatable("modkeel.home.worlds"), worldsText(),
                        Component.translatable("modkeel.home.worlds_go"), b -> open(new WorldsScreen(this, g))),
                new Tile(Component.translatable("modkeel.home.mods"), modsText(),
                        Component.translatable("modkeel.home.mods_go"), b -> open(new ModsScreen(this, g))),
                new Tile(Component.translatable("modkeel.home.spikes"), spikesText(),
                        Component.translatable("modkeel.home.spikes_go"), b -> open(new SpikeScreen(this))));
        int tw = (w - 2 * GAP) / 3;
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
