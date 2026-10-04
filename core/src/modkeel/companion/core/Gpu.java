package modkeel.companion.core;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Which graphics card draws the game, and whether a faster one sits unused next to it: laptops
 * often start Java on the integrated graphics, at half the frame rate or less. The renderer
 * name stays on this computer; reports only carry the maker and the kind.
 */
public final class Gpu {
    /** What the driver calls itself (GL_RENDERER): shown to the player, never sent. */
    public final String renderer;
    /** nvidia, amd, intel, apple, software or other. */
    public final String vendor;
    /** integrated, dedicated or software (no graphics driver: the processor draws). */
    public final String kind;
    /** The maker of a dedicated card that draws nothing while the integrated one does, or null. */
    public final String unused;

    private static final Pattern SOFTWARE = Pattern.compile(
            "llvmpipe|softpipe|swrast|basic render driver|gdi generic|software rasterizer");
    /** Intel Arc A/B cards are dedicated; "Intel Arc Graphics" with no model is a processor's. */
    private static final Pattern INTEL_CARD = Pattern.compile("arc(\\(tm\\))? [ab]\\d");
    /** Ryzen processors' graphics: "Radeon(TM) Graphics", "Radeon Vega 8 Graphics", "Radeon 780M". */
    private static final Pattern AMD_BUILT_IN = Pattern.compile(
            "radeon(\\(tm\\))?( vega)?( \\d+)? graphics|vega \\d+ graphics|radeon(\\(tm\\))? \\d{3}m\\b");
    private static final String GPU_PREFERENCES = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";

    Gpu(String renderer, Set<String> installed) {
        this.renderer = renderer;
        String r = renderer.toLowerCase(Locale.ROOT);
        vendor = vendor(r);
        kind = vendor.equals("software") ? "software" : integrated(r, vendor) ? "integrated" : "dedicated";
        String other = null;
        if (!kind.equals("dedicated")) {
            for (String v : installed) {
                if (!v.equals(vendor) && (v.equals("nvidia") || v.equals("amd"))) {
                    other = v;
                    break;
                }
            }
        }
        unused = other;
    }

    /** The card behind {@code renderer}, and the makers whose cards or drivers this computer has. */
    public static Gpu of(String renderer) {
        return new Gpu(renderer, installed(Paths.get(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32"), Paths.get("/sys/class/drm")));
    }

    static String vendor(String r) {
        if (SOFTWARE.matcher(r).find()) {
            return "software";
        }
        if (r.contains("nvidia") || r.contains("geforce") || r.contains("quadro")) {
            return "nvidia";
        }
        if (r.contains("radeon") || r.contains("amd") || r.startsWith("ati ")) {
            return "amd";
        }
        if (r.contains("intel")) {
            return "intel";
        }
        return r.contains("apple") ? "apple" : "other";
    }

    static boolean integrated(String r, String vendor) {
        switch (vendor) {
            case "intel": return !INTEL_CARD.matcher(r).find();
            case "amd": return AMD_BUILT_IN.matcher(r).find();
            case "apple": return true;
            default: return false;
        }
    }

    /**
     * Makers of the graphics hardware present. Windows: each maker's driver library in System32
     * (installed only with its card). Linux: the PCI vendor of every DRM card.
     */
    static Set<String> installed(Path system32, Path drm) {
        Set<String> out = new LinkedHashSet<>();
        if (Files.exists(system32.resolve("nvapi64.dll")) || Files.exists(system32.resolve("nvapi.dll"))) {
            out.add("nvidia");
        }
        if (Files.exists(system32.resolve("atiadlxx.dll")) || Files.exists(system32.resolve("atiadlxy.dll"))) {
            out.add("amd");
        }
        if (Files.isDirectory(drm)) {
            try (DirectoryStream<Path> cards = Files.newDirectoryStream(drm, "card*")) {
                for (Path card : cards) {
                    Path v = card.resolve("device").resolve("vendor");
                    if (!Files.exists(v)) {
                        continue;
                    }
                    switch (new String(Files.readAllBytes(v), StandardCharsets.US_ASCII).trim()) {
                        case "0x10de": out.add("nvidia"); break;
                        case "0x1002": out.add("amd"); break;
                        case "0x8086": out.add("intel"); break;
                        default: break;
                    }
                }
            } catch (IOException e) {
                Log.warn("cannot list graphics cards", e);
            }
        }
        return out;
    }

    /**
     * Ask Windows to start {@code javaExe} on the fast card ("High performance" in Settings,
     * System, Display, Graphics), as the player would by hand. Takes effect on the next start.
     */
    public static boolean preferFastCard(String javaExe) {
        try {
            Process p = new ProcessBuilder("reg", "add", GPU_PREFERENCES, "/v", javaExe, "/t", "REG_SZ",
                    "/d", "GpuPreference=2;", "/f").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            Log.warn("cannot set the graphics preference", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The Java running this game, which the graphics preference is keyed on. */
    public static String javaExe() {
        return ProcessHandle.current().info().command().orElse(null);
    }

    /** The computer's memory rounded to a size machines are sold with, so it names no one. */
    public static int ramGb() {
        long bytes;
        try {
            bytes = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                    .getTotalMemorySize();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
        return roundRam(bytes / (1024.0 * 1024 * 1024));
    }

    static int roundRam(double gb) {
        int[] sizes = {2, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128};
        if (gb <= 0) {
            return 0;
        }
        int best = sizes[0];
        for (int s : sizes) {
            if (Math.abs(s - gb) < Math.abs(best - gb)) {
                best = s;
            }
        }
        return best;
    }
}
