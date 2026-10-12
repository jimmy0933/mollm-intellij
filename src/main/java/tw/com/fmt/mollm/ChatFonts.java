package tw.com.fmt.mollm;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.List;

final class ChatFonts {
    private static final List<String> FAMILIES = List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    static List<String> families() { return FAMILIES; }
    static boolean available(String family) { return family != null && FAMILIES.contains(family); }
    static Font create(String family, Font fallback, float size) {
        return available(family) ? new Font(family, Font.PLAIN, 1).deriveFont(size) : fallback.deriveFont(size);
    }
}
