package modkeel.companion.game;

import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;
import modkeel.companion.core.Log;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ImageWidget;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.FaviconTexture;

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

    /** A world's icon.png as a widget, or null. The texture goes into {@code owned} to close later. */
    public static AbstractWidget worldIcon(Minecraft mc, String world, Path png, int size,
                                           List<AutoCloseable> owned) {
        if (!Files.isRegularFile(png)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(png)) {
            NativeImage img = NativeImage.read(in);
            FaviconTexture tex = FaviconTexture.forWorld(mc.getTextureManager(), world);
            tex.upload(img);
            owned.add(tex);
            return new ImageWidget(size, size, tex.textureLocation());
        } catch (IOException | RuntimeException e) {
            Log.warn("cannot show the icon of " + world, e);
            return null;
        }
    }

    /** A toast in the corner that replaces the previous one. */
    public static void toast(Minecraft mc, Component title, Component message) {
        SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION, title, message);
    }

    /**
     * 1.20.1 screens draw no background unless they ask: without one, the last frame stays under
     * the screen (the loading overlay, text from before a scroll). A widget drawn first paints it.
     */
    public static void background(Screen screen, java.util.function.Consumer<AbstractWidget> add) {
        add.accept(new Canvas(screen.width, screen.height) {
            @Override
            protected void renderWidget(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float delta) {
                screen.renderBackground(g);
            }

            @Override
            protected void paint(Paint p, int mouseX, int mouseY) {
            }
        });
    }

    /** A checkbox sized to its label; read it back with {@code selected()}. */
    public static Checkbox checkbox(Component label, Font font, boolean selected) {
        return new Checkbox(0, 0, font.width(label) + 24, 20, label, selected);
    }
}
