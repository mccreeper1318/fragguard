package org.pinnaclesmp.fragguard;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Passive host-visible filesystem signals for where a Unix SQLite VFS might
 * place sort temporary files. Never assume a candidate is the native VFS's
 * actual selected directory; never log environment values or absolute paths.
 */
final class SqliteTempStorageProbe {
    private SqliteTempStorageProbe() {
    }

    static String describe(File databaseFile) {
        List<String> candidates = new ArrayList<>();
        Path dataDirectory = databaseFile.toPath().toAbsolutePath().getParent();
        FileStore dataStore = fileStore(dataDirectory);

        // Order documented for the Unix VFS; actual selected directory can
        // differ with native global settings, VFS, or file unlink behavior.
        inspectEnvironment(candidates, "SQLITE_TMPDIR", dataStore);
        inspectEnvironment(candidates, "TMPDIR", dataStore);
        inspect(candidates, "var-tmp", new File("/var/tmp"), dataStore);
        inspect(candidates, "usr-tmp", new File("/usr/tmp"), dataStore);
        inspect(candidates, "tmp", new File("/tmp"), dataStore);
        inspect(candidates, "cwd", new File("."), dataStore);

        // Java tmpdir is an independent host signal, NOT a SQLite VFS locator.
        String javaTemp = System.getProperty("java.io.tmpdir", "");
        if (!javaTemp.isBlank()) {
            inspect(candidates, "java-tmp(advisory)", new File(javaTemp), dataStore);
        }

        return String.join("; ", candidates)
                + "; selected-sqlite-native-temp=unverified"
                + "; host-visible-space-does-not-prove-container-quota";
    }

    private static void inspectEnvironment(List<String> candidates, String variable, FileStore dataStore) {
        String value;
        try {
            value = System.getenv(variable);
        } catch (SecurityException ignored) {
            candidates.add(variable + ":environment-unavailable");
            return;
        }
        if (value == null || value.isBlank()) {
            candidates.add(variable + ":unset");
            return;
        }
        // Do not emit value, which can contain user/system information.
        inspect(candidates, variable + "(value-hidden)", new File(value), dataStore);
    }

    private static void inspect(List<String> entries, String name, File path, FileStore dataStore) {
        try {
            if (!path.isDirectory()) {
                entries.add(name + ":absent");
                return;
            }
            boolean access = path.canWrite() && path.canExecute();
            long free = path.getUsableSpace();
            long total = path.getTotalSpace();
            FileStore storage = fileStore(path.toPath());
            String fsType = storage == null ? "unknown" : safeType(storage);
            String sameFs = dataStore == null || storage == null ? "unknown"
                    : Boolean.toString(dataStore.equals(storage));
            entries.add(name + ":exists,write-exec=" + access
                    + ",host-usable=" + capacity(free)
                    + ",host-total=" + capacity(total)
                    + ",fs-type=" + fsType
                    + ",same-filestore-as-data=" + sameFs);
        } catch (SecurityException | IllegalArgumentException ignored) {
            entries.add(name + ":unavailable");
        }
    }

    private static String safeType(FileStore store) {
        try {
            String type = store.type();
            // Avoid exposing unexpected provider-controlled strings.
            if (type == null || !type.matches("[A-Za-z0-9_.-]{1,40}")) {
                return "unavailable";
            }
            return type;
        } catch (SecurityException ignored) {
            return "unknown";
        }
    }

    private static FileStore fileStore(Path path) {
        try {
            return path != null && Files.isDirectory(path) ? Files.getFileStore(path) : null;
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private static String capacity(long bytes) {
        return bytes > 0L ? MigrationStoragePolicy.formatBytes(bytes) : "unknown";
    }
}
