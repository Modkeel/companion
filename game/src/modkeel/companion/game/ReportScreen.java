package modkeel.companion.game;

import modkeel.companion.core.Sent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** "What is sent": the report in plain words, and the exact data the server will get. */
public final class ReportScreen extends Page {
    private final String payload;
    private final String note;
    private final boolean exact;

    public ReportScreen(Screen back, String payload) {
        this(back, payload, "modkeel.share.note");
    }

    /** {@code note} is the translation key that says when this report leaves. */
    public ReportScreen(Screen back, String payload, String note) {
        this(back, payload, note, false);
    }

    private ReportScreen(Screen back, String payload, String note, boolean exact) {
        super(Component.translatable("modkeel.share.what"), back);
        this.payload = payload;
        this.note = note;
        this.exact = exact;
    }

    @Override
    protected void body(Stack body, int w) {
        body.addChild(Text.loose(Component.translatable(note), w, Keel.GRAY));
        Card c = body.addChild(new Card(w));
        int dash = font.width("- ");
        for (Sent.Line line : Sent.lines(payload)) {
            // a wrapped line starts under the text, not under the dash
            Stack row = c.add(Stack.horizontal(0));
            row.addChild(Text.in(Component.literal("-"), dash, Keel.SOFT));
            row.addChild(Text.in(Component.translatable(line.key, (Object[]) line.args), c.inner() - dash, Keel.SOFT));
        }
        if (exact) {
            Card data = body.addChild(new Card(w));
            data.add(Text.in(Component.literal(payload), data.inner(), Keel.GRAY));
        }
    }

    @Override
    protected void footer(Stack footer) {
        footer.addChild(new KeelButton(150, Component.translatable(exact ? "modkeel.sent.hide" : "modkeel.sent.show"),
                b -> Compat.setScreen(minecraft, new ReportScreen(back, payload, note, !exact))));
        super.footer(footer);
    }
}
