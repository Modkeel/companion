package modkeel.companion.game;

import java.util.Locale;

import modkeel.companion.core.Gpu;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The graphics card the game draws on, read once the window is up. When a faster card sits
 * unused (a laptop starting Java on the integrated graphics) or no driver is installed, the
 * home screen says so and, on Windows, switches Java to the fast card.
 */
final class Graphics {
    static Gpu gpu;
    private static boolean read;
    /** Windows was asked for the fast card during this start: it applies on the next. */
    private static boolean switchedNow;

    private Graphics() {
    }

    /** On the render thread, where the game's GL context is current. */
    static void detect(Minecraft mc, Guardian g) {
        if (read) {
            return;
        }
        read = true;
        String renderer = System.getProperty("modkeel.test.gpu", renderer());
        if (renderer == null || renderer.isBlank()) {
            return;
        }
        gpu = Gpu.of(renderer);
        Log.info("graphics: " + gpu.vendor + " " + gpu.kind
                + (gpu.unused == null ? "" : ", unused " + gpu.unused + " card"));
        g.state.set("gpuVendor", gpu.vendor);
        g.state.set("gpuKind", gpu.kind);
        g.state.save();
        if (shown(g)) {
            Compat.toast(mc, title(), Component.translatable("modkeel.gpu.toast"));
        }
    }

    /** GL_RENDERER through LWJGL, which no loader remaps: the same call on every version. */
    private static String renderer() {
        try {
            return (String) Class.forName("org.lwjgl.opengl.GL11")
                    .getMethod("glGetString", int.class).invoke(null, 0x1F01);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.info("graphics card not read: " + e);
            return null;
        }
    }

    static boolean shown(Guardian g) {
        return gpu != null && (gpu.unused != null || gpu.kind.equals("software"))
                && !"off".equals(g.state.get("gpuNotice", "on"));
    }

    static Component title() {
        return Component.translatable(gpu.unused != null ? "modkeel.gpu.unused_title"
                : "modkeel.gpu.software_title");
    }

    private static Component maker(String vendor) {
        return Component.literal(vendor.toUpperCase(Locale.ROOT));  // NVIDIA, AMD
    }

    /** The home screen card; nothing when the game already draws on its best card. */
    static void card(HealthScreen page, Stack body, int w, Guardian g) {
        if (!shown(g)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Card c = body.addChild(new Card(w, Keel.EDGE_WARN));
        c.add(new Heading(title(), c.inner()));
        if (gpu.unused == null) {
            c.add(Text.in(Component.translatable("modkeel.gpu.software_text", gpu.renderer),
                    c.inner(), Keel.SOFT));
        } else {
            Component fast = maker(gpu.unused);
            c.add(Text.in(Component.translatable("modkeel.gpu.unused_text", gpu.renderer, fast),
                    c.inner(), Keel.SOFT));
            String exe = Gpu.javaExe();
            String asked = g.state.get("gpuSwitched", "");
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && exe != null) {
                if (switchedNow) {
                    c.add(Text.in(Component.translatable("modkeel.gpu.switched", fast), c.inner(), Keel.AQUA));
                } else if (asked.equals(exe)) {
                    c.add(Text.in(Component.translatable("modkeel.gpu.still", fast), c.inner(), Keel.GRAY));
                } else {
                    c.row(Component.translatable("modkeel.gpu.switch_hint"), Keel.GRAY,
                            new KeelButton(130, Component.translatable("modkeel.gpu.switch", fast),
                                    b -> page.open(Client.confirm(page,
                                            Component.translatable("modkeel.gpu.switch_title", fast),
                                            Component.translatable("modkeel.gpu.switch_text", fast),
                                            Component.translatable("modkeel.gpu.switch_do"),
                                            () -> switchCard(mc, page, g, exe)))));
                }
            } else if (gpu.unused.equals("nvidia")) {
                c.add(Text.in(Component.translatable("modkeel.gpu.linux_nvidia"), c.inner(), Keel.GRAY));
            } else {
                c.add(Text.in(Component.translatable("modkeel.gpu.linux_amd"), c.inner(), Keel.GRAY));
            }
        }
        c.add(new KeelButton(110, Component.translatable("modkeel.gpu.hide"), b -> {
            g.state.set("gpuNotice", "off");
            g.state.save();
            page.open(new HealthScreen(page.back, g));
        }));
    }

    private static void switchCard(Minecraft mc, HealthScreen page, Guardian g, String exe) {
        if (Gpu.preferFastCard(exe)) {
            Log.info("asked Windows to start Java on the fast graphics card");
            switchedNow = true;
            g.state.set("gpuSwitched", exe);
            g.state.save();
            Compat.setScreen(mc, new HealthScreen(page.back, g));
        } else {
            Compat.setScreen(mc, Client.confirm(new HealthScreen(page.back, g),
                    Component.translatable("modkeel.gpu.switch_failed_title"),
                    Component.translatable("modkeel.gpu.switch_failed", exe),
                    Component.translatable("gui.ok"), () -> Compat.setScreen(mc, new HealthScreen(page.back, g))));
        }
    }
}
