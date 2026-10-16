package modkeel.companion.core;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The crash told right away: when a crash closes the game, a window of its own (a separate
 * process, like {@link Apply}) shows what the next start would, so the player does not have to
 * start the game again to learn what happened. It waits for the game to exit, reads the crash
 * report the game wrote, and offers the same fix as the crash screen. A fix applied here is
 * recorded, so the next start does not ask again; closing it changes nothing, and the crash
 * screen still shows at the next start.
 * {@code java -cp window.jar modkeel.companion.core.CrashWindow <pid> <game dir> <mc> <loader> <loader version>}
 */
public final class CrashWindow {
    // not WIDTH: inside a Swing component that name is ImageObserver.WIDTH (1)
    static final int TEXT_WIDTH = 460;
    static final Color BG = new Color(0x262626);
    static final Color CARD = new Color(0x343434);
    static final Color TEXT = Color.WHITE;
    static final Color SOFT = new Color(0xE0E0E0);
    static final Color GRAY = new Color(0xC6C6C6);
    static final Color EDGE_OK = new Color(0x5F8F4E);
    static final Color EDGE_WARN = new Color(0xB08A3A);

    private final Guardian g;
    private final Diagnosis d;
    private final Lang lang;
    private final Path selfJar;
    private final Font regular;
    private final Font bold;
    private final JFrame frame = new JFrame("Modkeel");
    private final JPanel root = new JPanel();
    private JCheckBox shareBox;
    private boolean share;
    /** The last crash check when the window opened: another start changes it. */
    private long checked;

    private CrashWindow(Guardian g, Diagnosis d, Lang lang, Path selfJar) {
        this.g = g;
        this.d = d;
        this.lang = lang;
        this.selfJar = selfJar;
        this.share = g.shareChoice();
        String sample = lang.get("modkeel.crash.title");
        Font r = font("BricolageGrotesque-Regular.ttf");
        Font b = font("BricolageGrotesque-Bold.ttf");
        // Bricolage has Latin only: Russian and Chinese fall back to the system's UI font
        if (r == null || b == null || r.canDisplayUpTo(sample) != -1) {
            r = new Font(Font.DIALOG, Font.PLAIN, 1);
            b = new Font(Font.DIALOG, Font.BOLD, 1);
        }
        this.regular = r.deriveFont(14f);
        this.bold = b.deriveFont(15f);
    }

    /**
     * From the crashing game: start the window process. It copies this mod's jar first, since
     * the fix may move the original. Only the client calls it (Minecraft runs with
     * java.awt.headless=true, so the window process checks for a display, not the game); off
     * with -Dmodkeel.window=false.
     */
    public static void launch(Guardian g, Path selfJar) {
        if (!Boolean.parseBoolean(System.getProperty("modkeel.window", "true"))) {
            return;
        }
        try {
            Files.createDirectories(g.home);
            Path jar = g.home.resolve("window.jar");
            Files.copy(selfJar, jar, StandardCopyOption.REPLACE_EXISTING);
            String java = ProcessHandle.current().info().command()
                    .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
            List<String> cmd = new ArrayList<>(List.of(java, "-Xmx256m"));
            for (String p : new String[] {"modkeel.test.window", "modkeel.test.window_shot"}) {
                if (System.getProperty(p) != null) {
                    cmd.add("-D" + p + "=" + System.getProperty(p));
                }
            }
            cmd.addAll(List.of("-cp", jar.toString(), CrashWindow.class.getName(),
                    Long.toString(ProcessHandle.current().pid()), g.gameDir.toString(),
                    g.mcVersion, g.loader, g.loaderVersion));
            new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(g.home.resolve("window.log").toFile()).start();
            Log.info("crash window started");
        } catch (IOException e) {
            Log.warn("cannot start the crash window", e);
        }
    }

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path gameDir = Paths.get(args[1]);
        Optional<ProcessHandle> game = ProcessHandle.of(pid);
        if (game.isPresent()) {
            try {
                game.get().onExit().get(Apply.WAIT_MINUTES, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                Log.info("the game is still running: no crash window");
                return;
            }
        }
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Guardian g = new Guardian(gameDir);
        g.mcVersion = args.length > 2 ? args[2] : "";
        g.loader = args.length > 3 ? args[3] : "";
        g.loaderVersion = args.length > 4 ? args[4] : "";
        Diagnosis d = g.findCrash();
        if (d == null) {
            Log.info("no crash report after the game closed: no crash window");
            return;
        }
        Lang lang = Lang.load(gameDir);
        Path self = Paths.get(CrashWindow.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        SwingUtilities.invokeAndWait(() -> new CrashWindow(g, d, lang, self).show());
    }

    private void show() {
        checked = g.state.getLong("lastCrashCheck", 0);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                System.exit(0);
            }
        });
        root.setBackground(BG);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        frame.setContentPane(root);
        main();
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);
        frame.setVisible(true);
        frame.toFront();
        frame.setAlwaysOnTop(false);
        // the player started the game again: its own crash screen takes over
        Timer t = new Timer(2000, e -> {
            if (State.load(g.gameDir).getLong("lastCrashCheck", 0) != checked) {
                Log.info("the game started again: closing the crash window");
                frame.dispose();
            }
        });
        t.start();
        test();
    }

    /** What happened, the world, the likely cause, and the fix. */
    private void main() {
        reset();
        title(lang.get("modkeel.crash.title"));
        rescue();
        cause();
        Diagnosis.Suspect s = d.top();
        JPanel buttons = row();
        if (s != null && s.file != null) {
            buttons.add(button(lang.get("modkeel.crash.fix", s.name), true, this::confirmDisable));
        } else if (g.canRevert()) {
            buttons.add(button(lang.get("modkeel.crash.fix_revert"), true, this::confirmRevert));
        }
        buttons.add(button(lang.get("modkeel.crash.not_now"), false, frame::dispose));
        if (g.reports.enabled()) {
            shareBox = new JCheckBox(lang.get("modkeel.share.checkbox"), share);
            shareBox.setFont(regular);
            shareBox.setForeground(GRAY);
            shareBox.setOpaque(false);
            shareBox.setFocusPainted(false);
            shareBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            shareBox.addActionListener(e -> share = shareBox.isSelected());
            gap(10);
            root.add(shareBox);
        }
        gap(4);
        note(lang.get("modkeel.window.later"));
        done();
    }

    private void confirmDisable() {
        Diagnosis.Suspect s = d.top();
        reset();
        title(lang.get("modkeel.disable.confirm_title", s.name));
        text(lang.get("modkeel.window.points"), SOFT);
        List<String> deps = g.dependents(s.id);
        if (!deps.isEmpty()) {
            gap(8);
            text(lang.get("modkeel.disable.dependents", String.join(", ", deps)), new Color(0xFFFF55));
        }
        JPanel buttons = row();
        buttons.add(button(lang.get("modkeel.window.disable_go"), true,
                () -> fix(g.disablePlan(s), g.reports.disableFix(s))));
        buttons.add(button(lang.get("modkeel.window.back"), false, this::main));
        done();
    }

    private void confirmRevert() {
        List<String> names = new ArrayList<>();
        g.changedSinceGood().forEach(j -> names.add(j.file));
        String list = names.isEmpty() ? "-" : String.join(", ", names);
        reset();
        title(lang.get("modkeel.revert.confirm_title"));
        text(lang.get("modkeel.window.revert_points",
                list.length() <= 300 ? list : list.substring(0, 299) + "…"), SOFT);
        JPanel buttons = row();
        buttons.add(button(lang.get("modkeel.window.revert_go"), true,
                () -> fix(g.revertPlan(), g.reports.revertFix())));
        buttons.add(button(lang.get("modkeel.window.back"), false, this::main));
        done();
    }

    /** The fix, recorded as if picked on the crash screen: the next start shows what it did. */
    private void fix(Plan plan, Reports.Fix f) {
        g.recordCrash(d);
        checked = g.state.getLong("lastCrashCheck", 0);
        g.shareCrash(share, f);
        g.applyCrashFix(plan, selfJar);
        reset();
        title(lang.msg(plan.title));
        text(lang.get("modkeel.window.reopen"), SOFT);
        JPanel buttons = row();
        buttons.add(button(lang.get("modkeel.window.close"), true, frame::dispose));
        done();
    }

    // ---- parts ---------------------------------------------------------------------------

    private void rescue() {
        Guardian.WorldAtCrash world = g.worldAtCrash;
        if (world == null) {
            return;
        }
        Card card = card(EDGE_OK);
        card.heading(lang.get("modkeel.rescue.title"));
        long minutes = world.unsavedMinutes();
        if (minutes == 0) {
            card.line(lang.get("modkeel.rescue.saved", world.name), SOFT);
        } else if (minutes > 0) {
            card.line(lang.get("modkeel.rescue.saved_min", world.name, minutes), SOFT);
        }
        if (world.backup != null) {
            card.line(lang.get("modkeel.rescue.copy",
                    backupTime(world.backup.getFileName().toString(), lang.code)), GRAY);
        }
        gap(10);
    }

    private void cause() {
        Diagnosis.Suspect s = d.top();
        Card card = card(EDGE_WARN);
        if (s != null) {
            card.line(lang.get("modkeel.crash.likely." + switch (d.confidence) {
                case HIGH -> "high";
                case MEDIUM -> "medium";
                default -> "low";
            }), GRAY);
            card.heading(s.name);
            List<String> reasons = new ArrayList<>();
            for (String r : s.reasons) {
                reasons.add(lang.msg(r));
            }
            card.line(sentence(String.join("; ", reasons)), SOFT);
        } else {
            card.heading(lang.get("modkeel.crash.unclear"));
            if (g.lastGood() != null) {
                int changed = g.changedSinceGood().size();
                card.line(changed > 0 ? lang.get("modkeel.crash.changed", changed)
                        : lang.get("modkeel.crash.unchanged"), SOFT);
            }
        }
        if (d.kind == Diagnosis.Kind.OUT_OF_MEMORY) {
            card.line(lang.get("modkeel.kind.out_of_memory"), GRAY);
        }
    }

    /** "its code is in the error" -> "Its code is in the error." */
    static String sentence(String s) {
        if (s.isEmpty()) {
            return s;
        }
        String first = s.substring(0, s.offsetByCodePoints(0, 1));
        return first.toUpperCase(Locale.ROOT) + s.substring(first.length()) + (s.endsWith(".") ? "" : ".");
    }

    /** "20260930-184012-123.zip" -> a date in the game's language ("en_us"). */
    static String backupTime(String file, String language) {
        Locale locale = Locale.forLanguageTag(language.replace('_', '-'));
        try {
            return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(
                    new SimpleDateFormat("yyyyMMdd-HHmmss").parse(file.substring(0, 15)))
                    .replaceAll("\\.$", "");
        } catch (ParseException | IndexOutOfBoundsException e) {
            return file;
        }
    }

    private void reset() {
        root.removeAll();
        shareBox = null;
    }

    private void done() {
        root.revalidate();
        root.repaint();
        frame.pack();
    }

    private void gap(int h) {
        root.add(Box.createRigidArea(new Dimension(0, h)));
    }

    private void title(String s) {
        JTextArea t = area(s, TEXT, bold.deriveFont(19f), TEXT_WIDTH);
        root.add(t);
        gap(12);
    }

    private void text(String s, Color c) {
        root.add(area(s, c, regular, TEXT_WIDTH));
    }

    private void note(String s) {
        root.add(area(s, GRAY, regular.deriveFont(12.5f), TEXT_WIDTH));
    }

    private JPanel row() {
        gap(14);
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(p);
        return p;
    }

    private Card card(Color edge) {
        Card c = new Card(edge);
        root.add(c);
        return c;
    }

    private JTextArea area(String s, Color c, Font f, int width) {
        JTextArea t = new JTextArea(s);
        t.setFont(f);
        t.setForeground(c);
        t.setOpaque(false);
        t.setEditable(false);
        t.setFocusable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setBorder(null);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        t.setSize(width, Short.MAX_VALUE);
        Dimension pref = new Dimension(width, t.getPreferredSize().height);
        t.setPreferredSize(pref);
        t.setMaximumSize(pref);
        return t;
    }

    /** A card: dark panel, colored left edge, heading and lines. */
    private final class Card extends JPanel {
        private final Color edge;

        Card(Color edge) {
            this.edge = edge;
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 12));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        void heading(String s) {
            add(area(s, TEXT, bold, TEXT_WIDTH - 28));
            add(Box.createRigidArea(new Dimension(0, 3)));
        }

        void line(String s, Color c) {
            add(area(s, c, regular, TEXT_WIDTH - 28));
            add(Box.createRigidArea(new Dimension(0, 2)));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(TEXT_WIDTH, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics gr) {
            Graphics2D g2 = (Graphics2D) gr;
            g2.setColor(CARD);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(new Color(255, 255, 255, 26));
            g2.fillRect(0, 0, getWidth(), 1);
            g2.setColor(new Color(0, 0, 0, 115));
            g2.fillRect(0, getHeight() - 1, getWidth(), 1);
            g2.setColor(edge);
            g2.fillRect(0, 0, 4, getHeight());
        }
    }

    /** A Keel button: green for the fix, gray for the rest, bevelled like the game's. */
    private JComponent button(String label, boolean green, Runnable action) {
        JComponent b = new JComponent() {
            boolean hot;

            {
                setFont(green ? bold.deriveFont(14f) : regular);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hot = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hot = false;
                        repaint();
                    }

                    @Override
                    public void mouseClicked(MouseEvent e) {
                        action.run();
                    }
                });
            }

            @Override
            public Dimension getPreferredSize() {
                FontMetrics fm = getFontMetrics(getFont());
                return new Dimension(fm.stringWidth(label) + 36, 34);
            }

            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }

            @Override
            protected void paintComponent(Graphics gr) {
                Graphics2D g2 = (Graphics2D) gr;
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, w, h);
                g2.setColor(new Color(green ? (hot ? 0x5A8049 : 0x4C6E3E) : hot ? 0x8C8C8C : 0x727272));
                g2.fillRect(1, 1, w - 2, h - 2);
                g2.setColor(new Color(green ? (hot ? 0x8DB37C : 0x769C66) : hot ? 0xC8C8C8 : 0xB0B0B0));
                g2.setStroke(new BasicStroke(1));
                g2.fillRect(1, 1, w - 2, 1);
                g2.fillRect(1, 1, 1, h - 4);
                g2.setColor(new Color(green ? 0x2C4224 : 0x3D3D3D));
                g2.fillRect(1, h - 3, w - 2, 2);
                g2.setColor(TEXT);
                FontMetrics fm = g2.getFontMetrics(getFont());
                g2.setFont(getFont());
                g2.drawString(label, (w - fm.stringWidth(label)) / 2,
                        (h - 2 - fm.getHeight()) / 2 + fm.getAscent());
            }
        };
        b.setAlignmentY(Component.TOP_ALIGNMENT);
        JPanel cell = new JPanel();
        cell.setOpaque(false);
        cell.setLayout(new BoxLayout(cell, BoxLayout.X_AXIS));
        cell.add(b);
        cell.add(Box.createRigidArea(new Dimension(8, 0)));
        cell.setMaximumSize(cell.getPreferredSize());
        return cell;
    }

    private static Font font(String file) {
        try (InputStream in = CrashWindow.class.getResourceAsStream("/assets/modkeel/fonts/" + file)) {
            return in == null ? null : Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | java.awt.FontFormatException e) {
            Log.warn("cannot load " + file, e);
            return null;
        }
    }

    // ---- test hooks ----------------------------------------------------------------------

    /**
     * -Dmodkeel.test.window_shot=<png> saves the window as drawn; -Dmodkeel.test.window=disable
     * then takes the fix (the confirm and done views get their own shots) and closes it.
     */
    private void test() {
        String shot = System.getProperty("modkeel.test.window_shot");
        String act = System.getProperty("modkeel.test.window");
        if (shot == null && act == null) {
            return;
        }
        List<Runnable> steps = new ArrayList<>();
        steps.add(() -> shot(shot, ""));
        Diagnosis.Suspect s = d.top();
        if ("disable".equals(act) && s != null && s.file != null) {
            steps.add(this::confirmDisable);
            steps.add(() -> shot(shot, "-confirm"));
            steps.add(() -> fix(g.disablePlan(s), g.reports.disableFix(s)));
            steps.add(() -> shot(shot, "-done"));
        }
        steps.add(frame::dispose);
        Timer t = new Timer(1500, null);
        t.addActionListener(e -> {
            steps.remove(0).run();
            if (steps.isEmpty()) {
                t.stop();
            }
        });
        t.start();
    }

    private void shot(String file, String suffix) {
        if (file == null) {
            return;
        }
        BufferedImage im = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = im.createGraphics();
        root.paint(g2);
        g2.dispose();
        Path out = Paths.get(file.replaceAll("\\.png$", "") + suffix + ".png");
        try {
            ImageIO.write(im, "png", out.toFile());
            Log.info("window shot " + out);
        } catch (IOException e) {
            Log.warn("cannot write " + out, e);
        }
    }

    // ---- language ------------------------------------------------------------------------

    /** The mod's own lang files, in the language the game was set to (options.txt). */
    static final class Lang {
        final String code;
        private final Map<String, String> strings = new HashMap<>();
        private final Map<String, String> english = new HashMap<>();

        private Lang(String code) {
            this.code = code;
        }

        static Lang load(Path gameDir) {
            String code = "en_us";
            try {
                for (String line : Files.readAllLines(gameDir.resolve("options.txt"), StandardCharsets.UTF_8)) {
                    if (line.startsWith("lang:")) {
                        code = line.substring(5).trim().toLowerCase(Locale.ROOT);
                    }
                }
            } catch (IOException e) {
                // a first start has no options yet: English
            }
            Lang l = new Lang(code);
            l.english.putAll(read("en_us"));
            l.strings.putAll(read(code));
            return l;
        }

        private static Map<String, String> read(String code) {
            try (InputStream in = CrashWindow.class.getResourceAsStream("/assets/modkeel/lang/" + code + ".json")) {
                return in == null ? Map.of() : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                return Map.of();
            }
        }

        String get(String key, Object... args) {
            String f = strings.getOrDefault(key, english.getOrDefault(key, key));
            try {
                return args.length == 0 ? f : String.format(Locale.ROOT, f, args);
            } catch (java.util.IllegalFormatException e) {
                return f;
            }
        }

        /** A stored message: a translation key with its arguments, or plain text. */
        String msg(String m) {
            return Msg.isKey(m) ? get(Msg.key(m), (Object[]) Msg.args(m)) : m;
        }

        /** A flat JSON object of strings, as the lang files are. */
        static Map<String, String> parse(String json) {
            Map<String, String> out = new HashMap<>();
            int[] i = {json.indexOf('{') + 1};
            while (true) {
                String k = string(json, i);
                if (k == null) {
                    return out;
                }
                String v = string(json, i);
                if (v == null) {
                    return out;
                }
                out.put(k, v);
            }
        }

        /** The next "..." from i[0], unescaped; null when there is none. */
        private static String string(String s, int[] i) {
            int start = s.indexOf('"', i[0]);
            if (start < 0) {
                return null;
            }
            StringBuilder b = new StringBuilder();
            for (int j = start + 1; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '"') {
                    i[0] = j + 1;
                    return b.toString();
                }
                if (c == '\\' && j + 1 < s.length()) {
                    char e = s.charAt(++j);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            b.append((char) Integer.parseInt(s.substring(j + 1, j + 5), 16));
                            j += 4;
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            return null;
        }
    }
}
