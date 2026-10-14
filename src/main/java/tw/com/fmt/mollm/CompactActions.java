package tw.com.fmt.mollm;

import javax.swing.*;
import java.awt.*;

/** Keep buttons at natural size while giving wrapped rows enough height in narrow tool windows. */
final class CompactActions extends JPanel {
    CompactActions(JButton... buttons) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 2));
        for (var button : buttons) { button.setMargin(new Insets(3, 8, 3, 8)); add(button); }
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { revalidate(); }
        });
    }
    @Override public Dimension getPreferredSize() {
        int available = getParent() == null ? getWidth() : getParent().getWidth();
        if (available <= 0) return super.getPreferredSize();
        var insets = getInsets(); var flow = (FlowLayout) getLayout();
        int limit = Math.max(1, available - insets.left - insets.right - flow.getHgap() * 2);
        int rowWidth = 0, rowHeight = 0, height = 0, rows = 0;
        for (var child : getComponents()) if (child.isVisible()) {
            var size = child.getPreferredSize();
            if (rowWidth > 0 && rowWidth + flow.getHgap() + size.width > limit) {
                height += rowHeight; rows++; rowWidth = 0; rowHeight = 0;
            }
            rowWidth += (rowWidth == 0 ? 0 : flow.getHgap()) + size.width; rowHeight = Math.max(rowHeight, size.height);
        }
        if (rowWidth > 0) { height += rowHeight; rows++; }
        return new Dimension(available, height + insets.top + insets.bottom + (rows + 1) * flow.getVgap());
    }
}
