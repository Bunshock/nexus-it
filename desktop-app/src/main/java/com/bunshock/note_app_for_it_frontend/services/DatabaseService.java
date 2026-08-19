package com.bunshock.note_app_for_it_frontend.services;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bunshock.note_app_for_it_frontend.models.core.AppConfig;

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
            createSettingsTable(stmt);
            createUserRoleTable(stmt);
            createAuditTables(stmt);
            migrateSchema(conn, stmt);
            insertDefaultData(stmt);
        }
    }

    // CREATE TABLE IF NOT EXISTS silently no-ops on a database that already has the
    // table from an older schema version, so newly added columns never land on disk —
    // each column added after the initial release must be migrated in here too.
    private void migrateSchema(Connection conn, Statement stmt) throws SQLException {
        // A row still holding the legacy Spanish value ("RECHAZADO") stops matching every
        // switch/if branch (all keyed on the English REJECTED) and gets stuck showing
        // "Pendiente" with no way to re-show the Aprobar/Rechazar buttons.
        if (columnExists(conn, "NOTE_REPORT", "approval_status")) {
            stmt.executeUpdate("UPDATE NOTE_REPORT SET approval_status = 'REJECTED' WHERE approval_status = 'RECHAZADO'");
        }
        // Normalizes the mixed-case Provider-note value to match the other ALL-CAPS profile_type
        // literals; every read site already tolerates either casing, so this is cleanup, not a fix.
        stmt.executeUpdate("UPDATE NOTE_REPORT SET profile_type = 'ENTREGA - PROVEEDOR' WHERE profile_type = 'Entrega - Proveedor'");
        // DEFAULT 1 is required, not optional — SQLite rejects ADD COLUMN ... NOT NULL on a
        // table with existing rows unless a default is supplied.
        addColumnIfMissing(stmt, "AUDIT_ITEM_STATUS", "quantity", "INTEGER NOT NULL DEFAULT 1");
        // failure_cause/failure_details/area_evento are deliberately NOT re-added here — they
        // moved to NOTE_DEVOLUCION_FALLA/NOTE_PRESTAMO_AREA_EVENTO (see
        // migrateEntregaDevolucionSplitSchema() below).
        addColumnIfMissing(stmt, "NOTE_PROVEEDOR", "responsible_name", "TEXT");
        addColumnIfMissing(stmt, "NOTE_PROVEEDOR", "responsible_dni", "TEXT");
        addColumnIfMissing(stmt, "NOTE_REPORT", "technician_name", "TEXT");
        addColumnIfMissing(stmt, "NOTE_REPORT", "technician_dni", "TEXT");
        // Only applies to a database still on the old wide NOTE_ITEM shape (is_asset present) —
        // a brand-new install is already slim and must not get these columns re-added.
        if (columnExists(conn, "NOTE_ITEM", "is_asset")) {
            addColumnIfMissing(stmt, "NOTE_ITEM", "return_status", "TEXT NOT NULL DEFAULT 'N_A'");
            addColumnIfMissing(stmt, "NOTE_ITEM", "return_rejection_reason", "TEXT");
            addColumnIfMissing(stmt, "NOTE_ITEM", "return_status_updated_at", "TEXT");
        }
        addColumnIfMissing(stmt, "NOTE_REPORT", "observations", "TEXT");
        // "sede" (the old free-text column) is deliberately NOT re-added here — see
        // dropDeadNoteReportColumns() below.
        addColumnIfMissing(stmt, "NOTE_REPORT", "sede_id", "INTEGER REFERENCES SEDE(id)");
        migrateSedeIdSchema(conn, stmt);
        addColumnIfMissing(stmt, "NOTE_REPORT", "approval_status", "TEXT NOT NULL DEFAULT 'PENDING'");
        // rejection_reason is deliberately NOT re-added here — see migrateRejectionReasonSchema()
        // below, same "stop re-adding a retired column" precedent as NOTE_REPORT.sede.
        dropDeadNoteReportColumns(conn, stmt);
        migrateRejectionReasonSchema(conn, stmt);
        migrateEntregaDevolucionSplitSchema(conn, stmt);
        addColumnIfMissing(stmt, "NOTE_REPORT", "stock_applied", "INTEGER NOT NULL DEFAULT 0");
        migrateStockAppliedSchema(conn, stmt);

        // Backfills the type that was previously hardcoded as "always requires S/N" so existing
        // databases keep that behavior after the column replaces the hardcoded check.
        if (addColumnIfMissing(stmt, "TYPE", "requires_serial", "INTEGER NOT NULL DEFAULT 0")) {
            try {
                stmt.executeUpdate("UPDATE TYPE SET requires_serial = 1 WHERE LOWER(name) = 'notebook'");
            } catch (SQLException ignored) {
                // no TYPE rows yet on a brand-new database — nothing to backfill
            }
        }
        migrateItemKindRevertSchema(conn, stmt);

        migrateNoteItemSchema(conn, stmt);

        addColumnIfMissing(stmt, "TYPE", "deprecated", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(stmt, "BRAND", "deprecated", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(stmt, "MODEL", "deprecated", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(stmt, "PROVIDER", "deprecated", "INTEGER NOT NULL DEFAULT 0");

        // One-time rename so the seeded fallback BRAND row's name matches what every combo box
        // displays for it (ItemDialogController.GENERIC_LABEL). No-op once already renamed.
        try {
            stmt.executeUpdate("UPDATE BRAND SET name = 'Genérico / Otro' WHERE name = 'Generic'");
        } catch (SQLException ignored) {
            // no BRAND rows yet on a brand-new database — insertDefaultData() below seeds it fresh
        }

        migrateCatalogFkSchema(conn, stmt);
        migrateGenericModelSchema(conn, stmt);
        migrateModelStockSedeSchema(conn, stmt);
        cleanupStrayGenericBrandLinks(conn);
        // Must run in this order — the Remito split's Sede-backed rows reference
        // SEDE_SHIPPING_INFO.id, which only exists once the id/deprecated migration below has run.
        migrateSedeShippingInfoIdSchema(conn, stmt);
        migrateNoteRemitoSplitSchema(conn, stmt);

        // DEFAULT 1 preserves current behavior for every existing item; only newly-created items
        // can opt out via the dialog checkbox.
        addColumnIfMissing(stmt, "NOTE_ITEM", "modifies_stock", "INTEGER NOT NULL DEFAULT 1");
        migrateStockExceptionReasonSchema(conn, stmt);
        migrateNoteItemStatusTrackingSchema(conn, stmt);

        // Lets a specific account skip the AD-group login gate (see LoginController) — e.g.
        // interns, who aren't in the org's IT support group but still need access.
        addColumnIfMissing(stmt, "APP_USER", "bypass_group_check", "INTEGER NOT NULL DEFAULT 0");

        migrateRoleTableSchema(conn, stmt);

        // Guarded — some migration tests call migrateSchema() standalone, before ROLE_PERMISSION exists.
        if (tableExists(conn, "ROLE_PERMISSION")) {
            revokeAdminAfFormatPermission(stmt);
        }
    }

    // Collapses the 3 old byte-identical per-dimension tables into one NOTE_ITEM_STATUS_TRACKING
    // table discriminated by tracking_type. No FK points into the old tables, so this is a plain
    // backfill + drop. No-op once already migrated.
    private void migrateNoteItemStatusTrackingSchema(Connection conn, Statement stmt) throws SQLException {
        if (tableExists(conn, "NOTE_ITEM_GLPI_TRACKING")) {
            stmt.executeUpdate("""
                INSERT OR IGNORE INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                SELECT item_id, 'GLPI', status, rejection_reason, status_updated_at FROM NOTE_ITEM_GLPI_TRACKING
                """);
            stmt.executeUpdate("DROP TABLE NOTE_ITEM_GLPI_TRACKING");
        }
        if (tableExists(conn, "NOTE_ITEM_RETURN_TRACKING")) {
            stmt.executeUpdate("""
                INSERT OR IGNORE INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                SELECT item_id, 'RETURN', status, rejection_reason, status_updated_at FROM NOTE_ITEM_RETURN_TRACKING
                """);
            stmt.executeUpdate("DROP TABLE NOTE_ITEM_RETURN_TRACKING");
        }
        if (tableExists(conn, "NOTE_ITEM_GLPI_RETURN_TRACKING")) {
            stmt.executeUpdate("""
                INSERT OR IGNORE INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                SELECT item_id, 'GLPI_RETURN', status, rejection_reason, status_updated_at FROM NOTE_ITEM_GLPI_RETURN_TRACKING
                """);
            stmt.executeUpdate("DROP TABLE NOTE_ITEM_GLPI_RETURN_TRACKING");
        }
    }

    // Replaces APP_USER.role / ROLE_PERMISSION.role (each an independent TEXT column with its own
    // CHECK constraint) with a role_id FK into the ROLE lookup table. Rebuilt under a temporary
    // name — SQLite can't alter a column out from under an inline CHECK constraint, and role_id
    // must end up NOT NULL, which ADD COLUMN can't express for a per-row backfill. No other table
    // references APP_USER/ROLE_PERMISSION by FK, so this is safe without a rename gotcha. No-op
    // once already migrated.
    private void migrateRoleTableSchema(Connection conn, Statement stmt) throws SQLException {
        boolean appUserNeedsMigration = tableExists(conn, "APP_USER") && !columnExists(conn, "APP_USER", "role_id");
        boolean rolePermissionNeedsMigration = tableExists(conn, "ROLE_PERMISSION") && !columnExists(conn, "ROLE_PERMISSION", "role_id");
        if (!appUserNeedsMigration && !rolePermissionNeedsMigration) return;

        // A caller invoking migrateSchema() standalone (e.g. a migration test) can't assume
        // createUserRoleTable() already created and seeded ROLE.
        if (!tableExists(conn, "ROLE")) {
            stmt.executeUpdate("""
                CREATE TABLE ROLE (
                    id   INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                )""");
            stmt.executeUpdate("INSERT INTO ROLE (name) VALUES ('USER'), ('ADMIN'), ('SUPERADMIN')");
        }

        stmt.executeUpdate("PRAGMA foreign_keys = OFF");
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            if (appUserNeedsMigration) {
                stmt.executeUpdate("ALTER TABLE APP_USER RENAME TO APP_USER_OLD_ROLE");
                stmt.executeUpdate("""
                    CREATE TABLE APP_USER (
                        id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                        username           TEXT NOT NULL UNIQUE,
                        role_id            INTEGER NOT NULL REFERENCES ROLE(id),
                        sede_id            INTEGER REFERENCES SEDE(id),
                        bypass_group_check INTEGER NOT NULL DEFAULT 0
                    )""");
                stmt.executeUpdate("""
                    INSERT INTO APP_USER (id, username, role_id, sede_id, bypass_group_check)
                    SELECT o.id, o.username, r.id, o.sede_id, o.bypass_group_check
                    FROM APP_USER_OLD_ROLE o JOIN ROLE r ON r.name = o.role
                    """);
                stmt.executeUpdate("DROP TABLE APP_USER_OLD_ROLE");
            }
            if (rolePermissionNeedsMigration) {
                stmt.executeUpdate("ALTER TABLE ROLE_PERMISSION RENAME TO ROLE_PERMISSION_OLD_ROLE");
                stmt.executeUpdate("""
                    CREATE TABLE ROLE_PERMISSION (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        role_id    INTEGER NOT NULL REFERENCES ROLE(id),
                        permission TEXT NOT NULL,
                        UNIQUE (role_id, permission)
                    )""");
                stmt.executeUpdate("""
                    INSERT INTO ROLE_PERMISSION (role_id, permission)
                    SELECT r.id, o.permission
                    FROM ROLE_PERMISSION_OLD_ROLE o JOIN ROLE r ON r.name = o.role
                    """);
                stmt.executeUpdate("DROP TABLE ROLE_PERMISSION_OLD_ROLE");
            }
            conn.commit();
        } catch (SQLException migrationFailed) {
            conn.rollback();
            throw migrationFailed;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
            stmt.executeUpdate("PRAGMA foreign_keys = ON");
        }
    }

    // Backfills a NOTE_ITEM.modifies_stock_reason column that briefly existed on a nullable
    // (never-shipped) shape into NOTE_ITEM_STOCK_EXCEPTION, then drops it. No-op on any database
    // that never had the column.
    private void migrateStockExceptionReasonSchema(Connection conn, Statement stmt) throws SQLException {
        if (!columnExists(conn, "NOTE_ITEM", "modifies_stock_reason")) return;
        stmt.executeUpdate("""
            INSERT INTO NOTE_ITEM_STOCK_EXCEPTION (item_id, reason)
            SELECT id, modifies_stock_reason FROM NOTE_ITEM
            WHERE modifies_stock_reason IS NOT NULL AND modifies_stock_reason <> ''
            """);
        stmt.executeUpdate("ALTER TABLE NOTE_ITEM DROP COLUMN modifies_stock_reason");
    }

    // MODEL_STOCK gained sede_id as part of its primary key — stock is now tracked per Sede, not
    // one shared number. Nothing else has an FK into MODEL_STOCK, so an old-shape table is simply
    // dropped and recreated; every (model, Sede) pair starts at 0 rather than guessing a split.
    private void migrateModelStockSedeSchema(Connection conn, Statement stmt) throws SQLException {
        if (tableExists(conn, "MODEL_STOCK") && !columnExists(conn, "MODEL_STOCK", "sede_id")) {
            stmt.executeUpdate("DROP TABLE MODEL_STOCK");
            stmt.executeUpdate("""
                CREATE TABLE MODEL_STOCK (
                    brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                    sede_id       INTEGER NOT NULL REFERENCES SEDE(id),
                    stock         INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (brand_type_id, model_id, sede_id)
                )""");
        }
    }

    // SEDE_SHIPPING_INFO's PK changed from sede_id to a surrogate id — SQLite can't relax a
    // PRIMARY KEY via ALTER TABLE, so this rebuilds under a temp name and renames it into place.
    // Nothing references it by FK yet at this point, so no other table's schema needs to survive
    // the rebuild. Every migrated row is left active (deprecated=0). No-op once already migrated.
    private void migrateSedeShippingInfoIdSchema(Connection conn, Statement stmt) throws SQLException {
        if (!tableExists(conn, "SEDE_SHIPPING_INFO") || columnExists(conn, "SEDE_SHIPPING_INFO", "id")) return;

        stmt.executeUpdate("PRAGMA foreign_keys = OFF");
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            stmt.executeUpdate("""
                CREATE TABLE SEDE_SHIPPING_INFO_NEW_20260807 (
                    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                    sede_id            INTEGER NOT NULL REFERENCES SEDE(id),
                    destination_label  TEXT NOT NULL,
                    address            TEXT,
                    recipients         TEXT,
                    deprecated         INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                INSERT INTO SEDE_SHIPPING_INFO_NEW_20260807 (sede_id, destination_label, address, recipients, deprecated)
                SELECT sede_id, destination_label, address, recipients, 0 FROM SEDE_SHIPPING_INFO""");
            stmt.executeUpdate("DROP TABLE SEDE_SHIPPING_INFO");
            stmt.executeUpdate("ALTER TABLE SEDE_SHIPPING_INFO_NEW_20260807 RENAME TO SEDE_SHIPPING_INFO");
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_sede_shipping_single_active "
                    + "ON SEDE_SHIPPING_INFO(sede_id) WHERE deprecated = 0");
            conn.commit();
        } catch (SQLException migrationFailed) {
            conn.rollback();
            throw migrationFailed;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
            stmt.executeUpdate("PRAGMA foreign_keys = ON");
        }
    }

    // Splits NOTE_REMITO (a nullable destination_sede_id mixed with always-populated free text)
    // into NOTE_REMITO_SEDE (FK to the exact historical SEDE_SHIPPING_INFO row) and
    // NOTE_REMITO_OTHER (free text, for a destination with no catalog row). Must run after
    // migrateSedeShippingInfoIdSchema(). No-op once NOTE_REMITO is gone.
    private void migrateNoteRemitoSplitSchema(Connection conn, Statement stmt) throws SQLException {
        if (!tableExists(conn, "NOTE_REMITO")) return;

        Map<String, Integer> shippingInfoCache = new HashMap<>();
        List<Integer> reportIds = new ArrayList<>();
        List<Integer> sedeIds = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        List<String> recipientsList = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(
                "SELECT note_report_id, destination_sede_id, destination_label, address, recipients FROM NOTE_REMITO")) {
            while (rs.next()) {
                reportIds.add(rs.getInt("note_report_id"));
                int sedeId = rs.getInt("destination_sede_id");
                sedeIds.add(rs.wasNull() ? null : sedeId);
                labels.add(rs.getString("destination_label"));
                addresses.add(rs.getString("address"));
                recipientsList.add(rs.getString("recipients"));
            }
        }

        for (int i = 0; i < reportIds.size(); i++) {
            int reportId = reportIds.get(i);
            Integer sedeId = sedeIds.get(i);
            String label = labels.get(i);
            String address = addresses.get(i);
            String recipients = recipientsList.get(i);

            if (sedeId != null) {
                int shippingInfoId = resolveOrCreateShippingInfoRow(
                    conn, shippingInfoCache, sedeId, label, address, recipients);
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO NOTE_REMITO_SEDE (note_report_id, shipping_info_id) VALUES (?, ?)")) {
                    ins.setInt(1, reportId);
                    ins.setInt(2, shippingInfoId);
                    ins.executeUpdate();
                }
            } else {
                try (PreparedStatement ins = conn.prepareStatement("""
                        INSERT INTO NOTE_REMITO_OTHER (note_report_id, destination_label, address, recipients)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    ins.setInt(1, reportId);
                    ins.setString(2, label);
                    ins.setString(3, address);
                    ins.setString(4, recipients);
                    ins.executeUpdate();
                }
            }
        }

        stmt.executeUpdate("DROP TABLE NOTE_REMITO");
    }

    // Matches a historical row's exact (sede_id, label, address, recipients) tuple regardless of
    // deprecated status, since an old note's snapshot may no longer match the currently-active
    // row. Creates a new deprecated=1 row only when nothing matches. SQLite's IS operator compares
    // a NULL operand against a bound parameter correctly, unlike T-SQL — no separate branching
    // needed here.
    private int resolveOrCreateShippingInfoRow(Connection conn, Map<String, Integer> cache,
            int sedeId, String label, String address, String recipients) throws SQLException {
        String key = sedeId + "|" + label + "|" + address + "|" + recipients;
        Integer cached = cache.get(key);
        if (cached != null) return cached;

        Integer existing = null;
        try (PreparedStatement sel = conn.prepareStatement("""
                SELECT id FROM SEDE_SHIPPING_INFO
                WHERE sede_id = ? AND destination_label = ? AND address IS ? AND recipients IS ?
                """)) {
            sel.setInt(1, sedeId);
            sel.setString(2, label);
            sel.setString(3, address);
            sel.setString(4, recipients);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) existing = rs.getInt("id");
            }
        }
        if (existing == null) {
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO SEDE_SHIPPING_INFO (sede_id, destination_label, address, recipients, deprecated) VALUES (?, ?, ?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setInt(1, sedeId);
                ins.setString(2, label);
                ins.setString(3, address);
                ins.setString(4, recipients);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    keys.next();
                    existing = keys.getInt(1);
                }
            }
        }
        cache.put(key, existing);
        return existing;
    }

    // A leftover BRAND_TYPE_LINK for the generic brand makes getBrandsForType() return it via a
    // real JOIN for that one type (sorted among real brands) instead of via client-side synthesis
    // (always appended last) — a visible per-type ordering inconsistency. Removes any such link
    // with no MODEL rows left under it; a link with a genuinely different model is left alone.
    private void cleanupStrayGenericBrandLinks(Connection conn) throws SQLException {
        String label = resolveGenericLabel();
        Integer genericBrandId = null;
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT id FROM BRAND WHERE LOWER(name) = LOWER(?)")) {
            sel.setString(1, label);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) genericBrandId = rs.getInt(1);
            }
        }
        if (genericBrandId == null) return;

        List<Integer> linkIds = new ArrayList<>();
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT id FROM BRAND_TYPE_LINK WHERE brand_id = ?")) {
            sel.setInt(1, genericBrandId);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) linkIds.add(rs.getInt(1));
            }
        }

        for (int linkId : linkIds) {
            try (PreparedStatement count = conn.prepareStatement(
                    "SELECT COUNT(*) FROM MODEL WHERE brand_type_id = ?")) {
                count.setInt(1, linkId);
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) continue;
                }
            }
            // A link with zero MODEL rows can still carry a legitimate MODEL_STOCK row for the
            // global generic model (stock is tracked per (Type,Brand) usage, independent of
            // whether that usage has any of its own MODEL rows) — deleting the link here would
            // silently drop that stock number on next startup.
            try (PreparedStatement count = conn.prepareStatement(
                    "SELECT COUNT(*) FROM MODEL_STOCK WHERE brand_type_id = ?")) {
                count.setInt(1, linkId);
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) continue;
                }
            }
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM BRAND_TYPE_LINK WHERE id = ?")) {
                del.setInt(1, linkId);
                del.executeUpdate();
            }
        }
    }

    // BRAND has no scoping FK, so it's identified by name — reads the live config value so a
    // renamed fallback brand stays correctly seeded as long as catalog.genericLabel is kept in
    // sync. Duplicated from SqliteEquipmentService's identical helper (no shared abstraction).
    private String resolveGenericLabel() {
        try {
            AppConfig.CatalogConfig catalog = ConfigService.getInstance().getConfig().catalog;
            if (catalog != null && catalog.genericLabel != null && !catalog.genericLabel.isBlank()) {
                return catalog.genericLabel.trim();
            }
        } catch (IllegalStateException notLoaded) {
            // ConfigService not loaded in this context (e.g. some test setups) — use the default
        }
        return "Genérico / Otro";
    }

    private boolean isColumnNotNull(Connection conn, String table, String column) throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return rs.getInt("notnull") == 1;
                }
            }
            return false;
        }
    }

    /**
     * Collapses every per-link "Genérico / Otro" MODEL row (a duplicate forced by
     * brand_type_id's old NOT NULL constraint) into a single global row (brand_type_id = NULL,
     * offered for every brand+type combination — see getModelsForBrandAndType()), re-points
     * historical NOTE_ITEM references onto it, and deprecates the old rows. No-op once already
     * migrated.
     */
    private void migrateGenericModelSchema(Connection conn, Statement stmt) throws SQLException {
        if (!isColumnNotNull(conn, "MODEL", "brand_type_id")) return;

        stmt.executeUpdate("PRAGMA foreign_keys = OFF");
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // Built under a temp name rather than renaming MODEL itself — SQLite's ALTER TABLE
            // RENAME silently rewrites other tables' REFERENCES clauses to follow a renamed
            // table (SN_VALIDATION/NOTE_ITEM both say REFERENCES MODEL(id)).
            stmt.executeUpdate("""
                CREATE TABLE MODEL_NEW_20260723 (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    brand_type_id INTEGER REFERENCES BRAND_TYPE_LINK(id),
                    name          TEXT NOT NULL,
                    deprecated    INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.executeUpdate("""
                INSERT INTO MODEL_NEW_20260723 (id, brand_type_id, name, deprecated)
                SELECT id, brand_type_id, name, deprecated FROM MODEL""");
            stmt.executeUpdate("DROP TABLE MODEL");
            stmt.executeUpdate("ALTER TABLE MODEL_NEW_20260723 RENAME TO MODEL");

            String label = resolveGenericLabel();

            // Promote one existing per-link row into the new global row (preserving its id)
            // rather than always inserting a brand-new one — any NOTE_ITEM already pointing at
            // exactly that row needs no repointing at all.
            Integer globalId = null;
            try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT id FROM MODEL WHERE brand_type_id IS NOT NULL AND LOWER(name) = LOWER(?) ORDER BY id LIMIT 1")) {
                sel.setString(1, label);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) globalId = rs.getInt(1);
                }
            }
            if (globalId == null) {
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO MODEL (brand_type_id, name, deprecated) VALUES (NULL, ?, 0)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ins.setString(1, label);
                    ins.executeUpdate();
                    try (ResultSet keys = ins.getGeneratedKeys()) {
                        keys.next();
                        globalId = keys.getInt(1);
                    }
                }
            } else {
                try (PreparedStatement up = conn.prepareStatement(
                        "UPDATE MODEL SET brand_type_id = NULL, deprecated = 0 WHERE id = ?")) {
                    up.setInt(1, globalId);
                    up.executeUpdate();
                }
            }

            // Re-points every historical NOTE_ITEM referencing any other per-link row onto the
            // new global row, then deprecates (not deletes) those rows, so a stray SN_VALIDATION
            // reference keeps resolving instead of hitting a dangling FK.
            try (PreparedStatement up = conn.prepareStatement("""
                    UPDATE NOTE_ITEM SET model_id = ?
                    WHERE model_id IN (
                        SELECT id FROM MODEL WHERE brand_type_id IS NOT NULL
                            AND LOWER(name) = LOWER(?) AND id != ?
                    )""")) {
                up.setInt(1, globalId);
                up.setString(2, label);
                up.setInt(3, globalId);
                up.executeUpdate();
            }
            try (PreparedStatement dep = conn.prepareStatement(
                    "UPDATE MODEL SET deprecated = 1 WHERE brand_type_id IS NOT NULL AND LOWER(name) = LOWER(?) AND id != ?")) {
                dep.setString(1, label);
                dep.setInt(2, globalId);
                dep.executeUpdate();
            }

            conn.commit();
        } catch (SQLException migrationFailed) {
            conn.rollback();
            throw migrationFailed;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
            stmt.executeUpdate("PRAGMA foreign_keys = ON");
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

    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (Statement s = conn.createStatement();
             java.sql.ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
            return false;
        }
    }

    private boolean tableExists(Connection conn, String table) throws SQLException {
        try (Statement s = conn.createStatement();
             java.sql.ResultSet rs = s.executeQuery(
                 "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'")) {
            return rs.next();
        }
    }

    /**
     * Splits the old wide NOTE_ITEM (is_asset, serial_number, a_f, quantity, glpi_/return_
     * tracking columns) into the slim base table plus its subtype tables — a subtype row now
     * only exists when that dimension actually applies, instead of every row carrying a
     * 'N_A'/NULL sentinel. No-op once already migrated.
     */
    private void migrateNoteItemSchema(Connection conn, Statement stmt) throws SQLException {
        if (!columnExists(conn, "NOTE_ITEM", "is_asset")) return;

        // PRAGMA foreign_keys is a documented no-op inside an active transaction, so it must be
        // toggled OFF before setAutoCommit(false) starts one, and back ON only after the
        // transaction has been committed and autocommit restored.
        stmt.executeUpdate("PRAGMA foreign_keys = OFF");
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            stmt.executeUpdate("ALTER TABLE NOTE_ITEM RENAME TO NOTE_ITEM_OLD_20260722");

            // SQLite's ALTER TABLE RENAME silently rewrites the REFERENCES clause of any other
            // table's schema that names the renamed table. createHistoryTables() already created
            // these subtype tables moments earlier on this old-wide-shape database, so their
            // REFERENCES NOTE_ITEM(id) just got rewritten to point at NOTE_ITEM_OLD_20260722,
            // which is dropped a few statements down — a dangling reference. Drop and recreate
            // them fresh (guaranteed empty) so their FK targets the new NOTE_ITEM created below.
            stmt.executeUpdate("DROP TABLE IF EXISTS NOTE_ITEM_ASSET");
            stmt.executeUpdate("DROP TABLE IF EXISTS NOTE_ITEM_COUNTABLE");
            stmt.executeUpdate("DROP TABLE IF EXISTS NOTE_ITEM_STATUS_TRACKING");
            stmt.executeUpdate("DROP TABLE IF EXISTS NOTE_ITEM_GLPI_TRACKING");
            stmt.executeUpdate("DROP TABLE IF EXISTS NOTE_ITEM_RETURN_TRACKING");

            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    note_id      INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
                    type_name    TEXT NOT NULL,
                    brand_name   TEXT,
                    model_name   TEXT,
                    observations TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM_ASSET (
                    item_id       INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    serial_number TEXT,
                    a_f           TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM_COUNTABLE (
                    item_id  INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    quantity INTEGER NOT NULL DEFAULT 1
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM_STATUS_TRACKING (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_id           INTEGER NOT NULL REFERENCES NOTE_ITEM(id),
                    tracking_type     TEXT NOT NULL CHECK (tracking_type IN ('GLPI', 'RETURN', 'GLPI_RETURN')),
                    status            TEXT NOT NULL,
                    rejection_reason  TEXT,
                    status_updated_at TEXT,
                    UNIQUE (item_id, tracking_type)
                )""");

            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM (id, note_id, type_name, brand_name, model_name, observations)
                SELECT id, note_id, type_name, brand_name, model_name, observations
                FROM NOTE_ITEM_OLD_20260722""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
                SELECT id, serial_number, a_f FROM NOTE_ITEM_OLD_20260722 WHERE is_asset = 1""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity)
                SELECT id, quantity FROM NOTE_ITEM_OLD_20260722 WHERE is_asset = 0""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                SELECT id, 'GLPI', glpi_status, glpi_rejection_reason, glpi_status_updated_at
                FROM NOTE_ITEM_OLD_20260722 WHERE glpi_status <> 'N_A'""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM_STATUS_TRACKING (item_id, tracking_type, status, rejection_reason, status_updated_at)
                SELECT id, 'RETURN', return_status, return_rejection_reason, return_status_updated_at
                FROM NOTE_ITEM_OLD_20260722 WHERE return_status <> 'N_A'""");

            stmt.executeUpdate("DROP TABLE NOTE_ITEM_OLD_20260722");
            conn.commit();
        } catch (SQLException migrationFailed) {
            conn.rollback();
            throw migrationFailed;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
            stmt.executeUpdate("PRAGMA foreign_keys = ON");
        }
    }

    /**
     * Replaces NOTE_ITEM.type_name/brand_name/model_name and NOTE_PROVEEDOR.provider_name (text
     * snapshots) with real FKs into the catalog tables. Every distinct historical value is
     * resolved case-insensitively regardless of deprecated status; a value with no match gets a
     * new deprecated=1 catalog row, so it never silently becomes an active, selectable entry.
     * No-op once already migrated.
     */
    private void migrateCatalogFkSchema(Connection conn, Statement stmt) throws SQLException {
        boolean needsNoteItem = columnExists(conn, "NOTE_ITEM", "type_name");
        boolean needsNoteProveedor = columnExists(conn, "NOTE_PROVEEDOR", "provider_name");
        if (!needsNoteItem && !needsNoteProveedor) return;

        stmt.executeUpdate("PRAGMA foreign_keys = OFF");
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            if (needsNoteItem) migrateNoteItemToFk(conn, stmt);
            if (needsNoteProveedor) migrateNoteProveedorToFk(conn, stmt);
            conn.commit();
        } catch (SQLException migrationFailed) {
            conn.rollback();
            throw migrationFailed;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
            stmt.executeUpdate("PRAGMA foreign_keys = ON");
        }
    }

    // Builds the new table under a temporary name rather than renaming NOTE_ITEM itself — its
    // subtype tables all say REFERENCES NOTE_ITEM(id), and SQLite's ALTER TABLE RENAME silently
    // rewrites other tables' schema to follow a renamed table (see migrateNoteItemSchema()).
    private void migrateNoteItemToFk(Connection conn, Statement stmt) throws SQLException {
        Map<String, Integer> typeCache = new HashMap<>();
        Map<String, Integer> brandCache = new HashMap<>();
        Map<String, Integer> linkCache = new HashMap<>();
        Map<String, Integer> modelCache = new HashMap<>();

        stmt.executeUpdate("""
            CREATE TABLE NOTE_ITEM_FK_NEW_20260722 (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                note_id      INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
                type_id      INTEGER NOT NULL REFERENCES TYPE(id),
                brand_id     INTEGER NOT NULL REFERENCES BRAND(id),
                model_id     INTEGER NOT NULL REFERENCES MODEL(id),
                observations TEXT
            )""");

        try (ResultSet rs = stmt.executeQuery(
                "SELECT id, note_id, type_name, brand_name, model_name, observations FROM NOTE_ITEM");
             PreparedStatement insertNew = conn.prepareStatement("""
                INSERT INTO NOTE_ITEM_FK_NEW_20260722
                    (id, note_id, type_id, brand_id, model_id, observations)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            while (rs.next()) {
                int itemId = rs.getInt("id");
                boolean isAssetItem = rowExists(conn, "SELECT 1 FROM NOTE_ITEM_ASSET WHERE item_id = ?", itemId);

                String typeName = blankToFallback(rs.getString("type_name"), "Genérico / Otro");
                String brandName = blankToFallback(rs.getString("brand_name"), "Genérico / Otro");
                String modelName = blankToFallback(rs.getString("model_name"), "Genérico / Otro");

                int typeId = resolveOrCreateCatalogRow(conn, typeCache, "TYPE", typeName,
                    "INSERT INTO TYPE (name, is_asset, deprecated) VALUES (?, "
                        + (isAssetItem ? "1" : "0") + ", 1)");
                int brandId = resolveOrCreateCatalogRow(conn, brandCache, "BRAND", brandName,
                    "INSERT INTO BRAND (name, deprecated) VALUES (?, 1)");
                int linkId = resolveOrCreateBrandTypeLink(conn, linkCache, typeId, brandId);
                int modelId = resolveOrCreateModel(conn, modelCache, linkId, modelName);

                insertNew.setInt(1, itemId);
                insertNew.setInt(2, rs.getInt("note_id"));
                insertNew.setInt(3, typeId);
                insertNew.setInt(4, brandId);
                insertNew.setInt(5, modelId);
                insertNew.setString(6, rs.getString("observations"));
                insertNew.executeUpdate();
            }
        }

        stmt.executeUpdate("DROP TABLE NOTE_ITEM");
        stmt.executeUpdate("ALTER TABLE NOTE_ITEM_FK_NEW_20260722 RENAME TO NOTE_ITEM");
    }

    // Nothing else references NOTE_PROVEEDOR by foreign key, so unlike NOTE_ITEM above this can
    // use the simpler rename-old/create-new/copy/drop-old shape directly.
    private void migrateNoteProveedorToFk(Connection conn, Statement stmt) throws SQLException {
        Map<String, Integer> providerCache = new HashMap<>();

        stmt.executeUpdate("ALTER TABLE NOTE_PROVEEDOR RENAME TO NOTE_PROVEEDOR_OLD_20260722");
        stmt.executeUpdate("""
            CREATE TABLE NOTE_PROVEEDOR (
                note_report_id   INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                provider_id      INTEGER NOT NULL REFERENCES PROVIDER(id),
                cuit             TEXT,
                motivo           TEXT,
                responsible_name TEXT,
                responsible_dni  TEXT
            )""");

        try (ResultSet rs = stmt.executeQuery("""
                SELECT note_report_id, provider_name, cuit, motivo, responsible_name, responsible_dni
                FROM NOTE_PROVEEDOR_OLD_20260722""");
             PreparedStatement insertNew = conn.prepareStatement("""
                INSERT INTO NOTE_PROVEEDOR
                    (note_report_id, provider_id, cuit, motivo, responsible_name, responsible_dni)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            while (rs.next()) {
                String providerName = blankToFallback(rs.getString("provider_name"), "Desconocido");
                int providerId = resolveOrCreateCatalogRow(conn, providerCache, "PROVIDER", providerName,
                    "INSERT INTO PROVIDER (name, deprecated) VALUES (?, 1)");

                insertNew.setInt(1, rs.getInt("note_report_id"));
                insertNew.setInt(2, providerId);
                insertNew.setString(3, rs.getString("cuit"));
                insertNew.setString(4, rs.getString("motivo"));
                insertNew.setString(5, rs.getString("responsible_name"));
                insertNew.setString(6, rs.getString("responsible_dni"));
                insertNew.executeUpdate();
            }
        }

        stmt.executeUpdate("DROP TABLE NOTE_PROVEEDOR_OLD_20260722");
    }

    private String blankToFallback(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private boolean rowExists(Connection conn, String sql, int param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Backfills NOTE_REPORT.sede (free text) into a matching or newly created SEDE row and
     * points sede_id at it, resolved case-insensitively via resolveOrCreateCatalogRow(). A newly
     * created row is left ACTIVE (deprecated=0) — unlike the catalog-FK backfill for
     * Type/Brand/Model/Provider, SEDE has no pre-existing curated catalog to fall back on, so
     * marking every row deprecated would leave zero selectable Sedes and block note generation.
     * Idempotent — only selects rows still missing sede_id. No-op on a database with no sede
     * column at all.
     */
    private void migrateSedeIdSchema(Connection conn, Statement stmt) throws SQLException {
        if (!columnExists(conn, "NOTE_REPORT", "sede")) return;

        Map<String, Integer> sedeCache = new HashMap<>();
        List<Integer> reportIds = new ArrayList<>();
        List<String> sedeTexts = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(
                "SELECT id, sede FROM NOTE_REPORT WHERE sede_id IS NULL AND sede IS NOT NULL AND TRIM(sede) <> ''")) {
            while (rs.next()) {
                reportIds.add(rs.getInt("id"));
                sedeTexts.add(rs.getString("sede"));
            }
        }
        for (int i = 0; i < reportIds.size(); i++) {
            int sedeId = resolveOrCreateCatalogRow(conn, sedeCache, "SEDE", sedeTexts.get(i).trim(),
                "INSERT INTO SEDE (name, deprecated) VALUES (?, 0)");
            try (PreparedStatement up = conn.prepareStatement("UPDATE NOTE_REPORT SET sede_id = ? WHERE id = ?")) {
                up.setInt(1, sedeId);
                up.setInt(2, reportIds.get(i));
                up.executeUpdate();
            }
        }
    }

    // Drops NOTE_REPORT.sede once migrateSedeIdSchema() has backfilled sede_id from it — must
    // run after that call, never before. SQLite supports DROP COLUMN natively (3.35+), so no
    // table rebuild is needed. Wrapped in try/catch — a failure leaves the column for next
    // startup to retry rather than crashing initialize().
    private void dropDeadNoteReportColumns(Connection conn, Statement stmt) throws SQLException {
        if (columnExists(conn, "NOTE_REPORT", "sede")) {
            try {
                stmt.executeUpdate("ALTER TABLE NOTE_REPORT DROP COLUMN sede");
            } catch (SQLException ignored) {
                // leave it for next startup to retry — not worth blocking initialize() over
            }
        }
    }

    // Consolidates the double-approval stock guard onto one shared NOTE_REPORT.stock_applied
    // column instead of NOTE_REMITO's own — every note type moves stock on approval now, not
    // just Remito. Backfills from the old column before dropping it. No-op once already migrated.
    private void migrateStockAppliedSchema(Connection conn, Statement stmt) throws SQLException {
        if (!columnExists(conn, "NOTE_REMITO", "stock_applied")) return;
        try {
            stmt.executeUpdate("""
                UPDATE NOTE_REPORT SET stock_applied = 1
                WHERE id IN (SELECT note_report_id FROM NOTE_REMITO WHERE stock_applied = 1)
                """);
            stmt.executeUpdate("ALTER TABLE NOTE_REMITO DROP COLUMN stock_applied");
        } catch (SQLException ignored) {
            // leave it for next startup to retry — not worth blocking initialize() over
        }
    }

    // Reverses a never-shipped item_kind/requires_identifier design (which replaced TYPE.is_asset/
    // requires_serial to model phones/chips as distinct kinds) back to plain is_asset/
    // requires_serial, on any local database that ran the forward migration. No-op once reverted.
    private void migrateItemKindRevertSchema(Connection conn, Statement stmt) throws SQLException {
        if (columnExists(conn, "TYPE", "requires_identifier")) {
            try {
                stmt.executeUpdate("ALTER TABLE TYPE RENAME COLUMN requires_identifier TO requires_serial");
            } catch (SQLException ignored) {
                // leave it for next startup to retry — not worth blocking initialize() over
            }
        }
        if (!columnExists(conn, "TYPE", "item_kind")) return;
        addColumnIfMissing(stmt, "TYPE", "is_asset", "INTEGER NOT NULL DEFAULT 0");
        stmt.executeUpdate(
            "UPDATE TYPE SET is_asset = CASE WHEN item_kind IN ('ASSET_SERIAL', 'ASSET_IMEI') THEN 1 ELSE 0 END");
        try {
            stmt.executeUpdate("ALTER TABLE TYPE DROP COLUMN item_kind");
        } catch (SQLException ignored) {
            // leave it for next startup to retry — not worth blocking initialize() over
        }
        // Both subtype tables were only ever reachable on a local dev database that ran this
        // same never-shipped feature — safe to drop outright rather than leave unused, unlike a
        // real released table this codebase would otherwise keep in place for old data.
        stmt.executeUpdate("DROP TABLE IF EXISTS NOTE_ITEM_PHONE");
        stmt.executeUpdate("DROP TABLE IF EXISTS NOTE_ITEM_SIM");
    }

    // rejection_reason moved into NOTE_REPORT_REJECTION — a row exists only for a note that's
    // actually been rejected. No-op once already migrated.
    private void migrateRejectionReasonSchema(Connection conn, Statement stmt) throws SQLException {
        if (!columnExists(conn, "NOTE_REPORT", "rejection_reason")) return;
        stmt.executeUpdate("""
            INSERT OR IGNORE INTO NOTE_REPORT_REJECTION (note_report_id, rejection_reason)
            SELECT id, rejection_reason FROM NOTE_REPORT
            WHERE rejection_reason IS NOT NULL AND TRIM(rejection_reason) <> ''
            """);
        try {
            stmt.executeUpdate("ALTER TABLE NOTE_REPORT DROP COLUMN rejection_reason");
        } catch (SQLException ignored) {
            // leave it for next startup to retry — not worth blocking initialize() over
        }
    }

    // NOTE_ENTREGA_DEVOLUCION mixed 4 profile types' fields on one table — failure_cause/
    // failure_details only apply to Devolución+Falla, area_evento only to Préstamo. Split into
    // NOTE_DEVOLUCION_FALLA/NOTE_PRESTAMO_AREA_EVENTO — a row exists only when that dimension
    // applies.
    private void migrateEntregaDevolucionSplitSchema(Connection conn, Statement stmt) throws SQLException {
        boolean hasFailureCause = columnExists(conn, "NOTE_ENTREGA_DEVOLUCION", "failure_cause");
        boolean hasAreaEvento = columnExists(conn, "NOTE_ENTREGA_DEVOLUCION", "area_evento");

        if (hasFailureCause) {
            stmt.executeUpdate("""
                INSERT OR IGNORE INTO NOTE_DEVOLUCION_FALLA (note_report_id, failure_cause, failure_details)
                SELECT note_report_id, failure_cause, failure_details FROM NOTE_ENTREGA_DEVOLUCION
                WHERE failure_cause IS NOT NULL AND TRIM(failure_cause) <> ''
                """);
            try {
                stmt.executeUpdate("ALTER TABLE NOTE_ENTREGA_DEVOLUCION DROP COLUMN failure_cause");
            } catch (SQLException ignored) {
                // leave it for next startup to retry
            }
        }
        if (columnExists(conn, "NOTE_ENTREGA_DEVOLUCION", "failure_details")) {
            try {
                stmt.executeUpdate("ALTER TABLE NOTE_ENTREGA_DEVOLUCION DROP COLUMN failure_details");
            } catch (SQLException ignored) {
                // leave it for next startup to retry
            }
        }
        if (hasAreaEvento) {
            stmt.executeUpdate("""
                INSERT OR IGNORE INTO NOTE_PRESTAMO_AREA_EVENTO (note_report_id, area_evento)
                SELECT note_report_id, area_evento FROM NOTE_ENTREGA_DEVOLUCION
                WHERE area_evento IS NOT NULL AND TRIM(area_evento) <> ''
                """);
            try {
                stmt.executeUpdate("ALTER TABLE NOTE_ENTREGA_DEVOLUCION DROP COLUMN area_evento");
            } catch (SQLException ignored) {
                // leave it for next startup to retry
            }
        }
    }

    // Shared resolve-or-create for TYPE/BRAND/PROVIDER/SEDE: matched case-insensitively by name
    // regardless of deprecated status (a name only ever lives on one row at a time). insertSql
    // must bind exactly one `?` for the name.
    private int resolveOrCreateCatalogRow(Connection conn, Map<String, Integer> cache,
            String table, String name, String insertSql) throws SQLException {
        String key = name.toLowerCase();
        Integer cached = cache.get(key);
        if (cached != null) return cached;

        Integer existing;
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT id FROM " + table + " WHERE LOWER(name) = LOWER(?)")) {
            sel.setString(1, name);
            try (ResultSet rs = sel.executeQuery()) {
                existing = rs.next() ? rs.getInt(1) : null;
            }
        }

        int id;
        if (existing != null) {
            id = existing;
        } else {
            try (PreparedStatement ins = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, name);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getInt(1);
                }
            }
        }
        cache.put(key, id);
        return id;
    }

    private int resolveOrCreateBrandTypeLink(Connection conn, Map<String, Integer> cache,
            int typeId, int brandId) throws SQLException {
        String key = typeId + "|" + brandId;
        Integer cached = cache.get(key);
        if (cached != null) return cached;

        Integer existing;
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT id FROM BRAND_TYPE_LINK WHERE type_id = ? AND brand_id = ?")) {
            sel.setInt(1, typeId);
            sel.setInt(2, brandId);
            try (ResultSet rs = sel.executeQuery()) {
                existing = rs.next() ? rs.getInt(1) : null;
            }
        }

        int id;
        if (existing != null) {
            id = existing;
        } else {
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setInt(1, typeId);
                ins.setInt(2, brandId);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getInt(1);
                }
            }
        }
        cache.put(key, id);
        return id;
    }

    private int resolveOrCreateModel(Connection conn, Map<String, Integer> cache,
            int brandTypeId, String name) throws SQLException {
        String key = brandTypeId + "|" + name.toLowerCase();
        Integer cached = cache.get(key);
        if (cached != null) return cached;

        Integer existing;
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT id FROM MODEL WHERE brand_type_id = ? AND LOWER(name) = LOWER(?)")) {
            sel.setInt(1, brandTypeId);
            sel.setString(2, name);
            try (ResultSet rs = sel.executeQuery()) {
                existing = rs.next() ? rs.getInt(1) : null;
            }
        }

        int id;
        if (existing != null) {
            id = existing;
        } else {
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO MODEL (brand_type_id, name, deprecated) VALUES (?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setInt(1, brandTypeId);
                ins.setString(2, name);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getInt(1);
                }
            }
        }
        cache.put(key, id);
        return id;
    }

    private void createEquipmentTables(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS TYPE (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                is_asset INTEGER NOT NULL DEFAULT 0,
                requires_serial INTEGER NOT NULL DEFAULT 0,
                deprecated INTEGER NOT NULL DEFAULT 0
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS BRAND (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                deprecated INTEGER NOT NULL DEFAULT 0
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS BRAND_TYPE_LINK (
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                type_id  INTEGER NOT NULL REFERENCES TYPE(id),
                brand_id INTEGER NOT NULL REFERENCES BRAND(id),
                UNIQUE(type_id, brand_id)
            )""");

        // brand_type_id is nullable — NULL is reserved for the single global "Genérico / Otro"
        // model (see getModelsForBrandAndType()); every other row has a real brand_type_id.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS MODEL (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                brand_type_id INTEGER REFERENCES BRAND_TYPE_LINK(id),
                name          TEXT NOT NULL,
                deprecated    INTEGER NOT NULL DEFAULT 0
            )""");

        // SQLite can't ALTER TABLE ADD CONSTRAINT — a unique index enforces it instead. A
        // database with pre-existing duplicate (brand_type_id, name) rows fails this statement;
        // swallowed to degrade to app-layer-only enforcement rather than block startup.
        try {
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_model_brand_type_name ON MODEL(brand_type_id, name)");
        } catch (SQLException duplicatesExist) {
            // pre-existing duplicate model names for the same brand+type — see comment above
        }

        // The index above doesn't protect the global generic row (brand_type_id IS NULL) — every
        // SQL engine treats NULL as never-equal-to-NULL, even in a unique index. Two narrower
        // indexes fill the gap: idx_model_global_generic_name blocks a duplicate label among
        // NULL-scoped rows; idx_model_single_active_generic guarantees at most one active global
        // row at a time.
        try {
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_model_global_generic_name ON MODEL(name) WHERE brand_type_id IS NULL");
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_model_single_active_generic ON MODEL(deprecated) WHERE brand_type_id IS NULL AND deprecated = 0");
        } catch (SQLException duplicatesExist) {
            // Degrades to app-layer-only enforcement, same as idx_model_brand_type_name above.
        }

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS SN_VALIDATION (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                regex_pattern TEXT,
                is_active     INTEGER NOT NULL DEFAULT 1
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS PROVIDER (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                deprecated INTEGER NOT NULL DEFAULT 0
            )""");

        // Deprecated flag from day one — unlike TYPE/BRAND/MODEL/PROVIDER, SEDE never had a
        // pre-deprecated-flag era to migrate away from, so this needs no addColumnIfMissing call.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS SEDE (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                deprecated INTEGER NOT NULL DEFAULT 0
            )""");

        // Row exists only once a superadmin has configured a Sede's Remito shipping info — not
        // nullable columns on SEDE itself. Deprecated-flag versioned like TYPE/BRAND/MODEL/
        // PROVIDER/SEDE, so NOTE_REMITO_SEDE.shipping_info_id can reference a specific row by FK
        // without it silently changing under an already-saved note.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS SEDE_SHIPPING_INFO (
                id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                sede_id            INTEGER NOT NULL REFERENCES SEDE(id),
                destination_label  TEXT NOT NULL,
                address            TEXT,
                recipients         TEXT,
                deprecated         INTEGER NOT NULL DEFAULT 0
            )""");
        // On an old-shape table, `deprecated` doesn't exist yet until migrateSedeShippingInfoIdSchema()
        // adds it — this would otherwise fail startup on every run until that migration catches up.
        try {
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_sede_shipping_single_active "
                    + "ON SEDE_SHIPPING_INFO(sede_id) WHERE deprecated = 0");
        } catch (SQLException notMigratedYet) {
            // migrateSedeShippingInfoIdSchema() creates this same index again once the column exists.
        }

        // brand_type_id is stored explicitly (not inferred from model_id) because the global
        // "Genérico / Otro" MODEL row needs an independent stock number per (Type,Brand) it's
        // used under. sede_id makes stock per-site — the same Model at two Sedes carries two
        // independent counts.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS MODEL_STOCK (
                brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                sede_id       INTEGER NOT NULL REFERENCES SEDE(id),
                stock         INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (brand_type_id, model_id, sede_id)
            )""");
    }

    private void createHistoryTables(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_REPORT (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at        TEXT NOT NULL,
                profile_type      TEXT NOT NULL,
                technician_name   TEXT,
                technician_dni    TEXT,
                observations      TEXT,
                sede_id           INTEGER REFERENCES SEDE(id),
                approval_status   TEXT NOT NULL DEFAULT 'PENDING',
                stock_applied     INTEGER NOT NULL DEFAULT 0
            )""");

        // Row exists only for a note an admin has actually rejected — not a NULL sentinel on
        // every NOTE_REPORT row that's PENDING/APPROVED, same "doesn't apply to this row"
        // anti-pattern the NOTE_ITEM 5-table split fixed.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_REPORT_REJECTION (
                note_report_id   INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                rejection_reason TEXT NOT NULL
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ENTREGA_DEVOLUCION (
                note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                user_name       TEXT,
                user_dni        TEXT,
                user_email      TEXT,
                motivo          TEXT
            )""");

        // Row exists only for a Devolución note whose Motivo triggered the Falla popup — not a
        // NULL sentinel on every ENTREGA_DEVOLUCION row regardless of profile type/motivo,
        // same reasoning as NOTE_REPORT_REJECTION above.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_DEVOLUCION_FALLA (
                note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
                failure_cause   TEXT NOT NULL,
                failure_details TEXT
            )""");

        // Row exists only when a Préstamo note actually captured an Área/Evento (itself optional
        // even within Préstamo) — not a NULL sentinel on every ENTREGA_DEVOLUCION row regardless
        // of profile type, same reasoning as above.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_PRESTAMO_AREA_EVENTO (
                note_report_id INTEGER PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
                area_evento    TEXT NOT NULL
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_PROVEEDOR (
                note_report_id   INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                provider_id      INTEGER NOT NULL REFERENCES PROVIDER(id),
                cuit             TEXT,
                motivo           TEXT,
                responsible_name TEXT,
                responsible_dni  TEXT
            )""");

        // Split into two mutually-exclusive subtype tables — exactly one exists per Remito note,
        // same shape as NOTE_ITEM_ASSET/NOTE_ITEM_COUNTABLE. A catalog-Sede destination's text
        // fields are locked in RemitoNoteController once a Sede is picked, so NOTE_REMITO_SEDE
        // can safely reference the SEDE_SHIPPING_INFO row by FK instead of duplicating its text —
        // SEDE_SHIPPING_INFO's deprecated-flag versioning keeps an already-saved note's FK
        // resolving to the exact historical values even after a later edit.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_REMITO_SEDE (
                note_report_id    INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                shipping_info_id  INTEGER NOT NULL REFERENCES SEDE_SHIPPING_INFO(id)
            )""");
        // A custom/manual destination (e.g. a CAU not in the SEDE catalog) has no catalog row to
        // reference at all — genuinely owned free text, not a duplicate of anything.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_REMITO_OTHER (
                note_report_id     INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                destination_label  TEXT NOT NULL,
                address             TEXT,
                recipients          TEXT
            )""");

        // Slim base table — asset-only/countable-only/tracking fields each live in their own
        // subtype table below, so a row never carries a column that doesn't apply to it.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                note_id      INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
                type_id      INTEGER NOT NULL REFERENCES TYPE(id),
                brand_id     INTEGER NOT NULL REFERENCES BRAND(id),
                model_id     INTEGER NOT NULL REFERENCES MODEL(id),
                observations TEXT,
                modifies_stock INTEGER NOT NULL DEFAULT 1
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM_ASSET (
                item_id       INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                serial_number TEXT,
                a_f           TEXT
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM_COUNTABLE (
                item_id  INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                quantity INTEGER NOT NULL DEFAULT 1
            )""");

        // A row simply doesn't exist for a tracking dimension that doesn't apply, instead of a
        // sentinel value. One table for all 3 dimensions (GLPI sync-out, Préstamo/Provider
        // return, GLPI sync-back), discriminated by tracking_type — an item can hold up to 3 rows
        // at once (a returnable Provider asset gets GLPI, then RETURN, then GLPI_RETURN).
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM_STATUS_TRACKING (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id           INTEGER NOT NULL REFERENCES NOTE_ITEM(id),
                tracking_type     TEXT NOT NULL CHECK (tracking_type IN ('GLPI', 'RETURN', 'GLPI_RETURN')),
                status            TEXT NOT NULL,
                rejection_reason  TEXT,
                status_updated_at TEXT,
                UNIQUE (item_id, tracking_type)
            )""");

        // Row exists only for an item flagged "no modifica stock" — most items never use this
        // exception, so the reason lives here rather than as an always-present NULL column on
        // NOTE_ITEM.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM_STOCK_EXCEPTION (
                item_id INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                reason  TEXT NOT NULL
            )""");

        // Countable items can be resolved in partial batches over time (e.g. 5 loaned headsets: 3
        // returned now, 1 lost later, 1 still pending) — a single status column can't express
        // that, so each partial action gets its own append-only row. PENDING is never stored —
        // remaining quantity is NOTE_ITEM_COUNTABLE.quantity minus the sum of allocations.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM_RETURN_ALLOCATION (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id    INTEGER NOT NULL REFERENCES NOTE_ITEM(id),
                status     TEXT NOT NULL,
                quantity   INTEGER NOT NULL,
                reason     TEXT,
                updated_at TEXT NOT NULL
            )""");
    }

    private void createSettingsTable(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS APP_SETTINGS (
                key   TEXT PRIMARY KEY,
                value TEXT
            )""");
    }

    // Login-time role/sede/permission lookup — see IUserRoleService. Distinct from AD group
    // membership (checked separately at login). Named APP_USER, not USER — USER is a reserved
    // keyword in T-SQL.
    private void createUserRoleTable(Statement stmt) throws SQLException {
        // Lookup table for the 3 fixed role names — APP_USER.role_id and ROLE_PERMISSION.role_id
        // both reference this instead of each independently duplicating the same
        // CHECK (role IN ('USER','ADMIN','SUPERADMIN')) constraint with nothing enforcing the two
        // stay in sync.
        boolean roleExisted = tableExists(stmt.getConnection(), "ROLE");
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS ROLE (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE
            )""");
        if (!roleExisted) {
            stmt.executeUpdate("INSERT INTO ROLE (name) VALUES ('USER'), ('ADMIN'), ('SUPERADMIN')");
        }

        boolean appUserExisted = tableExists(stmt.getConnection(), "APP_USER");
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS APP_USER (
                id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                username           TEXT NOT NULL UNIQUE,
                role_id            INTEGER NOT NULL REFERENCES ROLE(id),
                sede_id            INTEGER REFERENCES SEDE(id),
                bypass_group_check INTEGER NOT NULL DEFAULT 0
            )""");
        if (!appUserExisted) {
            migrateUserRoleIntoAppUser(stmt);
        }

        // A permission not present here is denied — there is no separate "explicitly denied"
        // state (see models.Permission). Seeded only the first time this table is created, not
        // on every startup, so a superadmin's later revocation (a DELETE against this table)
        // isn't silently undone on next launch.
        boolean rolePermissionExisted = tableExists(stmt.getConnection(), "ROLE_PERMISSION");
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS ROLE_PERMISSION (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                role_id    INTEGER NOT NULL REFERENCES ROLE(id),
                permission TEXT NOT NULL,
                UNIQUE (role_id, permission)
            )""");
        if (!rolePermissionExisted) {
            seedDefaultRolePermissions(stmt);
        }
    }

    // Carries existing role assignments forward onto the new surrogate-keyed table; sede_id
    // starts NULL for every migrated row (Sede assignment is a new, separate concept — a
    // superadmin must assign it by hand afterward, same as for a brand-new user).
    private void migrateUserRoleIntoAppUser(Statement stmt) throws SQLException {
        if (!tableExists(stmt.getConnection(), "USER_ROLE")) return;
        stmt.executeUpdate("""
            INSERT INTO APP_USER (username, role_id, sede_id)
            SELECT u.username, r.id, NULL FROM USER_ROLE u JOIN ROLE r ON r.name = u.role
            WHERE NOT EXISTS (SELECT 1 FROM APP_USER WHERE APP_USER.username = u.username)
            """);
        stmt.executeUpdate("DROP TABLE USER_ROLE");
    }

    // ADMIN gets everything except the org-wide config permissions reserved for SUPERADMIN
    // (EDIT_SMTP_CONFIG, EDIT_AF_FORMAT_CONFIG); SUPERADMIN gets everything. Enumerated from the
    // Permission enum itself (not hand-typed strings) so this can't drift out of sync with it.
    private void seedDefaultRolePermissions(Statement stmt) throws SQLException {
        int adminRoleId = roleIdFor(stmt, "ADMIN");
        int superadminRoleId = roleIdFor(stmt, "SUPERADMIN");
        for (com.bunshock.note_app_for_it_frontend.models.admin.Permission p
                : com.bunshock.note_app_for_it_frontend.models.admin.Permission.values()) {
            if (p != com.bunshock.note_app_for_it_frontend.models.admin.Permission.EDIT_SMTP_CONFIG
                    && p != com.bunshock.note_app_for_it_frontend.models.admin.Permission.EDIT_AF_FORMAT_CONFIG) {
                stmt.executeUpdate("INSERT INTO ROLE_PERMISSION (role_id, permission) VALUES (" + adminRoleId + ", '" + p.name() + "')");
            }
            stmt.executeUpdate("INSERT INTO ROLE_PERMISSION (role_id, permission) VALUES (" + superadminRoleId + ", '" + p.name() + "')");
        }
    }

    private int roleIdFor(Statement stmt, String roleName) throws SQLException {
        try (java.sql.ResultSet rs = stmt.executeQuery("SELECT id FROM ROLE WHERE name = '" + roleName + "'")) {
            if (!rs.next()) throw new SQLException("ROLE row missing for '" + roleName + "'");
            return rs.getInt("id");
        }
    }

    // One-time default correction (ADMIN's original seed wrongly included this) — safe to run
    // unconditionally every startup, same as the approval_status/profile_type corrections above.
    private void revokeAdminAfFormatPermission(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            DELETE FROM ROLE_PERMISSION
            WHERE role_id = (SELECT id FROM ROLE WHERE name = 'ADMIN')
            AND permission = 'EDIT_AF_FORMAT_CONFIG'
            """);
    }

    // Append-only audit trail — 4 tables, one per concern, since 3 of the 4 have enough real
    // structure (typed FKs, old/new columns) to be worth keeping precise. AUDIT_ADMIN_ACTION is
    // the exception: a generic action/target_type/target_id/old_value/new_value shape, since it
    // covers a heterogeneous set of admin actions with no shared FK target. target_id is TEXT
    // since it sometimes holds a settings key rather than a numeric row id.
    //
    // old_value/new_value must NEVER hold an actual secret (SMTP password, GLPI API key, AD
    // token, DB credentials) — only that a change happened. Enforced by the caller, not this
    // schema.
    //
    // APP_USER/ROLE_PERMISSION changes are made via direct SQL, not through the app, so this
    // table structurally cannot see those writes.
    private void createAuditTables(Statement stmt) throws SQLException {
        // No FK to APP_USER — a failed login attempt's username may not be a registered account
        // at all (a typo, or someone probing), and APP_USER only has rows for accounts a
        // superadmin has actually registered/promoted.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS AUDIT_LOGIN (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                username       TEXT NOT NULL,
                success        INTEGER NOT NULL,
                failure_reason TEXT,
                attempted_at   TEXT NOT NULL
            )""");

        // reason is NOT NULL — the Base de Datos "Stock"/"Editar modelo" dialogs both require a
        // reason before saving (see DatabaseSectionController.promptStockChangeReason()).
        // old_stock/new_stock stay real INTEGER columns rather than folding into
        // AUDIT_ADMIN_ACTION's generic TEXT old_value/new_value, since these are genuinely
        // numeric and worth precise typing for a DBA running aggregate queries later.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS AUDIT_STOCK (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                model_id      INTEGER NOT NULL REFERENCES MODEL(id),
                sede_id       INTEGER NOT NULL REFERENCES SEDE(id),
                username      TEXT NOT NULL,
                old_stock     INTEGER NOT NULL,
                new_stock     INTEGER NOT NULL,
                reason        TEXT NOT NULL,
                changed_at    TEXT NOT NULL
            )""");

        // Covers both GLPI and Préstamo/Provider return-status transitions in one table via a
        // status_kind discriminator — both are the same shape (status moved A to B, by whom,
        // when, why), and NOTE_ITEM_STATUS_TRACKING only ever keeps the latest status.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS AUDIT_ITEM_STATUS (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id     INTEGER NOT NULL REFERENCES NOTE_ITEM(id),
                status_kind TEXT NOT NULL CHECK (status_kind IN ('GLPI', 'RETURN')),
                old_status  TEXT NOT NULL,
                new_status  TEXT NOT NULL,
                reason      TEXT,
                quantity    INTEGER NOT NULL DEFAULT 1,
                username    TEXT NOT NULL,
                changed_at  TEXT NOT NULL
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS AUDIT_ADMIN_ACTION (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                username     TEXT NOT NULL,
                action       TEXT NOT NULL,
                target_type  TEXT NOT NULL,
                target_id    TEXT,
                old_value    TEXT,
                new_value    TEXT,
                reason       TEXT,
                performed_at TEXT NOT NULL
            )""");
    }

    private void insertDefaultData(Statement stmt) throws SQLException {
        String label = resolveGenericLabel();
        try (PreparedStatement insBrand = stmt.getConnection().prepareStatement(
                "INSERT OR IGNORE INTO BRAND (name) VALUES (?)")) {
            insBrand.setString(1, label);
            insBrand.executeUpdate();
        }
        // Only ever creates the global model on a brand-new install — migrateGenericModelSchema()
        // already created (or promoted) it for a pre-existing database. idx_model_single_active_
        // generic makes a second attempt here a silent no-op via OR IGNORE, same as BRAND above.
        try (PreparedStatement insModel = stmt.getConnection().prepareStatement(
                "INSERT OR IGNORE INTO MODEL (brand_type_id, name, deprecated) VALUES (NULL, ?, 0)")) {
            insModel.setString(1, label);
            insModel.executeUpdate();
        }
    }

}
