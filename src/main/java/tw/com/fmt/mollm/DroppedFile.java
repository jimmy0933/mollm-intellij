package tw.com.fmt.mollm;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

record DroppedFile(String name, String text, ImageAttachment image) {
    static DroppedFile read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("請拖入檔案，不能附加整個資料夾。");
        String name = path.getFileName().toString(), lower = name.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(png|jpe?g|gif|bmp|webp)$")) {
            if (Files.size(path) > ImageAttachment.MAX_BYTES) throw new IOException(name + " 超過 10 MiB。");
            try (var input = ImageIO.createImageInputStream(path.toFile())) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("無法讀取 " + name + "，請改用 PNG 或 JPEG。");
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > 16_000_000) throw new IOException(name + " 超過 1600 萬像素。");
                    return new DroppedFile(name, "", ImageAttachment.from(reader.read(0)));
                } finally { reader.dispose(); }
            }
        }
        String text = WorkspaceTools.readUtf8(path);
        if (text.indexOf('\0') >= 0) throw new IOException(name + " 不是文字檔。");
        return new DroppedFile(name, text, null);
    }
    String draftText() { return "\n\n附加檔案（內容資料）：「" + name + "」\n```\n" + text + "\n```\n"; }
}
