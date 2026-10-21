package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;

final class McpManagerDialog extends DialogWrapper {
    private final Project project;
    private final DefaultListModel<MollmSettings.McpServerConfig> listModel = new DefaultListModel<>();
    private final JList<MollmSettings.McpServerConfig> list = new JList<>(listModel);

    private final JButton addBtn = new JButton("新增");
    private final JButton editBtn = new JButton("編輯");
    private final JButton importBtn = new JButton("從 JSON 匯入");
    private final JButton removeBtn = new JButton("刪除");

    McpManagerDialog(Project project) {
        super(project);
        this.project = project;
        setTitle("MCP Server 管理");

        for (var config : MollmSettings.getInstance().mcpServers()) {
            listModel.addElement(config);
        }

        addBtn.addActionListener(e -> {
            var dialog = new McpServerDialog(project, null);
            if (dialog.showAndGet()) {
                var config = dialog.server();
                if (saveConfig(config, dialog)) {
                    listModel.addElement(config);
                }
            }
        });

        editBtn.addActionListener(e -> {
            var selected = list.getSelectedValue();
            if (selected == null) return;
            var dialog = new McpServerDialog(project, selected);
            if (dialog.showAndGet()) {
                var config = dialog.server();
                if (saveConfig(config, dialog)) {
                    int idx = list.getSelectedIndex();
                    listModel.set(idx, config);
                }
            }
        });

        removeBtn.addActionListener(e -> {
            var selected = list.getSelectedValue();
            if (selected != null) {
                if (Messages.showYesNoDialog(project, "確定要刪除 " + selected.name + " 嗎？", "刪除確認", Messages.getQuestionIcon()) == Messages.YES) {
                    MollmSettings.getInstance().removeMcpServer(selected.id);
                    McpConnectionManager.close(selected.id);
                    listModel.removeElement(selected);
                }
            }
        });

        importBtn.addActionListener(e -> importFromJson());

        init();
    }

    private boolean saveConfig(MollmSettings.McpServerConfig config, McpServerDialog dialog) {
        // Safe migration and storage of secrets
        try {
            for (var entry : config.env.entrySet()) {
                if (!"__SECRET__".equals(entry.getValue())) {
                    boolean isSecret = entry.getKey().toLowerCase().contains("key") || entry.getKey().toLowerCase().contains("token") || entry.getKey().toLowerCase().contains("secret") || entry.getKey().toLowerCase().contains("pass");
                    if (isSecret || entry.getValue().startsWith("Bearer ")) {
                        ApiKeys.set("mcp_env_" + config.id + "_" + entry.getKey(), entry.getValue());
                        entry.setValue("__SECRET__");
                    }
                }
            }
            for (var entry : config.headers.entrySet()) {
                if (!"__SECRET__".equals(entry.getValue())) {
                    boolean isSecret = entry.getKey().toLowerCase().contains("auth") || entry.getKey().toLowerCase().contains("key") || entry.getKey().toLowerCase().contains("token");
                    if (isSecret || entry.getValue().startsWith("Bearer ")) {
                        ApiKeys.set("mcp_header_" + config.id + "_" + entry.getKey(), entry.getValue());
                        entry.setValue("__SECRET__");
                    }
                }
            }

            if (dialog.shouldClearPassword() || dialog.getPassword().length > 0) {
                ApiKeys.set(config.id, dialog.shouldClearPassword() ? "" : new String(dialog.getPassword()));
            }
        } catch (Exception ex) {
            Messages.showErrorDialog(project, "儲存密碼失敗，設定不會被儲存：" + ex.getMessage(), "儲存失敗");
            return false;
        }

        MollmSettings.getInstance().saveMcpServer(config);
        return true;
    }

    private void importFromJson() {
        var chooser = new JFileChooser();
        if (chooser.showOpenDialog(getContentPanel()) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                String content = Files.readString(file.toPath());
                var root = JsonParser.parseString(content).getAsJsonObject();
                if (!root.has("mcpServers")) {
                    Messages.showErrorDialog(project, "找不到 mcpServers 欄位", "匯入失敗");
                    return;
                }

                var mcpServers = root.getAsJsonObject("mcpServers");
                boolean hasVariables = false;
                for (Map.Entry<String, com.google.gson.JsonElement> entry : mcpServers.entrySet()) {
                    String name = entry.getKey();
                    var obj = entry.getValue().getAsJsonObject();

                    var config = new MollmSettings.McpServerConfig();
                    config.name = name;

                    if (obj.has("command")) {
                        config.transport = "stdio";
                        config.executable = obj.get("command").getAsString();

                        if (obj.has("args")) {
                            var argsArray = obj.getAsJsonArray("args");
                            for (var arg : argsArray) {
                                config.args.add(arg.getAsString());
                            }
                        }

                        if (obj.has("env")) {
                            var envObj = obj.getAsJsonObject("env");
                            for (var envEntry : envObj.entrySet()) {
                                String val = envEntry.getValue().getAsString();
                                if (val.contains("%") || val.contains("$")) hasVariables = true;
                                config.env.put(envEntry.getKey(), val);
                            }
                        }
                    } else if (obj.has("url")) {
                        config.transport = "sse";
                        config.baseUrl = obj.get("url").getAsString();
                    } else {
                        continue;
                    }

                    var previewDialog = new McpServerDialog(project, config);
                    previewDialog.setTitle("預覽並儲存匯入設定 - " + name);
                    if (hasVariables) {
                        Messages.showWarningDialog(project, "匯入的設定包含環境變數參考，請在接下來的對話框中手動替換為實際值。", "注意變數");
                    }
                    if (previewDialog.showAndGet()) {
                        var finalConfig = previewDialog.server();
                        if (saveConfig(finalConfig, previewDialog)) {
                            listModel.addElement(finalConfig);
                        }
                    }
                }
            } catch (Exception ex) {
                Messages.showErrorDialog(project, "解析 JSON 失敗：" + ex.getMessage(), "匯入失敗");
            }
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        var panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(500, 300));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);

        var btnPanel = new JPanel();
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.Y_AXIS));
        btnPanel.add(addBtn);
        btnPanel.add(editBtn);
        btnPanel.add(importBtn);
        btnPanel.add(removeBtn);

        panel.add(btnPanel, BorderLayout.EAST);

        return panel;
    }
}
