package modkeel.companion.game;

import java.net.URI;
import java.nio.file.Path;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minecraft 1.21.1 versions of the calls that changed later. */
public final class Compat {
    private Compat() {
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    public static void openPath(Path path) {
        Util.getPlatform().openPath(path);
    }

    /** StringWidget clips its text to its width with an ellipsis. */
    public static StringWidget maxWidth(StringWidget widget, int px) {
        if (widget.getWidth() > px) {
            widget.setWidth(px);
            widget.alignLeft();
        }
        return widget;
    }

    public static void titleHeader(HeaderAndFooterLayout layout, Component title, Font font) {
        layout.addTitleHeader(title, font);
    }

    public static int contentHeight(HeaderAndFooterLayout layout) {
        return layout.getContentHeight();
    }

    public static void openLink(Screen screen, String url) {
        ConfirmLinkScreen.confirmLinkNow(screen, URI.create(url));
    }
}
