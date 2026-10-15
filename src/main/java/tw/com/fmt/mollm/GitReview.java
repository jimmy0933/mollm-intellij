package tw.com.fmt.mollm;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** Fixed Git operations; the model cannot select an executable, flags, shell or checkout. */
final class GitReview {
    private static final int OUTPUT_LIMIT = 2_000_000;
    private static final SensitiveFiles OUTPUT_SECRETS = new SensitiveFiles(true, true, List.of());
    private final Path workspace;
    private final SensitiveFiles sensitive;
    private final WorkspaceTools.Approval approval;
    private final Set<String> deniedFetches = new HashSet<>();
    private record Output(int exit, String text) { }

    GitReview(Path workspace, SensitiveFiles sensitive, WorkspaceTools.Approval approval) {
        this.workspace = workspace; this.sensitive = sensitive; this.approval = approval;
    }
    String execute(String name, JsonObject args, Gateway.Call call) throws Exception {
        Path repo = repository(Gateway.string(args, "repository", "."));
        if (name.equals("git_fetch_mr")) return fetch(repo, args, call);
        String operation = WorkspaceTools.requiredString(args, "operation");
        String revision = revision(Gateway.string(args, "revision", "HEAD"));
        String result = switch (operation) {
            case "status" -> checked(repo, call, "status", "--short", "--branch", "--untracked-files=no");
            case "refs" -> checked(repo, call, "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads", "refs/remotes", "refs/mollm");
            case "log" -> checked(repo, call, "log", "--no-show-signature", "--format=%H %P %s", "-n", "100", revision, "--");
            case "merge_base" -> checked(repo, call, "merge-base", revision(WorkspaceTools.requiredString(args, "base")), revision);
            case "show" -> blob(repo, revision, sourcePath(args), call);
            case "files" -> changedFiles(repo, revision(WorkspaceTools.requiredString(args, "base")), revision, call);
            case "diff" -> diff(repo, revision(WorkspaceTools.requiredString(args, "base")), revision, sourcePath(args), call);
            default -> throw new IOException("INVALID_GIT_OPERATION: 僅支援 status、refs、log、merge_base、files、diff、show。");
        };
        String header = operation.equals("show") ? "版本=" + revision + "；檔案=" + sourcePath(args) + "\n" : "";
        return header + page(sensitive.redact(OUTPUT_SECRETS.redact(result)), args);
    }
    private Path repository(String value) throws IOException {
        Path path = workspace.resolve(value).normalize().toRealPath();
        if (!path.startsWith(workspace) || !Files.exists(path.resolve(".git"))) throw new IOException("INVALID_REPOSITORY: 請指定工作範圍內的 Git 專案根目錄。");
        sensitive.checkPath(path); return path;
    }
    static String revision(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_./~^+\\-]{0,199}") || value.contains(".."))
            throw new IOException("INVALID_REVISION: 請使用 commit SHA、HEAD 或單一參照名稱，不能傳入選項或指令。");
        return value;
    }
    private static String sourcePath(JsonObject args) throws IOException {
        String value = WorkspaceTools.requiredString(args, "path").replace('\\', '/');
        if (value.isBlank() || value.startsWith("/") || value.contains(":") || value.startsWith("-") || value.codePoints().anyMatch(ch -> ch < 32)
                || List.of(value.split("/")).contains("..")) throw new IOException("INVALID_GIT_PATH: 請使用 repo 相對檔案路徑。");
        return value;
    }
    private void checkSource(Path repo, String path) throws IOException { sensitive.checkPath(repo.resolve(path).normalize()); }
    private String blob(Path repo, String ref, String path, Gateway.Call call) throws Exception {
        checkSource(repo, path);
        if (!checked(repo, call, "cat-file", "-t", ref + ":" + path).trim().equals("blob")) throw new IOException("GIT_NOT_FILE: 請指定版本內的單一檔案。");
        String text = checked(repo, call, "show", "--no-ext-diff", "--no-textconv", ref + ":" + path);
        if (text.length() > WorkspaceTools.MAX_FILE_BYTES) throw new IOException("FILE_TOO_LARGE: 版本檔案超過 1 MB。");
        sensitive.checkContent(text); return text;
    }
    private String changedFiles(Path repo, String base, String head, Gateway.Call call) throws Exception {
        String names = checked(repo, call, "diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-only", "-z", base, head, "--");
        var result = new StringBuilder(); int skipped = 0;
        for (var path : names.split("\0")) {
            if (path.isEmpty()) continue;
            if (sensitive.blocks(repo.resolve(path))) { skipped++; continue; }
            result.append(path.replace("\n", "\\n").replace("\r", "\\r")).append('\n');
        }
        return "比較：" + base + " → " + head + "；已略過敏感檔案=" + skipped + "\n" + result;
    }
    private String diff(Path repo, String base, String head, String path, Gateway.Call call) throws Exception {
        checkSource(repo, path);
        // Inspect both complete versions before returning a patch, not only its visible hunk.
        for (var ref : List.of(base, head)) {
            var exists = run(repo, call, List.of("cat-file", "-e", ref + ":" + path));
            if (exists.exit() == 0) blob(repo, ref, path, call);
        }
        return checked(repo, call, "diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--no-color", base, head, "--", path);
    }
    private String fetch(Path repo, JsonObject args, Gateway.Call call) throws Exception {
        String remote = Gateway.string(args, "remote", "origin");
        if (!remote.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw new IOException("INVALID_REMOTE: 請使用已設定的 remote 名稱。");
        double number = Gateway.number(args, "mr_number", -1);
        if (number < 1 || number > 9_999_999 || number != Math.floor(number)) throw new IOException("INVALID_MR: MR 編號無效。");
        int mr = (int) number;
        String url = checked(repo, call, "remote", "get-url", remote).trim();
        java.net.URI uri;
        try { uri = java.net.URI.create(url); } catch (IllegalArgumentException e) { throw new IOException("INVALID_REMOTE_URL: 遠端網址格式無效。"); }
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IOException("UNSUPPORTED_REMOTE: MR 更新只支援不含帳密的 HTTP(S) remote；請使用本機 Git 認證管理員。");
        String identity = repo + "\0" + remote + "\0" + mr;
        if (deniedFetches.contains(identity)) return "Denied previously: 已拒絕此 MR 更新，未連線。";
        String target = "refs/mollm/merge-requests/" + mr;
        var arguments = new ArrayList<>(List.of("fetch", "--no-tags", "--no-prune", "--no-prune-tags", "--refmap=", "--no-recurse-submodules", "--no-auto-maintenance", "--no-write-fetch-head", remote,
                "+refs/merge-requests/" + mr + "/head:" + target + "/head"));
        var preview = new WorkspaceTools.Command(command(arguments), repo, 120);
        if (!approval.gitFetch(preview, call)) { deniedFetches.add(identity); return "Denied: 未取得 MR 更新；本機狀態未變更。"; }
        if (!repo.toRealPath().equals(repo) || !checked(repo, call, "remote", "get-url", remote).trim().equals(url))
            throw new IOException("GIT_REMOTE_CHANGED: 確認期間專案或 remote 已變動，未連線；請重新確認。");
        checked(repo, call, arguments.toArray(String[]::new));
        arguments.set(arguments.size() - 1, "+refs/merge-requests/" + mr + "/merge:" + target + "/merge");
        var merge = run(repo, call, arguments);
        String head = checked(repo, call, "rev-parse", "--verify", target + "/head").trim();
        return "已取得 MR " + mr + " 更新；head=" + head + "；參照=" + target + "/head。\n"
                + (merge.exit() == 0 ? "merge 參照已更新：" + target + "/merge；可用 git_read show/log 讀取父 commit。" : "遠端 merge 參照目前無法取得；請勿使用舊 merge 參照判定 base，先確認比較基準。")
                + "\n未 checkout、未變更分支、索引或工作檔案；只更新物件與專用參照。";
    }
    private static List<String> command(List<String> operation) throws IOException {
        var argv = new ArrayList<String>(); argv.add(executable().toString());
        argv.addAll(List.of("--no-pager", "--no-optional-locks", "--literal-pathspecs", "-c", "core.fsmonitor=false", "-c", "core.hooksPath=" + (isWindows() ? "NUL" : "/dev/null"),
                "-c", "core.askPass=", "-c", "credential.helper=", "-c", "credential.helper=manager", "-c", "credential.interactive=false",
                "-c", "http.extraHeader=", "-c", "protocol.allow=never", "-c", "protocol.http.allow=always", "-c", "protocol.https.allow=always"));
        argv.addAll(operation); return List.copyOf(argv);
    }
    static Path executable() throws IOException {
        var candidates = new ArrayList<Path>();
        if (isWindows()) {
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles != null) candidates.add(Path.of(programFiles, "Git", "cmd", "git.exe"));
        }
        for (var entry : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) { Path directory = Path.of(entry); if (directory.isAbsolute()) candidates.add(directory.resolve(isWindows() ? "git.exe" : "git")); }
        }
        for (var candidate : candidates) if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toRealPath();
        throw new IOException("GIT_NOT_FOUND: 找不到本機 Git；請先安裝 Git 並重新啟動 IDE。");
    }
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"); }
    private String checked(Path repo, Gateway.Call call, String... arguments) throws Exception {
        var result = run(repo, call, List.of(arguments));
        if (result.exit() != 0) throw new IOException("GIT_FAILED: " + OUTPUT_SECRETS.redact(sensitive.redact(result.text())));
        return result.text();
    }
    private Output run(Path repo, Gateway.Call call, List<String> arguments) throws Exception {
        AgentLoop.checkCancelled(call);
        var builder = new ProcessBuilder(command(arguments)).directory(repo.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.toUpperCase(java.util.Locale.ROOT).startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1"); builder.environment().put("GIT_CONFIG_GLOBAL", isWindows() ? "NUL" : "/dev/null");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0"); builder.environment().put("GCM_INTERACTIVE", "Never");
        var process = builder.start(); process.getOutputStream().close();
        var bytes = new ByteArrayOutputStream(); var overflow = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = new java.util.concurrent.atomic.AtomicReference<IOException>();
        var reader = Thread.ofVirtual().start(() -> {
            try (var input = process.getInputStream()) {
                byte[] buffer = new byte[8192]; int size;
                while ((size = input.read(buffer)) != -1) {
                    int available = OUTPUT_LIMIT - bytes.size(); if (size > available) overflow.set(true);
                    if (available > 0) bytes.write(buffer, 0, Math.min(size, available));
                }
            } catch (IOException e) { failure.set(e); }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        try {
            while (!process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                AgentLoop.checkCancelled(call);
                if (System.nanoTime() > deadline) throw new IOException("GIT_TIMEOUT: Git 操作逾時。");
            }
            reader.join(2000); AgentLoop.checkCancelled(call);
            if (reader.isAlive() || failure.get() != null) throw new IOException("GIT_OUTPUT_FAILED: Git 輸出未完整讀取。");
            if (overflow.get()) throw new IOException("GIT_OUTPUT_LIMIT: 輸出超過 2 MB，請縮小檔案或比較範圍。");
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString(); }
            catch (java.nio.charset.CharacterCodingException e) { throw new IOException("GIT_BINARY_FILE: Git 輸出不是 UTF-8 文字。"); }
            if (text.indexOf('\0') >= 0 && !arguments.contains("-z")) throw new IOException("GIT_BINARY_FILE: 版本檔案不是文字檔。");
            return new Output(process.exitValue(), text);
        } finally {
            process.descendants().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly(); process.getInputStream().close(); reader.join(2000);
        }
    }
    private static String page(String text, JsonObject args) throws IOException {
        int offset = integer(args, "offset", 0, 0, 1_000_000), size = integer(args, "page_size", 120, 1, 200);
        String[] lines = text.split("\n", -1); int end = Math.min(lines.length, offset + size);
        var out = new StringBuilder(); int index = offset;
        for (; index < end; index++) {
            String line = lines[index]; if (line.length() > 12_000) throw new IOException("GIT_LONG_LINE: 單行過長，請縮小內容。");
            if (out.length() + line.length() > 16_000) break;
            out.append(index + 1).append(": ").append(line).append('\n');
        }
        return "offset=" + offset + "; next_offset=" + (index < lines.length ? index : "none") + "; total_lines=" + lines.length + "\n" + out;
    }
    private static int integer(JsonObject args, String key, int fallback, int min, int max) throws IOException {
        double value = Gateway.number(args, key, fallback);
        if (value < min || value > max || !Double.isFinite(value) || value != Math.floor(value)) throw new IOException("INVALID_ARGUMENT: " + key);
        return (int) value;
    }
}
