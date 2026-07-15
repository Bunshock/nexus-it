package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseService {

    private static DatabaseService instance;
    private static final String DB_URL = "jdbc:sqlite:data/noteapp.db";

    private DatabaseService() {}

    public static DatabaseService getInstance() {
        if (instance == null) {
            instance = new DatabaseService();
        }
        return instance;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    public void initialize() throws SQLException {
        new java.io.File("data").mkdirs();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("PRAGMA foreign_keys = ON");
            createEquipmentTables(stmt);
            createHistoryTables(stmt);
            createTechnicianTable(stmt);
            createSettingsTable(stmt);
            migrateSchema(stmt);
            insertDefaultData(stmt);
        }
    }

    // CREATE TABLE IF NOT EXISTS silently no-ops on a database that already has the
    // table from an older schema version, so newly added columns never land on disk —
    // each column added after the initial release must be migrated in here too.
    private void migrateSchema(Statement stmt) {
        addColumnIfMissing(stmt, "NOTE_ENTREGA_DEVOLUCION", "failure_cause", "TEXT");
        addColumnIfMissing(stmt, "NOTE_ENTREGA_DEVOLUCION", "failure_details", "TEXT");
        addColumnIfMissing(stmt, "NOTE_PROVEEDOR", "responsible_name", "TEXT");
        addColumnIfMissing(stmt, "NOTE_PROVEEDOR", "responsible_dni", "TEXT");
        addColumnIfMissing(stmt, "NOTE_REPORT", "technician_name", "TEXT");
        addColumnIfMissing(stmt, "NOTE_REPORT", "technician_dni", "TEXT");

        // Column just introduced — backfill the type that used to be hardcoded as
        // "always requires S/N" (ItemDialogController's old "Notebook".equals(...) check)
        // so existing databases keep today's behavior instead of silently losing the rule.
        if (addColumnIfMissing(stmt, "TYPE", "requires_serial", "INTEGER NOT NULL DEFAULT 0")) {
            try {
                stmt.executeUpdate("UPDATE TYPE SET requires_serial = 1 WHERE LOWER(name) = 'notebook'");
            } catch (SQLException ignored) {
                // no TYPE rows yet on a brand-new database — nothing to backfill
            }
        }
    }

    private boolean addColumnIfMissing(Statement stmt, String table, String column, String type) {
        try {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            return true;
        } catch (SQLException alreadyExists) {
            // column already present from a prior run — nothing to do
            return false;
        }
    }

    private void createEquipmentTables(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS TYPE (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                is_asset INTEGER NOT NULL DEFAULT 1,
                requires_serial INTEGER NOT NULL DEFAULT 0
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS BRAND (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS BRAND_TYPE_LINK (
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                type_id  INTEGER NOT NULL REFERENCES TYPE(id),
                brand_id INTEGER NOT NULL REFERENCES BRAND(id),
                UNIQUE(type_id, brand_id)
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS MODEL (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                name          TEXT NOT NULL
            )""");

        // No table rebuild needed for this constraint (SQLite can't ALTER TABLE ADD CONSTRAINT) —
        // a unique index enforces it just as well. Wrapped locally: an existing database that
        // already has accidental duplicate (brand_type_id, name) rows would fail this statement:
        // swallow it and degrade to app-layer-only enforcement (SqliteEquipmentService.addModel/
        // renameModel) rather than blocking startup — same "fail safely" pattern as addColumnIfMissing.
        try {
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_model_brand_type_name ON MODEL(brand_type_id, name)");
        } catch (SQLException duplicatesExist) {
            // pre-existing duplicate model names for the same brand+type — see comment above
        }

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS SN_VALIDATION (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                regex_pattern TEXT,
                description   TEXT,
                is_active     INTEGER NOT NULL DEFAULT 1
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS PROVIDER (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE
            )""");
    }

    private void createHistoryTables(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_REPORT (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at      TEXT NOT NULL,
                profile_type    TEXT NOT NULL,
                technician_id   INTEGER REFERENCES TECHNICIAN_PROFILE(id),
                technician_name TEXT,
                technician_dni  TEXT
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ENTREGA_DEVOLUCION (
                note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                user_name       TEXT,
                user_dni        TEXT,
                user_email      TEXT,
                motivo          TEXT,
                failure_cause   TEXT,
                failure_details TEXT
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_PROVEEDOR (
                note_report_id   INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                provider_name    TEXT,
                cuit             TEXT,
                motivo           TEXT,
                responsible_name TEXT,
                responsible_dni  TEXT
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM (
                id                      INTEGER PRIMARY KEY AUTOINCREMENT,
                note_id                 INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
                type_name               TEXT NOT NULL,
                brand_name              TEXT,
                model_name              TEXT,
                serial_number           TEXT,
                a_f                     TEXT,
                quantity                INTEGER NOT NULL DEFAULT 1,
                observations            TEXT,
                is_asset                INTEGER NOT NULL DEFAULT 0,
                glpi_status             TEXT NOT NULL DEFAULT 'N_A',
                glpi_rejection_reason   TEXT,
                glpi_status_updated_at  TEXT
            )""");
    }

    private void createTechnicianTable(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS TECHNICIAN_PROFILE (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                windows_username TEXT NOT NULL UNIQUE,
                name             TEXT,
                dni              TEXT,
                email            TEXT
            )""");
    }

    private void createSettingsTable(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS APP_SETTINGS (
                key   TEXT PRIMARY KEY,
                value TEXT
            )""");
    }

    private void insertDefaultData(Statement stmt) throws SQLException {
        stmt.executeUpdate("INSERT OR IGNORE INTO BRAND (name) VALUES ('Generic')");
    }

}
