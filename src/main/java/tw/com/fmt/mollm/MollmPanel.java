package tw.com.fmt.mollm;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.google.gson.JsonObject;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class MollmPanel extends JPanel implements Disposable, com.intellij.openapi.actionSystem.UiDataProvider {
    private final Project project;
    private final MollmSettings settings = MollmSettings.getInstance();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        var thread = new Thread(task, "MoLLM-worker"); thread.setDaemon(true); return thread;
    });
    private final JComboBox<MollmSettings.Server> servers = new JComboBox<>();
    private final JComboBox<Gateway.Model> models = new JComboBox<>();
    private final JTextArea transcript = new JTextArea();
    private final ChatInput prompt = new ChatInput(this::pasteImage, this::pasteStatus);
    private final JTextArea fontSample = new JTextArea("", 3, 20);
    private final JLabel status = new JLabel("新增伺服器後即可開始。");
    private final UsagePanel usage = new UsagePanel();
    private final JButton send = new JButton("傳送");
    private final JButton stop = new JButton("停止");
    private final JButton refresh = new JButton("重新整理");
    private final JButton edit = new JButton("編輯");
    private final JButton add = new JButton("新增");
    private final JButton remove = new JButton("刪除");
    private final JButton newChat = new JButton("新對話");
    private final JButton parallel = new JButton("並行任務");
    private final JButton continueAgent = new JButton("繼續 Agent");
    private boolean canContinueAgent;
    private java.util.function.Predicate<ParallelDraft> parallelLauncher;
    private AgentInbox activeInbox;
    private final Font defaultChatFont = transcript.getFont();
    record ParallelDraft(MollmSettings.Server server, Gateway.Model model, String scope, String text, List<ImageAttachment> images) {
        ParallelDraft { images = List.copyOf(images); }
    }
    private final JButton parameters = new JButton("模型參數");
    private final JButton settingsButton = new JButton("設定");
    private final JButton fullScreenButton = new JButton("全螢幕");
    private final JComboBox<String> viewMode = new JComboBox<>(new String[]{"一般大小", "全螢幕"});
    private final InterfaceScale interfaceScale = new InterfaceScale();
    private FullScreenView presentation;
    private boolean changingPresentation;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel parameterModel = new JLabel();
    private final JPanel imagePreviews = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JScrollPane imageScroll = new JScrollPane(imagePreviews,
            JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    private final List<ImageAttachment> draftImages = new ArrayList<>();
    private boolean encodingImage;
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"Agent", "Chat"});
    private final JTextField agentDirectory = new JTextField(".", 16);
    private final DefaultTableModel modelTable = new DefaultTableModel(new String[]{"模型", "輸入上限", "輸出上限", "工具", "視覺", "輸入 $/M", "輸出 $/M"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final List<Gateway.Message> history = new ArrayList<>();
    private Future<?> streamTask;
    private Future<?> refreshTask;
    private Gateway.Call activeCall;
    private boolean changing;
    private boolean busy;
    private boolean loading;
    private boolean usageLoading;
    private volatile boolean disposed;
    private int generation;
    private final Timer refreshTimer;
    private final com.intellij.util.messages.MessageBusConnection intervalConnection;

    MollmPanel(Project project) {
        super(new BorderLayout(8, 8));
        this.project = project;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        var north = new JPanel(new BorderLayout(6, 0));
        north.add(mode, BorderLayout.WEST);
        north.add(models, BorderLayout.CENTER);
        var viewActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        viewActions.add(settingsButton); viewActions.add(fullScreenButton);
        north.add(viewActions, BorderLayout.EAST);
        fullScreenButton.setEnabled(false);
        fullScreenButton.addActionListener(event -> setFullScreen(presentation != null && !presentation.isFullScreen()));
        agentDirectory.setToolTipText("相對於目前 IntelliJ 專案；. 表示整個專案，亦可填入子專案名稱。");
        add(north, BorderLayout.NORTH);
        tabs.addTab("對話", chatPanel());
        tabs.addTab("用量", usage);
        tabs.addTab("模型", new JScrollPane(new JTable(modelTable)));
        var settingsPage = settingsPanel();
        tabs.addTab("設定", settingsPage);
        settingsButton.addActionListener(event -> tabs.setSelectedComponent(settingsPage));
        add(tabs, BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
        add.addActionListener(event -> configure(null));
        edit.addActionListener(event -> { if (selectedServer() != null) configure(selectedServer()); });
        remove.addActionListener(event -> removeServer());
        refresh.addActionListener(event -> reload());
        parameters.addActionListener(event -> configureModel());
        servers.addActionListener(event -> {
            if (!changing) { resetChat(); usage.clear(); reload(); }
        });
        models.addActionListener(event -> { if (!changing && !busy) { resetChat(); controls(); } });
        send.addActionListener(event -> send());
        stop.addActionListener(event -> cancel());
        newChat.addActionListener(event -> { resetChat(); prompt.setText(""); draftImages.clear(); renderImages(); });
        mode.addActionListener(event -> { resetChat(); controls(); });
        agentDirectory.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { resetChat(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { resetChat(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { resetChat(); }
        });
        prompt.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send");
        prompt.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "send");
        prompt.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), javax.swing.text.DefaultEditorKit.insertBreakAction);
        prompt.getActionMap().put("send", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { if (send.isEnabled()) send(); }
        });
        chooseServer(null);
        refreshTimer = new Timer(settings.usageRefreshSeconds() * 1000, event -> { if (isShowing()) reloadUsage(); });
        refreshTimer.start();
        intervalConnection = com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().connect();
        intervalConnection.subscribe(UsageWidgetFactory.INTERVALS, () -> ui(() -> {
            int delay = settings.usageRefreshSeconds() * 1000;
            refreshTimer.setDelay(delay); refreshTimer.setInitialDelay(delay); refreshTimer.restart();
        }));
        controls();
        reload();
        interfaceScale.apply(this, settings.getState().interfaceScalePercent);
    }

    void attachHost(JPanel host) {
        presentation = new FullScreenView(this, host, this::presentationChanged);
        fullScreenButton.setEnabled(true);
    }

    void setParallelLauncher(java.util.function.Predicate<ParallelDraft> launcher) { parallelLauncher = launcher; }
    void startParallel(ParallelDraft draft) {
        generation++;
        if (refreshTask != null) refreshTask.cancel(true);
        loading = false; changing = true;
        try { chooseServer(draft.server().id); models.removeAllItems(); models.addItem(draft.model()); }
        finally { changing = false; }
        mode.setSelectedItem("Agent"); agentDirectory.setText(draft.scope());
        prompt.setText(draft.text()); draftImages.addAll(draft.images()); controls(); send();
    }

    private void launchParallel() {
        if (encodingImage || parallelLauncher == null || selectedServer() == null || selectedModel() == null) return;
        String text = prompt.getText().trim();
        if (text.isBlank() && draftImages.isEmpty()) { status.setText("請先輸入另一個任務或附加截圖。"); return; }
        var draft = new ParallelDraft(selectedServer(), selectedModel(), agentDirectory.getText().trim(), text, draftImages);
        if (!parallelLauncher.test(draft)) { status.setText("最多開啟 4 個任務分頁，請先關閉已完成的分頁。"); return; }
        prompt.setText(""); draftImages.clear(); renderImages();
        if (presentation != null) presentation.restore();
    }

    private void setFullScreen(boolean fullScreen) {
        if (presentation == null || disposed) return;
        try { if (fullScreen) presentation.enter(); else presentation.restore(); }
        catch (RuntimeException e) {
            presentationChanged();
            status.setText("無法開啟全螢幕；已保留目前對話並回到一般大小。");
        }
    }

    private void presentationChanged() {
        boolean fullScreen = presentation != null && presentation.isFullScreen();
        changingPresentation = true;
        try {
            fullScreenButton.setText(fullScreen ? "一般大小" : "全螢幕");
            viewMode.setSelectedIndex(fullScreen ? 1 : 0);
        } finally { changingPresentation = false; }
        SwingUtilities.invokeLater(() -> { if (!disposed) prompt.requestFocusInWindow(); });
    }

    @Override public void uiDataSnapshot(com.intellij.openapi.actionSystem.DataSink sink) {
        sink.set(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project);
    }

    private JPanel chatPanel() {
        transcript.setEditable(false); transcript.setLineWrap(true); transcript.setWrapStyleWord(true);
        prompt.setLineWrap(true); prompt.setWrapStyleWord(true);
        prompt.setFilesReceiver(this::dropFiles);
        var transcriptTextHandler = transcript.getTransferHandler();
        transcript.setTransferHandler(new TransferHandler() {
            @Override public void exportToClipboard(JComponent component, java.awt.datatransfer.Clipboard clipboard, int action) {
                if (transcriptTextHandler != null) transcriptTextHandler.exportToClipboard(component, clipboard, action);
            }
            @Override public int getSourceActions(JComponent component) {
                return transcriptTextHandler == null ? NONE : transcriptTextHandler.getSourceActions(component);
            }
            @Override public boolean canImport(TransferSupport support) {
                return prompt.getTransferHandler().canImport(new TransferSupport(prompt, support.getTransferable()));
            }
            @Override public boolean importData(TransferSupport support) {
                if (support.isDrop()) {
                    if ((support.getSourceDropActions() & COPY) == 0) return false;
                    support.setDropAction(COPY);
                }
                return prompt.getTransferHandler().importData(prompt, support.getTransferable());
            }
        });
        int savedSize = settings.getState().chatFontSize;
        setChatFontSize(savedSize > 0 ? savedSize : defaultChatFont.getSize());
        transcript.setToolTipText("Ctrl＋滑鼠滾輪可放大／縮小對話文字。");
        var panel = new JPanel(new BorderLayout(4, 4));
        panel.add(chatScrollPane(transcript), BorderLayout.CENTER);
        var input = new JPanel(new BorderLayout(4, 4));
        imagePreviews.setLayout(new BoxLayout(imagePreviews, BoxLayout.X_AXIS));
        imageScroll.setPreferredSize(new Dimension(0, 118));
        imageScroll.setVisible(false);
        input.add(imageScroll, BorderLayout.NORTH);
        input.add(chatScrollPane(prompt), BorderLayout.CENTER);
        prompt.setToolTipText("拖入圖片／文字檔案或程式碼；Enter 傳送，Shift+Enter 換行；Ctrl+V 仍可貼上截圖。");
        parallel.addActionListener(event -> launchParallel());
        continueAgent.addActionListener(event -> {
            if (!canContinueAgent || busy || loading || encodingImage) return;
            if (prompt.getText().isBlank() && draftImages.isEmpty()) prompt.setText("請接續尚未完成的任務，沿用目前對話中的工具結果與 review 進度，勿重新開始已完成的檢查；完成剩餘檢查後給出最終結果。");
            send();
        });
        var actions = new CompactActions(send, stop, newChat, parallel, continueAgent);
        input.add(actions, BorderLayout.SOUTH); panel.add(input, BorderLayout.SOUTH);
        return panel;
    }

    private JScrollPane chatScrollPane(JTextArea text) {
        return new JScrollPane(text) {
            @Override protected void processMouseWheelEvent(java.awt.event.MouseWheelEvent event) {
                if (event.isControlDown()) {
                    int size = (int) Math.round(transcript.getFont().getSize2D() / interfaceScale.factor()) - event.getWheelRotation();
                    setChatFontSize(size);
                    settings.getState().chatFontSize = Math.max(10, Math.min(40, size));
                    event.consume();
                } else super.processMouseWheelEvent(event);
            }
        };
    }

    private void setChatFontSize(int size) {
        float bounded = Math.max(10, Math.min(40, size));
        var font = ChatFonts.create(settings.getState().chatFontFamily, defaultChatFont, bounded);
        interfaceScale.setFont(transcript, font);
        interfaceScale.setFont(prompt, font);
        interfaceScale.setFont(fontSample, font);
        fontSample.setText("實際字體：" + font.getFamily() + "\n你好，這是一段對話文字範例。\nThe quick brown fox · 0123456789");
    }

    private JPanel settingsPanel() {
        var panel = new JPanel(new BorderLayout(8, 8));
        var configuration = new JPanel();
        configuration.setLayout(new BoxLayout(configuration, BoxLayout.Y_AXIS));
        var serverBar = new JPanel(new BorderLayout(4, 4));
        serverBar.add(new JLabel("伺服器"), BorderLayout.WEST); serverBar.add(servers, BorderLayout.CENTER);
        var serverActions = new JPanel(new GridLayout(1, 4, 4, 0));
        serverActions.add(add); serverActions.add(edit); serverActions.add(remove); serverActions.add(refresh);
        serverBar.add(serverActions, BorderLayout.SOUTH);
        var modelBar = new JPanel(new BorderLayout(4, 4));
        modelBar.add(new JLabel("目前模型："), BorderLayout.WEST);
        modelBar.add(parameterModel, BorderLayout.CENTER); modelBar.add(parameters, BorderLayout.EAST);
        var agentBar = new JPanel(new BorderLayout(4, 4));
        agentBar.add(new JLabel("Agent 工作目錄："), BorderLayout.WEST); agentBar.add(agentDirectory, BorderLayout.CENTER);
        configuration.add(serverBar); configuration.add(Box.createVerticalStrut(12));
        configuration.add(modelBar); configuration.add(Box.createVerticalStrut(12)); configuration.add(agentBar);
        panel.add(configuration, BorderLayout.NORTH);
        panel.add(helpPanel(), BorderLayout.CENTER);
        return panel;
    }

    private void pasteImage(Image image) {
        if (encodingImage) { status.setText("請等圖片處理完成，再貼上截圖。"); return; }
        if (draftImages.size() >= ImageAttachment.MAX_COUNT) { status.setText("每次最多附加 4 張截圖。"); return; }
        encodingImage = true; controls(); status.setText("處理截圖…");
        workers.submit(() -> {
            try {
                var attachment = ImageAttachment.from(image);
                ui(() -> {
                    encodingImage = false;
                    int total = draftImages.stream().mapToInt(ImageAttachment::bytes).sum();
                    if (total + attachment.bytes() > ImageAttachment.MAX_TOTAL_BYTES) status.setText("附件合計不可超過 20 MiB。");
                    else { draftImages.add(attachment); renderImages(); status.setText("已附加截圖；可繼續輸入說明，再按傳送。"); }
                    controls();
                });
            } catch (Exception e) {
                ui(() -> { encodingImage = false; controls(); status.setText("無法附加截圖：" + e.getMessage()); });
            }
        });
    }

    private void pasteStatus(String message) { status.setText(message); }

    private void dropFiles(List<java.io.File> files) {
        if (encodingImage) { status.setText("請等目前附件處理完成。"); return; }
        if (files.size() > 8) { status.setText("一次最多拖入 8 個檔案。"); return; }
        encodingImage = true; controls(); status.setText("讀取拖入的附件…");
        workers.submit(() -> {
            var attachments = new ArrayList<DroppedFile>(); var errors = new ArrayList<String>();
            for (var file : files) {
                try { attachments.add(DroppedFile.read(file.toPath())); }
                catch (Exception e) { errors.add(file.getName() + "：" + e.getMessage()); }
            }
            ui(() -> {
                encodingImage = false; int accepted = 0;
                for (var attachment : attachments) {
                    if (attachment.image() == null) { prompt.append(attachment.draftText()); accepted++; }
                    else {
                        long bytes = draftImages.stream().mapToLong(ImageAttachment::bytes).sum();
                        if (draftImages.size() >= ImageAttachment.MAX_COUNT || bytes + attachment.image().bytes() > ImageAttachment.MAX_TOTAL_BYTES)
                            errors.add(attachment.name() + "：圖片附件超過 4 張／20 MiB。");
                        else { draftImages.add(attachment.image()); accepted++; }
                    }
                }
                renderImages(); controls();
                status.setText("已附加 " + accepted + " 個檔案。" + String.join("；", errors));
            });
        });
    }

    private void renderImages() {
        imagePreviews.removeAll();
        for (var image : draftImages) {
            var card = new JPanel(new BorderLayout(2, 2));
            card.add(new JLabel(image.preview()), BorderLayout.CENTER);
            var removeImage = new JButton("移除 " + image.width() + "×" + image.height());
            removeImage.setEnabled(!encodingImage);
            removeImage.addActionListener(event -> { draftImages.remove(image); renderImages(); });
            card.add(removeImage, BorderLayout.SOUTH);
            card.setMaximumSize(card.getPreferredSize()); imagePreviews.add(card);
            imagePreviews.add(Box.createHorizontalStrut(6));
        }
        imageScroll.setVisible(!draftImages.isEmpty());
        interfaceScale.apply(imagePreviews, (int) Math.round(interfaceScale.factor() * 100));
        imagePreviews.revalidate(); imagePreviews.repaint();
    }

    private JPanel helpPanel() {
        var panel = new JPanel(new BorderLayout(8, 8));
        var text = new JTextArea("MoLLM IntelliJ 0.7.0\n\n"
                + "1. 新增 Base URL 與 API Key。\n2. 重新整理後選擇模型。\n3. Enter 傳送，Shift+Enter 換行；Ctrl+Enter 亦可傳送。\n"
                + "4. 將圖片或文字檔案拖入對話區／輸入框附加；程式碼選取內容也可拖入草稿。\n\n"
                + "在對話或輸入框上 Ctrl＋滾輪調整文字大小（10–40），並記住大小。\n\n"
                + "上方「全螢幕」放大 MoLLM；「一般大小」或 Esc 可回到原本工具視窗，對話與 Agent 持續保留。\n"
                + "設定頁可調整 MoLLM 介面縮放 75%–200%，不改動 IDE 的全域縮放設定。\n\n"
                + "金鑰存入 IntelliJ Password Safe；對話只保留在目前視窗記憶體。\n"
                + "Agent 執行中仍可輸入，按 Enter／引導 Agent 追加指示；目前操作完成後會讀取並重新規劃。\n"
                + "「並行任務」以草稿開啟獨立 Agent 分頁，最多 4 個；停止或關閉分頁只影響該任務。\n"
                + "設定可選系統內已安裝的對話字體，保留字體大小與介面縮放設定。\n"
                + "用量更新頻率可設定為 30 秒、1 分鐘或 3 分鐘，預設 1 分鐘；管理路由無權限時仍可對話。\n"
                + "切換 Agent 後可搜尋與讀取專案、修改／新增檔案、執行指令與測試。\n"
                + "讀取與搜尋自動執行；每次修改會開啟 diff 確認，指令則顯示完整內容供你允許或拒絕。\n"
                + "確認時可選「允許一次」或「此工作目錄下永遠允許」；檔案與指令權限分開，在設定的 Agent 權限管理查看／撤銷。\n"
                + "讀取／列檔／搜尋支援隱藏目錄及專案外路徑；修改限工作目錄，指令經你允許後具有一般系統權限。\n"
                + "在對話輸入框按 Ctrl+V 可貼上截圖，預覽下方可移除；Chat 與 Agent 都可傳送圖片。\n"
                + "每次最多 4 張、單張 PNG 10 MiB／1600 萬像素、合計 20 MiB；需模型與閘道支援圖片輸入。\n"
                + "新對話預設 Agent。每段預設 80 模型回合／256 次工具，可在設定調整或勾選「不設限」；達上限可按「繼續 Agent」接續既有進度。\n"
                + "連續兩次相同工具錯誤會暫停並保留進度，可調整後續作。設定可開啟每輪串流診斷。\n"
                + "新增列目錄、依檔名搜尋與分頁；MR 報告使用 mr<編號>-mollm.md，同名採數字後綴，可分段寫入。\n"
                + "報告基本檢查只確認儲存與格式，不代表程式問題已重現或測試已通過。\n"
                + "停止會取消待確認操作並終止目前指令，已套用修改仍保留。\n"
                + "回覆以純文字呈現（含 Markdown 原文），停止或出錯的回覆不加入後續對話歷史。\n"
                + "切換伺服器或模型會開始新對話。模型刷新時若原模型仍存在則保留歷史。\n"
                + "原始 MoLLM VS Code 套件：yhho，MIT License。\n");
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        panel.add(new JScrollPane(text), BorderLayout.CENTER);
        var options = new JPanel(new GridLayout(0, 2, 8, 8));
        var timeout = new JSpinner(new SpinnerNumberModel(settings.getState().timeoutMs / 1000, 1, 3600, 10));
        var reasoning = new JComboBox<>(new String[]{"quoted", "inline", "hidden"});
        reasoning.setSelectedItem(settings.getState().reasoningDisplay);
        timeout.addChangeListener(event -> settings.getState().timeoutMs = (Integer) timeout.getValue() * 1000);
        reasoning.addActionListener(event -> settings.getState().reasoningDisplay = (String) reasoning.getSelectedItem());
        options.add(new JLabel("連線／串流閒置逾時（秒）")); options.add(timeout);
        options.add(new JLabel("思考內容（引言／混合／隱藏）")); options.add(reasoning);
        var frequency = new JComboBox<>(new String[]{"30 秒", "1 分鐘", "3 分鐘"});
        frequency.setSelectedIndex(switch (settings.usageRefreshSeconds()) { case 30 -> 0; case 180 -> 2; default -> 1; });
        frequency.addActionListener(event -> {
            settings.getState().usageRefreshSeconds = switch (frequency.getSelectedIndex()) { case 0 -> 30; case 2 -> 180; default -> 60; };
            UsageWidgetFactory.intervalChanged();
        });
        options.add(new JLabel("使用量更新頻率")); options.add(frequency);
        var scale = new JComboBox<>(new String[]{"75%", "100%", "125%", "150%", "175%", "200%"});
        scale.setSelectedItem(InterfaceScale.normalize(settings.getState().interfaceScalePercent) + "%");
        scale.addActionListener(event -> {
            settings.getState().interfaceScalePercent = Integer.parseInt(((String) scale.getSelectedItem()).replace("%", ""));
            interfaceScale.apply(this, settings.getState().interfaceScalePercent);
        });
        viewMode.addActionListener(event -> { if (!changingPresentation) setFullScreen(viewMode.getSelectedIndex() == 1); });
        options.add(new JLabel("MoLLM 介面縮放")); options.add(scale);
        options.add(new JLabel("MoLLM 視窗大小")); options.add(viewMode);
        var permissions = new JButton("Agent 權限管理");
        permissions.addActionListener(event -> {
            String base = project.getBasePath();
            if (base == null) { status.setText("此專案沒有可用工作目錄。"); return; }
            try {
                var root = WorkspaceTools.selectRoot(java.nio.file.Path.of(base), agentDirectory.getText().trim());
                new PermissionDialog(project, root, new DirectoryPermissions(settings)).show();
            } catch (Exception e) { status.setText("無法開啟權限設定，請檢查 Agent 工作目錄。"); }
        });
        options.add(new JLabel("Agent 工具權限")); options.add(permissions);
        var limits = AgentLimits.from(settings.getState());
        var rounds = new JSpinner(new SpinnerNumberModel(limits.rounds(), 1, AgentLimits.MAX_ROUNDS, 10));
        var operations = new JSpinner(new SpinnerNumberModel(limits.operations(), 1, AgentLimits.MAX_OPERATIONS, 32));
        var unlimited = new JCheckBox("不設限（模型回合與工具操作）", limits.unlimited());
        unlimited.setToolTipText("下一次 Agent 或續作生效；持續到完成、手動停止或請求失敗。仍受模型與服務限制。");
        rounds.setEnabled(!limits.unlimited()); operations.setEnabled(!limits.unlimited());
        unlimited.addActionListener(event -> {
            settings.getState().agentUnlimited = unlimited.isSelected();
            rounds.setEnabled(!unlimited.isSelected()); operations.setEnabled(!unlimited.isSelected());
        });
        options.add(new JLabel("Agent 執行限制")); options.add(unlimited);
        var diagnostics = new JCheckBox("顯示每輪串流診斷", settings.getState().showStreamDiagnostics);
        diagnostics.setToolTipText("顯示結束原因與服務提供的 Token 用量；IDE 日誌也會記錄這些數值，不記錄工具參數內容或金鑰。");
        diagnostics.addActionListener(event -> settings.getState().showStreamDiagnostics = diagnostics.isSelected());
        options.add(new JLabel("問題排查")); options.add(diagnostics);
        rounds.setToolTipText("下一次 Agent 或續作生效；預設 80，最大 200。調高會增加模型請求時間與用量。");
        operations.setToolTipText("下一次 Agent 或續作生效；預設 256，最大 2000。一次模型回合可執行多個工具。");
        rounds.addChangeListener(event -> settings.getState().agentMaxRounds = (Integer) rounds.getValue());
        operations.addChangeListener(event -> settings.getState().agentMaxOperations = (Integer) operations.getValue());
        options.add(new JLabel("Agent 模型回合上限")); options.add(rounds);
        options.add(new JLabel("Agent 工具操作上限")); options.add(operations);
        String chatDefault = "聊天預設（" + ChatFonts.defaultFamily() + "）";
        var fonts = new JComboBox<String>(); fonts.addItem(chatDefault); fonts.addItem("IDE 預設");
        for (var family : ChatFonts.families()) fonts.addItem(family);
        fonts.setSelectedItem("__chat_default__".equals(settings.getState().chatFontFamily) ? chatDefault
                : ChatFonts.available(settings.getState().chatFontFamily) ? settings.getState().chatFontFamily : "IDE 預設");
        fonts.addActionListener(event -> {
            String selected = (String) fonts.getSelectedItem(); settings.getState().chatFontFamily = selected.equals(chatDefault) ? "__chat_default__" : selected.equals("IDE 預設") ? "" : selected;
            setChatFontSize(settings.getState().chatFontSize > 0 ? settings.getState().chatFontSize : defaultChatFont.getSize());
        });
        options.add(new JLabel("對話與輸入框字體")); options.add(fonts);
        fontSample.setEditable(false); fontSample.setLineWrap(true); fontSample.setWrapStyleWord(true);
        var footer = new JPanel(new BorderLayout(0, 6)); footer.add(options, BorderLayout.NORTH);
        var preview = new JPanel(new BorderLayout(0, 4)); preview.add(new JLabel("即時字體預覽"), BorderLayout.NORTH);
        preview.add(new JScrollPane(fontSample), BorderLayout.CENTER); footer.add(preview, BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private MollmSettings.Server selectedServer() { return (MollmSettings.Server) servers.getSelectedItem(); }
    private Gateway.Model selectedModel() { return (Gateway.Model) models.getSelectedItem(); }
    private void chooseServer(String id) {
        changing = true;
        try {
            servers.removeAllItems();
            for (var server : settings.servers()) {
                servers.addItem(server);
                if (server.id.equals(id)) servers.setSelectedItem(server);
            }
        } finally { changing = false; }
    }

    private void configure(MollmSettings.Server original) {
        var dialog = new ServerDialog(project, original);
        if (!dialog.showAndGet()) return;
        var server = dialog.server();
        String key = dialog.key();
        boolean clearKey = dialog.clearKey();
        loading = true; controls();
        workers.submit(() -> {
            try {
                if (clearKey || !key.isBlank()) ApiKeys.set(server.id, clearKey ? "" : key);
                ui(() -> {
                    settings.save(server); loading = false; chooseServer(server.id);
                    resetChat(); usage.clear(); reload();
                });
            } catch (Exception e) { ui(() -> { loading = false; controls(); status.setText("金鑰儲存失敗，設定未變更。"); }); }
        });
    }

    private void removeServer() {
        var server = selectedServer();
        if (server == null || Messages.showYesNoDialog(project, "刪除「" + server.label + "」及其金鑰？", "MoLLM", Messages.getQuestionIcon()) != Messages.YES) return;
        loading = true; controls();
        workers.submit(() -> {
            try {
                ApiKeys.set(server.id, "");
                ui(() -> { settings.remove(server.id); loading = false; chooseServer(null); resetChat(); usage.clear(); reload(); });
            } catch (Exception e) { ui(() -> { loading = false; controls(); status.setText("無法刪除金鑰，設定未變更。"); }); }
        });
    }

    private void configureModel() {
        var server = selectedServer(); var model = selectedModel();
        if (server == null || model == null) return;
        var existing = server.overrides.getOrDefault(model.id(), new MollmSettings.ModelOverride());
        var output = new JSpinner(new SpinnerNumberModel(existing.maxOutputTokens, 0, 1_000_000, 1024));
        var effort = new JComboBox<>(new String[]{"", "minimal", "low", "medium", "high"});
        effort.setSelectedItem(existing.reasoningEffort);
        var thinking = new JSpinner(new SpinnerNumberModel(existing.thinkingBudgetTokens, 0, 1_000_000, 1024));
        var forceTools = new JCheckBox("閘道能力回報不準時，強制嘗試工具呼叫", existing.forceToolCalling);
        var forceImages = new JCheckBox("閘道能力回報不準時，強制嘗試圖片輸入", existing.forceImageInput);
        var panel = new JPanel(new GridLayout(0, 2, 8, 8));
        panel.add(new JLabel("輸出上限（0 = 自動）")); panel.add(output);
        panel.add(new JLabel("reasoning_effort")); panel.add(effort);
        panel.add(new JLabel("thinking budget（0 = 不指定）")); panel.add(thinking);
        panel.add(new JLabel("僅傳送閘道聲明支援的推理參數")); panel.add(new JLabel(""));
        panel.add(new JLabel("Agent")); panel.add(forceTools);
        panel.add(new JLabel("圖片")); panel.add(forceImages);
        if (JOptionPane.showConfirmDialog(this, panel, model.id(), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        var override = new MollmSettings.ModelOverride();
        override.maxOutputTokens = (Integer) output.getValue(); override.reasoningEffort = (String) effort.getSelectedItem();
        override.thinkingBudgetTokens = (Integer) thinking.getValue();
        override.forceToolCalling = forceTools.isSelected();
        override.forceImageInput = forceImages.isSelected();
        server.overrides.put(model.id(), override);
        settings.save(server);
        status.setText("已儲存 " + model.id() + " 的參數。");
    }

    private Gateway gateway(MollmSettings.Server server) {
        return new Gateway(server.baseUrl, ApiKeys.get(server.id), settings.getState().timeoutMs);
    }

    private void reload() {
        if (busy || loading || disposed) return;
        int ticket = ++generation;
        if (refreshTask != null) refreshTask.cancel(true);
        var server = selectedServer();
        var previous = selectedModel();
        if (server == null) {
            changing = true; models.removeAllItems(); changing = false;
            modelTable.setRowCount(0); controls(); status.setText("請先新增伺服器。"); return;
        }
        loading = true; controls(); status.setText("載入模型與用量…");
        refreshTask = workers.submit(() -> {
            Gateway api = null;
            try {
                api = gateway(server);
                List<Gateway.Model> all = api.models();
                List<Gateway.Model> available = all.stream().filter(model -> server.models.isEmpty() || server.models.contains(model.id())).toList();
                // Publish model discovery before optional usage endpoints, which may be slow.
                ui(() -> {
                    if (ticket != generation) return;
                    changing = true;
                    try {
                        models.removeAllItems();
                        for (var model : available) {
                            models.addItem(model);
                            if (previous != null && model.id().equals(previous.id())) models.setSelectedItem(model);
                        }
                        if (previous != null && (selectedModel() == null || !previous.id().equals(selectedModel().id()))) resetChat();
                    } finally { changing = false; }
                    modelTable.setRowCount(0);
                    for (var model : all) {
                        JsonObject info = model.info();
                        modelTable.addRow(new Object[]{model.id(), Gateway.string(info, "max_input_tokens", "—"),
                                Gateway.string(info, "max_output_tokens", "—"), Gateway.string(info, "supports_function_calling", "—"),
                                Gateway.string(info, "supports_vision", "—"), price(info, "input_cost_per_token"), price(info, "output_cost_per_token")});
                    }
                    status.setText("已取得 " + available.size() + " 個對話模型；載入用量…");
                    loading = false; controls();
                });
                var snapshot = api.usage();
                ui(() -> {
                    if (ticket != generation) return;
                    loading = false; usage.display(snapshot); controls();
                    UsageWidgetFactory.nudge();
                    status.setText("已更新 " + available.size() + " 個對話模型。" + (snapshot.warnings().isEmpty() ? "" : " 部分用量路由不可用，請看用量頁。"));
                });
            } catch (Exception e) {
                String message = api == null ? "金鑰或連線設定無法讀取。" : api.redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                ui(() -> {
                    if (ticket != generation) return;
                    loading = false; changing = true; models.removeAllItems(); changing = false;
                    modelTable.setRowCount(0); controls(); status.setText("載入失敗：" + message);
                });
            }
        });
    }

    private static String price(JsonObject info, String name) {
        return info.has(name) && !info.get(name).isJsonNull() ? String.format(Locale.ROOT, "%.4f", Gateway.number(info, name, 0) * 1_000_000) : "—";
    }

    private void reloadUsage() {
        var server = selectedServer();
        if (disposed || loading || usageLoading || server == null) return;
        usageLoading = true;
        int ticket = generation;
        workers.submit(() -> {
            Gateway api = null;
            try {
                api = gateway(server);
                var snapshot = api.usage();
                ui(() -> {
                    usageLoading = false;
                    if (ticket == generation) usage.display(snapshot);
                });
            } catch (Exception e) {
                String detail = api == null ? "金鑰或連線設定無法讀取。" : api.redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                ui(() -> {
                    usageLoading = false;
                    if (ticket == generation && !busy) status.setText("用量更新失敗：" + detail);
                });
            }
        });
    }

    private void send() {
        if (busy && activeInbox != null) { steer(); return; }
        if (draftImages.size() > ImageAttachment.MAX_COUNT || draftImages.stream().mapToLong(ImageAttachment::bytes).sum() > ImageAttachment.MAX_TOTAL_BYTES) {
            status.setText("還原的草稿附件超過限制，請移除部分圖片後再傳送（最多 4 張／20 MiB）。"); return;
        }
        var server = selectedServer(); var model = selectedModel();
        String draft = prompt.getText().trim();
        if (busy || loading || encodingImage || server == null || model == null || draft.isBlank() && draftImages.isEmpty()) return;
        String text = draft.isBlank() ? "請分析附加的截圖。" : draft;
        var images = List.copyOf(draftImages);
        var imageOverride = server.overrides.get(model.id());
        if (!images.isEmpty() && "false".equals(Gateway.string(model.info(), "supports_vision", "unknown"))
                && (imageOverride == null || !imageOverride.forceImageInput)) {
            status.setText("模型未支援圖片，請換視覺模型；能力回報不準時可在設定的模型參數強制嘗試。"); return;
        }
        boolean agent = "Agent".equals(mode.getSelectedItem());
        String scope = agentDirectory.getText().trim();
        String projectPath = project.getBasePath();
        if (agent) {
            if (projectPath == null) { status.setText("此專案沒有可用工作目錄。"); return; }
            var override = server.overrides.get(model.id());
            if (model.info().has("supports_function_calling")
                    && !Gateway.string(model.info(), "supports_function_calling", "false").equals("true")
                    && (override == null || !override.forceToolCalling)) {
                status.setText("模型未聲明工具支援；請換模型，或在模型參數勾選強制嘗試工具呼叫。"); return;
            }
        }
        var runLimits = AgentLimits.from(settings.getState());
        canContinueAgent = false;
        activeInbox = agent ? new AgentInbox() : null;
        var inbox = activeInbox;
        busy = true; controls(); prompt.setText("");
        draftImages.clear(); renderImages();
        transcript.append("\n你：\n" + text + (images.isEmpty() ? "" : "\n[附加 " + images.size() + " 張截圖]") + "\n\nMoLLM（" + model.id() + "）：\n");
        var requestHistory = new ArrayList<>(history);
        requestHistory.add(new Gateway.Message("user", text, images));
        var call = new Gateway.Call(); activeCall = call;
        String reasoningDisplay = settings.getState().reasoningDisplay;
        boolean showDiagnostics = settings.getState().showStreamDiagnostics;
        status.setText(agent ? "Agent 啟動中…" : "串流回覆中…");
        streamTask = workers.submit(() -> {
            Gateway api = null;
            AgentLoop engine = null;
            var answer = new StringBuilder();
            boolean[] thinking = {false};
            try (call) {
                api = gateway(server);
                java.util.function.BiConsumer<String, String> receive = (kind, value) -> {
                    String display;
                    if (kind.equals("text")) {
                        answer.append(value);
                        display = thinking[0] && reasoningDisplay.equals("quoted") ? "\n\n" + value : value;
                        thinking[0] = false;
                    } else {
                        display = switch (reasoningDisplay) {
                            case "hidden" -> "";
                            case "inline" -> value;
                            default -> (thinking[0] ? "" : "> 思考：\n> ") + value.replace("\n", "\n> ");
                        };
                        thinking[0] = true;
                    }
                    ui(() -> { if (activeCall == call && !call.cancelled()) { transcript.append(display); transcript.setCaretPosition(transcript.getDocument().getLength()); } });
                };
                List<Gateway.Message> completedHistory;
                boolean completed;
                if (agent) {
                    var root = WorkspaceTools.selectRoot(java.nio.file.Path.of(projectPath), scope);
                    var tools = new WorkspaceTools(root, new IdeWorkspace(project), new AgentApproval(project, root));
                    engine = new AgentLoop(tools, requestHistory, inbox, runLimits);
                    var safeApi = api;
                    var result = engine.run(api, model, server, call, receive, activity -> ui(() -> {
                        if (activeCall != call || call.cancelled()) return;
                        if (activity.startsWith("Agent 診斷") && !showDiagnostics) return;
                        String visible = safeApi.redact(activity);
                        status.setText(visible.lines().findFirst().orElse("Agent 工作中…"));
                        if (activity.startsWith("工具 ") || activity.startsWith("Agent 已讀取") || activity.startsWith("Agent 診斷") || activity.startsWith("Agent 報告檢查")) {
                            transcript.append("\n\n[" + visible + "]\n");
                            transcript.setCaretPosition(transcript.getDocument().getLength());
                        }
                    }));
                    completedHistory = result.messages(); completed = result.completed();
                } else {
                    var reply = api.stream(Gateway.request(model, requestHistory, server), call, receive);
                    var diagnostics = api.redact(reply.diagnostics());
                    if (showDiagnostics) ui(() -> { if (activeCall == call && !call.cancelled()) transcript.append("\n\n[串流診斷：" + diagnostics + "]\n"); });
                    if (reply.truncated()) throw new java.io.IOException("OUTPUT_TRUNCATED: 模型已達輸出額度，回覆不完整。" + diagnostics);
                    if (!reply.tools().isEmpty()) throw new java.io.IOException("模型要求工具呼叫；請切換 Agent 模式。");
                    var finished = new ArrayList<>(requestHistory);
                    if (!answer.isEmpty()) finished.add(new Gateway.Message("assistant", answer.toString(), List.of(), "", reply.reasoning()));
                    completedHistory = List.copyOf(finished); completed = true;
                }
                if (call.cancelled()) throw new java.util.concurrent.CancellationException();
                ui(() -> {
                    if (activeCall != call) return;
                    history.clear(); history.addAll(completedHistory);
                    if (call.cancelled()) {
                        transcript.append("\n\n[已停止；已完成的操作仍保留]\n"); status.setText("已停止。"); finish(call); return;
                    }
                    canContinueAgent = agent && !completed;
                    status.setText(completed ? (agent ? "Agent 完成。請檢查檔案變更與測試結果。" : "回覆完成。") : "Agent 本段未完成；請查看停止原因，調整後按「繼續 Agent」。"); finish(call);
                });
            } catch (Exception e) {
                var unconsumed = inbox == null ? List.<Gateway.Message>of() : inbox.close();
                String detail = api == null ? "金鑰無法讀取。" : api.redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                List<Gateway.Message> checkpoint = engine == null ? List.of() : engine.snapshot();
                ui(() -> {
                    if (activeCall != call) return;
                    if (agent && !checkpoint.isEmpty()) { history.clear(); history.addAll(checkpoint); }
                    if (checkpoint.isEmpty()) {
                        draftImages.addAll(images); renderImages();
                        if (prompt.getText().isBlank()) prompt.setText(draft);
                    }
                    if (!unconsumed.isEmpty()) {
                        for (var pending : unconsumed) {
                            prompt.append((prompt.getText().isBlank() ? "" : "\n\n") + pending.content());
                            draftImages.addAll(pending.images());
                        }
                        transcript.append("\n[尚未讀取的引導訊息已還原到草稿]\n"); renderImages();
                    }
                    if (call.cancelled()) {
                        transcript.append(agent ? "\n\n[Agent 已停止；已套用的修改仍保留，待執行操作已取消]\n" : "\n\n[已停止；此回覆未加入歷史]\n");
                        status.setText("已停止。");
                    } else {
                        transcript.append("\n\n[回覆失敗：" + detail + "]\n");
                        status.setText("回覆失敗。" + (agent ? "已執行的工具結果保留，可要求繼續。" : "可修改草稿後重試。"));
                    }
                    finish(call);
                });
            }
        });
    }

    private void steer() {
        if (encodingImage || activeInbox == null || activeCall == null || activeCall.cancelled()) return;
        String text = prompt.getText().trim();
        if (text.isBlank() && draftImages.isEmpty()) return;
        var model = selectedModel(); var server = selectedServer();
        var override = server.overrides.get(model.id());
        if (!draftImages.isEmpty() && "false".equals(Gateway.string(model.info(), "supports_vision", "unknown"))
                && (override == null || !override.forceImageInput)) { status.setText("此模型不支援圖片引導，請改用文字。"); return; }
        var message = new Gateway.Message("user", text.isBlank() ? "請參考附加截圖調整任務。" : text, draftImages);
        try {
            if (!activeInbox.submit(message)) { status.setText("Agent 已結束，請稍後再傳送；草稿已保留。"); return; }
            prompt.setText(""); draftImages.clear(); renderImages();
            transcript.append("\n\n[你的引導訊息，待 Agent 讀取]\n" + message.content() + "\n");
            status.setText("引導訊息已排入，Agent 會在目前操作完成後重新規劃。");
        } catch (IllegalStateException e) { status.setText(e.getMessage()); }
    }

    private void finish(Gateway.Call call) {
        if (activeCall != call) return;
        activeCall = null; activeInbox = null; busy = false; controls();
        UsageWidgetFactory.nudge();
    }
    private void cancel() {
        if (activeCall == null) return;
        activeCall.cancel();
        stop.setEnabled(false); status.setText("正在停止並取消待確認操作…");
    }
    private void resetChat() {
        history.clear(); transcript.setText(""); canContinueAgent = false;
        continueAgent.setVisible(false); continueAgent.setEnabled(false);
    }
    private void controls() {
        boolean idle = !busy && !loading;
        servers.setEnabled(idle); models.setEnabled(idle); add.setEnabled(idle); edit.setEnabled(idle && selectedServer() != null);
        remove.setEnabled(idle && selectedServer() != null); refresh.setEnabled(idle && selectedServer() != null);
        parameterModel.setText(selectedModel() == null ? "尚未選擇" : selectedModel().id());
        parameters.setEnabled(idle && selectedModel() != null); newChat.setEnabled(!busy && !encodingImage);
        send.setText(busy && activeInbox != null ? "引導 Agent" : "傳送");
        send.setEnabled((idle || busy && activeInbox != null) && !encodingImage && selectedModel() != null
                && (activeCall == null || !activeCall.cancelled())); stop.setEnabled(busy);
        parallel.setEnabled(!loading && !encodingImage && selectedModel() != null && parallelLauncher != null);
        continueAgent.setVisible(canContinueAgent);
        continueAgent.setEnabled(canContinueAgent && idle && !encodingImage && "Agent".equals(mode.getSelectedItem()));
        mode.setEnabled(idle); agentDirectory.setEnabled(idle && "Agent".equals(mode.getSelectedItem()));
        if (activeCall != null && activeCall.cancelled()) stop.setEnabled(false);
        renderImages();
    }
    private void ui(Runnable action) { SwingUtilities.invokeLater(() -> { if (!disposed && !project.isDisposed()) action.run(); }); }
    @Override public void dispose() {
        disposed = true; refreshTimer.stop();
        if (presentation != null) presentation.dispose();
        intervalConnection.disconnect();
        var call = activeCall;
        if (call != null) call.cancel();
        workers.shutdownNow();
    }
}
