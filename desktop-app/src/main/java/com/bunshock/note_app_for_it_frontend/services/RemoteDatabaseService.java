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
        return DriverManager.getConnection(buildUrl(host, port, dbName), username, password);
    }

    public boolean testConnection() {
        if (!isConfigured()) return false;
        return testConnection(host, port, dbName, username, password);
    }

    public boolean testConnection(String host, int port, String dbName, String username, String password) {
        if (host == null || host.isBlank()) return false;
        try (Connection c = DriverManager.getConnection(buildUrl(host, port, dbName), username, password)) {
            c.createStatement().execute("SELECT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // trustServerCertificate=true is set unconditionally: a SQL Server Express instance almost
    // never has a real TLS certificate configured, and mssql-jdbc defaults to requiring
    // encryption — without this flag, a fresh Express install reliably fails to connect at all.
    private String buildUrl(String host, int port, String dbName) {
        return "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + dbName
            + ";encrypt=true;trustServerCertificate=true";
    }

    public void ensureSchema() throws SQLException {
        try (Connection c = getConnection(); Statement stmt = c.createStatement()) {
            createTableIfMissing(stmt, "TYPE", """
                CREATE TABLE TYPE (
                    id              INT IDENTITY(1,1) PRIMARY KEY,
                    name            NVARCHAR(255) NOT NULL UNIQUE,
                    is_asset        INT NOT NULL DEFAULT 1,
                    requires_serial INT NOT NULL DEFAULT 0,
                    deprecated      INT NOT NULL DEFAULT 0
                )""");
            createTableIfMissing(stmt, "BRAND", """
                CREATE TABLE BRAND (
                    id         INT IDENTITY(1,1) PRIMARY KEY,
                    name       NVARCHAR(255) NOT NULL UNIQUE,
                    deprecated INT NOT NULL DEFAULT 0
                )""");
            createTableIfMissing(stmt, "BRAND_TYPE_LINK", """
                CREATE TABLE BRAND_TYPE_LINK (
                    id       INT IDENTITY(1,1) PRIMARY KEY,
                    type_id  INT NOT NULL REFERENCES TYPE(id),
                    brand_id INT NOT NULL REFERENCES BRAND(id),
                    CONSTRAINT uq_brand_type_link UNIQUE(type_id, brand_id)
                )""");
            // brand_type_id is nullable — NULL is reserved for the single global "Genérico /
            // Otro" model, not scoped to any particular brand+type link (see
            // DatabaseService's identical SQLite schema for the full rationale).
            createTableIfMissing(stmt, "MODEL", """
                CREATE TABLE MODEL (
                    id            INT IDENTITY(1,1) PRIMARY KEY,
                    brand_type_id INT REFERENCES BRAND_TYPE_LINK(id),
                    name          NVARCHAR(255) NOT NULL,
                    deprecated    INT NOT NULL DEFAULT 0
                )""");
            // Wrapped locally: an existing database with pre-existing duplicate (brand_type_id,
            // name) rows would fail this statement — swallow it and degrade to app-layer-only
            // enforcement (SqliteEquipmentService.addModel/renameModel) rather than aborting the
            // whole ensureSchema() run, same "fail safely" pattern as the ADD COLUMN calls below.
            try {
                if (!indexExists(c, "idx_model_brand_type_name")) {
                    stmt.executeUpdate(
                        "CREATE UNIQUE INDEX idx_model_brand_type_name ON MODEL(brand_type_id, name)");
                }
            } catch (SQLException duplicatesExist) {
                // pre-existing duplicate model names for the same brand+type — see comment above
            }
            // Mirrors DatabaseService's identical pair of SQLite indexes — see that class for
            // the full rationale (idx_model_brand_type_name above never protects NULL-scoped
            // rows, since NULL is never-equal-to-NULL even in a unique index; these key off
            // `name`/`deprecated` instead, which hold real comparable values).
            try {
                if (!indexExists(c, "idx_model_global_generic_name")) {
                    stmt.executeUpdate(
                        "CREATE UNIQUE INDEX idx_model_global_generic_name ON MODEL(name) WHERE brand_type_id IS NULL");
                }
                if (!indexExists(c, "idx_model_single_active_generic")) {
                    stmt.executeUpdate(
                        "CREATE UNIQUE INDEX idx_model_single_active_generic ON MODEL(deprecated) WHERE brand_type_id IS NULL AND deprecated = 0");
                }
            } catch (SQLException duplicatesExist) {
                // same "fail safely" degradation as idx_model_brand_type_name above
            }
            createTableIfMissing(stmt, "SN_VALIDATION", """
                CREATE TABLE SN_VALIDATION (
                    id            INT IDENTITY(1,1) PRIMARY KEY,
                    model_id      INT NOT NULL REFERENCES MODEL(id),
                    regex_pattern NVARCHAR(500),
                    is_active     INT NOT NULL DEFAULT 1
                )""");
            createTableIfMissing(stmt, "PROVIDER", """
                CREATE TABLE PROVIDER (
                    id         INT IDENTITY(1,1) PRIMARY KEY,
                    name       NVARCHAR(255) NOT NULL UNIQUE,
                    deprecated INT NOT NULL DEFAULT 0
                )""");
            // Deprecated flag from day one — unlike TYPE/BRAND/MODEL/PROVIDER, SEDE never had a
            // pre-deprecated-flag era to migrate away from, so this needs no addColumnIfMissing call.
            createTableIfMissing(stmt, "SEDE", """
                CREATE TABLE SEDE (
                    id         INT IDENTITY(1,1) PRIMARY KEY,
                    name       NVARCHAR(255) NOT NULL UNIQUE,
                    deprecated INT NOT NULL DEFAULT 0
                )""");
            // Login-time role lookup (ADMIN starts admin mode already active) — not the same
            // thing as AD group membership, which gates app access at all and is checked
            // against the AD API at login time, not stored here.
            createTableIfMissing(stmt, "USER_ROLE", """
                CREATE TABLE USER_ROLE (
                    username NVARCHAR(100) PRIMARY KEY,
                    role     NVARCHAR(20) NOT NULL
                )""");
            // created_at/glpi_status_updated_at/return_status_updated_at are deliberately left as
            // NVARCHAR(MAX), not bounded — TODO: revisit these as a real DATETIME2 (or Postgres
            // TIMESTAMP) column once the remote engine choice is confirmed (see 2026-07-20 report
            // discussion in CLAUDE.md's "Free-text field DB-side length bounds" section). They're
            // plain ISO-8601 strings today (SqliteHistoryService.java), not yet real date/time
            // values, and SqliteHistoryService is reused unchanged for both the local SQLite and
            // remote connections, so this needs a coordinated read/write code change, not just DDL.
            createTableIfMissing(stmt, "NOTE_REPORT", """
                CREATE TABLE NOTE_REPORT (
                    id              INT IDENTITY(1,1) PRIMARY KEY,
                    created_at      NVARCHAR(MAX) NOT NULL,
                    profile_type    NVARCHAR(255) NOT NULL,
                    glpi_synced     INT NOT NULL DEFAULT 0,
                    technician_name NVARCHAR(255),
                    technician_dni  NVARCHAR(255),
                    observations    NVARCHAR(300),
                    sede            NVARCHAR(255),
                    sede_id         INT REFERENCES SEDE(id)
                )""");
            createTableIfMissing(stmt, "NOTE_ENTREGA_DEVOLUCION", """
                CREATE TABLE NOTE_ENTREGA_DEVOLUCION (
                    note_report_id  INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    user_name       NVARCHAR(255),
                    user_dni        NVARCHAR(255),
                    user_email      NVARCHAR(255),
                    motivo          NVARCHAR(100),
                    failure_cause   NVARCHAR(100),
                    failure_details NVARCHAR(200),
                    area_evento     NVARCHAR(200)
                )""");
            createTableIfMissing(stmt, "NOTE_PROVEEDOR", """
                CREATE TABLE NOTE_PROVEEDOR (
                    note_report_id   INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    provider_id      INT NOT NULL REFERENCES PROVIDER(id),
                    cuit             NVARCHAR(255),
                    motivo           NVARCHAR(100),
                    responsible_name NVARCHAR(255),
                    responsible_dni  NVARCHAR(255)
                )""");
            createTableIfMissing(stmt, "NOTE_REMITO", """
                CREATE TABLE NOTE_REMITO (
                    note_report_id    INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    destinatario_name NVARCHAR(255),
                    destinatario_area NVARCHAR(100),
                    destinatario_sede NVARCHAR(255),
                    remitente_name    NVARCHAR(255),
                    remitente_area    NVARCHAR(100),
                    remitente_sede    NVARCHAR(255)
                )""");
            // Slim base table — asset-only, countable-only, GLPI-tracking, and return-tracking
            // fields each live in their own subtype table below, so a row never carries a column
            // that doesn't apply to it. See the backfill/DROP COLUMN block further down for the
            // migration path off the old wide 16-column shape on an already-running installation.
            createTableIfMissing(stmt, "NOTE_ITEM", """
                CREATE TABLE NOTE_ITEM (
                    id           INT IDENTITY(1,1) PRIMARY KEY,
                    note_id      INT NOT NULL REFERENCES NOTE_REPORT(id),
                    type_id      INT NOT NULL REFERENCES TYPE(id),
                    brand_id     INT NOT NULL REFERENCES BRAND(id),
                    model_id     INT NOT NULL REFERENCES MODEL(id),
                    observations NVARCHAR(200)
                )""");
            createTableIfMissing(stmt, "NOTE_ITEM_ASSET", """
                CREATE TABLE NOTE_ITEM_ASSET (
                    item_id       INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    serial_number NVARCHAR(255),
                    a_f           NVARCHAR(255)
                )""");
            createTableIfMissing(stmt, "NOTE_ITEM_COUNTABLE", """
                CREATE TABLE NOTE_ITEM_COUNTABLE (
                    item_id  INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    quantity INT NOT NULL DEFAULT 1
                )""");
            // No 'N_A' value/default here — a row simply doesn't exist for an item that isn't
            // GLPI-tracked (countable, or a Préstamo asset), instead of always existing with a
            // sentinel value. Same reasoning for NOTE_ITEM_RETURN_TRACKING below.
            createTableIfMissing(stmt, "NOTE_ITEM_GLPI_TRACKING", """
                CREATE TABLE NOTE_ITEM_GLPI_TRACKING (
                    item_id           INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    status            NVARCHAR(50) NOT NULL,
                    rejection_reason  NVARCHAR(300),
                    status_updated_at NVARCHAR(MAX)
                )""");
            createTableIfMissing(stmt, "NOTE_ITEM_RETURN_TRACKING", """
                CREATE TABLE NOTE_ITEM_RETURN_TRACKING (
                    item_id           INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    status            NVARCHAR(50) NOT NULL,
                    rejection_reason  NVARCHAR(300),
                    status_updated_at NVARCHAR(MAX)
                )""");

            // Append-only — no app code ever updates or deletes a row here. Writes are
            // best-effort/fail-open (CachingAuditService swallows exceptions from both primary
            // and local) so a logging failure never blocks the real action being audited.
            createTableIfMissing(stmt, "LOGIN_AUDIT", """
                CREATE TABLE LOGIN_AUDIT (
                    id             INT IDENTITY(1,1) PRIMARY KEY,
                    username       NVARCHAR(100) NOT NULL,
                    attempted_at   NVARCHAR(MAX) NOT NULL,
                    success        INT NOT NULL,
                    failure_reason NVARCHAR(50)
                )""");
            // note_item_id is a real FK, not a generic entity_type/entity_id pair — every
            // current action either targets a NOTE_ITEM row or nothing at all
            // (DB_CONNECTION_CHANGED). Placed after NOTE_ITEM above, which it references.
            createTableIfMissing(stmt, "ACTION_AUDIT", """
                CREATE TABLE ACTION_AUDIT (
                    id           INT IDENTITY(1,1) PRIMARY KEY,
                    username     NVARCHAR(100) NOT NULL,
                    event_type   NVARCHAR(30) NOT NULL,
                    occurred_at  NVARCHAR(MAX) NOT NULL,
                    note_item_id INT REFERENCES NOTE_ITEM(id),
                    details      NVARCHAR(300)
                )""");

            // CREATE TABLE ... only-if-missing (above) silently no-ops on a database that already
            // has the table from an older schema version, so columns added later must be
            // migrated here too — every ADD COLUMN below is guarded by columnExists() since T-SQL
            // has no "ADD COLUMN IF NOT EXISTS" clause the way Postgres/SQLite do.
            boolean hadRequiresSerial = columnExists(c, "TYPE", "requires_serial");
            addColumnIfMissing(stmt, c, "TYPE", "requires_serial", "INT NOT NULL DEFAULT 0");
            if (!hadRequiresSerial) {
                // Backfill the type that used to be hardcoded as "always requires S/N"
                // (ItemDialogController's old "Notebook".equals(...) check) so existing
                // databases keep today's behavior instead of silently losing the rule.
                stmt.executeUpdate("UPDATE TYPE SET requires_serial = 1 WHERE LOWER(name) = 'notebook'");
            }
            addColumnIfMissing(stmt, c, "NOTE_ENTREGA_DEVOLUCION", "failure_cause", "NVARCHAR(100)");
            addColumnIfMissing(stmt, c, "NOTE_ENTREGA_DEVOLUCION", "failure_details", "NVARCHAR(200)");
            addColumnIfMissing(stmt, c, "NOTE_PROVEEDOR", "responsible_name", "NVARCHAR(255)");
            addColumnIfMissing(stmt, c, "NOTE_PROVEEDOR", "responsible_dni", "NVARCHAR(255)");
            addColumnIfMissing(stmt, c, "NOTE_REPORT", "technician_name", "NVARCHAR(255)");
            addColumnIfMissing(stmt, c, "NOTE_REPORT", "technician_dni", "NVARCHAR(255)");
            // Only relevant to a database still on the old wide NOTE_ITEM shape (is_asset
            // present) — on a brand-new install NOTE_ITEM is already the slim shape from the
            // createTableIfMissing calls above, and these columns must NOT be re-added there just
            // because they're "missing". Walking a genuinely old database up to the full wide
            // shape here is a prerequisite for the backfill/DROP COLUMN block further down, which
            // splits it into the 5-table shape.
            boolean noteItemStillWide = columnExists(c, "NOTE_ITEM", "is_asset");
            if (noteItemStillWide) {
                addColumnIfMissing(stmt, c, "NOTE_ITEM", "is_asset", "INT NOT NULL DEFAULT 0");
                addColumnIfMissing(stmt, c, "NOTE_ITEM", "glpi_status", "NVARCHAR(50) NOT NULL DEFAULT 'N_A'");
                addColumnIfMissing(stmt, c, "NOTE_ITEM", "glpi_rejection_reason", "NVARCHAR(300)");
                addColumnIfMissing(stmt, c, "NOTE_ITEM", "glpi_status_updated_at", "NVARCHAR(MAX)");
                addColumnIfMissing(stmt, c, "NOTE_ITEM", "return_status", "NVARCHAR(50) NOT NULL DEFAULT 'N_A'");
                addColumnIfMissing(stmt, c, "NOTE_ITEM", "return_rejection_reason", "NVARCHAR(300)");
                addColumnIfMissing(stmt, c, "NOTE_ITEM", "return_status_updated_at", "NVARCHAR(MAX)");
            }
            addColumnIfMissing(stmt, c, "NOTE_ENTREGA_DEVOLUCION", "area_evento", "NVARCHAR(200)");
            addColumnIfMissing(stmt, c, "NOTE_REPORT", "observations", "NVARCHAR(300)");
            addColumnIfMissing(stmt, c, "NOTE_REPORT", "sede", "NVARCHAR(255)");
            addColumnIfMissing(stmt, c, "NOTE_REPORT", "sede_id", "INT REFERENCES SEDE(id)");
            migrateSedeIdSchema(stmt, c);

            if (noteItemStillWide) {
                migrateNoteItemSchema(stmt, c);
            }

            addColumnIfMissing(stmt, c, "TYPE", "deprecated", "INT NOT NULL DEFAULT 0");
            addColumnIfMissing(stmt, c, "BRAND", "deprecated", "INT NOT NULL DEFAULT 0");
            addColumnIfMissing(stmt, c, "MODEL", "deprecated", "INT NOT NULL DEFAULT 0");
            addColumnIfMissing(stmt, c, "PROVIDER", "deprecated", "INT NOT NULL DEFAULT 0");

            // "Generic" -> "Genérico / Otro": one-time rename, see DatabaseService's identical
            // migration for the full rationale. No-op if no such row exists on this database.
            stmt.executeUpdate("UPDATE BRAND SET name = N'Genérico / Otro' WHERE name = 'Generic'");

            migrateCatalogFkSchema(stmt, c);
            migrateGenericModelSchema(stmt, c);
            cleanupStrayGenericBrandLinks(c);

            // Narrow any of the above columns that a pre-existing installation already created as
            // NVARCHAR(MAX) (either via an older CREATE TABLE or an older ADD COLUMN call above,
            // before these bounds existed) down to the new, real length limit — added 2026-07-20.
            // Both createTableIfMissing and addColumnIfMissing above already declare the bounded
            // type directly for a brand-new install/column, so this only ever does real work on an
            // already-running remote database.
            narrowNvarcharIfNeeded(stmt, c, "SN_VALIDATION", "regex_pattern", 500);
            narrowNvarcharIfNeeded(stmt, c, "NOTE_REPORT", "observations", 300);
            narrowNvarcharIfNeeded(stmt, c, "NOTE_ENTREGA_DEVOLUCION", "motivo", 100);
            narrowNvarcharIfNeeded(stmt, c, "NOTE_ENTREGA_DEVOLUCION", "failure_cause", 100);
            narrowNvarcharIfNeeded(stmt, c, "NOTE_ENTREGA_DEVOLUCION", "failure_details", 200);
            narrowNvarcharIfNeeded(stmt, c, "NOTE_ENTREGA_DEVOLUCION", "area_evento", 200);
            narrowNvarcharIfNeeded(stmt, c, "NOTE_PROVEEDOR", "motivo", 100);
            narrowNvarcharIfNeeded(stmt, c, "NOTE_ITEM", "observations", 200);
            // glpi_rejection_reason/return_rejection_reason used to live on NOTE_ITEM itself and
            // were narrowed here; they now live on NOTE_ITEM_GLPI_TRACKING/NOTE_ITEM_RETURN_TRACKING
            // (see migrateNoteItemSchema()), created with the NVARCHAR(300) bound from the start —
            // nothing left to narrow on this table for either column.
        }
    }

    /**
     * Backfills NOTE_ITEM_ASSET/COUNTABLE/GLPI_TRACKING/RETURN_TRACKING from the still-wide
     * NOTE_ITEM (called only when noteItemStillWide, i.e. is_asset still exists), then drops the
     * 10 now-redundant wide columns. Own transaction, separate from the rest of ensureSchema()'s
     * auto-committed statements, so a failure here doesn't leave the backfill half-applied.
     */
    private void migrateNoteItemSchema(Statement stmt, Connection c) throws SQLException {
        boolean originalAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
                SELECT id, serial_number, a_f FROM NOTE_ITEM WHERE is_asset = 1""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity)
                SELECT id, quantity FROM NOTE_ITEM WHERE is_asset = 0""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
                SELECT id, glpi_status, glpi_rejection_reason, glpi_status_updated_at
                FROM NOTE_ITEM WHERE glpi_status <> 'N_A'""");
            stmt.executeUpdate("""
                INSERT INTO NOTE_ITEM_RETURN_TRACKING (item_id, status, rejection_reason, status_updated_at)
                SELECT id, return_status, return_rejection_reason, return_status_updated_at
                FROM NOTE_ITEM WHERE return_status <> 'N_A'""");

            for (String col : new String[] {"serial_number", "a_f", "quantity", "is_asset",
                    "glpi_status", "glpi_rejection_reason", "glpi_status_updated_at",
                    "return_status", "return_rejection_reason", "return_status_updated_at"}) {
                dropDefaultConstraintIfAny(stmt, c, "NOTE_ITEM", col);
                stmt.executeUpdate("ALTER TABLE NOTE_ITEM DROP COLUMN " + col);
            }
            c.commit();
        } catch (SQLException migrationFailed) {
            c.rollback();
            throw migrationFailed;
        } finally {
            c.setAutoCommit(originalAutoCommit);
        }
    }

    // BRAND has no scoping FK, so it stays identified by name — duplicated from
    // DatabaseService's identical helper per this codebase's no-shared-abstraction convention.
    private String resolveGenericLabel() {
        try {
            AppConfig.CatalogConfig catalog = ConfigService.getInstance().getConfig().catalog;
            if (catalog != null && catalog.genericLabel != null && !catalog.genericLabel.isBlank()) {
                return catalog.genericLabel.trim();
            }
        } catch (IllegalStateException notLoaded) {
            // ConfigService not loaded in this context — use the default
        }
        return "Genérico / Otro";
    }

    private boolean isColumnNotNullable(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT IS_NULLABLE FROM information_schema.columns WHERE TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "NO".equalsIgnoreCase(rs.getString(1));
            }
        }
    }

    /**
     * SQL Server mirror of DatabaseService.migrateGenericModelSchema() — see that method's
     * Javadoc for the full rationale. T-SQL supports ALTER COLUMN natively, so unlike the SQLite
     * side this doesn't need a rebuild-under-a-temp-name dance: brand_type_id is relaxed to
     * nullable in place, then every existing per-link "Genérico / Otro" row is consolidated into
     * a single global row the same way. No-ops once already migrated (brand_type_id already
     * nullable) — including on a brand-new remote database, which gets the nullable column
     * straight from ensureSchema()'s CREATE TABLE. Unlike DatabaseService, there's no always-run
     * seed step for a brand-new database here either — same as BRAND's own "Genérico / Otro" row,
     * the remote catalog is only ever populated via CatalogMigrationTool or manual entry, never
     * auto-seeded.
     */
    private void migrateGenericModelSchema(Statement stmt, Connection c) throws SQLException {
        if (!isColumnNotNullable(c, "MODEL", "brand_type_id")) return;

        boolean originalAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            stmt.executeUpdate("ALTER TABLE MODEL ALTER COLUMN brand_type_id INT NULL");

            String label = resolveGenericLabel();

            // Promote one existing per-link row into the new global row (preserving its id)
            // rather than always inserting a brand-new one.
            Integer globalId = null;
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT TOP 1 id FROM MODEL WHERE brand_type_id IS NOT NULL AND LOWER(name) = LOWER(?) ORDER BY id")) {
                sel.setString(1, label);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) globalId = rs.getInt(1);
                }
            }
            if (globalId == null) {
                try (PreparedStatement ins = c.prepareStatement(
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
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE MODEL SET brand_type_id = NULL, deprecated = 0 WHERE id = ?")) {
                    up.setInt(1, globalId);
                    up.executeUpdate();
                }
            }

            // Re-point every historical NOTE_ITEM referencing any OTHER per-link "Genérico /
            // Otro" row onto the single new global row, then deprecate those now-unused rows.
            try (PreparedStatement up = c.prepareStatement("""
                    UPDATE NOTE_ITEM SET model_id = ?
                    WHERE model_id IN (
                        SELECT id FROM MODEL WHERE brand_type_id IS NOT NULL
                            AND LOWER(name) = LOWER(?) AND id <> ?
                    )""")) {
                up.setInt(1, globalId);
                up.setString(2, label);
                up.setInt(3, globalId);
                up.executeUpdate();
            }
            try (PreparedStatement dep = c.prepareStatement(
                    "UPDATE MODEL SET deprecated = 1 WHERE brand_type_id IS NOT NULL AND LOWER(name) = LOWER(?) AND id <> ?")) {
                dep.setString(1, label);
                dep.setInt(2, globalId);
                dep.executeUpdate();
            }

            c.commit();
        } catch (SQLException migrationFailed) {
            c.rollback();
            throw migrationFailed;
        } finally {
            c.setAutoCommit(originalAutoCommit);
        }
    }

    // SQL Server mirror of DatabaseService.cleanupStrayGenericBrandLinks() — see that method's
    // Javadoc for the full rationale (a BRAND_TYPE_LINK left over from before the generic brand
    // stopped needing one makes it render inconsistently for that one type vs. every other).
    private void cleanupStrayGenericBrandLinks(Connection c) throws SQLException {
        String label = resolveGenericLabel();
        Integer genericBrandId = null;
        try (PreparedStatement sel = c.prepareStatement(
                "SELECT id FROM BRAND WHERE LOWER(name) = LOWER(?)")) {
            sel.setString(1, label);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) genericBrandId = rs.getInt(1);
            }
        }
        if (genericBrandId == null) return;

        List<Integer> linkIds = new ArrayList<>();
        try (PreparedStatement sel = c.prepareStatement(
                "SELECT id FROM BRAND_TYPE_LINK WHERE brand_id = ?")) {
            sel.setInt(1, genericBrandId);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) linkIds.add(rs.getInt(1));
            }
        }

        for (int linkId : linkIds) {
            try (PreparedStatement count = c.prepareStatement(
                    "SELECT COUNT(*) FROM MODEL WHERE brand_type_id = ?")) {
                count.setInt(1, linkId);
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) continue;
                }
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM BRAND_TYPE_LINK WHERE id = ?")) {
                del.setInt(1, linkId);
                del.executeUpdate();
            }
        }
    }

    /**
     * SQL Server mirror of DatabaseService.migrateCatalogFkSchema() — see that method's Javadoc
     * for the full rationale. T-SQL supports ALTER COLUMN/ADD CONSTRAINT natively, so unlike the
     * SQLite side this doesn't need a rebuild-under-a-temp-name dance: nullable FK columns are
     * added, backfilled row by row, then tightened to NOT NULL with a real FK constraint, and the
     * old text columns are dropped last.
     */
    private void migrateCatalogFkSchema(Statement stmt, Connection c) throws SQLException {
        boolean needsNoteItem = columnExists(c, "NOTE_ITEM", "type_name");
        boolean needsNoteProveedor = columnExists(c, "NOTE_PROVEEDOR", "provider_name");
        if (!needsNoteItem && !needsNoteProveedor) return;

        boolean originalAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            if (needsNoteItem) migrateNoteItemToFk(stmt, c);
            if (needsNoteProveedor) migrateNoteProveedorToFk(stmt, c);
            c.commit();
        } catch (SQLException migrationFailed) {
            c.rollback();
            throw migrationFailed;
        } finally {
            c.setAutoCommit(originalAutoCommit);
        }
    }

    private void migrateNoteItemToFk(Statement stmt, Connection c) throws SQLException {
        stmt.executeUpdate("ALTER TABLE NOTE_ITEM ADD type_id INT NULL");
        stmt.executeUpdate("ALTER TABLE NOTE_ITEM ADD brand_id INT NULL");
        stmt.executeUpdate("ALTER TABLE NOTE_ITEM ADD model_id INT NULL");

        Map<String, Integer> typeCache = new HashMap<>();
        Map<String, Integer> brandCache = new HashMap<>();
        Map<String, Integer> linkCache = new HashMap<>();
        Map<String, Integer> modelCache = new HashMap<>();

        try (ResultSet rs = stmt.executeQuery(
                "SELECT id, type_name, brand_name, model_name FROM NOTE_ITEM");
             PreparedStatement update = c.prepareStatement(
                "UPDATE NOTE_ITEM SET type_id = ?, brand_id = ?, model_id = ? WHERE id = ?")) {
            while (rs.next()) {
                int itemId = rs.getInt("id");
                boolean isAssetItem = rowExists(c, "SELECT 1 FROM NOTE_ITEM_ASSET WHERE item_id = ?", itemId);

                String typeName = blankToFallback(rs.getString("type_name"), "Genérico / Otro");
                String brandName = blankToFallback(rs.getString("brand_name"), "Genérico / Otro");
                String modelName = blankToFallback(rs.getString("model_name"), "Genérico / Otro");

                int typeId = resolveOrCreateCatalogRow(c, typeCache, "TYPE", typeName,
                    "INSERT INTO TYPE (name, is_asset, requires_serial, deprecated) VALUES (?, "
                        + (isAssetItem ? 1 : 0) + ", 0, 1)");
                int brandId = resolveOrCreateCatalogRow(c, brandCache, "BRAND", brandName,
                    "INSERT INTO BRAND (name, deprecated) VALUES (?, 1)");
                int linkId = resolveOrCreateBrandTypeLink(c, linkCache, typeId, brandId);
                int modelId = resolveOrCreateModel(c, modelCache, linkId, modelName);

                update.setInt(1, typeId);
                update.setInt(2, brandId);
                update.setInt(3, modelId);
                update.setInt(4, itemId);
                update.executeUpdate();
            }
        }

        dropDefaultConstraintIfAny(stmt, c, "NOTE_ITEM", "type_name");
        dropDefaultConstraintIfAny(stmt, c, "NOTE_ITEM", "brand_name");
        dropDefaultConstraintIfAny(stmt, c, "NOTE_ITEM", "model_name");
        stmt.executeUpdate("ALTER TABLE NOTE_ITEM DROP COLUMN type_name, brand_name, model_name");

        stmt.executeUpdate("ALTER TABLE NOTE_ITEM ALTER COLUMN type_id INT NOT NULL");
        stmt.executeUpdate("ALTER TABLE NOTE_ITEM ALTER COLUMN brand_id INT NOT NULL");
        stmt.executeUpdate("ALTER TABLE NOTE_ITEM ALTER COLUMN model_id INT NOT NULL");
        stmt.executeUpdate("ALTER TABLE NOTE_ITEM ADD CONSTRAINT fk_note_item_type FOREIGN KEY (type_id) REFERENCES TYPE(id)");
        stmt.executeUpdate("ALTER TABLE NOTE_ITEM ADD CONSTRAINT fk_note_item_brand FOREIGN KEY (brand_id) REFERENCES BRAND(id)");
        stmt.executeUpdate("ALTER TABLE NOTE_ITEM ADD CONSTRAINT fk_note_item_model FOREIGN KEY (model_id) REFERENCES MODEL(id)");
    }

    // Nothing else references NOTE_PROVEEDOR by foreign key, so this is a simpler
    // add-nullable/backfill/tighten sequence than NOTE_ITEM's above.
    private void migrateNoteProveedorToFk(Statement stmt, Connection c) throws SQLException {
        stmt.executeUpdate("ALTER TABLE NOTE_PROVEEDOR ADD provider_id INT NULL");

        Map<String, Integer> providerCache = new HashMap<>();
        try (ResultSet rs = stmt.executeQuery("SELECT note_report_id, provider_name FROM NOTE_PROVEEDOR");
             PreparedStatement update = c.prepareStatement(
                "UPDATE NOTE_PROVEEDOR SET provider_id = ? WHERE note_report_id = ?")) {
            while (rs.next()) {
                String providerName = blankToFallback(rs.getString("provider_name"), "Desconocido");
                int providerId = resolveOrCreateCatalogRow(c, providerCache, "PROVIDER", providerName,
                    "INSERT INTO PROVIDER (name, deprecated) VALUES (?, 1)");
                update.setInt(1, providerId);
                update.setInt(2, rs.getInt("note_report_id"));
                update.executeUpdate();
            }
        }

        dropDefaultConstraintIfAny(stmt, c, "NOTE_PROVEEDOR", "provider_name");
        stmt.executeUpdate("ALTER TABLE NOTE_PROVEEDOR DROP COLUMN provider_name");
        stmt.executeUpdate("ALTER TABLE NOTE_PROVEEDOR ALTER COLUMN provider_id INT NOT NULL");
        stmt.executeUpdate(
            "ALTER TABLE NOTE_PROVEEDOR ADD CONSTRAINT fk_note_proveedor_provider FOREIGN KEY (provider_id) REFERENCES PROVIDER(id)");
    }

    private String blankToFallback(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private boolean rowExists(Connection c, String sql, int param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // Shared resolve-or-create for TYPE/BRAND/PROVIDER — see DatabaseService's identical helper
    // for the full rationale (uniqueness on name holds regardless of deprecated status).
    // SQL Server mirror of DatabaseService.migrateSedeIdSchema() — see that method's Javadoc for
    // the full rationale (why backfilled rows are left active, not deprecated, unlike the
    // NOTE_ITEM/NOTE_PROVEEDOR catalog-FK backfill).
    private void migrateSedeIdSchema(Statement stmt, Connection c) throws SQLException {
        Map<String, Integer> sedeCache = new HashMap<>();
        List<Integer> reportIds = new ArrayList<>();
        List<String> sedeTexts = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(
                "SELECT id, sede FROM NOTE_REPORT WHERE sede_id IS NULL AND sede IS NOT NULL AND LTRIM(RTRIM(sede)) <> ''")) {
            while (rs.next()) {
                reportIds.add(rs.getInt("id"));
                sedeTexts.add(rs.getString("sede"));
            }
        }
        for (int i = 0; i < reportIds.size(); i++) {
            int sedeId = resolveOrCreateCatalogRow(c, sedeCache, "SEDE", sedeTexts.get(i).trim(),
                "INSERT INTO SEDE (name, deprecated) VALUES (?, 0)");
            try (PreparedStatement up = c.prepareStatement("UPDATE NOTE_REPORT SET sede_id = ? WHERE id = ?")) {
                up.setInt(1, sedeId);
                up.setInt(2, reportIds.get(i));
                up.executeUpdate();
            }
        }
    }

    private int resolveOrCreateCatalogRow(Connection c, Map<String, Integer> cache,
            String table, String name, String insertSql) throws SQLException {
        String key = name.toLowerCase();
        Integer cached = cache.get(key);
        if (cached != null) return cached;

        Integer existing;
        try (PreparedStatement sel = c.prepareStatement(
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
            try (PreparedStatement ins = c.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
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

    private int resolveOrCreateBrandTypeLink(Connection c, Map<String, Integer> cache,
            int typeId, int brandId) throws SQLException {
        String key = typeId + "|" + brandId;
        Integer cached = cache.get(key);
        if (cached != null) return cached;

        Integer existing;
        try (PreparedStatement sel = c.prepareStatement(
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
            try (PreparedStatement ins = c.prepareStatement(
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

    private int resolveOrCreateModel(Connection c, Map<String, Integer> cache,
            int brandTypeId, String name) throws SQLException {
        String key = brandTypeId + "|" + name.toLowerCase();
        Integer cached = cache.get(key);
        if (cached != null) return cached;

        Integer existing;
        try (PreparedStatement sel = c.prepareStatement(
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
            try (PreparedStatement ins = c.prepareStatement(
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

    // SQL Server's ALTER TABLE ... DROP COLUMN fails if the column still has a DEFAULT
    // constraint attached — unlike SQLite, it doesn't drop the constraint automatically.
    private void dropDefaultConstraintIfAny(Statement stmt, Connection c, String table, String column)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT dc.name FROM sys.columns col
                JOIN sys.default_constraints dc ON col.default_object_id = dc.object_id
                WHERE col.object_id = OBJECT_ID(?) AND col.name = ?""")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stmt.executeUpdate("ALTER TABLE " + table + " DROP CONSTRAINT " + rs.getString(1));
                }
            }
        }
    }

    private void createTableIfMissing(Statement stmt, String table, String createSql) throws SQLException {
        if (!tableExists(stmt.getConnection(), table)) {
            stmt.executeUpdate(createSql);
        }
    }

    private boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM sys.tables WHERE name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean indexExists(Connection c, String indexName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM sys.indexes WHERE name = ?")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void addColumnIfMissing(Statement stmt, Connection c, String table, String column, String type)
            throws SQLException {
        if (!columnExists(c, table, column)) {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD " + column + " " + type);
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

    // Wrapped locally: an already-running installation may have rows whose current value is
    // longer than the new target length, which makes ALTER COLUMN fail with a truncation error.
    // Swallow that and leave the column at its current (wider) size rather than blocking
    // startup — same "fail safely" pattern as the unique index creation above. An admin who wants
    // the tighter bound enforced would need to first trim/fix the offending row(s) by hand.
    private void narrowNvarcharIfNeeded(Statement stmt, Connection c, String table, String column,
            int targetLength) throws SQLException {
        Integer currentLength = currentMaxLength(c, table, column);
        if (currentLength != null && currentLength != targetLength) {
            try {
                stmt.executeUpdate("ALTER TABLE " + table + " ALTER COLUMN " + column
                    + " NVARCHAR(" + targetLength + ")");
            } catch (SQLException dataAlreadyExceedsLimit) {
                // see comment above
            }
        }
    }

    // SQL Server reports NVARCHAR(MAX) as CHARACTER_MAXIMUM_LENGTH = -1, so this naturally
    // differs from any real target length and triggers the narrowing ALTER above.
    private Integer currentMaxLength(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns " +
                "WHERE lower(table_name) = ? AND lower(column_name) = ?")) {
            ps.setString(1, table.toLowerCase());
            ps.setString(2, column.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }
}
