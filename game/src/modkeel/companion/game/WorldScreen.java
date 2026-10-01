package modkeel.companion.game;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import modkeel.companion.core.Backups;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Log;
import modkeel.companion.core.Msg;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** One world's copies, newest first: when, why, and a button to go back to each. */
final class WorldScreen extends Page {
    private final Guardian g;
    private final String world;

    WorldScreen(Screen back, Guardian g, String world) {
        super(Component.literal(world), back);
        this.g = g;
        this.world = world;
    }

    @Override
    protected void body(Stack body, int w) {
        List<Path> zips = Backups.list(g.gameDir, world);
        Card c = body.addChild(new Card(w));
        int in = c.inner();
        for (Path zip : zips) {
            String when = time(zip);
            KeelButton go = new KeelButton(110, Component.translatable("modkeel.world.go"),
                    b -> confirm(zip, when));
            Stack row = c.add(Stack.horizontal(8));
            row.defaultCellSetting().alignVerticallyMiddle();
            Stack lines = row.addChild(Stack.vertical(2));
            int left = in - go.getWidth() - 8;
            lines.addChild(Text.in(Component.literal(when), left, Keel.TEXT));
            String reason = Backups.reason(zip);
            if (reason != null) {
                lines.addChild(Text.in(CrashScreen.msg(reason), left, Keel.GRAY));
            }
            row.addChild(go);
        }
        body.addChild(Text.loose(Component.translatable("modkeel.world.keep", Guardian.KEEP_BACKUPS),
                w, Keel.GRAY));
    }

    private String time(Path zip) {
        return CrashScreen.backupTime(zip.getFileName().toString(), minecraft.options.languageCode);
    }

    private void confirm(Path zip, String when) {
        open(Client.confirm(this, Component.translatable("modkeel.restore.confirm_title", when),
                Component.translatable("modkeel.restore.points", world),
                Component.translatable("modkeel.restore.go"), () -> {
                    try {
                        Path aside = Backups.restore(g.gameDir, g.gameDir.resolve("saves"), world, zip);
                        g.state.set("lastAction", Msg.of("modkeel.action.restored", world, when));
                        g.state.setList("lastResult", List.of("ok" + (aside == null ? "" : " " + aside)));
                        Log.info("restored " + world + " from " + zip);
                    } catch (IOException e) {
                        g.state.set("lastAction", Msg.of("modkeel.action.restored", world, when));
                        g.state.setList("lastResult", List.of("fail " + e));
                        Log.warn("restore of " + world + " failed", e);
                    }
                    g.state.save();
                    open(this);
                }));
    }
}
