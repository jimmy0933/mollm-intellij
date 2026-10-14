package tw.com.fmt.mollm;

import javax.swing.*;
import java.awt.*;

/** Compact actions on the left, clipped status anchored at the right of the last row. */
final class ChatActionBar extends JPanel {
    private final JLabel status;
    private final JButton[] buttons;
    ChatActionBar(JLabel status, JButton... buttons) {
        this.status = status; this.buttons = buttons.clone();
        setLayout(null); setOpaque(false); setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        for (var button : buttons) { button.setMargin(new Insets(3, 9, 3, 9)); add(button); }
        status.setHorizontalAlignment(SwingConstants.RIGHT);
        status.setToolTipText(status.getText());
        status.addPropertyChangeListener("text", event -> { status.setToolTipText(status.getText()); revalidate(); repaint(); });
        add(status);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { revalidate(); }
        });
    }
    private Dimension arrange(boolean apply) {
        int width = getWidth() > 0 ? getWidth() : 600;
        if (getParent() != null && getParent().getWidth() > 0) {
            var parentInsets = getParent().getInsets();
            width = getParent().getWidth() - parentInsets.left - parentInsets.right;
        }
        var insets = getInsets(); int available = Math.max(1, width - insets.left - insets.right);
        int x = 0, y = insets.top, rowHeight = 0;
        for (var button : buttons) if (button.isVisible()) {
            var size = button.getPreferredSize(); int buttonWidth = Math.min(available, size.width);
            if (x > 0 && x + buttonWidth > available) { y += rowHeight + 6; x = 0; rowHeight = 0; }
            if (apply) button.setBounds(insets.left + x, y, buttonWidth, size.height);
            x += buttonWidth + 6; rowHeight = Math.max(rowHeight, size.height);
        }
        if (status.isVisible()) {
            int statusWidth = Math.min(status.getPreferredSize().width, Math.min(available, Math.max(80, available / 2)));
            if (available - x < Math.min(80, statusWidth)) { y += rowHeight + 6; rowHeight = 0; x = 0; }
            statusWidth = Math.max(1, Math.min(statusWidth, available - x));
            int statusHeight = status.getPreferredSize().height; rowHeight = Math.max(rowHeight, statusHeight);
            if (apply) status.setBounds(insets.left + available - statusWidth, y + (rowHeight - statusHeight) / 2, statusWidth, statusHeight);
        }
        return new Dimension(width, y + rowHeight + insets.bottom);
    }
    @Override public Dimension getPreferredSize() { return arrange(false); }
    @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
    @Override public void doLayout() { arrange(true); }
}
