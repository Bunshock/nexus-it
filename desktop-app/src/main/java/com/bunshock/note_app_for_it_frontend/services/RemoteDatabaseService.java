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
            // Row exists only once a superadmin has configured a Sede's Remito shipping info via
            // direct SQL — not nullable columns on SEDE itself, which every Sede would carry
            // regardless of whether shipping was ever configured for it.
            // deprecated-flag versioned, same pattern as TYPE/BRAND/MODEL/PROVIDER/SEDE — see
            // DatabaseService's identical SQLite table for the full rationale (NOTE_REMITO_SEDE
            // below references a specific row by FK instead of duplicating its text).
            createTableIfMissing(stmt, "SEDE_SHIPPING_INFO", """
                CREATE TABLE SEDE_SHIPPING_INFO (
                    id                INT IDENTITY(1,1) PRIMARY KEY,
                    sede_id           INT NOT NULL REFERENCES SEDE(id),
                    destination_label NVARCHAR(255) NOT NULL,
                    address           NVARCHAR(500),
                    recipients        NVARCHAR(500),
                    deprecated        INT NOT NULL DEFAULT 0
                )""");
            migrateSedeShippingInfoIdSchema(stmt, c);
            // Mirrors DatabaseService's identical SQLite MODEL_STOCK table — brand_type_id is
            // stored explicitly (not inferred from model_id) so the single global "Genérico /
            // Otro" model can carry an independent stock number per (Type,Brand) it's used under.
            // sede_id makes stock genuinely per-site: the same Model at two Sedes carries two
            // independent counts, not one shared global number.
            createTableIfMissing(stmt, "MODEL_STOCK", """
                CREATE TABLE MODEL_STOCK (
                    brand_type_id INT NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    model_id      INT NOT NULL REFERENCES MODEL(id),
                    sede_id       INT NOT NULL REFERENCES SEDE(id),
                    stock         INT NOT NULL DEFAULT 0,
                    CONSTRAINT pk_model_stock PRIMARY KEY (brand_type_id, model_id, sede_id)
                )""");
            migrateModelStockSedeSchema(stmt, c);
            // Login-time role/sede/permission lookup — not the same thing as AD group
            // membership, which gates app access at all and is checked against the AD API at
            // login time, not stored here. Named APP_USER, not USER — USER is a reserved
            // keyword (a niladic function) in T-SQL.
            boolean appUserExisted = tableExists(c, "APP_USER");
            createTableIfMissing(stmt, "APP_USER", """
                CREATE TABLE APP_USER (
                    id       INT IDENTITY(1,1) PRIMARY KEY,
                    username NVARCHAR(100) NOT NULL UNIQUE,
                    role     NVARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ADMIN', 'SUPERADMIN')),
                    sede_id  INT REFERENCES SEDE(id)
                )""");
            if (!appUserExisted) migrateUserRoleIntoAppUser(stmt, c);

            // A permission not present here is denied — there is no separate "explicitly
            // denied" state (see models.Permission). Seeded only the first time this table is
            // created, not on every startup, so a superadmin's later revocation (a DELETE
            // against this table) isn't silently undone on next launch.
            boolean rolePermissionExisted = tableExists(c, "ROLE_PERMISSION");
            createTableIfMissing(stmt, "ROLE_PERMISSION", """
                CREATE TABLE ROLE_PERMISSION (
                    role       NVARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ADMIN', 'SUPERADMIN')),
                    permission NVARCHAR(50) NOT NULL,
                    CONSTRAINT pk_role_permission PRIMARY KEY (role, permission)
                )""");
            if (!rolePermissionExisted) seedDefaultRolePermissions(stmt);

            // Append-only audit trail — see DatabaseService's identical SQLite copy for the full
            // rationale on why AUDIT_ADMIN_ACTION is generic (target_id TEXT) while
            // AUDIT_STOCK/AUDIT_ITEM_STATUS stay typed, and why old_value/new_value must never
            // hold an actual secret value, and why APP_USER/ROLE_PERMISSION changes (direct-SQL
            // only) are structurally outside what an app-level audit table can ever see.
            createTableIfMissing(stmt, "AUDIT_LOGIN", """
                CREATE TABLE AUDIT_LOGIN (
                    id             INT IDENTITY(1,1) PRIMARY KEY,
                    username       NVARCHAR(100) NOT NULL,
                    success        INT NOT NULL,
                    failure_reason NVARCHAR(255),
                    attempted_at   DATETIME2 NOT NULL
                )""");
            createTableIfMissing(stmt, "AUDIT_STOCK", """
                CREATE TABLE AUDIT_STOCK (
                    id            INT IDENTITY(1,1) PRIMARY KEY,
                    brand_type_id INT NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    model_id      INT NOT NULL REFERENCES MODEL(id),
                    sede_id       INT NOT NULL REFERENCES SEDE(id),
                    username      NVARCHAR(100) NOT NULL,
                    old_stock     INT NOT NULL,
                    new_stock     INT NOT NULL,
                    reason        NVARCHAR(500) NOT NULL,
                    changed_at    DATETIME2 NOT NULL
                )""");
            // AUDIT_ITEM_STATUS (references NOTE_ITEM) is created further down, right after
            // NOTE_ITEM and its subtype tables exist — SQL Server validates FK targets at
            // CREATE TABLE time, unlike SQLite, so it can't be declared this early.
            createTableIfMissing(stmt, "AUDIT_ADMIN_ACTION", """
                CREATE TABLE AUDIT_ADMIN_ACTION (
                    id           INT IDENTITY(1,1) PRIMARY KEY,
                    username     NVARCHAR(100) NOT NULL,
                    action       NVARCHAR(100) NOT NULL,
                    target_type  NVARCHAR(100) NOT NULL,
                    target_id    NVARCHAR(255),
                    old_value    NVARCHAR(1000),
                    new_value    NVARCHAR(1000),
                    reason       NVARCHAR(500),
                    performed_at DATETIME2 NOT NULL
                )""");

            // created_at is a real DATETIME2 — see dropDeadNoteReportColumns()/
            // migrateTimestampColumnsToDatetime2() below for the migration path on an
            // already-running installation). SqliteHistoryService writes it via plain
            // setString(LocalDateTime.toString()) on both engines (SQL Server implicitly
            // converts an ISO-8601 'T'-separated string to DATETIME2 on INSERT/UPDATE — a
            // documented, locale-independent conversion) and reads it back tolerant of either
            // engine's getString() rendering (parseStoredTimestamp()), so no write-path change
            // was needed, only the column type plus a tolerant read.
            // glpi_synced/sede were both dead (no reader/writer anywhere in the app — sede was
            // superseded by sede_id, glpi_synced was never wired to anything) — dropped,
            // see dropDeadNoteReportColumns().
            createTableIfMissing(stmt, "NOTE_REPORT", """
                CREATE TABLE NOTE_REPORT (
                    id              INT IDENTITY(1,1) PRIMARY KEY,
                    created_at      DATETIME2 NOT NULL,
                    profile_type    NVARCHAR(255) NOT NULL,
                    technician_name NVARCHAR(255),
                    technician_dni  NVARCHAR(255),
                    observations      NVARCHAR(300),
                    sede_id           INT REFERENCES SEDE(id),
                    approval_status   NVARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    stock_applied     INT NOT NULL DEFAULT 0
                )""");
            // Row exists only for a note an admin has actually rejected — not a NULL sentinel on
            // every NOTE_REPORT row that's PENDING/APPROVED, see DatabaseService's identical
            // SQLite table for the full rationale.
            createTableIfMissing(stmt, "NOTE_REPORT_REJECTION", """
                CREATE TABLE NOTE_REPORT_REJECTION (
                    note_report_id   INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    rejection_reason NVARCHAR(300) NOT NULL
                )""");
            createTableIfMissing(stmt, "NOTE_ENTREGA_DEVOLUCION", """
                CREATE TABLE NOTE_ENTREGA_DEVOLUCION (
                    note_report_id  INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    user_name       NVARCHAR(255),
                    user_dni        NVARCHAR(255),
                    user_email      NVARCHAR(255),
                    motivo          NVARCHAR(100)
                )""");
            // Row exists only for a Devolución note whose Motivo triggered the Falla popup — not
            // a NULL sentinel on every ENTREGA_DEVOLUCION row, see DatabaseService's identical
            // SQLite table for the full rationale.
            createTableIfMissing(stmt, "NOTE_DEVOLUCION_FALLA", """
                CREATE TABLE NOTE_DEVOLUCION_FALLA (
                    note_report_id  INT PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
                    failure_cause   NVARCHAR(100) NOT NULL,
                    failure_details NVARCHAR(200)
                )""");
            // Row exists only when a Préstamo note actually captured an Área/Evento (itself
            // optional even within Préstamo) — not a NULL sentinel on every ENTREGA_DEVOLUCION
            // row regardless of profile type (same reasoning as above).
            createTableIfMissing(stmt, "NOTE_PRESTAMO_AREA_EVENTO", """
                CREATE TABLE NOTE_PRESTAMO_AREA_EVENTO (
                    note_report_id INT PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
                    area_evento    NVARCHAR(200) NOT NULL
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
            // Split into two mutually-exclusive subtype tables — see DatabaseService's identical
            // SQLite tables for the full rationale (a catalog-Sede destination's 3 text fields
            // are locked/disabled in RemitoNoteController the moment a Sede is picked, so they can
            // only ever equal that Sede's currently-active SEDE_SHIPPING_INFO row — referencing
            // it by FK is safe and avoids duplicating the text at all; a custom/manual
            // destination has no catalog row to reference, so NOTE_REMITO_OTHER keeps its own
            // free text).
            createTableIfMissing(stmt, "NOTE_REMITO_SEDE", """
                CREATE TABLE NOTE_REMITO_SEDE (
                    note_report_id   INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    shipping_info_id INT NOT NULL REFERENCES SEDE_SHIPPING_INFO(id)
                )""");
            createTableIfMissing(stmt, "NOTE_REMITO_OTHER", """
                CREATE TABLE NOTE_REMITO_OTHER (
                    note_report_id     INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
                    destination_label  NVARCHAR(255) NOT NULL,
                    address             NVARCHAR(500),
                    recipients          NVARCHAR(500)
                )""");
            migrateNoteRemitoSplitSchema(stmt, c);
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
                    observations NVARCHAR(200),
                    modifies_stock INT NOT NULL DEFAULT 1
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
                    status_updated_at DATETIME2
                )""");
            createTableIfMissing(stmt, "NOTE_ITEM_RETURN_TRACKING", """
                CREATE TABLE NOTE_ITEM_RETURN_TRACKING (
                    item_id           INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    status            NVARCHAR(50) NOT NULL,
                    rejection_reason  NVARCHAR(300),
                    status_updated_at DATETIME2
                )""");
            // A second, independent GLPI dimension for a returnable Provider note's asset items
            // only (Provider assets, unlike Préstamo's, get real GLPI tracking). GLPI sync is
            // one-way/no-revert, so the original sync-out (NOTE_ITEM_GLPI_TRACKING) can never be
            // "undone" to reflect an item coming back — this table tracks the separate "synced
            // back into GLPI" event instead. Row absence means not applicable yet; a row is only
            // created once the item's return is actually validated (RETURNED), seeded PENDING.
            createTableIfMissing(stmt, "NOTE_ITEM_GLPI_RETURN_TRACKING", """
                CREATE TABLE NOTE_ITEM_GLPI_RETURN_TRACKING (
                    item_id           INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    status            NVARCHAR(50) NOT NULL,
                    rejection_reason  NVARCHAR(300),
                    status_updated_at DATETIME2
                )""");
            // Row exists only for an item flagged "no modifica stock" — the overwhelming majority
            // of items never use this exception, so the reason lives here rather than as an
            // always-present-but-usually-NULL column on NOTE_ITEM itself, same "row-absence means
            // not applicable" precedent as every other conditional-reason table in this schema.
            createTableIfMissing(stmt, "NOTE_ITEM_STOCK_EXCEPTION", """
                CREATE TABLE NOTE_ITEM_STOCK_EXCEPTION (
                    item_id INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
                    reason  NVARCHAR(300) NOT NULL
                )""");
            // Covers both GLPI and Préstamo/Provider return-status transitions in one table via
            // a status_kind discriminator — see DatabaseService's identical SQLite copy for the
            // full rationale (both are the same "item's status moved from A to B" shape, and
            // NOTE_ITEM_GLPI_TRACKING/NOTE_ITEM_RETURN_TRACKING only ever keep the latest status,
            // never prior transitions).
            createTableIfMissing(stmt, "AUDIT_ITEM_STATUS", """
                CREATE TABLE AUDIT_ITEM_STATUS (
                    id          INT IDENTITY(1,1) PRIMARY KEY,
                    item_id     INT NOT NULL REFERENCES NOTE_ITEM(id),
                    status_kind NVARCHAR(20) NOT NULL CHECK (status_kind IN ('GLPI', 'RETURN')),
                    old_status  NVARCHAR(50) NOT NULL,
                    new_status  NVARCHAR(50) NOT NULL,
                    reason      NVARCHAR(500),
                    quantity    INT NOT NULL DEFAULT 1,
                    username    NVARCHAR(100) NOT NULL,
                    changed_at  DATETIME2 NOT NULL
                )""");
            // Added after AUDIT_ITEM_STATUS's own CREATE TABLE already shipped once — see
            // DatabaseService's identical SQLite migration for why this can't just live in the
            // CREATE TABLE block alone.
            addColumnIfMissing(stmt, c, "AUDIT_ITEM_STATUS", "quantity", "INT NOT NULL DEFAULT 1");
            // Countable items can be resolved in partial batches over time (e.g. 5 loaned
            // headsets: 3 returned now, 1 lost later, 1 still pending) — a single status column
            // can't express that, so each partial action gets its own append-only row here
            // instead. PENDING is never stored — remaining pending quantity is always
            // NOTE_ITEM_COUNTABLE.quantity minus the sum of allocations for that item, same "row
            // absence is the state" convention as every other tracking table in this schema.
            // Asset items never get a row here (a physical unit isn't divisible) — they stay on
            // NOTE_ITEM_RETURN_TRACKING.status exactly as before.
            createTableIfMissing(stmt, "NOTE_ITEM_RETURN_ALLOCATION", """
                CREATE TABLE NOTE_ITEM_RETURN_ALLOCATION (
                    id         INT IDENTITY(1,1) PRIMARY KEY,
                    item_id    INT NOT NULL REFERENCES NOTE_ITEM(id),
                    status     NVARCHAR(50) NOT NULL,
                    quantity   INT NOT NULL,
                    reason     NVARCHAR(300),
                    updated_at DATETIME2 NOT NULL
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
            // failure_cause/failure_details (the old, already-released columns) are deliberately
            // NOT re-added here — they're dead going forward, split into
            // NOTE_DEVOLUCION_FALLA/NOTE_PRESTAMO_AREA_EVENTO instead (see
            // migrateEntregaDevolucionSplitSchema() below), same "stop re-adding a retired
            // column" precedent as NOTE_REPORT.sede.
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
            // area_evento (the old, already-released column) is deliberately NOT re-added here —
            // see the failure_cause/failure_details comment above; same reasoning.
            addColumnIfMissing(stmt, c, "NOTE_REPORT", "observations", "NVARCHAR(300)");
            addColumnIfMissing(stmt, c, "NOTE_REPORT", "sede_id", "INT REFERENCES SEDE(id)");
            migrateSedeIdSchema(stmt, c);
            addColumnIfMissing(stmt, c, "NOTE_REPORT", "approval_status", "NVARCHAR(20) NOT NULL DEFAULT 'PENDING'");
            // rejection_reason is deliberately NOT re-added here — see
            // migrateRejectionReasonSchema() below, same "stop re-adding a retired column"
            // precedent as NOTE_REPORT.sede.
            dropDeadNoteReportColumns(stmt, c);
            migrateRejectionReasonSchema(stmt, c);
            migrateEntregaDevolucionSplitSchema(stmt, c);
            migrateTimestampColumnsToDatetime2(stmt, c);
            addColumnIfMissing(stmt, c, "NOTE_REPORT", "stock_applied", "INT NOT NULL DEFAULT 0");
            migrateStockAppliedSchema(stmt, c);
            // approval_status's rejected value was originally the Spanish "RECHAZADO" —
            // inconsistent with its siblings PENDING/APPROVED (English). Renamed to REJECTED in
            // code; any row already written under the old value must be updated too, or it
            // silently stops matching any switch/if branch. Safe on every run — a no-op once no
            // row has the old value left.
            stmt.executeUpdate("UPDATE NOTE_REPORT SET approval_status = 'REJECTED' WHERE approval_status = 'RECHAZADO'");
            // profile_type's Provider-note value was originally mixed-case "Entrega - Proveedor"
            // — inconsistent with the other ALL-CAPS literal values. See DatabaseService's
            // identical SQLite migration for the full rationale.
            stmt.executeUpdate("UPDATE NOTE_REPORT SET profile_type = 'ENTREGA - PROVEEDOR' WHERE profile_type = 'Entrega - Proveedor'");

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

            // DEFAULT 1 preserves existing items' current behavior. modifies_stock itself was
            // previously only declared on the CREATE TABLE path — createTableIfMissing() is a
            // no-op once NOTE_ITEM already exists, so an already-running installation never
            // actually got this column added until now.
            addColumnIfMissing(stmt, c, "NOTE_ITEM", "modifies_stock", "INT NOT NULL DEFAULT 1");
            migrateStockExceptionReasonSchema(stmt, c);

            // Narrow any of the above columns that a pre-existing installation already created as
            // NVARCHAR(MAX) (either via an older CREATE TABLE or an older ADD COLUMN call above,
            // before these bounds existed) down to the new, real length limit.
            // Both createTableIfMissing and addColumnIfMissing above already declare the bounded
            // type directly for a brand-new install/column, so this only ever does real work on an
            // already-running remote database.
            narrowNvarcharIfNeeded(stmt, c, "SN_VALIDATION", "regex_pattern", 500);
            narrowNvarcharIfNeeded(stmt, c, "NOTE_REPORT", "observations", 300);
            narrowNvarcharIfNeeded(stmt, c, "NOTE_ENTREGA_DEVOLUCION", "motivo", 100);
            narrowNvarcharIfNeeded(stmt, c, "NOTE_PROVEEDOR", "motivo", 100);
            narrowNvarcharIfNeeded(stmt, c, "NOTE_ITEM", "observations", 200);
            // glpi_rejection_reason/return_rejection_reason used to live on NOTE_ITEM itself and
            // were narrowed here; they now live on NOTE_ITEM_GLPI_TRACKING/NOTE_ITEM_RETURN_TRACKING
            // (see migrateNoteItemSchema()), created with the NVARCHAR(300) bound from the start —
            // nothing left to narrow on this table for either column. Same reasoning for
            // failure_cause/failure_details/area_evento/rejection_reason (split into
            // NOTE_DEVOLUCION_FALLA/NOTE_PRESTAMO_AREA_EVENTO/NOTE_REPORT_REJECTION) — each new
            // table is only ever created with its bounded NVARCHAR(n) type from the start, so
            // there's nothing pre-existing on it to narrow.
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
            // A link with zero MODEL rows can still carry a legitimate MODEL_STOCK row for the
            // global generic model — deleting the link here would silently drop that stock
            // number on next startup. See DatabaseService's identical check for the full reasoning.
            try (PreparedStatement count = c.prepareStatement(
                    "SELECT COUNT(*) FROM MODEL_STOCK WHERE brand_type_id = ?")) {
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

    // MODEL_STOCK gained sede_id as part of its primary key — mirrors DatabaseService's identical
    // SQLite migration. Nothing else has an FK pointing INTO MODEL_STOCK, so an already-running
    // installation's old-shape table is simply dropped and recreated rather than attempting to
    // split its existing numbers across Sedes — explicit user decision: every (model, Sede) pair
    // starts at 0, and an admin re-enters real counts going forward.
    private void migrateModelStockSedeSchema(Statement stmt, Connection c) throws SQLException {
        if (tableExists(c, "MODEL_STOCK") && !columnExists(c, "MODEL_STOCK", "sede_id")) {
            stmt.executeUpdate("DROP TABLE MODEL_STOCK");
            stmt.executeUpdate("""
                CREATE TABLE MODEL_STOCK (
                    brand_type_id INT NOT NULL REFERENCES BRAND_TYPE_LINK(id),
                    model_id      INT NOT NULL REFERENCES MODEL(id),
                    sede_id       INT NOT NULL REFERENCES SEDE(id),
                    stock         INT NOT NULL DEFAULT 0,
                    CONSTRAINT pk_model_stock PRIMARY KEY (brand_type_id, model_id, sede_id)
                )""");
        }
    }

    // SEDE_SHIPPING_INFO's PK changed from sede_id itself to a surrogate id — see
    // DatabaseService.migrateSedeShippingInfoIdSchema()'s SQLite mirror for the full rationale.
    // T-SQL supports adding an IDENTITY column to an existing (non-empty) table directly via
    // ALTER TABLE ADD, so unlike the SQLite side this doesn't need a rebuild-under-a-temp-name
    // dance — just drop the old inline PK constraint (auto-named by SQL Server, so its name has
    // to be looked up dynamically first, same as dropDefaultConstraintIfAny() does for DEFAULT
    // constraints) and add the new one. No live SQL Server instance in this project's test
    // infrastructure to validate the IDENTITY-on-a-non-empty-table behavior against — same
    // limitation already accepted elsewhere in this file. No-ops (past the index check) once
    // already migrated (id column present), including on a brand-new install.
    private void migrateSedeShippingInfoIdSchema(Statement stmt, Connection c) throws SQLException {
        if (tableExists(c, "SEDE_SHIPPING_INFO") && !columnExists(c, "SEDE_SHIPPING_INFO", "id")) {
            boolean originalAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                dropPrimaryKeyConstraint(stmt, c, "SEDE_SHIPPING_INFO");
                stmt.executeUpdate("ALTER TABLE SEDE_SHIPPING_INFO ADD id INT IDENTITY(1,1)");
                stmt.executeUpdate("ALTER TABLE SEDE_SHIPPING_INFO ADD CONSTRAINT pk_sede_shipping_info PRIMARY KEY (id)");
                addColumnIfMissing(stmt, c, "SEDE_SHIPPING_INFO", "deprecated", "INT NOT NULL DEFAULT 0");
                c.commit();
            } catch (SQLException migrationFailed) {
                c.rollback();
                throw migrationFailed;
            } finally {
                c.setAutoCommit(originalAutoCommit);
            }
        }

        // Mirrors DatabaseService's identical SQLite index — "at most one active row per Sede."
        // Wrapped the same "fail safely" way as the MODEL indexes above: a pre-existing
        // installation with duplicate active rows for the same Sede degrades to app-layer-only
        // enforcement rather than blocking startup.
        try {
            if (!indexExists(c, "idx_sede_shipping_single_active")) {
                stmt.executeUpdate(
                    "CREATE UNIQUE INDEX idx_sede_shipping_single_active ON SEDE_SHIPPING_INFO(sede_id) WHERE deprecated = 0");
            }
        } catch (SQLException duplicatesExist) {
            // same "fail safely" degradation as idx_model_brand_type_name above
        }
    }

    private void dropPrimaryKeyConstraint(Statement stmt, Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT kc.name FROM sys.key_constraints kc
                WHERE kc.parent_object_id = OBJECT_ID(?) AND kc.type = 'PK'""")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stmt.executeUpdate("ALTER TABLE " + table + " DROP CONSTRAINT " + rs.getString(1));
                }
            }
        }
    }

    // SQL Server mirror of DatabaseService.migrateNoteRemitoSplitSchema() — see that method's
    // Javadoc for the full rationale. Must run after migrateSedeShippingInfoIdSchema() — relies on
    // SEDE_SHIPPING_INFO already having its id/deprecated columns. Row-by-row JDBC backfill, same
    // shape as migrateNoteItemToFk()/migrateNoteProveedorToFk() above, since this needs per-row
    // resolve-or-create logic no single set-based SQL statement can express cleanly.
    private void migrateNoteRemitoSplitSchema(Statement stmt, Connection c) throws SQLException {
        if (!tableExists(c, "NOTE_REMITO")) return;

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
                    c, shippingInfoCache, sedeId, label, address, recipients);
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO NOTE_REMITO_SEDE (note_report_id, shipping_info_id) VALUES (?, ?)")) {
                    ins.setInt(1, reportId);
                    ins.setInt(2, shippingInfoId);
                    ins.executeUpdate();
                }
            } else {
                try (PreparedStatement ins = c.prepareStatement("""
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

    // T-SQL's IS operator only compares against the NULL literal, not an arbitrary bound
    // parameter (unlike SQLite's, see DatabaseService's identical helper) — the explicit
    // "(col IS NULL AND ? IS NULL) OR col = ?" form is the portable null-safe equality idiom here.
    private int resolveOrCreateShippingInfoRow(Connection c, Map<String, Integer> cache,
            int sedeId, String label, String address, String recipients) throws SQLException {
        String key = sedeId + "|" + label + "|" + address + "|" + recipients;
        Integer cached = cache.get(key);
        if (cached != null) return cached;

        Integer existing = null;
        try (PreparedStatement sel = c.prepareStatement("""
                SELECT id FROM SEDE_SHIPPING_INFO
                WHERE sede_id = ? AND destination_label = ?
                  AND ((address IS NULL AND ? IS NULL) OR address = ?)
                  AND ((recipients IS NULL AND ? IS NULL) OR recipients = ?)
                """)) {
            sel.setInt(1, sedeId);
            sel.setString(2, label);
            sel.setString(3, address);
            sel.setString(4, address);
            sel.setString(5, recipients);
            sel.setString(6, recipients);
            try (ResultSet rs = sel.executeQuery()) {
                if (rs.next()) existing = rs.getInt("id");
            }
        }
        if (existing == null) {
            try (PreparedStatement ins = c.prepareStatement(
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
        if (!columnExists(c, "NOTE_REPORT", "sede")) return;
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

    // Carries existing role assignments forward onto the new surrogate-keyed table; sede_id
    // starts NULL for every migrated row (Sede assignment is a new, separate concept — a
    // superadmin must assign it by hand afterward, same as for a brand-new user).
    private void migrateUserRoleIntoAppUser(Statement stmt, Connection c) throws SQLException {
        if (!tableExists(c, "USER_ROLE")) return;
        stmt.executeUpdate("""
            INSERT INTO APP_USER (username, role, sede_id)
            SELECT username, role, NULL FROM USER_ROLE u
            WHERE NOT EXISTS (SELECT 1 FROM APP_USER a WHERE a.username = u.username)
            """);
        stmt.executeUpdate("DROP TABLE USER_ROLE");
    }

    // Matches today's status quo: ADMIN could already do everything except the newly-introduced
    // EDIT_SMTP_CONFIG; SUPERADMIN gets everything including that. Enumerated from the
    // Permission enum itself (not hand-typed strings) so this can't drift out of sync with it.
    private void seedDefaultRolePermissions(Statement stmt) throws SQLException {
        for (com.bunshock.note_app_for_it_frontend.models.Permission p
                : com.bunshock.note_app_for_it_frontend.models.Permission.values()) {
            if (p != com.bunshock.note_app_for_it_frontend.models.Permission.EDIT_SMTP_CONFIG) {
                stmt.executeUpdate("INSERT INTO ROLE_PERMISSION (role, permission) VALUES ('ADMIN', '" + p.name() + "')");
            }
            stmt.executeUpdate("INSERT INTO ROLE_PERMISSION (role, permission) VALUES ('SUPERADMIN', '" + p.name() + "')");
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

    // glpi_synced/sede on NOTE_REPORT were both confirmed dead (no reader/writer anywhere in the
    // app — sede was superseded by sede_id, glpi_synced was never wired to anything). A brand-new
    // install never creates either column (see the CREATE TABLE above); this only fires on an
    // already-running installation that still has one or both. Wrapped in try/catch per the
    // existing "fail safely, don't block startup" convention used by narrowNvarcharIfNeeded()
    // above — an installation with unexpected constraints on either column just keeps them until
    // an admin investigates, rather than failing ensureSchema() outright.
    private void dropDeadNoteReportColumns(Statement stmt, Connection c) throws SQLException {
        if (columnExists(c, "NOTE_REPORT", "glpi_synced")) {
            try {
                dropDefaultConstraintIfAny(stmt, c, "NOTE_REPORT", "glpi_synced");
                stmt.executeUpdate("ALTER TABLE NOTE_REPORT DROP COLUMN glpi_synced");
            } catch (SQLException ignored) {
                // leave it for next startup to retry
            }
        }
        if (columnExists(c, "NOTE_REPORT", "sede")) {
            try {
                stmt.executeUpdate("ALTER TABLE NOTE_REPORT DROP COLUMN sede");
            } catch (SQLException ignored) {
                // leave it for next startup to retry
            }
        }
    }

    // SQL Server mirror of DatabaseService.migrateStockAppliedSchema() — consolidates the
    // double-approval stock guard onto one shared NOTE_REPORT.stock_applied column instead of
    // NOTE_REMITO's own, now that every note type moves stock on approval, not just Remito.
    // Guarded on NOTE_REMITO still having its own stock_applied column — a no-op once already
    // migrated, including on a brand-new install, which never creates that column at all.
    private void migrateStockAppliedSchema(Statement stmt, Connection c) throws SQLException {
        if (!columnExists(c, "NOTE_REMITO", "stock_applied")) return;
        try {
            stmt.executeUpdate("""
                UPDATE r SET r.stock_applied = 1
                FROM NOTE_REPORT r JOIN NOTE_REMITO rm ON rm.note_report_id = r.id
                WHERE rm.stock_applied = 1
                """);
            dropDefaultConstraintIfAny(stmt, c, "NOTE_REMITO", "stock_applied");
            stmt.executeUpdate("ALTER TABLE NOTE_REMITO DROP COLUMN stock_applied");
        } catch (SQLException ignored) {
            // leave it for next startup to retry
        }
    }

    // SQL Server mirror of DatabaseService.migrateRejectionReasonSchema() — see that method's
    // Javadoc for the full rationale. T-SQL has no INSERT OR IGNORE, so a NOT EXISTS guard makes
    // the backfill safely re-runnable if the DROP COLUMN below fails partway and this method
    // retries on next startup with the column still present.
    // A first attempt at this feature added modifies_stock_reason directly as a nullable column
    // on NOTE_ITEM — reverted before shipping once a nullable-on-every-row shape was flagged as
    // inconsistent with this schema's own standing normalization rule (see
    // migrateRejectionReasonSchema()'s identical split, just below, for the established
    // precedent). No live SQL Server instance in this project's test infrastructure ever ran the
    // old shape, so this is defensive/no-op on every real remote installation — kept only for
    // parity with DatabaseService's own migration, which a local SQLite database genuinely needed.
    private void migrateStockExceptionReasonSchema(Statement stmt, Connection c) throws SQLException {
        if (!columnExists(c, "NOTE_ITEM", "modifies_stock_reason")) return;
        stmt.executeUpdate("""
            INSERT INTO NOTE_ITEM_STOCK_EXCEPTION (item_id, reason)
            SELECT i.id, i.modifies_stock_reason FROM NOTE_ITEM i
            WHERE i.modifies_stock_reason IS NOT NULL AND LTRIM(RTRIM(i.modifies_stock_reason)) <> ''
            AND NOT EXISTS (SELECT 1 FROM NOTE_ITEM_STOCK_EXCEPTION WHERE item_id = i.id)
            """);
        try {
            stmt.executeUpdate("ALTER TABLE NOTE_ITEM DROP COLUMN modifies_stock_reason");
        } catch (SQLException ignored) {
            // leave it for next startup to retry
        }
    }

    private void migrateRejectionReasonSchema(Statement stmt, Connection c) throws SQLException {
        if (!columnExists(c, "NOTE_REPORT", "rejection_reason")) return;
        stmt.executeUpdate("""
            INSERT INTO NOTE_REPORT_REJECTION (note_report_id, rejection_reason)
            SELECT r.id, r.rejection_reason FROM NOTE_REPORT r
            WHERE r.rejection_reason IS NOT NULL AND LTRIM(RTRIM(r.rejection_reason)) <> ''
            AND NOT EXISTS (SELECT 1 FROM NOTE_REPORT_REJECTION WHERE note_report_id = r.id)
            """);
        try {
            stmt.executeUpdate("ALTER TABLE NOTE_REPORT DROP COLUMN rejection_reason");
        } catch (SQLException ignored) {
            // leave it for next startup to retry
        }
    }

    // SQL Server mirror of DatabaseService.migrateEntregaDevolucionSplitSchema() — see that
    // method's Javadoc for the full rationale.
    private void migrateEntregaDevolucionSplitSchema(Statement stmt, Connection c) throws SQLException {
        boolean hasFailureCause = columnExists(c, "NOTE_ENTREGA_DEVOLUCION", "failure_cause");
        boolean hasAreaEvento = columnExists(c, "NOTE_ENTREGA_DEVOLUCION", "area_evento");

        if (hasFailureCause) {
            stmt.executeUpdate("""
                INSERT INTO NOTE_DEVOLUCION_FALLA (note_report_id, failure_cause, failure_details)
                SELECT e.note_report_id, e.failure_cause, e.failure_details FROM NOTE_ENTREGA_DEVOLUCION e
                WHERE e.failure_cause IS NOT NULL AND LTRIM(RTRIM(e.failure_cause)) <> ''
                AND NOT EXISTS (SELECT 1 FROM NOTE_DEVOLUCION_FALLA WHERE note_report_id = e.note_report_id)
                """);
            try {
                stmt.executeUpdate("ALTER TABLE NOTE_ENTREGA_DEVOLUCION DROP COLUMN failure_cause");
            } catch (SQLException ignored) {
                // leave it for next startup to retry
            }
        }
        if (columnExists(c, "NOTE_ENTREGA_DEVOLUCION", "failure_details")) {
            try {
                stmt.executeUpdate("ALTER TABLE NOTE_ENTREGA_DEVOLUCION DROP COLUMN failure_details");
            } catch (SQLException ignored) {
                // leave it for next startup to retry
            }
        }
        if (hasAreaEvento) {
            stmt.executeUpdate("""
                INSERT INTO NOTE_PRESTAMO_AREA_EVENTO (note_report_id, area_evento)
                SELECT e.note_report_id, e.area_evento FROM NOTE_ENTREGA_DEVOLUCION e
                WHERE e.area_evento IS NOT NULL AND LTRIM(RTRIM(e.area_evento)) <> ''
                AND NOT EXISTS (SELECT 1 FROM NOTE_PRESTAMO_AREA_EVENTO WHERE note_report_id = e.note_report_id)
                """);
            try {
                stmt.executeUpdate("ALTER TABLE NOTE_ENTREGA_DEVOLUCION DROP COLUMN area_evento");
            } catch (SQLException ignored) {
                // leave it for next startup to retry
            }
        }
    }

    // Promotes created_at/status_updated_at from the old NVARCHAR(MAX) ISO-8601-text shape to a
    // real DATETIME2 column. A brand-new install never needs this — the CREATE TABLE
    // blocks above already declare DATETIME2 directly. SQL Server implicitly converts an
    // ISO-8601 'T'-separated string (exactly what SqliteHistoryService has always written via
    // setString(LocalDateTime.toString())) to DATETIME2 on ALTER COLUMN, so no data
    // transformation is needed first — only guarded by a swallowed try/catch in case an
    // installation somehow has a non-ISO value already stored, matching the same "fail safely"
    // precedent as narrowNvarcharIfNeeded() above.
    private void migrateTimestampColumnsToDatetime2(Statement stmt, Connection c) throws SQLException {
        alterToDatetime2IfNeeded(stmt, c, "NOTE_REPORT", "created_at");
        alterToDatetime2IfNeeded(stmt, c, "NOTE_ITEM_GLPI_TRACKING", "status_updated_at");
        alterToDatetime2IfNeeded(stmt, c, "NOTE_ITEM_RETURN_TRACKING", "status_updated_at");
    }

    private void alterToDatetime2IfNeeded(Statement stmt, Connection c, String table, String column)
            throws SQLException {
        if (!columnExists(c, table, column) || isAlreadyDatetime2(c, table, column)) return;
        try {
            stmt.executeUpdate("ALTER TABLE " + table + " ALTER COLUMN " + column + " DATETIME2");
        } catch (SQLException nonConvertibleExistingData) {
            // leave it as-is for an admin to investigate — SqliteHistoryService's tolerant
            // parseStoredTimestamp()/normalizeTimestampString() still work against the old
            // NVARCHAR(MAX) shape in the meantime.
        }
    }

    private boolean isAlreadyDatetime2(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DATA_TYPE FROM information_schema.columns " +
                "WHERE lower(table_name) = ? AND lower(column_name) = ?")) {
            ps.setString(1, table.toLowerCase());
            ps.setString(2, column.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "datetime2".equalsIgnoreCase(rs.getString(1));
            }
        }
    }
}
