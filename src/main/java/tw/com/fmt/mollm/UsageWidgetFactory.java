package tw.com.fmt.mollm;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class UsageWidgetFactory implements StatusBarWidgetFactory {
    public interface UsageListener { void refresh(); }
    public interface IntervalListener { void changed(); }
    static final com.intellij.util.messages.Topic<IntervalListener> INTERVALS = com.intellij.util.messages.Topic.create("MoLLM usage interval", IntervalListener.class);
    static void intervalChanged() {
        com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().syncPublisher(INTERVALS).changed();
    }
    private static final com.intellij.util.messages.Topic<UsageListener> UPDATES = com.intellij.util.messages.Topic.create("MoLLM usage refresh", UsageListener.class);
    static void nudge() {
        com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().syncPublisher(UPDATES).refresh();
    }
    @Override public @NotNull String getId() { return "MoLLM.Usage"; }
    @Override public @NotNull String getDisplayName() { return "MoLLM 用量"; }
    @Override public @NotNull StatusBarWidget createWidget(@NotNull Project project) { return new Widget(project); }
    @Override public boolean canBeEnabledOn(@NotNull StatusBar statusBar) { return true; }

    private static final class Widget implements CustomStatusBarWidget {
        private final Project project;
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "MoLLM-budget"); thread.setDaemon(true); return thread;
        });
        private volatile String text = "MoLLM";
        private volatile String tooltip = "點擊開啟 MoLLM；用量更新頻率可在設定頁調整。";
        private volatile boolean disposed;
        private StatusBar statusBar;
        private com.intellij.util.messages.MessageBusConnection connection;
        private java.util.concurrent.ScheduledFuture<?> pendingRefresh;
        private java.util.concurrent.ScheduledFuture<?> periodicRefresh;
        private final javax.swing.JLabel label = new javax.swing.JLabel("○ MoLLM");
        Widget(Project project) {
            this.project = project;
            label.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 6, 0, 6));
            label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            label.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    var window = ToolWindowManager.getInstance(project).getToolWindow("MoLLM");
                    if (window != null) window.activate(null);
                }
            });
        }
        @Override public @NotNull String ID() { return "MoLLM.Usage"; }
        @Override public @NotNull javax.swing.JComponent getComponent() { return label; }
        @Override public void install(@NotNull StatusBar statusBar) {
            this.statusBar = statusBar;
            connection = com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().connect();
            connection.subscribe(UPDATES, () -> queue(() -> {
                if (pendingRefresh != null) pendingRefresh.cancel(false);
                pendingRefresh = timer.schedule(this::refresh, 5, TimeUnit.SECONDS);
            }));
            connection.subscribe(INTERVALS, () -> queue(() -> schedulePeriodic(MollmSettings.getInstance().usageRefreshSeconds())));
            queue(() -> schedulePeriodic(15));
        }
        private synchronized void queue(Runnable action) {
            if (!disposed) timer.execute(action);
        }
        private synchronized void schedulePeriodic(long initialDelay) {
            if (disposed) return;
            if (periodicRefresh != null) periodicRefresh.cancel(false);
            periodicRefresh = timer.scheduleWithFixedDelay(this::refresh, initialDelay,
                    MollmSettings.getInstance().usageRefreshSeconds(), TimeUnit.SECONDS);
        }
        private void refresh() {
            if (disposed || project.isDisposed()) return;
            var settings = MollmSettings.getInstance();
            double highest = -1;
            double totalSpend = 0;
            var details = new StringBuilder("MoLLM 用量（點擊開啟）");
            boolean obtained = false;
            for (var server : settings.servers()) {
                if (disposed) return;
                try {
                    var api = new Gateway(server.baseUrl, ApiKeys.get(server.id), Math.min(10_000, settings.getState().timeoutMs));
                    var info = Gateway.object(api.get("/key/info"), "info");
                    double spend = Gateway.number(info, "spend", 0);
                    double budget = Gateway.number(info, "max_budget", 0);
                    if (budget > 0) highest = Math.max(highest, spend / budget * 100);
                    totalSpend += spend;
                    details.append(" | ").append(server.label).append(": ").append(UsagePanel.money(spend));
                    if (budget > 0) details.append(" / ").append(UsagePanel.money(budget));
                    obtained = true;
                } catch (Exception e) {
                    // Avoid exposing credentials or gateway response bodies in a global tooltip.
                    details.append(" | ").append(server.label).append(": 用量不可用");
                }
            }
            text = !obtained ? "○ MoLLM" : highest >= 0
                    ? "○ MoLLM " + String.format(Locale.ROOT, "%.1f%%", highest)
                    : "○ MoLLM " + UsagePanel.money(totalSpend);
            tooltip = details.toString();
            double percent = highest;
            SwingUtilities.invokeLater(() -> {
                if (disposed || project.isDisposed()) return;
                label.setText(text); label.setToolTipText(tooltip);
                label.setForeground(percent >= 100 ? new java.awt.Color(0xE86868) : percent >= 80 ? new java.awt.Color(0xDBAC54) : javax.swing.UIManager.getColor("Label.foreground"));
                statusBar.updateWidget(ID());
            });
        }
        @Override public void dispose() {
            synchronized (this) {
                disposed = true;
                timer.shutdownNow();
            }
            if (connection != null) connection.disconnect();
        }
    }
}
