package modkeel.companion.game;

import modkeel.companion.core.Guardian;
import modkeel.companion.core.Memory;
import net.minecraft.network.chat.Component;

/**
 * The home screen card for the memory the launcher gave Java: too little for this pack, nearly
 * all of the computer's, or a 32-bit Java. Says the size to set and where this launcher keeps it.
 */
final class MemoryCard {
    private MemoryCard() {
    }

    /** Read on each visit: the player may have changed it in the launcher and restarted. */
    static Memory read(Guardian g) {
        return Memory.of(g.gameDir, g.current().jars.size());
    }

    /** Hidden by the player for this verdict; a different problem shows again. */
    static boolean shown(Guardian g, Memory m) {
        return m.warns() && !("off:" + m.verdict).equals(g.state.get("memNotice", ""));
    }

    static Component title(Memory m) {
        return Component.translatable("modkeel.mem." + m.verdict + "_title");
    }

    static void card(HealthScreen page, Stack body, int w, Guardian g, Memory m) {
        Card c = body.addChild(new Card(w, Keel.EDGE_WARN));
        c.add(new Heading(title(m), c.inner()));
        Component text = switch (m.verdict) {
            case "low" -> Component.translatable("modkeel.mem.low_text", m.givenGb(),
                    g.current().jars.size(), m.adviseGb);
            case "high" -> Component.translatable("modkeel.mem.high_text", m.givenGb(), m.ramGb, m.adviseGb);
            default -> Component.translatable("modkeel.mem.bits32_text");
        };
        c.add(Text.in(text, c.inner(), Keel.SOFT));
        if (!m.verdict.equals("bits32")) {
            // Prism, CurseForge and the Modrinth App take megabytes; the others gigabytes
            c.add(Text.in(Component.translatable("modkeel.mem.how_" + m.launcher,
                    m.launcher.equals("vanilla") || m.launcher.equals("other")
                            ? String.valueOf(m.adviseGb) : String.valueOf(m.adviseGb * 1024)),
                    c.inner(), Keel.GRAY));
        }
        c.add(new KeelButton(110, Component.translatable("modkeel.gpu.hide"), b -> {
            g.state.set("memNotice", "off:" + m.verdict);
            g.state.save();
            page.open(new HealthScreen(page.back, g));
        }));
    }
}
