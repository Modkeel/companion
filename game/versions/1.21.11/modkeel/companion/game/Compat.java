package modkeel.companion.game;

import java.net.URI;
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
            return ImageWidget.texture(size, size, tex.textureLocation(), size, size);
        } catch (IOException | RuntimeException e) {
            Log.warn("cannot show the icon of " + world, e);
            return null;
        }
    }

    /** A toast in the corner that replaces the previous one. */
    public static void toast(Minecraft mc, Component title, Component message) {
        SystemToast.addOrUpdate(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, message);
    }

    /** Screens draw their own background from 1.20.2 on: nothing to add. */
    public static void background(Screen screen, java.util.function.Consumer<AbstractWidget> add) {
    }

    /** A checkbox sized to its label; read it back with {@code selected()}. */
    public static Checkbox checkbox(Component label, Font font, boolean selected) {
        return Checkbox.builder(label, font).selected(selected).build();
    }
}
