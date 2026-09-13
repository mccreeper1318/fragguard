from pathlib import Path


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


db_path = Path("src/main/java/org/pinnaclesmp/fragguard/Database.java")
db = db_path.read_text()

db = replace_once(
    db,
    "import java.util.List;\nimport java.util.Map;\n",
    "import java.util.LinkedHashMap;\nimport java.util.List;\nimport java.util.Map;\n",
    "LinkedHashMap import",
)

db = replace_once(
    db,
    "    private static final long WARNING_INTERVAL_MILLIS = 10_000L;\n",
    "    private static final long WARNING_INTERVAL_MILLIS = 10_000L;\n"
    "    private static final int MAX_PERSISTED_COALESCE_CANDIDATES = 4_096;\n"
    "    private static final long PERSISTED_COALESCE_TICK_RETENTION = 2L;\n",
    "coalesce cache constants",
)

db = replace_once(
    db,
    "    private final Map<CoalesceKey, PendingBlockChange> coalescedChanges = new HashMap<>();\n",
    "    private final Map<CoalesceKey, PendingBlockChange> coalescedChanges = new HashMap<>();\n"
    "    private final LinkedHashMap<CoalesceKey, PersistedCoalesceCandidate> persistedCoalesceCandidates =\n"
    "            new LinkedHashMap<>();\n",
    "persisted candidate map",
)

db = replace_once(
    db,
    "    private final int writeCapacity;\n    private final int operationCapacity;\n",
    "    private final int writeCapacity;\n    private final int persistedCoalesceCapacity;\n    private final int operationCapacity;\n",
    "persisted candidate capacity field",
)

db = replace_once(
    db,
    "        this.writeCapacity = Math.max(64, plugin.getConfig().getInt(\"database-write-queue-capacity\", 20_000));\n"
    "        this.operationCapacity = Math.max(8, plugin.getConfig().getInt(\"database-operation-queue-capacity\", 256));\n",
    "        this.writeCapacity = Math.max(64, plugin.getConfig().getInt(\"database-write-queue-capacity\", 20_000));\n"
    "        this.persistedCoalesceCapacity = Math.max(64, Math.min(writeCapacity, MAX_PERSISTED_COALESCE_CANDIDATES));\n"
    "        this.operationCapacity = Math.max(8, plugin.getConfig().getInt(\"database-operation-queue-capacity\", 256));\n",
    "persisted candidate capacity initialization",
)

db = replace_once(
    db,
    "        } finally {\n            running = false;\n            connection = null;\n            workerStopped = true;\n        }\n",
    "        } finally {\n            persistedCoalesceCandidates.clear();\n            running = false;\n            connection = null;\n            workerStopped = true;\n        }\n",
    "worker cleanup",
)

db = replace_once(
    db,
    "            plugin.getLogger().info(\"FragGuard schema v3->v4 is index-only; skipping a full database copy and \"\n"
    "                    + \"using the storage-aware coalescing-index migration.\");\n",
    "            plugin.getLogger().info(\"FragGuard schema v3->v4 only removes the obsolete persistent coalescing \"\n"
    "                    + \"index; skipping a full database copy.\");\n",
    "v3 migration log",
)

db = replace_once(
    db,
    "        plugin.getLogger().info(\"FragGuard migration storage preflight passed for \" + step + \": available=\"\n"
    "                + MigrationStoragePolicy.formatBytes(usable) + \", estimated minimum free=\"\n"
    "                + MigrationStoragePolicy.formatBytes(required) + \".\");\n",
    "        plugin.getLogger().info(\"FragGuard migration storage preflight passed for \" + step\n"
    "                + \": filesystem-reported available=\" + MigrationStoragePolicy.formatBytes(usable)\n"
    "                + \", estimated minimum free=\" + MigrationStoragePolicy.formatBytes(required)\n"
    "                + \". Managed-host account quotas can be lower than the filesystem value.\");\n",
    "preflight success wording",
)

db = replace_once(
    db,
    "            throw new SQLException(\"Cannot safely begin \" + step + \": database=\"\n"
    "                    + MigrationStoragePolicy.formatBytes(databaseFile.length()) + \", available=\"\n"
    "                    + MigrationStoragePolicy.formatBytes(usable) + \", estimated minimum free=\"\n",
    "            throw new SQLException(\"Cannot safely begin \" + step + \": database=\"\n"
    "                    + MigrationStoragePolicy.formatBytes(databaseFile.length()) + \", filesystem-reported available=\"\n"
    "                    + MigrationStoragePolicy.formatBytes(usable) + \", estimated minimum free=\"\n",
    "preflight failure wording",
)

index_create = '''            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_fg_tick_coalesce
                    ON block_changes(world_uuid, x, y, z, coalesce_session, server_tick)
                    """);
'''
db = replace_once(db, index_create, "", "new schema persistent coalesce index")

migration_start = db.index("    private void migrateAttributionCoalescingSchema(Connection databaseConnection) throws SQLException {")
migration_end = db.index("    private void migrateWorldIdentities", migration_start)
new_migration = '''    private void migrateAttributionCoalescingSchema(Connection databaseConnection) throws SQLException {
        startupStage = "removing obsolete coalescing index for schema v3 to v4";
        verifyOpenDatabase(databaseConnection, "before the index-removal v3->v4 migration");
        inTransaction(databaseConnection, () -> {
            try (Statement statement = databaseConnection.createStatement()) {
                statement.executeUpdate("DROP INDEX IF EXISTS idx_fg_tick_coalesce");
            }
            verifyOpenDatabase(databaseConnection, "after removing the obsolete schema-v3 coalescing index");
            try (Statement statement = databaseConnection.createStatement()) {
                statement.execute("PRAGMA user_version=4");
            }
            return null;
        });
    }

'''
db = db[:migration_start] + new_migration + db[migration_end:]

transaction_start = db.index("        try {\n            long persistedCoalesces = inTransaction(connection, () -> {")
transaction_end = db.index("            synchronized (coalescedChanges) {\n                completedWrites.addAndGet(batch.size());", transaction_start)
new_transaction = '''        try {
            Map<CoalesceKey, PersistedCoalesceCandidate> candidateChanges = new HashMap<>();
            long persistedCoalesces = inTransaction(connection, () -> {
                long coalesces = 0L;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE block_changes
                        SET after_data = ?, after_entity_data = ?
                        WHERE id = ?
                          AND actor_uuid = ?
                          AND actor_name = ?
                          AND action = ?
                          AND after_data = ?
                          AND ((after_entity_data IS NULL AND ? IS NULL) OR after_entity_data = ?)
                        """);
                     PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO block_changes
                        (happened_at, actor_uuid, actor_name, world, x, y, z, action,
                         before_data, after_data, world_uuid, chunk_x, chunk_z, coalesce_session, server_tick,
                         before_entity_data, after_entity_data)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement deleteNoOp = connection.prepareStatement("""
                        DELETE FROM block_changes
                        WHERE id = ?
                          AND before_data = after_data
                          AND before_entity_data IS after_entity_data
                        """)) {
                    for (PendingBlockChange pending : batch) {
                        BlockChange change = pending.change;
                        if (sameState(change.beforeData(), change.beforeEntityData(),
                                change.afterData(), change.afterEntityData())) {
                            continue;
                        }

                        PersistedCoalesceCandidate candidate = candidateChanges.containsKey(pending.key)
                                ? candidateChanges.get(pending.key)
                                : persistedCoalesceCandidates.get(pending.key);
                        if (candidate != null && canCoalesce(candidate, change)) {
                            update.setString(1, change.afterData());
                            update.setBytes(2, change.afterEntityData());
                            update.setLong(3, candidate.rowId());
                            update.setString(4, candidate.actorUuid());
                            update.setString(5, candidate.actorName());
                            update.setString(6, candidate.action().storageId());
                            update.setString(7, candidate.afterData());
                            update.setBytes(8, candidate.afterEntityData());
                            update.setBytes(9, candidate.afterEntityData());
                            if (update.executeUpdate() == 1) {
                                coalesces++;
                                deleteNoOp.setLong(1, candidate.rowId());
                                if (deleteNoOp.executeUpdate() == 1) {
                                    candidateChanges.put(pending.key, null);
                                } else {
                                    candidateChanges.put(pending.key,
                                            persistedCandidate(candidate.rowId(), change));
                                }
                                continue;
                            }
                        }

                        insert.setLong(1, change.happenedAt());
                        insert.setString(2, change.actorUuid());
                        insert.setString(3, change.actorName());
                        insert.setString(4, change.worldName());
                        insert.setInt(5, change.x());
                        insert.setInt(6, change.y());
                        insert.setInt(7, change.z());
                        insert.setString(8, change.action().storageId());
                        insert.setString(9, change.beforeData());
                        insert.setString(10, change.afterData());
                        insert.setString(11, pending.worldUuid);
                        insert.setInt(12, change.x() >> 4);
                        insert.setInt(13, change.z() >> 4);
                        insert.setString(14, coalesceSession);
                        insert.setLong(15, pending.key.tick());
                        insert.setBytes(16, change.beforeEntityData());
                        insert.setBytes(17, change.afterEntityData());
                        insert.executeUpdate();
                        try (ResultSet keys = insert.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new SQLException("SQLite did not return a block-change row ID for coalescing.");
                            }
                            candidateChanges.put(pending.key,
                                    persistedCandidate(keys.getLong(1), change));
                        }
                    }
                }
                return coalesces;
            });
            applyPersistedCoalesceCandidateChanges(candidateChanges);
'''
db = db[:transaction_start] + new_transaction + db[transaction_end:]

merge_marker = '''    private static boolean canCoalesce(BlockChange previous, BlockChange latest) {
        return Objects.equals(previous.actorUuid(), latest.actorUuid())
                && Objects.equals(previous.actorName(), latest.actorName())
                && previous.action() == latest.action()
                && sameState(previous.afterData(), previous.afterEntityData(),
                        latest.beforeData(), latest.beforeEntityData());
    }

'''
new_helpers = merge_marker + '''    private static boolean canCoalesce(PersistedCoalesceCandidate previous, BlockChange latest) {
        return Objects.equals(previous.actorUuid(), latest.actorUuid())
                && Objects.equals(previous.actorName(), latest.actorName())
                && previous.action() == latest.action()
                && sameState(previous.afterData(), previous.afterEntityData(),
                        latest.beforeData(), latest.beforeEntityData());
    }

    private static PersistedCoalesceCandidate persistedCandidate(long rowId, BlockChange change) {
        byte[] entityData = change.afterEntityData() == null
                ? null
                : Arrays.copyOf(change.afterEntityData(), change.afterEntityData().length);
        return new PersistedCoalesceCandidate(rowId, change.actorUuid(), change.actorName(), change.action(),
                change.afterData(), entityData);
    }

    private void applyPersistedCoalesceCandidateChanges(
            Map<CoalesceKey, PersistedCoalesceCandidate> candidateChanges
    ) {
        for (Map.Entry<CoalesceKey, PersistedCoalesceCandidate> entry : candidateChanges.entrySet()) {
            persistedCoalesceCandidates.remove(entry.getKey());
            if (entry.getValue() != null) {
                persistedCoalesceCandidates.put(entry.getKey(), entry.getValue());
            }
        }
        if (persistedCoalesceCandidates.isEmpty()) {
            return;
        }

        long newestTick = persistedCoalesceCandidates.keySet().stream()
                .mapToLong(CoalesceKey::tick)
                .max()
                .orElse(Long.MIN_VALUE);
        long cutoff = newestTick <= Long.MIN_VALUE + PERSISTED_COALESCE_TICK_RETENTION
                ? Long.MIN_VALUE
                : newestTick - PERSISTED_COALESCE_TICK_RETENTION;
        persistedCoalesceCandidates.entrySet().removeIf(entry -> entry.getKey().tick() < cutoff);

        while (persistedCoalesceCandidates.size() > persistedCoalesceCapacity) {
            var iterator = persistedCoalesceCandidates.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

'''
db = replace_once(db, merge_marker, new_helpers, "persisted coalesce helpers")

record_marker = '''    private record RequiredBlockChange(String worldUuid, BlockChange change) {
    }
'''
record_replacement = '''    private record PersistedCoalesceCandidate(
            long rowId,
            String actorUuid,
            String actorName,
            ChangeAction action,
            String afterData,
            byte[] afterEntityData
    ) {
    }

''' + record_marker
db = replace_once(db, record_marker, record_replacement, "persisted candidate record")

db_path.write_text(db)

# Simplify the migration storage policy now that v3->v4 allocates no replacement index.
policy_path = Path("src/main/java/org/pinnaclesmp/fragguard/MigrationStoragePolicy.java")
policy = policy_path.read_text()
policy = policy.replace("    private static final long MIN_INDEX_WORK_BYTES = 64L * MIB;\n", "")
policy = policy.replace("    private static final long FALLBACK_INDEX_WORK_BYTES = 256L * MIB;\n", "")
index_method = '''    static long estimateIndexWorkingBytes(long databaseBytes, long existingIndexBytes) {
        if (existingIndexBytes > 0L) {
            return Math.max(existingIndexBytes, MIN_INDEX_WORK_BYTES);
        }
        return Math.max(databaseBytes / 2L, FALLBACK_INDEX_WORK_BYTES);
    }

'''
policy = replace_once(policy, index_method, "", "obsolete index working-space estimate")
policy_path.write_text(policy)

# Update database regression tests for an index-free schema v4.
test_path = Path("src/test/java/org/pinnaclesmp/fragguard/DatabaseTest.java")
tests = test_path.read_text()
old_migration_test = '''    @Test
    void migratesVersionThreeTickCoalescingIndexToOrderedNonUniqueHistory() throws Exception {
        database = startDatabase();
        database.shutdown();
        database = null;

        try (Connection connection = openDatabase(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS idx_fg_tick_coalesce");
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX idx_fg_tick_coalesce
                    ON block_changes(world_uuid, x, y, z, coalesce_session, server_tick)
                    """);
            statement.execute("PRAGMA user_version=3");
        }

        database = startDatabase();
        assertFalse(Files.exists(temporaryDirectory.resolve("backups")),
                "index-only v3->v4 migration must not duplicate the full history database");
        try (Connection connection = openDatabase(); Statement statement = connection.createStatement()) {
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(version.next());
                assertEquals(4, version.getInt(1));
            }
            try (ResultSet indexes = statement.executeQuery("PRAGMA index_list(block_changes)")) {
                boolean found = false;
                while (indexes.next()) {
                    if ("idx_fg_tick_coalesce".equals(indexes.getString("name"))) {
                        found = true;
                        assertEquals(0, indexes.getInt("unique"),
                                "schema v4 must replace the old one-row-per-coordinate/tick uniqueness rule");
                    }
                }
                assertTrue(found);
            }
            List<String> columns = new java.util.ArrayList<>();
            try (ResultSet indexColumns = statement.executeQuery("PRAGMA index_info(idx_fg_tick_coalesce)")) {
                while (indexColumns.next()) {
                    columns.add(indexColumns.getString("name"));
                }
            }
            assertEquals(List.of("world_uuid", "x", "y", "z", "coalesce_session", "server_tick"), columns,
                    "the row id is already implicit in an ordinary SQLite index and must not be stored twice");
        }
    }
'''
new_migration_test = '''    @Test
    void migratesVersionThreeByRemovingObsoletePersistentCoalescingIndex() throws Exception {
        database = startDatabase();
        database.shutdown();
        database = null;

        try (Connection connection = openDatabase(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX idx_fg_tick_coalesce
                    ON block_changes(world_uuid, x, y, z, coalesce_session, server_tick)
                    """);
            statement.execute("PRAGMA user_version=3");
        }

        database = startDatabase();
        assertFalse(Files.exists(temporaryDirectory.resolve("backups")),
                "index-only v3->v4 migration must not duplicate the full history database");
        try (Connection connection = openDatabase(); Statement statement = connection.createStatement()) {
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(version.next());
                assertEquals(4, version.getInt(1));
            }
            try (ResultSet indexes = statement.executeQuery("""
                    SELECT 1 FROM sqlite_master
                    WHERE type = 'index' AND name = 'idx_fg_tick_coalesce'
                    """)) {
                assertFalse(indexes.next(),
                        "schema v4 must not retain or rebuild the persistent same-tick coalescing index");
            }
        }
    }
'''
tests = replace_once(tests, old_migration_test, new_migration_test, "v3 migration regression test")

old_retry_tail = '''        try (Connection connection = openDatabase(); Statement statement = connection.createStatement();
             ResultSet version = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(4, version.getInt(1));
        }
    }
'''
new_retry_tail = '''        try (Connection connection = openDatabase(); Statement statement = connection.createStatement()) {
            try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(version.next());
                assertEquals(4, version.getInt(1));
            }
            try (ResultSet indexes = statement.executeQuery("""
                    SELECT 1 FROM sqlite_master
                    WHERE type = 'index' AND name = 'idx_fg_tick_coalesce'
                    """)) {
                assertFalse(indexes.next(),
                        "the exact interrupted production state must upgrade without recreating the large index");
            }
        }
    }
'''
retry_anchor = tests.index("    void retriesVersionThreeMigrationWhenOldCoalescingIndexWasAlreadyDropped()")
retry_end = tests.index("    @Test\n    void refusesToOpenNewerDatabaseSchemasWithoutChangingThem", retry_anchor)
retry_section = tests[retry_anchor:retry_end]
if old_retry_tail not in retry_section:
    raise SystemExit("retry migration test tail not found")
retry_section = retry_section.replace(old_retry_tail, new_retry_tail, 1)
tests = tests[:retry_anchor] + retry_section + tests[retry_end:]

coalesce_anchor = '''        assertEquals(1, database.health().coalescedWrites(),
                "SQLite cross-flush upserts must contribute to /fg status coalesce metrics");
    }
'''
coalesce_replacement = '''        assertEquals(1, database.health().coalescedWrites(),
                "cross-flush row-id coalescing must contribute to /fg status coalesce metrics");
        try (Connection connection = openDatabase(); Statement statement = connection.createStatement();
             ResultSet index = statement.executeQuery("""
                     SELECT 1 FROM sqlite_master
                     WHERE type = 'index' AND name = 'idx_fg_tick_coalesce'
                     """)) {
            assertFalse(index.next(),
                    "cross-flush coalescing must work without a persistent history index");
        }
    }
'''
tests = replace_once(tests, coalesce_anchor, coalesce_replacement, "cross-flush index-free assertion")

intervening_marker = '''    @Test
    void preservesDifferentActionsAcrossSeparateDatabaseFlushes() throws Exception {
'''
intervening_test = '''    @Test
    void preservesInterveningActorAcrossSeparateFlushesWithRowIdTracking() throws Exception {
        database = startDatabase();
        long timestamp = System.currentTimeMillis();
        UUID otherActor = UUID.fromString("97cebc5c-570f-40af-9719-a0dc24670c52");

        database.insertAsync(new BlockChange(timestamp, 913L, ACTOR_UUID.toString(), "Builder", "world",
                13, 64, 13, ChangeAction.BREAK, "minecraft:stone", "minecraft:dirt"));
        database.lookupAsync("world", 13, 13, 1, 1, 15, 30).join();

        database.insertAsync(new BlockChange(timestamp + 1L, 913L, otherActor.toString(), "OtherBuilder", "world",
                13, 64, 13, ChangeAction.BREAK, "minecraft:dirt", "minecraft:grass_block"));
        database.lookupAsync("world", 13, 13, 1, 1, 15, 30).join();

        database.insertAsync(new BlockChange(timestamp + 2L, 913L, ACTOR_UUID.toString(), "Builder", "world",
                13, 64, 13, ChangeAction.BREAK, "minecraft:grass_block", "minecraft:gold_block"));
        LookupPage page = database.lookupAsync("world", 13, 13, 1, 1, 15, 30).join();

        assertEquals(3, page.totalRows(),
                "row-id tracking must follow the latest persisted row and never skip an intervening actor");
        assertEquals(0, database.health().coalescedWrites());
    }

'''
tests = replace_once(tests, intervening_marker, intervening_test + intervening_marker,
                     "intervening cross-flush actor test")
test_path.write_text(tests)

# Replace obsolete migration policy tests.
policy_test_path = Path("src/test/java/org/pinnaclesmp/fragguard/MigrationStoragePolicyTest.java")
policy_tests = '''package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationStoragePolicyTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void onlyDataChangingLegacyMigrationsRequireFullDatabaseBackup() {
        assertTrue(MigrationStoragePolicy.requiresFullBackup(0));
        assertTrue(MigrationStoragePolicy.requiresFullBackup(1));
        assertTrue(MigrationStoragePolicy.requiresFullBackup(2));
        assertFalse(MigrationStoragePolicy.requiresFullBackup(3));
    }

    @Test
    void fullBackupMigrationEstimateStillIncludesDatabaseCopyAndWorkingSpace() {
        long database = 6L * GIB;
        long required = MigrationStoragePolicy.requiredFreeBytes(
                MigrationStoragePolicy.estimateFullMigrationWorkingBytes(database), 256L);
        assertTrue(required > database,
                "data-changing migrations must still reserve more than one database copy of free space");
    }
}
'''
policy_test_path.write_text(policy_tests)

# Config wording: filesystem free space is not necessarily a managed-host quota.
config_path = Path("src/main/resources/config.yml")
config = config_path.read_text()
config = replace_once(
    config,
    '''# Migration preflight is a safety net after FragGuard chooses the lowest-space safe migration strategy.
# Disable only when your hosting platform reports Java filesystem free space inaccurately.
database-migration-space-preflight-enabled: true
''',
    '''# Full-backup migrations use Java filesystem free space as a safety signal.
# Managed hosts can enforce a smaller account quota that Java cannot see, so this value is not treated as a quota.
# Disable only when the host filesystem value itself is unusable; SQLite errors still abort safely.
database-migration-space-preflight-enabled: true
''',
    "config migration preflight wording",
)
config_path.write_text(config)

# README migration docs.
readme_path = Path("README.md")
readme = readme_path.read_text()
old_readme = '''The schema-v3 to schema-v4 migration is different: it only replaces the derived same-tick coalescing index. It therefore **does not duplicate the full history database**. FragGuard verifies the live database first, commits removal of the old derived index so those database pages can be reused, checkpoints the migration WAL, then transactionally builds the replacement non-unique index and advances `PRAGMA user_version` only after `PRAGMA quick_check` succeeds. If that rebuild is interrupted or runs out of storage, the history rows remain schema v3 and the derived index can be rebuilt on the next startup.

The v4 coalescing index also relies on SQLite's implicit rowid instead of explicitly storing `id` as an extra indexed column, reducing index storage while preserving ordered same-tick lookups.

For full-backup migrations, failed or unverifiable partial backups are removed when possible. Verified backups created by this policy receive a verification marker; successful migrations prune only those explicitly verified backups according to `database-migration-backup-retention-count`, preserving at least one by default. Older unmarked backups are never auto-deleted.

Before either a full backup migration or a large index rebuild, FragGuard performs a disk-space preflight using the selected migration strategy plus `database-migration-space-safety-mib`. This is a final safety net, not the migration strategy itself: index-only migrations first avoid the full database copy. If your hosting platform does not expose its quota correctly through Java filesystem APIs, `database-migration-space-preflight-enabled` can be explicitly disabled; SQLite failures still abort without advancing the schema version.
'''
new_readme = '''The schema-v3 to schema-v4 migration is different: the persistent same-tick coalescing index is derived data and is no longer needed. FragGuard verifies the live database, transactionally removes `idx_fg_tick_coalesce` if it still exists, verifies the database again, and only then advances `PRAGMA user_version` to 4. It does **not** create a full history backup and does **not** build a replacement multi-gigabyte index. A retry from schema v3 where a previous attempt already removed the index is explicitly supported.

Schema v4 performs cross-flush same-tick coalescing with a small bounded in-memory row-ID cache for the current server session. Compatible changes update the exact persisted row directly by primary-key ID, incompatible actors/actions replace the candidate, net no-ops delete that exact row, and old candidates expire after a short tick window or when the cache reaches its hard cap. Eviction can only reduce coalescing efficiency; it never discards an underlying history event.

For full-backup migrations that actually change persistent schema/data, failed or unverifiable partial backups are removed when possible. Verified backups created by this policy receive a verification marker; successful migrations prune only those explicitly verified backups according to `database-migration-backup-retention-count`, preserving at least one by default. Older unmarked backups are never auto-deleted.

Full-backup migrations perform a disk-space preflight using Java's filesystem-reported usable space plus `database-migration-space-safety-mib`. On managed hosting, that filesystem value can be much larger than the account's real storage quota, so FragGuard logs it specifically as a filesystem value rather than claiming it is the usable hosting quota. `database-migration-space-preflight-enabled` can be explicitly disabled if the host filesystem value itself is unusable; SQLite failures still abort without advancing the schema version.
'''
readme = replace_once(readme, old_readme, new_readme, "README migration strategy")
readme_path.write_text(readme)

# Changelog: replace the first #68 description and remove the duplicate Fixed heading.
changelog_path = Path("changelog.md")
changelog = changelog_path.read_text()
changelog = replace_once(
    changelog,
    "- Fixed #68 by making schema migrations storage-aware: the index-only v3→v4 upgrade no longer creates a full duplicate database, frees the old derived index before rebuilding it, removes the redundant explicit row-ID index column, performs migration-space preflight checks, cleans failed partial backups, and conservatively prunes only backups explicitly marked as verified.\n\n### Fixed\n\n- Fixed #62",
    "- Fixed #68 by eliminating the persistent same-tick coalescing index from schema v4. The v3→v4 migration now only verifies the database, removes the obsolete derived index, and advances the schema without a full backup or replacement index build; cross-flush coalescing now uses bounded current-session row-ID tracking, while full data-changing migrations retain verified backups, failed-backup cleanup, conservative retention, and filesystem-space preflight warnings that do not misrepresent managed-host quotas.\n- Fixed #62",
    "#68 changelog entry",
)
changelog_path.write_text(changelog)
