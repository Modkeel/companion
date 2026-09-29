package modkeel.companion.core;

import java.util.Arrays;

/**
 * A message kept as a translation key and its arguments ("modkeel.action.disabled" + a mod
 * name), so the screens show it in the player's language even when it was saved in another
 * session. Text that is not a key (older saves, lab hints) is shown as it is.
 */
public final class Msg {
    private static final String SEP = "\u001f";

    private Msg() {
    }

    public static String of(String key, Object... args) {
        StringBuilder sb = new StringBuilder(key);
        for (Object a : args) {
            sb.append(SEP).append(String.valueOf(a).replace(SEP, " "));
        }
        return sb.toString();
    }

    public static boolean isKey(String m) {
        return m.startsWith("modkeel.");
    }

    public static String key(String m) {
        int i = m.indexOf(SEP);
        return i < 0 ? m : m.substring(0, i);
    }

    public static String[] args(String m) {
        String[] p = m.split(SEP, -1);
        return Arrays.copyOfRange(p, 1, p.length);
    }

    /** For logs: the key with its arguments, readable in English-only log files. */
    public static String plain(String m) {
        return m.replace(SEP, " ");
    }
}
