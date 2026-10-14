package tw.com.fmt.mollm;

import com.intellij.ide.PasteProvider;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.ide.CopyPasteManager;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import java.awt.Image;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The IDE handles Ctrl+V before Swing; expose the same paste operation to both. */
final class ChatInput extends JTextArea implements UiDataProvider {
    private final Supplier<Transferable> clipboard;
    private final Consumer<String> failure;
    private final PasteProvider pasteProvider = new PasteProvider() {
        @Override public void performPaste(DataContext context) { paste(); }
        @Override public boolean isPastePossible(DataContext context) { return isEditable() && isEnabled(); }
        @Override public boolean isPasteEnabled(DataContext context) {
            // Do not repeatedly fetch large clipboard images during action updates.
            return isPastePossible(context);
        }
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    };

    ChatInput(Consumer<Image> receive, Consumer<String> failure) {
        this(receive, failure, ChatInput::systemClipboard);
    }

    void setFilesReceiver(Consumer<java.util.List<java.io.File>> receive) {
        ((ImagePasteHandler) getTransferHandler()).setFilesReceiver(receive);
    }

    ChatInput(Consumer<Image> receive, Consumer<String> failure, Supplier<Transferable> clipboard) {
        super(5, 30);
        this.clipboard = clipboard; this.failure = failure;
        setTransferHandler(new ImagePasteHandler(getTransferHandler(), receive, failure));
        var action = new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { paste(); }
        };
        getActionMap().put(DefaultEditorKit.pasteAction, action);
        getInputMap().put(KeyStroke.getKeyStroke("ctrl V"), DefaultEditorKit.pasteAction);
        getInputMap().put(KeyStroke.getKeyStroke("shift INSERT"), DefaultEditorKit.pasteAction);
        var menu = new JPopupMenu();
        var paste = new JMenuItem("貼上文字／截圖");
        paste.addActionListener(event -> paste()); menu.add(paste);
        setComponentPopupMenu(menu);
        var idePaste = new com.intellij.openapi.project.DumbAwareAction("MoLLM 貼上文字／截圖") {
            @Override public void actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent event) { paste(); }
            @Override public void update(com.intellij.openapi.actionSystem.AnActionEvent event) {
                event.getPresentation().setEnabled(isEditable() && isEnabled());
            }
            @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        };
        idePaste.registerCustomShortcutSet(new com.intellij.openapi.actionSystem.CustomShortcutSet(
                new com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke("ctrl V"), null),
                new com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke("shift INSERT"), null)), this);
    }

    @Override public void uiDataSnapshot(DataSink sink) {
        sink.set(PlatformDataKeys.PASTE_PROVIDER, pasteProvider);
    }

    @Override public void paste() {
        if (!isEditable() || !isEnabled()) return;
        try {
            var data = clipboard.get();
            if (data == null) { failure.accept("剪貼簿沒有內容；請先複製截圖或文字。"); return; }
            if (!getTransferHandler().importData(this, data))
                failure.accept("剪貼簿沒有可貼上的圖片或文字；請先用 Win+Shift+S 截圖。");
        } catch (RuntimeException e) {
            failure.accept("無法讀取剪貼簿；可能正在被其他程式使用，請稍後再貼上。");
        }
    }

    private static Transferable systemClipboard() {
        // Use the IDE's Windows clipboard synchronization and availability handling.
        return CopyPasteManager.getInstance().getContents();
    }
}
