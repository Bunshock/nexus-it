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
            insertDefaultData(stmt);
        }
    }

    private void createEquipmentTables(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS TYPE (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                is_asset INTEGER NOT NULL DEFAULT 1
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

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS SN_VALIDATION (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                regex_pattern TEXT,
                description   TEXT,
                is_active     INTEGER NOT NULL DEFAULT 1
            )""");
    }

    private void createHistoryTables(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_REPORT (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at   TEXT NOT NULL,
                profile_type TEXT NOT NULL,
                glpi_synced  INTEGER NOT NULL DEFAULT 0,
                technician_id INTEGER REFERENCES TECHNICIAN_PROFILE(id)
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ENTREGA_DEVOLUCION (
                note_report_id INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                user_name      TEXT,
                user_dni       TEXT,
                user_email     TEXT,
                motivo         TEXT
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_PROVEEDOR (
                note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                provider_name   TEXT,
                cuit            TEXT,
                motivo          TEXT
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                note_id       INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
                type_name     TEXT NOT NULL,
                brand_name    TEXT,
                model_name    TEXT,
                serial_number TEXT,
                a_f           TEXT,
                quantity      INTEGER NOT NULL DEFAULT 1,
                observations  TEXT
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
        // Protect the Generic brand — insert only if it doesn't exist
        stmt.executeUpdate("""
            INSERT OR IGNORE INTO BRAND (name) VALUES ('Generic')
            """);
    }
}
