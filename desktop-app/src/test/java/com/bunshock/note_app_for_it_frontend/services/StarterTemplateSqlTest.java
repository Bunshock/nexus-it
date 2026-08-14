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

// database/sqlite/starter-template.sql.example — same rationale as DemoSeedSqlTest (hand-written
// SQL that nothing else in the build compiles or type-checks), but for the blank-starting-point
// template rather than the demo dataset. Tested against the committed .example file directly
// (the real, gitignored starter-template.sql a user fills in doesn't exist in this repo) — its
// example rows are valid, fillable-in-place data, so loading it for real is exactly what a user
// copying and editing it would also do.
class StarterTemplateSqlTest {

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
                    is_asset INTEGER NOT NULL DEFAULT 0,
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
            s.executeUpdate("CREATE UNIQUE INDEX idx_model_brand_type_name ON MODEL(brand_type_id, name)");
            s.executeUpdate("""
                CREATE TABLE SN_VALIDATION (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                    regex_pattern TEXT,
                    is_active     INTEGER NOT NULL DEFAULT 1
                )""");
            s.executeUpdate("""
                CREATE TABLE PROVIDER (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                )""");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private void runTemplateScript() throws Exception {
        Path sqlFile = Path.of("database", "sqlite", "starter-template.sql.example");
        assertTrue(Files.exists(sqlFile), "database/sqlite/starter-template.sql.example not found relative to the working directory tests run from");
        String script = Files.readString(sqlFile);

        // Same comment-stripping approach as DemoSeedSqlTest — see that test for why a naive
        // split-then-skip-"--"-chunks approach silently drops statements glued to a preceding
        // comment line with no semicolon between them.
        StringBuilder withoutComments = new StringBuilder();
        for (String line : script.split("\n")) {
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
    void loadsExampleCatalogWithoutErrors() throws Exception {
        runTemplateScript();

        assertEquals(4, count("TYPE"), "NOTEBOOK, MONITOR, MOUSE, HEADSET");
        assertEquals(3, count("BRAND"), "Marca Ejemplo A, Marca Ejemplo B, Genérico");
        // NOTEBOOK+MarcaA, NOTEBOOK+MarcaB, MONITOR+Genérico, MOUSE+MarcaA, HEADSET+Genérico —
        // 5 distinct (type, brand) pairs; MOUSE+MarcaA is a different link than NOTEBOOK+MarcaA
        // despite sharing a brand, since BRAND_TYPE_LINK is keyed on the (type, brand) pair.
        assertEquals(5, count("BRAND_TYPE_LINK"));
        // 4 example rows have a model name (both NOTEBOOK models, the NOTEBOOK Pro, and the
        // MOUSE) — MONITOR/Genérico and HEADSET/Genérico intentionally have a NULL model.
        assertEquals(4, count("MODEL"));
        assertEquals(2, count("PROVIDER"));
    }

    @Test
    void requiresSerialIsSetPerRowNotJustForNotebook() throws Exception {
        runTemplateScript();

        // requires_serial is a column in the _seed_rows staging table itself now (matching
        // database/sqlserver/02-seed-equipment.sql.example's per-row approach), not derived
        // from the type name — so a non-NOTEBOOK type could require it too, and NOTEBOOK itself
        // isn't hardcoded as special. MOUSE is the same is_asset=0 case demo-seed.sql's
        // NOTEBOOK-only CASE expression would have gotten right anyway, but for the wrong
        // reason — this checks the actual per-row value made it through, not a coincidence.
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT requires_serial FROM TYPE WHERE name = 'NOTEBOOK'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT requires_serial FROM TYPE WHERE name = 'MOUSE'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void loadsSnValidationRulesLinkedToCorrectModels() throws Exception {
        runTemplateScript();

        assertEquals(2, count("SN_VALIDATION"), "Both example NOTEBOOK models have a rule");

        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("""
                 SELECT sv.regex_pattern FROM SN_VALIDATION sv
                 JOIN MODEL m ON m.id = sv.model_id
                 WHERE m.name = 'Modelo Ejemplo 14'
                 """)) {
            assertTrue(rs.next(), "The rule must resolve to the actual MODEL row, not some other id");
            assertEquals("^[A-Z0-9]{11}$", rs.getString(1));
        }
    }

    @Test
    void reRunningScriptReplacesRulesInsteadOfDuplicating() throws Exception {
        runTemplateScript();
        runTemplateScript();

        assertEquals(2, count("SN_VALIDATION"), "Re-running must replace (delete-then-insert), not accumulate duplicate rules per model");
        assertEquals(4, count("MODEL"), "Equipment catalog OR IGNORE inserts must not duplicate either");
    }
}
