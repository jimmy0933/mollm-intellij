package tw.com.fmt.mollm;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class MollmToolWindowFactory implements ToolWindowFactory, DumbAware {
    private int taskSequence;
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        addTask(project, toolWindow, null);
    }
    private boolean addTask(Project project, ToolWindow toolWindow, MollmPanel.ParallelDraft draft) {
        var manager = toolWindow.getContentManager();
        if (manager.getContentCount() >= 4) return false;
        var panel = new MollmPanel(project);
        var host = new javax.swing.JPanel(new java.awt.BorderLayout());
        host.add(panel, java.awt.BorderLayout.CENTER);
        panel.attachHost(host);
        panel.setParallelLauncher(task -> addTask(project, toolWindow, task));
        var content = ContentFactory.getInstance().createContent(host, "任務 " + (++taskSequence), false);
        content.setCloseable(draft != null);
        content.setDisposer(panel);
        manager.addContent(content); manager.setSelectedContent(content);
        if (draft != null) { panel.startParallel(draft); toolWindow.activate(null); }
        return true;
    }
}
