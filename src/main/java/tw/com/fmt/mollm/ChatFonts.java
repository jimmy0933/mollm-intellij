package tw.com.fmt.mollm;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.List;

final class ChatFonts {
    private static final List<String> FAMILIES = List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    static List<String> families() { return FAMILIES; }
    static boolean available(String family) { return family != null && FAMILIES.contains(family); }
    static String defaultFamily() {
        for (String family : List.of("Segoe UI", "Microsoft JhengHei UI", "Microsoft JhengHei")) if (available(family)) return family;
        return Font.SANS_SERIF;
    }
    static Font create(String family, Font fallback, float size) {
        if ("__chat_default__".equals(family)) family = defaultFamily();
        // Swing's public font resolver adds platform fallback glyphs for CJK text.
        return available(family) ? javax.swing.text.StyleContext.getDefaultStyleContext()
                .getFont(family, Font.PLAIN, Math.max(1, Math.round(size))).deriveFont(size) : fallback.deriveFont(size);
    }
}
