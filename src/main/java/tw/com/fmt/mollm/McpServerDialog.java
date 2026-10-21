package tw.com.fmt.mollm;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

final class McpServerDialog extends DialogWrapper {
    private final MollmSettings.McpServerConfig original;
    private final JTextField name = new JTextField(28);
    private final JComboBox<String> transport = new JComboBox<>(new String[]{"stdio", "sse"});

    // stdio
    private final JTextField executable = new JTextField(28);
    private final JTextArea args = new JTextArea(4, 28);
    private final JTextField workingDirectory = new JTextField(28);

    // sse & http
    private final JTextField baseUrl = new JTextField(28);
    private final JCheckBox passAuthToken = new JCheckBox("從 PasswordSafe 傳遞認證 Token");
    private final JPasswordField password = new JPasswordField(28);
    private final JCheckBox clearPassword = new JCheckBox("清除已儲存密碼");

    private final JSpinner requestTimeoutSeconds = new JSpinner(new SpinnerNumberModel(60, 1, 3600, 10));

    McpServerDialog(Project project, MollmSettings.McpServerConfig config) {
        super(project);
        original = config;
        var initial = config == null ? new MollmSettings.McpServerConfig() : config;
        name.setText(initial.name);
        transport.setSelectedItem(initial.transport);

        executable.setText(initial.executable);
        args.setText(String.join("\n", initial.args));
        workingDirectory.setText(initial.workingDirectory);

        baseUrl.setText(initial.baseUrl);
        passAuthToken.setSelected(initial.passAuthToken);
        requestTimeoutSeconds.setValue(initial.requestTimeoutSeconds);

        setTitle(config == null ? "新增 MCP 伺服器" : "編輯 MCP 伺服器");
        init();
    }

    @Override protected @Nullable JComponent createCenterPanel() {
        var panel = new JPanel(new GridLayout(0, 2, 10, 10));
        panel.add(new JLabel("名稱")); panel.add(name);
        panel.add(new JLabel("傳輸方式")); panel.add(transport);

        panel.add(new JLabel("=== stdio 設定 ===")); panel.add(new JLabel(""));
        panel.add(new JLabel("執行檔")); panel.add(executable);
        panel.add(new JLabel("參數 (每行一個)")); panel.add(new JScrollPane(args));
        panel.add(new JLabel("工作目錄")); panel.add(workingDirectory);

        panel.add(new JLabel("=== SSE/HTTP 設定 ===")); panel.add(new JLabel(""));
        panel.add(new JLabel("Base URL")); panel.add(baseUrl);
        panel.add(new JLabel("認證")); panel.add(passAuthToken);
        panel.add(new JLabel("輸入新 Token")); panel.add(password);
        panel.add(new JLabel("")); panel.add(clearPassword);

        panel.add(new JLabel("=== 進階 ===")); panel.add(new JLabel(""));
        panel.add(new JLabel("請求逾時 (秒)")); panel.add(requestTimeoutSeconds);

        return panel;
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        if (name.getText().isBlank()) return new ValidationInfo("請填入名稱", name);
        if (clearPassword.isSelected() && password.getPassword().length > 0)
            return new ValidationInfo("清除密碼與輸入新密碼請擇一", password);
        return null;
    }

    MollmSettings.McpServerConfig server() {
        var value = new MollmSettings.McpServerConfig();
        if (original != null) {
            value.id = original.id;
            value.env = original.env;
            value.headers = original.headers;
        }
        value.name = name.getText().trim();
        value.transport = (String) transport.getSelectedItem();

        value.executable = executable.getText().trim();
        value.args = java.util.Arrays.stream(args.getText().split("\n")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        value.workingDirectory = workingDirectory.getText().trim();

        value.baseUrl = baseUrl.getText().trim();
        value.passAuthToken = passAuthToken.isSelected();
        value.requestTimeoutSeconds = (Integer) requestTimeoutSeconds.getValue();

        return value;
    }

    char[] getPassword() { return password.getPassword(); }
    boolean shouldClearPassword() { return clearPassword.isSelected(); }
}
