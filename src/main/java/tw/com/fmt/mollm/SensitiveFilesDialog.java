package tw.com.fmt.mollm;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

final class SensitiveFilesDialog extends DialogWrapper {
    private final MollmSettings settings;
    private final JTextArea patterns = new JTextArea(15, 50);
    private final JLabel status = new JLabel("每行一個規則；* 任意字元、? 一個字元、** 可跨目錄。最多 64 條。");
    SensitiveFilesDialog(Project project, MollmSettings settings) {
        super(project, false); this.settings = settings;
        var saved = settings.getState().sensitiveFilePatterns;
        patterns.setText(String.join("\n", saved == null ? SensitiveFiles.DEFAULT_PATTERNS : saved));
        setTitle("MoLLM 敏感檔案規則"); setOKButtonText("儲存"); setCancelButtonText("取消"); init();
    }
    @Override protected JComponent createCenterPanel() {
        var panel = new JPanel(new BorderLayout(8, 8));
        var description = new JTextArea("符合規則的檔案／目錄不提供給 Agent 讀取或搜尋；名稱不符但內容含可辨識帳密也會封鎖。\n"
                + "規則可比對任一目錄層級，例如 .env*、application*.yml、config/private/**。\n"
                + "儲存於下一次 Agent／續作生效，已傳送的內容無法撤回；新增保護後建議開新對話。\n"
                + "辨識依常見格式，不能涵蓋所有帳密、編碼或自訂格式。");
        description.setEditable(false); description.setOpaque(false);
        panel.add(description, BorderLayout.NORTH); panel.add(new JScrollPane(patterns), BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(8, 8)); var reset = new JButton("恢復預設規則");
        reset.addActionListener(event -> patterns.setText(String.join("\n", SensitiveFiles.DEFAULT_PATTERNS)));
        footer.add(status, BorderLayout.CENTER); footer.add(reset, BorderLayout.EAST); panel.add(footer, BorderLayout.SOUTH); return panel;
    }
    @Override protected void doOKAction() {
        List<String> rules = patterns.getText().lines().map(String::trim).filter(line -> !line.isEmpty()).distinct().toList();
        if (rules.size() > 64 || rules.stream().anyMatch(rule -> rule.length() > 200)) { status.setText("最多 64 條規則，每條最多 200 字元。"); return; }
        settings.getState().sensitiveFilePatterns = new ArrayList<>(rules);
        super.doOKAction();
    }
}
