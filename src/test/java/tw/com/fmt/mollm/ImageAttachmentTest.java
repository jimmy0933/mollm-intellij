package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class ImageAttachmentTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name); checks++;
    }
    static void run() throws Exception {
        var source = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(10, 10, 0x80ff0044);
        var image = ImageAttachment.from(source);
        var decoded = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.dataUri().split(",", 2)[1])));
        check(decoded.getRGB(10, 10) == source.getRGB(10, 10), "PNG preserves screenshot pixels and transparency");
        check(image.width() == 320 && image.height() == 180 && image.preview().getIconWidth() <= 140, "Full image and bounded preview dimensions");
        var lazy = java.awt.Toolkit.getDefaultToolkit().createImage(Base64.getDecoder().decode(image.dataUri().split(",", 2)[1]));
        check(ImageAttachment.from(lazy).width() == 320, "Lazily loaded clipboard image accepted");
        try { ImageAttachment.from(new BufferedImage(4001, 4000, BufferedImage.TYPE_BYTE_GRAY)); throw new AssertionError("oversized accepted"); }
        catch (java.io.IOException expected) { check(true, "Oversized image rejected before encoding"); }
        var images = new ArrayList<>(List.of(image));
        var message = new Gateway.Message("user", "修正截圖中的錯誤", images);
        images.clear(); check(message.images().size() == 1, "History owns immutable attachment list");
        var config = new MollmSettings.Server();
        var model = new Gateway.Model("vision", new JsonObject());
        var request = Gateway.request(model, List.of(message, new Gateway.Message("assistant", "ok")), config);
        var content = request.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content");
        check(content.get(0).getAsJsonObject().get("text").getAsString().equals(message.content()), "Multimodal text preserved");
        check(content.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString().equals(image.dataUri()), "Actual PNG sent as image_url");
        check(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").isJsonPrimitive(), "Plain text format unchanged");
        check(Gateway.request(model, List.of(new Gateway.Message("user", "", List.of(image))), config)
                .getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content").size() == 1, "Image only request supported");
        SwingUtilities.invokeAndWait(() -> {
            var field = new JTextArea();
            var received = new AtomicReference<java.awt.Image>();
            var handler = new ImagePasteHandler(field.getTransferHandler(), received::set, error -> { throw new AssertionError(error); });
            field.setTransferHandler(handler);
            Transferable screenshot = new Transferable() {
                public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
                public boolean isDataFlavorSupported(DataFlavor flavor) { return flavor.equals(DataFlavor.imageFlavor); }
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor); return source;
                }
            };
            check(handler.canImport(field, screenshot.getTransferDataFlavors()), "Swing recognizes image paste");
            check(handler.importData(field, screenshot) && received.get() == source, "Ctrl V import path receives image");
            check(handler.importData(new TransferHandler.TransferSupport(field, screenshot)), "TransferSupport image import");
            var clipboardContents = new AtomicReference<Transferable>(screenshot);
            var pasteFailure = new AtomicReference<String>();
            var chat = new ChatInput(received::set, pasteFailure::set, clipboardContents::get);
            var provider = new AtomicReference<com.intellij.ide.PasteProvider>();
            var sink = (com.intellij.openapi.actionSystem.DataSink) java.lang.reflect.Proxy.newProxyInstance(
                    ImageAttachmentTest.class.getClassLoader(), new Class<?>[]{com.intellij.openapi.actionSystem.DataSink.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("set") && args[0] == com.intellij.openapi.actionSystem.PlatformDataKeys.PASTE_PROVIDER)
                            provider.set((com.intellij.ide.PasteProvider) args[1]);
                        return null;
                    });
            chat.uiDataSnapshot(sink);
            var context = com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT;
            check(provider.get() != null && provider.get().isPasteEnabled(context), "IDE data snapshot advertises image PasteProvider");
            check(provider.get().getActionUpdateThread() == com.intellij.openapi.actionSystem.ActionUpdateThread.EDT, "IDE paste updates on Swing thread");
            received.set(null); provider.get().performPaste(context);
            check(received.get() == source, "IDE PasteProvider reaches screenshot receiver");
            received.set(null);
            Object binding = chat.getInputMap().get(KeyStroke.getKeyStroke("ctrl V"));
            chat.getActionMap().get(binding).actionPerformed(new java.awt.event.ActionEvent(chat, 0, "paste"));
            check(received.get() == source, "Bound Ctrl V action reads clipboard image");
            var ideActions = com.intellij.openapi.actionSystem.ex.ActionUtil.getActions(chat);
            var ctrlV = new com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke("ctrl V"), null);
            var localPaste = ideActions.stream().filter(action -> java.util.Arrays.asList(action.getShortcutSet().getShortcuts()).contains(ctrlV)).findFirst().orElseThrow();
            check(localPaste instanceof com.intellij.openapi.project.DumbAware, "Component Ctrl V action registered with IDE even during indexing");
            received.set(null); localPaste.actionPerformed(null);
            check(received.get() == source, "Registered IDE Ctrl V action reaches actual screenshot paste operation");
            received.set(null); ((JMenuItem) chat.getComponentPopupMenu().getComponent(0)).doClick();
            check(received.get() == source, "Context menu pastes screenshot");
            clipboardContents.set(new StringSelection("IDE 貼上文字")); provider.get().performPaste(context);
            check(chat.getText().equals("IDE 貼上文字"), "IDE PasteProvider preserves text paste");
            chat.setEnabled(false); received.set(null); clipboardContents.set(screenshot);
            check(!provider.get().isPasteEnabled(context), "Disabled input disables IDE paste");
            provider.get().performPaste(context); check(received.get() == null, "Disabled input cannot paste screenshot");
            chat.setEnabled(true); clipboardContents.set(null); chat.paste();
            check(pasteFailure.get() != null && pasteFailure.get().contains("沒有內容"), "Empty clipboard has visible diagnostic");
            check(handler.importData(field, new StringSelection("文字")) && field.getText().equals("文字"), "Text paste still works");
            field.selectAll(); var clipboard = new Clipboard("test");
            handler.exportToClipboard(field, clipboard, TransferHandler.COPY);
            try { check(clipboard.getData(DataFlavor.stringFlavor).equals("文字"), "Text copy still works"); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        config.overrides.put("vision", new MollmSettings.ModelOverride()); config.overrides.get("vision").forceImageInput = true;
        var state = new MollmSettings.Data(); state.servers.add(config);
        var restored = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(state), MollmSettings.Data.class);
        check(restored.servers.getFirst().overrides.get("vision").forceImageInput, "Vision override persists");
        agentHistory(message, image);
        System.out.println("PASS: " + checks + " screenshot checks (PNG, Swing paste, multimodal requests and Agent history).");
    }

    private static void agentHistory(Gateway.Message message, ImageAttachment image) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var rounds = new AtomicInteger(); var failure = new AtomicReference<Throwable>();
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                var user = body.getAsJsonArray("messages").get(1).getAsJsonObject();
                if (!user.getAsJsonArray("content").get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString().equals(image.dataUri()))
                    throw new AssertionError("Agent lost screenshot");
                String delta = rounds.incrementAndGet() == 1
                        ? "{\"tool_calls\":[{\"index\":0,\"id\":\"read1\",\"function\":{\"name\":\"list_files\",\"arguments\":\"{}\"}}]}"
                        : "{\"content\":\"已分析截圖\"}";
                byte[] response = ("data: {\"choices\":[{\"delta\":" + delta + "}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Throwable error) { failure.set(error); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start();
        try (var call = new Gateway.Call()) {
            var tools = new AgentLoop.Tools() {
                public String context() { return "mock"; }
                public String execute(String name, JsonObject args, Gateway.Call token) { return "files"; }
            };
            var engine = new AgentLoop(tools, List.of(message));
            var result = engine.run(new Gateway("http://127.0.0.1:" + server.getAddress().getPort(), "", 2000),
                    new Gateway.Model("vision", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity -> {});
            if (failure.get() != null) throw new AssertionError(failure.get());
            check(result.completed() && rounds.get() == 2, "Screenshot reaches both Agent model rounds");
            check(result.messages().getFirst().images().getFirst() == image, "Agent result retains screenshot for next turn");
        } finally { server.stop(0); }
    }
}
