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
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

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
        layout = new HeaderAndFooterLayout(this, 33, 36);
        Compat.titleHeader(layout, title, font);
        int w = Math.min(width - 40, 380);
        Stack body = Stack.vertical(6);
        body.defaultCellSetting().alignHorizontallyCenter();

        ModSet now = g.current();
        ModSet good = g.lastGood();
        Component status;
        if (good == null) {
            status = Component.translatable("modkeel.health.no_good", now.jars.size());
        } else {
            List<String> changed = new ArrayList<>();
            g.changedSinceGood().forEach(j -> changed.add(j.file));
            status = changed.isEmpty()
                    ? Component.translatable("modkeel.health.same_as_good", now.jars.size())
                    : Component.translatable("modkeel.health.changed", now.jars.size(), changed.size(),
                            CrashScreen.clip(String.join(", ", changed), 200));
        }
        if (g.crash != null) {
            // Continue on the crash screen is not final: the diagnosis and its fixes stay here
            Stack row = body.addChild(Stack.horizontal(8));
            row.defaultCellSetting().alignVerticallyMiddle();
            row.addChild(Compat.maxWidth(new StringWidget(Component.translatable("modkeel.health.crash")
                    .withStyle(ChatFormatting.RED), font), w - 108));
            row.addChild(Button.builder(Component.translatable("modkeel.health.crash_open"),
                    b -> Compat.setScreen(minecraft, new CrashScreen(this, g))).width(100).build());
        }
        body.addChild(CrashScreen.text(status, w));

        List<String> last = g.state.getList("lastResult");
        String action = g.state.get("lastAction", "");
        if (!last.isEmpty() && !action.isEmpty()) {
            boolean failed = last.stream().anyMatch(l -> l.startsWith("fail"));
            body.addChild(CrashScreen.text(Component.translatable("modkeel.health.last_action",
                    CrashScreen.msg(action), Component.translatable(failed ? "modkeel.health.last_fail"
                            : "modkeel.health.last_ok")).withStyle(failed ? ChatFormatting.GOLD : ChatFormatting.GRAY), w));
        }

        body.addChild(new StringWidget(Component.translatable("modkeel.health.backups")
                .withStyle(ChatFormatting.YELLOW), font));
        List<String> worlds = Backups.worlds(g.gameDir);
        if (worlds.isEmpty()) {
            body.addChild(CrashScreen.text(Component.translatable("modkeel.health.no_backups"), w));
        }
        for (String world : worlds.subList(0, Math.min(worlds.size(), MAX_ROWS))) {
            List<Path> zips = Backups.list(g.gameDir, world);
            if (zips.isEmpty()) {
                continue;
            }
            Stack row = body.addChild(Stack.horizontal(8));
            row.defaultCellSetting().alignVerticallyMiddle();
            row.addChild(Compat.maxWidth(new StringWidget(Component.translatable("modkeel.health.backup_row",
                    CrashScreen.clip(world, 24), when(zips.get(0)), zips.size()), font), w - 108));
            row.addChild(Button.builder(Component.translatable("modkeel.health.restore"),
                    b -> confirmRestore(world, zips.get(0))).width(100).build());
        }

        List<String> disabled = g.disabledByUs();
        if (!disabled.isEmpty()) {
            body.addChild(new StringWidget(Component.translatable("modkeel.health.disabled")
                    .withStyle(ChatFormatting.YELLOW), font));
        }
        for (String f : disabled.subList(0, Math.min(disabled.size(), MAX_ROWS))) {
            Stack row = body.addChild(Stack.horizontal(8));
            row.defaultCellSetting().alignVerticallyMiddle();
            row.addChild(Compat.maxWidth(new StringWidget(Component.literal(CrashScreen.clip(f.replaceAll("(\\.\\d+)?\\.disabled$", ""), 40)), font), w - 108));
            row.addChild(Button.builder(Component.translatable("modkeel.health.enable"),
                    b -> Compat.setScreen(minecraft, Client.confirm(this,
                            Component.translatable("modkeel.health.enable"),
                            Component.translatable("modkeel.restart_note"),
                            () -> Client.applyAndQuit(minecraft, g, g.enablePlan(f)))))
                    .width(100).build());
        }

        if (g.rules.size > 0) {
            addLab(body, w);
        }

        List<Outcomes.Fix> fixes = g.outcomes.fixes;
        if (!fixes.isEmpty()) {
            body.addChild(new StringWidget(Component.translatable("modkeel.health.fixes")
                    .withStyle(ChatFormatting.YELLOW), font));
        }
        for (Outcomes.Fix f : fixes.subList(0, Math.min(fixes.size(), MAX_ROWS))) {
            body.addChild(CrashScreen.text(Component.translatable("modkeel.health.fix_row", CrashScreen.msg(f.title),
                    Component.translatable("modkeel.outcome." + f.status.name().toLowerCase(java.util.Locale.ROOT),
                            f.ticks / 72000, f.ticks / 1200 % 60)), w));
        }
        if (g.crash == null && CrashScreen.stuck(g)) {
            body.addChild(CrashScreen.text(Component.translatable("modkeel.cta").withStyle(ChatFormatting.AQUA), w));
        }
        scroll = layout.addToContents(new Scroll(minecraft, body, Compat.contentHeight(layout)));

        Stack footer = layout.addToFooter(Stack.horizontal(8));
        Button revert = footer.addChild(Button.builder(Component.translatable("modkeel.revert"),
                b -> Compat.setScreen(minecraft, Client.confirm(this,
                        Component.translatable("modkeel.revert.confirm_title"),
                        CrashScreen.revertMessage(g),
                        () -> Client.applyAndQuit(minecraft, g, g.revertPlan()))))
                .width(120).build());
        revert.active = g.canRevert();
        if (g.lastGood() == null) {
            revert.setTooltip(Tooltip.create(Component.translatable("modkeel.revert.none")));
        }
        footer.addChild(Button.builder(Component.translatable("modkeel.health.app"),
                b -> Compat.openLink(this, APP_URL)).width(120).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(120).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    /** What the Modkeel lab knows about the installed mods on this Minecraft version. */
    private void addLab(Stack body, int w) {
        body.addChild(new StringWidget(Component.translatable("modkeel.lab.title", g.mcVersion)
                .withStyle(ChatFormatting.YELLOW), font));
        List<Guardian.LabMod> mods = g.labMods();
        List<String> verified = new ArrayList<>();
        List<String> otherVersion = new ArrayList<>();
        for (Guardian.LabMod m : mods) {
            if (m.rule != null && m.rule.status == Rules.Status.CRASHES) {
                body.addChild(CrashScreen.text(Component.translatable("modkeel.lab.crashes", m.name, g.mcVersion)
                        .withStyle(ChatFormatting.RED), w));
            } else if (m.rule != null) {
                verified.add(m.name + " (" + Component.translatable("modkeel.lab." + m.rule.status.name()
                        .toLowerCase(java.util.Locale.ROOT)).getString() + ")");
            } else if (m.otherVersion != null) {
                otherVersion.add(m.name + " (" + m.otherVersion + ")");
            }
        }
        for (Rules.Rule r : g.labClashes()) {
            body.addChild(CrashScreen.text(Component.translatable("modkeel.lab.clash", r.a, r.b)
                    .withStyle(ChatFormatting.RED), w));
        }
        body.addChild(CrashScreen.text(verified.isEmpty()
                ? Component.translatable("modkeel.lab.verified_none", mods.size())
                : Component.translatable("modkeel.lab.verified", verified.size(), mods.size(),
                        CrashScreen.clip(String.join(", ", verified), 300)), w));
        if (!otherVersion.isEmpty()) {
            body.addChild(CrashScreen.text(Component.translatable("modkeel.lab.other_version",
                    CrashScreen.clip(String.join(", ", otherVersion), 300)).withStyle(ChatFormatting.GOLD), w));
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
