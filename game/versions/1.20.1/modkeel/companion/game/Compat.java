package modkeel.companion.game;

import java.nio.file.Path;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minecraft 1.20.1 versions of the calls that changed later. */
public final class Compat {
    private static final String ELLIPSIS = "…";

    private Compat() {
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    public static void openPath(Path path) {
        Util.getPlatform().openFile(path.toFile());
    }

    /** StringWidget draws its whole text here: shorten it to fit, with an ellipsis. */
    public static StringWidget maxWidth(StringWidget widget, int px) {
        Font font = Minecraft.getInstance().font;
        Component text = widget.getMessage();
        if (font.width(text) > px) {
            String cut = font.plainSubstrByWidth(text.getString(), px - font.width(ELLIPSIS));
            widget.setMessage(Component.literal(cut + ELLIPSIS).withStyle(text.getStyle()));
            widget.setWidth(px);
            widget.alignLeft();
        }
        return widget;
    }

    public static void titleHeader(HeaderAndFooterLayout layout, Component title, Font font) {
        layout.addToHeader(new StringWidget(title, font));
    }

    public static int contentHeight(HeaderAndFooterLayout layout) {
        return layout.getHeight() - layout.getHeaderHeight() - layout.getFooterHeight();
    }

    public static void openLink(Screen screen, String url) {
        ConfirmLinkScreen.confirmLinkNow(url, screen, true);
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
        button.onPress();
    }
}
