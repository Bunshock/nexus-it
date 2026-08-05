package com.bunshock.note_app_for_it_frontend.services;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

// DatabaseService.DB_URL is a hardcoded path (not swappable for a temp one — same constraint
// noted on SqliteHistoryServiceTest's own schema copy), so migrateSchema()/addColumnIfMissing()
// are invoked here via reflection against a temp SQLite database seeded with the pre-Préstamo
// schema, reproducing exactly what an existing installation's data/noteapp.db looks like before
// upgrading. Guards against the exact bug class CLAUDE.md documents: a column only added to
// CREATE TABLE, forgotten in migrateSchema(), never lands on an existing database.
class DatabaseServiceMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void migrateSchemaAddsPrestamoColumnsToAPreExistingDatabase() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("pre-prestamo.db").toAbsolutePath();

        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ENTREGA_DEVOLUCION (
                    note_report_id  INTEGER PRIMARY KEY,
                    user_name       TEXT,
                    user_dni        TEXT,
                    user_email      TEXT,
                    motivo          TEXT,
                    failure_cause   TEXT,
                    failure_details TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM (
                    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
                    note_id                INTEGER NOT NULL,
                    type_name              TEXT NOT NULL,
                    brand_name             TEXT,
                    model_name             TEXT,
                    serial_number          TEXT,
                    a_f                    TEXT,
                    quantity               INTEGER NOT NULL DEFAULT 1,
                    observations           TEXT,
                    is_asset               INTEGER NOT NULL DEFAULT 0,
                    glpi_status            TEXT NOT NULL DEFAULT 'N_A',
                    glpi_rejection_reason  TEXT,
                    glpi_status_updated_at TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE TYPE (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REPORT (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at   TEXT NOT NULL,
                    profile_type TEXT NOT NULL
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_PROVEEDOR (
                    note_report_id   INTEGER PRIMARY KEY,
                    responsible_name TEXT,
                    responsible_dni  TEXT
                )""");

            // Reproduces initialize()'s real ordering (createHistoryTables() runs before
            // migrateSchema()) — this is exactly what caught a real bug live-testing against the
            // actual data/noteapp.db: createHistoryTables()'s own CREATE TABLE IF NOT EXISTS for
            // the 4 new subtype tables is NOT a no-op on an old wide-shape database (they never
            // existed there at all), so by the time migrateNoteItemSchema() ran, those tables
            // already existed — its own CREATE TABLE statements for them needed IF NOT EXISTS
            // too, or this exact sequence throws "table already exists".
            invokeCreateEquipmentTables(stmt);
            invokeCreateHistoryTables(stmt);
            invokeMigrateSchema(c, stmt);

            // area_evento/failure_cause/failure_details never land back on NOTE_ENTREGA_DEVOLUCION
            // — they're split into
            // NOTE_PRESTAMO_AREA_EVENTO/NOTE_DEVOLUCION_FALLA instead, same "stop re-adding a
            // retired column" precedent as NOTE_REPORT.sede below.
            assertFalse(hasColumn(c, "NOTE_ENTREGA_DEVOLUCION", "area_evento"));
            assertFalse(hasColumn(c, "NOTE_ENTREGA_DEVOLUCION", "failure_cause"));
            assertFalse(hasColumn(c, "NOTE_ENTREGA_DEVOLUCION", "failure_details"));
            assertTrue(tableExists(c, "NOTE_PRESTAMO_AREA_EVENTO"));
            assertTrue(tableExists(c, "NOTE_DEVOLUCION_FALLA"));
            assertTrue(hasColumn(c, "NOTE_REPORT", "observations"));
            // sede was dropped (dead column, superseded by sede_id) — never lands on
            // an existing database anymore, see dropDeadNoteReportColumns() test below.
            assertFalse(hasColumn(c, "NOTE_REPORT", "sede"));
            assertTrue(hasColumn(c, "NOTE_REPORT", "approval_status"));
            // rejection_reason never lands back on NOTE_REPORT either —
            // split into NOTE_REPORT_REJECTION instead.
            assertFalse(hasColumn(c, "NOTE_REPORT", "rejection_reason"));
            assertTrue(tableExists(c, "NOTE_REPORT_REJECTION"));
            // return_status/etc. landed on NOTE_ITEM only transiently — this fixture's NOTE_ITEM
            // already had is_asset, so migrateNoteItemSchema() (called at the end of
            // migrateSchema()) immediately split it into the 5-table shape in the same run;
            // see migrateNoteItemSchemaSplitsWideTableIntoFiveTables() below for that behavior.
            assertFalse(hasColumn(c, "NOTE_ITEM", "return_status"));
            assertTrue(tableExists(c, "NOTE_ITEM_RETURN_TRACKING"));
        }
    }

    // sede was confirmed dead (no reader/writer anywhere in the app — superseded by sede_id) and
    // dropped. glpi_synced is a SQL-Server-only dead column (RemoteDatabaseService.java
    // never had a SQLite counterpart to begin with — confirmed via grep, DatabaseService.java's
    // NOTE_REPORT never declared it), so it has no SQLite migration to test here. This reproduces
    // an installation that already has sede (from an earlier app version) to confirm
    // migrateSchema() actually drops it, not just skips re-adding it on a fresh install.
    @Test
    void migrateSchemaDropsDeadSedeColumnFromAPreExistingDatabase() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("dead-columns.db").toAbsolutePath();

        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REPORT (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at   TEXT NOT NULL,
                    profile_type TEXT NOT NULL,
                    sede         TEXT
                )""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_REPORT (id, created_at, profile_type, sede)
                VALUES (1, '2026-01-01T10:00', 'ENTREGA', 'Campus Norte')""");

            invokeCreateEquipmentTables(stmt);
            invokeCreateHistoryTables(stmt);
            invokeMigrateSchema(c, stmt);

            assertFalse(hasColumn(c, "NOTE_REPORT", "sede"));
            // dropping the column must not touch unrelated existing rows/columns
            assertEquals("ENTREGA", singleString(c, "SELECT profile_type FROM NOTE_REPORT WHERE id = 1"));
        }
    }

    // rejection_reason used to sit inline on NOTE_REPORT,
    // nullable on every row and only ever populated once an admin actually rejects a note.
    // Reproduces an installation that already has a rejected note (rejection_reason populated)
    // to confirm migrateSchema() actually backfills it into NOTE_REPORT_REJECTION and drops the
    // column, not just skips re-adding it on a fresh install.
    @Test
    void migrateSchemaBackfillsRejectionReasonIntoOwnTableAndDropsColumn() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("dead-rejection-reason.db").toAbsolutePath();

        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REPORT (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at        TEXT NOT NULL,
                    profile_type      TEXT NOT NULL,
                    approval_status   TEXT NOT NULL DEFAULT 'PENDING',
                    rejection_reason  TEXT
                )""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_REPORT (id, created_at, profile_type, approval_status, rejection_reason)
                VALUES (1, '2026-01-01T10:00', 'ENTREGA', 'RECHAZADO', 'Tipo de nota incorrecto')""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_REPORT (id, created_at, profile_type, approval_status, rejection_reason)
                VALUES (2, '2026-01-02T10:00', 'ENTREGA', 'PENDING', NULL)""");

            invokeCreateEquipmentTables(stmt);
            invokeCreateHistoryTables(stmt);
            invokeMigrateSchema(c, stmt);

            assertFalse(hasColumn(c, "NOTE_REPORT", "rejection_reason"));
            assertEquals("Tipo de nota incorrecto",
                singleString(c, "SELECT rejection_reason FROM NOTE_REPORT_REJECTION WHERE note_report_id = 1"));
            assertFalse(rowExists(c, "SELECT 1 FROM NOTE_REPORT_REJECTION WHERE note_report_id = 2"));
        }
    }

    // failure_cause/failure_details/area_evento used to sit
    // inline on NOTE_ENTREGA_DEVOLUCION, nullable on every row regardless of profile type/motivo.
    // Reproduces an installation with one Devolución+Falla note and one Préstamo note with an
    // Área/Evento to confirm migrateSchema() backfills both into their own tables and drops all 3
    // columns, not just skips re-adding them on a fresh install.
    @Test
    void migrateSchemaBackfillsFailureAndAreaEventoIntoOwnTablesAndDropsColumns() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("dead-falla-area-evento.db").toAbsolutePath();

        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REPORT (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at   TEXT NOT NULL,
                    profile_type TEXT NOT NULL
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ENTREGA_DEVOLUCION (
                    note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    user_name       TEXT,
                    user_dni        TEXT,
                    user_email      TEXT,
                    motivo          TEXT,
                    failure_cause   TEXT,
                    failure_details TEXT,
                    area_evento     TEXT
                )""");
            stmt.executeUpdate("INSERT INTO NOTE_REPORT (id, created_at, profile_type) VALUES (1, '2026-01-01T10:00', 'DEVOLUCIÓN')");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ENTREGA_DEVOLUCION (note_report_id, user_name, motivo, failure_cause, failure_details)
                VALUES (1, 'Ana Diaz', 'Falla', 'No enciende', 'Pantalla no responde')""");
            stmt.executeUpdate("INSERT INTO NOTE_REPORT (id, created_at, profile_type) VALUES (2, '2026-01-02T10:00', 'PRÉSTAMO')");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ENTREGA_DEVOLUCION (note_report_id, user_name, motivo, area_evento)
                VALUES (2, 'Juan Perez', '01/02/2026', 'Feria de tecnología')""");

            invokeCreateEquipmentTables(stmt);
            invokeCreateHistoryTables(stmt);
            invokeMigrateSchema(c, stmt);

            assertFalse(hasColumn(c, "NOTE_ENTREGA_DEVOLUCION", "failure_cause"));
            assertFalse(hasColumn(c, "NOTE_ENTREGA_DEVOLUCION", "failure_details"));
            assertFalse(hasColumn(c, "NOTE_ENTREGA_DEVOLUCION", "area_evento"));
            assertEquals("No enciende",
                singleString(c, "SELECT failure_cause FROM NOTE_DEVOLUCION_FALLA WHERE note_report_id = 1"));
            assertEquals("Pantalla no responde",
                singleString(c, "SELECT failure_details FROM NOTE_DEVOLUCION_FALLA WHERE note_report_id = 1"));
            assertFalse(rowExists(c, "SELECT 1 FROM NOTE_DEVOLUCION_FALLA WHERE note_report_id = 2"));
            assertEquals("Feria de tecnología",
                singleString(c, "SELECT area_evento FROM NOTE_PRESTAMO_AREA_EVENTO WHERE note_report_id = 2"));
            assertFalse(rowExists(c, "SELECT 1 FROM NOTE_PRESTAMO_AREA_EVENTO WHERE note_report_id = 1"));
            // dropping the columns must not touch unrelated existing rows/columns
            assertEquals("Ana Diaz", singleString(c, "SELECT user_name FROM NOTE_ENTREGA_DEVOLUCION WHERE note_report_id = 1"));
        }
    }

    // Covers all 4 quadrants of the old wide NOTE_ITEM shape: an asset that IS GLPI-tracked, an
    // asset that ISN'T (Préstamo — glpi_status stayed 'N_A'), a countable that ISN'T return-tracked
    // (non-Préstamo — return_status stayed 'N_A'), and a countable that IS (Préstamo).
    @Test
    void migrateNoteItemSchemaSplitsWideTableIntoFiveTables() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("wide-note-item.db").toAbsolutePath();

        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REPORT (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at   TEXT NOT NULL,
                    profile_type TEXT NOT NULL
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM (
                    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
                    note_id                  INTEGER NOT NULL,
                    type_name                TEXT NOT NULL,
                    brand_name               TEXT,
                    model_name               TEXT,
                    serial_number            TEXT,
                    a_f                      TEXT,
                    quantity                 INTEGER NOT NULL DEFAULT 1,
                    observations             TEXT,
                    is_asset                 INTEGER NOT NULL DEFAULT 0,
                    glpi_status              TEXT NOT NULL DEFAULT 'N_A',
                    glpi_rejection_reason    TEXT,
                    glpi_status_updated_at   TEXT,
                    return_status            TEXT NOT NULL DEFAULT 'N_A',
                    return_rejection_reason  TEXT,
                    return_status_updated_at TEXT
                )""");
            stmt.executeUpdate("INSERT INTO NOTE_REPORT (id, created_at, profile_type) VALUES (1, '2026-01-01T10:00', 'ENTREGA')");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM (id, note_id, type_name, serial_number, a_f, quantity, is_asset,
                                        glpi_status, return_status)
                VALUES (1, 1, 'NOTEBOOK', 'SN1', 'AF-1', 1, 1, 'PENDING', 'N_A')""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM (id, note_id, type_name, serial_number, a_f, quantity, is_asset,
                                        glpi_status, return_status)
                VALUES (2, 1, 'NOTEBOOK', 'SN2', 'AF-2', 1, 1, 'N_A', 'PENDING')""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM (id, note_id, type_name, quantity, is_asset, glpi_status, return_status)
                VALUES (3, 1, 'MOUSE', 3, 0, 'N_A', 'N_A')""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM (id, note_id, type_name, quantity, is_asset, glpi_status, return_status)
                VALUES (4, 1, 'MOUSE', 5, 0, 'N_A', 'PENDING')""");

            invokeCreateEquipmentTables(stmt);
            invokeCreateHistoryTables(stmt);
            invokeMigrateSchema(c, stmt);

            assertFalse(hasColumn(c, "NOTE_ITEM", "is_asset"));
            assertFalse(hasColumn(c, "NOTE_ITEM", "serial_number"));
            assertFalse(hasColumn(c, "NOTE_ITEM", "return_status"));

            assertEquals(2, count(c, "NOTE_ITEM_ASSET"));
            assertEquals(2, count(c, "NOTE_ITEM_COUNTABLE"));
            assertEquals(1, count(c, "NOTE_ITEM_GLPI_TRACKING"));   // only item 1
            assertEquals(2, count(c, "NOTE_ITEM_RETURN_TRACKING")); // items 2 and 4

            // Regression: createHistoryTables() (invoked above, matching initialize()'s real
            // ordering) pre-creates these 4 tables before the rename runs, and SQLite's ALTER
            // TABLE RENAME auto-rewrites other tables' REFERENCES clauses — without dropping and
            // recreating them fresh inside migrateNoteItemSchema(), their FK silently ends up
            // pointing at the renamed-then-dropped NOTE_ITEM_OLD_20260722 instead of NOTE_ITEM.
            for (String subtypeTable : new String[]{
                    "NOTE_ITEM_ASSET", "NOTE_ITEM_COUNTABLE", "NOTE_ITEM_GLPI_TRACKING", "NOTE_ITEM_RETURN_TRACKING"}) {
                assertEquals("NOTE_ITEM", foreignKeyTarget(c, subtypeTable),
                    subtypeTable + "'s FK must point at NOTE_ITEM, not a renamed/dropped table");
            }

            assertEquals("PENDING", singleString(c,
                "SELECT status FROM NOTE_ITEM_GLPI_TRACKING WHERE item_id = 1"));
            assertEquals("SN1", singleString(c,
                "SELECT serial_number FROM NOTE_ITEM_ASSET WHERE item_id = 1"));
            assertEquals(3, singleInt(c,
                "SELECT quantity FROM NOTE_ITEM_COUNTABLE WHERE item_id = 3"));
            assertEquals("PENDING", singleString(c,
                "SELECT status FROM NOTE_ITEM_RETURN_TRACKING WHERE item_id = 4"));

            // Idempotent: re-running against an already-migrated database is a no-op, not an error
            // or a second copy of the data.
            invokeMigrateSchema(c, stmt);
            assertEquals(2, count(c, "NOTE_ITEM_ASSET"));
            assertEquals(2, count(c, "NOTE_ITEM_COUNTABLE"));
        }
    }

    // Reproduces a database from before MODEL.brand_type_id was made nullable, with a
    // duplicate "Genérico / Otro" row on each of two BRAND_TYPE_LINKs, and NOTE_ITEM (already
    // on the post-catalog-FK-redesign shape) pointing at each of them. Verifies
    // migrateGenericModelSchema() relaxes the column, collapses both per-link rows into one
    // global row, re-points both historical NOTE_ITEM references onto it, and deprecates
    // (never deletes) the old per-link rows — leaving a real, non-generic model untouched.
    @Test
    void migrateGenericModelSchemaCollapsesPerLinkGenericModelsIntoASingleGlobalRow() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("wide-generic-model.db").toAbsolutePath();

        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE TYPE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    is_asset INTEGER NOT NULL DEFAULT 1,
                    requires_serial INTEGER NOT NULL DEFAULT 0,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE BRAND (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE BRAND_TYPE_LINK (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type_id INTEGER NOT NULL REFERENCES TYPE(id),
                    brand_id INTEGER NOT NULL REFERENCES BRAND(id),
                    UNIQUE(type_id, brand_id)
                )""");
            // Old shape — brand_type_id NOT NULL — exactly what this migration must relax.
            stmt.executeUpdate("""
                CREATE TABLE MODEL (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    name TEXT NOT NULL,
                    deprecated INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REPORT (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    profile_type TEXT NOT NULL
                )""");
            // Already on the post-catalog-FK-redesign shape — migrateGenericModelSchema() always
            // runs after migrateCatalogFkSchema(), so by the time it sees NOTE_ITEM it's already
            // using model_id, never the old model_name text column.
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    note_id INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
                    type_id INTEGER NOT NULL REFERENCES TYPE(id),
                    brand_id INTEGER NOT NULL REFERENCES BRAND(id),
                    model_id INTEGER NOT NULL REFERENCES MODEL(id),
                    observations TEXT
                )""");

            stmt.executeUpdate("INSERT INTO TYPE (id, name) VALUES (1, 'NOTEBOOK')");
            stmt.executeUpdate("INSERT INTO TYPE (id, name) VALUES (2, 'MONITOR')");
            stmt.executeUpdate("INSERT INTO BRAND (id, name) VALUES (1, 'DELL')");
            stmt.executeUpdate("INSERT INTO BRAND_TYPE_LINK (id, type_id, brand_id) VALUES (1, 1, 1)");
            stmt.executeUpdate("INSERT INTO BRAND_TYPE_LINK (id, type_id, brand_id) VALUES (2, 2, 1)");
            stmt.executeUpdate("INSERT INTO MODEL (id, brand_type_id, name, deprecated) VALUES (10, 1, 'LATITUDE', 0)");
            stmt.executeUpdate("INSERT INTO MODEL (id, brand_type_id, name, deprecated) VALUES (11, 1, 'Genérico / Otro', 0)");
            stmt.executeUpdate("INSERT INTO MODEL (id, brand_type_id, name, deprecated) VALUES (12, 2, 'Genérico / Otro', 0)");
            stmt.executeUpdate("INSERT INTO NOTE_REPORT (id, created_at, profile_type) VALUES (1, '2026-01-01T10:00', 'ENTREGA')");
            stmt.executeUpdate("INSERT INTO NOTE_ITEM (id, note_id, type_id, brand_id, model_id) VALUES (1, 1, 1, 1, 11)");
            stmt.executeUpdate("INSERT INTO NOTE_ITEM (id, note_id, type_id, brand_id, model_id) VALUES (2, 1, 2, 1, 12)");

            invokeMigrateGenericModelSchema(c, stmt);

            assertFalse(isColumnNotNull(c, "MODEL", "brand_type_id"));

            assertEquals(1, singleInt(c, "SELECT COUNT(*) FROM MODEL WHERE brand_type_id IS NULL AND deprecated = 0"),
                "exactly one active global row must remain");
            int globalId = singleInt(c, "SELECT id FROM MODEL WHERE brand_type_id IS NULL AND deprecated = 0");

            assertEquals(1, singleInt(c,
                "SELECT COUNT(*) FROM MODEL WHERE brand_type_id IS NOT NULL AND LOWER(name) = LOWER('Genérico / Otro') AND deprecated = 1"),
                "the other per-link row must be deprecated, not deleted");

            assertEquals(globalId, singleInt(c, "SELECT model_id FROM NOTE_ITEM WHERE id = 1"));
            assertEquals(globalId, singleInt(c, "SELECT model_id FROM NOTE_ITEM WHERE id = 2"));

            assertEquals(0, singleInt(c, "SELECT deprecated FROM MODEL WHERE id = 10"),
                "a real, non-generic model must be untouched");
        }
    }

    // A BRAND_TYPE_LINK for the generic brand left over from before it stopped
    // needing one makes getBrandsForType() return it via a real JOIN for that one type — sorted
    // alphabetically among real brands — while every other type only shows it via client-side
    // synthesis (always appended last). cleanupStrayGenericBrandLinks() removes such a link, but
    // only once it has no MODEL rows left under it (they were already consolidated onto the
    // single global row by migrateGenericModelSchema()) — a link that still has a genuinely
    // different, deliberately-added model must be left alone.
    @Test
    void cleanupStrayGenericBrandLinksRemovesOnlyEmptyLinks() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("stray-generic-link.db").toAbsolutePath();

        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            invokeCreateEquipmentTables(stmt);

            stmt.executeUpdate("INSERT INTO TYPE (id, name) VALUES (1, 'NOTEBOOK')");
            stmt.executeUpdate("INSERT INTO TYPE (id, name) VALUES (2, 'MONITOR')");
            stmt.executeUpdate("INSERT INTO BRAND (id, name) VALUES (1, 'Genérico / Otro')");
            // Stray, empty link — no MODEL rows reference it — must be removed.
            stmt.executeUpdate("INSERT INTO BRAND_TYPE_LINK (id, type_id, brand_id) VALUES (1, 1, 1)");
            // Link with a genuinely different, deliberately-added model — must survive.
            stmt.executeUpdate("INSERT INTO BRAND_TYPE_LINK (id, type_id, brand_id) VALUES (2, 2, 1)");
            stmt.executeUpdate("INSERT INTO MODEL (id, brand_type_id, name) VALUES (1, 2, 'Modelo Especial')");

            invokeCleanupStrayGenericBrandLinks(c);

            assertEquals(0, singleInt(c, "SELECT COUNT(*) FROM BRAND_TYPE_LINK WHERE id = 1"),
                "the empty link must be removed");
            assertEquals(1, singleInt(c, "SELECT COUNT(*) FROM BRAND_TYPE_LINK WHERE id = 2"),
                "the link with a real model under it must survive");

            // Idempotent: re-running finds nothing left to clean up.
            invokeCleanupStrayGenericBrandLinks(c);
            assertEquals(1, singleInt(c, "SELECT COUNT(*) FROM BRAND_TYPE_LINK"));
        }
    }

    @Test
    void createUserRoleTableCreatesAppUserAndRolePermissionOnABrandNewDatabase() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("app-user-new.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            invokeCreateEquipmentTables(stmt);
            invokeCreateUserRoleTable(stmt);

            assertTrue(tableExists(c, "APP_USER"));
            assertTrue(tableExists(c, "ROLE_PERMISSION"));
            assertFalse(tableExists(c, "USER_ROLE"), "the old table name must not exist on a brand-new install");

            // Seeded once, on first creation: ADMIN gets everything except EDIT_SMTP_CONFIG,
            // SUPERADMIN gets everything.
            assertTrue(rowExists(c, "SELECT 1 FROM ROLE_PERMISSION WHERE role = 'ADMIN' AND permission = 'MANAGE_TYPES'"));
            assertFalse(rowExists(c, "SELECT 1 FROM ROLE_PERMISSION WHERE role = 'ADMIN' AND permission = 'EDIT_SMTP_CONFIG'"),
                "ADMIN must not be seeded with the SUPERADMIN-only SMTP permission");
            assertTrue(rowExists(c, "SELECT 1 FROM ROLE_PERMISSION WHERE role = 'SUPERADMIN' AND permission = 'EDIT_SMTP_CONFIG'"));
        }
    }

    @Test
    void createUserRoleTableMigratesExistingUserRoleDataAndDropsOldTable() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("app-user-migrate.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            invokeCreateEquipmentTables(stmt);
            // Reproduce a pre-existing installation's old USER_ROLE table, seeded the way a real
            // admin would via direct SQL.
            stmt.executeUpdate("""
                CREATE TABLE USER_ROLE (
                    username TEXT PRIMARY KEY,
                    role     TEXT NOT NULL
                )""");
            stmt.executeUpdate("INSERT INTO USER_ROLE (username, role) VALUES ('jperez', 'ADMIN')");

            invokeCreateUserRoleTable(stmt);

            assertFalse(tableExists(c, "USER_ROLE"), "the old table must be dropped after migrating");
            assertEquals("ADMIN", singleString(c, "SELECT role FROM APP_USER WHERE username = 'jperez'"));
            assertTrue(rowExists(c, "SELECT 1 FROM APP_USER WHERE username = 'jperez' AND sede_id IS NULL"),
                "a migrated row starts with no Sede assigned — that's a new, separate concept a superadmin must set by hand");
        }
    }

    @Test
    void createUserRoleTableIsIdempotentAndNeverResetsPermissionsAfterFirstCreation() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("app-user-idempotent.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            invokeCreateEquipmentTables(stmt);
            invokeCreateUserRoleTable(stmt);

            // Simulate a superadmin revoking a permission by hand, then simulate the next app
            // startup re-running this same method — the revocation must survive.
            stmt.executeUpdate("DELETE FROM ROLE_PERMISSION WHERE role = 'ADMIN' AND permission = 'MANAGE_TYPES'");
            invokeCreateUserRoleTable(stmt);

            assertFalse(rowExists(c, "SELECT 1 FROM ROLE_PERMISSION WHERE role = 'ADMIN' AND permission = 'MANAGE_TYPES'"),
                "re-running createUserRoleTable() must not silently re-seed a revoked permission");
        }
    }

    @Test
    void createEquipmentTablesCreatesModelStockTable() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("model-stock-table.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            invokeCreateEquipmentTables(stmt);
            assertTrue(tableExists(c, "MODEL_STOCK"));
        }
    }

    @Test
    void createEquipmentTablesCreatesSedeShippingInfoTable() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("sede-shipping-info-table.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            invokeCreateEquipmentTables(stmt);
            assertTrue(tableExists(c, "SEDE_SHIPPING_INFO"));
        }
    }

    @Test
    void createHistoryTablesCreatesNoteRemitoTable() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("note-remito-table.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            invokeCreateEquipmentTables(stmt);
            invokeCreateHistoryTables(stmt);
            assertTrue(tableExists(c, "NOTE_REMITO"));
        }
    }

    // MODEL_STOCK gained sede_id as part of its primary key — an already-running installation's
    // old-shape table (no sede_id, one global number per Model) has no correct way to attribute
    // its existing numbers to any one Sede, so the explicit user decision was to reset: drop the
    // old table, recreate it in the new shape, every (model, Sede) pair starts at 0.
    @Test
    void migrateModelStockSedeSchemaResetsOldShapeTableToZero() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("model-stock-sede-migration.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            invokeCreateEquipmentTables(stmt);
            assertTrue(hasColumn(c, "MODEL_STOCK", "sede_id"),
                "a brand-new install must already have the new shape, straight from createEquipmentTables()");

            // Simulate a pre-existing installation still on the old (no sede_id) shape.
            stmt.executeUpdate("DROP TABLE MODEL_STOCK");
            stmt.executeUpdate("""
                CREATE TABLE MODEL_STOCK (
                    brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                    stock         INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (brand_type_id, model_id)
                )""");
            stmt.executeUpdate("INSERT INTO TYPE (id, name) VALUES (1, 'NOTEBOOK')");
            stmt.executeUpdate("INSERT INTO BRAND (id, name) VALUES (1, 'DELL')");
            stmt.executeUpdate("INSERT INTO BRAND_TYPE_LINK (id, type_id, brand_id) VALUES (1, 1, 1)");
            stmt.executeUpdate("INSERT INTO MODEL (id, brand_type_id, name) VALUES (1, 1, 'LATITUDE')");
            stmt.executeUpdate("INSERT INTO MODEL_STOCK (brand_type_id, model_id, stock) VALUES (1, 1, 25)");
            assertFalse(hasColumn(c, "MODEL_STOCK", "sede_id"), "old shape confirmed before migrating");

            invokeMigrateModelStockSedeSchema(c, stmt);

            assertTrue(hasColumn(c, "MODEL_STOCK", "sede_id"),
                "the table must be on the new (sede_id) shape after migration");
            assertEquals(0, singleInt(c, "SELECT COUNT(*) FROM MODEL_STOCK"),
                "old-shape numbers are reset, not preserved or guessed at — explicit user decision");
        }
    }

    // Re-running against an already-migrated (new-shape) table must be a no-op — never drop and
    // re-create a table that's already correct, or a real installation's stock would be silently
    // wiped on every single startup.
    @Test
    void migrateModelStockSedeSchemaIsNoOpOnAnAlreadyMigratedTable() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("model-stock-sede-idempotent.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            invokeCreateEquipmentTables(stmt);
            stmt.executeUpdate("INSERT INTO TYPE (id, name) VALUES (1, 'NOTEBOOK')");
            stmt.executeUpdate("INSERT INTO BRAND (id, name) VALUES (1, 'DELL')");
            stmt.executeUpdate("INSERT INTO SEDE (id, name) VALUES (1, 'Campus Norte')");
            stmt.executeUpdate("INSERT INTO BRAND_TYPE_LINK (id, type_id, brand_id) VALUES (1, 1, 1)");
            stmt.executeUpdate("INSERT INTO MODEL (id, brand_type_id, name) VALUES (1, 1, 'LATITUDE')");
            stmt.executeUpdate("INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (1, 1, 1, 25)");

            invokeMigrateModelStockSedeSchema(c, stmt);

            assertEquals(25, singleInt(c, "SELECT stock FROM MODEL_STOCK WHERE brand_type_id = 1 AND model_id = 1 AND sede_id = 1"),
                "already-current data must survive a re-run untouched");
        }
    }

    // A link with zero MODEL rows can still carry a legitimate MODEL_STOCK row for the global
    // generic model (stock is tracked per (Type,Brand) usage of it, independent of whether that
    // usage has any of its own MODEL rows) — cleanupStrayGenericBrandLinks() must not delete it.
    @Test
    void cleanupStrayGenericBrandLinksSkipsLinkWithOnlyStockNoModels() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("stray-generic-link-with-stock.db").toAbsolutePath();

        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            invokeCreateEquipmentTables(stmt);

            stmt.executeUpdate("INSERT INTO TYPE (id, name) VALUES (1, 'NOTEBOOK')");
            stmt.executeUpdate("INSERT INTO BRAND (id, name) VALUES (1, 'Genérico / Otro')");
            stmt.executeUpdate("INSERT INTO SEDE (id, name) VALUES (1, 'Campus Norte')");
            // No MODEL rows under this link at all, but it does carry a stock number — must survive.
            stmt.executeUpdate("INSERT INTO BRAND_TYPE_LINK (id, type_id, brand_id) VALUES (1, 1, 1)");
            stmt.executeUpdate("INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (1, 999, 1, 14)");

            invokeCleanupStrayGenericBrandLinks(c);

            assertEquals(1, singleInt(c, "SELECT COUNT(*) FROM BRAND_TYPE_LINK WHERE id = 1"),
                "a link with a MODEL_STOCK row (even with zero MODEL rows) must survive");
        }
    }

    // NOTE_REMITO.stock_applied used to be its own idempotency flag, only for Remito notes.
    // Once every note type started moving stock on approval, "has this note's stock effect
    // already been applied" became a universal NOTE_REPORT-level fact instead — see
    // SqliteHistoryService.applyNoteStockIfNeeded(). Reproduces an installation with one
    // already-approved-and-stock-applied Remito (plus one never-applied Remito, to confirm the
    // backfill doesn't false-positive) to confirm migrateSchema() backfills the flag onto
    // NOTE_REPORT.stock_applied and drops the old NOTE_REMITO column, not just skips re-adding it
    // on a fresh install.
    @Test
    void migrateSchemaBackfillsStockAppliedFromNoteRemitoAndDropsColumn() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("dead-remito-stock-applied.db").toAbsolutePath();

        try (Connection c = DriverManager.getConnection(url); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REPORT (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at   TEXT NOT NULL,
                    profile_type TEXT NOT NULL
                )""");
            stmt.executeUpdate("INSERT INTO NOTE_REPORT (id, created_at, profile_type) VALUES (1, '2026-01-01T10:00', 'REMITO DE ENVÍO')");
            stmt.executeUpdate("INSERT INTO NOTE_REPORT (id, created_at, profile_type) VALUES (2, '2026-01-02T10:00', 'REMITO DE ENVÍO')");
            // Old shape — stock_applied lived on NOTE_REMITO itself, before every note type
            // started moving stock and the flag was consolidated onto NOTE_REPORT.
            stmt.executeUpdate("""
                CREATE TABLE NOTE_REMITO (
                    note_report_id      INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    destination_sede_id INTEGER,
                    destination_label   TEXT NOT NULL,
                    address             TEXT,
                    recipients          TEXT,
                    stock_applied       INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_REMITO (note_report_id, destination_label, stock_applied)
                VALUES (1, 'CAU Recoleta', 1)""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_REMITO (note_report_id, destination_label, stock_applied)
                VALUES (2, 'CAU Palermo', 0)""");

            // createHistoryTables()'s own NOTE_REMITO is CREATE TABLE IF NOT EXISTS — since the
            // old-shape table above already exists, this is a no-op and the old shape survives
            // until migrateSchema() itself backfills and drops the column.
            invokeCreateEquipmentTables(stmt);
            invokeCreateHistoryTables(stmt);
            invokeMigrateSchema(c, stmt);

            assertTrue(hasColumn(c, "NOTE_REPORT", "stock_applied"));
            assertFalse(hasColumn(c, "NOTE_REMITO", "stock_applied"));
            assertEquals(1, singleInt(c, "SELECT stock_applied FROM NOTE_REPORT WHERE id = 1"),
                "backfilled from the already-applied Remito row");
            assertEquals(0, singleInt(c, "SELECT stock_applied FROM NOTE_REPORT WHERE id = 2"),
                "an unapplied Remito row must not be backfilled to 1");
        }
    }

    private void invokeCreateUserRoleTable(Statement stmt) throws Exception {
        Method m = DatabaseService.class.getDeclaredMethod("createUserRoleTable", Statement.class);
        m.setAccessible(true);
        m.invoke(DatabaseService.getInstance(), stmt);
    }

    private void invokeCleanupStrayGenericBrandLinks(Connection c) throws Exception {
        Method m = DatabaseService.class.getDeclaredMethod("cleanupStrayGenericBrandLinks", Connection.class);
        m.setAccessible(true);
        m.invoke(DatabaseService.getInstance(), c);
    }

    private boolean isColumnNotNull(Connection c, String table, String column) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return rs.getInt("notnull") == 1;
            }
            return false;
        }
    }

    private void invokeMigrateGenericModelSchema(Connection c, Statement stmt) throws Exception {
        Method m = DatabaseService.class.getDeclaredMethod("migrateGenericModelSchema", Connection.class, Statement.class);
        m.setAccessible(true);
        m.invoke(DatabaseService.getInstance(), c, stmt);
    }

    private void invokeMigrateModelStockSedeSchema(Connection c, Statement stmt) throws Exception {
        Method m = DatabaseService.class.getDeclaredMethod("migrateModelStockSedeSchema", Connection.class, Statement.class);
        m.setAccessible(true);
        m.invoke(DatabaseService.getInstance(), c, stmt);
    }

    private void invokeMigrateSchema(Connection c, Statement stmt) throws Exception {
        Method migrateSchema = DatabaseService.class.getDeclaredMethod("migrateSchema", Connection.class, Statement.class);
        migrateSchema.setAccessible(true);
        migrateSchema.invoke(DatabaseService.getInstance(), c, stmt);
    }

    private void invokeCreateHistoryTables(Statement stmt) throws Exception {
        Method createHistoryTables = DatabaseService.class.getDeclaredMethod("createHistoryTables", Statement.class);
        createHistoryTables.setAccessible(true);
        createHistoryTables.invoke(DatabaseService.getInstance(), stmt);
    }

    // migrateSchema() now also backfills a catalog-FK-based NOTE_ITEM shape and a per-link
    // "Genérico / Otro" MODEL row, both of which assume TYPE/BRAND/BRAND_TYPE_LINK/MODEL/PROVIDER
    // already exist — exactly as they always do in the real initialize() call order
    // (createEquipmentTables() runs before migrateSchema()). Both fixtures below only build
    // NOTE_*/TYPE tables by hand, so this must be called too, matching production's real sequence
    // — same lesson already documented above for invokeCreateHistoryTables().
    private void invokeCreateEquipmentTables(Statement stmt) throws Exception {
        Method createEquipmentTables = DatabaseService.class.getDeclaredMethod("createEquipmentTables", Statement.class);
        createEquipmentTables.setAccessible(true);
        createEquipmentTables.invoke(DatabaseService.getInstance(), stmt);
    }

    private boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
            return false;
        }
    }

    private boolean tableExists(Connection c, String table) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'")) {
            return rs.next();
        }
    }

    private boolean rowExists(Connection c, String sql) throws SQLException {
        try (Statement stmt = c.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next();
        }
    }

    private int count(Connection c, String table) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String singleString(Connection c, String sql) throws SQLException {
        try (Statement stmt = c.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private String foreignKeyTarget(Connection c, String table) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
            assertTrue(rs.next(), table + " has no foreign key at all");
            return rs.getString("table");
        }
    }

    private int singleInt(Connection c, String sql) throws SQLException {
        try (Statement stmt = c.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
