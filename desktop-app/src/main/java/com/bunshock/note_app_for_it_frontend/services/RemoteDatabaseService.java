package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.DriverManager;
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
        try (Connection c = getConnection()) {
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
                    id       SERIAL PRIMARY KEY,
                    name     TEXT NOT NULL UNIQUE,
                    is_asset INTEGER NOT NULL DEFAULT 1
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
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS SN_VALIDATION (
                    id            SERIAL PRIMARY KEY,
                    model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                    regex_pattern TEXT,
                    description   TEXT,
                    is_active     INTEGER NOT NULL DEFAULT 1
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
                    id            SERIAL PRIMARY KEY,
                    created_at    TEXT NOT NULL,
                    profile_type  TEXT NOT NULL,
                    glpi_synced   INTEGER NOT NULL DEFAULT 0,
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
                    note_report_id INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    provider_name  TEXT,
                    cuit           TEXT,
                    motivo         TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS NOTE_ITEM (
                    id            SERIAL PRIMARY KEY,
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
    }
}
