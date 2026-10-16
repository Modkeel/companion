package modkeel.companion.core;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
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
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The crash told right away: when a crash closes the game, a window of its own (a separate
 * process, like {@link Apply}) shows what the next start would, so the player does not have to
 * start the game again to learn what happened. It waits for the game to exit, reads the crash
 * report the game wrote, and offers the same fix as the crash screen. A fix applied here is
 * recorded, so the next start does not ask again; closing it changes nothing, and the crash
 * screen still shows at the next start. It is drawn like the crash screen, in GUI pixels with
 * the game's own font ({@link PixelFont}).
 * {@code java -cp window.jar modkeel.companion.core.CrashWindow <pid> <game dir> <mc> <loader> <loader version>}
 */
public final class CrashWindow {
    /** The crash screen at GUI scale 2 on the game's default window. */
    static final int SCREEN = 436;
    static final int HEADER = 33;
    static final int GUTTER = 10;
    static final int PANEL_MARGIN = 8;
    static final int INSET_X = 4;
    static final int INSET_Y = 10;
    static final int SCROLL_RESERVE = 10;
    static final int BODY = SCREEN - 2 * (GUTTER + PANEL_MARGIN + SCROLL_RESERVE + INSET_X);
    static final int PAD = 6;

    static final int BLACK = 0xFF000000;
    static final int TEXT = 0xFFFFFFFF;
    static final int SOFT = 0xFFE0E0E0;
    static final int GRAY = 0xFFC6C6C6;
    static final int YELLOW = 0xFFFFFF55;
    static final int EDGE_OK = 0xFF5F8F4E;
    static final int EDGE_WARN = 0xFFB08A3A;
    /** Behind everything: the game's darkened menu background. */
    static final int BASE = 0xFF121212;

    private final Guardian g;
    private final Diagnosis d;
    private final Lang lang;
    private final Path selfJar;
    private final PixelFont font;
    private final JFrame frame = new JFrame("Modkeel");
    private final View view = new View();
    private boolean share;
    /** The last crash check when the window opened: another start changes it. */
    private long checked;

    private String title = "";
    private final List<Block> body = new ArrayList<>();
    private final List<List<Widget>> footer = new ArrayList<>();
    private int scale = 2;
    private int height;
    private int mouseX = -1;
    private int mouseY = -1;

    private CrashWindow(Guardian g, Diagnosis d, Lang lang, Path selfJar, PixelFont font) {
        this.g = g;
        this.d = d;
        this.lang = lang;
        this.selfJar = selfJar;
        this.font = font;
        this.share = g.shareChoice();
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

    /** Where the game copies its font for the window: one folder per Minecraft version. */
    public static Path fontDir(Guardian g) {
        return g.home.resolve("window-font").resolve(g.mcVersion);
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
        Font vector = font("BricolageGrotesque-Regular.ttf");
        PixelFont pf = PixelFont.load(fontDir(g),
                vector == null ? new Font(Font.DIALOG, Font.PLAIN, 8) : vector.deriveFont(8f));
        if (!pf.pixel()) {
            Log.info("no copy of the game's font: the crash window uses its own");
        }
        Path self = Paths.get(CrashWindow.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        SwingUtilities.invokeAndWait(() -> new CrashWindow(g, d, lang, self, pf).show());
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
        try (InputStream in = CrashWindow.class.getResourceAsStream("/assets/modkeel/icon.png")) {
            if (in != null) {
                frame.setIconImage(ImageIO.read(in));
            }
        } catch (IOException e) {
            // the system's default icon
        }
        frame.setContentPane(view);
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

    /** What happened, the world, the likely cause, and the fix: the crash screen's layout. */
    private void main() {
        reset(lang.get("modkeel.crash.title"));
        rescue();
        cause();
        body.add(new Lines(lang.get("modkeel.window.later"), BODY, GRAY, true));
        Diagnosis.Suspect s = d.top();
        if (s != null && s.file != null) {
            footer.add(List.of(new Button(304, lang.get("modkeel.crash.fix", s.name), true,
                    this::confirmDisable)));
        } else if (g.canRevert()) {
            footer.add(List.of(new Button(304, lang.get("modkeel.crash.fix_revert"), true,
                    this::confirmRevert)));
        }
        footer.add(List.of(new Button(304, lang.get("modkeel.crash.not_now"), false, frame::dispose)));
        if (g.reports.enabled()) {
            footer.add(List.of(new Check(lang.get("modkeel.share.checkbox"))));
        }
        done();
    }

    /** As the game's confirm screen: the question, what happens, the action and Back. */
    private void confirmDisable() {
        Diagnosis.Suspect s = d.top();
        reset(lang.get("modkeel.disable.confirm_title", s.name));
        int in = BODY - 2 * PAD;
        List<Block> lines = new ArrayList<>();
        lines.add(new Lines(lang.get("modkeel.window.points"), in, SOFT, false));
        List<String> deps = g.dependents(s.id);
        if (!deps.isEmpty()) {
            lines.add(new Lines(lang.get("modkeel.disable.dependents", String.join(", ", deps)),
                    in, YELLOW, false));
        }
        body.add(new Card(0, null, lines));
        footer.add(List.of(
                new Button(150, lang.get("modkeel.window.disable_go"), true,
                        () -> fix(g.disablePlan(s), g.reports.disableFix(s))),
                new Button(150, lang.get("modkeel.window.back"), false, this::main)));
        done();
    }

    private void confirmRevert() {
        List<String> names = new ArrayList<>();
        g.changedSinceGood().forEach(j -> names.add(j.file));
        String list = names.isEmpty() ? "-" : String.join(", ", names);
        reset(lang.get("modkeel.revert.confirm_title"));
        body.add(new Card(0, null, List.of(new Lines(lang.get("modkeel.window.revert_points",
                list.length() <= 300 ? list : list.substring(0, 299) + "…"), BODY - 2 * PAD, SOFT, false))));
        footer.add(List.of(
                new Button(150, lang.get("modkeel.window.revert_go"), true,
                        () -> fix(g.revertPlan(), g.reports.revertFix())),
                new Button(150, lang.get("modkeel.window.back"), false, this::main)));
        done();
    }

    /** The fix, recorded as if picked on the crash screen: the next start shows what it did. */
    private void fix(Plan plan, Reports.Fix f) {
        g.recordCrash(d);
        checked = g.state.getLong("lastCrashCheck", 0);
        g.shareCrash(share, f);
        g.applyCrashFix(plan, selfJar);
        reset(lang.msg(plan.title));
        body.add(new Lines(lang.get("modkeel.window.reopen"), BODY, SOFT, true));
        footer.add(List.of(new Button(304, lang.get("modkeel.window.close"), true, frame::dispose)));
        done();
    }

    // ---- parts ---------------------------------------------------------------------------

    /** The world that was open: it is still there, and how much of it Minecraft had saved. */
    private void rescue() {
        Guardian.WorldAtCrash world = g.worldAtCrash;
        if (world == null) {
            return;
        }
        BufferedImage icon = null;
        try {
            Path p = world.dir.resolve("icon.png");
            icon = Files.exists(p) ? ImageIO.read(p.toFile()) : null;
        } catch (IOException e) {
            // no icon: the lines take the whole card
        }
        int in = BODY - 2 * PAD - (icon == null ? 0 : 32 + 8);
        List<Block> lines = new ArrayList<>();
        lines.add(new Heading(lang.get("modkeel.rescue.title"), in));
        long minutes = world.unsavedMinutes();
        if (minutes == 0) {
            lines.add(new Lines(lang.get("modkeel.rescue.saved", world.name), in, SOFT, false));
        } else if (minutes > 0) {
            lines.add(new Lines(lang.get("modkeel.rescue.saved_min", world.name, minutes), in, SOFT, false));
        }
        if (world.backup != null) {
            lines.add(new Lines(lang.get("modkeel.rescue.copy",
                    backupTime(world.backup.getFileName().toString(), lang.code)), in, GRAY, false));
        }
        body.add(new Card(EDGE_OK, icon, lines));
    }

    /** The likely cause as the card's title, how sure in the words above it, then why. */
    private void cause() {
        Diagnosis.Suspect s = d.top();
        int in = BODY - 2 * PAD;
        List<Block> lines = new ArrayList<>();
        if (s != null) {
            lines.add(new Lines(lang.get("modkeel.crash.likely." + switch (d.confidence) {
                case HIGH -> "high";
                case MEDIUM -> "medium";
                default -> "low";
            }), in, GRAY, false));
            lines.add(new Heading(s.name, in));
            List<String> reasons = new ArrayList<>();
            for (String r : s.reasons) {
                reasons.add(lang.msg(r));
            }
            lines.add(new Lines(sentence(String.join("; ", reasons)), in, SOFT, false));
        } else {
            lines.add(new Heading(lang.get("modkeel.crash.unclear"), in));
            if (g.lastGood() != null) {
                int changed = g.changedSinceGood().size();
                lines.add(new Lines(changed > 0 ? lang.get("modkeel.crash.changed", changed)
                        : lang.get("modkeel.crash.unchanged"), in, SOFT, false));
            }
        }
        if (d.kind == Diagnosis.Kind.OUT_OF_MEMORY) {
            lines.add(new Lines(lang.get("modkeel.kind.out_of_memory"), in, GRAY, false));
        }
        body.add(new Card(EDGE_WARN, null, lines));
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

    // ---- layout --------------------------------------------------------------------------

    private void reset(String title) {
        this.title = title;
        body.clear();
        footer.clear();
    }

    /** Sizes the window to the view: GUI scale 2 when the screen has room for it. */
    private void done() {
        int bodyH = INSET_Y * 2 - 6;
        for (Block b : body) {
            bodyH += b.height() + 6;
        }
        height = HEADER + bodyH + footerHeight();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        scale = SCREEN * 2 <= screen.width && height * 2 <= screen.height - 40 ? 2 : 1;
        view.setPreferredSize(new Dimension(SCREEN * scale, height * scale));
        frame.pack();
        view.repaint();
    }

    private int footerHeight() {
        return 12 + 24 * footer.size();
    }

    /** Paints the view in GUI pixels (null: only hit-tests); returns the action under the mouse. */
    private Runnable draw(Paint p, int mx, int my) {
        int h = height;
        int foot = footerHeight();
        int mid = h - HEADER - foot;
        int px = GUTTER;
        int pw = SCREEN - 2 * GUTTER;
        if (p != null) {
            p.fill(0, 0, SCREEN, h, BASE);
            strip(p, 0, 0, SCREEN, HEADER, false);
            strip(p, 0, h - foot, SCREEN, foot, true);
            checks(p, 0, HEADER, px, mid);
            checks(p, px + pw, HEADER, SCREEN - px - pw, mid);
            panel(p, px, HEADER, pw, mid);
            int tw = font.width(title);
            font.draw(p.g, title, (SCREEN - tw) / 2, (HEADER - 9) / 2 + 1, TEXT, true);
            int y = HEADER + INSET_Y;
            int x = (SCREEN - BODY) / 2;
            for (Block b : body) {
                b.paint(p, x, y);
                y += b.height() + 6;
            }
        }
        Runnable hit = null;
        int y = h - foot + 8;
        for (List<Widget> row : footer) {
            int gap = row.size() > 1 ? 4 : 0;
            int rw = -gap;
            for (Widget w : row) {
                rw += w.width() + gap;
            }
            int x = (SCREEN - rw) / 2;
            for (Widget w : row) {
                boolean over = mx >= x && mx < x + w.width() && my >= y && my < y + 20;
                if (p != null) {
                    w.paint(p, x, y, over);
                }
                if (over) {
                    hit = w::click;
                }
                x += w.width() + gap;
            }
            y += 24;
        }
        return hit;
    }

    private final class View extends JComponent {
        View() {
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    mouseX = e.getX() / scale;
                    mouseY = e.getY() / scale;
                    setCursor(Cursor.getPredefinedCursor(draw(null, mouseX, mouseY) != null
                            ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    mouseX = -1;
                    mouseY = -1;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    Runnable r = draw(null, e.getX() / scale, e.getY() / scale);
                    if (r != null) {
                        r.run();
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        @Override
        protected void paintComponent(Graphics gr) {
            Graphics2D g2 = (Graphics2D) gr.create();
            g2.scale(scale, scale);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            draw(new Paint(g2), mouseX, mouseY);
            g2.dispose();
        }
    }

    // ---- blocks and widgets --------------------------------------------------------------

    private interface Block {
        int height();

        void paint(Paint p, int x, int y);
    }

    /** Wrapped text: left-aligned inside a card, centered between cards. */
    private final class Lines implements Block {
        private final List<String> lines;
        private final int width;
        private final int color;
        private final boolean centered;

        Lines(String text, int width, int color, boolean centered) {
            this.lines = font.wrap(text, width);
            this.width = width;
            this.color = color;
            this.centered = centered;
        }

        @Override
        public int height() {
            return Math.max(0, lines.size() * PixelFont.LINE - 1);
        }

        @Override
        public void paint(Paint p, int x, int y) {
            for (String line : lines) {
                int lx = centered ? x + (width - font.width(line)) / 2 : x;
                font.draw(p.g, line, lx, y, color, true);
                y += PixelFont.LINE;
            }
        }
    }

    /** A card's first line: the key fact in white. */
    private final class Heading implements Block {
        private static final int ROW = 14;
        private final List<String> lines;

        Heading(String text, int width) {
            this.lines = font.wrap(text, width);
        }

        @Override
        public int height() {
            return (lines.size() - 1) * ROW + 11;
        }

        @Override
        public void paint(Paint p, int x, int y) {
            for (String line : lines) {
                font.draw(p.g, line, x, y + 2, TEXT, true);
                y += ROW;
            }
        }
    }

    /** A Keel card: status edge on the left (0: none), an optional 32 px icon, lines 4 apart. */
    private static final class Card implements Block {
        private final int edge;
        private final BufferedImage icon;
        private final List<Block> lines;

        Card(int edge, BufferedImage icon, List<Block> lines) {
            this.edge = edge;
            this.icon = icon;
            this.lines = lines;
        }

        private int inner() {
            int h = -4;
            for (Block b : lines) {
                h += b.height() + 4;
            }
            return Math.max(h, icon == null ? 0 : 32);
        }

        @Override
        public int height() {
            return inner() + 2 * PAD;
        }

        @Override
        public void paint(Paint p, int x, int y) {
            bevel(p, x, y, BODY, height(), 0xEB343434, 0x1AFFFFFF, 0x73000000);
            if (edge != 0) {
                p.fill(x + 1, y + 1, x + 3, y + height() - 1, edge);
            }
            int lx = x + PAD;
            if (icon != null) {
                p.g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                p.g.drawImage(icon, lx, y + PAD, 32, 32, null);
                lx += 32 + 8;
            }
            int ly = y + PAD;
            for (Block b : lines) {
                b.paint(p, lx, ly);
                ly += b.height() + 4;
            }
        }
    }

    private interface Widget {
        int width();

        void paint(Paint p, int x, int y, boolean hovered);

        void click();
    }

    /** A Keel button: gray, or muted green for the fix; white outline when hovered. */
    private final class Button implements Widget {
        private final int width;
        private final String label;
        private final boolean green;
        private final Runnable action;

        Button(int width, String label, boolean green, Runnable action) {
            this.width = width;
            this.label = label;
            this.green = green;
            this.action = action;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public void click() {
            action.run();
        }

        @Override
        public void paint(Paint p, int x, int y, boolean hot) {
            int w = width;
            int h = 20;
            p.fill(x, y, x + w, y + h, hot ? TEXT : BLACK);
            p.fill(x + 1, y + 1, x + w - 1, y + h - 1,
                    green ? (hot ? 0xFF5A8049 : 0xFF4C6E3E) : hot ? 0xFF8C8C8C : 0xFF727272);
            int light = green ? (hot ? 0xFF8DB37C : 0xFF769C66) : hot ? 0xFFC8C8C8 : 0xFFB0B0B0;
            p.fill(x + 1, y + 1, x + w - 1, y + 2, light);
            p.fill(x + 1, y + 2, x + 2, y + h - 4, light);
            p.fill(x + w - 2, y + 2, x + w - 1, y + h - 4, green ? 0xFF3A5530 : 0xFF4A4A4A);
            p.fill(x + 1, y + h - 4, x + w - 1, y + h - 1, green ? 0xFF2C4224 : 0xFF3D3D3D);
            String text = label;
            if (font.width(text) > w - 8) {
                while (text.length() > 1 && font.width(text + "…") > w - 8) {
                    text = text.substring(0, text.length() - 1);
                }
                text += "…";
            }
            font.draw(p.g, text, x + (w - font.width(text)) / 2, y + (h - 4 - 8) / 2 + 1, TEXT, true);
        }
    }

    /** The game's checkbox: a 17 px box, its label beside it. */
    private final class Check implements Widget {
        private static final int BOX = 17;
        private final String label;

        Check(String label) {
            this.label = label;
        }

        @Override
        public int width() {
            return BOX + 4 + font.width(label);
        }

        @Override
        public void click() {
            share = !share;
            view.repaint();
        }

        @Override
        public void paint(Paint p, int x, int y, boolean hot) {
            int by = y + (20 - BOX) / 2;
            outline(p, x, by, BOX, BOX, hot ? TEXT : 0xFF6F6F6F);
            p.fill(x + 1, by + 1, x + BOX - 1, by + BOX - 1, 0xFF2B2B2B);
            if (share) {
                // a check mark, two strokes 2 px thick
                for (int i = 0; i < 4; i++) {
                    p.fill(x + 4 + i, by + 8 + i, x + 6 + i, by + 10 + i, TEXT);
                }
                for (int i = 0; i < 7; i++) {
                    p.fill(x + 8 + i, by + 10 - i, x + 10 + i, by + 12 - i, TEXT);
                }
            }
            font.draw(p.g, label, x + BOX + 4, by + (BOX - 9) / 2 + 1, SOFT, true);
        }
    }

    // ---- Keel paint (the game's Keel class, on Java2D) -----------------------------------

    /** Fills in GUI pixels, corners as the game's: (x1, y1) to (x2, y2), color ARGB. */
    static final class Paint {
        final Graphics2D g;

        Paint(Graphics2D g) {
            this.g = g;
        }

        void fill(int x1, int y1, int x2, int y2, int argb) {
            if (x2 > x1 && y2 > y1) {
                g.setColor(new Color(argb, true));
                g.fillRect(x1, y1, x2 - x1, y2 - y1);
            }
        }
    }

    static void bevel(Paint p, int x, int y, int w, int h, int fill, int light, int dark) {
        outline(p, x, y, w, h, BLACK);
        p.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        p.fill(x + 1, y + 1, x + w - 1, y + 2, light);
        p.fill(x + 1, y + 2, x + 2, y + h - 1, light);
        p.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, dark);
        p.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, dark);
    }

    static void outline(Paint p, int x, int y, int w, int h, int color) {
        p.fill(x, y, x + w, y + 1, color);
        p.fill(x, y + h - 1, x + w, y + h, color);
        p.fill(x, y + 1, x + 1, y + h - 1, color);
        p.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    static void panel(Paint p, int x, int y, int w, int h) {
        bevel(p, x, y, w, h, 0x60202020, 0x14FFFFFF, 0x40000000);
        stripes(p, x + 2, y + 2, w - 4, h - 4, false);
    }

    static void strip(Paint p, int x, int y, int w, int h, boolean footer) {
        p.fill(x, y, x + w, y + h, 0x66000000);
        stripes(p, x, y, w, h, true);
        int line = footer ? y : y + h - 1;
        p.fill(x, line, x + w, line + 1, BLACK);
    }

    static void stripes(Paint p, int x, int y, int w, int h, boolean mirrored) {
        int period = 10;
        for (int row = 0; row < h; row++) {
            int r = Math.floorMod(y + row, period);
            int shift = Math.floorMod((mirrored ? period - 1 - r : r) + x, period);
            for (int sx = x - shift; sx < x + w; sx += period) {
                p.fill(Math.max(x, sx), y + row, Math.min(x + w, sx + 5), y + row + 1, 0x0DFFFFFF);
            }
        }
    }

    static void checks(Paint p, int x, int y, int w, int h) {
        int c = 4;
        for (int cy = y - Math.floorMod(y, c); cy < y + h; cy += c) {
            for (int cx = x - Math.floorMod(x, c); cx < x + w; cx += c) {
                boolean light = ((cx / c) + (cy / c)) % 2 == 0;
                p.fill(Math.max(x, cx), Math.max(y, cy), Math.min(x + w, cx + c), Math.min(y + h, cy + c),
                        light ? 0x0FFFFFFF : 0x0F000000);
            }
        }
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
        BufferedImage im = new BufferedImage(SCREEN * scale, height * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = im.createGraphics();
        view.paint(g2);
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
