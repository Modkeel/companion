package modkeel.companion.game;

import java.util.function.Consumer;

import modkeel.companion.core.Diagnosis;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Log;
import modkeel.companion.core.Plan;
import modkeel.companion.core.Spikes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Client side: the crash screen before the title screen, the Modkeel button, lag spike alerts. */
public final class Client {
    /** A spike this long gets a toast while playing. */
    static final long TOAST_MS = Long.getLong("modkeel.spikes.toast_ms", 500);
    /** Joining a world always stutters: no toast in its first seconds. */
    static final long JOIN_QUIET_MS = 20_000;
    static final long TOAST_EVERY_MS = 60_000;

    private static boolean crashHandled;
    /** What the title screen opens, held until resources load: 1.20.1 shows the title screen
     *  mid-load, and a screen built then keeps raw translation keys. */
    private static Runnable whenLoaded;
    private static boolean loaded;
    private static Spikes.Watch frames;
    private static volatile long inWorldSince;
    private static long lastToast;

    private Client() {
    }

    public static void init() {
        Common.guardian.startup();
        StartingCrash.offer();
        Common.spikes.listener = Client::onSpike;
        Tour.start();
    }

    /** Called by the loader at the end of every client tick, on the render thread. */
    public static void clientTick(Minecraft mc) {
        if (!loaded && !Compat.loading(mc)) {
            loaded = true;
            Common.guardian.loaded();
        }
        if (whenLoaded != null && !Compat.loading(mc)) {
            Runnable r = whenLoaded;
            whenLoaded = null;
            r.run();
        }
        if (frames == null) {
            frames = Common.spikes.watch(Spikes.Where.FRAME);
        }
        if (mc.level == null) {
            frames.pause();
            inWorldSince = 0;
            return;
        }
        if (inWorldSince == 0) {
            inWorldSince = System.currentTimeMillis();
        }
        frames.beat();
    }

    static boolean spikeAlerts() {
        return !"off".equals(Common.guardian.state.get("spikeAlerts", "on"));
    }

    static void setSpikeAlerts(boolean on) {
        Common.guardian.state.set("spikeAlerts", on ? "on" : "off");
        Common.guardian.state.save();
    }

    /** On the sampler thread: a toast for a spike worth mentioning, never more than one a minute. */
    private static void onSpike(Spikes.Spike s) {
        long now = System.currentTimeMillis();
        long joined = inWorldSince;
        if (s.millis < TOAST_MS || joined == 0 || now - joined < JOIN_QUIET_MS
                || now - lastToast < TOAST_EVERY_MS || !spikeAlerts()) {
            return;
        }
        lastToast = now;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            Log.info("lag spike toast: " + s);
            Compat.toast(mc, Component.translatable("modkeel.spikes.toast_title",
                    SpikeScreen.seconds(s.millis)), SpikeScreen.mostly(s));
            if ("spikes".equals(System.getProperty("modkeel.test.screen"))) {
                Log.info("showing the lag spike screen");
                Compat.setScreen(mc, new SpikeScreen(null));
            }
        });
    }

    /** Called by the loader after a screen is initialised; `add` puts a widget on it. */
    public static void afterScreenInit(Minecraft mc, Screen screen, int width,
                                       Consumer<AbstractWidget> add) {
        if (screen instanceof PauseScreen) {
            add.accept(Button.builder(Component.translatable("modkeel.spikes.button"),
                            b -> Compat.setScreen(mc, new SpikeScreen(screen)))
                    .bounds(width - 104, 4, 100, 20)
                    .tooltip(Tooltip.create(Component.translatable("modkeel.spikes.tooltip")))
                    .build());
            return;
        }
        if (!(screen instanceof TitleScreen)) {
            return;
        }
        Guardian g = Common.guardian;
        add.accept(Button.builder(Component.literal("Modkeel"),
                        b -> Compat.setScreen(mc, new HealthScreen(screen, g)))
                .bounds(width - 84, 4, 80, 20)
                .tooltip(Tooltip.create(Component.translatable("modkeel.button.tooltip")))
                .build());
        String test = System.getProperty("modkeel.test.screen");
        if (g.crash != null && !crashHandled) {
            crashHandled = true;
            // the crash screen already introduced Modkeel: no welcome on the start after the fix
            if (g.state.get("welcomed", null) == null) {
                g.state.set("welcomed", System.currentTimeMillis());
                g.state.save();
            }
            String auto = System.getProperty("modkeel.test.autofix");
            if (auto != null) {
                whenLoaded = () -> autofix(mc, g, auto);
            } else {
                whenLoaded = () -> {
                    Log.info("showing the crash screen");
                    Compat.setScreen(mc, new CrashScreen(screen, g));
                    if ("report".equals(test)) {
                        Log.info("showing the report screen");
                        Compat.setScreen(mc, new ReportScreen(Compat.screen(mc),
                                g.reports.readable(g.reports.crash(g.crash, null))));
                    }
                };
            }
        } else if ("health".equals(test) && !crashHandled) {
            crashHandled = true;
            whenLoaded = () -> {
                Log.info("showing the health screen");
                Compat.setScreen(mc, new HealthScreen(screen, g));
            };
        } else if (!crashHandled && (test == null ? g.state.get("welcomed", null) == null
                                                  : test.equals("welcome"))) {
            crashHandled = true;
            g.state.set("welcomed", System.currentTimeMillis());
            g.state.save();
            whenLoaded = () -> {
                Log.info("showing the welcome screen");
                Compat.setScreen(mc, new WelcomeScreen(screen));
            };
        }
    }

    /** Apply a plan and close the game: loaded mods only change on the next start. */
    static void applyAndQuit(Minecraft mc, Guardian g, Plan plan) {
        g.apply(plan, Common.selfJar());
        mc.stop();
    }

    /** Same, for a fix picked after a crash: Modkeel then watches whether it holds. */
    static void applyCrashFixAndQuit(Minecraft mc, Guardian g, Plan plan) {
        g.applyCrashFix(plan, Common.selfJar());
        mc.stop();
    }

    /** Test hook: pick a fix without clicking (-Dmodkeel.test.autofix=disable|revert). */
    private static void autofix(Minecraft mc, Guardian g, String action) {
        Diagnosis.Suspect s = g.crash.top();
        boolean share = Boolean.getBoolean("modkeel.test.share");
        if (action.equals("disable") && s != null && s.file != null) {
            Log.info("autofix: disabling " + s.id);
            g.shareCrash(share, g.reports.disableFix(s));
            applyCrashFixAndQuit(mc, g, g.disablePlan(s));
        } else if (action.equals("revert") && g.canRevert()) {
            Log.info("autofix: reverting to the last good set");
            g.shareCrash(share, g.reports.revertFix());
            applyCrashFixAndQuit(mc, g, g.revertPlan());
        } else {
            Log.info("autofix: nothing to do for " + action);
        }
    }

    static Screen confirm(Screen back, Component title, Component message, Runnable yes) {
        return confirm(back, title, message, CommonComponents.GUI_YES, yes);
    }

    /** The yes button names the action ("Disable and close"), never just "Yes". */
    static Screen confirm(Screen back, Component title, Component message, Component action,
                          Runnable yes) {
        Minecraft mc = Minecraft.getInstance();
        return new ConfirmScreen(ok -> {
            if (ok) {
                yes.run();
            } else {
                Compat.setScreen(mc, back);
            }
        }, title, message, action, CommonComponents.GUI_CANCEL);
    }
}
