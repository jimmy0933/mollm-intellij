package tw.com.fmt.mollm;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Modeless review keeps the tool window's Stop button usable while an approval is pending. */
final class AgentApproval implements WorkspaceTools.Approval {
    private final Project project;
    private final java.nio.file.Path root;
    private final DirectoryPermissions permissions;
    AgentApproval(Project project, java.nio.file.Path root) {
        this.project = project; this.root = root;
        permissions = new DirectoryPermissions(MollmSettings.getInstance());
    }
    @Override public boolean change(WorkspaceTools.Change change, Gateway.Call cancellation) throws Exception {
        AgentLoop.checkCancelled(cancellation);
        if (project.isDisposed()) return false;
        if (permissions.allows(root, change.path(), DirectoryPermissions.Kind.FILES)) return true;
        return review(change, null, cancellation);
    }
    @Override public boolean command(WorkspaceTools.Command command, Gateway.Call cancellation) throws Exception {
        AgentLoop.checkCancelled(cancellation);
        if (project.isDisposed()) return false;
        if (permissions.allows(root, command.directory(), DirectoryPermissions.Kind.COMMANDS)) return true;
        return review(null, command, cancellation);
    }
    private boolean review(WorkspaceTools.Change change, WorkspaceTools.Command command, Gateway.Call cancellation) throws Exception {
        var decision = new CompletableFuture<Boolean>();
        var dialog = new AtomicReference<ReviewDialog>();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed() || cancellation.cancelled()) { decision.complete(false); return; }
            try {
                var value = new ReviewDialog(project, root, permissions, change, command, cancellation, decision);
                dialog.set(value); value.show();
            } catch (Exception e) { decision.completeExceptionally(e); }
        }, ModalityState.nonModal());
        try {
            while (true) {
                AgentLoop.checkCancelled(cancellation);
                if (project.isDisposed()) return false;
                try { return decision.get(150, TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) { /* Poll cancellation while waiting for user review. */ }
                catch (ExecutionException e) { throw new IllegalStateException("確認視窗無法開啟", e.getCause()); }
            }
        } finally {
            ApplicationManager.getApplication().invokeLater(() -> {
                var value = dialog.get();
                if (value != null && !value.closed) value.close(DialogWrapper.CANCEL_EXIT_CODE);
            }, ModalityState.any());
        }
    }

    private static final class ReviewDialog extends DialogWrapper {
        private final Project project;
        private final WorkspaceTools.Change change;
        private final WorkspaceTools.Command command;
        private final CompletableFuture<Boolean> decision;
        private final java.nio.file.Path root;
        private final DirectoryPermissions permissions;
        private final Gateway.Call cancellation;
        private boolean closed;
        ReviewDialog(Project project, java.nio.file.Path root, DirectoryPermissions permissions, WorkspaceTools.Change change,
                     WorkspaceTools.Command command, Gateway.Call cancellation, CompletableFuture<Boolean> decision) {
            super(project, false);
            this.project = project; this.change = change; this.command = command; this.decision = decision;
            this.root = root; this.permissions = permissions; this.cancellation = cancellation;
            setModal(false); setTitle(change == null ? "MoLLM Agent — 確認指令" : "MoLLM Agent — 確認檔案修改");
            setOKButtonText("允許一次"); setCancelButtonText("拒絕"); init();
        }
        @Override protected Action[] createActions() {
            var always = new DialogWrapperAction("此工作目錄下永遠允許") {
                @Override protected void doAction(java.awt.event.ActionEvent event) {
                    if (cancellation.cancelled() || project.isDisposed()) { doCancelAction(); return; }
                    try {
                        permissions.allow(root, change == null ? DirectoryPermissions.Kind.COMMANDS : DirectoryPermissions.Kind.FILES);
                        decision.complete(true); close(OK_EXIT_CODE);
                    } catch (java.io.IOException e) { decision.completeExceptionally(e); close(CANCEL_EXIT_CODE); }
                }
            };
            return new Action[]{getOKAction(), always, getCancelAction()};
        }
        @Override protected @Nullable JComponent createCenterPanel() {
            var panel = new JPanel(new BorderLayout(8, 8));
            panel.setPreferredSize(new Dimension(850, 520));
            var scope = new JTextArea("永久授權工作目錄：" + root + "\n"
                    + (change == null ? "永遠允許會授權此工作目錄及子目錄中的所有指令；檔案修改需另外授權。"
                    : "永遠允許會授權此工作目錄及子目錄中的所有檔案修改／新增；指令需另外授權。")
                    + "\n可在 MoLLM 設定 → Agent 權限管理中撤銷。");
            scope.setEditable(false); scope.setLineWrap(true); scope.setWrapStyleWord(true);
            if (change != null) {
                scope.append("\n本次檔案：" + change.path());
                var diff = DiffManager.getInstance().createRequestPanel(project, getDisposable(), null);
                var factory = DiffContentFactory.getInstance();
                diff.setRequest(new SimpleDiffRequest(change.create() ? "新增檔案" : "修改檔案",
                        factory.create(project, change.before()), factory.create(project, change.after()), "修改前", "修改後"));
                panel.add(diff.getComponent(), BorderLayout.CENTER);
                panel.add(new JLabel("套用後會儲存檔案；可在 IntelliJ 使用 Undo 復原。"), BorderLayout.SOUTH);
            } else {
                var text = new JTextArea("工作目錄：" + command.directory() + "\n逾時：" + command.timeoutSeconds() + " 秒\n\n"
                        + "Executable:\n" + command.argv().getFirst() + "\n\nArguments (JSON):\n"
                        + new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(command.argv().subList(1, command.argv().size())));
                text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
                panel.add(new JScrollPane(text), BorderLayout.CENTER);
                var notice = new JTextArea("指令使用已儲存的檔案，不會自動儲存你其他的編輯。\n"
                        + "工作目錄不是作業系統沙箱；指令可存取專案外的檔案或網路，請確認完整內容。");
                notice.setEditable(false); notice.setLineWrap(true); notice.setWrapStyleWord(true);
                panel.add(notice, BorderLayout.SOUTH);
            }
            panel.add(scope, BorderLayout.NORTH);
            return panel;
        }
        @Override protected void doOKAction() {
            if (cancellation.cancelled() || project.isDisposed()) { doCancelAction(); return; }
            decision.complete(true); super.doOKAction();
        }
        @Override public void doCancelAction() { decision.complete(false); super.doCancelAction(); }
        @Override protected void dispose() { closed = true; decision.complete(false); super.dispose(); }
    }
}
