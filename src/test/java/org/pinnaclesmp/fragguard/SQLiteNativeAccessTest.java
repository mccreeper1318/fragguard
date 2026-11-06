package org.pinnaclesmp.fragguard;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteNativeAccessTest {
    @Test
    void sqliteWorksWhenIllegalNativeAccessIsDenied() throws Exception {
        assertTrue(org.sqlite.JDBC.class.getModule().isNativeAccessEnabled(),
                "SQLite's JNI module must have explicitly enabled native access in the test JVM");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE native_access_test (value INTEGER NOT NULL)");
            statement.executeUpdate("INSERT INTO native_access_test (value) VALUES (42)");

            try (ResultSet result = statement.executeQuery("SELECT value FROM native_access_test")) {
                assertTrue(result.next());
                assertEquals(42, result.getInt(1));
            }
        }
    }
}
