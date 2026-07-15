package com.bunshock.note_app_for_it_frontend.utils;

import com.bunshock.note_app_for_it_frontend.services.RemoteDatabaseService;

import java.io.Console;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * Developer/admin one-off utility — copies the equipment catalog (Type, Brand, Brand-Type
 * links, Model, S/N validation rules, Provider) from the local SQLite database
 * (data/noteapp.db) into a PostgreSQL database, remapping autoincrement ids along the way.
 * Postgres assigns its own SERIAL ids on insert; SQLite's original ids can't be copied as-is
 * without risking collisions with rows Postgres already has, and would leave its sequence
 * counters stale for future inserts (see CLAUDE.md's remote-database migration notes).
 *
 * Use this to build up the real catalog locally through the app's own "Base de Datos" UI first
 * — while a remote PostgreSQL server isn't available yet — then run this script once one is.
 * It creates the PostgreSQL schema itself (reuses RemoteDatabaseService.ensureSchema(), the
 * same call the app makes on startup once db_host is configured), so an empty, freshly created
 * PostgreSQL database is all that's required on the other end — see
 * database/postgresql/README.md for how to provision one.
 *
 * Safe to re-run: every table is migrated with an upsert (ON CONFLICT ... RETURNING id), so
 * running this again after adding more local data only inserts what's new — existing rows are
 * matched by their unique name (or, for MODEL, brand_type_id + name) and their other columns
 * (is_asset, requires_serial) are refreshed to match the local values. S/N_VALIDATION rows are
 * always replaced outright (no unique constraint of their own to upsert against).
 *
 * History (NOTE_REPORT and related tables) is deliberately NOT migrated by this script — only
 * the equipment catalog, which is what an admin typically wants to prepare ahead of time.
 * History rows store type/brand/model as plain denormalized text on NOTE_ITEM, not catalog
 * foreign keys (the same "snapshot, don't reference" pattern documented in CLAUDE.md), so they
 * don't depend on catalog ids and aren't covered here.
 *
 * Usage (from desktop-app/):
 *   mvn exec:java -Dexec.mainClass="com.bunshock.note_app_for_it_frontend.utils.CatalogMigrationTool"
 *
 * Prompts interactively for the PostgreSQL host/port/database/username/password — nothing is
 * read from or written to APP_SETTINGS, so this works whether or not the app itself has been
 * pointed at the remote database yet.
 */
public class CatalogMigrationTool {

    private static final String SQLITE_URL = "jdbc:sqlite:data/noteapp.db";

    public static void main(String[] args) throws Exception {
        System.out.println("=== Catalog migration: local SQLite -> remote PostgreSQL ===\n");

        Scanner scanner = new Scanner(System.in);
        String host = prompt(scanner, "Postgres host: ");
        String portInput = prompt(scanner, "Postgres port [5432]: ");
        int port = portInput.isBlank() ? 5432 : Integer.parseInt(portInput);
        String dbName = prompt(scanner, "Database name: ");
        String username = prompt(scanner, "Username: ");
        String password = promptPassword(scanner, "Password: ");

        RemoteDatabaseService remote = RemoteDatabaseService.getInstance();
        remote.configure(host, port, dbName, username, password);

        System.out.println("\nVerifying connection...");
        if (!remote.testConnection()) {
            System.err.println("Could not connect to PostgreSQL with these details. Aborting — nothing was migrated.");
            System.exit(1);
        }

        System.out.println("Connected. Ensuring schema exists...");
        remote.ensureSchema();

        try (Connection sqlite = DriverManager.getConnection(SQLITE_URL);
             Connection pg = remote.getConnection()) {

            pg.setAutoCommit(false);
            try {
                Map<Integer, Integer> typeIds = migrateTypes(sqlite, pg);
                Map<Integer, Integer> brandIds = migrateBrands(sqlite, pg);
                Map<Integer, Integer> linkIds = migrateBrandTypeLinks(sqlite, pg, typeIds, brandIds);
                Map<Integer, Integer> modelIds = migrateModels(sqlite, pg, linkIds);
                migrateSnValidations(sqlite, pg, modelIds);
                migrateProviders(sqlite, pg);
                pg.commit();
                System.out.println("\nMigration complete.");
            } catch (Exception e) {
                pg.rollback();
                System.err.println("\nMigration failed, rolled back — nothing was changed on the remote database: " + e.getMessage());
                throw e;
            }
        }
    }

    static Map<Integer, Integer> migrateTypes(Connection sqlite, Connection pg) throws SQLException {
        Map<Integer, Integer> idMap = new HashMap<>();
        String upsert = """
            INSERT INTO TYPE (name, is_asset, requires_serial) VALUES (?, ?, ?)
            ON CONFLICT (name) DO UPDATE SET is_asset = EXCLUDED.is_asset, requires_serial = EXCLUDED.requires_serial
            RETURNING id
            """;
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name, is_asset, requires_serial FROM TYPE");
             PreparedStatement ps = pg.prepareStatement(upsert)) {
            int count = 0;
            while (rs.next()) {
                ps.setString(1, rs.getString("name"));
                ps.setInt(2, rs.getInt("is_asset"));
                ps.setInt(3, rs.getInt("requires_serial"));
                try (ResultSet keys = ps.executeQuery()) {
                    keys.next();
                    idMap.put(rs.getInt("id"), keys.getInt(1));
                }
                count++;
            }
            System.out.println("Types: " + count + " migrated.");
        }
        return idMap;
    }

    static Map<Integer, Integer> migrateBrands(Connection sqlite, Connection pg) throws SQLException {
        Map<Integer, Integer> idMap = new HashMap<>();
        String upsert = """
            INSERT INTO BRAND (name) VALUES (?)
            ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
            RETURNING id
            """;
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name FROM BRAND");
             PreparedStatement ps = pg.prepareStatement(upsert)) {
            int count = 0;
            while (rs.next()) {
                ps.setString(1, rs.getString("name"));
                try (ResultSet keys = ps.executeQuery()) {
                    keys.next();
                    idMap.put(rs.getInt("id"), keys.getInt(1));
                }
                count++;
            }
            System.out.println("Brands: " + count + " migrated.");
        }
        return idMap;
    }

    static Map<Integer, Integer> migrateBrandTypeLinks(Connection sqlite, Connection pg,
            Map<Integer, Integer> typeIds, Map<Integer, Integer> brandIds) throws SQLException {
        Map<Integer, Integer> idMap = new HashMap<>();
        String upsert = """
            INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?)
            ON CONFLICT (type_id, brand_id) DO UPDATE SET type_id = EXCLUDED.type_id
            RETURNING id
            """;
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, type_id, brand_id FROM BRAND_TYPE_LINK");
             PreparedStatement ps = pg.prepareStatement(upsert)) {
            int count = 0;
            while (rs.next()) {
                int oldId = rs.getInt("id");
                Integer newTypeId = typeIds.get(rs.getInt("type_id"));
                Integer newBrandId = brandIds.get(rs.getInt("brand_id"));
                if (newTypeId == null || newBrandId == null) {
                    System.err.println("Skipping BRAND_TYPE_LINK id=" + oldId + " — its type or brand wasn't migrated.");
                    continue;
                }
                ps.setInt(1, newTypeId);
                ps.setInt(2, newBrandId);
                try (ResultSet keys = ps.executeQuery()) {
                    keys.next();
                    idMap.put(oldId, keys.getInt(1));
                }
                count++;
            }
            System.out.println("Brand-Type links: " + count + " migrated.");
        }
        return idMap;
    }

    static Map<Integer, Integer> migrateModels(Connection sqlite, Connection pg,
            Map<Integer, Integer> linkIds) throws SQLException {
        Map<Integer, Integer> idMap = new HashMap<>();
        String upsert = """
            INSERT INTO MODEL (brand_type_id, name) VALUES (?, ?)
            ON CONFLICT (brand_type_id, name) DO UPDATE SET name = EXCLUDED.name
            RETURNING id
            """;
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, brand_type_id, name FROM MODEL");
             PreparedStatement ps = pg.prepareStatement(upsert)) {
            int count = 0;
            while (rs.next()) {
                int oldId = rs.getInt("id");
                Integer newLinkId = linkIds.get(rs.getInt("brand_type_id"));
                if (newLinkId == null) {
                    System.err.println("Skipping MODEL id=" + oldId + " — its brand-type link wasn't migrated.");
                    continue;
                }
                ps.setInt(1, newLinkId);
                ps.setString(2, rs.getString("name"));
                try (ResultSet keys = ps.executeQuery()) {
                    keys.next();
                    idMap.put(oldId, keys.getInt(1));
                }
                count++;
            }
            System.out.println("Models: " + count + " migrated.");
        }
        return idMap;
    }

    static void migrateSnValidations(Connection sqlite, Connection pg,
            Map<Integer, Integer> modelIds) throws SQLException {
        // No unique constraint of its own to upsert against (one row per model_id is enforced
        // app-side, in SqliteEquipmentService.upsertSnValidation, not by the schema) — delete
        // any existing row for the target model first so re-running this script replaces rather
        // than duplicates.
        String delete = "DELETE FROM SN_VALIDATION WHERE model_id = ?";
        String insert = "INSERT INTO SN_VALIDATION (model_id, regex_pattern, description, is_active) VALUES (?, ?, ?, ?)";
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT model_id, regex_pattern, description, is_active FROM SN_VALIDATION");
             PreparedStatement del = pg.prepareStatement(delete);
             PreparedStatement ins = pg.prepareStatement(insert)) {
            int count = 0;
            while (rs.next()) {
                Integer newModelId = modelIds.get(rs.getInt("model_id"));
                if (newModelId == null) {
                    System.err.println("Skipping S/N validation for model_id=" + rs.getInt("model_id") + " — model wasn't migrated.");
                    continue;
                }
                del.setInt(1, newModelId);
                del.executeUpdate();
                ins.setInt(1, newModelId);
                ins.setString(2, rs.getString("regex_pattern"));
                ins.setString(3, rs.getString("description"));
                ins.setInt(4, rs.getInt("is_active"));
                ins.executeUpdate();
                count++;
            }
            System.out.println("S/N validation rules: " + count + " migrated.");
        }
    }

    static void migrateProviders(Connection sqlite, Connection pg) throws SQLException {
        String upsert = """
            INSERT INTO PROVIDER (name) VALUES (?)
            ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
            """;
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM PROVIDER");
             PreparedStatement ps = pg.prepareStatement(upsert)) {
            int count = 0;
            while (rs.next()) {
                ps.setString(1, rs.getString("name"));
                ps.executeUpdate();
                count++;
            }
            System.out.println("Providers: " + count + " migrated.");
        }
    }

    private static String prompt(Scanner scanner, String label) {
        System.out.print(label);
        return scanner.nextLine().trim();
    }

    private static String promptPassword(Scanner scanner, String label) {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword(label);
            String value = new String(chars);
            Arrays.fill(chars, '\0');
            return value;
        }
        return prompt(scanner, label);
    }
}
