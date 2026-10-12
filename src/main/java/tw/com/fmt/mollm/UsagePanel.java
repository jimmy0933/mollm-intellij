package tw.com.fmt.mollm;

import com.google.gson.JsonObject;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class UsagePanel extends JPanel {
    private final Map<String, JLabel> values = new java.util.LinkedHashMap<>();
    private final JLabel budgetDetail = new JLabel("—");
    private final JLabel failedDetail = new JLabel("—");
    private final JProgressBar budgetBar = new JProgressBar(0, 1000);
    private final JPanel cards = new JPanel(new GridLayout(3, 3, 18, 16));
    private final DailyChart chart = new DailyChart();
    private final DefaultTableModel table = new DefaultTableModel(new String[]{"模型", "消費 USD", "請求", "Tokens"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTextArea warning = new JTextArea(3, 30);

    UsagePanel() {
        super(new BorderLayout(8, 8));
        warning.setEditable(false);
        warning.setLineWrap(true);
        warning.setWrapStyleWord(true);
        cards.setBorder(BorderFactory.createEmptyBorder(14, 12, 14, 12));
        cards.setPreferredSize(new Dimension(620, 420));
        addCard("key", "金鑰"); var budgetCard = addCard("budget", "預算使用率"); addCard("rpm", "RPM 上限");
        addCard("tpm", "TPM 上限"); addCard("reset", "預算重置"); addCard("period", "近 30 日");
        addCard("requests", "請求次數"); var successCard = addCard("success", "成功率"); addCard("tokens", "Token 合計");
        var budgetFooter = new JPanel(new BorderLayout(0, 4));
        budgetBar.setPreferredSize(new Dimension(100, 4)); budgetBar.setForeground(new Color(0xD58AA1));
        budgetFooter.add(budgetBar, BorderLayout.NORTH); budgetFooter.add(budgetDetail, BorderLayout.CENTER);
        budgetCard.add(budgetFooter, BorderLayout.SOUTH); successCard.add(failedDetail, BorderLayout.SOUTH);
        var top = new SummaryScrollPanel(); top.add(cards, BorderLayout.NORTH); top.add(chart, BorderLayout.CENTER);
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(top), new JScrollPane(new JTable(table)));
        split.setResizeWeight(.8);
        add(split, BorderLayout.CENTER);
        add(new JScrollPane(warning), BorderLayout.SOUTH);
        clear();
    }

    void clear() {
        values.values().forEach(value -> value.setText("—"));
        budgetBar.setValue(0); budgetBar.setVisible(false); budgetDetail.setText("—"); failedDetail.setText("—");
        warning.setText(""); table.setRowCount(0); chart.update(List.of(), false);
    }

    private JPanel addCard(String id, String caption) {
        var card = new JPanel(new BorderLayout(0, 8));
        var title = new JLabel(caption);
        Color muted = UIManager.getColor("Label.disabledForeground");
        if (muted != null) title.setForeground(muted);
        var value = new JLabel("—"); value.setVerticalAlignment(SwingConstants.TOP);
        value.setFont(new Font(Font.SERIF, Font.PLAIN, 25));
        values.put(id, value); card.add(title, BorderLayout.NORTH); card.add(value, BorderLayout.CENTER); cards.add(card);
        return card;
    }
    private void value(String id, String text) {
        // Gateway aliases are data: escape them before using Swing's HTML layout.
        values.get(id).setText("<html>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>");
        values.get(id).setToolTipText("資訊：" + text);
    }

    void display(Gateway.Usage usage) {
        JsonObject key = usage.key();
        double spend = Gateway.number(key, "spend", 0);
        double budget = Gateway.number(key, "max_budget", 0);
        var days = new TreeMap<String, double[]>();
        var byModel = new TreeMap<String, double[]>();
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        for (int i = 29; i >= 0; i--) days.put(end.minusDays(i).toString(), new double[4]);
        for (var value : usage.days()) {
            var row = value.getAsJsonObject();
            String date = Gateway.string(row, "date", "");
            if (date.length() > 10) date = date.substring(0, 10);
            if (!days.containsKey(date)) continue;
            var metrics = Gateway.object(row, "metrics");
            double[] total = days.get(date);
            total[0] += Gateway.number(metrics, "spend", 0);
            total[1] += Gateway.number(metrics, "api_requests", 0);
            total[2] += Gateway.number(metrics, "failed_requests", 0);
            total[3] += Gateway.number(metrics, "total_tokens", Gateway.number(metrics, "prompt_tokens", 0) + Gateway.number(metrics, "completion_tokens", 0));
            var modelRows = Gateway.object(Gateway.object(row, "breakdown"), "models");
            for (var entry : modelRows.entrySet()) {
                var modelMetrics = Gateway.object(entry.getValue().getAsJsonObject(), "metrics");
                double[] modelTotal = byModel.computeIfAbsent(entry.getKey(), ignored -> new double[3]);
                modelTotal[0] += Gateway.number(modelMetrics, "spend", 0);
                modelTotal[1] += Gateway.number(modelMetrics, "api_requests", 0);
                modelTotal[2] += Gateway.number(modelMetrics, "total_tokens",
                        Gateway.number(modelMetrics, "prompt_tokens", 0) + Gateway.number(modelMetrics, "completion_tokens", 0));
            }
        }
        double periodSpend = days.values().stream().mapToDouble(value -> value[0]).sum();
        double requests = days.values().stream().mapToDouble(value -> value[1]).sum();
        double failed = days.values().stream().mapToDouble(value -> value[2]).sum();
        double tokens = days.values().stream().mapToDouble(value -> value[3]).sum();
        value("key", Gateway.string(key, "key_alias", "未提供"));
        value("budget", key.isEmpty() ? "—" : budget > 0 ? String.format(Locale.ROOT, "%.1f%%", spend / budget * 100) : money(spend));
        budgetBar.setVisible(budget > 0); budgetBar.setValue(budget > 0 ? (int) Math.max(0, Math.min(1000, spend / budget * 1000)) : 0);
        budgetDetail.setText(budget > 0 ? "<html>" + money(spend) + " / " + money(budget) + "<br>剩餘 " + money(budget - spend) + "</html>" : key.isEmpty() ? "未取得金鑰資料" : "累計消費（未設定預算）");
        value("rpm", Gateway.string(key, "rpm_limit", "—")); value("tpm", Gateway.string(key, "tpm_limit", "—"));
        String reset = Gateway.string(key, "budget_reset_at", "—");
        value("reset", reset.length() >= 10 ? reset.substring(0, 10) : reset);
        boolean dailyAvailable = usage.warnings().stream().noneMatch(w -> w.startsWith("/user/daily/activity:"));
        value("period", dailyAvailable ? money(periodSpend) : "—"); value("requests", dailyAvailable ? count(requests) : "—");
        value("success", requests > 0 && dailyAvailable ? String.format(Locale.ROOT, "%.1f%%", Math.max(0, 1 - failed / requests) * 100) : "—");
        failedDetail.setText(dailyAvailable ? "失敗 " + count(failed) : "未取得用量資料");
        value("tokens", dailyAvailable ? compact(tokens) : "—"); values.get("tokens").setToolTipText(count(tokens));
        warning.setText(String.join("\n", usage.warnings()));
        table.setRowCount(0);
        byModel.entrySet().stream().sorted((left, right) -> Double.compare(right.getValue()[periodSpend > 0 ? 0 : 1], left.getValue()[periodSpend > 0 ? 0 : 1]))
                .forEach(entry -> table.addRow(new Object[]{entry.getKey(), money(entry.getValue()[0]), count(entry.getValue()[1]), count(entry.getValue()[2])}));
        chart.update(days.entrySet().stream().map(entry -> new Day(entry.getKey(), entry.getValue()[periodSpend > 0 ? 0 : 1])).toList(), periodSpend > 0);
    }

    static String money(double value) { return String.format(Locale.ROOT, Math.abs(value) >= 1 || value == 0 ? "$%.2f" : "$%.5f", value); }
    private static String compact(double value) {
        if (value >= 1_000_000_000) return String.format(Locale.ROOT, "%.1fB", value / 1_000_000_000);
        if (value >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000);
        if (value >= 10_000) return String.format(Locale.ROOT, "%.1fK", value / 1000);
        return count(value);
    }
    private static String count(double value) { return NumberFormat.getIntegerInstance().format(value); }
    private record Day(String date, double value) { }
    private static final class SummaryScrollPanel extends JPanel implements Scrollable {
        SummaryScrollPanel() { super(new BorderLayout()); }
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(620, 600); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(16, visible.height - 20); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
    private static final class DailyChart extends JPanel {
        private List<Day> days = List.of();
        private boolean dollars;
        DailyChart() { setPreferredSize(new Dimension(320, 180)); }
        void update(List<Day> data, boolean money) { days = data; dollars = money; repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            var g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getForeground());
                var metrics = g.getFontMetrics();
                int padding = Math.max(8, metrics.getHeight() / 2);
                int titleBaseline = Math.max(20, metrics.getAscent() + 6);
                double max = days.stream().mapToDouble(Day::value).max().orElse(0);
                g.drawString("近 30 日（UTC） " + (dollars ? "消費 USD，最高 " + money(max) : "請求次數，最高 " + count(max)), padding, titleBaseline);
                int baseline = getHeight() - metrics.getHeight() - 10;
                int available = Math.max(1, baseline - titleBaseline - 15);
                double width = Math.max(1, getWidth() - padding * 2) / 30.0;
                g.setColor(new Color(0x6C9AD1));
                for (int i = 0; i < days.size(); i++) {
                    int height = max > 0 ? (int) (days.get(i).value() / max * available) : 0;
                    g.fillRect(padding + (int) (i * width), baseline - height, Math.max(1, (int) width - 2), height);
                }
                g.setColor(getForeground());
                if (!days.isEmpty()) {
                    String first = days.getFirst().date().substring(5), last = days.getLast().date().substring(5);
                    int labelBaseline = getHeight() - metrics.getDescent() - 4;
                    g.drawString(first, padding, labelBaseline);
                    g.drawString(last, Math.max(padding, getWidth() - metrics.stringWidth(last) - padding), labelBaseline);
                }
            } finally { g.dispose(); }
        }
    }
}
