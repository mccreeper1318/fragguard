package org.pinnaclesmp.fragguard;

import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Reports which SQLite JDBC and native engine *actually opened* the database.
 *
 * Paper already provides SQLite JDBC. Gradle's compile/test dependency does
 * not determine the runtime driver under Paper's classloader. Never reveal
 * absolute filesystem paths, classloader internals or player/database data.
 */
final class SqliteDriverProvenance {
    private static final Pattern SQLITE_JAR =
            Pattern.compile("sqlite-jdbc-[0-9]+(?:\\.[0-9]+){3}\\.jar");
    private static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z._-]{1,40}");

    private SqliteDriverProvenance() {
    }

    record Details(String jdbcVersion, String nativeVersion,
                   String jdbcClassOrigin, String connectionClassOrigin,
                   boolean sqliteClassesFromPlugin) {
        String summary() {
            return "FragGuard SQLite runtime: jdbc-driver-version=" + jdbcVersion
                    + ", native-sqlite-version=" + nativeVersion
                    + ", JDBC-class-origin=" + jdbcClassOrigin
                    + ", connection-class-origin=" + connectionClassOrigin
                    + ", driver-from-plugin=" + sqliteClassesFromPlugin
                    + ". Paper provides the production SQLite JDBC library; "
                    + "Gradle's driver version is used for compile/tests only.";
        }
    }

    static Details inspect(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String version = safeVersion(metadata.getDriverVersion());
        String engine;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT sqlite_version()")) {
            engine = result.next() ? safeVersion(result.getString(1)) : "unknown";
        }
        String jdbcOrigin;
        try {
            jdbcOrigin = classOrigin(Class.forName("org.sqlite.JDBC", false,
                    SqliteDriverProvenance.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            jdbcOrigin = "unknown";
        }
        String connectionOrigin = classOrigin(connection.getClass());
        boolean pluginClasses = "FragGuard-jar".equals(jdbcOrigin)
                || "FragGuard-jar".equals(connectionOrigin);
        return new Details(version, engine, jdbcOrigin, connectionOrigin, pluginClasses);
    }

    /**
     * Observability only: diagnostics must not prevent startup or obscure a
     * genuine SQLite schema/storage failure. No JDBC/SQLite global settings
     * are changed and no extra database connection is opened.
     */
    static void log(Connection connection, Consumer<String> info, Consumer<String> warning) {
        try {
            Details details = inspect(connection);
            info.accept(details.summary());
            if (details.sqliteClassesFromPlugin()) {
                warning.accept("FragGuard SQLite driver originated from the plugin JAR; "
                        + "expected Paper-provided SQLite. Check the shaded artifact and runtime classpath.");
            }
        } catch (SQLException | SecurityException | RuntimeException | LinkageError e) {
            warning.accept("FragGuard could not inspect SQLite runtime provenance ("
                    + e.getClass().getSimpleName() + "). "
                    + "This diagnostic does not establish a migration or driver failure.");
        }
    }

    static String classOrigin(Class<?> type) {
        try {
            ProtectionDomain domain = type.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            return source == null ? "unknown" : sanitizedJarName(source.getLocation());
        } catch (SecurityException | RuntimeException e) {
            return "unknown";
        }
    }

    /** Sanitizes external provider location, never logging a directory/path. */
    static String sanitizedJarName(URL location) {
        if (location == null) return "unknown";
        String path = location.getPath();
        if (path == null || path.isBlank()) return "unknown";
        int last = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String file = path.substring(last + 1);
        if (SQLITE_JAR.matcher(file).matches()) {
            return file;
        }
        if (file.toLowerCase(Locale.ROOT).startsWith("fragguard-")
                && file.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return "FragGuard-jar";
        }
        return "other/unknown";
    }

    private static String safeVersion(String value) {
        return value != null && SAFE_VERSION.matcher(value).matches() ? value : "unknown";
    }
}
