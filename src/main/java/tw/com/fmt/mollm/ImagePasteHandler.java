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
    private Consumer<java.util.List<java.io.File>> files;

    ImagePasteHandler(TransferHandler textHandler, Consumer<Image> receive, Consumer<String> failure) {
        this.textHandler = textHandler; this.receive = receive; this.failure = failure;
    }
    void setFilesReceiver(Consumer<java.util.List<java.io.File>> receiver) { files = receiver; }
    private boolean fileFlavor(DataFlavor[] flavors) {
        return files != null && com.intellij.ide.dnd.FileCopyPasteUtil.isFileListFlavorAvailable(flavors);
    }
    @Override public boolean canImport(TransferSupport support) {
        if (support.isDrop()) {
            if ((support.getSourceDropActions() & COPY) == 0) return false;
            support.setDropAction(COPY);
        }
        return support.isDataFlavorSupported(DataFlavor.imageFlavor) || fileFlavor(support.getDataFlavors()) || textHandler != null && textHandler.canImport(support);
    }
    @Override public boolean canImport(JComponent component, DataFlavor[] flavors) {
        for (var flavor : flavors) if (flavor.equals(DataFlavor.imageFlavor)) return true;
        if (fileFlavor(flavors)) return true;
        return textHandler != null && textHandler.canImport(component, flavors);
    }
    @Override public boolean importData(TransferSupport support) {
        if (support.isDrop()) {
            if ((support.getSourceDropActions() & COPY) == 0) return false;
            support.setDropAction(COPY);
        }
        if (fileFlavor(support.getDataFlavors())) {
            return files(support.getTransferable());
        }
        if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) return image(support.getTransferable());
        return textHandler != null && textHandler.importData(support);
    }
    @Override public boolean importData(JComponent component, Transferable data) {
        if (fileFlavor(data.getTransferDataFlavors())) return files(data);
        if (data.isDataFlavorSupported(DataFlavor.imageFlavor)) return image(data);
        return textHandler != null && textHandler.importData(component, data);
    }
    private boolean files(Transferable data) {
        try {
            var values = com.intellij.ide.dnd.FileCopyPasteUtil.getFileList(data);
            if (values == null || values.isEmpty()) return false;
            files.accept(java.util.List.copyOf(values)); return true;
        } catch (Exception e) { failure.accept("無法附加拖入的檔案：" + e.getMessage()); return false; }
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
