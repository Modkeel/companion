package modkeel.companion.core;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Minecraft's own bitmap font for the crash window, drawn in GUI pixels. The game copies it
 * from its jar into {@code modkeel/window-font/<version>/} ({@link #export}); nothing of
 * Mojang's ships in this mod. Characters the bitmaps lack (Chinese: the game's unifont) and a
 * missing copy fall back to a vector font.
 */
public final class PixelFont {
    public static final int LINE = 10;
    private static final String[][] SOURCES = {
        // 1.21+: the default font references these; 1.20.1 inlines them in font/default.json
        {"assets/minecraft/font/include/space.json", "space.json"},
        {"assets/minecraft/font/include/default.json", "default.json"},
    };
    private static final String OLD = "assets/minecraft/font/default.json";

    private record Glyph(int w, int h, boolean[] px, float scale, int ascent, int advance) {
    }

    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Map<Integer, Integer> spaces = new HashMap<>();
    private final Font vector;
    private final Font dialog = new Font(Font.DIALOG, Font.PLAIN, 8);
    private final FontRenderContext frc = new FontRenderContext(null, false, false);

    private PixelFont(Font vector) {
        this.vector = vector;
    }

    /** True when the game's bitmaps were found: text looks as in the game. */
    public boolean pixel() {
        return !glyphs.isEmpty();
    }

    /** Copies the font from the jar of {@code cl}, once per version; false when it is not there. */
    public static boolean export(Class<?> cl, Path dir) {
        if (Files.exists(dir.resolve("ok"))) {
            return true;
        }
        try {
            Files.createDirectories(dir);
            List<byte[]> jsons = new ArrayList<>();
            boolean found = false;
            for (String[] s : SOURCES) {
                byte[] b = read(cl, s[0]);
                if (b != null) {
                    Files.write(dir.resolve(s[1]), b);
                    jsons.add(b);
                    found |= s[1].equals("default.json");
                }
            }
            if (!found) {
                byte[] b = read(cl, OLD);
                if (b == null) {
                    return false;
                }
                Files.write(dir.resolve("default.json"), b);
                jsons.add(b);
            }
            for (byte[] json : jsons) {
                for (Object o : providers(json)) {
                    Map<?, ?> p = (Map<?, ?>) o;
                    if ("bitmap".equals(p.get("type"))) {
                        String file = texture((String) p.get("file"));
                        byte[] png = read(cl, file);
                        if (png != null) {
                            Files.write(dir.resolve(local(file)), png);
                        }
                    }
                }
            }
            Files.writeString(dir.resolve("ok"), "");
            return true;
        } catch (IOException | RuntimeException e) {
            Log.warn("cannot copy the game's font for the crash window", e);
            return false;
        }
    }

    /** The game's font from {@code dir}, or only {@code vector} when it is missing. */
    public static PixelFont load(Path dir, Font vector) {
        PixelFont f = new PixelFont(vector);
        if (!Files.exists(dir.resolve("ok"))) {
            return f;
        }
        try {
            for (String name : new String[] {"space.json", "default.json"}) {
                Path p = dir.resolve(name);
                if (Files.exists(p)) {
                    f.add(dir, providers(Files.readAllBytes(p)));
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("cannot read the game's font: plain text in the crash window", e);
            f.glyphs.clear();
        }
        return f;
    }

    private void add(Path dir, List<Object> providers) throws IOException {
        for (Object o : providers) {
            Map<?, ?> p = (Map<?, ?>) o;
            Object type = p.get("type");
            if ("space".equals(type)) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) p.get("advances")).entrySet()) {
                    spaces.putIfAbsent(((String) e.getKey()).codePointAt(0),
                            ((Number) e.getValue()).intValue());
                }
            } else if ("bitmap".equals(type)) {
                Path png = dir.resolve(local(texture((String) p.get("file"))));
                if (!Files.exists(png)) {
                    continue;
                }
                BufferedImage img = ImageIO.read(png.toFile());
                List<?> rows = (List<?>) p.get("chars");
                int cols = ((String) rows.get(0)).codePointCount(0, ((String) rows.get(0)).length());
                int cw = img.getWidth() / cols;
                int ch = img.getHeight() / rows.size();
                int height = p.containsKey("height") ? ((Number) p.get("height")).intValue() : 8;
                int ascent = ((Number) p.get("ascent")).intValue();
                float scale = (float) height / ch;
                for (int r = 0; r < rows.size(); r++) {
                    int[] cps = ((String) rows.get(r)).codePoints().toArray();
                    for (int c = 0; c < cps.length; c++) {
                        if (cps[c] == 0 || glyphs.containsKey(cps[c]) || spaces.containsKey(cps[c])) {
                            continue;
                        }
                        boolean[] px = new boolean[cw * ch];
                        int right = 0;
                        for (int y = 0; y < ch; y++) {
                            for (int x = 0; x < cw; x++) {
                                if ((img.getRGB(c * cw + x, r * ch + y) >>> 24) != 0) {
                                    px[y * cw + x] = true;
                                    right = Math.max(right, x + 1);
                                }
                            }
                        }
                        glyphs.put(cps[c], new Glyph(cw, ch, px, scale, ascent,
                                (int) (0.5f + right * scale) + 1));
                    }
                }
            }
        }
    }

    public int width(String s) {
        float w = 0;
        for (int cp : s.codePoints().toArray()) {
            w += advance(cp);
        }
        return (int) Math.ceil(w);
    }

    private float advance(int cp) {
        Glyph g = glyphs.get(cp);
        if (g != null) {
            return g.advance;
        }
        Integer sp = spaces.get(cp);
        if (sp != null) {
            return sp;
        }
        if (Character.isSpaceChar(cp)) {
            // also the narrow no-break space Java puts before "PM"
            return 4;
        }
        return (float) fallback(cp).getStringBounds(new String(Character.toChars(cp)), frc).getWidth();
    }

    private Font fallback(int cp) {
        return vector.canDisplay(cp) ? vector : dialog;
    }

    /** Draws {@code s} with its top at {@code y}, in GUI pixels, with the game's drop shadow. */
    public void draw(Graphics2D g, String s, int x, int y, int argb, boolean shadow) {
        if (shadow) {
            int a = argb & 0xFF000000;
            int rgb = (argb & 0xFCFCFC) >> 2;
            draw(g, s, x + 1, y + 1, a | rgb, false);
        }
        Color color = new Color(argb, true);
        g.setColor(color);
        float cx = x;
        for (int cp : s.codePoints().toArray()) {
            Glyph gl = glyphs.get(cp);
            if (gl != null) {
                int top = y + 7 - gl.ascent;
                for (int py = 0; py < gl.h; py++) {
                    for (int px = 0; px < gl.w; px++) {
                        if (gl.px[py * gl.w + px]) {
                            int x0 = (int) (cx + px * gl.scale);
                            int y0 = (int) (top + py * gl.scale);
                            int x1 = (int) (cx + (px + 1) * gl.scale);
                            int y1 = (int) (top + (py + 1) * gl.scale);
                            g.fillRect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
                        }
                    }
                }
            } else if (!spaces.containsKey(cp) && !Character.isSpaceChar(cp)) {
                Font f = fallback(cp);
                g.setFont(f);
                g.drawString(new String(Character.toChars(cp)), cx, y + 7.5f);
            }
            cx += advance(cp);
        }
    }

    /** Lines no wider than {@code width}: breaks at spaces, and at "\n". */
    public List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ", -1)) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && width(next) > width) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
                while (width(line.toString()) > width && line.length() > 1) {
                    // one word wider than the line (or no spaces, as in Chinese): cut it
                    int cut = line.length() - 1;
                    while (cut > 1 && width(line.substring(0, cut)) > width) {
                        cut--;
                    }
                    out.add(line.substring(0, cut));
                    line = new StringBuilder(line.substring(cut));
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    private static String texture(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
        return "assets/" + ns + "/textures/" + path;
    }

    /** "assets/minecraft/textures/font/ascii.png" -> "ascii.png" */
    private static String local(String file) {
        return file.substring(file.lastIndexOf('/') + 1);
    }

    /** Through the class, not its loader: on (Neo)Forge the game is a module. */
    private static byte[] read(Class<?> cl, String name) throws IOException {
        try (InputStream in = cl.getResourceAsStream("/" + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static List<Object> providers(byte[] json) {
        Object root = new Json(new String(json, StandardCharsets.UTF_8)).value();
        Object p = root instanceof Map<?, ?> m ? m.get("providers") : null;
        return p instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    /** Just enough JSON for font definitions: objects, arrays, strings, numbers, literals. */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                skip();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    skip();
                    String k = string();
                    skip();
                    i++; // ':'
                    m.put(k, value());
                    skip();
                    if (s.charAt(i++) == '}') {
                        return m;
                    }
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                i++;
                skip();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    skip();
                    if (s.charAt(i++) == ']') {
                        return l;
                    }
                }
            }
            if (c == '"') {
                return string();
            }
            int start = i;
            while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            String t = s.substring(start, i);
            return switch (t) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> Double.parseDouble(t);
            };
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
