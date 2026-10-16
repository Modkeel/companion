package modkeel.companion.game;

import java.nio.file.Path;
import java.nio.file.Paths;

import modkeel.companion.core.Backups;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Log;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * What the player can switch: sharing play sessions, lag spike alerts, where world backups go;
 * and the website.
 */
final class SettingsScreen extends Page {
    /** What the last backup folder change said, shown until the screen is left. */
    private static volatile Component folderNote;
    private static volatile boolean moving;
    private volatile boolean showing;
    private final Guardian g;

    SettingsScreen(Screen back, Guardian g) {
        super(Component.translatable("modkeel.home.settings"), back);
        this.g = g;
        if (!moving) {
            folderNote = null;
        }
    }

    @Override
    protected void body(Stack body, int w) {
        if (g.reports.enabled()) {
            boolean on = g.shareSessions();
            Card c = body.addChild(new Card(w));
            Button toggle = new KeelButton(100, Component.translatable(on ? "modkeel.sessions.stop" : "modkeel.sessions.start"),
                    b -> {
                        g.setShareSessions(!on);
                        rebuildWidgets();
                    });
            toggle.setTooltip(Tooltip.create(Component.translatable("modkeel.sessions.tooltip")));
            c.row(Component.translatable(on ? "modkeel.sessions.on" : "modkeel.sessions.off"), Keel.SOFT,
                    toggle, CrashScreen.whatIsSent(this, g));
        }
        Card alerts = body.addChild(new Card(w));
        alerts.row(Component.translatable("modkeel.settings.alerts"), Keel.SOFT,
                new KeelButton(100, alertsLabel(), b -> {
                    Client.setSpikeAlerts(!Client.spikeAlerts());
                    b.setMessage(alertsLabel());
                }));
        Card copies = body.addChild(new Card(w));
        Path chosen = Backups.chosenFolder(g.gameDir);
        Component where = Component.literal(chosen == null
                ? g.gameDir.toAbsolutePath().normalize().resolve("modkeel").resolve("backups").toString()
                : chosen.toString());
        Component changeLabel = Component.translatable("modkeel.settings.backups_change");
        Button change = new KeelButton(fit(changeLabel), changeLabel, b -> pickFolder(chosen));
        change.setTooltip(Tooltip.create(Component.translatable("modkeel.settings.backups_tip")));
        change.active = !moving;
        if (chosen == null) {
            copies.row(Component.translatable("modkeel.settings.backups", where), Keel.SOFT, change);
        } else {
            Component backLabel = Component.translatable("modkeel.settings.backups_default");
            Button back = new KeelButton(fit(backLabel), backLabel, b -> move(null));
            back.active = !moving;
            copies.row(Component.translatable("modkeel.settings.backups", where), Keel.SOFT, change, back);
        }
        Component note = moving ? Component.translatable("modkeel.settings.backups_moving") : folderNote;
        if (note != null) {
            copies.add(Text.in(note, copies.inner(), Keel.GRAY));
        }
        Card site = body.addChild(new Card(w));
        site.row(Component.translatable("modkeel.health.app"), Keel.SOFT,
                new KeelButton(100, Component.translatable("modkeel.more.open"),
                        b -> Compat.openLink(this, HealthScreen.APP_URL)));
    }

    /** A button as wide as its label needs ("Carpeta del juego"), at least 70. */
    private int fit(Component label) {
        return Math.max(70, font.width(label) + 12);
    }

    /** The system's folder picker (LWJGL's tinyfd, in every Minecraft) off the render thread. */
    private void pickFolder(Path chosen) {
        String start = (chosen != null ? chosen : g.gameDir.toAbsolutePath().normalize()).toString();
        String title = Component.translatable("modkeel.settings.backups_pick").getString();
        Thread t = new Thread(() -> {
            String picked;
            try {
                picked = TinyFileDialogs.tinyfd_selectFolderDialog(title, start);
            } catch (Throwable e) {
                Log.warn("cannot show the folder picker", e);
                return;
            }
            if (picked != null && !picked.isBlank()) {
                move(Paths.get(picked));
            }
        }, "modkeel-folder-picker");
        t.setDaemon(true);
        t.start();
    }

    private void move(Path folder) {
        moving = true;
        folderNote = null;
        refresh();
        Thread t = new Thread(() -> {
            try {
                Path to = Backups.moveTo(g.gameDir, folder);
                folderNote = Component.translatable("modkeel.settings.backups_moved", to.toString());
            } catch (Exception e) {
                Log.warn("cannot move the backups to " + folder, e);
                folderNote = Component.translatable("modkeel.settings.backups_failed",
                        String.valueOf(e.getMessage()));
            }
            moving = false;
            refresh();
        }, "modkeel-move-backups");
        t.setDaemon(true);
        t.start();
    }

    /** Rebuild this screen on the render thread, if it is still the one shown. */
    private void refresh() {
        minecraft.execute(() -> {
            if (showing) {
                rebuildWidgets();
            }
        });
    }

    @Override
    protected void init() {
        super.init();
        showing = true;
    }

    @Override
    public void removed() {
        super.removed();
        showing = false;
    }

    static Component alertsLabel() {
        return Component.translatable(Client.spikeAlerts() ? "modkeel.spikes.alerts_on"
                                                           : "modkeel.spikes.alerts_off");
    }
}
