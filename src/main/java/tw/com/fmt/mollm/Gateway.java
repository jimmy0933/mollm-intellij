package tw.com.fmt.mollm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;

final class Gateway {
    record Model(String id, JsonObject info) {
        @Override public String toString() { return id; }
        boolean accepts(String name) {
            return array(info, "supported_openai_params").asList().stream()
                    .anyMatch(value -> value.isJsonPrimitive() && value.getAsString().equals(name));
        }
        int maxOutput(int fallback) { return (int) number(info, "max_output_tokens", fallback); }
    }
    record Usage(JsonObject key, JsonArray days, List<String> warnings) { }
    record ToolCall(String id, String name, String arguments) {
        JsonObject json() {
            var value = new JsonObject(); value.addProperty("id", id); value.addProperty("type", "function");
            var function = new JsonObject(); function.addProperty("name", name); function.addProperty("arguments", arguments);
            value.add("function", function); return value;
        }
    }
    record Message(String role, String content, List<ToolCall> tools, String toolCallId, String reasoning, List<ImageAttachment> images) {
        Message(String role, String content, List<ToolCall> tools, String toolCallId, String reasoning) {
            this(role, content, tools, toolCallId, reasoning, List.of());
        }
        Message(String role, String content, List<ImageAttachment> images) {
            this(role, content, List.of(), "", "", images);
        }
        Message(String role, String content) { this(role, content, List.of(), "", ""); }
        Message(String role, String content, List<ToolCall> tools, String toolCallId) { this(role, content, tools, toolCallId, ""); }
        Message { tools = List.copyOf(tools); images = List.copyOf(images); }
    }
    record TokenUsage(long prompt, long completion, long total) {
        static TokenUsage unknown() { return new TokenUsage(-1, -1, -1); }
    }
    record Reply(String content, List<ToolCall> tools, String reasoning, String finishReason, TokenUsage usage, int requestedOutput) {
        boolean truncated() { return finishReason.equals("length") || finishReason.equals("max_tokens"); }
        String diagnostics() {
            return "finish_reason=" + (finishReason.isEmpty() ? "未提供" : finishReason)
                    + "; requested_output=" + requestedOutput + "; prompt_tokens=" + count(usage.prompt())
                    + "; completion_tokens=" + count(usage.completion()) + "; total_tokens=" + count(usage.total());
        }
        private static String count(long value) { return value < 0 ? "未提供" : Long.toString(value); }
    }
    private static final class StreamState {
        String finishReason = "";
        TokenUsage usage = TokenUsage.unknown();
        int requestedOutput;
    }
    private static final class ToolParts {
        final StringBuilder id = new StringBuilder();
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }

    static final class Call implements AutoCloseable {
        private volatile boolean cancelled;
        private HttpURLConnection connection;
        synchronized void attach(HttpURLConnection value) {
            if (cancelled) { value.disconnect(); throw new CancellationException(); }
            connection = value;
        }
        boolean cancelled() { return cancelled; }
        void cancel() { cancelled = true; Thread.ofVirtual().start(this::disconnect); }
        private synchronized void disconnect() { if (connection != null) connection.disconnect(); }
        @Override public synchronized void close() {
            if (connection != null) connection.disconnect();
        }
    }

    static String normalizeUrl(String input) {
        String url = input.trim().replaceAll("/+$", "");
        if (url.contains("://") && !url.matches("(?i)^https?://.*"))
            throw new IllegalArgumentException("Base URL 僅支援 http 或 https");
        if (!url.matches("(?i)^https?://.*")) url = "http://" + url;
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Base URL 格式不正確");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("請填入 http(s) Base URL，不含金鑰、query 或 fragment");
        }
        return url.matches(".*/v\\d+$") ? url : url + "/v1";
    }

    private final String base;
    private final String root;
    private final String key;
    private final int timeout;

    Gateway(String baseUrl, String apiKey, int timeoutMs) {
        base = normalizeUrl(baseUrl);
        root = base.replaceAll("/v\\d+$", "");
        key = apiKey;
        timeout = Math.max(1000, timeoutMs);
    }

    private HttpURLConnection open(String url, String method, Call call) throws IOException {
        var connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        // Do not forward credentials to redirect destinations.
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout); // Idle timeout is reset by each network read.
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (!key.isBlank()) connection.setRequestProperty("Authorization", "Bearer " + key);
        call.attach(connection);
        return connection;
    }

    private InputStream checkedBody(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status >= 200 && status < 300) return connection.getInputStream();
        String detail = "";
        InputStream error = connection.getErrorStream();
        if (error != null) {
            try (error) { detail = new String(error.readNBytes(2048), StandardCharsets.UTF_8); }
        }
        throw new IOException("HTTP " + status + (detail.isBlank() ? "" : " — " + redact(detail)));
    }

    String redact(String text) { return key.isBlank() ? text : text.replace(key, "[REDACTED]"); }

    JsonObject get(String path) throws IOException {
        try (var call = new Call()) {
            var connection = open(root + path, "GET", call);
            try (var reader = new InputStreamReader(checkedBody(connection), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IOException("伺服器未回傳有效 JSON", e);
            }
        }
    }

    List<Model> models() throws IOException {
        var models = new TreeMap<String, JsonObject>();
        JsonObject list = get(base.substring(root.length()) + "/models");
        for (var value : array(list, "data")) {
            String id = string(value.getAsJsonObject(), "id", "");
            if (!id.isBlank()) models.put(id, new JsonObject());
        }
        try {
            for (var value : array(get("/model_group/info"), "data")) {
                var info = value.getAsJsonObject();
                String id = string(info, "model_group", "");
                if (models.containsKey(id)) models.put(id, info);
            }
        } catch (IOException ignored) {
            // This optional management route must not prevent OpenAI-only gateways from chatting.
        }
        return models.entrySet().stream()
                .filter(entry -> string(entry.getValue(), "mode", "chat").equals("chat"))
                .map(entry -> new Model(entry.getKey(), entry.getValue())).toList();
    }

    Usage usage() {
        var warnings = new ArrayList<String>();
        JsonObject keyInfo = new JsonObject();
        JsonArray days = new JsonArray();
        try { keyInfo = object(get("/key/info"), "info"); }
        catch (IOException e) { warnings.add("/key/info: " + e.getMessage()); }
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        try {
            JsonObject daily = get("/user/daily/activity?start_date=" + end.minusDays(29)
                    + "&end_date=" + end + "&page_size=1000");
            days = array(daily, "results");
            if (number(daily, "total_pages", 1) > 1) warnings.add("用量超過一頁；目前顯示前 1000 筆。");
        } catch (IOException e) { warnings.add("/user/daily/activity: " + e.getMessage()); }
        return new Usage(keyInfo, days, List.copyOf(warnings));
    }

    static JsonObject request(Model model, List<Message> history, MollmSettings.Server server) {
        var body = new JsonObject();
        body.addProperty("model", model.id());
        body.addProperty("stream", true);
        var messages = new JsonArray();
        for (var message : history) {
            var item = new JsonObject();
            item.addProperty("role", message.role());
            if (message.images().isEmpty()) item.addProperty("content", message.content());
            else {
                var parts = new JsonArray();
                if (!message.content().isEmpty()) {
                    var text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", message.content()); parts.add(text);
                }
                for (var image : message.images()) parts.add(image.json());
                item.add("content", parts);
            }
            if (!message.tools().isEmpty()) {
                var tools = new JsonArray(); message.tools().forEach(tool -> tools.add(tool.json()));
                item.add("tool_calls", tools);
            }
            if (!message.toolCallId().isEmpty()) item.addProperty("tool_call_id", message.toolCallId());
            if (message.role().equals("assistant") && !message.reasoning().isEmpty()) item.addProperty("reasoning_content", message.reasoning());
            messages.add(item);
        }
        body.add("messages", messages);
        var override = server.overrides.get(model.id());
        int max = override != null && override.maxOutputTokens > 0 ? override.maxOutputTokens
                : Math.min(server.maxOutputTokens, model.maxOutput(server.maxOutputTokens));
        String limitParameter = model.accepts("max_completion_tokens") && !model.accepts("max_tokens")
                ? "max_completion_tokens" : "max_tokens";
        body.addProperty(limitParameter, Math.max(1, max));
        if (model.accepts("stream_options")) {
            var streamOptions = new JsonObject(); streamOptions.addProperty("include_usage", true);
            body.add("stream_options", streamOptions);
        }
        if (override != null) {
            if (!override.reasoningEffort.isBlank() && model.accepts("reasoning_effort"))
                body.addProperty("reasoning_effort", override.reasoningEffort);
            if (override.thinkingBudgetTokens > 0 && model.accepts("thinking")) {
                var thinking = new JsonObject();
                thinking.addProperty("type", "enabled");
                thinking.addProperty("budget_tokens", override.thinkingBudgetTokens);
                body.add("thinking", thinking);
            }
        }
        return body;
    }

    Reply stream(JsonObject body, Call call, BiConsumer<String, String> output) throws IOException {
        var tools = new TreeMap<Integer, ToolParts>();
        var content = new StringBuilder();
        var reasoning = new StringBuilder();
        var state = new StreamState();
        state.requestedOutput = (int) number(body, "max_completion_tokens", number(body, "max_tokens", -1));
        BiConsumer<String, String> receive = (kind, text) -> {
            if (kind.equals("text")) content.append(text);
            if (kind.equals("reasoning")) reasoning.append(text);
            output.accept(kind, text);
        };
        var connection = open(base + "/chat/completions", "POST", call);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (var stream = connection.getOutputStream()) { stream.write(bytes); }
        try (var reader = new BufferedReader(new InputStreamReader(checkedBody(connection), StandardCharsets.UTF_8))) {
            StringBuilder event = new StringBuilder();
            for (String line; (line = reader.readLine()) != null;) {
                if (call.cancelled()) throw new CancellationException();
                if (line.isEmpty()) {
                    if (dispatch(event.toString(), receive, tools, state)) return reply(content, reasoning, tools, state);
                    event.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (!event.isEmpty()) event.append('\n');
                    event.append(line.substring(5).stripLeading());
                    if (event.length() > 4_000_000) throw new IOException("SSE event 過大");
                }
            }
            if (!event.isEmpty() && dispatch(event.toString(), receive, tools, state)) return reply(content, reasoning, tools, state);
            if (!call.cancelled()) throw new IOException("STREAM_INCOMPLETE: 串流提前結束，未收到 [DONE]；finish_reason="
                    + (state.finishReason.isEmpty() ? "未提供" : state.finishReason) + "；工具參數可能不完整，未執行。");
            throw new CancellationException();
        }
    }

    private Reply reply(StringBuilder content, StringBuilder reasoning, Map<Integer, ToolParts> parts, StreamState state) throws IOException {
        var calls = new ArrayList<ToolCall>();
        var ids = new java.util.HashSet<String>();
        for (var value : parts.values()) {
            if (value.id.isEmpty() || value.name.isEmpty() || !ids.add(value.id.toString()))
                throw new IOException("工具呼叫缺少 id／name 或 id 重複");
            calls.add(new ToolCall(value.id.toString(), value.name.toString(), value.arguments.toString()));
        }
        var reply = new Reply(content.toString(), List.copyOf(calls), reasoning.toString(), state.finishReason, state.usage, state.requestedOutput);
        com.intellij.openapi.diagnostic.Logger.getInstance(Gateway.class).info("MoLLM stream: " + redact(reply.diagnostics())
                + "; tool_argument_chars=" + calls.stream().map(call -> call.arguments().length()).toList());
        return reply;
    }

    private boolean dispatch(String event, BiConsumer<String, String> output, Map<Integer, ToolParts> tools, StreamState state) throws IOException {
        if (event.isBlank()) return false;
        if (event.strip().equals("[DONE]")) return true;
        JsonObject chunk;
        try { chunk = JsonParser.parseString(event).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("無效的 SSE JSON", e); }
        if (chunk.has("error")) throw new IOException("閘道錯誤：" + redact(chunk.get("error").toString()));
        if (chunk.has("usage") && chunk.get("usage").isJsonObject()) {
            var usage = chunk.getAsJsonObject("usage");
            state.usage = new TokenUsage((long) number(usage, "prompt_tokens", state.usage.prompt()),
                    (long) number(usage, "completion_tokens", state.usage.completion()), (long) number(usage, "total_tokens", state.usage.total()));
        }
        JsonArray choices = array(chunk, "choices");
        if (choices.isEmpty()) return false;
        var choice = choices.get(0).getAsJsonObject();
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull())
            state.finishReason = string(choice, "finish_reason", "");
        JsonObject delta = object(choice, "delta");
        String content = string(delta, "content", "");
        String reasoning = string(delta, "reasoning_content", string(delta, "reasoning", ""));
        if (!reasoning.isEmpty()) output.accept("reasoning", reasoning);
        if (!content.isEmpty()) output.accept("text", content);
        for (var item : array(delta, "tool_calls")) {
            var fragment = item.getAsJsonObject();
            int index = (int) number(fragment, "index", 0);
            if (index < 0 || index >= 16) throw new IOException("單輪工具呼叫超過 16 次");
            var parts = tools.computeIfAbsent(index, ignored -> new ToolParts());
            parts.id.append(string(fragment, "id", ""));
            var function = object(fragment, "function");
            parts.name.append(string(function, "name", ""));
            parts.arguments.append(string(function, "arguments", ""));
            if (parts.id.length() > 256 || parts.name.length() > 128 || parts.arguments.length() > 1_000_000)
                throw new IOException("工具呼叫內容過大");
        }
        return false;
    }

    static JsonObject object(JsonObject source, String name) {
        JsonElement value = source.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    static JsonArray array(JsonObject source, String name) {
        JsonElement value = source.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }
    static String string(JsonObject source, String name, String fallback) {
        JsonElement value = source.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
    static double number(JsonObject source, String name, double fallback) {
        try { return source.has(name) && !source.get(name).isJsonNull() ? source.get(name).getAsDouble() : fallback; }
        catch (RuntimeException e) { return fallback; }
    }
}
