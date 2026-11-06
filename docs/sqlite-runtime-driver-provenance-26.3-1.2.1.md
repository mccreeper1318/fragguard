# FragGuard 26.3-1.2.1 — SQLite JDBC runtime provenance (#149)

## Why Beta 2 reported a different SQLite JDBC version

FG's earlier Gradle dependency/lockfile specified **Xerial SQLite JDBC 3.54.1.0**, but the Beta 2 server stack trace reported **sqlite-jdbc-3.49.1.0.jar**.

Paper includes SQLite JDBC directly. Paper's own runtime build dependency lists **sqlite-jdbc 3.49.1.0** and its database documentation says plugins **should not shade/relocate** that driver.

- Paper developer guide: https://docs.papermc.io/paper/dev/using-databases/
- Paper server runtime dependency source: https://github.com/PaperMC/Paper/blob/main/paper-server/build.gradle.kts

This is a supported **runtime provider difference**, not in itself a corrupt database, and **not established as the cause** of the v7→v8 `SQLITE_FULL` failure. The precise classes and native engine used on the affected BisectHosting instance still require observation in an actual matching Paper runtime.

## Fix in #149

- Change the SQLite JDBC dependency to **`compileOnly`**: the Gradle 3.54.1.0 version is for compiling; Paper supplies the library at runtime.
- Keep a pinned 3.54.1.0 **test runtime** dependency for local/CI tests, where Paper itself is absent.
- Do not bundle `org/sqlite/**`, SQLite JDBC native binaries or `META-INF/services/java.sql.Driver` in the released FragGuard JAR.
- Preserve the SLF4J NOP service provider and all other plugin shading requirements.
- Add CI checks for the final shaded artifact and negative tests that intentionally insert a SQLite driver class/native file/service descriptor.
- Add startup logging from the **actual JDBC connection opened by FG** before database schema migrations begin:
  - `jdbc-driver-version` from `DatabaseMetaData.getDriverVersion()`;
  - `native-sqlite-version` from `SELECT sqlite_version()`;
  - sanitized JDBC/connection class JAR identities, never absolute paths;
  - whether a driver class unexpectedly came from the FG plugin JAR.
- This telemetry is best-effort: errors inspecting provenance do not change SQLite configuration or hide an actual migration error.

### What the diagnostic means

A log like

```
FragGuard SQLite runtime: jdbc-driver-version=3.49.1.0,
native-sqlite-version=3.49.1, JDBC-class-origin=sqlite-jdbc-3.49.1.0.jar,
connection-class-origin=sqlite-jdbc-3.49.1.0.jar, driver-from-plugin=false.
```

would establish the versions from the **running** connection. It is an **illustrative example**, not a claim we have observed this specific output from the production server.

The fallback `other/unknown` means the JAR could not be recognized safely. Do not treat an unknown origin as a reason to change database files. If a Paper update supplies a different driver version, the logs will report that actual version rather than the compile/test dependency.

The program no longer tries to ship a newer SQLite JDBC library over Paper's runtime. This is consistent with Paper's officially supported plugin DB API and avoids keeping two copies of the same JDBC/native implementation in different classloaders.

## Release checks and limitations

- [ ] CI: no bundled SQLite classes/native files/service descriptors.
- [ ] CI: native SQLite read/write works on Java 25 with native access enabled and `--illegal-native-access=deny`.
- [ ] CI: provider provenance test, JAR-verifier guardrails and full regression suite pass.
- [ ] Confirm Paper 26.3 build 241 startup reports the correct JDBC/native engine and external JAR origin on an **isolated test server**.
- [ ] Test v7→v9 on a coherent database copy, then final live Paper tests at the end of this update (#150/#151).
- [ ] Confirm no regressions in player rollback, GUI, migrations or SQLite WAL handling.

**Critical recovery constraint:** The server owner has **no verified pre-upgrade schema-v4 backup**. The live database likely remains at schema **v7** after Beta 2. Never hand-edit `PRAGMA user_version`, delete WAL/SHM files, or replace the JAR with FG 1.2.0 against the existing DB. Preserve it and complete a tested forward recovery to v9 (#150/#151).

Release PR #146 must remain open and unmerged until all release blockers are complete.
