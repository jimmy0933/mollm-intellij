package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

final class ToolReliabilityTest {
    private static int checks;
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked action, String code) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (IOException expected) { check(expected.getMessage().contains(code), code); }
    }
    static void run() throws Exception {
        for (String invalid : List.of("{\"path\":\"partial", "{} {}", "[]", "null", "{'path':'x'}", "{path:1}", "{\"x\":1,}"))
            fails(() -> ToolArguments.parse(invalid), "INVALID_TOOL_ARGUMENTS");
        check(ToolArguments.parse("{\"content\":\"中文\\ntext\"}").get("content").getAsString().equals("中文\ntext"), "Strict JSON accepts complete Unicode content");
        var model = new Gateway.Model("mock", args("{\"supported_openai_params\":[\"stream_options\"]}"));
        check(Gateway.request(model, List.of(), new MollmSettings.Server()).getAsJsonObject("stream_options").get("include_usage").getAsBoolean(), "Usage requested only when gateway advertises stream_options support");
        check(!Gateway.request(new Gateway.Model("mock", new JsonObject()), List.of(), new MollmSettings.Server()).has("stream_options"), "Unsupported usage request option omitted for compatibility");
        var approvals = new AtomicInteger();
        Path root = Files.createTempDirectory(Path.of("build"), "reliability-").toRealPath();
        Files.createDirectory(root.resolve(".git")); Files.createDirectory(root.resolve("doc"));
        var workspace = new WorkspaceTools(root, new WorkspaceTools.FilesAccess() {
            public String read(Path path) throws Exception { return WorkspaceTools.readUtf8(path); }
            public void apply(WorkspaceTools.Change change, Gateway.Call call) throws Exception { Files.writeString(change.path(), change.after()); }
        }, new WorkspaceTools.Approval() {
            public boolean change(WorkspaceTools.Change change, Gateway.Call call) { approvals.incrementAndGet(); return true; }
            public boolean command(WorkspaceTools.Command command, Gateway.Call call) { return false; }
        });
        try (var call = new Gateway.Call()) {
            fails(() -> workspace.execute("read_file", args("{\"path\":\"missing.md\"}"), call), "NOT_FOUND");
            fails(() -> workspace.execute("read_file", args("{\"path\":\"doc\"}"), call), "IS_DIRECTORY");
            Files.writeString(root.resolve("big.txt"), "x".repeat(1_000_001));
            fails(() -> workspace.execute("read_file", args("{\"path\":\"big.txt\"}"), call), "FILE_TOO_LARGE");
            Files.write(root.resolve("binary.bin"), new byte[]{0});
            fails(() -> workspace.execute("read_file", args("{\"path\":\"binary.bin\"}"), call), "BINARY_FILE");
            for (int i = 0; i < 305; i++) Files.createDirectory(root.resolve(String.format("repo-%03d", i)));
            String first = workspace.execute("list_directory", args("{\"page_size\":300}"), call);
            check(first.contains("next_offset=300") && first.contains("scan_complete=true"), "Top-level repositories paginate without recursive starvation");
            String second = workspace.execute("list_directory", args("{\"page_size\":300,\"offset\":300}"), call);
            check(second.contains("repo-304") && second.contains("next_offset=none"), "Remaining repository appears on next page");
            Files.writeString(root.resolve("repo-304/Target.java"), "needle\n".repeat(85));
            String find = workspace.execute("find_files", args("{\"query\":\"TARGET\"}"), call);
            check(find.contains("repo-304/Target.java") && find.contains("scan_complete=true"), "Name lookup is case-insensitive and independent of contents");
            String search = workspace.execute("search_text", args("{\"path\":\"repo-304/Target.java\",\"query\":\"needle\"}"), call);
            check(search.contains("next_offset=80") && search.contains("search_complete=false"), "Search provides next page instead of silent truncation");
            String tail = workspace.execute("search_text", args("{\"path\":\"repo-304/Target.java\",\"query\":\"needle\",\"offset\":80}"), call);
            check(tail.contains("Target.java:85:") && tail.contains("returned=5") && tail.contains("search_complete=true"), "Search continuation preserves remaining matches");
            String incomplete = workspace.execute("search_text", args("{\"query\":\"not-present\"}"), call);
            check(incomplete.contains("No matches.") && incomplete.contains("search_complete=false") && incomplete.contains("skipped=2"), "Oversized/binary skips cannot be reported as definitive absence");
            Path deep = root.resolve("deep");
            for (int i = 0; i < 22; i++) { deep = deep.resolve("d"); Files.createDirectories(deep); }
            check(workspace.execute("find_files", args("{\"query\":\"absent\",\"path\":\"deep\"}"), call).contains("scan_complete=false"), "Depth cap exposes incomplete scanning");
            Files.writeString(root.resolve("long.txt"), "x".repeat(20_000));
            String slice = workspace.execute("read_file", args("{\"path\":\"long.txt\"}"), call);
            check(slice.contains("next_start_line=1") && slice.contains("next_start_column=15996"), "Long line can be resumed without losing text");
            check(workspace.execute("read_file", args("{\"path\":\"long.txt\",\"start_column\":15996}"), call).contains("next_start_line=none"), "Long-line remainder completes");
            fails(() -> workspace.execute("list_files", args("{\"offset\":\"bad\"}"), call), "INVALID_ARGUMENT");
            String header = "# MR 51\nHead: abc\nBase: def\n測試：未執行\n";
            var report = new JsonObject(); report.addProperty("directory", "doc"); report.addProperty("mr_number", 51); report.addProperty("content", header);
            check(workspace.execute("create_review_report", report, call).contains("mr51-mollm.md"), "Report created at repository root with MoLLM naming");
            check(Files.exists(root.resolve("mr51-mollm.md")) && !Files.exists(root.resolve("doc/mr51-mollm.md")), "Reports stay in project root");
            check(workspace.execute("create_review_report", report, call).contains("mr51-mollm-2.md"), "Existing report preserved with numeric suffix");
            check(Files.readString(root.resolve("mr51-mollm.md")).equals(header), "Collision never overwrites existing report");
            var canonical = new JsonObject(); canonical.addProperty("path", "mr51-mollm.md"); canonical.addProperty("content", header);
            check(workspace.execute("create_file", canonical, call).contains("mr51-mollm-3.md"), "Direct canonical report creation also preserves existing files with suffix");
            var legacy = new JsonObject(); legacy.addProperty("path", "doc/mr52-review.md"); legacy.addProperty("content", header);
            check(workspace.execute("create_file", legacy, call).contains("mr52-mollm.md"), "Legacy review name routes to enforced project-root name");
            var append = new JsonObject(); append.addProperty("path", "mr51-mollm.md"); append.addProperty("expected_chars", header.length()); append.addProperty("content", "中文附件\n");
            check(workspace.execute("append_file", append, call).startsWith("Applied"), "Chunk append applies after approval");
            int approved = approvals.get();
            check(workspace.execute("append_file", append, call).startsWith("Already applied"), "Retried chunk does not duplicate content");
            check(approvals.get() == approved && Files.readString(root.resolve("mr51-mollm.md")).equals(header + "中文附件\n"), "Idempotent retry needs no new approval or write");
            append.addProperty("content", "different");
            fails(() -> workspace.execute("append_file", append, call), "FILE_CHANGED");
            check(approvals.get() == approved, "Length mismatch fails before approval");
            var reportCheck = args(workspace.execute("check_review_report", args("{\"path\":\"mr51-mollm.md\"}"), call));
            check(reportCheck.get("structure_ok").getAsBoolean() && reportCheck.get("disk_matches_editor").getAsBoolean() && !reportCheck.get("review_verified").getAsBoolean(), "Full report check confirms storage without certifying findings");
            check(workspace.completionChecks().contains("mr51-mollm-2.md"), "Completion automatically checks created reports");
            var priorCall = new Gateway.ToolCall("saved-report", "create_review_report", "{}");
            var restoredWorkspace = new WorkspaceTools(root, new WorkspaceTools.FilesAccess() {
                public String read(Path path) throws Exception { return WorkspaceTools.readUtf8(path); }
                public void apply(WorkspaceTools.Change change, Gateway.Call token) { throw new AssertionError("Completion wrote to disk"); }
            }, new WorkspaceTools.Approval() {
                public boolean change(WorkspaceTools.Change change, Gateway.Call token) { return false; }
                public boolean command(WorkspaceTools.Command command, Gateway.Call token) { return false; }
            });
            check(restoredWorkspace.completionChecks(List.of(new Gateway.Message("assistant", "", List.of(priorCall), ""),
                    new Gateway.Message("tool", "Applied and saved: mr51-mollm.md (new file); chars=40; lines=5", List.of(), "saved-report")))
                    .contains("disk_matches_editor"), "Continuation restores created-report checks from paired save results");
            String counts = header + "共 2 個高風險、1 個低風險\n| 1 | 高 | issue |\n";
            check(!args(ReviewReportCheck.inspect(root.resolve("mr51-mollm.md"), counts, true)).get("structure_ok").getAsBoolean(), "Risk count mismatch detected including zero table rows for a declared category");
            var denied = new WorkspaceTools(root, new WorkspaceTools.FilesAccess() {
                public String read(Path path) throws Exception { return WorkspaceTools.readUtf8(path); }
                public void apply(WorkspaceTools.Change change, Gateway.Call token) { throw new AssertionError("Denied write executed"); }
            }, new WorkspaceTools.Approval() {
                public boolean change(WorkspaceTools.Change change, Gateway.Call token) { return false; }
                public boolean command(WorkspaceTools.Command command, Gateway.Call token) { return false; }
            });
            report.addProperty("mr_number", 99);
            check(denied.execute("create_review_report", report, call).startsWith("Denied") && !Files.exists(root.resolve("mr99-mollm.md")), "Report creation retains approval semantics");
            var changing = new WorkspaceTools(root, new WorkspaceTools.FilesAccess() {
                public String read(Path path) throws Exception { return WorkspaceTools.readUtf8(path); }
                public void apply(WorkspaceTools.Change change, Gateway.Call token) { throw new AssertionError("Concurrent edit overwritten"); }
            }, new WorkspaceTools.Approval() {
                public boolean change(WorkspaceTools.Change change, Gateway.Call token) throws Exception { Files.writeString(change.path(), "changed during approval"); return true; }
                public boolean command(WorkspaceTools.Command command, Gateway.Call token) { return false; }
            });
            append.addProperty("expected_chars", Files.readString(root.resolve("mr51-mollm.md")).length()); append.addProperty("content", "another chunk");
            fails(() -> changing.execute("append_file", append, call), "文件已變動");
            check(Files.readString(root.resolve("mr51-mollm.md")).equals("changed during approval"), "Append preserves concurrent user changes");
        }
        try (var cancelled = new Gateway.Call()) {
            cancelled.cancel(); boolean stopped = false;
            try { FileDiscovery.scan(root, true, entry -> true, cancelled); } catch (java.util.concurrent.CancellationException expected) { stopped = true; }
            check(stopped, "Directory discovery remains cancellable before traversal");
        }
        var guard = new AgentFailureGuard();
        check(!guard.record("create_file", "bad", "Error: INVALID_TOOL_ARGUMENTS: 20 chars")
                && guard.record("create_file", "bad", "Error: INVALID_TOOL_ARGUMENTS: 30 chars"), "Repeated incomplete JSON detected despite length variation");
        guard.record("read_file", "ok", "success");
        check(!guard.record("create_file", "bad", "Error: INVALID_TOOL_ARGUMENTS"), "Success resets consecutive failure guard");
        String reminder = AgentTaskContext.describe(List.of(new Gateway.Message("user", "review dsp mr51"), new Gateway.Message("assistant", "found reports"), new Gateway.Message("user", "review momo-dsp 就好"), new Gateway.Message("user", "產生 review md")));
        check(reminder.contains("mr51") && reminder.contains("momo-dsp") && reminder.contains("review md"), "Original MR goal and later scope survive every round");
        streamsAndFailures();
        System.out.println("PASS: " + checks + " tool reliability checks.");
    }

    private static void streamsAndFailures() throws Exception {
        var requests = new AtomicInteger(); var executed = new AtomicInteger(); var mode = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes(); int count = requests.incrementAndGet();
            var delta = new JsonObject(); var choice = new JsonObject();
            if (mode.get() == 3 || mode.get() == 6 || (mode.get() == 2 && count > 1)) delta.addProperty("content", "done");
            else if (mode.get() == 5) delta.addProperty("reasoning_content", "thinking reached output budget");
            else {
                var function = new JsonObject(); function.addProperty("name", "create_file");
                function.addProperty("arguments", mode.get() == 1 ? "{\"path\":\"report.md\",\"content\":\"safe\"}" : "{\"path\":\"partial");
                var tool = new JsonObject(); tool.addProperty("index", 0); tool.addProperty("id", "call-" + count); tool.add("function", function);
                var calls = new com.google.gson.JsonArray(); calls.add(tool);
                if (mode.get() == 4) {
                    var duplicate = tool.deepCopy(); duplicate.addProperty("index", 1); duplicate.addProperty("id", "second"); calls.add(duplicate);
                    var third = tool.deepCopy(); third.addProperty("index", 2); third.addProperty("id", "third");
                    third.getAsJsonObject("function").addProperty("arguments", "{\"path\":\"never.md\",\"content\":\"must not execute\"}"); calls.add(third);
                }
                delta.add("tool_calls", calls);
            }
            choice.add("delta", delta);
            if (mode.get() != 6) choice.addProperty("finish_reason", mode.get() == 1 || mode.get() == 5 ? "length" : delta.has("tool_calls") ? "tool_calls" : "stop");
            var choices = new com.google.gson.JsonArray(); choices.add(choice); var chunk = new JsonObject(); chunk.add("choices", choices);
            String usage = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":8192,\"total_tokens\":8292}}\n\n";
            byte[] bytes = ("data: " + chunk + "\n\n" + (mode.get() == 6 ? "" : usage) + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        var tools = new AgentLoop.Tools() {
            public String context() { return "test"; }
            public String execute(String name, JsonObject args, Gateway.Call call) { executed.incrementAndGet(); return "Applied"; }
        };
        try {
            var api = new Gateway("http://127.0.0.1:" + server.getAddress().getPort(), "", 2000);
            var body = Gateway.request(new Gateway.Model("mock", new JsonObject()), List.of(), new MollmSettings.Server());
            mode.set(1);
            try (var call = new Gateway.Call()) {
                var reply = api.stream(body, call, (kind, text) -> {});
                check(reply.truncated() && reply.usage().completion() == 8192 && reply.usage().prompt() == 100, "Terminal finish reason and usage-only SSE event retained");
                check(reply.diagnostics().contains("requested_output=8192") && reply.diagnostics().contains("total_tokens=8292"), "Diagnostic distinguishes requested quota from actual usage");
            }
            for (int failureMode : List.of(0, 1)) {
                mode.set(failureMode); requests.set(0); executed.set(0); var activity = new ArrayList<String>();
                try (var call = new Gateway.Call()) {
                    var result = new AgentLoop(tools, List.of(new Gateway.Message("user", "write report")), new AgentInbox(), new AgentLimits(80, 256, true))
                            .run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity::add);
                    check(!result.completed() && requests.get() == 2 && executed.get() == 0, "Repeated invalid/truncated tools pause unlimited run without execution");
                    check(result.messages().stream().filter(message -> message.role().equals("tool")).count() == 2, "Paused history retains paired tool errors for continuation");
                    check(activity.stream().anyMatch(value -> value.contains(failureMode == 0 ? "INVALID_TOOL_ARGUMENTS" : "OUTPUT_TRUNCATED")), "Cause is visible instead of raw EOF guess");
                }
            }
            mode.set(2); requests.set(0);
            try (var call = new Gateway.Call()) {
                var result = new AgentLoop(tools, List.of(new Gateway.Message("user", "recover one failure")))
                        .run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity -> {});
                check(result.completed() && requests.get() == 2, "One parameter error can recover without forced stop");
            }
            mode.set(4); requests.set(0); executed.set(0);
            try (var call = new Gateway.Call()) {
                var result = new AgentLoop(tools, List.of()).run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity -> {});
                check(!result.completed() && requests.get() == 1 && executed.get() == 0, "Repeated failure within a batch skips later valid writes");
                check(result.messages().stream().filter(message -> message.role().equals("tool")).count() == 3
                        && result.messages().stream().anyMatch(message -> message.content().contains("segment paused")), "Skipped batch tools remain paired for safe continuation");
            }
            mode.set(5); requests.set(0);
            try (var call = new Gateway.Call()) {
                var result = new AgentLoop(tools, List.of()).run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> {}, activity -> {});
                check(!result.completed() && requests.get() == 1 && result.messages().getLast().content().contains("輸出額度"), "Thinking-only output exhaustion pauses with correct cause");
            }
            mode.set(6); requests.set(0);
            try (var call = new Gateway.Call()) {
                var reply = api.stream(body, call, (kind, text) -> {});
                check(reply.usage().total() == -1 && reply.finishReason().isEmpty() && reply.diagnostics().contains("未提供"), "Missing metadata remains unavailable, not invented zero usage");
            }
            mode.set(3); requests.set(0);
            var reportTools = new AgentLoop.Tools() {
                public String context() { return "test"; }
                public String execute(String name, JsonObject args, Gateway.Call call) { return "unused"; }
                public String completionChecks() { return "storage check, not review certification"; }
            };
            try (var call = new Gateway.Call()) {
                var output = new StringBuilder();
                new AgentLoop(reportTools, List.of()).run(api, new Gateway.Model("mock", new JsonObject()), new MollmSettings.Server(), call, (kind, text) -> output.append(text), activity -> {});
                check(output.toString().contains("storage check"), "Completion checks are displayed even when model forgets to request them");
            }
        } finally { server.stop(0); }
    }
}
