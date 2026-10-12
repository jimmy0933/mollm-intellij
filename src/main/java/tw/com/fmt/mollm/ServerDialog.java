package tw.com.fmt.mollm;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

final class ServerDialog extends DialogWrapper {
    private final MollmSettings.Server original;
    private final JTextField label = new JTextField(28);
    private final JTextField url = new JTextField(28);
    private final JPasswordField key = new JPasswordField(28);
    private final JTextField models = new JTextField(28);
    private final JSpinner maxOutput = new JSpinner(new SpinnerNumberModel(8192, 1, 1_000_000, 1024));
    private final JCheckBox clearKey = new JCheckBox("清除已儲存金鑰");

    ServerDialog(Project project, MollmSettings.Server server) {
        super(project);
        original = server;
        var initial = server == null ? new MollmSettings.Server() : server;
        label.setText(initial.label);
        url.setText(initial.baseUrl);
        models.setText(String.join(", ", initial.models));
        maxOutput.setValue(initial.maxOutputTokens);
        setTitle(server == null ? "新增 MoLLM 伺服器" : "編輯 MoLLM 伺服器");
        init();
    }

    @Override protected @Nullable JComponent createCenterPanel() {
        var panel = new JPanel(new GridLayout(0, 2, 10, 10));
        panel.add(new JLabel("名稱")); panel.add(label);
        panel.add(new JLabel("Base URL")); panel.add(url);
        panel.add(new JLabel("API Key（留空沿用）")); panel.add(key);
        panel.add(new JLabel("模型白名單（逗號分隔，留空全部）")); panel.add(models);
        panel.add(new JLabel("預設輸出 token 上限")); panel.add(maxOutput);
        panel.add(new JLabel("金鑰存入 IntelliJ Password Safe")); panel.add(clearKey);
        return panel;
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        if (label.getText().isBlank()) return new ValidationInfo("請填入名稱", label);
        try { Gateway.normalizeUrl(url.getText()); }
        catch (IllegalArgumentException e) { return new ValidationInfo(e.getMessage(), url); }
        if (clearKey.isSelected() && key.getPassword().length > 0)
            return new ValidationInfo("清除金鑰與輸入新金鑰請擇一", key);
        return null;
    }

    MollmSettings.Server server() {
        var value = new MollmSettings.Server();
        if (original != null) { value.id = original.id; value.overrides = original.overrides; }
        value.label = label.getText().trim();
        value.baseUrl = Gateway.normalizeUrl(url.getText());
        value.models = Arrays.stream(models.getText().split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).distinct().toList();
        value.maxOutputTokens = (Integer) maxOutput.getValue();
        return value;
    }
    String key() { return new String(key.getPassword()); }
    boolean clearKey() { return clearKey.isSelected(); }
}
