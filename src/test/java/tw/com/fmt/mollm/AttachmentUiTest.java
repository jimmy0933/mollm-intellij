package tw.com.fmt.mollm;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class AttachmentUiTest {
    private static int checks;
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); checks++; }
    static void run() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build"), "drop-test-");
        Path code = root.resolve("example.java"); Files.writeString(code, "// 中文\nclass Example {}\n");
        var text = DroppedFile.read(code);
        check(text.image() == null && text.draftText().contains("class Example") && text.draftText().contains("example.java"), "Dropped code keeps filename and UTF-8 contents");
        var image = new java.awt.image.BufferedImage(160, 90, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(10, 10, 0xff4488aa); Path png = root.resolve("screenshot.png"); javax.imageio.ImageIO.write(image, "png", png.toFile());
        check(DroppedFile.read(png).image().width() == 160, "Dropped screenshot becomes PNG image attachment");
        Path binary = root.resolve("binary.dat"); Files.write(binary, new byte[]{0, 1, 2});
        try { DroppedFile.read(binary); throw new AssertionError("Binary file accepted"); }
        catch (java.io.IOException expected) { checks++; }
        try { DroppedFile.read(root); throw new AssertionError("Directory accepted"); }
        catch (java.io.IOException expected) { checks++; }
        Path large = root.resolve("large.txt"); Files.write(large, new byte[WorkspaceTools.MAX_FILE_BYTES + 1]);
        try { DroppedFile.read(large); throw new AssertionError("Large text accepted"); }
        catch (java.io.IOException expected) { checks++; }
        SwingUtilities.invokeAndWait(() -> {
            var field = new JTextArea(); var files = new AtomicReference<List<java.io.File>>();
            var handler = new ImagePasteHandler(field.getTransferHandler(), value -> {}, error -> { throw new AssertionError(error); });
            handler.setFilesReceiver(files::set);
            Transferable data = new Transferable() {
                public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
                public boolean isDataFlavorSupported(DataFlavor flavor) { return flavor.equals(DataFlavor.javaFileListFlavor); }
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor); return List.of(code.toFile(), png.toFile());
                }
            };
            check(handler.canImport(new TransferHandler.TransferSupport(field, data)), "File-list drag flavor recognized");
            check(handler.importData(new TransferHandler.TransferSupport(field, data)) && files.get().size() == 2, "Multiple dropped files routed to attachment receiver");
            check(handler.importData(field, new StringSelection("拖入程式碼")) && field.getText().equals("拖入程式碼"), "Text selection import remains available");
            var controls = new CompactActions(new JButton("傳送"), new JButton("停止"), new JButton("新對話"), new JButton("並行任務"));
            var host = new JPanel(new BorderLayout()); host.add(controls, BorderLayout.SOUTH); host.setSize(640, 200); host.doLayout(); controls.doLayout();
            int wideHeight = controls.getPreferredSize().height;
            check(controls.getComponent(0).getWidth() < 120, "Buttons retain compact natural widths instead of filling columns");
            host.setSize(180, 200); host.doLayout(); controls.doLayout();
            check(controls.getPreferredSize().height > wideHeight, "Narrow toolbar reserves height for wrapped buttons");
            var fallback = new Font(Font.SANS_SERIF, 0, 13);
            var preview = new JTextArea("實際字體：" + ChatFonts.defaultFamily() + "\n你好，這是一段對話文字範例。\nThe quick brown fox · 0123456789", 3, 30);
            preview.setFont(ChatFonts.create("__chat_default__", fallback, 16));
            check(preview.getFont().getFamily().equals(new Font(ChatFonts.defaultFamily(), 0, 16).getFamily()), "Chat default resolves to actual available system font");
            check(preview.getFont().canDisplay('你'), "Chat default preserves CJK fallback glyphs");
            String next = ChatFonts.families().stream().filter(value -> !value.equals(preview.getFont().getFamily())).findFirst().orElse(Font.MONOSPACED);
            preview.setFont(ChatFonts.create(next, fallback, 16));
            check(preview.getFont().getFamily().equals(new Font(next, 0, 16).getFamily()), "Font sample changes immediately with selected family");
            preview.setFont(ChatFonts.create("__chat_default__", fallback, 16));
            var page = new JPanel(new BorderLayout(8, 8)); page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            page.add(new JScrollPane(preview), BorderLayout.CENTER); page.add(controls, BorderLayout.SOUTH);
            page.setSize(640, 220); page.doLayout(); controls.doLayout();
            var canvas = new java.awt.image.BufferedImage(640, 220, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            layout(page); var graphics = canvas.createGraphics(); try { page.printAll(graphics); } finally { graphics.dispose(); }
            try { javax.imageio.ImageIO.write(canvas, "png", Path.of("build/font-toolbar-preview.png").toFile()); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        check(Files.readString(code).contains("class Example") && Files.exists(png), "Drag attachment processing preserves original files");
        System.out.println("PASS: " + checks + " file-drop/font-preview/compact-toolbar checks.");
    }
    private static void layout(Container parent) { parent.doLayout(); for (var child : parent.getComponents()) if (child instanceof Container container) layout(container); }
}
