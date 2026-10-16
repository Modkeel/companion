package modkeel.companion.game;

import modkeel.companion.core.Snapshots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * On the world loading screen: how far the backup of the world has got, once it takes long
 * enough to notice (the first backup of a big world copies all of it). The backup runs on the
 * server thread while the client keeps drawing this screen.
 */
final class BackupBar extends Canvas {
    /** A backup shorter than this goes by without a word. */
    static final long SHOW_AFTER_MS = 500;
    private static final int BAR_W = 200;
    private static final int BAR_H = 7;

    BackupBar(int width, int height) {
        super(width, height);
    }

    /** The screens a backup can run under. */
    static boolean on(Screen screen) {
        return screen instanceof LevelLoadingScreen;
    }

    @Override
    protected void paint(Paint p, int mouseX, int mouseY) {
        draw(p, getWidth(), getHeight());
    }

    /** A card near the bottom of a screen this size: the line, and the bar under it. */
    static void draw(Paint p, int width, int height) {
        Snapshots.Progress now = Common.guardian == null ? null : Common.guardian.backingUp;
        if (now == null || System.currentTimeMillis() - now.started < SHOW_AFTER_MS) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        FormattedCharSequence line = Component.translatable("modkeel.backup.progress", now.world,
                now.percent(), Snapshots.Progress.size(now.done()),
                Snapshots.Progress.size(now.total)).getVisualOrderText();
        int barW = Math.min(BAR_W, width - 40);
        int w = Math.min(width - 8, Math.max(font.width(line), barW) + 16);
        int h = 8 + 9 + 4 + BAR_H + 7;
        int x = (width - w) / 2;
        int y = height - h - 12;
        Keel.card(p, x, y, w, h);
        p.text(font, line, (width - font.width(line)) / 2, y + 8, Keel.TEXT, true);
        Keel.bar(p, (width - barW) / 2, y + 8 + 9 + 4, barW, BAR_H, new int[] {now.percent()});
    }
}
