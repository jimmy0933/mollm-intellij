package tw.com.fmt.mollm;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Scale plugin components without changing global IDE UI defaults. */
final class InterfaceScale {
    private final Map<Component, Base> originals = new WeakHashMap<>();
    private double factor = 1;
    double factor() { return factor; }
    void setFont(Component component, Font font) {
        originals.computeIfAbsent(component, Base::new).font = font;
        component.setFont(font.deriveFont((float) (font.getSize2D() * factor)));
    }
    void apply(JComponent root, int percent) {
        factor = normalize(percent) / 100.0;
        var components = new ArrayList<Component>(); collect(root, components);
        // Capture all fonts before changing parents whose fonts may be inherited.
        for (var component : components) originals.computeIfAbsent(component, Base::new);
        for (var component : components) apply(component, originals.get(component));
        root.revalidate(); root.repaint();
    }
    static int normalize(int percent) {
        return switch (percent) { case 75, 100, 125, 150, 175, 200 -> percent; default -> 100; };
    }
    private static void collect(Component component, List<Component> values) {
        if (values.contains(component)) return;
        values.add(component);
        if (component instanceof Container container) for (var child : container.getComponents()) collect(child, values);
        if (component instanceof JComponent swing && swing.getComponentPopupMenu() != null) collect(swing.getComponentPopupMenu(), values);
    }
    private void apply(Component component, Base base) {
        if (base.font != null) component.setFont(base.font.deriveFont((float) (base.font.getSize2D() * factor)));
        if (base.preferred != null) component.setPreferredSize(size(base.preferred));
        if (base.minimum != null) component.setMinimumSize(size(base.minimum));
        if (base.maximum != null) component.setMaximumSize(size(base.maximum));
        if (component instanceof JComponent swing && base.border != null) swing.setBorder(border(base.border));
        if (component instanceof AbstractButton button && base.margin != null) button.setMargin(insets(base.margin));
        if (component instanceof JTable table) {
            table.setRowHeight(Math.max(1, scaled(base.rowHeight)));
            table.setRowMargin(scaled(base.rowMargin));
            if (table.getTableHeader() != null) table.getTableHeader().setFont(table.getFont());
        }
        if (component instanceof JSplitPane split) split.setDividerSize(Math.max(1, scaled(base.dividerSize)));
        if (component instanceof JLabel label && base.icon != null) label.setIcon(icon(base.icon));
        if (component instanceof AbstractButton button && base.icon != null) button.setIcon(icon(base.icon));
        if (component instanceof Container container) {
            var layout = container.getLayout();
            if (layout instanceof BorderLayout border) { border.setHgap(scaled(base.hgap)); border.setVgap(scaled(base.vgap)); }
            if (layout instanceof FlowLayout flow) { flow.setHgap(scaled(base.hgap)); flow.setVgap(scaled(base.vgap)); }
            if (layout instanceof GridLayout grid) { grid.setHgap(scaled(base.hgap)); grid.setVgap(scaled(base.vgap)); }
        }
    }
    private int scaled(int value) { return (int) Math.min(Integer.MAX_VALUE, Math.round(value * factor)); }
    private Dimension size(Dimension original) { return new Dimension(scaled(original.width), scaled(original.height)); }
    private Insets insets(Insets original) { return new Insets(scaled(original.top), scaled(original.left), scaled(original.bottom), scaled(original.right)); }
    private Border border(Border original) {
        if (original instanceof EmptyBorder empty) return new EmptyBorder(insets(empty.getBorderInsets()));
        if (original instanceof LineBorder line) return new LineBorder(line.getLineColor(), Math.max(1, scaled(line.getThickness())), line.getRoundedCorners());
        if (original instanceof CompoundBorder compound) return new CompoundBorder(
                compound.getOutsideBorder() == null ? null : border(compound.getOutsideBorder()),
                compound.getInsideBorder() == null ? null : border(compound.getInsideBorder()));
        return original;
    }
    private Icon icon(Icon original) {
        if (factor == 1) return original;
        double scale = factor;
        return new Icon() {
            public int getIconWidth() { return Math.max(1, (int) Math.round(original.getIconWidth() * scale)); }
            public int getIconHeight() { return Math.max(1, (int) Math.round(original.getIconHeight() * scale)); }
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                var copy = (Graphics2D) graphics.create();
                try { copy.translate(x, y); copy.scale(scale, scale); original.paintIcon(component, copy, 0, 0); }
                finally { copy.dispose(); }
            }
        };
    }
    private static final class Base {
        Font font;
        final Dimension preferred, minimum, maximum;
        final Border border;
        final Insets margin;
        final Icon icon;
        final int rowHeight, rowMargin, dividerSize, hgap, vgap;
        Base(Component component) {
            font = component.isFontSet() ? component.getFont() : null;
            preferred = component.isPreferredSizeSet() ? component.getPreferredSize() : null;
            minimum = component.isMinimumSizeSet() ? component.getMinimumSize() : null;
            maximum = component.isMaximumSizeSet() ? component.getMaximumSize() : null;
            border = component instanceof JComponent swing ? swing.getBorder() : null;
            margin = component instanceof AbstractButton button ? button.getMargin() : null;
            icon = component instanceof JLabel label ? label.getIcon() : component instanceof AbstractButton button ? button.getIcon() : null;
            rowHeight = component instanceof JTable table ? table.getRowHeight() : 0;
            rowMargin = component instanceof JTable table ? table.getRowMargin() : 0;
            dividerSize = component instanceof JSplitPane split ? split.getDividerSize() : 0;
            var layout = component instanceof Container container ? container.getLayout() : null;
            hgap = layout instanceof BorderLayout b ? b.getHgap() : layout instanceof FlowLayout f ? f.getHgap() : layout instanceof GridLayout g ? g.getHgap() : 0;
            vgap = layout instanceof BorderLayout b ? b.getVgap() : layout instanceof FlowLayout f ? f.getVgap() : layout instanceof GridLayout g ? g.getVgap() : 0;
        }
    }
}
