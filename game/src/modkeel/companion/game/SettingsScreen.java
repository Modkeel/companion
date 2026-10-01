package modkeel.companion.game;

import modkeel.companion.core.Guardian;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** What the player can switch: sharing play sessions, lag spike alerts; and the website. */
final class SettingsScreen extends Page {
    private final Guardian g;

    SettingsScreen(Screen back, Guardian g) {
        super(Component.translatable("modkeel.home.settings"), back);
        this.g = g;
    }

    @Override
    protected void body(Stack body, int w) {
        if (g.reports.enabled()) {
            boolean on = g.shareSessions();
            Card c = body.addChild(new Card(w));
            Button toggle = new KeelButton(100, Component.translatable(on ? "modkeel.sessions.stop" : "modkeel.sessions.start"),
                    b -> {
                        g.setShareSessions(!on);
                        open(this);
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
        Card site = body.addChild(new Card(w));
        site.row(Component.translatable("modkeel.health.app"), Keel.SOFT,
                new KeelButton(100, Component.translatable("modkeel.more.open"),
                        b -> Compat.openLink(this, HealthScreen.APP_URL)));
    }

    static Component alertsLabel() {
        return Component.translatable(Client.spikeAlerts() ? "modkeel.spikes.alerts_on"
                                                           : "modkeel.spikes.alerts_off");
    }
}
