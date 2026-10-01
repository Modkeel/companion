package modkeel.companion.game;

import java.nio.file.Path;
import java.util.List;

import modkeel.companion.core.Backups;
import modkeel.companion.core.Guardian;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** "Your worlds": one card per world with copies; its copies are one step further. */
final class WorldsScreen extends Page {
    private final Guardian g;

    WorldsScreen(Screen back, Guardian g) {
        super(Component.translatable("modkeel.home.worlds"), back);
        this.g = g;
    }

    @Override
    protected void body(Stack body, int w) {
        List<String> worlds = Backups.worlds(g.gameDir);
        boolean any = false;
        for (String world : worlds) {
            List<Path> zips = Backups.list(g.gameDir, world);
            if (zips.isEmpty()) {
                continue;
            }
            any = true;
            Card c = body.addChild(new Card(w));
            c.add(new Heading(Component.literal(world), c.inner()));
            c.row(Component.translatable("modkeel.worlds.row", zips.size(),
                    CrashScreen.backupTime(zips.get(0).getFileName().toString(), minecraft.options.languageCode)),
                    Keel.GRAY, new KeelButton(100, Component.translatable("modkeel.home.worlds_go"),
                            b -> open(new WorldScreen(this, g, world))));
        }
        if (!any) {
            body.addChild(Text.loose(Component.translatable("modkeel.home.worlds_none"), w, Keel.SOFT));
        }
    }
}
