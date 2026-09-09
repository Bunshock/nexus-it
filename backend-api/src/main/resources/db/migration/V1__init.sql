-- V1 — initial schema for the NexusIT middleware (backend-api-v1-no-adapter).
--
-- Microsoft SQL Server. Applied by Flyway on the `sqlserver` profile against an empty
-- `notesit` database (see local-sqlserver/README.md to run it locally). Flyway records
-- this migration in `flyway_schema_history` and runs it exactly once, so this file is
-- deliberately FRESH-INSTALL ONLY: no `BEGIN TRANSACTION` wrapper (Flyway owns the
-- transaction), and no "upgrade an already-running installation" ALTER/backfill/cursor
-- logic — Flyway versioning is that mechanism, and a later shape change goes in a new
-- V<n>__*.sql, not here.
--
-- The `IF OBJECT_ID(...) IS NULL` guards are kept for parity with V2__config.sql and so a
-- DBA can also run the file by hand harmlessly. The desktop app's own hand-run,
-- self-migrating equivalent (idempotent against any prior shape) lives separately in that
-- repo's `database/sqlserver/01-schema.sql` and is not what this middleware uses.

-- ============================================================================
-- Equipment catalog
-- ============================================================================

IF OBJECT_ID('dbo.TYPE', 'U') IS NULL
BEGIN
    CREATE TABLE TYPE (
        id              INT IDENTITY(1,1) PRIMARY KEY,
        name            NVARCHAR(255) NOT NULL UNIQUE,
        is_asset        INT NOT NULL DEFAULT 0,
        requires_serial INT NOT NULL DEFAULT 0,
        deprecated      INT NOT NULL DEFAULT 0
    );
END

IF OBJECT_ID('dbo.BRAND', 'U') IS NULL
BEGIN
    CREATE TABLE BRAND (
        id         INT IDENTITY(1,1) PRIMARY KEY,
        name       NVARCHAR(255) NOT NULL UNIQUE,
        deprecated INT NOT NULL DEFAULT 0
    );
END

IF OBJECT_ID('dbo.BRAND_TYPE_LINK', 'U') IS NULL
BEGIN
    CREATE TABLE BRAND_TYPE_LINK (
        id       INT IDENTITY(1,1) PRIMARY KEY,
        type_id  INT NOT NULL REFERENCES TYPE(id),
        brand_id INT NOT NULL REFERENCES BRAND(id),
        CONSTRAINT uq_brand_type_link UNIQUE(type_id, brand_id)
    );
END

-- brand_type_id is nullable — NULL is reserved for the single global "Genérico / Otro" model,
-- not scoped to any particular brand+type link.
IF OBJECT_ID('dbo.MODEL', 'U') IS NULL
BEGIN
    CREATE TABLE MODEL (
        id            INT IDENTITY(1,1) PRIMARY KEY,
        brand_type_id INT REFERENCES BRAND_TYPE_LINK(id),
        name          NVARCHAR(255) NOT NULL,
        deprecated    INT NOT NULL DEFAULT 0
    );
END

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_model_brand_type_name')
    CREATE UNIQUE INDEX idx_model_brand_type_name ON MODEL(brand_type_id, name);

-- idx_model_brand_type_name above never protects the global generic row (brand_type_id IS
-- NULL) — every SQL engine treats NULL as never-equal-to-NULL, even in a unique index. These
-- two indexes key off name/deprecated instead, which hold real comparable values: one stops an
-- accidental duplicate global-generic name, the other guarantees at most one ACTIVE global row.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_model_global_generic_name')
    CREATE UNIQUE INDEX idx_model_global_generic_name ON MODEL(name) WHERE brand_type_id IS NULL;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_model_single_active_generic')
    CREATE UNIQUE INDEX idx_model_single_active_generic ON MODEL(deprecated) WHERE brand_type_id IS NULL AND deprecated = 0;

IF OBJECT_ID('dbo.SN_VALIDATION', 'U') IS NULL
BEGIN
    CREATE TABLE SN_VALIDATION (
        id            INT IDENTITY(1,1) PRIMARY KEY,
        model_id      INT NOT NULL REFERENCES MODEL(id),
        regex_pattern NVARCHAR(500),
        is_active     INT NOT NULL DEFAULT 1
    );
END

IF OBJECT_ID('dbo.PROVIDER', 'U') IS NULL
BEGIN
    CREATE TABLE PROVIDER (
        id         INT IDENTITY(1,1) PRIMARY KEY,
        name       NVARCHAR(255) NOT NULL UNIQUE,
        deprecated INT NOT NULL DEFAULT 0
    );
END

IF OBJECT_ID('dbo.SEDE', 'U') IS NULL
BEGIN
    CREATE TABLE SEDE (
        id         INT IDENTITY(1,1) PRIMARY KEY,
        name       NVARCHAR(255) NOT NULL UNIQUE,
        deprecated INT NOT NULL DEFAULT 0
    );
END

-- Row exists only once a superadmin has configured a Sede's Remito shipping info via direct SQL —
-- not nullable columns on SEDE itself, which every Sede would carry regardless of whether
-- shipping was ever configured for it. deprecated-flag versioned, same pattern as
-- TYPE/BRAND/MODEL/PROVIDER/SEDE — an edit deprecates the old row and inserts a new one rather
-- than mutating in place, so NOTE_REMITO_SEDE can safely reference a specific row by FK without
-- that historical value ever silently changing.
IF OBJECT_ID('dbo.SEDE_SHIPPING_INFO', 'U') IS NULL
BEGIN
    CREATE TABLE SEDE_SHIPPING_INFO (
        id                INT IDENTITY(1,1) PRIMARY KEY,
        sede_id           INT NOT NULL REFERENCES SEDE(id),
        destination_label NVARCHAR(255) NOT NULL,
        address           NVARCHAR(500),
        recipients        NVARCHAR(500),
        deprecated        INT NOT NULL DEFAULT 0
    );
END

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_sede_shipping_single_active')
    CREATE UNIQUE INDEX idx_sede_shipping_single_active ON SEDE_SHIPPING_INFO(sede_id) WHERE deprecated = 0;

-- brand_type_id is stored explicitly (not inferred from model_id) so the single global
-- "Genérico / Otro" model can carry an independent stock number per (Type,Brand) it's used under.
-- sede_id makes stock genuinely per-site: the same Model at two Sedes carries two independent
-- counts, not one shared global number.
IF OBJECT_ID('dbo.MODEL_STOCK', 'U') IS NULL
BEGIN
    CREATE TABLE MODEL_STOCK (
        brand_type_id INT NOT NULL REFERENCES BRAND_TYPE_LINK(id),
        model_id      INT NOT NULL REFERENCES MODEL(id),
        sede_id       INT NOT NULL REFERENCES SEDE(id),
        stock         INT NOT NULL DEFAULT 0,
        CONSTRAINT pk_model_stock PRIMARY KEY (brand_type_id, model_id, sede_id)
    );
END

-- ============================================================================
-- Users, roles, permissions
-- ============================================================================

-- Login-time role/Sede lookup — not the same thing as AD group membership, which gates app
-- access at all and is checked against the AD API at login time, not stored here. Named APP_USER,
-- not USER — USER is a reserved keyword/niladic function in T-SQL.
--
-- ROLE is a plain lookup table for the 3 fixed role names — APP_USER.role_id and
-- ROLE_PERMISSION.role_id both reference it, instead of each independently duplicating the same
-- CHECK (role IN (...)) constraint with nothing enforcing the two stay in sync.
IF OBJECT_ID('dbo.ROLE', 'U') IS NULL
BEGIN
    CREATE TABLE ROLE (
        id   INT IDENTITY(1,1) PRIMARY KEY,
        name NVARCHAR(20) NOT NULL UNIQUE
    );
    INSERT INTO ROLE (name) VALUES ('USER'), ('ADMIN'), ('SUPERADMIN');
END

IF OBJECT_ID('dbo.APP_USER', 'U') IS NULL
BEGIN
    CREATE TABLE APP_USER (
        id                 INT IDENTITY(1,1) PRIMARY KEY,
        username           NVARCHAR(100) NOT NULL UNIQUE,
        role_id            INT NOT NULL REFERENCES ROLE(id),
        sede_id            INT REFERENCES SEDE(id),
        bypass_group_check INT NOT NULL DEFAULT 0
    );
END

-- Deny-by-default permission grants: a Permission is denied unless a matching row exists here.
-- Edited directly via SQL by a superadmin, same "no in-app CRUD" precedent as APP_USER itself.
IF OBJECT_ID('dbo.ROLE_PERMISSION', 'U') IS NULL
BEGIN
    CREATE TABLE ROLE_PERMISSION (
        id         INT IDENTITY(1,1) PRIMARY KEY,
        role_id    INT NOT NULL REFERENCES ROLE(id),
        permission NVARCHAR(50) NOT NULL,
        CONSTRAINT uq_role_permission UNIQUE (role_id, permission)
    );
END

-- ============================================================================
-- Audit trail (append-only)
-- ============================================================================

-- AUDIT_ADMIN_ACTION is deliberately generic (target_id NVARCHAR, not a typed FK) since it covers
-- heterogeneous admin actions with no single shared FK target; AUDIT_STOCK/AUDIT_ITEM_STATUS stay
-- typed since each has one clear FK target. old_value/new_value must NEVER hold an actual secret
-- value (SMTP password, GLPI API key, AD token, DB credentials) — only that a change happened;
-- enforced by the caller, not this schema.
IF OBJECT_ID('dbo.AUDIT_LOGIN', 'U') IS NULL
BEGIN
    CREATE TABLE AUDIT_LOGIN (
        id             INT IDENTITY(1,1) PRIMARY KEY,
        username       NVARCHAR(100) NOT NULL,
        success        INT NOT NULL,
        failure_reason NVARCHAR(255),
        attempted_at   DATETIME2 NOT NULL
    );
END

IF OBJECT_ID('dbo.AUDIT_STOCK', 'U') IS NULL
BEGIN
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
    );
END

-- AUDIT_ADMIN_ACTION has no FK dependency on NOTE_ITEM/etc. so it's created here; AUDIT_ITEM_STATUS
-- is further below, right after NOTE_ITEM exists.
IF OBJECT_ID('dbo.AUDIT_ADMIN_ACTION', 'U') IS NULL
BEGIN
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
    );
END

-- ============================================================================
-- Notes
-- ============================================================================

-- stock_applied guards against double-applying a note's stock adjustment on re-approve — every
-- note type moves stock on approval, so this is a universal per-note flag.
IF OBJECT_ID('dbo.NOTE_REPORT', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_REPORT (
        id              INT IDENTITY(1,1) PRIMARY KEY,
        created_at      DATETIME2 NOT NULL,
        profile_type    NVARCHAR(255) NOT NULL,
        technician_name NVARCHAR(255),
        technician_dni  NVARCHAR(255),
        observations    NVARCHAR(300),
        sede_id         INT REFERENCES SEDE(id),
        approval_status NVARCHAR(20) NOT NULL DEFAULT 'PENDING',
        stock_applied   INT NOT NULL DEFAULT 0
    );
END

-- Row exists only for a note an admin has actually rejected — not a NULL sentinel on every
-- NOTE_REPORT row that's PENDING/APPROVED.
IF OBJECT_ID('dbo.NOTE_REPORT_REJECTION', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_REPORT_REJECTION (
        note_report_id   INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
        rejection_reason NVARCHAR(300) NOT NULL
    );
END

IF OBJECT_ID('dbo.NOTE_ENTREGA_DEVOLUCION', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ENTREGA_DEVOLUCION (
        note_report_id INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
        user_name      NVARCHAR(255),
        user_dni       NVARCHAR(255),
        user_email     NVARCHAR(255),
        motivo         NVARCHAR(100)
    );
END

-- Row exists only for a Devolución note whose Motivo triggered the Falla popup — not a NULL
-- sentinel on every ENTREGA_DEVOLUCION row regardless of profile type/motivo.
IF OBJECT_ID('dbo.NOTE_DEVOLUCION_FALLA', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_DEVOLUCION_FALLA (
        note_report_id  INT PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
        failure_cause   NVARCHAR(100) NOT NULL,
        failure_details NVARCHAR(200)
    );
END

-- Row exists only when a Préstamo note actually captured an Área/Evento (itself optional even
-- within Préstamo) — not a NULL sentinel on every ENTREGA_DEVOLUCION row regardless of profile type.
IF OBJECT_ID('dbo.NOTE_PRESTAMO_AREA_EVENTO', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_PRESTAMO_AREA_EVENTO (
        note_report_id INT PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
        area_evento    NVARCHAR(200) NOT NULL
    );
END

IF OBJECT_ID('dbo.NOTE_PROVEEDOR', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_PROVEEDOR (
        note_report_id   INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
        provider_id      INT NOT NULL REFERENCES PROVIDER(id),
        cuit             NVARCHAR(255),
        motivo           NVARCHAR(100),
        responsible_name NVARCHAR(255),
        responsible_dni  NVARCHAR(255)
    );
END

-- Split into two mutually-exclusive subtype tables — exactly one exists per Remito note, never
-- neither, never both. A catalog-Sede destination's 3 text fields are locked in the app the
-- moment a Sede is picked, so they can only ever equal that Sede's currently-active
-- SEDE_SHIPPING_INFO row — NOTE_REMITO_SEDE references that row by FK instead of duplicating its
-- text. A custom/manual destination (e.g. a CAU not in the SEDE catalog) has no catalog row to
-- reference, so NOTE_REMITO_OTHER keeps its own free text. No stock_applied column on either —
-- that idempotency flag lives on NOTE_REPORT, shared by every note type.
IF OBJECT_ID('dbo.NOTE_REMITO_SEDE', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_REMITO_SEDE (
        note_report_id   INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
        shipping_info_id INT NOT NULL REFERENCES SEDE_SHIPPING_INFO(id)
    );
END

IF OBJECT_ID('dbo.NOTE_REMITO_OTHER', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_REMITO_OTHER (
        note_report_id    INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
        destination_label NVARCHAR(255) NOT NULL,
        address           NVARCHAR(500),
        recipients        NVARCHAR(500)
    );
END

-- Slim base table — asset-only, countable-only, GLPI-tracking, and return-tracking fields each
-- live in their own subtype table below, so a row never carries a column that doesn't apply to it.
IF OBJECT_ID('dbo.NOTE_ITEM', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ITEM (
        id             INT IDENTITY(1,1) PRIMARY KEY,
        note_id        INT NOT NULL REFERENCES NOTE_REPORT(id),
        type_id        INT NOT NULL REFERENCES TYPE(id),
        brand_id       INT NOT NULL REFERENCES BRAND(id),
        model_id       INT NOT NULL REFERENCES MODEL(id),
        observations   NVARCHAR(200),
        modifies_stock INT NOT NULL DEFAULT 1
    );
END

IF OBJECT_ID('dbo.NOTE_ITEM_ASSET', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ITEM_ASSET (
        item_id       INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
        serial_number NVARCHAR(255),
        a_f           NVARCHAR(255)
    );
END

IF OBJECT_ID('dbo.NOTE_ITEM_COUNTABLE', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ITEM_COUNTABLE (
        item_id  INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
        quantity INT NOT NULL DEFAULT 1
    );
END

-- No 'N_A' value/default — a row simply doesn't exist for a tracking dimension that doesn't apply
-- to an item, instead of always existing with a sentinel value. One table for all 3 tracking
-- dimensions (GLPI sync-out, Préstamo/Provider return, GLPI sync-back after a return) — they're
-- byte-identical in shape, differing only in which dimension a row belongs to, so tracking_type is
-- the discriminator. UNIQUE(item_id, tracking_type) is what used to be each dimension's own
-- item_id PK — an item can legitimately hold up to 3 rows at once (a returnable Provider note's
-- asset gets a GLPI row on sync-out, a RETURN row once returned, and a GLPI_RETURN row once
-- re-synced afterward — GLPI sync is one-way/no-revert, so the original sync-out can never be
-- "undone" to reflect an item coming back, hence the separate dimension).
IF OBJECT_ID('dbo.NOTE_ITEM_STATUS_TRACKING', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ITEM_STATUS_TRACKING (
        id                INT IDENTITY(1,1) PRIMARY KEY,
        item_id           INT NOT NULL REFERENCES NOTE_ITEM(id),
        -- GLPI_RETURN = re-sync of a validated Provider-return into GLPI
        tracking_type     NVARCHAR(20) NOT NULL CHECK (tracking_type IN ('GLPI', 'RETURN', 'GLPI_RETURN')),
        status            NVARCHAR(50) NOT NULL,
        rejection_reason  NVARCHAR(300),
        status_updated_at DATETIME2,
        CONSTRAINT uq_note_item_status_tracking UNIQUE (item_id, tracking_type)
    );
END

-- Row exists only for an item flagged "no modifica stock" — the overwhelming majority of items
-- never use this exception, so the reason lives here rather than as an always-present-but-usually
-- NULL column on NOTE_ITEM itself, same "row-absence means not applicable" precedent as every
-- other conditional-reason table in this schema.
IF OBJECT_ID('dbo.NOTE_ITEM_STOCK_EXCEPTION', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ITEM_STOCK_EXCEPTION (
        item_id INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
        reason  NVARCHAR(300) NOT NULL
    );
END

-- Covers both GLPI and Préstamo/Provider return-status transitions in one table via a status_kind
-- discriminator (both are the same "item's status moved from A to B" shape) —
-- NOTE_ITEM_STATUS_TRACKING only ever keeps the latest status per dimension, never prior
-- transitions. Created here, not with the other audit tables above, because it references
-- NOTE_ITEM.
IF OBJECT_ID('dbo.AUDIT_ITEM_STATUS', 'U') IS NULL
BEGIN
    CREATE TABLE AUDIT_ITEM_STATUS (
        id          INT IDENTITY(1,1) PRIMARY KEY,
        item_id     INT NOT NULL REFERENCES NOTE_ITEM(id),
        status_kind NVARCHAR(20) NOT NULL CHECK (status_kind IN ('GLPI', 'RETURN', 'GLPI_RETURN')),
        old_status  NVARCHAR(50) NOT NULL,
        new_status  NVARCHAR(50) NOT NULL,
        reason      NVARCHAR(500),
        quantity    INT NOT NULL DEFAULT 1,
        username    NVARCHAR(100) NOT NULL,
        changed_at  DATETIME2 NOT NULL
    );
END

-- Countable items can be resolved in partial batches over time (e.g. 5 loaned headsets: 3 returned
-- now, 1 lost later, 1 still pending) — a single status column on NOTE_ITEM_STATUS_TRACKING
-- (tracking_type = 'RETURN') can't express that, so each partial action gets its own append-only
-- row here instead. PENDING is never stored — the remaining pending quantity is always
-- NOTE_ITEM_COUNTABLE.quantity minus the sum of allocations for that item, same "row absence is
-- the state" convention as every other tracking table in this schema. Asset items never get a row
-- here (a physical unit isn't divisible) — they stay on the whole-item RETURN-dimension status.
IF OBJECT_ID('dbo.NOTE_ITEM_RETURN_ALLOCATION', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ITEM_RETURN_ALLOCATION (
        id         INT IDENTITY(1,1) PRIMARY KEY,
        item_id    INT NOT NULL REFERENCES NOTE_ITEM(id),
        status     NVARCHAR(50) NOT NULL,
        quantity   INT NOT NULL,
        reason     NVARCHAR(300),
        updated_at DATETIME2 NOT NULL
    );
END
