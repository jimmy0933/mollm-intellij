package tw.com.fmt.mollm;

import javax.swing.JComponent;
import javax.swing.TransferHandler;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.util.function.Consumer;

final class ImagePasteHandler extends TransferHandler {
    private final TransferHandler textHandler;
    private final Consumer<Image> receive;
    private final Consumer<String> failure;

    ImagePasteHandler(TransferHandler textHandler, Consumer<Image> receive, Consumer<String> failure) {
        this.textHandler = textHandler; this.receive = receive; this.failure = failure;
    }
    @Override public boolean canImport(TransferSupport support) {
        return support.isDataFlavorSupported(DataFlavor.imageFlavor) || textHandler != null && textHandler.canImport(support);
    }
    @Override public boolean canImport(JComponent component, DataFlavor[] flavors) {
        for (var flavor : flavors) if (flavor.equals(DataFlavor.imageFlavor)) return true;
        return textHandler != null && textHandler.canImport(component, flavors);
    }
    @Override public boolean importData(TransferSupport support) {
        if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) return image(support.getTransferable());
        return textHandler != null && textHandler.importData(support);
    }
    @Override public boolean importData(JComponent component, Transferable data) {
        if (data.isDataFlavorSupported(DataFlavor.imageFlavor)) return image(data);
        return textHandler != null && textHandler.importData(component, data);
    }
    private boolean image(Transferable data) {
        try { receive.accept((Image) data.getTransferData(DataFlavor.imageFlavor)); return true; }
        catch (Exception e) { failure.accept("無法貼上圖片：" + e.getMessage()); return false; }
    }
    // Preserve normal text cut/copy after wrapping the text component's handler.
    @Override public void exportToClipboard(JComponent component, java.awt.datatransfer.Clipboard clipboard, int action) {
        if (textHandler != null) textHandler.exportToClipboard(component, clipboard, action);
    }
    @Override public int getSourceActions(JComponent component) {
        return textHandler == null ? NONE : textHandler.getSourceActions(component);
    }
}
