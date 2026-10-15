package tw.com.fmt.mollm;

import javax.swing.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.function.DoubleConsumer;

/** A draggable conversation/composer split whose proportion survives resize and restart. */
final class ChatSplitPane extends JSplitPane {
    private double ratio;
    private boolean restoring;
    private boolean initialized;
    ChatSplitPane(JComponent conversation, JComponent composer, double savedRatio, DoubleConsumer save) {
        super(VERTICAL_SPLIT, conversation, composer);
        ratio = validRatio(savedRatio);
        setBorder(BorderFactory.createEmptyBorder()); setDividerSize(8); setContinuousLayout(true);
        setResizeWeight(ratio); setOneTouchExpandable(false);
        setToolTipText("拖曳分隔線，調整回覆區與輸入區的高度。");
        addPropertyChangeListener(DIVIDER_LOCATION_PROPERTY, event -> {
            int available = getHeight() - getDividerSize();
            if (!initialized || restoring || available <= 0) return;
            ratio = validRatio((double) getDividerLocation() / available);
            setResizeWeight(ratio); save.accept(ratio);
        });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { restoreProportion(); }
        });
    }
    static double validRatio(double ratio) { return Double.isFinite(ratio) && ratio >= .05 && ratio <= .95 ? ratio : .75; }
    void restoreProportion() {
        int available = getHeight() - getDividerSize();
        if (available <= 0) return;
        restoring = true;
        try {
            int minimum = getTopComponent().getMinimumSize().height;
            int maximum = Math.max(minimum, available - getBottomComponent().getMinimumSize().height);
            setDividerLocation(Math.max(minimum, Math.min(maximum, (int) Math.round(available * ratio))));
            initialized = true;
        } finally { restoring = false; }
    }
    @Override public void doLayout() {
        if (!initialized && getHeight() > 0) restoreProportion();
        super.doLayout();
    }
}
