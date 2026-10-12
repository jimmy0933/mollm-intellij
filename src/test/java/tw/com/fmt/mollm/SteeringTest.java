package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class SteeringTest {
    private static int checks;
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    static void run() throws Exception {
        var inbox = new AgentInbox();
        check(inbox.submit(new Gateway.Message("user", "先不要修改")), "Running task accepts guidance");
        check(!inbox.finishIfEmpty(), "Completion cannot race past queued guidance");
        check(inbox.drain().getFirst().content().equals("先不要修改"), "Guidance drains in order");
        check(inbox.finishIfEmpty() && !inbox.submit(new Gateway.Message("user", "too late")), "Completed task rejects without losing UI draft");
        var cancelled = new AgentInbox(); cancelled.submit(new Gateway.Message("user", "待讀訊息"));
        check(cancelled.close().size() == 1 && cancelled.close().isEmpty(), "Failure returns unconsumed guidance exactly once");
        var a = new AgentInbox(); var b = new AgentInbox(); a.submit(new Gateway.Message("user", "A"));
        check(b.pendingCount() == 0 && a.pendingCount() == 1, "Parallel task queues are isolated");
        var bounded = new AgentInbox();
        for (int i = 0; i < 16; i++) bounded.submit(new Gateway.Message("user", "message"));
        try { bounded.submit(new Gateway.Message("user", "extra")); throw new AssertionError("Unbounded queue"); }
        catch (IllegalStateException expected) { check(bounded.pendingCount() == 16, "Queue rejects overflow without corrupting existing messages"); }
        loop(); parallel(); fonts();
        System.out.println("PASS: " + checks + " steering/font checks.");
    }
    private static void loop() throws Exception {
        var inbox = new AgentInbox(); var round = new AtomicInteger(); var executed = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                int current = round.incrementAndGet();
                String delta;
                if (current == 1) {
                    delta = "{\"tool_calls\":[{\"index\":0,\"id\":\"first\",\"function\":{\"name\":\"list_files\",\"arguments\":\"{}\"}},{\"index\":1,\"id\":\"second\",\"function\":{\"name\":\"create_file\",\"arguments\":\"{}\"}}]}";
                } else {
                    var messages = request.getAsJsonArray("messages");
                    if (current == 2) {
                        check(messages.get(3).getAsJsonObject().get("tool_call_id").getAsString().equals("first"), "Completed tool pairing retained");
                        check(messages.get(4).getAsJsonObject().get("content").getAsString().startsWith("Not executed"), "Old pending tool skipped after guidance");
                        check(messages.get(5).getAsJsonObject().get("content").getAsString().equals("不要修改，改為說明"), "New user guidance follows complete tool batch");
                        inbox.submit(new Gateway.Message("user", "補充另一項"));
                    } else check(messages.asList().stream().anyMatch(value -> value.getAsJsonObject().get("content").getAsString().equals("補充另一項")), "Guidance during final reply forces another model round");
                    delta = "{\"content\":\"已依引導完成\"}";
                }
                byte[] bytes = ("data: {\"choices\":[{\"delta\":" + delta + "}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable e) { failure.set(e); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        }); server.start();
        try (var call = new Gateway.Call()) {
            var tools = new AgentLoop.Tools() {
                public String context() { return "test"; }
                public String execute(String name, JsonObject args, Gateway.Call token) {
                    executed.incrementAndGet(); inbox.submit(new Gateway.Message("user", "不要修改，改為說明")); return "done";
                }
            };
            var loop = new AgentLoop(tools, List.of(new Gateway.Message("user", "start")), inbox);
            var result = loop.run(new Gateway("http://127.0.0.1:" + server.getAddress().getPort(), "", 2000),
                    new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity -> {});
            if (failure.get() != null) throw new AssertionError(failure.get());
            check(round.get() == 3 && executed.get() == 1 && result.completed(), "Steered loop completes without executing stale operation");
        } finally { server.stop(0); }
    }
    private static void parallel() throws Exception {
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var rounds = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        server.createContext("/v1/chat/completions", exchange -> {
            var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String model = body.get("model").getAsString();
            int round = rounds.computeIfAbsent(model, key -> new AtomicInteger()).incrementAndGet();
            String delta = round == 1 ? "{\"tool_calls\":[{\"index\":0,\"id\":\"tool\",\"function\":{\"name\":\"list_files\",\"arguments\":\"{}\"}}]}" : "{\"content\":\"done\"}";
            byte[] bytes = ("data: {\"choices\":[{\"delta\":" + delta + "}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        var running = new java.util.concurrent.CountDownLatch(2); var release = new java.util.concurrent.CountDownLatch(1);
        AgentLoop.Tools tools = new AgentLoop.Tools() {
            public String context() { return "parallel test"; }
            public String execute(String name, JsonObject args, Gateway.Call call) throws Exception {
                running.countDown(); if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Parallel tasks did not overlap");
                AgentLoop.checkCancelled(call); return "done";
            }
        };
        try (var first = new Gateway.Call(); var second = new Gateway.Call()) {
            java.util.function.BiFunction<String, Gateway.Call, Boolean> run = (model, call) -> {
                try {
                    return new AgentLoop(tools, List.of(new Gateway.Message("user", model))).run(
                            new Gateway("http://127.0.0.1:" + server.getAddress().getPort(), "", 2000),
                            new Gateway.Model(model, new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity -> {}).completed();
                } catch (java.util.concurrent.CancellationException expected) { return false; }
                catch (Exception e) { throw new RuntimeException(e); }
            };
            var a = workers.submit(() -> run.apply("A", first)); var b = workers.submit(() -> run.apply("B", second));
            check(running.await(5, java.util.concurrent.TimeUnit.SECONDS), "Two agents execute concurrently");
            first.cancel(); release.countDown();
            check(!a.get(5, java.util.concurrent.TimeUnit.SECONDS) && b.get(5, java.util.concurrent.TimeUnit.SECONDS), "Stopping one parallel agent leaves the other running to completion");
        } finally { release.countDown(); server.stop(0); workers.shutdownNow(); }
    }
    private static void fonts() {
        var fallback = new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13);
        var family = ChatFonts.families().getFirst();
        check(ChatFonts.create(family, fallback, 18).getFamily().equals(new java.awt.Font(family, 0, 18).getFamily()), "Installed system font selected");
        check(ChatFonts.create("missing-font-xyz", fallback, 18).getFamily().equals(fallback.getFamily()), "Missing font falls back on another machine");
        check(ChatFonts.create(null, fallback, 18).getSize() == 18, "Legacy font state retains chosen size");
        var state = new MollmSettings.Data(); state.chatFontFamily = family;
        var restored = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(state), MollmSettings.Data.class);
        check(restored.chatFontFamily.equals(family), "Font preference survives restart");
    }
}
