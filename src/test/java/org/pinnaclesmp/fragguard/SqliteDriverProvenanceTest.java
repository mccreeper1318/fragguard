package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqliteDriverProvenanceTest {
    @Test
    void reportsDriverAndEngineFromRealOpenConnectionWithoutLeakingPaths() throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var details = SqliteDriverProvenance.inspect(db);
            assertEquals(db.getMetaData().getDriverVersion(), details.jdbcVersion());
            assertTrue(details.nativeVersion().matches("[0-9]+(?:\\.[0-9]+){2}"),
                    "SQLite native engine must report its actual version");
            assertFalse(details.jdbcClassOrigin().contains("/"));
            assertFalse(details.connectionClassOrigin().contains("/"));
            assertTrue(details.summary().contains("jdbc-driver-version="));
            assertTrue(details.summary().contains("native-sqlite-version="));
            assertTrue(details.summary().contains("driver-from-plugin="));

            List<String> info = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            SqliteDriverProvenance.log(db, info::add, warnings::add);
            assertEquals(1, info.size());
            assertTrue(warnings.isEmpty(), "test driver comes from a standalone JDBC library");
            assertFalse(info.getFirst().contains(System.getProperty("user.home")),
                    "logs must not include the filesystem location of the driver or database");
        }
    }

    @Test
    void redactsAbsoluteOriginPathsButRetainsRecognizableLibraryVersion() throws Exception {
        assertEquals("sqlite-jdbc-3.49.1.0.jar",
                SqliteDriverProvenance.sanitizedJarName(
                        new URL("file:/private/server/lib/sqlite-jdbc-3.49.1.0.jar")));
        assertEquals("sqlite-jdbc-3.54.1.0.jar",
                SqliteDriverProvenance.sanitizedJarName(
                        new URL("file:/home/secret/.gradle/sqlite-jdbc-3.54.1.0.jar")));
        assertEquals("FragGuard-jar",
                SqliteDriverProvenance.sanitizedJarName(
                        new URL("file:/plugins/FragGuard-26.3-1.2.1.jar")));
        assertEquals("other/unknown",
                SqliteDriverProvenance.sanitizedJarName(
                        new URL("file:/home/private/mystery-driver.jar")));
        assertEquals("unknown", SqliteDriverProvenance.sanitizedJarName(null));
    }

    @Test
    void reportsProviderVersionDynamicallyInsteadOfAssumingGradleTestDependencyAtRuntime()
            throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            String actual = db.getMetaData().getDriverVersion();
            var details = SqliteDriverProvenance.inspect(db);
            assertEquals(actual, details.jdbcVersion());
            // Paper may supply 3.49.1.0 while testClasspath uses 3.54.1.0.
            // Neither version is guessed from the dependency declaration.
            assertFalse(details.jdbcVersion().isBlank());
            assertFalse(details.nativeVersion().isBlank());
        }
    }
}
