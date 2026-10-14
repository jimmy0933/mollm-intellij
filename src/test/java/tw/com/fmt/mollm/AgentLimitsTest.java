package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class AgentLimitsTest {
    private static int checks;
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); checks++; }
    static void run() throws Exception {
        var settings = new MollmSettings.Data();
        check(AgentLimits.from(settings).equals(new AgentLimits(80, 256)), "New and migrated settings have review-sized defaults");
        settings.agentMaxRounds = 120; settings.agentMaxOperations = 600;
        var restored = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(settings), MollmSettings.Data.class);
        check(AgentLimits.from(restored).equals(new AgentLimits(120, 600)), "Both limits persist across IDE restart");
        restored.agentMaxRounds = -1; restored.agentMaxOperations = Integer.MAX_VALUE;
        check(AgentLimits.from(restored).equals(AgentLimits.defaults()), "Invalid stored limits retain finite safe bounds");
        settings.agentUnlimited = true;
        restored = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(settings), MollmSettings.Data.class);
        check(AgentLimits.from(restored).unlimited(), "Unlimited option persists across IDE restart");
        check(!AgentLimits.from(restored).roundsReached(Long.MAX_VALUE) && !AgentLimits.from(restored).operationsReached(Long.MAX_VALUE), "Unlimited bypasses both caps without numeric sentinel");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger(); var executed = new AtomicInteger(); var mode = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                int count = requests.incrementAndGet();
                if (mode.get() == 0 && count == 21) {
                    long historyResults = body.getAsJsonArray("messages").asList().stream()
                            .filter(value -> "tool".equals(value.getAsJsonObject().get("role").getAsString())).count();
                    check(historyResults == 20, "Continuation sends prior tool results instead of restarting discovery");
                }
                String delta;
                if ((mode.get() == 0 && count > 21) || (mode.get() == 2 && count > 81)) delta = "{\"content\":\"review completed\"}";
                else {
                    var calls = new com.google.gson.JsonArray();
                    for (int i = 0; i < (mode.get() == 2 ? 4 : mode.get() == 1 ? 2 : 1); i++) {
                        var call = new JsonObject(); call.addProperty("index", i); call.addProperty("id", "call_" + count + "_" + i);
                        var fn = new JsonObject(); fn.addProperty("name", "list_files"); fn.addProperty("arguments", "{}"); call.add("function", fn); calls.add(call);
                    }
                    var value = new JsonObject(); value.add("tool_calls", calls); delta = value.toString();
                }
                byte[] bytes = ("data: {\"choices\":[{\"delta\":" + delta + "}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable e) { failure.set(e); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        }); server.start();
        var tools = new AgentLoop.Tools() {
            public String context() { return "review test"; }
            public String execute(String name, JsonObject args, Gateway.Call cancellation) { executed.incrementAndGet(); return "inspected"; }
        };
        try {
            var api = new Gateway("http://127.0.0.1:" + server.getAddress().getPort(), "", 2000);
            AgentLoop.Result first;
            try (var call = new Gateway.Call()) {
                first = new AgentLoop(tools, List.of(new Gateway.Message("user", "review")), new AgentInbox(), new AgentLimits(20, 64))
                        .run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity -> {});
            }
            check(!first.completed() && requests.get() == 20 && executed.get() == 20, "Reproduces model-round stop below tool limit");
            check(first.messages().getLast().content().contains("模型回合上限") && first.messages().getLast().content().contains("20/64"), "Notice distinguishes reached limit and actual counts");
            var history = new ArrayList<>(first.messages()); history.add(new Gateway.Message("user", "continue remaining review"));
            try (var call = new Gateway.Call()) {
                var result = new AgentLoop(tools, history).run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity -> {});
                check(result.completed() && requests.get() == 22 && executed.get() == 21, "Continuation completes previously stopped review");
            }
            mode.set(1); requests.set(0); executed.set(0);
            try (var call = new Gateway.Call()) {
                var result = new AgentLoop(tools, List.of(new Gateway.Message("user", "bounded tools")), new AgentInbox(), new AgentLimits(10, 5))
                        .run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity -> {});
                check(!result.completed() && requests.get() == 3 && executed.get() == 5, "Custom operation cap stops a multi-tool batch exactly");
                check(result.messages().getLast().content().contains("工具操作上限") && result.messages().getLast().content().contains("3/10"), "Operation-limit notice reports accurate model count");
                check(result.messages().stream().filter(value -> value.role().equals("tool")).count() == 6,
                        "Unexecuted tool calls still have paired results for continuation");
            }
            mode.set(2); requests.set(0); executed.set(0);
            try (var call = new Gateway.Call()) {
                var activity = new ArrayList<String>();
                var result = new AgentLoop(tools, List.of(new Gateway.Message("user", "unlimited review")), new AgentInbox(), new AgentLimits(80, 256, true))
                        .run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity::add);
                check(result.completed() && requests.get() == 82 && executed.get() == 324, "Unlimited completes beyond both default caps");
                check(activity.stream().anyMatch(value -> value.contains("82/不設限")) && activity.stream().anyMatch(value -> value.contains("324/不設限")), "Unlimited reports real progress without misleading finite cap");
            }
            requests.set(0); executed.set(0);
            var cancellingTools = new AgentLoop.Tools() {
                public String context() { return "cancel test"; }
                public String execute(String name, JsonObject args, Gateway.Call cancellation) { executed.incrementAndGet(); cancellation.cancel(); return "stopped"; }
            };
            try (var call = new Gateway.Call()) {
                boolean cancelled = false;
                try {
                    new AgentLoop(cancellingTools, List.of(new Gateway.Message("user", "cancel unlimited")), new AgentInbox(), new AgentLimits(80, 256, true))
                            .run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity -> {});
                } catch (java.util.concurrent.CancellationException expected) { cancelled = true; }
                check(cancelled && executed.get() == 1 && requests.get() == 1, "Unlimited still stops within an active tool batch");
            }
            if (failure.get() != null) throw new AssertionError(failure.get());
        } finally { server.stop(0); }
        System.out.println("PASS: " + checks + " Agent limit/continuation checks.");
    }
}
