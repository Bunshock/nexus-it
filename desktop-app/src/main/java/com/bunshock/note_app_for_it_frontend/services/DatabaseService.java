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

import com.bunshock.note_app_for_it_frontend.models.AppConfig;

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
            migrateSchema(conn, stmt);
            insertDefaultData(stmt);
        }
    }

    // CREATE TABLE IF NOT EXISTS silently no-ops on a database that already has the
    // table from an older schema version, so newly added columns never land on disk —
    // each column added after the initial release must be migrated in here too.
    private void migrateSchema(Connection conn, Statement stmt) throws SQLException {
        addColumnIfMissing(stmt, "NOTE_ENTREGA_DEVOLUCION", "failure_cause", "TEXT");
        addColumnIfMissing(stmt, "NOTE_ENTREGA_DEVOLUCION", "failure_details", "TEXT");
        addColumnIfMissing(stmt, "NOTE_PROVEEDOR", "responsible_name", "TEXT");
        addColumnIfMissing(stmt, "NOTE_PROVEEDOR", "responsible_dni", "TEXT");
        addColumnIfMissing(stmt, "NOTE_REPORT", "technician_name", "TEXT");
        addColumnIfMissing(stmt, "NOTE_REPORT", "technician_dni", "TEXT");
        addColumnIfMissing(stmt, "NOTE_ENTREGA_DEVOLUCION", "area_evento", "TEXT");
        // Only relevant to a database still on the old wide NOTE_ITEM shape (is_asset present) —
        // on a brand-new install NOTE_ITEM is already the slim shape from createHistoryTables(),
        // and these columns must NOT be re-added there just because they're "missing"; walking a
        // genuinely old database up to the full wide shape here is a prerequisite for
        // migrateNoteItemSchema() below to correctly split it into the 5-table shape.
        if (columnExists(conn, "NOTE_ITEM", "is_asset")) {
            addColumnIfMissing(stmt, "NOTE_ITEM", "return_status", "TEXT NOT NULL DEFAULT 'N_A'");
            addColumnIfMissing(stmt, "NOTE_ITEM", "return_rejection_reason", "TEXT");
            addColumnIfMissing(stmt, "NOTE_ITEM", "return_status_updated_at", "TEXT");
        }
        addColumnIfMissing(stmt, "NOTE_REPORT", "observations", "TEXT");
        addColumnIfMissing(stmt, "NOTE_REPORT", "sede", "TEXT");
        addColumnIfMissing(stmt, "NOTE_REPORT", "sede_id", "INTEGER REFERENCES SEDE(id)");
        migrateSedeIdSchema(conn, stmt);

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

        migrateNoteItemSchema(conn, stmt);

        addColumnIfMissing(stmt, "TYPE", "deprecated", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(stmt, "BRAND", "deprecated", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(stmt, "MODEL", "deprecated", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(stmt, "PROVIDER", "deprecated", "INTEGER NOT NULL DEFAULT 0");

        // "Generic" -> "Genérico / Otro": one-time rename so the seeded fallback BRAND row's
        // name matches exactly what every combo box has always displayed for it (see
        // ItemDialogController's GENERIC_LABEL) instead of two different names for the same
        // concept. Plain no-op UPDATE on a database that's already been through this once.
        try {
            stmt.executeUpdate("UPDATE BRAND SET name = 'Genérico / Otro' WHERE name = 'Generic'");
        } catch (SQLException ignored) {
            // no BRAND rows yet on a brand-new database — insertDefaultData() below seeds it fresh
        }

        migrateCatalogFkSchema(conn, stmt);
        migrateGenericModelSchema(conn, stmt);
        cleanupStrayGenericBrandLinks(conn);
    }

    // Before 2026-07-23, ItemDialogController lazily created a real BRAND_TYPE_LINK the first
    // time a technician picked the global generic brand for a given type — vestigial now that
    // the generic brand is offered for every type via client-side synthesis instead
    // (ItemDialogController.onTypeSelected(), DatabaseSectionController.refreshBrandsForType()).
    // A surviving link isn't just clutter: it makes getBrandsForType() return the generic brand
    // via its real JOIN for *that one type* (sorted alphabetically among real brands), while
    // every other type only shows it via synthesis (always appended last) — a real, visible
    // per-type inconsistency in ordering/styling, not just untidy data. Removes any such link
    // that has no MODEL rows left under it (the generic model that used to live there was
    // already consolidated into the single global row by migrateGenericModelSchema() above);
    // a link that somehow still has a genuinely different, deliberately-added model under it is
    // left alone rather than risking an orphaned row. Idempotent and cheap — safe every startup,
    // same as the other backfills in this method.
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
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM BRAND_TYPE_LINK WHERE id = ?")) {
                del.setInt(1, linkId);
                del.executeUpdate();
            }
        }
    }

    // BRAND has no scoping FK, so it stays identified by name — reads the live config value so
    // a renamed fallback brand stays correctly seeded/protected as long as catalog.genericLabel
    // is kept in sync with the rename. Duplicated from SqliteEquipmentService's identical
    // helper per this codebase's no-shared-abstraction convention.
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
     * MODEL.brand_type_id used to be NOT NULL, which forced a duplicate "Genérico / Otro" row
     * per BRAND_TYPE_LINK purely to satisfy the FK — the label itself never actually varied by
     * scope, so this was pure redundancy (renaming the fallback meant updating N rows to stay
     * in sync, and nothing stopped the copies from drifting apart). Collapses every existing
     * per-link "Genérico / Otro" row into a single global row (brand_type_id = NULL, offered
     * for every brand+type combination regardless — see SqliteEquipmentService's
     * getModelsForBrandAndType()), re-points historical NOTE_ITEM references onto it, and
     * deprecates (never deletes) the old per-link rows. No-ops once already migrated
     * (brand_type_id already nullable) — including on a brand-new install, which gets the
     * nullable column straight from createEquipmentTables(); insertDefaultData() is what
     * actually creates the global row for that case.
     */
    private void migrateGenericModelSchema(Connection conn, Statement stmt) throws SQLException {
        if (!isColumnNotNull(conn, "MODEL", "brand_type_id")) return;

        stmt.executeUpdate("PRAGMA foreign_keys = OFF");
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // Built under a temp name rather than renaming MODEL itself — SN_VALIDATION/NOTE_ITEM
            // both say REFERENCES MODEL(id), and SQLite's ALTER TABLE RENAME silently rewrites
            // other tables' schema to follow a renamed table (the same gotcha already hit and
            // documented in migrateNoteItemSchema() above) — dropping the old MODEL (never
            // renamed) and renaming the new table into its exact name avoids that entirely.
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

            // Re-point every historical NOTE_ITEM referencing any OTHER per-link "Genérico /
            // Otro" row onto the single new global row, then deprecate those now-unused rows —
            // deprecated, not deleted, so a stray SN_VALIDATION row (unlikely, but not
            // impossible) referencing one keeps resolving instead of hitting a dangling FK.
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

    /**
     * Splits the old wide NOTE_ITEM (is_asset, serial_number, a_f, quantity, the glpi_ and
     * return_ tracking columns) into the slim base table plus
     * NOTE_ITEM_ASSET/COUNTABLE/GLPI_TRACKING/RETURN_TRACKING — a row in a
     * subtype table now only ever exists when that dimension actually applies, instead of every
     * row always carrying every column with a 'N_A'/NULL sentinel for whatever doesn't apply.
     * No-ops once already migrated (is_asset no longer exists on NOTE_ITEM) — including on a
     * brand-new install, which got the slim shape straight from createHistoryTables().
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

            // SQLite's ALTER TABLE RENAME auto-rewrites the REFERENCES clause of any OTHER
            // table's schema that mentioned the renamed table by name. createHistoryTables()
            // (which runs before migrateSchema() in initialize()) already created these 4
            // subtype tables moments earlier — on this old-wide-shape database they never
            // existed before, so its own CREATE TABLE IF NOT EXISTS for them was NOT a no-op —
            // and their `REFERENCES NOTE_ITEM(id)` just got silently rewritten by the RENAME
            // above to point at NOTE_ITEM_OLD_20260722, which is then DROPped a few statements
            // down, leaving a dangling reference. Drop and recreate all 4 fresh here (they're
            // guaranteed empty either way — this whole method only ever runs once, the first
            // time a given database is migrated) so their FK unambiguously targets the new,
            // just-renamed-free NOTE_ITEM created right below, not a table that's about to stop
            // existing. Confirmed via PRAGMA foreign_key_list against the real data/noteapp.db —
            // this was silent (no FK violation, since PRAGMA foreign_keys is OFF here) but left
            // every one of the 4 tables with a broken/dangling reference, invisible until a tool
            // like DBeaver tried to render the relationship.
            stmt.executeUpdate("DROP TABLE IF EXISTS NOTE_ITEM_ASSET");
            stmt.executeUpdate("DROP TABLE IF EXISTS NOTE_ITEM_COUNTABLE");
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
                CREATE TABLE NOTE_ITEM_GLPI_TRACKING (
                    item_id           INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    status            TEXT NOT NULL,
                    rejection_reason  TEXT,
                    status_updated_at TEXT
                )""");
            stmt.executeUpdate("""
                CREATE TABLE NOTE_ITEM_RETURN_TRACKING (
                    item_id           INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    status            TEXT NOT NULL,
                    rejection_reason  TEXT,
                    status_updated_at TEXT
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
                INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
                SELECT id, glpi_status, glpi_rejection_reason, glpi_status_updated_at
                FROM NOTE_ITEM_OLD_20260722 WHERE glpi_status <> 'N_A'""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM_RETURN_TRACKING (item_id, status, rejection_reason, status_updated_at)
                SELECT id, return_status, return_rejection_reason, return_status_updated_at
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
     * Replaces NOTE_ITEM.type_name/brand_name/model_name and NOTE_PROVEEDOR.provider_name
     * (plain text snapshots) with real type_id/brand_id/model_id/provider_id foreign keys into
     * the catalog tables. Every distinct historical text value is resolved against the catalog
     * case-insensitively, regardless of a matching row's deprecated status (uniqueness on name
     * holds regardless of deprecated — see SqliteEquipmentService's rename methods); a value with
     * no match at all gets a brand-new deprecated=1 catalog row created for it, so a historical
     * value never silently becomes an active, selectable entry. No-ops once already migrated
     * (NOTE_ITEM/NOTE_PROVEEDOR no longer have their old text columns) — including on a brand-new
     * install, which gets the FK shape straight from createHistoryTables()/createEquipmentTables().
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

    // Builds the new table under a temporary name rather than renaming NOTE_ITEM itself —
    // NOTE_ITEM_ASSET/COUNTABLE/GLPI_TRACKING/RETURN_TRACKING all say REFERENCES NOTE_ITEM(id),
    // and SQLite's ALTER TABLE RENAME silently rewrites OTHER tables' schema to follow a renamed
    // table (the exact gotcha already hit and documented in migrateNoteItemSchema() above) —
    // renaming NOTE_ITEM here would leave those 4 already-populated subtype tables pointing at a
    // table that's about to be dropped. Building under a fresh name and only touching NOTE_ITEM
    // via DROP+RENAME-into-place at the very end means the subtype tables (never renamed, never
    // dropped) are untouched throughout and their FK just starts resolving correctly again the
    // moment the rename completes.
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
                    "INSERT INTO TYPE (name, is_asset, requires_serial, deprecated) VALUES (?, " + (isAssetItem ? 1 : 0) + ", 0, 1)");
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
     * NOTE_REPORT.sede used to be a plain free-text snapshot; sede_id (added 2026-07-24) is a
     * real FK into the new SEDE catalog table, mirroring the earlier TYPE/BRAND/MODEL/PROVIDER
     * catalog-FK redesign, so Sede can be renamed via the same deprecate/reactivate mechanism
     * without corrupting how old notes render. Backfills every existing row's free-text sede
     * into a matching (or newly created) SEDE row, resolved case-insensitively via the same
     * resolveOrCreateCatalogRow() helper the NOTE_ITEM/NOTE_PROVEEDOR backfill already uses.
     * Unlike that backfill, a newly created row here is left ACTIVE (deprecated=0), not 1 —
     * SEDE has no pre-existing admin-curated catalog to fall back on (it's a brand-new table),
     * so marking every backfilled row deprecated would leave the Settings combobox with zero
     * selectable options, blocking every technician from generating a note until an admin
     * manually reactivated each one. The old sede TEXT column is deliberately left in place,
     * unused — same "leave the old column, migrate forward" precedent already used for
     * TECHNICIAN_PROFILE/NOTE_REPORT.technician_id (no DROP COLUMN here either). Naturally
     * idempotent (only ever selects rows still missing sede_id) — safe to run every startup.
     */
    private void migrateSedeIdSchema(Connection conn, Statement stmt) throws SQLException {
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

    // Shared resolve-or-create for TYPE/BRAND/PROVIDER/SEDE: matched by a plain,
    // case-insensitive `name` lookup regardless of deprecated status (a name may only ever live
    // on one row at a time — see SqliteEquipmentService's rename logic), and insertSql is
    // expected to bind exactly one `?` for the name.
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
                is_asset INTEGER NOT NULL DEFAULT 1,
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
        // model, not scoped to any particular brand+type link (see SqliteEquipmentService's
        // getModelsForBrandAndType()/renameModel()). Every other MODEL row still has a real,
        // non-null brand_type_id.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS MODEL (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                brand_type_id INTEGER REFERENCES BRAND_TYPE_LINK(id),
                name          TEXT NOT NULL,
                deprecated    INTEGER NOT NULL DEFAULT 0
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

        // The index above never actually protects the global generic row (brand_type_id IS
        // NULL) — every SQL engine treats NULL as never-equal-to-NULL, even in a unique index,
        // so a plain UNIQUE(brand_type_id, name) silently permits any number of NULL-scoped
        // rows sharing the same name. Two narrower indexes fill that gap instead of indexing
        // brand_type_id itself: idx_model_global_generic_name keys off `name` (a real,
        // comparable value for every NULL-scoped row) to stop an accidental duplicate of the
        // exact same label; idx_model_single_active_generic keys off `deprecated` (constant 0
        // among the rows the filter matches) to guarantee at most one ACTIVE global row exists
        // at a time, regardless of how many renamed-away deprecated copies pile up over time.
        try {
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_model_global_generic_name ON MODEL(name) WHERE brand_type_id IS NULL");
            stmt.executeUpdate(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_model_single_active_generic ON MODEL(deprecated) WHERE brand_type_id IS NULL AND deprecated = 0");
        } catch (SQLException duplicatesExist) {
            // Same "fail safely" degradation as idx_model_brand_type_name above — an
            // installation that already has duplicate global-generic rows (shouldn't happen via
            // normal app use, but not impossible via direct DB access) just runs without this
            // DB-level backstop; SqliteEquipmentService's app-layer checks still apply.
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
    }

    private void createHistoryTables(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_REPORT (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at      TEXT NOT NULL,
                profile_type    TEXT NOT NULL,
                technician_name TEXT,
                technician_dni  TEXT,
                observations    TEXT,
                sede            TEXT,
                sede_id         INTEGER REFERENCES SEDE(id)
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ENTREGA_DEVOLUCION (
                note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
                user_name       TEXT,
                user_dni        TEXT,
                user_email      TEXT,
                motivo          TEXT,
                failure_cause   TEXT,
                failure_details TEXT,
                area_evento     TEXT
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

        // Slim base table — asset-only, countable-only, GLPI-tracking, and return-tracking
        // fields each live in their own subtype table below, so a row never carries a column
        // that doesn't apply to it (see migrateNoteItemSchema() for the pre-existing-database
        // migration path off the old wide 16-column shape).
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                note_id      INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
                type_id      INTEGER NOT NULL REFERENCES TYPE(id),
                brand_id     INTEGER NOT NULL REFERENCES BRAND(id),
                model_id     INTEGER NOT NULL REFERENCES MODEL(id),
                observations TEXT
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

        // No 'N_A' value/default here — a row simply doesn't exist for an item that isn't
        // GLPI-tracked (countable, or a Préstamo asset), instead of always existing with a
        // sentinel value. Same reasoning for NOTE_ITEM_RETURN_TRACKING below.
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM_GLPI_TRACKING (
                item_id           INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                status            TEXT NOT NULL,
                rejection_reason  TEXT,
                status_updated_at TEXT
            )""");

        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS NOTE_ITEM_RETURN_TRACKING (
                item_id           INTEGER PRIMARY KEY REFERENCES NOTE_ITEM(id),
                status            TEXT NOT NULL,
                rejection_reason  TEXT,
                status_updated_at TEXT
            )""");
    }

    private void createSettingsTable(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS APP_SETTINGS (
                key   TEXT PRIMARY KEY,
                value TEXT
            )""");
    }

    // Login-time role lookup (ADMIN starts admin mode already active, USER doesn't) — see
    // IUserRoleService. Not the same thing as AD group membership (which gates app access at
    // all, checked at login against the AD API) — this only distinguishes admin vs. normal
    // among users who already got past that gate.
    private void createUserRoleTable(Statement stmt) throws SQLException {
        stmt.executeUpdate("""
            CREATE TABLE IF NOT EXISTS USER_ROLE (
                username TEXT PRIMARY KEY,
                role     TEXT NOT NULL
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
