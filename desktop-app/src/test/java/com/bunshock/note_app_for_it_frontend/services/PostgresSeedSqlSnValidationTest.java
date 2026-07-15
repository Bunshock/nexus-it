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

// database/postgresql/02-seed-equipment.sql.example targets PostgreSQL specifically (SERIAL,
// ON CONFLICT, an explicit BEGIN/COMMIT transaction) and there's no live PostgreSQL instance
// anywhere in this test suite's infrastructure to run the whole file against (confirmed via
// RemoteDatabaseServiceTest, which only ever exercises unreachable-host failure paths). But the
// S/N validation section added to that file (2026-07-14, for parity with
// database/sqlite/starter-template.sql.example) uses no Postgres-specific syntax at all — plain
// DELETE/INSERT/JOIN, standard SQL SQLite executes identically. This test extracts just that
// section (between its own start/end markers in the file) and runs it against a temp SQLite
// database as a dialect-neutral stand-in, the same "same-dialect stand-in, not a mock" reasoning
// CatalogMigrationToolTest already uses — it's specifically checking the model_id resolution
// chain (TYPE -> BRAND -> BRAND_TYPE_LINK -> MODEL) is correct, which is dialect-independent.
class PostgresSeedSqlSnValidationTest {

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
                    description   TEXT,
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
        Path sqlFile = Path.of("database", "postgresql", "02-seed-equipment.sql.example");
        assertTrue(Files.exists(sqlFile), "database/postgresql/02-seed-equipment.sql.example not found relative to the working directory tests run from");
        String fullScript = Files.readString(sqlFile);

        int start = fullScript.indexOf(SECTION_START_MARKER);
        int end = fullScript.indexOf(SECTION_END_MARKER, start);
        assertTrue(start >= 0 && end > start,
            "Could not locate the S/N validation section between its start/end markers — did the surrounding comments change?");
        // "ON COMMIT DROP" is genuine Postgres-only syntax (auto-drops the temp table at
        // transaction commit) — SQLite has no equivalent and errors on it. Stripped for this
        // dialect-neutral proxy since it's a cleanup-timing detail, not part of the DELETE/
        // INSERT/JOIN resolution logic this test actually cares about; the temp table just
        // outlives the connection instead, which is fine for a single test run.
        String section = fullScript.substring(start, end).replace("ON COMMIT DROP", "");

        StringBuilder withoutComments = new StringBuilder();
        for (String line : section.split("\n")) {
            int commentAt = line.indexOf("--");
            withoutComments.append(commentAt >= 0 ? line.substring(0, commentAt) : line).append('\n');
        }

        try (Statement s = conn.createStatement()) {
            // Also test-harness-only: real Postgres auto-drops _seed_sn_rules at COMMIT (that's
            // what the stripped "ON COMMIT DROP" did), so calling this section a second time in
            // the same transaction never collides there. Without that, SQLite's temp table
            // outlives the whole connection, so a second call's CREATE TEMP TABLE would fail —
            // drop it first so re-running this method mirrors what re-running the real script
            // against a real Postgres database actually does.
            s.executeUpdate("DROP TABLE IF EXISTS _seed_sn_rules");
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
        runSnValidationSection();
        runSnValidationSection();

        assertEquals(2, count("SN_VALIDATION"), "Delete-then-insert per model must not accumulate duplicates on re-run");
    }
}
