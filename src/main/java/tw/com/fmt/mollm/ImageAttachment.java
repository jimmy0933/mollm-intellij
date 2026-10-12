package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

record ImageAttachment(String dataUri, int width, int height, int bytes, ImageIcon preview) {
    static final int MAX_COUNT = 4;
    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final int MAX_TOTAL_BYTES = 20 * 1024 * 1024;

    static ImageAttachment from(Image image) throws IOException {
        int width = image.getWidth(null), height = image.getHeight(null);
        if (width < 0 || height < 0) {
            var loaded = new ImageIcon(image);
            width = loaded.getIconWidth(); height = loaded.getIconHeight();
        }
        if (width <= 0 || height <= 0 || (long) width * height > 16_000_000)
            throw new IOException("圖片需小於 1600 萬像素，且已完整載入。");
        BufferedImage source;
        if (image instanceof BufferedImage buffered) source = buffered;
        else {
            source = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            var graphics = source.createGraphics();
            try { if (!graphics.drawImage(image, 0, 0, null)) throw new IOException("圖片尚未完整載入。"); }
            finally { graphics.dispose(); }
        }
        var output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] value, int offset, int length) {
                if ((long) count + length > MAX_BYTES) throw new IllegalArgumentException("單張 PNG 不可超過 10 MiB。");
                super.write(value, offset, length);
            }
            @Override public synchronized void write(int value) {
                if (count >= MAX_BYTES) throw new IllegalArgumentException("單張 PNG 不可超過 10 MiB。");
                super.write(value);
            }
        };
        try (var png = new MemoryCacheImageOutputStream(output)) {
            if (!ImageIO.write(source, "png", png)) throw new IOException("無法編碼 PNG。");
        }
        double scale = Math.min(1, Math.min(140.0 / width, 80.0 / height));
        var thumbnail = new BufferedImage(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), BufferedImage.TYPE_INT_ARGB);
        var graphics = thumbnail.createGraphics();
        try { graphics.drawImage(source, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), null); }
        finally { graphics.dispose(); }
        return new ImageAttachment("data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()),
                width, height, output.size(), new ImageIcon(thumbnail));
    }

    JsonObject json() {
        var part = new JsonObject(); part.addProperty("type", "image_url");
        var url = new JsonObject(); url.addProperty("url", dataUri);
        part.add("image_url", url); return part;
    }
}
