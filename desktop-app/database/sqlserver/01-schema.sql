-- Generador de Notas IT — Microsoft SQL Server schema
--
-- Mirrors RemoteDatabaseService.ensureSchema() exactly (desktop-app/src/main/java/.../services/RemoteDatabaseService.java).
-- The app already creates this schema automatically the first time it connects to a configured
-- remote database (Configuración → Base de Datos → Editar, admin mode), so running this script
-- by hand is OPTIONAL — it's here for DBAs who want to provision the database independently of
-- ever launching the app, or who want the schema under version control / change review before
-- the app touches it.
--
-- Safe to run multiple times (every CREATE TABLE / index / column addition is guarded by an
-- existence check first, since T-SQL has no CREATE TABLE IF NOT EXISTS or ADD COLUMN IF NOT
-- EXISTS the way PostgreSQL/SQLite do) and safe to run against a database at ANY prior schema
-- version this app has ever shipped — every migration step below is its own existence-guarded,
-- idempotent block, exactly like the Java it mirrors.
-- Run 02-seed-equipment.sql afterward to populate the starting Type/Brand/Model catalog.

BEGIN TRANSACTION;

-- ============================================================================
-- CREATE TABLE (only if missing) — current shape for a brand-new install
-- ============================================================================

IF OBJECT_ID('dbo.TYPE', 'U') IS NULL
BEGIN
    CREATE TABLE TYPE (
        id              INT IDENTITY(1,1) PRIMARY KEY,
        name            NVARCHAR(255) NOT NULL UNIQUE,
        is_asset        INT NOT NULL DEFAULT 1,
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

-- Wrapped in a duplicate-safe check: an existing database with pre-existing duplicate
-- (brand_type_id, name) rows would fail this statement — degrade to app-layer-only enforcement
-- (SqliteEquipmentService.addModel/renameModel) rather than aborting the whole script.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_model_brand_type_name')
BEGIN
    BEGIN TRY
        CREATE UNIQUE INDEX idx_model_brand_type_name ON MODEL(brand_type_id, name);
    END TRY
    BEGIN CATCH
        -- pre-existing duplicate model names for the same brand+type — see comment above
    END CATCH
END

-- idx_model_brand_type_name above never protects the global generic row (brand_type_id IS
-- NULL) — every SQL engine treats NULL as never-equal-to-NULL, even in a unique index. These
-- two indexes key off name/deprecated instead, which hold real comparable values: one stops an
-- accidental duplicate global-generic name, the other guarantees at most one ACTIVE global row.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_model_global_generic_name')
BEGIN
    BEGIN TRY
        CREATE UNIQUE INDEX idx_model_global_generic_name ON MODEL(name) WHERE brand_type_id IS NULL;
    END TRY
    BEGIN CATCH
        -- same "fail safely" degradation as idx_model_brand_type_name above
    END CATCH
END
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_model_single_active_generic')
BEGIN
    BEGIN TRY
        CREATE UNIQUE INDEX idx_model_single_active_generic ON MODEL(deprecated) WHERE brand_type_id IS NULL AND deprecated = 0;
    END TRY
    BEGIN CATCH
        -- same "fail safely" degradation as idx_model_brand_type_name above
    END CATCH
END

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

-- Deprecated flag from day one — unlike TYPE/BRAND/MODEL/PROVIDER, SEDE never had a
-- pre-deprecated-flag era to migrate away from, so this needs no ADD COLUMN step.
IF OBJECT_ID('dbo.SEDE', 'U') IS NULL
BEGIN
    CREATE TABLE SEDE (
        id         INT IDENTITY(1,1) PRIMARY KEY,
        name       NVARCHAR(255) NOT NULL UNIQUE,
        deprecated INT NOT NULL DEFAULT 0
    );
END

-- brand_type_id is stored explicitly (not inferred from model_id) so the single global
-- "Genérico / Otro" model can carry an independent stock number per (Type,Brand) it's used under.
IF OBJECT_ID('dbo.MODEL_STOCK', 'U') IS NULL
BEGIN
    CREATE TABLE MODEL_STOCK (
        brand_type_id INT NOT NULL REFERENCES BRAND_TYPE_LINK(id),
        model_id      INT NOT NULL REFERENCES MODEL(id),
        stock         INT NOT NULL DEFAULT 0,
        CONSTRAINT pk_model_stock PRIMARY KEY (brand_type_id, model_id)
    );
END

-- Login-time role/Sede lookup — not the same thing as AD group membership, which gates app
-- access at all and is checked against the AD API at login time, not stored here. Renamed from
-- USER_ROLE (2026-07-30) since USER is a reserved keyword/niladic function in T-SQL and the old
-- table had no sede_id/SUPERADMIN tier.
IF OBJECT_ID('dbo.APP_USER', 'U') IS NULL
BEGIN
    CREATE TABLE APP_USER (
        id       INT IDENTITY(1,1) PRIMARY KEY,
        username NVARCHAR(100) NOT NULL UNIQUE,
        role     NVARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ADMIN', 'SUPERADMIN')),
        sede_id  INT REFERENCES SEDE(id)
    );
END

-- Deny-by-default permission grants: a Permission is denied unless a matching row exists here.
-- Edited directly via SQL by a superadmin, same "no in-app CRUD" precedent as APP_USER itself.
IF OBJECT_ID('dbo.ROLE_PERMISSION', 'U') IS NULL
BEGIN
    CREATE TABLE ROLE_PERMISSION (
        role       NVARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ADMIN', 'SUPERADMIN')),
        permission NVARCHAR(50) NOT NULL,
        CONSTRAINT pk_role_permission PRIMARY KEY (role, permission)
    );
END

-- glpi_synced/sede are deliberately absent here — both were confirmed dead (no reader/writer
-- anywhere in the app) and are actively dropped from an existing database further down
-- (dropDeadNoteReportColumns), never re-created for a fresh install.
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
        approval_status NVARCHAR(20) NOT NULL DEFAULT 'PENDING'
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

-- Slim base table — asset-only, countable-only, GLPI-tracking, and return-tracking fields each
-- live in their own subtype table below, so a row never carries a column that doesn't apply to
-- it. See the migration block further down for the path off the old wide 16-column shape (and,
-- earlier still, off plain text type_name/brand_name/model_name columns) on an already-running
-- installation.
IF OBJECT_ID('dbo.NOTE_ITEM', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ITEM (
        id           INT IDENTITY(1,1) PRIMARY KEY,
        note_id      INT NOT NULL REFERENCES NOTE_REPORT(id),
        type_id      INT NOT NULL REFERENCES TYPE(id),
        brand_id     INT NOT NULL REFERENCES BRAND(id),
        model_id     INT NOT NULL REFERENCES MODEL(id),
        observations NVARCHAR(200)
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

-- No 'N_A' value/default here — a row simply doesn't exist for an item that isn't GLPI-tracked
-- (countable, or a Préstamo asset), instead of always existing with a sentinel value. Same
-- reasoning for NOTE_ITEM_RETURN_TRACKING below.
IF OBJECT_ID('dbo.NOTE_ITEM_GLPI_TRACKING', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ITEM_GLPI_TRACKING (
        item_id           INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
        status            NVARCHAR(50) NOT NULL,
        rejection_reason  NVARCHAR(300),
        status_updated_at DATETIME2
    );
END

IF OBJECT_ID('dbo.NOTE_ITEM_RETURN_TRACKING', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ITEM_RETURN_TRACKING (
        item_id           INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
        status            NVARCHAR(50) NOT NULL,
        rejection_reason  NVARCHAR(300),
        status_updated_at DATETIME2
    );
END

-- ============================================================================
-- Migrations for an already-running installation — CREATE TABLE-if-missing above silently
-- no-ops on a database that already has the table from an older schema version, so every column
-- added later must be migrated here too. Every step is its own existence-guarded, idempotent
-- block, kept in sync with RemoteDatabaseService.ensureSchema()'s own migration block, in the
-- same order.
-- ============================================================================

-- requires_serial replaces ItemDialogController's old hardcoded "Notebook".equals(type.getName())
-- check. Only backfill requires_serial=1 for the existing Notebook-named type on the run that
-- actually adds the column — otherwise re-running this script would silently re-enable the flag
-- for anyone who deliberately turned it off for a type literally named "notebook" via the app's
-- admin UI.
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'type' AND lower(column_name) = 'requires_serial')
BEGIN
    ALTER TABLE TYPE ADD requires_serial INT NOT NULL DEFAULT 0;
    UPDATE TYPE SET requires_serial = 1 WHERE LOWER(name) = 'notebook';
END

-- failure_cause/failure_details/area_evento/rejection_reason are deliberately NOT re-added as
-- columns anywhere in this script — they're retired, split into
-- NOTE_DEVOLUCION_FALLA/NOTE_PRESTAMO_AREA_EVENTO/NOTE_REPORT_REJECTION instead (see the
-- migration blocks further down). Same reasoning for NOTE_REPORT.sede/glpi_synced.
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_proveedor' AND lower(column_name) = 'responsible_name')
    ALTER TABLE NOTE_PROVEEDOR ADD responsible_name NVARCHAR(255);
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_proveedor' AND lower(column_name) = 'responsible_dni')
    ALTER TABLE NOTE_PROVEEDOR ADD responsible_dni NVARCHAR(255);
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'technician_name')
    ALTER TABLE NOTE_REPORT ADD technician_name NVARCHAR(255);
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'technician_dni')
    ALTER TABLE NOTE_REPORT ADD technician_dni NVARCHAR(255);

-- Only relevant to a database still on the old wide NOTE_ITEM shape (is_asset present) — on a
-- brand-new install (or one already past this migration) NOTE_ITEM is already the slim shape,
-- and these columns must NOT be re-added there just because they're "missing". Walking a
-- genuinely old database up to the full wide shape here is a prerequisite for the backfill/DROP
-- COLUMN block further down, which splits it into the 5-table shape.
DECLARE @noteItemStillWide BIT = CASE WHEN EXISTS (
    SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'is_asset'
) THEN 1 ELSE 0 END;

IF @noteItemStillWide = 1
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'is_asset')
        ALTER TABLE NOTE_ITEM ADD is_asset INT NOT NULL DEFAULT 0;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'glpi_status')
        ALTER TABLE NOTE_ITEM ADD glpi_status NVARCHAR(50) NOT NULL DEFAULT 'N_A';
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'glpi_rejection_reason')
        ALTER TABLE NOTE_ITEM ADD glpi_rejection_reason NVARCHAR(300);
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'glpi_status_updated_at')
        ALTER TABLE NOTE_ITEM ADD glpi_status_updated_at NVARCHAR(MAX);
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'return_status')
        ALTER TABLE NOTE_ITEM ADD return_status NVARCHAR(50) NOT NULL DEFAULT 'N_A';
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'return_rejection_reason')
        ALTER TABLE NOTE_ITEM ADD return_rejection_reason NVARCHAR(300);
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'return_status_updated_at')
        ALTER TABLE NOTE_ITEM ADD return_status_updated_at NVARCHAR(MAX);
END

IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'observations')
    ALTER TABLE NOTE_REPORT ADD observations NVARCHAR(300);
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'sede_id')
    ALTER TABLE NOTE_REPORT ADD sede_id INT REFERENCES SEDE(id);

-- NOTE_REPORT.sede used to be a plain free-text snapshot; sede_id is a real FK into SEDE. Backfill
-- every existing row's free-text sede into a matching (or newly created) SEDE row, resolved
-- case-insensitively, then point sede_id at it. Set-based, not the row-by-row cache the Java side
-- uses (a performance detail, not a correctness one) — same end result. Newly created rows are
-- left ACTIVE (deprecated=0), not 1: SEDE has no pre-existing admin-curated catalog to fall back
-- on, so marking every backfilled row deprecated would leave the Settings combobox with zero
-- selectable options, blocking every technician from generating a note.
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'sede')
BEGIN
    INSERT INTO SEDE (name, deprecated)
    SELECT DISTINCT LTRIM(RTRIM(r.sede)), 0
    FROM NOTE_REPORT r
    WHERE r.sede_id IS NULL AND r.sede IS NOT NULL AND LTRIM(RTRIM(r.sede)) <> ''
      AND NOT EXISTS (SELECT 1 FROM SEDE s WHERE LOWER(s.name) = LOWER(LTRIM(RTRIM(r.sede))));

    UPDATE r
    SET r.sede_id = s.id
    FROM NOTE_REPORT r
    JOIN SEDE s ON LOWER(s.name) = LOWER(LTRIM(RTRIM(r.sede)))
    WHERE r.sede_id IS NULL AND r.sede IS NOT NULL AND LTRIM(RTRIM(r.sede)) <> '';
END

IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'approval_status')
    ALTER TABLE NOTE_REPORT ADD approval_status NVARCHAR(20) NOT NULL DEFAULT 'PENDING';

-- glpi_synced/sede on NOTE_REPORT were both confirmed dead (no reader/writer anywhere in the
-- app — sede was superseded by sede_id, glpi_synced was never wired to anything). A brand-new
-- install never creates either column; this only fires on an already-running installation that
-- still has one or both.
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'glpi_synced')
BEGIN
    BEGIN TRY
        DECLARE @glpiSyncedDefault NVARCHAR(128);
        SELECT @glpiSyncedDefault = dc.name FROM sys.columns col
            JOIN sys.default_constraints dc ON col.default_object_id = dc.object_id
            WHERE col.object_id = OBJECT_ID('NOTE_REPORT') AND col.name = 'glpi_synced';
        IF @glpiSyncedDefault IS NOT NULL
            EXEC('ALTER TABLE NOTE_REPORT DROP CONSTRAINT ' + @glpiSyncedDefault);
        ALTER TABLE NOTE_REPORT DROP COLUMN glpi_synced;
    END TRY
    BEGIN CATCH
        -- leave it for next run to retry — not worth blocking this script over
    END CATCH
END
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'sede')
BEGIN
    BEGIN TRY
        ALTER TABLE NOTE_REPORT DROP COLUMN sede;
    END TRY
    BEGIN CATCH
        -- leave it for next run to retry
    END CATCH
END

-- NOTE_REPORT.rejection_reason used to sit inline, nullable on every row and only ever populated
-- once an admin actually rejects a note. Split into NOTE_REPORT_REJECTION: a row exists only for
-- a note that's actually been rejected.
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'rejection_reason')
BEGIN
    INSERT INTO NOTE_REPORT_REJECTION (note_report_id, rejection_reason)
    SELECT r.id, r.rejection_reason FROM NOTE_REPORT r
    WHERE r.rejection_reason IS NOT NULL AND LTRIM(RTRIM(r.rejection_reason)) <> ''
      AND NOT EXISTS (SELECT 1 FROM NOTE_REPORT_REJECTION WHERE note_report_id = r.id);
    BEGIN TRY
        ALTER TABLE NOTE_REPORT DROP COLUMN rejection_reason;
    END TRY
    BEGIN CATCH
        -- leave it for next run to retry
    END CATCH
END

-- NOTE_ENTREGA_DEVOLUCION mixed 4 profile types' fields on one table — failure_cause/
-- failure_details only ever populated for Devolución+Falla, area_evento only for Préstamo. Split
-- into NOTE_DEVOLUCION_FALLA/NOTE_PRESTAMO_AREA_EVENTO — a row exists only when that dimension
-- actually applies.
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_entrega_devolucion' AND lower(column_name) = 'failure_cause')
BEGIN
    INSERT INTO NOTE_DEVOLUCION_FALLA (note_report_id, failure_cause, failure_details)
    SELECT e.note_report_id, e.failure_cause, e.failure_details FROM NOTE_ENTREGA_DEVOLUCION e
    WHERE e.failure_cause IS NOT NULL AND LTRIM(RTRIM(e.failure_cause)) <> ''
      AND NOT EXISTS (SELECT 1 FROM NOTE_DEVOLUCION_FALLA WHERE note_report_id = e.note_report_id);
    BEGIN TRY
        ALTER TABLE NOTE_ENTREGA_DEVOLUCION DROP COLUMN failure_cause;
    END TRY
    BEGIN CATCH
        -- leave it for next run to retry
    END CATCH
END
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_entrega_devolucion' AND lower(column_name) = 'failure_details')
BEGIN
    BEGIN TRY
        ALTER TABLE NOTE_ENTREGA_DEVOLUCION DROP COLUMN failure_details;
    END TRY
    BEGIN CATCH
        -- leave it for next run to retry
    END CATCH
END
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_entrega_devolucion' AND lower(column_name) = 'area_evento')
BEGIN
    INSERT INTO NOTE_PRESTAMO_AREA_EVENTO (note_report_id, area_evento)
    SELECT e.note_report_id, e.area_evento FROM NOTE_ENTREGA_DEVOLUCION e
    WHERE e.area_evento IS NOT NULL AND LTRIM(RTRIM(e.area_evento)) <> ''
      AND NOT EXISTS (SELECT 1 FROM NOTE_PRESTAMO_AREA_EVENTO WHERE note_report_id = e.note_report_id);
    BEGIN TRY
        ALTER TABLE NOTE_ENTREGA_DEVOLUCION DROP COLUMN area_evento;
    END TRY
    BEGIN CATCH
        -- leave it for next run to retry
    END CATCH
END

-- Promotes created_at/status_updated_at from the old NVARCHAR(MAX) ISO-8601-text shape to a real
-- DATETIME2 column. A brand-new install never needs this — the CREATE TABLE blocks above already
-- declare DATETIME2 directly. SQL Server implicitly converts an ISO-8601 'T'-separated string to
-- DATETIME2 on ALTER COLUMN, so no data transformation is needed first.
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'created_at' AND lower(data_type) <> 'datetime2')
BEGIN
    BEGIN TRY
        ALTER TABLE NOTE_REPORT ALTER COLUMN created_at DATETIME2;
    END TRY
    BEGIN CATCH
        -- leave it as-is for an admin to investigate — the app's own tolerant read path still
        -- works against the old NVARCHAR(MAX) shape in the meantime
    END CATCH
END
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item_glpi_tracking' AND lower(column_name) = 'status_updated_at' AND lower(data_type) <> 'datetime2')
BEGIN
    BEGIN TRY
        ALTER TABLE NOTE_ITEM_GLPI_TRACKING ALTER COLUMN status_updated_at DATETIME2;
    END TRY
    BEGIN CATCH
        -- see comment above
    END CATCH
END
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item_return_tracking' AND lower(column_name) = 'status_updated_at' AND lower(data_type) <> 'datetime2')
BEGIN
    BEGIN TRY
        ALTER TABLE NOTE_ITEM_RETURN_TRACKING ALTER COLUMN status_updated_at DATETIME2;
    END TRY
    BEGIN CATCH
        -- see comment above
    END CATCH
END

-- Backfills NOTE_ITEM_ASSET/COUNTABLE/GLPI_TRACKING/RETURN_TRACKING from the still-wide NOTE_ITEM
-- (only when @noteItemStillWide = 1, captured earlier — is_asset existed at the START of this
-- script, before the "walk up to full wide shape" block above added the rest), then drops the 10
-- now-redundant wide columns.
IF @noteItemStillWide = 1
BEGIN
    INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
        SELECT id, serial_number, a_f FROM NOTE_ITEM WHERE is_asset = 1;
    INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity)
        SELECT id, quantity FROM NOTE_ITEM WHERE is_asset = 0;
    INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
        SELECT id, glpi_status, glpi_rejection_reason, glpi_status_updated_at
        FROM NOTE_ITEM WHERE glpi_status <> 'N_A';
    INSERT INTO NOTE_ITEM_RETURN_TRACKING (item_id, status, rejection_reason, status_updated_at)
        SELECT id, return_status, return_rejection_reason, return_status_updated_at
        FROM NOTE_ITEM WHERE return_status <> 'N_A';

    -- SQL Server's ALTER TABLE ... DROP COLUMN fails if the column still has a DEFAULT
    -- constraint attached, so each one is dropped first via dynamic SQL (constraint names are
    -- auto-generated, not known ahead of time).
    DECLARE @wideCols TABLE (name NVARCHAR(50));
    INSERT INTO @wideCols VALUES
        ('serial_number'), ('a_f'), ('quantity'), ('is_asset'),
        ('glpi_status'), ('glpi_rejection_reason'), ('glpi_status_updated_at'),
        ('return_status'), ('return_rejection_reason'), ('return_status_updated_at');

    DECLARE @wideCol NVARCHAR(50), @wideConstraintName NVARCHAR(128), @wideDynSql NVARCHAR(MAX);
    DECLARE wideColCursor CURSOR FOR SELECT name FROM @wideCols;
    OPEN wideColCursor;
    FETCH NEXT FROM wideColCursor INTO @wideCol;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        SELECT @wideConstraintName = dc.name
        FROM sys.columns c
        JOIN sys.default_constraints dc ON c.default_object_id = dc.object_id
        WHERE c.object_id = OBJECT_ID('NOTE_ITEM') AND c.name = @wideCol;

        IF @wideConstraintName IS NOT NULL
        BEGIN
            SET @wideDynSql = 'ALTER TABLE NOTE_ITEM DROP CONSTRAINT ' + @wideConstraintName;
            EXEC sp_executesql @wideDynSql;
            SET @wideConstraintName = NULL;
        END

        SET @wideDynSql = 'ALTER TABLE NOTE_ITEM DROP COLUMN ' + @wideCol;
        EXEC sp_executesql @wideDynSql;

        FETCH NEXT FROM wideColCursor INTO @wideCol;
    END
    CLOSE wideColCursor;
    DEALLOCATE wideColCursor;
END

IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'type' AND lower(column_name) = 'deprecated')
    ALTER TABLE TYPE ADD deprecated INT NOT NULL DEFAULT 0;
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'brand' AND lower(column_name) = 'deprecated')
    ALTER TABLE BRAND ADD deprecated INT NOT NULL DEFAULT 0;
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'model' AND lower(column_name) = 'deprecated')
    ALTER TABLE MODEL ADD deprecated INT NOT NULL DEFAULT 0;
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'provider' AND lower(column_name) = 'deprecated')
    ALTER TABLE PROVIDER ADD deprecated INT NOT NULL DEFAULT 0;

-- "Generic" -> "Genérico / Otro": one-time rename so the seeded fallback BRAND row's name
-- matches exactly what every combo box has always displayed for it. No-op if no such row exists.
UPDATE BRAND SET name = N'Genérico / Otro' WHERE name = 'Generic';

-- NOTE_ITEM.type_name/brand_name/model_name and NOTE_PROVEEDOR.provider_name (plain text
-- snapshots) are replaced with real type_id/brand_id/model_id/provider_id foreign keys into the
-- catalog. Every distinct historical text value is resolved against the catalog
-- case-insensitively (regardless of a matching row's deprecated status); a value with no match
-- gets a brand-new deprecated=1 catalog row, so a historical value never silently becomes an
-- active, selectable entry. Row-by-row (not set-based) since each item's type/brand/model/link
-- must be resolved-or-created together, matching the Java migration's own logic exactly.
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'type_name')
BEGIN
    ALTER TABLE NOTE_ITEM ADD type_id INT NULL;
    ALTER TABLE NOTE_ITEM ADD brand_id INT NULL;
    ALTER TABLE NOTE_ITEM ADD model_id INT NULL;

    DECLARE @niItemId INT, @niTypeName NVARCHAR(255), @niBrandName NVARCHAR(255), @niModelName NVARCHAR(255);
    DECLARE @niIsAsset BIT, @niTypeId INT, @niBrandId INT, @niLinkId INT, @niModelId INT;
    DECLARE niCursor CURSOR FOR SELECT id, type_name, brand_name, model_name FROM NOTE_ITEM;
    OPEN niCursor;
    FETCH NEXT FROM niCursor INTO @niItemId, @niTypeName, @niBrandName, @niModelName;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        SET @niIsAsset = CASE WHEN EXISTS (SELECT 1 FROM NOTE_ITEM_ASSET WHERE item_id = @niItemId) THEN 1 ELSE 0 END;
        SET @niTypeName = NULLIF(LTRIM(RTRIM(ISNULL(@niTypeName, ''))), '');
        SET @niBrandName = NULLIF(LTRIM(RTRIM(ISNULL(@niBrandName, ''))), '');
        SET @niModelName = NULLIF(LTRIM(RTRIM(ISNULL(@niModelName, ''))), '');
        IF @niTypeName IS NULL SET @niTypeName = N'Genérico / Otro';
        IF @niBrandName IS NULL SET @niBrandName = N'Genérico / Otro';
        IF @niModelName IS NULL SET @niModelName = N'Genérico / Otro';

        SELECT @niTypeId = id FROM TYPE WHERE LOWER(name) = LOWER(@niTypeName);
        IF @niTypeId IS NULL
        BEGIN
            INSERT INTO TYPE (name, is_asset, requires_serial, deprecated) VALUES (@niTypeName, @niIsAsset, 0, 1);
            SET @niTypeId = SCOPE_IDENTITY();
        END

        SELECT @niBrandId = id FROM BRAND WHERE LOWER(name) = LOWER(@niBrandName);
        IF @niBrandId IS NULL
        BEGIN
            INSERT INTO BRAND (name, deprecated) VALUES (@niBrandName, 1);
            SET @niBrandId = SCOPE_IDENTITY();
        END

        SELECT @niLinkId = id FROM BRAND_TYPE_LINK WHERE type_id = @niTypeId AND brand_id = @niBrandId;
        IF @niLinkId IS NULL
        BEGIN
            INSERT INTO BRAND_TYPE_LINK (type_id, brand_id) VALUES (@niTypeId, @niBrandId);
            SET @niLinkId = SCOPE_IDENTITY();
        END

        SELECT @niModelId = id FROM MODEL WHERE brand_type_id = @niLinkId AND LOWER(name) = LOWER(@niModelName);
        IF @niModelId IS NULL
        BEGIN
            INSERT INTO MODEL (brand_type_id, name, deprecated) VALUES (@niLinkId, @niModelName, 1);
            SET @niModelId = SCOPE_IDENTITY();
        END

        UPDATE NOTE_ITEM SET type_id = @niTypeId, brand_id = @niBrandId, model_id = @niModelId WHERE id = @niItemId;

        SET @niTypeId = NULL; SET @niBrandId = NULL; SET @niLinkId = NULL; SET @niModelId = NULL;
        FETCH NEXT FROM niCursor INTO @niItemId, @niTypeName, @niBrandName, @niModelName;
    END
    CLOSE niCursor;
    DEALLOCATE niCursor;

    DECLARE @niTextColConstraint NVARCHAR(128);
    SELECT @niTextColConstraint = dc.name FROM sys.columns c
        JOIN sys.default_constraints dc ON c.default_object_id = dc.object_id
        WHERE c.object_id = OBJECT_ID('NOTE_ITEM') AND c.name IN ('type_name', 'brand_name', 'model_name');
    IF @niTextColConstraint IS NOT NULL
        EXEC('ALTER TABLE NOTE_ITEM DROP CONSTRAINT ' + @niTextColConstraint);
    ALTER TABLE NOTE_ITEM DROP COLUMN type_name, brand_name, model_name;

    ALTER TABLE NOTE_ITEM ALTER COLUMN type_id INT NOT NULL;
    ALTER TABLE NOTE_ITEM ALTER COLUMN brand_id INT NOT NULL;
    ALTER TABLE NOTE_ITEM ALTER COLUMN model_id INT NOT NULL;
    ALTER TABLE NOTE_ITEM ADD CONSTRAINT fk_note_item_type FOREIGN KEY (type_id) REFERENCES TYPE(id);
    ALTER TABLE NOTE_ITEM ADD CONSTRAINT fk_note_item_brand FOREIGN KEY (brand_id) REFERENCES BRAND(id);
    ALTER TABLE NOTE_ITEM ADD CONSTRAINT fk_note_item_model FOREIGN KEY (model_id) REFERENCES MODEL(id);
END

-- Nothing else references NOTE_PROVEEDOR by foreign key, so this is a simpler
-- add-nullable/backfill/tighten sequence than NOTE_ITEM's above.
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_proveedor' AND lower(column_name) = 'provider_name')
BEGIN
    ALTER TABLE NOTE_PROVEEDOR ADD provider_id INT NULL;

    INSERT INTO PROVIDER (name, deprecated)
    SELECT DISTINCT ISNULL(NULLIF(LTRIM(RTRIM(p.provider_name)), ''), N'Desconocido')
    FROM NOTE_PROVEEDOR p
    WHERE NOT EXISTS (
        SELECT 1 FROM PROVIDER pr
        WHERE LOWER(pr.name) = LOWER(ISNULL(NULLIF(LTRIM(RTRIM(p.provider_name)), ''), N'Desconocido'))
    );

    UPDATE p
    SET p.provider_id = pr.id
    FROM NOTE_PROVEEDOR p
    JOIN PROVIDER pr ON LOWER(pr.name) = LOWER(ISNULL(NULLIF(LTRIM(RTRIM(p.provider_name)), ''), N'Desconocido'));

    DECLARE @npConstraint NVARCHAR(128);
    SELECT @npConstraint = dc.name FROM sys.columns c
        JOIN sys.default_constraints dc ON c.default_object_id = dc.object_id
        WHERE c.object_id = OBJECT_ID('NOTE_PROVEEDOR') AND c.name = 'provider_name';
    IF @npConstraint IS NOT NULL
        EXEC('ALTER TABLE NOTE_PROVEEDOR DROP CONSTRAINT ' + @npConstraint);
    ALTER TABLE NOTE_PROVEEDOR DROP COLUMN provider_name;
    ALTER TABLE NOTE_PROVEEDOR ALTER COLUMN provider_id INT NOT NULL;
    ALTER TABLE NOTE_PROVEEDOR ADD CONSTRAINT fk_note_proveedor_provider FOREIGN KEY (provider_id) REFERENCES PROVIDER(id);
END

-- MODEL.brand_type_id used to be NOT NULL, which forced a duplicate "Genérico / Otro" row per
-- BRAND_TYPE_LINK purely to satisfy the FK. Relax to nullable, then consolidate every existing
-- per-link "Genérico / Otro" row into a single global row (brand_type_id = NULL), re-point
-- historical NOTE_ITEM references onto it, and deprecate (never delete) the old per-link rows.
IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE lower(table_name) = 'model' AND lower(column_name) = 'brand_type_id' AND IS_NULLABLE = 'NO'
)
BEGIN
    ALTER TABLE MODEL ALTER COLUMN brand_type_id INT NULL;

    DECLARE @genericLabel NVARCHAR(255) = N'Genérico / Otro';
    DECLARE @globalModelId INT;

    SELECT TOP 1 @globalModelId = id FROM MODEL
        WHERE brand_type_id IS NOT NULL AND LOWER(name) = LOWER(@genericLabel) ORDER BY id;

    IF @globalModelId IS NULL
    BEGIN
        INSERT INTO MODEL (brand_type_id, name, deprecated) VALUES (NULL, @genericLabel, 0);
        SET @globalModelId = SCOPE_IDENTITY();
    END
    ELSE
    BEGIN
        UPDATE MODEL SET brand_type_id = NULL, deprecated = 0 WHERE id = @globalModelId;
    END

    UPDATE NOTE_ITEM SET model_id = @globalModelId
    WHERE model_id IN (
        SELECT id FROM MODEL WHERE brand_type_id IS NOT NULL AND LOWER(name) = LOWER(@genericLabel) AND id <> @globalModelId
    );
    UPDATE MODEL SET deprecated = 1
    WHERE brand_type_id IS NOT NULL AND LOWER(name) = LOWER(@genericLabel) AND id <> @globalModelId;
END

-- A BRAND_TYPE_LINK for the generic brand left over from before it stopped needing one makes
-- getBrandsForType() return it via a real JOIN for that one type — sorted alphabetically among
-- real brands — while every other type only shows it via client-side synthesis (always appended
-- last), a real per-type rendering inconsistency. Removes any such link with zero MODEL rows
-- left under it AND zero MODEL_STOCK rows (a link with a legitimate stock number for the global
-- model, even with no MODEL rows of its own, must survive).
DECLARE @cleanupGenericBrandId INT;
SELECT @cleanupGenericBrandId = id FROM BRAND WHERE LOWER(name) = LOWER(N'Genérico / Otro');
IF @cleanupGenericBrandId IS NOT NULL
BEGIN
    DELETE FROM BRAND_TYPE_LINK
    WHERE brand_id = @cleanupGenericBrandId
      AND NOT EXISTS (SELECT 1 FROM MODEL WHERE brand_type_id = BRAND_TYPE_LINK.id)
      AND NOT EXISTS (SELECT 1 FROM MODEL_STOCK WHERE brand_type_id = BRAND_TYPE_LINK.id);
END

-- Narrow any of the above columns that a pre-existing installation already created as
-- NVARCHAR(MAX) (either via an older CREATE TABLE or an older ADD COLUMN call above, before these
-- bounds existed) down to the real length limit. A brand-new install already declares the bounded
-- type directly, so this only ever does real work on an already-running remote database. Wrapped
-- per-column: an installation with rows already longer than the new bound would fail the ALTER —
-- swallow it and leave the column at its current (wider) size rather than blocking this script;
-- an admin who wants the tighter bound enforced would need to trim the offending row(s) first.
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'sn_validation' AND lower(column_name) = 'regex_pattern' AND (character_maximum_length <> 500 OR character_maximum_length IS NULL))
BEGIN TRY ALTER TABLE SN_VALIDATION ALTER COLUMN regex_pattern NVARCHAR(500); END TRY BEGIN CATCH END CATCH
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'observations' AND (character_maximum_length <> 300 OR character_maximum_length IS NULL))
BEGIN TRY ALTER TABLE NOTE_REPORT ALTER COLUMN observations NVARCHAR(300); END TRY BEGIN CATCH END CATCH
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_entrega_devolucion' AND lower(column_name) = 'motivo' AND (character_maximum_length <> 100 OR character_maximum_length IS NULL))
BEGIN TRY ALTER TABLE NOTE_ENTREGA_DEVOLUCION ALTER COLUMN motivo NVARCHAR(100); END TRY BEGIN CATCH END CATCH
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_proveedor' AND lower(column_name) = 'motivo' AND (character_maximum_length <> 100 OR character_maximum_length IS NULL))
BEGIN TRY ALTER TABLE NOTE_PROVEEDOR ALTER COLUMN motivo NVARCHAR(100); END TRY BEGIN CATCH END CATCH
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'observations' AND (character_maximum_length <> 200 OR character_maximum_length IS NULL))
BEGIN TRY ALTER TABLE NOTE_ITEM ALTER COLUMN observations NVARCHAR(200); END TRY BEGIN CATCH END CATCH
-- glpi_rejection_reason/return_rejection_reason used to live on NOTE_ITEM itself and were
-- narrowed here; they now live on NOTE_ITEM_GLPI_TRACKING/NOTE_ITEM_RETURN_TRACKING, created with
-- the NVARCHAR(300) bound from the start — nothing left to narrow on this table for either
-- column. Same reasoning for failure_cause/failure_details/area_evento/rejection_reason (split
-- into NOTE_DEVOLUCION_FALLA/NOTE_PRESTAMO_AREA_EVENTO/NOTE_REPORT_REJECTION) — each new table is
-- only ever created with its bounded NVARCHAR(n) type from the start.

COMMIT TRANSACTION;
