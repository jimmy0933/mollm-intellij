package tw.com.fmt.mollm;

import javax.swing.SwingUtilities;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Optional read-only Windows clipboard smoke check; never changes clipboard contents. */
final class SystemClipboardTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.awt.datatransfer.Transferable data;
            try { data = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null); }
            catch (IllegalStateException e) {
                System.out.println("UNAVAILABLE: this process cannot open the Windows clipboard; no live paste verification."); return;
            }
            if (data == null || !data.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                System.out.println("SKIP: current system clipboard has no image; clipboard unchanged."); return;
            }
            var received = new AtomicInteger(); var error = new AtomicReference<String>();
            var input = new ChatInput(image -> {
                try {
                    var attachment = ImageAttachment.from(image);
                    if (attachment.bytes() <= 0) throw new AssertionError("Empty PNG");
                    received.incrementAndGet();
                } catch (Exception e) { throw new AssertionError(e); }
            }, error::set, () -> data);
            input.paste();
            if (received.get() != 1 || error.get() != null) throw new AssertionError("Clipboard image did not reach encoder: " + error.get());
            System.out.println("PASS: real Windows clipboard image -> ChatInput paste -> PNG; clipboard unchanged.");
        });
    }
}
