package modkeel.companion.game;

import java.net.URI;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Minecraft 1.21.11: the 26.x calls, except those that changed in 26.1. */
public final class Compat {
    private Compat() {
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    public static void openPath(Path path) {
        Util.getPlatform().openPath(path);
    }

    public static StringWidget maxWidth(StringWidget widget, int px) {
        widget.setMaxWidth(px);
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

    public static Screen screen(Minecraft mc) {
        return mc.screen;
    }

    /** A resource reload (the loading overlay) is covering the screen. */
    public static boolean loading(Minecraft mc) {
        return mc.getOverlay() != null;
    }

    /** Test tour: press a button as a click would. */
    public static void press(AbstractButton button) {
        button.onPress(new KeyEvent(257, 0, 0));
    }
}
