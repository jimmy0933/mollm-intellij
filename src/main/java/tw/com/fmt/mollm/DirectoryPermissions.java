package tw.com.fmt.mollm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persistent opt-in permissions are isolated by canonical Agent working directory and tool kind. */
final class DirectoryPermissions {
    enum Kind { FILES, COMMANDS }
    private final MollmSettings settings;
    DirectoryPermissions(MollmSettings settings) { this.settings = settings; }

    boolean allows(Path root, Path target, Kind kind) {
        try {
            Path canonicalRoot = stableRoot(root);
            if (!canonicalTarget(target).startsWith(canonicalRoot)) return false;
            for (var grant : grants()) {
                try {
                    if (stableRoot(Path.of(grant.directory)).equals(canonicalRoot)
                            && (kind == Kind.FILES ? grant.allowFiles : grant.allowCommands)) return true;
                } catch (IOException | IllegalArgumentException ignored) { /* Invalid or moved directories never auto-approve. */ }
            }
        } catch (IOException | IllegalArgumentException ignored) { /* Fail closed; the normal confirmation remains available. */ }
        return false;
    }

    void allow(Path root, Kind kind) throws IOException {
        synchronized (settings) {
            Path canonical = stableRoot(root);
            boolean files = false, commands = false;
            for (var grant : grants()) if (samePath(grant.directory, canonical)) {
                files |= grant.allowFiles; commands |= grant.allowCommands;
            }
            set(canonical, files || kind == Kind.FILES, commands || kind == Kind.COMMANDS);
        }
    }

    void set(Path root, boolean files, boolean commands) throws IOException {
        synchronized (settings) {
            Path canonical = stableRoot(root);
            var updated = new ArrayList<>(grants());
            updated.removeIf(grant -> samePath(grant.directory, canonical));
            if (files || commands) {
                var grant = new MollmSettings.DirectoryGrant();
                grant.directory = canonical.toString(); grant.allowFiles = files; grant.allowCommands = commands;
                updated.add(grant);
            }
            settings.getState().directoryGrants = List.copyOf(updated);
        }
    }

    List<MollmSettings.DirectoryGrant> grants() {
        synchronized (settings) {
            var grants = settings.getState().directoryGrants;
            return grants == null ? List.of() : List.copyOf(grants);
        }
    }

    void revoke(String directory) {
        synchronized (settings) {
            settings.getState().directoryGrants = grants().stream().filter(grant -> !grant.directory.equals(directory)).toList();
        }
    }

    static boolean samePath(String stored, Path expected) {
        try { return Path.of(stored).toAbsolutePath().normalize().equals(expected); }
        catch (IllegalArgumentException e) { return false; }
    }
    private static Path stableRoot(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize(), real = normalized.toRealPath();
        if (!normalized.equals(real) || !Files.isDirectory(real)) throw new IOException("工作目錄已變更或不是資料夾，請重新選擇。");
        return real;
    }
    private static Path canonicalTarget(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize(), existing = normalized;
        while (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) throw new IOException("找不到目標的上層目錄。");
        }
        return existing.toRealPath().resolve(existing.relativize(normalized)).normalize();
    }
}
