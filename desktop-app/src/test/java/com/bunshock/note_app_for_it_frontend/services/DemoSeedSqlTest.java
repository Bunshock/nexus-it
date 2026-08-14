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

// database/sqlite/demo-seed.sql is hand-written SQL, preserved outside the app's own Java code
// after seedEquipmentData()/seedHistoryData() were removed from DatabaseService, so
// a fresh/deleted-and-recreated local database starts genuinely empty. Nothing else in this
// build compiles or type-checks that file, so a typo or schema drift would only surface the
// first time someone actually loads it — this test catches that ahead of time by running it for
// real against a database with the exact same schema DatabaseService creates (duplicated here,
// same precedent as SqliteHistoryServiceTest's own schema copy — DatabaseService.DB_URL is a
// hardcoded path, not swappable for a temp one).
class DemoSeedSqlTest {

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
                CREATE TABLE PROVIDER (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                )""");
            s.executeUpdate("""
                CREATE TABLE NOTE_REPORT (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at      TEXT NOT NULL,
                    profile_type    TEXT NOT NULL,
                    technician_name TEXT,
                    technician_dni  TEXT
                )""");
            s.executeUpdate("""
                CREATE TABLE NOTE_ENTREGA_DEVOLUCION (
                    note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    user_name       TEXT,
                    user_dni        TEXT,
                    user_email      TEXT,
                    motivo          TEXT,
                    failure_cause   TEXT,
                    failure_details TEXT
                )""");
            s.executeUpdate("""
                CREATE TABLE NOTE_PROVEEDOR (
                    note_report_id   INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    provider_id      INTEGER NOT NULL REFERENCES PROVIDER(id),
                    cuit             TEXT,
                    motivo           TEXT,
                    responsible_name TEXT,
                    responsible_dni  TEXT
                )""");
            s.executeUpdate("""
                CREATE TABLE NOTE_ITEM (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    note_id      INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
                    type_id      INTEGER NOT NULL REFERENCES TYPE(id),
                    brand_id     INTEGER NOT NULL REFERENCES BRAND(id),
                    model_id     INTEGER NOT NULL REFERENCES MODEL(id),
                    observations TEXT
                )""");
            s.executeUpdate("""
                CREATE TABLE NOTE_ITEM_ASSET (
                    item_id       INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    serial_number TEXT,
                    a_f           TEXT
                )""");
            s.executeUpdate("""
                CREATE TABLE NOTE_ITEM_COUNTABLE (
                    item_id  INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    quantity INTEGER NOT NULL DEFAULT 1
                )""");
            s.executeUpdate("""
                CREATE TABLE NOTE_ITEM_GLPI_TRACKING (
                    item_id           INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    status            TEXT NOT NULL,
                    rejection_reason  TEXT,
                    status_updated_at TEXT
                )""");
            s.executeUpdate("""
                CREATE TABLE NOTE_ITEM_RETURN_TRACKING (
                    item_id           INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    status            TEXT NOT NULL,
                    rejection_reason  TEXT,
                    status_updated_at TEXT
                )""");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private void runDemoSeedScript() throws Exception {
        Path sqlFile = Path.of("database", "sqlite", "demo-seed.sql");
        assertTrue(Files.exists(sqlFile), "database/sqlite/demo-seed.sql not found relative to the working directory tests run from");
        String script = Files.readString(sqlFile);

        // Strip "-- ..." line comments before splitting on ';' — splitting first and then
        // skipping chunks that merely *start* with "--" isn't enough: a comment line with no
        // semicolon before the next real statement (e.g. this file's header comment block,
        // directly followed by "CREATE TEMP TABLE ...") ends up glued into the same chunk as
        // that statement, so the whole chunk still starts with "--" and the statement inside it
        // gets silently skipped too.
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
    void loadsFullDemoDatasetWithoutErrors() throws Exception {
        runDemoSeedScript();

        assertTrue(count("TYPE") > 0, "TYPE should have rows after loading the script");
        assertTrue(count("BRAND") > 0, "BRAND should have rows after loading the script");
        assertTrue(count("BRAND_TYPE_LINK") > 0, "BRAND_TYPE_LINK should have rows after loading the script");

        // Three rows in the source data intentionally have a NULL model name (types/brand
        // combos with no specific model — LINEA CORPORATIVA/PERSONAL, MICROFONO/K9,
        // MOUSE/DELL) — those contribute a TYPE/BRAND/BRAND_TYPE_LINK row but no MODEL row, so
        // 133 source rows (132 original + CABLE HDMI/Genérico / Otro/1.5 MTS, added so the demo
        // history notes below have a real catalog row to resolve their type_id/brand_id/model_id
        // against) -> 130 MODEL rows.
        assertEquals(130, count("MODEL"));

        assertEquals(8, count("NOTE_REPORT"), "8 fictional demo history notes");
        assertTrue(count("NOTE_ENTREGA_DEVOLUCION") > 0);
        assertTrue(count("NOTE_PROVEEDOR") > 0);
        assertEquals(19, count("NOTE_ITEM"), "19 demo items across the 8 notes");
        assertEquals(11, count("NOTE_ITEM_ASSET"));
        assertEquals(8, count("NOTE_ITEM_COUNTABLE"));
        assertEquals(10, count("NOTE_ITEM_GLPI_TRACKING"), "every asset item except the Préstamo's");
        // The Préstamo note's single asset item is the only one return-tracked (Préstamo assets
        // are excluded from GLPI sync — see CLAUDE.md's "Préstamo assets are deliberately
        // excluded from GLPI sync" — so it has no NOTE_ITEM_GLPI_TRACKING row, only this one).
        assertEquals(1, count("NOTE_ITEM_RETURN_TRACKING"));
    }

    @Test
    void reRunningScriptDoesNotDuplicateRows() throws Exception {
        runDemoSeedScript();
        int typesAfterFirstRun = count("TYPE");
        int notesAfterFirstRun = count("NOTE_REPORT");

        runDemoSeedScript();

        assertEquals(typesAfterFirstRun, count("TYPE"), "Equipment catalog upserts (OR IGNORE) must not duplicate on re-run");
        assertEquals(notesAfterFirstRun, count("NOTE_REPORT"), "History notes are skipped as a whole group once NOTE_REPORT is non-empty");
    }

    @Test
    void notebookTypeRequiresSerial() throws Exception {
        runDemoSeedScript();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT requires_serial FROM TYPE WHERE name = 'NOTEBOOK'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void skipsHistorySeedEntirelyWhenNoteReportAlreadyHasRows() throws Exception {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("INSERT INTO NOTE_REPORT (created_at, profile_type) VALUES ('2020-01-01T00:00:00', 'Entrega')");
        }

        runDemoSeedScript();

        assertEquals(1, count("NOTE_REPORT"), "A pre-existing real note must not have fake demo notes appended alongside it");
    }
}
