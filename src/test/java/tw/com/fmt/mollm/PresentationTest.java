package tw.com.fmt.mollm;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class PresentationTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name); checks++;
    }
    static void run() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            transition(); scaling();
        });
        System.out.println("PASS: " + checks + " presentation checks (state continuity, close/failure recovery and UI scaling).");
    }
    private static void transition() {
        var host = new JPanel(new BorderLayout()); var content = new JPanel(new BorderLayout());
        var transcript = new JTextArea("Agent 正在執行工具…"); var draft = new JTextArea("待送出的問題");
        content.add(transcript, BorderLayout.CENTER); content.add(draft, BorderLayout.SOUTH); host.add(content);
        var draftDocument = draft.getDocument(); var surfaces = new AtomicReference<FakeSurface>(); var updates = new AtomicInteger();
        var view = new FullScreenView(content, host, exit -> {
            var surface = new FakeSurface(exit); surfaces.set(surface); return surface;
        }, updates::incrementAndGet);
        view.enter(); var window = surfaces.get();
        check(view.isFullScreen() && content.getParent() == window.content(), "Full screen reparents existing chat");
        check(draft.getDocument() == draftDocument && draft.getText().equals("待送出的問題"), "Full screen retains draft document");
        transcript.append("\n工具完成");
        view.enter(); check(surfaces.get() == window, "Repeated entry does not create duplicate windows");
        window.exit.run();
        check(!view.isFullScreen() && content.getParent() == host && window.closed, "Window close restores normal host");
        check(transcript.getText().endsWith("工具完成") && draft.getDocument() == draftDocument, "Live updates and draft survive restore");
        view.enter(); view.dispose();
        check(!view.isFullScreen() && surfaces.get().closed && content.getParent() == host, "Project disposal closes full screen");
        view.enter(); check(!view.isFullScreen(), "Disposed view cannot open another window");
        check(updates.get() == 3, "Presentation changes notify once per user transition");
        var failing = new FullScreenView(content, host, exit -> new FakeSurface(exit) {
            @Override public void show() { throw new IllegalStateException("simulated window failure"); }
        }, () -> {});
        try { failing.enter(); throw new AssertionError("Failure swallowed"); }
        catch (IllegalStateException expected) { check(content.getParent() == host && !failing.isFullScreen(), "Show failure restores original live content"); }
    }
    private static void scaling() {
        var root = new JPanel(new BorderLayout(8, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        var button = new JButton("傳送"); var text = new JTextArea("對話");
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        var table = new JTable(2, 2); table.setRowHeight(20);
        root.add(button, BorderLayout.NORTH); root.add(text, BorderLayout.CENTER); root.add(table, BorderLayout.SOUTH);
        root.setPreferredSize(new Dimension(500, 400));
        float buttonFont = button.getFont().getSize2D(); var margin = button.getMargin();
        var scale = new InterfaceScale(); scale.apply(root, 150);
        check(text.getFont().getSize2D() == 24 && button.getFont().getSize2D() == buttonFont * 1.5f, "Scale applies to chat and controls");
        check(table.getRowHeight() == 30 && root.getPreferredSize().equals(new Dimension(750, 600)), "Table and fixed layout sizes scale");
        check(root.getInsets().left == 15 && ((BorderLayout) root.getLayout()).getHgap() == 12, "Borders and layout spacing scale");
        scale.apply(root, 200); scale.apply(root, 100);
        check(text.getFont().getSize2D() == 16 && root.getPreferredSize().width == 500 && table.getRowHeight() == 20, "Repeated zoom has no cumulative drift");
        check(button.getMargin().equals(margin), "Normal zoom restores button padding");
        scale.apply(root, 200); scale.setFont(text, text.getFont().deriveFont(18f));
        check(text.getFont().getSize2D() == 36, "Chat font zoom combines with interface scale");
        scale.apply(root, 100); check(text.getFont().getSize2D() == 18, "Changing interface zoom retains chosen chat font size");
        var added = new JLabel("新附件"); float font = added.getFont().getSize2D(); root.add(added, BorderLayout.WEST);
        scale.apply(root, 150); check(added.getFont().getSize2D() == font * 1.5f, "New components inherit selected interface zoom");
        scale.apply(root, -50); check(text.getFont().getSize2D() == 18, "Invalid stored zoom falls back to normal");
        var settings = new MollmSettings.Data(); settings.interfaceScalePercent = 175;
        var restored = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(settings), MollmSettings.Data.class);
        check(restored.interfaceScalePercent == 175, "Interface zoom persists in IntelliJ state");
        render(scale);
    }
    private static void render(InterfaceScale scale) {
        var usage = new UsagePanel();
        var key = com.google.gson.JsonParser.parseString("{\"key_alias\":\"Demo gateway\",\"spend\":100.22,\"max_budget\":200,\"rpm_limit\":60,\"tpm_limit\":2700000,\"budget_reset_at\":\"2026-11-01\"}").getAsJsonObject();
        var days = new com.google.gson.JsonArray();
        days.add(com.google.gson.JsonParser.parseString("{\"date\":\"" + java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                + "\",\"metrics\":{\"spend\":291.99,\"api_requests\":1332,\"failed_requests\":12,\"total_tokens\":202300000}}"));
        usage.display(new Gateway.Usage(key, days, java.util.List.of()));
        scale.apply(usage, 150); usage.setSize(1200, 1000); layout(usage);
        var image = new java.awt.image.BufferedImage(1200, 1000, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { usage.printAll(graphics); } finally { graphics.dispose(); }
        try { javax.imageio.ImageIO.write(image, "png", java.nio.file.Path.of("build/interface-scale-preview.png").toFile()); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    private static void layout(Container parent) {
        parent.doLayout(); for (var child : parent.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static class FakeSurface implements FullScreenView.Surface {
        private final JPanel content = new JPanel(new BorderLayout());
        private final Runnable exit;
        private boolean closed;
        FakeSurface(Runnable exit) { this.exit = exit; }
        public JPanel content() { return content; }
        public void show() { }
        public void close() { closed = true; exit.run(); }
    }
}
