package com.bunshock.note_app_for_it_frontend.utils.admin;

import com.bunshock.note_app_for_it_frontend.services.core.RemoteDatabaseService;
import java.io.Console;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * Developer/admin one-off utility — copies the equipment catalog (Type, Brand, Brand-Type
 * links, Model, S/N validation rules, Provider) from the local SQLite database
 * (data/noteapp.db) into a remote SQL Server database, remapping autoincrement ids along the way.
 * SQL Server assigns its own IDENTITY ids on insert; SQLite's original ids can't be copied as-is
 * without risking collisions with rows the remote database already has, and would leave its
 * identity counters stale for future inserts (see CLAUDE.md's remote-database migration notes).
 *
 * Use this to build up the real catalog locally through the app's own "Base de Datos" UI first
 * — while a remote SQL Server isn't available yet — then run this script once one is. It creates
 * the schema itself (reuses RemoteDatabaseService.ensureSchema(), the same call the app makes on
 * startup once db_host is configured), so an empty, freshly created database is all that's
 * required on the other end — see database/sqlserver/README.md for how to provision one.
 *
 * Safe to re-run: every table is migrated with a check-then-insert-or-update (no SQL Server
 * upsert syntax like MERGE is used — this stays plain, portable SQL that also lets
 * CatalogMigrationToolTest exercise it against a second SQLite database as a same-dialect
 * stand-in, since no live SQL Server exists in this project's test infrastructure), so running
 * this again after adding more local data only inserts what's new — existing rows are matched by
 * their unique name (or, for MODEL, brand_type_id + name) and their other columns (is_asset,
 * requires_serial) are refreshed to match the local values. SN_VALIDATION rows are always
 * replaced outright (no unique constraint of their own to upsert against).
 *
 * History (NOTE_REPORT and related tables) is deliberately NOT migrated by this script — only
 * the equipment catalog, which is what an admin typically wants to prepare ahead of time.
 * History rows store type/brand/model as plain denormalized text on NOTE_ITEM, not catalog
 * foreign keys (the same "snapshot, don't reference" pattern documented in CLAUDE.md), so they
 * don't depend on catalog ids and aren't covered here.
 *
 * Usage (from desktop-app/):
 *   mvn exec:java -Dexec.mainClass="com.bunshock.note_app_for_it_frontend.utils.admin.CatalogMigrationTool"
 *
 * Prompts interactively for the SQL Server host/port/database/username/password — nothing is
 * read from or written to APP_SETTINGS, so this works whether or not the app itself has been
 * pointed at the remote database yet.
 */
public class CatalogMigrationTool {

    private static final String SQLITE_URL = "jdbc:sqlite:data/noteapp.db";

    public static void main(String[] args) throws Exception {
        System.out.println("=== Catalog migration: local SQLite -> remote SQL Server ===\n");

        Scanner scanner = new Scanner(System.in);
        String host = prompt(scanner, "SQL Server host: ");
        String portInput = prompt(scanner, "SQL Server port [1433]: ");
        int port = portInput.isBlank() ? 1433 : Integer.parseInt(portInput);
        String dbName = prompt(scanner, "Database name: ");
        String username = prompt(scanner, "Username: ");
        String password = promptPassword(scanner, "Password: ");

        RemoteDatabaseService remote = RemoteDatabaseService.getInstance();
        remote.configure(host, port, dbName, username, password);

        System.out.println("\nVerifying connection...");
        if (!remote.testConnection()) {
            System.err.println("Could not connect to SQL Server with these details. Aborting — nothing was migrated.");
            System.exit(1);
        }

        System.out.println("Connected. Ensuring schema exists...");
        remote.ensureSchema();

        try (Connection sqlite = DriverManager.getConnection(SQLITE_URL);
             Connection remoteConn = remote.getConnection()) {

            remoteConn.setAutoCommit(false);
            try {
                Map<Integer, Integer> typeIds = migrateTypes(sqlite, remoteConn);
                Map<Integer, Integer> brandIds = migrateBrands(sqlite, remoteConn);
                Map<Integer, Integer> linkIds = migrateBrandTypeLinks(sqlite, remoteConn, typeIds, brandIds);
                Map<Integer, Integer> modelIds = migrateModels(sqlite, remoteConn, linkIds);
                Map<Integer, Integer> sedeIds = migrateSedes(sqlite, remoteConn);
                migrateModelStock(sqlite, remoteConn, linkIds, modelIds, sedeIds);
                migrateSnValidations(sqlite, remoteConn, modelIds);
                migrateProviders(sqlite, remoteConn);
                remoteConn.commit();
                System.out.println("\nMigration complete.");
            } catch (Exception e) {
                remoteConn.rollback();
                System.err.println("\nMigration failed, rolled back — nothing was changed on the remote database: " + e.getMessage());
                throw e;
            }
        }
    }

    static Map<Integer, Integer> migrateTypes(Connection sqlite, Connection remote) throws SQLException {
        Map<Integer, Integer> idMap = new HashMap<>();
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name, is_asset, requires_serial, deprecated FROM TYPE")) {
            int count = 0;
            while (rs.next()) {
                String name = rs.getString("name");
                int isAsset = rs.getInt("is_asset");
                int requiresSerial = rs.getInt("requires_serial");
                int deprecated = rs.getInt("deprecated");

                Integer existingId = findId(remote, "SELECT id FROM TYPE WHERE name = ?", name);
                int newId;
                if (existingId != null) {
                    try (PreparedStatement up = remote.prepareStatement(
                            "UPDATE TYPE SET is_asset = ?, requires_serial = ?, deprecated = ? WHERE id = ?")) {
                        up.setInt(1, isAsset);
                        up.setInt(2, requiresSerial);
                        up.setInt(3, deprecated);
                        up.setInt(4, existingId);
                        up.executeUpdate();
                    }
                    newId = existingId;
                } else {
                    try (PreparedStatement ins = remote.prepareStatement(
                            "INSERT INTO TYPE (name, is_asset, requires_serial, deprecated) VALUES (?, ?, ?, ?)",
                            PreparedStatement.RETURN_GENERATED_KEYS)) {
                        ins.setString(1, name);
                        ins.setInt(2, isAsset);
                        ins.setInt(3, requiresSerial);
                        ins.setInt(4, deprecated);
                        ins.executeUpdate();
                        try (ResultSet keys = ins.getGeneratedKeys()) {
                            keys.next();
                            newId = keys.getInt(1);
                        }
                    }
                }
                idMap.put(rs.getInt("id"), newId);
                count++;
            }
            System.out.println("Types: " + count + " migrated.");
        }
        return idMap;
    }

    static Map<Integer, Integer> migrateBrands(Connection sqlite, Connection remote) throws SQLException {
        Map<Integer, Integer> idMap = new HashMap<>();
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name, deprecated FROM BRAND")) {
            int count = 0;
            while (rs.next()) {
                String name = rs.getString("name");
                int deprecated = rs.getInt("deprecated");
                Integer existingId = findId(remote, "SELECT id FROM BRAND WHERE name = ?", name);
                int newId;
                if (existingId != null) {
                    try (PreparedStatement up = remote.prepareStatement(
                            "UPDATE BRAND SET deprecated = ? WHERE id = ?")) {
                        up.setInt(1, deprecated);
                        up.setInt(2, existingId);
                        up.executeUpdate();
                    }
                    newId = existingId;
                } else {
                    try (PreparedStatement ins = remote.prepareStatement(
                            "INSERT INTO BRAND (name, deprecated) VALUES (?, ?)",
                            PreparedStatement.RETURN_GENERATED_KEYS)) {
                        ins.setString(1, name);
                        ins.setInt(2, deprecated);
                        ins.executeUpdate();
                        try (ResultSet keys = ins.getGeneratedKeys()) {
                            keys.next();
                            newId = keys.getInt(1);
                        }
                    }
                }
                idMap.put(rs.getInt("id"), newId);
                count++;
            }
            System.out.println("Brands: " + count + " migrated.");
        }
        return idMap;
    }

    static Map<Integer, Integer> migrateBrandTypeLinks(Connection sqlite, Connection remote,
            Map<Integer, Integer> typeIds, Map<Integer, Integer> brandIds) throws SQLException {
        Map<Integer, Integer> idMap = new HashMap<>();
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, type_id, brand_id FROM BRAND_TYPE_LINK")) {
            int count = 0;
            while (rs.next()) {
                int oldId = rs.getInt("id");
                Integer newTypeId = typeIds.get(rs.getInt("type_id"));
                Integer newBrandId = brandIds.get(rs.getInt("brand_id"));
                if (newTypeId == null || newBrandId == null) {
                    System.err.println("Skipping BRAND_TYPE_LINK id=" + oldId + " — its type or brand wasn't migrated.");
                    continue;
                }
                Integer existingId;
                try (PreparedStatement sel = remote.prepareStatement(
                        "SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?")) {
                    sel.setInt(1, newTypeId);
                    sel.setInt(2, newBrandId);
                    try (ResultSet found = sel.executeQuery()) {
                        existingId = found.next() ? found.getInt(1) : null;
                    }
                }
                int newId;
                if (existingId != null) {
                    newId = existingId;
                } else {
                    try (PreparedStatement ins = remote.prepareStatement(
                            "INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?)",
                            PreparedStatement.RETURN_GENERATED_KEYS)) {
                        ins.setInt(1, newTypeId);
                        ins.setInt(2, newBrandId);
                        ins.executeUpdate();
                        try (ResultSet keys = ins.getGeneratedKeys()) {
                            keys.next();
                            newId = keys.getInt(1);
                        }
                    }
                }
                idMap.put(oldId, newId);
                count++;
            }
            System.out.println("Brand-Type links: " + count + " migrated.");
        }
        return idMap;
    }

    static Map<Integer, Integer> migrateModels(Connection sqlite, Connection remote,
            Map<Integer, Integer> linkIds) throws SQLException {
        Map<Integer, Integer> idMap = new HashMap<>();
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, brand_type_id, name, deprecated FROM MODEL")) {
            int count = 0;
            while (rs.next()) {
                int oldId = rs.getInt("id");
                // NULL brand_type_id means this is the single global "Genérico / Otro" model,
                // not scoped to any particular link — matched/inserted on the remote via
                // brand_type_id IS NULL instead of a remapped linkId.
                Integer oldLinkId = (Integer) rs.getObject("brand_type_id");
                Integer newLinkId = null;
                if (oldLinkId != null) {
                    newLinkId = linkIds.get(oldLinkId);
                    if (newLinkId == null) {
                        System.err.println("Skipping MODEL id=" + oldId + " — its brand-type link wasn't migrated.");
                        continue;
                    }
                }
                String name = rs.getString("name");
                int deprecated = rs.getInt("deprecated");
                Integer existingId;
                String selSql = newLinkId == null
                    ? "SELECT id FROM MODEL WHERE brand_type_id IS NULL AND name = ?"
                    : "SELECT id FROM MODEL WHERE brand_type_id = ? AND name = ?";
                try (PreparedStatement sel = remote.prepareStatement(selSql)) {
                    int idx = 1;
                    if (newLinkId != null) sel.setInt(idx++, newLinkId);
                    sel.setString(idx, name);
                    try (ResultSet found = sel.executeQuery()) {
                        existingId = found.next() ? found.getInt(1) : null;
                    }
                }
                int newId;
                if (existingId != null) {
                    try (PreparedStatement up = remote.prepareStatement(
                            "UPDATE MODEL SET name = ?, deprecated = ? WHERE id = ?")) {
                        up.setString(1, name);
                        up.setInt(2, deprecated);
                        up.setInt(3, existingId);
                        up.executeUpdate();
                    }
                    newId = existingId;
                } else {
                    try (PreparedStatement ins = remote.prepareStatement(
                            "INSERT INTO MODEL (brand_type_id, name, deprecated) VALUES (?, ?, ?)",
                            PreparedStatement.RETURN_GENERATED_KEYS)) {
                        if (newLinkId == null) ins.setNull(1, Types.INTEGER);
                        else ins.setInt(1, newLinkId);
                        ins.setString(2, name);
                        ins.setInt(3, deprecated);
                        ins.executeUpdate();
                        try (ResultSet keys = ins.getGeneratedKeys()) {
                            keys.next();
                            newId = keys.getInt(1);
                        }
                    }
                }
                idMap.put(oldId, newId);
                count++;
            }
            System.out.println("Models: " + count + " migrated.");
        }
        return idMap;
    }

    // Same shape as migrateBrands()/migrateProviders() — SEDE is a flat table (name UNIQUE +
    // deprecated), migrated here (not previously covered by this tool at all) specifically so
    // MODEL_STOCK's sede_id can be remapped below, same as every other catalog FK this tool moves.
    static Map<Integer, Integer> migrateSedes(Connection sqlite, Connection remote) throws SQLException {
        Map<Integer, Integer> idMap = new HashMap<>();
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name, deprecated FROM SEDE")) {
            int count = 0;
            while (rs.next()) {
                String name = rs.getString("name");
                int deprecated = rs.getInt("deprecated");
                Integer existingId = findId(remote, "SELECT id FROM SEDE WHERE name = ?", name);
                int newId;
                if (existingId != null) {
                    try (PreparedStatement up = remote.prepareStatement(
                            "UPDATE SEDE SET deprecated = ? WHERE id = ?")) {
                        up.setInt(1, deprecated);
                        up.setInt(2, existingId);
                        up.executeUpdate();
                    }
                    newId = existingId;
                } else {
                    try (PreparedStatement ins = remote.prepareStatement(
                            "INSERT INTO SEDE (name, deprecated) VALUES (?, ?)",
                            PreparedStatement.RETURN_GENERATED_KEYS)) {
                        ins.setString(1, name);
                        ins.setInt(2, deprecated);
                        ins.executeUpdate();
                        try (ResultSet keys = ins.getGeneratedKeys()) {
                            keys.next();
                            newId = keys.getInt(1);
                        }
                    }
                }
                idMap.put(rs.getInt("id"), newId);
                count++;
            }
            System.out.println("Sedes: " + count + " migrated.");
        }
        return idMap;
    }

    // Same check-then-insert-or-update shape as every other migrate*() method — matched on the
    // remapped (brand_type_id, model_id, sede_id) triple, since that's MODEL_STOCK's own PK.
    static void migrateModelStock(Connection sqlite, Connection remote,
            Map<Integer, Integer> linkIds, Map<Integer, Integer> modelIds,
            Map<Integer, Integer> sedeIds) throws SQLException {
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT brand_type_id, model_id, sede_id, stock FROM MODEL_STOCK")) {
            int count = 0;
            while (rs.next()) {
                int oldLinkId = rs.getInt("brand_type_id");
                int oldModelId = rs.getInt("model_id");
                int oldSedeId = rs.getInt("sede_id");
                Integer newLinkId = linkIds.get(oldLinkId);
                Integer newModelId = modelIds.get(oldModelId);
                Integer newSedeId = sedeIds.get(oldSedeId);
                if (newLinkId == null || newModelId == null || newSedeId == null) {
                    System.err.println("Skipping MODEL_STOCK (brand_type_id=" + oldLinkId
                        + ", model_id=" + oldModelId + ", sede_id=" + oldSedeId
                        + ") — its link, model, or sede wasn't migrated.");
                    continue;
                }
                int stock = rs.getInt("stock");
                Integer existing;
                try (PreparedStatement sel = remote.prepareStatement(
                        "SELECT stock FROM MODEL_STOCK WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?")) {
                    sel.setInt(1, newLinkId);
                    sel.setInt(2, newModelId);
                    sel.setInt(3, newSedeId);
                    try (ResultSet found = sel.executeQuery()) {
                        existing = found.next() ? found.getInt(1) : null;
                    }
                }
                if (existing != null) {
                    try (PreparedStatement up = remote.prepareStatement(
                            "UPDATE MODEL_STOCK SET stock = ? WHERE brand_type_id = ? AND model_id = ? AND sede_id = ?")) {
                        up.setInt(1, stock);
                        up.setInt(2, newLinkId);
                        up.setInt(3, newModelId);
                        up.setInt(4, newSedeId);
                        up.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ins = remote.prepareStatement(
                            "INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock) VALUES (?, ?, ?, ?)")) {
                        ins.setInt(1, newLinkId);
                        ins.setInt(2, newModelId);
                        ins.setInt(3, newSedeId);
                        ins.setInt(4, stock);
                        ins.executeUpdate();
                    }
                }
                count++;
            }
            System.out.println("Model stock rows: " + count + " migrated.");
        }
    }

    static void migrateSnValidations(Connection sqlite, Connection remote,
            Map<Integer, Integer> modelIds) throws SQLException {
        // No unique constraint of its own to upsert against (one row per model_id is enforced
        // app-side, in SqliteEquipmentService.upsertSnValidation, not by the schema) — delete
        // any existing row for the target model first so re-running this script replaces rather
        // than duplicates.
        String delete = "DELETE FROM SN_VALIDATION WHERE model_id = ?";
        String insert = "INSERT INTO SN_VALIDATION (model_id, regex_pattern, is_active) VALUES (?, ?, ?)";
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT model_id, regex_pattern, is_active FROM SN_VALIDATION");
             PreparedStatement del = remote.prepareStatement(delete);
             PreparedStatement ins = remote.prepareStatement(insert)) {
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
                ins.setInt(3, rs.getInt("is_active"));
                ins.executeUpdate();
                count++;
            }
            System.out.println("S/N validation rules: " + count + " migrated.");
        }
    }

    static void migrateProviders(Connection sqlite, Connection remote) throws SQLException {
        try (Statement s = sqlite.createStatement();
             ResultSet rs = s.executeQuery("SELECT name, deprecated FROM PROVIDER")) {
            int count = 0;
            while (rs.next()) {
                String name = rs.getString("name");
                int deprecated = rs.getInt("deprecated");
                Integer existingId = findId(remote, "SELECT id FROM PROVIDER WHERE name = ?", name);
                if (existingId != null) {
                    try (PreparedStatement up = remote.prepareStatement(
                            "UPDATE PROVIDER SET deprecated = ? WHERE id = ?")) {
                        up.setInt(1, deprecated);
                        up.setInt(2, existingId);
                        up.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ins = remote.prepareStatement(
                            "INSERT INTO PROVIDER (name, deprecated) VALUES (?, ?)")) {
                        ins.setString(1, name);
                        ins.setInt(2, deprecated);
                        ins.executeUpdate();
                    }
                }
                count++;
            }
            System.out.println("Providers: " + count + " migrated.");
        }
    }

    private static Integer findId(Connection c, String sql, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
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
