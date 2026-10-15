package tw.com.fmt.mollm;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

final class ChatLayoutTest {
    private static int checks;
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    static void run() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var transcript = new JTextArea("你：\n請 review MR 51，並產生報告。\n\nMoLLM：\n已定位專案並讀取審查規範。\n正在檢查變更與必要的呼叫端。\n\n工具結果會保留，可繼續輸入訊息引導 Agent。\n");
            transcript.setEditable(false); transcript.setLineWrap(true); transcript.setWrapStyleWord(true);
            transcript.setFont(ChatFonts.create("__chat_default__", transcript.getFont(), 15)); transcript.setMargin(new Insets(12, 12, 12, 12));
            var prompt = new JTextArea("請優先檢查交易邊界與錯誤處理。", 5, 30);
            prompt.setFont(transcript.getFont()); prompt.setMargin(new Insets(10, 12, 10, 12));
            var status = new JLabel("Agent 工作中 · Enter 傳送，Shift+Enter 換行");
            var send = new JButton("引導 Agent"); var stop = new JButton("停止"); var next = new JButton("新對話"); var parallel = new JButton("並行任務");
            var bar = new ChatActionBar(status, send, stop, next, parallel);
            var composer = new JPanel(new BorderLayout(6, 6));
            var input = new JScrollPane(prompt); input.setMinimumSize(new Dimension(0, 64));
            composer.add(input, BorderLayout.CENTER); composer.add(bar, BorderLayout.SOUTH);
            var output = new JScrollPane(transcript); output.setMinimumSize(new Dimension(0, 80));
            var saved = new AtomicReference<>(.75);
            var split = new ChatSplitPane(output, composer, saved.get(), saved::set);
            var host = new JPanel(new BorderLayout(8, 8)); host.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            var header = new JPanel(new BorderLayout(6, 0)); header.add(new JComboBox<>(new String[]{"Agent"}), BorderLayout.WEST);
            header.add(new JComboBox<>(new String[]{"bedrock/claude-opus-5-5"}), BorderLayout.CENTER);
            var settings = new JButton("設定"); header.add(settings, BorderLayout.EAST);
            host.add(header, BorderLayout.NORTH); host.add(split, BorderLayout.CENTER); host.setSize(900, 560); layout(host);
            split.restoreProportion(); layout(host);
            check(split.isContinuousLayout() && split.getOrientation() == JSplitPane.VERTICAL_SPLIT, "Conversation/input divider uses native drag support");
            split.setDividerLocation(250); layout(host);
            double userRatio = saved.get();
            check(userRatio > .4 && userRatio < .6, "User divider movement saves the actual proportion");
            host.setSize(900, 720); layout(host); split.restoreProportion(); layout(host);
            check(Math.abs((double) split.getDividerLocation() / (split.getHeight() - split.getDividerSize()) - userRatio) < .02, "Resize/fullscreen restores user proportion");
            check(status.getParent() == bar && status.getX() + status.getWidth() == bar.getWidth() - bar.getInsets().right, "Status is anchored at right edge of action row");
            check(send.getWidth() < 160 && send.getBounds().intersects(status.getBounds()) == false, "Actions retain compact size and do not overlap status");
            status.setText("Agent 正在讀取檔案，這是一段很長的完整狀態文字，滑鼠懸停可以檢視完整內容。"); layout(host);
            check(status.getToolTipText().equals(status.getText()) && status.getWidth() < status.getPreferredSize().width, "Long status clips cleanly and exposes full tooltip");
            host.setSize(220, 560); layout(host);
            for (var button : new JButton[]{send, stop, next, parallel}) check(button.getY() + button.getHeight() <= bar.getHeight(), "Narrow action row reserves wrapped height");
            check(!status.getBounds().intersects(parallel.getBounds()) && status.getX() >= 0 && status.getY() + status.getHeight() <= bar.getHeight(), "Narrow status remains inside toolbar without overlap");
            var data = new MollmSettings.Data(); data.chatSplitRatio = userRatio;
            var restored = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(data), MollmSettings.Data.class);
            check(restored.chatSplitRatio == userRatio && ChatSplitPane.validRatio(Double.NaN) == .75, "Divider ratio persists and invalid values fall back");
            host.setSize(900, 560); layout(host); split.restoreProportion(); layout(host);
            var canvas = new java.awt.image.BufferedImage(900, 560, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var graphics = canvas.createGraphics(); try { host.printAll(graphics); } finally { graphics.dispose(); }
            try { javax.imageio.ImageIO.write(canvas, "png", Path.of("build/chat-layout-preview.png").toFile()); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        System.out.println("PASS: " + checks + " resizable chat layout checks.");
    }
    private static void layout(Container parent) { parent.doLayout(); for (var child : parent.getComponents()) if (child instanceof Container container) layout(container); }
}
