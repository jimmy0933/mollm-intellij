package tw.com.fmt.mollm;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class MollmToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var panel = new MollmPanel(project);
        var host = new javax.swing.JPanel(new java.awt.BorderLayout());
        host.add(panel, java.awt.BorderLayout.CENTER);
        panel.attachHost(host);
        var content = ContentFactory.getInstance().createContent(host, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
