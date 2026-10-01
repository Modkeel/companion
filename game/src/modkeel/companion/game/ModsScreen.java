package modkeel.companion.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import modkeel.companion.core.Guardian;
import modkeel.companion.core.ModSet;
import modkeel.companion.core.Outcomes;
import modkeel.companion.core.Rules;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** "Your mods": how they stand against the last set that worked, and what Modkeel changed. */
final class ModsScreen extends Page {
    private static final int MAX_ROWS = 4;

    private final Guardian g;

    ModsScreen(Screen back, Guardian g) {
        super(Component.translatable("modkeel.home.mods"), back);
        this.g = g;
    }

    @Override
    protected void body(Stack body, int w) {
        mods(body, w);
        List<String> disabled = g.disabledByUs();
        if (!disabled.isEmpty()) {
            Card c = body.addChild(new Card(w));
            c.add(new Heading(Component.translatable("modkeel.health.disabled"), c.inner()));
            for (String f : disabled.subList(0, Math.min(disabled.size(), MAX_ROWS))) {
                c.row(Component.literal(CrashScreen.clip(f.replaceAll("(\\.\\d+)?\\.disabled$", ""), 40)),
                        Keel.SOFT, new KeelButton(100, Component.translatable("modkeel.health.enable"),
                                b -> open(Client.confirm(this,
                                        Component.translatable("modkeel.health.enable"),
                                        Component.translatable("modkeel.restart_note"),
                                        () -> Client.applyAndQuit(minecraft, g, g.enablePlan(f))))));
            }
        }
        if (g.rules.size > 0) {
            lab(body, w);
        }
        List<Outcomes.Fix> fixes = g.outcomes.fixes;
        if (!fixes.isEmpty()) {
            Card c = body.addChild(new Card(w));
            c.add(new Heading(Component.translatable("modkeel.health.fixes"), c.inner()));
            for (Outcomes.Fix f : fixes.subList(0, Math.min(fixes.size(), MAX_ROWS))) {
                c.add(Text.in(Component.translatable("modkeel.health.fix_row", CrashScreen.msg(f.title),
                        Component.translatable("modkeel.outcome." + f.status.name().toLowerCase(Locale.ROOT),
                                f.ticks / 72000, f.ticks / 1200 % 60)), c.inner(), Keel.SOFT));
            }
        }
    }

    @Override
    protected void footer(Stack footer) {
        Button revert = footer.addChild(new KeelButton(200, Component.translatable("modkeel.revert"),
                b -> open(Client.confirm(this, Component.translatable("modkeel.revert.confirm_title"),
                        CrashScreen.revertMessage(g), Component.translatable("modkeel.revert.go"),
                        () -> Client.applyAndQuit(minecraft, g, g.revertPlan())))));
        revert.active = g.canRevert();
        if (g.lastGood() == null) {
            revert.setTooltip(Tooltip.create(Component.translatable("modkeel.revert.none")));
        }
        footer.addChild(new KeelButton(100, CommonComponents.GUI_BACK, b -> onClose()));
    }

    /** The mod count, how it stands against the last good set, and the changed files. */
    private void mods(Stack body, int w) {
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
    }

    /** What the Modkeel lab knows about the installed mods on this Minecraft version. */
    private void lab(Stack body, int w) {
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
                        .toLowerCase(Locale.ROOT)).getString() + ")");
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
}
