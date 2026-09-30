package modkeel.companion.game;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import modkeel.companion.core.Spikes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * One lag spike as a card: its length with where/when tags, a bar of whose code ran, the
 * legend of that bar, and what it waited on.
 */
final class SpikeCard extends Canvas {
    private static final int PAD = 6;
    private static final int BAR_H = 9;
    private static final int SWATCH = 7;
    private static final int LINE = 10;

    private final Font font;
    private final FormattedCharSequence length;
    private final Component[] tags;
    private final int[] percents;
    /** Legend items: text, and the segment index or -1 for the rest. */
    private final List<FormattedCharSequence> legend = new ArrayList<>();
    private final List<Integer> legendColor = new ArrayList<>();
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private final int lineColor;

    SpikeCard(Spikes.Spike s, int width, String languageCode) {
        super(width, 0);
        Minecraft mc = Minecraft.getInstance();
        font = mc.font;
        Locale locale = Locale.forLanguageTag(languageCode.replace('_', '-'));
        length = Component.translatable("modkeel.spikes.seconds", SpikeScreen.seconds(s.millis))
                .getVisualOrderText();
        tags = new Component[] {
                Component.translatable("modkeel.spikes.tag." + s.where.name().toLowerCase(Locale.ROOT)),
                Component.literal(new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(s.at)))};
        percents = new int[s.shares.size()];
        int used = 0;
        for (int i = 0; i < percents.length; i++) {
            Spikes.Share share = s.shares.get(i);
            percents[i] = share.percent;
            used += share.percent;
            legend.add(Component.literal(capitalize(SpikeScreen.shortName(share).getString(), locale)
                                         + " " + share.percent + "%").getVisualOrderText());
            legendColor.add(i);
        }
        if (used < 100) {
            legend.add(Component.translatable("modkeel.spikes.other", 100 - used).getVisualOrderText());
            legendColor.add(-1);
        }
        int inner = width - 2 * PAD;
        Component causes = SpikeScreen.causes(s);
        if (causes != null) {
            lines.addAll(font.split(causes, inner));
        }
        Component crowds = SpikeScreen.crowds(s, languageCode);
        if (crowds != null) {
            lines.addAll(font.split(crowds, inner));
        }
        lineColor = SpikeScreen.heavy(s) ? Keel.YELLOW : Keel.GRAY;
        resize(width, PAD + Keel.PILL_H + 4 + BAR_H + 4 + legendRows(inner) * LINE
                  + lines.size() * LINE + PAD - 2);
    }

    private int legendRows(int inner) {
        int rows = 1;
        int x = 0;
        for (FormattedCharSequence item : legend) {
            int w = SWATCH + 3 + font.width(item) + 8;
            if (x > 0 && x + w > inner) {
                rows++;
                x = 0;
            }
            x += w;
        }
        return legend.isEmpty() ? 0 : rows;
    }

    private static String capitalize(String s, Locale locale) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(locale) + s.substring(1);
    }

    @Override
    protected void paint(Paint p, int mouseX, int mouseY) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        Keel.card(p, x, y, w, getHeight());
        int cx = x + PAD;
        int cy = y + PAD;
        p.text(font, length, cx, cy + 2, Keel.TEXT, true);
        int tx = cx + font.width(length) + 6;
        for (Component tag : tags) {
            tx += Keel.pill(p, font, tx, cy, tag) + 3;
        }
        cy += Keel.PILL_H + 4;
        Keel.bar(p, cx, cy, w - 2 * PAD, BAR_H, percents);
        cy += BAR_H + 4;
        int lx = cx;
        int inner = w - 2 * PAD;
        for (int i = 0; i < legend.size(); i++) {
            FormattedCharSequence item = legend.get(i);
            int iw = SWATCH + 3 + font.width(item) + 8;
            if (lx > cx && lx - cx + iw > inner) {
                lx = cx;
                cy += LINE;
            }
            int color = legendColor.get(i);
            if (color < 0) {
                Keel.restSwatch(p, lx, cy, SWATCH);
            } else {
                Keel.swatch(p, lx, cy, SWATCH, Keel.SEGMENTS[color % Keel.SEGMENTS.length]);
            }
            p.text(font, item, lx + SWATCH + 3, cy, Keel.SOFT, true);
            lx += iw;
        }
        if (!legend.isEmpty()) {
            cy += LINE;
        }
        for (FormattedCharSequence line : lines) {
            p.text(font, line, cx, cy, lineColor, true);
            cy += LINE;
        }
    }
}
