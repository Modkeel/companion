package modkeel.companion.game;

import java.util.function.Consumer;

import modkeel.companion.core.Diagnosis;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Log;
import modkeel.companion.core.Plan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** Client side: the crash screen before the title screen, and the Modkeel button. */
public final class Client {
    private static boolean crashHandled;

    private Client() {
    }

    public static void init() {
        Common.guardian.startup();
        Tour.start();
    }

    /** Called by the loader after a screen is initialised; `add` puts a widget on it. */
    public static void afterScreenInit(Minecraft mc, Screen screen, int width,
                                       Consumer<AbstractWidget> add) {
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
            String auto = System.getProperty("modkeel.test.autofix");
            if (auto != null) {
                mc.execute(() -> autofix(mc, g, auto));
            } else {
                Log.info("showing the crash screen");
                mc.execute(() -> Compat.setScreen(mc, new CrashScreen(screen, g)));
            }
        } else if ("health".equals(test) && !crashHandled) {
            crashHandled = true;
            Log.info("showing the health screen");
            mc.execute(() -> Compat.setScreen(mc, new HealthScreen(screen, g)));
        } else if (!crashHandled && (test == null ? g.state.get("welcomed", null) == null
                                                  : test.equals("welcome"))) {
            crashHandled = true;
            g.state.set("welcomed", System.currentTimeMillis());
            g.state.save();
            Log.info("showing the welcome screen");
            mc.execute(() -> Compat.setScreen(mc, new WelcomeScreen(screen)));
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
        if (action.equals("disable") && s != null && s.file != null) {
            Log.info("autofix: disabling " + s.id);
            applyCrashFixAndQuit(mc, g, g.disablePlan(s));
        } else if (action.equals("revert") && g.canRevert()) {
            Log.info("autofix: reverting to the last good set");
            applyCrashFixAndQuit(mc, g, g.revertPlan());
        } else {
            Log.info("autofix: nothing to do for " + action);
        }
    }

    static Screen confirm(Screen back, Component title, Component message, Runnable yes) {
        Minecraft mc = Minecraft.getInstance();
        return new ConfirmScreen(ok -> {
            if (ok) {
                yes.run();
            } else {
                Compat.setScreen(mc, back);
            }
        }, title, message);
    }
}
