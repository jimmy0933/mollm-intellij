package tw.com.fmt.mollm;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Function;

/** Moves the existing UI rather than creating a second chat or disposing its state. */
final class FullScreenView {
    interface Surface {
        JPanel content();
        void show();
        void close();
    }
    private final JComponent content;
    private final JPanel host;
    private final Function<Runnable, Surface> factory;
    private final Runnable changed;
    private Surface surface;
    private boolean disposed;

    FullScreenView(JComponent content, JPanel host, Runnable changed) {
        this(content, host, exit -> new WindowSurface(host, exit), changed);
    }
    FullScreenView(JComponent content, JPanel host, Function<Runnable, Surface> factory, Runnable changed) {
        this.content = content; this.host = host; this.factory = factory; this.changed = changed;
    }
    boolean isFullScreen() { return surface != null; }
    void enter() {
        if (disposed || surface != null) return;
        surface = factory.apply(this::restore);
        try {
            surface.content().add(content, BorderLayout.CENTER);
            var placeholder = new JPanel(new BorderLayout(8, 8));
            placeholder.add(new JLabel("MoLLM 已在全螢幕視窗中開啟。", SwingConstants.CENTER), BorderLayout.CENTER);
            var restore = new JButton("回到一般大小");
            restore.addActionListener(event -> restore()); placeholder.add(restore, BorderLayout.SOUTH);
            host.removeAll(); host.add(placeholder, BorderLayout.CENTER); host.revalidate(); host.repaint();
            changed.run(); surface.show();
        } catch (RuntimeException e) { restore(); throw e; }
    }
    void restore() { restore(true); }
    private void restore(boolean notify) {
        var window = surface;
        if (window == null) return;
        surface = null;
        host.removeAll(); host.add(content, BorderLayout.CENTER); host.revalidate(); host.repaint();
        window.close();
        if (notify) changed.run();
    }
    void dispose() { disposed = true; restore(false); }

    private static final class WindowSurface implements Surface {
        private final JDialog window;
        private final JPanel content = new JPanel(new BorderLayout());
        WindowSurface(JPanel host, Runnable exit) {
            var owner = SwingUtilities.getWindowAncestor(host);
            var configuration = owner == null ? GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration() : owner.getGraphicsConfiguration();
            window = new JDialog(owner, "MoLLM", Dialog.ModalityType.MODELESS, configuration);
            window.setUndecorated(true);
            window.getRootPane().setWindowDecorationStyle(JRootPane.NONE);
            window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            window.setContentPane(content);
            window.setBounds(configuration.getBounds());
            window.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { exit.run(); }
                @Override public void windowClosed(WindowEvent event) { exit.run(); }
            });
            window.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(KeyStroke.getKeyStroke("ESCAPE"), "restore-mollm");
            window.getRootPane().getActionMap().put("restore-mollm", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { exit.run(); }
            });
        }
        public JPanel content() { return content; }
        public void show() { window.setVisible(true); window.toFront(); }
        public void close() { window.dispose(); }
    }
}
