package com.bunshock.note_app_for_it_frontend.services;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

// database/sqlserver/02-seed-equipment.sql.example targets SQL Server specifically (IDENTITY,
// NVARCHAR, #-prefixed local temp tables, N'...' Unicode string literals, an explicit
// BEGIN/COMMIT TRANSACTION) and there's no live SQL Server instance anywhere in this test suite's
// infrastructure to run the whole file against (confirmed via RemoteDatabaseServiceTest, which
// only ever exercises unreachable-host failure paths). But the S/N validation section of that
// file uses no SQL Server-specific *logic* — plain DELETE/INSERT/JOIN, standard SQL SQLite
// executes identically once the handful of T-SQL-only lexical quirks are normalized away. This
// test extracts just that section (between its own start/end markers in the file) and runs it
// against a temp SQLite database as a dialect-neutral stand-in, the same "same-dialect stand-in,
// not a mock" reasoning CatalogMigrationToolTest already uses — it's specifically checking the
// model_id resolution chain (TYPE -> BRAND -> BRAND_TYPE_LINK -> MODEL) is correct, which is
// dialect-independent.
class SqlServerSeedSqlSnValidationTest {

    private static final String SECTION_START_MARKER = "-- S/N validation rules";
    private static final String SECTION_END_MARKER = "-- Providers (";

    @TempDir
    Path tempDir;

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("noteapp.db"));
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE TYPE (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    is_asset INTEGER NOT NULL DEFAULT 1,
                    requires_serial INTEGER NOT NULL DEFAULT 0
                )""");
            s.executeUpdate("""
                CREATE TABLE BRAND (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                )""");
            s.executeUpdate("""
                CREATE TABLE BRAND_TYPE_LINK (
                    id       INTEGER PRIMARY KEY AUTOINCREMENT,
                    type_id  INTEGER NOT NULL REFERENCES TYPE(id),
                    brand_id INTEGER NOT NULL REFERENCES BRAND(id),
                    UNIQUE(type_id, brand_id)
                )""");
            s.executeUpdate("""
                CREATE TABLE MODEL (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    name          TEXT NOT NULL
                )""");
            s.executeUpdate("""
                CREATE TABLE SN_VALIDATION (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                    regex_pattern TEXT,
                    is_active     INTEGER NOT NULL DEFAULT 1
                )""");

            // Mirrors the seed file's own example catalog rows relevant to the S/N section
            // (NOTEBOOK / Marca Ejemplo A / "Modelo Ejemplo 14" and "15") plus an unrelated
            // model (MOUSE) to prove the resolution doesn't accidentally match the wrong one.
            s.executeUpdate("INSERT INTO TYPE (name, is_asset, requires_serial) VALUES ('NOTEBOOK', 1, 1)");
            s.executeUpdate("INSERT INTO TYPE (name, is_asset, requires_serial) VALUES ('MOUSE', 0, 0)");
            s.executeUpdate("INSERT INTO BRAND (name) VALUES ('Marca Ejemplo A')");
            s.executeUpdate("INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) SELECT (SELECT id FROM TYPE WHERE name='NOTEBOOK'), (SELECT id FROM BRAND WHERE name='Marca Ejemplo A')");
            s.executeUpdate("INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) SELECT (SELECT id FROM TYPE WHERE name='MOUSE'), (SELECT id FROM BRAND WHERE name='Marca Ejemplo A')");
            s.executeUpdate("""
                INSERT INTO MODEL (brand_type_id, name)
                SELECT id, 'Modelo Ejemplo 14' FROM BRAND_TYPE_LINK
                WHERE type_id = (SELECT id FROM TYPE WHERE name='NOTEBOOK')
                """);
            s.executeUpdate("""
                INSERT INTO MODEL (brand_type_id, name)
                SELECT id, 'Modelo Ejemplo 15' FROM BRAND_TYPE_LINK
                WHERE type_id = (SELECT id FROM TYPE WHERE name='NOTEBOOK')
                """);
            s.executeUpdate("""
                INSERT INTO MODEL (brand_type_id, name)
                SELECT id, 'Modelo Ejemplo Inalámbrico' FROM BRAND_TYPE_LINK
                WHERE type_id = (SELECT id FROM TYPE WHERE name='MOUSE')
                """);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private void runSnValidationSection() throws Exception {
        Path sqlFile = Path.of("database", "sqlserver", "02-seed-equipment.sql.example");
        assertTrue(Files.exists(sqlFile), "database/sqlserver/02-seed-equipment.sql.example not found relative to the working directory tests run from");
        String fullScript = Files.readString(sqlFile);

        int start = fullScript.indexOf(SECTION_START_MARKER);
        int end = fullScript.indexOf(SECTION_END_MARKER, start);
        assertTrue(start >= 0 && end > start,
            "Could not locate the S/N validation section between its start/end markers — did the surrounding comments change?");
        // Three T-SQL-only lexical quirks stripped for this dialect-neutral proxy — none are part
        // of the DELETE/INSERT/JOIN resolution logic this test actually cares about:
        // - "#_seed_sn_rules" (SQL Server's naming convention for a local temp table) isn't a
        //   valid unquoted SQLite identifier ('#' isn't allowed unquoted) — rename to a plain
        //   identifier the same way the real script's own DROP TABLE at the end already scopes
        //   its lifetime, so no behavior is lost by the rename.
        // - "N'...'" (T-SQL's Unicode string literal prefix) has no SQLite equivalent — SQLite
        //   strings are always Unicode already, so the prefix is simply dropped.
        // - "NVARCHAR(MAX)" — SQLite's parser chokes on the "MAX" keyword-as-length-argument
        //   syntax (a genuine syntax error, not just an unrecognized type name — SQLite is
        //   normally lenient about type names via type affinity). Replaced with plain TEXT.
        String section = fullScript.substring(start, end)
            .replace("#_seed_sn_rules", "_seed_sn_rules")
            .replace("N'", "'")
            .replace("NVARCHAR(MAX)", "TEXT");

        StringBuilder withoutComments = new StringBuilder();
        for (String line : section.split("\n")) {
            int commentAt = line.indexOf("--");
            withoutComments.append(commentAt >= 0 ? line.substring(0, commentAt) : line).append('\n');
        }

        try (Statement s = conn.createStatement()) {
            for (String statement : withoutComments.toString().split(";")) {
                String trimmed = statement.strip();
                if (trimmed.isEmpty()) continue;
                s.executeUpdate(trimmed);
            }
        }
    }

    private int count(String table) throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void resolvesRulesToTheCorrectModelIds() throws Exception {
        runSnValidationSection();

        assertEquals(2, count("SN_VALIDATION"), "Both example NOTEBOOK models get a rule; MOUSE gets none");

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("""
                 SELECT sv.regex_pattern FROM SN_VALIDATION sv
                 JOIN MODEL m ON m.id = sv.model_id
                 WHERE m.name = 'Modelo Ejemplo 14'
                 """)) {
            assertTrue(rs.next(), "The rule must resolve to the actual MODEL row, not BRAND_TYPE_LINK's id or any other id");
            assertEquals("^[A-Z0-9]{11}$", rs.getString(1));
        }

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("""
                 SELECT COUNT(*) FROM SN_VALIDATION sv
                 JOIN MODEL m ON m.id = sv.model_id
                 WHERE m.name = 'Modelo Ejemplo Inalámbrico'
                 """)) {
            rs.next();
            assertEquals(0, rs.getInt(1), "MOUSE's model must not get a rule — it's not in the example _seed_sn_rules data");
        }
    }

    @Test
    void reRunningSectionReplacesRulesInsteadOfDuplicating() throws Exception {
        // The real script's own DROP TABLE at the end of this section (mirrored here after the
        // #_seed_sn_rules -> _seed_sn_rules rename) already makes the section self-contained and
        // re-runnable with no test-harness-only cleanup step needed between calls.
        runSnValidationSection();
        runSnValidationSection();

        assertEquals(2, count("SN_VALIDATION"), "Delete-then-insert per model must not accumulate duplicates on re-run");
    }
}
