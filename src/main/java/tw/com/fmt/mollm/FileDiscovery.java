package tw.com.fmt.mollm;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Predicate;

/** Bounded traversal with explicit completeness and resumable result pages. */
final class FileDiscovery {
    static final int MAX_VISITED = 20_000, MAX_RESULTS = 5000, MAX_DEPTH = 20;
    record Entry(Path path, boolean directory) { }
    record Scan(List<Entry> entries, boolean complete, int skipped) { }
    private FileDiscovery() { }

    static Scan scan(Path start, boolean recursive, Predicate<Entry> filter, Gateway.Call call) throws IOException {
        AgentLoop.checkCancelled(call);
        if (!Files.isDirectory(start)) throw new IOException("NOT_DIRECTORY: 請指定資料夾：" + start);
        var entries = new ArrayList<Entry>();
        var visited = new int[]{0}; var skipped = new int[]{0}; var complete = new boolean[]{true};
        Files.walkFileTree(start, Set.of(FileVisitOption.FOLLOW_LINKS), recursive ? MAX_DEPTH : 1, new SimpleFileVisitor<>() {
            private FileVisitResult accept(Path path, BasicFileAttributes attrs) {
                AgentLoop.checkCancelled(call);
                if (++visited[0] > MAX_VISITED) { complete[0] = false; return FileVisitResult.TERMINATE; }
                var entry = new Entry(path, attrs.isDirectory());
                if (filter.test(entry)) {
                    if (entries.size() >= MAX_RESULTS) { complete[0] = false; return FileVisitResult.TERMINATE; }
                    entries.add(entry);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return dir.equals(start) ? FileVisitResult.CONTINUE : accept(dir, attrs);
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // Directories at the recursive depth limit are returned but cannot be traversed.
                if (recursive && attrs.isDirectory()) complete[0] = false;
                return accept(file, attrs);
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                AgentLoop.checkCancelled(call); skipped[0]++; complete[0] = false;
                if (file.equals(start)) throw new IOException((error instanceof AccessDeniedException ? "ACCESS_DENIED: " : "SCAN_FAILED: ") + start, error);
                if (++visited[0] > MAX_VISITED) return FileVisitResult.TERMINATE;
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(entry -> entry.path().toString()));
        return new Scan(List.copyOf(entries), complete[0], skipped[0]);
    }

    static String page(List<String> entries, int offset, int pageSize, boolean complete, int skipped) {
        var result = new StringBuilder(); int index = offset;
        while (index < entries.size() && index - offset < pageSize) {
            String line = entries.get(index);
            if (result.length() + line.length() + 1 > 16_000) {
                if (index == offset) { result.append("[Entry path too long to display; narrow to its parent directory.]\n"); index++; complete = false; }
                break;
            }
            result.append(line).append('\n'); index++;
        }
        boolean more = index < entries.size();
        return "offset=" + offset + "; returned=" + (index - offset) + "; next_offset=" + (more ? index : "none")
                + "; scan_complete=" + complete + "; skipped=" + skipped + "\n"
                + (entries.isEmpty() ? "No matches.\n" : result)
                + (!complete ? "[Scan incomplete: narrow the path; missing results are not proof of absence.]\n" : "");
    }
}
