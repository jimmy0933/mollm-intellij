package tw.com.fmt.mollm;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VfsUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Reads unsaved editor buffers and writes through IDE commands rather than overwriting them on disk. */
final class IdeWorkspace implements WorkspaceTools.FilesAccess {
    private final Project project;
    IdeWorkspace(Project project) { this.project = project; }

    @Override public String read(Path path) throws Exception {
        if (Files.size(path) > WorkspaceTools.MAX_FILE_BYTES) throw new IOException("文件超過 1 MB");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        if (file == null) throw new IOException("IDE 找不到文件：" + path);
        return ReadAction.compute(() -> {
            if (file.getFileType().isBinary()) throw new IOException("不支援二進位文件");
            var document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null) throw new IOException("IDE 無法讀取文字文件");
            return document.getText();
        });
    }

    @Override public void apply(WorkspaceTools.Change change, Gateway.Call cancellation) throws Exception {
        var filesystem = LocalFileSystem.getInstance();
        // Refresh outside a read lock, before entering EDT/write command.
        if (!change.create()) filesystem.refreshAndFindFileByNioFile(change.path());
        var done = new CompletableFuture<Void>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                AgentLoop.checkCancelled(cancellation);
                if (project.isDisposed()) throw new IOException("專案已關閉");
                WriteCommandAction.writeCommandAction(project).withName("MoLLM Agent: " + change.path().getFileName()).run(() -> {
                    AgentLoop.checkCancelled(cancellation);
                    com.intellij.openapi.vfs.VirtualFile file;
                    if (change.create()) {
                        if (com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName(change.path().getFileName().toString()).isBinary())
                            throw new IOException("create_file 僅支援文字檔案類型");
                        if (Files.exists(change.path())) throw new IOException("文件已被建立，修改取消");
                        var parent = VfsUtil.createDirectories(change.path().getParent().toString());
                        file = parent.createChildData(this, change.path().getFileName().toString());
                        file.setCharset(StandardCharsets.UTF_8);
                    } else {
                        file = filesystem.findFileByNioFile(change.path());
                        if (file == null) throw new IOException("文件已被刪除");
                        if (ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(java.util.List.of(file)).hasReadonlyFiles())
                            throw new IOException("文件唯讀");
                    }
                    var manager = FileDocumentManager.getInstance();
                    var document = manager.getDocument(file);
                    if (document == null) throw new IOException("IDE 無法編輯此文件");
                    if (!document.getText().equals(change.before())) throw new IOException("文件已變動，修改取消");
                    document.setText(change.after());
                    manager.saveDocument(document);
                    if (manager.isDocumentUnsaved(document)) throw new IOException("修改已套用但未能儲存，請在 IDE 檢查文件");
                });
                done.complete(null);
            } catch (Exception e) { done.completeExceptionally(e); }
        }, ModalityState.nonModal());
        while (true) {
            if (!done.isDone()) AgentLoop.checkCancelled(cancellation);
            try { done.get(150, TimeUnit.MILLISECONDS); return; }
            catch (TimeoutException ignored) { /* Keep cancellation responsive while another IDE dialog is modal. */ }
            catch (ExecutionException e) {
                if (e.getCause() instanceof Exception exception) throw exception;
                throw new IllegalStateException("IDE 修改失敗", e.getCause());
            }
        }
    }
}
