package org.pinnaclesmp.fragguard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in full-history verification. Scans a single read-only SQLite snapshot on
 * its own worker, never the main thread or the live writer. This reports
 * evidence only: it NEVER fixes an index, migrates a schema or changes a row.
 *
 * The manifest contains fingerprints/counts, not recorded player, inventory,
 * world, or block-state values. Future candidate verification (#166) can use
 * the per-table digests from one consistent snapshot.
 */
final class DatabaseDeepDiagnostics implements AutoCloseable {
    private static final int MAX_ISSUES = 64;
    private static final int MAX_TIMEOUT_SECONDS = 21_600;
    private static final List<String> TABLES = List.of(
            "block_changes", "rollback_jobs", "rollback_job_changes", "rollback_job_chunks");
    // Complete persisted v9 column inventory, not just the fields used by
    // the diagnostic itself. A missing column can make a saved rollback or
    // retained history unreadable even when a SELECT * scan succeeds.
    // Keep this aligned with Database's CREATE TABLE and v1-v9 migrations.
    private static final Map<String, List<String>> REQUIRED_COLUMNS = Map.of(
            "block_changes", List.of(
                    "id", "happened_at", "actor_uuid", "actor_name", "world",
                    "x", "y", "z", "action", "before_data", "after_data",
                    "world_uuid", "chunk_x", "chunk_z", "coalesce_session",
                    "server_tick", "before_entity_data", "after_entity_data",
                    "rollback_pending", "causal_player_uuid", "causal_player_name",
                    "causal_chain_id", "causal_cause_type"),
            "rollback_jobs", List.of(
                    "id", "created_at", "updated_at", "actor_uuid", "actor_name",
                    "world_uuid", "world", "center_x", "center_z", "radius",
                    "target_timestamp", "snapshot_timestamp", "force", "status",
                    "total_blocks", "processed_blocks", "applied_blocks",
                    "conflict_blocks", "undo_processed_blocks", "last_error",
                    "scope_type", "target_player_uuid", "target_player_name",
                    "world_scope_uuid", "world_scope_name", "historical_conflicts",
                    "affected_worlds", "affected_chunks"),
            "rollback_job_changes", List.of(
                    "job_id", "sequence", "world", "x", "y", "z",
                    "before_data", "target_data", "expected_data", "processed",
                    "applied", "conflicted", "undone", "before_entity_data",
                    "target_entity_data", "expected_entity_data", "applied_data",
                    "applied_entity_data", "pending_audit_id", "pending_audit_undo",
                    "world_uuid", "physical_actor_uuid", "physical_actor_name",
                    "causal_player_uuid", "causal_chain_id", "causal_cause_type"),
            "rollback_job_chunks", List.of(
                    "job_id", "world_uuid", "chunk_x", "chunk_z"));
    private static final Map<String, String> REQUIRED_INDEXES = Map.ofEntries(
            Map.entry("idx_fg_time", "block_changes"),
            Map.entry("idx_fg_spatial_time", "block_changes"),
            Map.entry("idx_fg_actor_time", "block_changes"),
            Map.entry("idx_fg_actor_world_time", "block_changes"),
            Map.entry("idx_fg_actor_name_uuid", "block_changes"),
            Map.entry("idx_fg_causal_actor_time", "block_changes"),
            Map.entry("idx_fg_causal_actor_world_time", "block_changes"),
            Map.entry("idx_fg_causal_name_uuid", "block_changes"),
            Map.entry("idx_fg_active_jobs", "rollback_jobs"),
            Map.entry("idx_fg_pending_changes", "rollback_job_changes"),
            Map.entry("idx_fg_undo_changes", "rollback_job_changes"),
            Map.entry("idx_fg_job_chunk_overlap", "rollback_job_chunks"));
    // Index key sequences matter for historic player lookups and undo jobs.
    // Every name and field below is fixed by FragGuard, never user-controlled SQL.
    private static final Map<String, List<String>> INDEX_COLUMNS = Map.ofEntries(
            Map.entry("idx_fg_time", List.of("happened_at")),
            Map.entry("idx_fg_spatial_time", List.of("world_uuid", "chunk_x", "chunk_z", "happened_at")),
            Map.entry("idx_fg_actor_time", List.of("actor_uuid", "happened_at", "id")),
            Map.entry("idx_fg_actor_world_time", List.of("actor_uuid", "world_uuid", "happened_at", "id")),
            Map.entry("idx_fg_actor_name_uuid", List.of("actor_name", "actor_uuid")),
            Map.entry("idx_fg_causal_actor_time", List.of("causal_player_uuid", "happened_at", "id")),
            Map.entry("idx_fg_causal_actor_world_time",
                    List.of("causal_player_uuid", "world_uuid", "happened_at", "id")),
            Map.entry("idx_fg_causal_name_uuid", List.of("causal_player_name", "causal_player_uuid")),
            Map.entry("idx_fg_active_jobs", List.of("world_uuid", "status")),
            Map.entry("idx_fg_pending_changes", List.of("job_id", "processed", "sequence")),
            Map.entry("idx_fg_undo_changes", List.of("job_id", "undone", "sequence")),
            Map.entry("idx_fg_job_chunk_overlap",
                    List.of("world_uuid", "chunk_x", "chunk_z", "job_id")));
    private static final Map<String, String> ORDER_BY = Map.of(
            "block_changes", "id",
            "rollback_jobs", "id",
            "rollback_job_changes", "job_id, sequence",
            "rollback_job_chunks", "job_id, world_uuid, chunk_x, chunk_z");

    private static final int BLOB_SLICE_BYTES = 8192;

    private record TableColumn(String name, boolean blob) { }

    record TableDigest(long rows, long blobBytes, String sha256) { }
    record Progress(boolean running, String phase, long recordsScanned, Instant startedAt,
                    String latestResult) { }
    record Report(boolean complete, boolean passed, String phase, int schemaVersion,
                  Instant startedAt, Instant completedAt, long scannedRows,
                  String schemaSha256, Map<String, TableDigest> tables, List<String> issues,
                  Path reportFile, Path manifestFile) {
        Report {
            tables = Map.copyOf(tables);
            issues = List.copyOf(issues);
        }
        List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add("Deep scan: " + (complete ? (passed ? "PASS" : "FAILED CHECKS") : "PARTIAL / UNTRUSTED"));
            out.add("Started: " + startedAt + " | Finished: " + completedAt);
            out.add("Phase: " + phase + " | Schema: v" + schemaVersion + " (expected v"
                    + Database.SCHEMA_VERSION + ")");
            out.add("Rows examined: " + scannedRows + " | Completed tables: " + tables.size()
                    + "/" + TABLES.size());
            for (String table : TABLES) {
                TableDigest item = tables.get(table);
                out.add(table + ": " + (item == null ? "incomplete/not scanned"
                        : item.rows() + " rows, SHA-256 " + item.sha256()));
            }
            for (String issue : issues) out.add("Finding: " + issue);
            out.add("Report: " + reportFile);
            out.add("Manifest: " + manifestFile);
            out.add("Only counts, phase names, status and hashes are saved; no raw player/inventory data.");
            return List.copyOf(out);
        }
    }

    private final Path databaseFile;
    private final Path directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("FragGuard-Deep-Diagnostics"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            daemon("FragGuard-Deep-Diagnostics-Timeout"));
    private final AtomicReference<Work> active = new AtomicReference<>();
    private volatile Progress latest = new Progress(false, "not started", 0L, Instant.EPOCH, "none");
    private volatile boolean closed;

    DatabaseDeepDiagnostics(Path pluginDirectory) {
        Path root = Objects.requireNonNull(pluginDirectory).toAbsolutePath().normalize();
        this.databaseFile = root.resolve("fragguard.db");
        this.directory = root.resolve("diagnostic-reports");
    }

    Progress progress() {
        Work work = active.get();
        return work == null ? latest : work.progress(true);
    }

    CompletableFuture<Report> run(int timeoutSeconds) {
        Work request = new Work();
        if (closed) return CompletableFuture.failedFuture(
                new IllegalStateException("Deep diagnostics have been stopped"));
        if (!active.compareAndSet(null, request)) return CompletableFuture.failedFuture(
                new IllegalStateException("A deep diagnostic scan is already running"));
        if (closed) {
            active.compareAndSet(request, null);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Deep diagnostics have been stopped"));
        }
        int seconds = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, timeoutSeconds));
        try {
            request.task = worker.submit(() -> {
                if (!request.tryStart()) return;
                Report result = null;
                Throwable failure = null;
                try {
                    result = inspect(request);
                } catch (Throwable problem) {
                    failure = problem;
                } finally {
                    // Do not publish a completed response before the SQLite
                    // connection is closed and the active slot is released.
                    latest = request.progress(false);
                    active.compareAndSet(request, null);
                    ScheduledFuture<?> alarm = request.alarm;
                    if (alarm != null) alarm.cancel(false);
                }
                if (failure == null) request.response.complete(result);
                else request.response.completeExceptionally(failure);
            });
            request.alarm = timer.schedule(() -> {
                if (!request.response.isDone()) {
                    request.response.completeExceptionally(new TimeoutException(
                            "Deep diagnostics timed out after " + seconds
                                    + " seconds; any saved scan is PARTIAL and not a verified manifest"));
                    request.cancel("time limit exceeded");
                }
            }, seconds, TimeUnit.SECONDS);
            if (request.response.isDone()) request.alarm.cancel(false);
            request.response.whenComplete((report, error) -> {
                if (request.response.isCancelled()) request.cancel("operator cancelled");
            });
        } catch (RuntimeException exception) {
            request.cancel("worker rejected request");
            active.compareAndSet(request, null);
            request.response.completeExceptionally(exception);
        }
        return request.response;
    }

    boolean cancel() {
        Work request = active.get();
        if (request == null) return false;
        request.cancel("operator cancelled");
        request.response.cancel(true);
        return true;
    }

    private Report inspect(Work request) throws IOException, SQLException {
        Instant started = request.started;
        int schemaVersion = -1;
        String schemaHash = "";
        Map<String, TableDigest> tableResults = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        boolean complete = false;
        String phase = "starting";
        try {
            if (!Files.isRegularFile(databaseFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Active FragGuard database is missing or unsafe");
            }
            String url = "jdbc:sqlite:" + databaseFile.toUri() + "?mode=ro";
            try (Connection connection = DriverManager.getConnection(url)) {
                // query_only and read-only URI are independent write guards.
                try (Statement setup = connection.createStatement()) {
                    setup.execute("PRAGMA query_only = ON");
                    setup.execute("BEGIN");
                }
                request.check();
                phase = "schema";
                request.phase = phase;
                schemaVersion = Math.toIntExact(scalar(connection, "PRAGMA user_version", request));
                if (schemaVersion != Database.SCHEMA_VERSION) {
                    issue(issues, "Schema version differs from supported v" + Database.SCHEMA_VERSION);
                }
                Map<String, String> objects = new LinkedHashMap<>();
                MessageDigest schemaDigest = digest();
                try (Statement statement = connection.createStatement()) {
                    request.statement = statement;
                    try (ResultSet rows = statement.executeQuery(
                            "SELECT type, name, tbl_name, sql FROM sqlite_master "
                                    + "WHERE type IN ('table', 'index') ORDER BY type, name")) {
                        while (rows.next()) {
                            request.check();
                            String type = rows.getString(1);
                            String name = rows.getString(2);
                            String owner = rows.getString(3);
                            String definition = rows.getString(4);
                            objects.put(type + ":" + name, owner);
                            updateText(schemaDigest, type);
                            updateText(schemaDigest, name);
                            updateText(schemaDigest, owner);
                            updateText(schemaDigest, definition);
                        }
                    } finally {
                        request.statement = null;
                    }
                }
                schemaHash = HexFormat.of().formatHex(schemaDigest.digest());
                for (String table : TABLES) {
                    if (!table.equals(objects.get("table:" + table))) {
                        issue(issues, "Missing or invalid table " + table);
                        continue;
                    }
                    List<String> columns = new ArrayList<>();
                    try (Statement statement = connection.createStatement()) {
                        request.statement = statement;
                        try (ResultSet rows = statement.executeQuery("PRAGMA table_info('" + table + "')")) {
                            while (rows.next()) columns.add(rows.getString("name"));
                        } finally {
                            request.statement = null;
                        }
                    }
                    for (String column : REQUIRED_COLUMNS.get(table)) {
                        if (!columns.contains(column)) {
                            issue(issues, "Missing " + table + "." + column);
                        }
                    }
                }
                for (Map.Entry<String, String> expected : REQUIRED_INDEXES.entrySet()) {
                    String index = expected.getKey();
                    if (!expected.getValue().equals(objects.get("index:" + index))) {
                        issue(issues, "Missing or wrongly owned index " + index);
                        continue;
                    }
                    List<String> fields = new ArrayList<>();
                    try (Statement statement = connection.createStatement()) {
                        request.statement = statement;
                        try (ResultSet rows = statement.executeQuery(
                                "PRAGMA index_info('" + index + "')")) {
                            while (rows.next()) fields.add(rows.getString("name"));
                        } finally {
                            request.statement = null;
                        }
                    }
                    if (!INDEX_COLUMNS.get(index).equals(fields)) {
                        issue(issues, "Index has unexpected key fields: " + index);
                    }
                }
                for (String dependent : List.of("rollback_job_changes", "rollback_job_chunks")) {
                    int recognized = 0;
                    try (Statement statement = connection.createStatement()) {
                        request.statement = statement;
                        try (ResultSet rows = statement.executeQuery(
                                "PRAGMA foreign_key_list('" + dependent + "')")) {
                            while (rows.next()) {
                                if ("rollback_jobs".equals(rows.getString("table"))
                                        && "job_id".equals(rows.getString("from"))
                                        && "id".equals(rows.getString("to"))) recognized++;
                            }
                        } finally {
                            request.statement = null;
                        }
                    }
                    if (recognized == 0) issue(issues, "Missing rollback parent FK in " + dependent);
                }

                request.check();
                phase = "integrity_check";
                request.phase = phase;
                try (Statement statement = connection.createStatement()) {
                    request.statement = statement;
                    try (ResultSet rows = statement.executeQuery("PRAGMA integrity_check(100)")) {
                        int count = 0;
                        while (rows.next()) {
                            request.check();
                            String outcome = rows.getString(1);
                            if (!"ok".equalsIgnoreCase(outcome)) {
                                issue(issues, "SQLite integrity_check detected corruption");
                                count++;
                            }
                        }
                        if (count > 0) issue(issues, "SQLite integrity errors present (details suppressed)");
                    } finally {
                        request.statement = null;
                    }
                }

                request.check();
                phase = "foreign_key_check";
                request.phase = phase;
                int foreignFailures = 0;
                try (Statement statement = connection.createStatement()) {
                    request.statement = statement;
                    try (ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                        while (rows.next()) {
                            request.check();
                            foreignFailures++;
                        }
                    } finally {
                        request.statement = null;
                    }
                }
                if (foreignFailures > 0) {
                    issue(issues, "Orphan rollback records / foreign-key violations: "
                            + foreignFailures);
                }
                // Also detect orphan rows if a tampered table was rebuilt
                // without declaring its foreign key.
                for (String child : List.of("rollback_job_changes", "rollback_job_chunks")) {
                    if (!child.equals(objects.get("table:" + child))
                            || !"rollback_jobs".equals(objects.get("table:rollback_jobs"))) {
                        continue;
                    }
                    long orphans = scalar(connection, "SELECT COUNT(*) FROM " + child
                            + " AS c LEFT JOIN rollback_jobs AS j ON j.id = c.job_id "
                            + "WHERE j.id IS NULL", request);
                    if (orphans > 0) issue(issues, "Orphan records in " + child + ": " + orphans);
                }

                for (String table : TABLES) {
                    request.check();
                    if (!table.equals(objects.get("table:" + table))) {
                        continue; // Explicit failed/partial manifest, not an invented row count.
                    }
                    // Inspect the actual columns once. Never SELECT the complete
                    // contents of a BLOB into the JDBC ResultSet: Xerial's
                    // getBinaryStream() calls getBytes() and allocates the
                    // entire value on the Java heap.
                    List<TableColumn> columns = new ArrayList<>();
                    try (Statement columnStatement = connection.createStatement()) {
                        request.statement = columnStatement;
                        try (ResultSet definitions = columnStatement.executeQuery(
                                "PRAGMA table_info('" + table + "')")) {
                            while (definitions.next()) {
                                request.check();
                                String name = definitions.getString("name");
                                String type = definitions.getString("type");
                                columns.add(new TableColumn(name, type != null
                                        && type.toUpperCase(Locale.ROOT).contains("BLOB")));
                            }
                        } finally {
                            request.statement = null;
                        }
                    }
                    boolean hasColumns = true;
                    for (String required : REQUIRED_COLUMNS.get(table)) {
                        if (columns.stream().noneMatch(column -> column.name().equals(required))) {
                            hasColumns = false;
                        }
                    }
                    if (!hasColumns) continue;

                    phase = "scanning " + table;
                    request.phase = phase;
                    MessageDigest tableHash = digest();
                    long records = 0L;
                    long blobBytes = 0L;
                    // Only scalar fields and BLOB byte LENGTHS are projected
                    // in the main cursor. Every BLOB is fetched separately via
                    // SQLite substr() with a hard 8-KiB result cap.
                    StringBuilder scanSql = new StringBuilder("SELECT rowid AS __fg_diag_rowid");
                    for (TableColumn column : columns) {
                        String name = quoteIdentifier(column.name());
                        scanSql.append(", ");
                        if (column.blob()) {
                            scanSql.append("length(").append(name).append(") AS ").append(name);
                        } else {
                            scanSql.append(name);
                        }
                    }
                    scanSql.append(" FROM ").append(table)
                            .append(" ORDER BY ").append(ORDER_BY.get(table));
                    List<PreparedStatement> blobReaders = new ArrayList<>();
                    try {
                        for (TableColumn column : columns) {
                            blobReaders.add(column.blob()
                                    ? connection.prepareStatement("SELECT substr("
                                            + quoteIdentifier(column.name()) + ", ?, ?) FROM "
                                            + table + " WHERE rowid = ?")
                                    : null);
                        }
                        try (Statement statement = connection.createStatement()) {
                            request.statement = statement;
                            try (ResultSet rows = statement.executeQuery(scanSql.toString())) {
                                for (TableColumn column : columns) {
                                    updateText(tableHash, column.name());
                                }
                                while (rows.next()) {
                                    request.check();
                                    long rowId = rows.getLong(1);
                                    tableHash.update((byte) 0x7e); // row boundary
                                    for (int col = 0; col < columns.size(); col++) {
                                        TableColumn column = columns.get(col);
                                        if (column.blob()) {
                                            long byteLength = rows.getLong(col + 2);
                                            if (rows.wasNull()) {
                                                tableHash.update((byte) 0);
                                            } else {
                                                if (byteLength < 0) {
                                                    throw new SQLException("Invalid BLOB length");
                                                }
                                                MessageDigest cellHash = digest();
                                                hashBlobSlices(blobReaders.get(col), statement,
                                                        rowId, byteLength, cellHash, request);
                                                blobBytes = Math.addExact(blobBytes, byteLength);
                                                tableHash.update((byte) 2);
                                                updateLong(tableHash, byteLength);
                                                tableHash.update(cellHash.digest());
                                            }
                                        } else {
                                            updateText(tableHash, rows.getString(col + 2));
                                        }
                                    }
                                if ("block_changes".equals(table)) {
                                    long x = rows.getLong("x");
                                    long z = rows.getLong("z");
                                    if (rows.getLong("chunk_x") != Math.floorDiv(x, 16L)
                                            || rows.getLong("chunk_z") != Math.floorDiv(z, 16L)) {
                                        issue(issues, "History chunk coordinates disagree with block coordinates");
                                    }
                                    if (rows.getLong("id") <= 0
                                            || rows.getLong("happened_at") <= 0
                                            || rows.getString("world_uuid").isBlank()) {
                                        issue(issues, "Invalid block history identity or timestamp");
                                    }
                                } else if ("rollback_jobs".equals(table)) {
                                    if (rows.getLong("id") <= 0 || rows.getLong("total_blocks") < 0
                                            || rows.getLong("processed_blocks") < 0
                                            || rows.getLong("applied_blocks") < 0
                                            || rows.getLong("processed_blocks") > rows.getLong("total_blocks")
                                            || rows.getLong("applied_blocks") > rows.getLong("total_blocks")) {
                                        issue(issues, "Invalid rollback job counters or identity");
                                    }
                                } else if ("rollback_job_changes".equals(table)) {
                                    if (rows.getLong("job_id") <= 0 || rows.getLong("sequence") < 0
                                            || (rows.getInt("applied") != 0 && rows.getInt("processed") == 0)) {
                                        // A saved undo can legitimately set undone=1 with
                                        // applied=0: resumed, non-conflicting no-op rollbacks
                                        // keep before_data and are still eligible for undo.
                                        issue(issues, "Invalid rollback change state or identity");
                                    }
                                } else if (rows.getLong("job_id") <= 0) {
                                    issue(issues, "Invalid rollback chunk owner");
                                }
                                    records++;
                                    request.rows++;
                                    if ((records & 2047L) == 0) {
                                        request.phase = phase + " (" + records + " rows)";
                                    }
                                }
                            } finally {
                                request.statement = null;
                            }
                        }
                    } finally {
                        for (PreparedStatement blobReader : blobReaders) {
                            if (blobReader != null) blobReader.close();
                        }
                    }
                    tableResults.put(table, new TableDigest(records, blobBytes,
                            HexFormat.of().formatHex(tableHash.digest())));
                }

                complete = tableResults.size() == TABLES.size();
                if (!complete) issue(issues, "Required tables/columns prevented complete validation");
                phase = "completed";
            }
        } catch (SQLException | IOException | ArithmeticException problem) {
            // No message from SQLite or any row value is written to reports:
            // JDBC errors may include sensitive values in exceptional SQL text.
            issue(issues, request.cancelled ? "Deep scan interrupted or timed out"
                    : "Deep scan failed while reading SQLite");
            phase = request.cancelled ? "cancelled / timed out" : "failed / incomplete";
        } finally {
            // The SQLite connection and read transaction are already closed.
            request.phase = "writing redacted report";
        }
        // A cancelled worker's interrupt flag must not prevent it from
        // writing a clearly marked PARTIAL report after closing SQLite.
        if (request.cancelled) Thread.interrupted();
        Report report = persist(new Report(complete && !request.cancelled,
                complete && issues.isEmpty() && !request.cancelled,
                phase, schemaVersion, started, Instant.now(), request.rows, schemaHash,
                tableResults, issues, null, null));
        request.phase = report.complete ? "finished" : "partial";
        request.latestResult = report.reportFile.toString();
        return report;
    }

    private Report persist(Report unfinished) throws IOException {
        Path root = directory.getParent();
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(directory)) {
            throw new IOException("Diagnostics report folder must not be a symbolic link");
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Diagnostics report directory is unsafe");
        }
        // A server-created UUID is the ONLY filename input. Never use a name
        // supplied by players or the database itself for a report filename.
        String id = UUID.randomUUID().toString();
        Path reportFile = directory.resolve("fg-deep-" + id + ".txt");
        Path manifestFile = directory.resolve("fg-deep-" + id + ".manifest");
        Report report = new Report(unfinished.complete, unfinished.passed,
                unfinished.phase, unfinished.schemaVersion, unfinished.startedAt,
                unfinished.completedAt, unfinished.scannedRows, unfinished.schemaSha256,
                unfinished.tables, unfinished.issues, reportFile, manifestFile);
        StringBuilder manifest = new StringBuilder();
        manifest.append("format=fragguard-deep-1\n")
                .append("complete=").append(report.complete).append('\n')
                .append("passed=").append(report.passed).append('\n')
                .append("schema_version=").append(report.schemaVersion).append('\n')
                .append("schema_sha256=").append(report.schemaSha256).append('\n')
                .append("started_at=").append(report.startedAt).append('\n')
                .append("completed_at=").append(report.completedAt).append('\n')
                .append("scanned_rows=").append(report.scannedRows).append('\n')
                .append("issue_count=").append(report.issues.size()).append('\n');
        for (String table : TABLES) {
            TableDigest info = report.tables.get(table);
            if (info == null) continue;
            manifest.append(table).append(".rows=").append(info.rows).append('\n')
                    .append(table).append(".blob_bytes=").append(info.blobBytes).append('\n')
                    .append(table).append(".sha256=").append(info.sha256).append('\n');
        }
        // Reports contain only fixed schema names, statuses, numerical counts
        // and digests. No sample row values or raw SQL error details.
        String text = String.join("\n", report.lines()) + "\n";
        Files.writeString(reportFile, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.writeString(manifestFile, manifest, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return report;
    }

    /** Quote schema-derived column identifiers; never interpolate player data. */
    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /**
     * JDBC getBinaryStream() in the pinned sqlite-jdbc driver materializes the
     * whole BLOB using getBytes(). Fetch SQL substr() slices instead. Every
     * JDBC byte array is at most BLOB_SLICE_BYTES, including for damaged or
     * unusually large snapshots; zero-length BLOBs remain distinct from NULL.
     */
    private static void hashBlobSlices(PreparedStatement slices, Statement scanStatement,
                                       long rowId, long byteLength, MessageDigest hash,
                                       Work request) throws SQLException {
        long consumed = 0;
        while (consumed < byteLength) {
            request.check();
            int chunkSize = (int) Math.min((long) BLOB_SLICE_BYTES, byteLength - consumed);
            slices.setLong(1, consumed + 1); // SQLite substr() indexes from 1
            slices.setInt(2, chunkSize);
            slices.setLong(3, rowId);
            request.statement = slices;
            try (ResultSet result = slices.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("A history BLOB disappeared during validation");
                }
                byte[] chunk = result.getBytes(1);
                if (chunk == null || chunk.length != chunkSize) {
                    throw new SQLException("A history BLOB could not be read completely");
                }
                hash.update(chunk);
                consumed += chunk.length;
            } finally {
                request.statement = scanStatement;
            }
        }
    }

    private static long scalar(Connection connection, String sql, Work request) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            request.statement = statement;
            try (ResultSet rows = statement.executeQuery(sql)) {
                if (!rows.next()) throw new SQLException("Diagnostic pragma returned no value");
                return rows.getLong(1);
            } finally {
                request.statement = null;
            }
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", exception);
        }
    }

    private static void updateLong(MessageDigest hash, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            hash.update((byte) (value >>> shift));
        }
    }

    private static void updateText(MessageDigest hash, String text) {
        if (text == null) {
            hash.update((byte) 0);
            return;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        hash.update((byte) 1);
        hash.update((byte) (bytes.length >>> 24));
        hash.update((byte) (bytes.length >>> 16));
        hash.update((byte) (bytes.length >>> 8));
        hash.update((byte) bytes.length);
        hash.update(bytes);
    }

    private static void issue(List<String> found, String code) {
        if (found.size() < MAX_ISSUES && !found.contains(code)) found.add(code);
    }

    private static ThreadFactory daemon(String name) {
        return action -> {
            Thread thread = new Thread(action, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override public void close() {
        closed = true;
        Work existing = active.get();
        if (existing != null) {
            existing.cancel("server shutdown");
            existing.response.cancel(true);
        }
        timer.shutdownNow();
        worker.shutdownNow();
        try {
            worker.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Work {
        final CompletableFuture<Report> response = new CompletableFuture<>();
        final Instant started = Instant.now();
        final Object lock = new Object();
        volatile String phase = "queued";
        volatile long rows;
        volatile String latestResult = "none";
        volatile Statement statement;
        volatile Future<?> task;
        volatile ScheduledFuture<?> alarm;
        volatile boolean cancelled;
        private boolean startedWork;

        boolean tryStart() {
            synchronized (lock) {
                if (cancelled) return false;
                startedWork = true;
                return true;
            }
        }

        Progress progress(boolean running) {
            return new Progress(running, phase, rows, started, latestResult);
        }

        void check() throws SQLException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new SQLException("Deep scan cancelled or interrupted");
            }
        }

        void cancel(String why) {
            boolean notStarted;
            synchronized (lock) {
                cancelled = true;
                phase = why;
                notStarted = !startedWork;
            }
            if (notStarted) {
                active.compareAndSet(this, null);
                latest = progress(false);
            }
            ScheduledFuture<?> watchdog = alarm;
            if (watchdog != null) watchdog.cancel(false);
            Statement ongoing = statement;
            if (ongoing != null) {
                try { ongoing.cancel(); } catch (SQLException ignored) { }
            }
            Future<?> assigned = task;
            if (assigned != null) assigned.cancel(true);
        }
    }
}
