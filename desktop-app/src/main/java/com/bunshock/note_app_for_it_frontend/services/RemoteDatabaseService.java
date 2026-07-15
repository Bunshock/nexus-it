package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class RemoteDatabaseService {

    private static RemoteDatabaseService instance;

    private String host;
    private int port;
    private String dbName;
    private String username;
    private String password;

    private RemoteDatabaseService() {}

    public static RemoteDatabaseService getInstance() {
        if (instance == null) instance = new RemoteDatabaseService();
        return instance;
    }

    public void configure(String host, int port, String dbName, String username, String password) {
        this.host = host;
        this.port = port;
        this.dbName = dbName;
        this.username = username;
        this.password = password;
    }

    public boolean isConfigured() {
        return host != null && !host.isBlank();
    }

    public Connection getConnection() throws SQLException {
        if (!isConfigured()) throw new IllegalStateException("Remote DB not configured");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;
        return DriverManager.getConnection(url, username, password);
    }

    public boolean testConnection() {
        if (!isConfigured()) return false;
        return testConnection(host, port, dbName, username, password);
    }

    public boolean testConnection(String host, int port, String dbName, String username, String password) {
        if (host == null || host.isBlank()) return false;
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;
        try (Connection c = DriverManager.getConnection(url, username, password)) {
            c.createStatement().execute("SELECT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void ensureSchema() throws SQLException {
        try (Connection c = getConnection(); Statement stmt = c.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS TYPE (
                    id              SERIAL PRIMARY KEY,
                    name            TEXT NOT NULL UNIQUE,
                    is_asset        INTEGER NOT NULL DEFAULT 1,
                    requires_serial INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS BRAND (
                    id   SERIAL PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE
                )""");
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS BRAND_TYPE_LINK (
                    id       SERIAL PRIMARY KEY,
                    type_id  INTEGER NOT NULL REFERENCES TYPE(id),
                    brand_id INTEGER NOT NULL REFERENCES BRAND(id),
                    UNIQUE(type_id, brand_id)
                )""");
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS MODEL (
                    id            SERIAL PRIMARY KEY,
                    brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    name          TEXT NOT NULL
                )""");
            // Wrapped locally: an existing database with pre-existing duplicate (brand_type_id,
            // name) rows would fail this statement — swallow it and degrade to app-layer-only
            // enforcement (SqliteEquipmentService.addModel/renameModel) rather than aborting the
            // whole ensureSchema() run, same "fail safely" pattern as the ALTER...ADD COLUMN calls below.
            try {
                stmt.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_model_brand_type_name ON MODEL(brand_type_id, name)");
            } catch (SQLException duplicatesExist) {
                // pre-existing duplicate model names for the same brand+type — see comment above
            }
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS SN_VALIDATION (
                    id            SERIAL PRIMARY KEY,
                    model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                    regex_pattern TEXT,
                    description   TEXT,
                    is_active     INTEGER NOT NULL DEFAULT 1
                )""");
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS PROVIDER (
                    id   SERIAL PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE
                )""");
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS TECHNICIAN_PROFILE (
                    id               SERIAL PRIMARY KEY,
                    windows_username TEXT NOT NULL UNIQUE,
                    name             TEXT,
                    dni              TEXT,
                    email            TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS NOTE_REPORT (
                    id              SERIAL PRIMARY KEY,
                    created_at      TEXT NOT NULL,
                    profile_type    TEXT NOT NULL,
                    glpi_synced     INTEGER NOT NULL DEFAULT 0,
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
                    id                     SERIAL PRIMARY KEY,
                    note_id                INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
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

            // CREATE TABLE IF NOT EXISTS silently no-ops on a database that already has the
            // table from an older schema version, so columns added later must be migrated here too.
            boolean hadRequiresSerial = columnExists(c, "type", "requires_serial");
            stmt.executeUpdate("ALTER TABLE TYPE ADD COLUMN IF NOT EXISTS requires_serial INTEGER NOT NULL DEFAULT 0");
            if (!hadRequiresSerial) {
                // Backfill the type that used to be hardcoded as "always requires S/N"
                // (ItemDialogController's old "Notebook".equals(...) check) so existing
                // databases keep today's behavior instead of silently losing the rule.
                stmt.executeUpdate("UPDATE TYPE SET requires_serial = 1 WHERE LOWER(name) = 'notebook'");
            }
            stmt.executeUpdate("ALTER TABLE NOTE_ENTREGA_DEVOLUCION ADD COLUMN IF NOT EXISTS failure_cause TEXT");
            stmt.executeUpdate("ALTER TABLE NOTE_ENTREGA_DEVOLUCION ADD COLUMN IF NOT EXISTS failure_details TEXT");
            stmt.executeUpdate("ALTER TABLE NOTE_PROVEEDOR ADD COLUMN IF NOT EXISTS responsible_name TEXT");
            stmt.executeUpdate("ALTER TABLE NOTE_PROVEEDOR ADD COLUMN IF NOT EXISTS responsible_dni TEXT");
            stmt.executeUpdate("ALTER TABLE NOTE_REPORT ADD COLUMN IF NOT EXISTS technician_name TEXT");
            stmt.executeUpdate("ALTER TABLE NOTE_REPORT ADD COLUMN IF NOT EXISTS technician_dni TEXT");
            stmt.executeUpdate("ALTER TABLE NOTE_ITEM ADD COLUMN IF NOT EXISTS is_asset INTEGER NOT NULL DEFAULT 0");
            stmt.executeUpdate("ALTER TABLE NOTE_ITEM ADD COLUMN IF NOT EXISTS glpi_status TEXT NOT NULL DEFAULT 'N_A'");
            stmt.executeUpdate("ALTER TABLE NOTE_ITEM ADD COLUMN IF NOT EXISTS glpi_rejection_reason TEXT");
            stmt.executeUpdate("ALTER TABLE NOTE_ITEM ADD COLUMN IF NOT EXISTS glpi_status_updated_at TEXT");
        }
    }

    private boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE lower(table_name) = ? AND lower(column_name) = ?")) {
            ps.setString(1, table.toLowerCase());
            ps.setString(2, column.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
