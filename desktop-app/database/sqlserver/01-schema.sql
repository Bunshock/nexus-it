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
-- EXISTS the way PostgreSQL/SQLite do).
-- Run 02-seed-equipment.sql afterward to populate the starting Type/Brand/Model catalog.

BEGIN TRANSACTION;

IF OBJECT_ID('dbo.TYPE', 'U') IS NULL
BEGIN
    CREATE TABLE TYPE (
        id              INT IDENTITY(1,1) PRIMARY KEY,
        name            NVARCHAR(255) NOT NULL UNIQUE,
        is_asset        INT NOT NULL DEFAULT 1,
        requires_serial INT NOT NULL DEFAULT 0
    );
END

IF OBJECT_ID('dbo.BRAND', 'U') IS NULL
BEGIN
    CREATE TABLE BRAND (
        id   INT IDENTITY(1,1) PRIMARY KEY,
        name NVARCHAR(255) NOT NULL UNIQUE
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

IF OBJECT_ID('dbo.MODEL', 'U') IS NULL
BEGIN
    CREATE TABLE MODEL (
        id            INT IDENTITY(1,1) PRIMARY KEY,
        brand_type_id INT NOT NULL REFERENCES BRAND_TYPE_LINK(id),
        name          NVARCHAR(255) NOT NULL
    );
END

-- Wrapped in a duplicate-safe check: an existing database with pre-existing duplicate
-- (brand_type_id, name) rows would fail this statement — same "fail safely" pattern as the
-- column additions below; enforcement degrades to app-layer-only (SqliteEquipmentService.
-- addModel/renameModel) rather than aborting the whole script.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_model_brand_type_name')
BEGIN
    BEGIN TRY
        CREATE UNIQUE INDEX idx_model_brand_type_name ON MODEL(brand_type_id, name);
    END TRY
    BEGIN CATCH
        -- pre-existing duplicate model names for the same brand+type — see comment above
    END CATCH
END

IF OBJECT_ID('dbo.SN_VALIDATION', 'U') IS NULL
BEGIN
    CREATE TABLE SN_VALIDATION (
        id            INT IDENTITY(1,1) PRIMARY KEY,
        model_id      INT NOT NULL REFERENCES MODEL(id),
        regex_pattern NVARCHAR(MAX),
        is_active     INT NOT NULL DEFAULT 1
    );
END

IF OBJECT_ID('dbo.PROVIDER', 'U') IS NULL
BEGIN
    CREATE TABLE PROVIDER (
        id   INT IDENTITY(1,1) PRIMARY KEY,
        name NVARCHAR(255) NOT NULL UNIQUE
    );
END

IF OBJECT_ID('dbo.NOTE_REPORT', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_REPORT (
        id              INT IDENTITY(1,1) PRIMARY KEY,
        created_at      NVARCHAR(MAX) NOT NULL,
        profile_type    NVARCHAR(255) NOT NULL,
        glpi_synced     INT NOT NULL DEFAULT 0,
        technician_name NVARCHAR(255),
        technician_dni  NVARCHAR(255)
    );
END

IF OBJECT_ID('dbo.NOTE_ENTREGA_DEVOLUCION', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ENTREGA_DEVOLUCION (
        note_report_id  INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
        user_name       NVARCHAR(255),
        user_dni        NVARCHAR(255),
        user_email      NVARCHAR(255),
        motivo          NVARCHAR(MAX),
        failure_cause   NVARCHAR(MAX),
        failure_details NVARCHAR(MAX),
        area_evento     NVARCHAR(MAX)
    );
END

IF OBJECT_ID('dbo.NOTE_PROVEEDOR', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_PROVEEDOR (
        note_report_id   INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
        provider_name    NVARCHAR(255),
        cuit             NVARCHAR(255),
        motivo           NVARCHAR(MAX),
        responsible_name NVARCHAR(255),
        responsible_dni  NVARCHAR(255)
    );
END

-- Slim base table — asset-only, countable-only, GLPI-tracking, and return-tracking fields each
-- live in their own subtype table below (added 2026-07-22), so a row never carries a column that
-- doesn't apply to it. See the backfill/DROP COLUMN block further down for the migration path off
-- the old wide 16-column shape on an already-running installation.
IF OBJECT_ID('dbo.NOTE_ITEM', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ITEM (
        id           INT IDENTITY(1,1) PRIMARY KEY,
        note_id      INT NOT NULL REFERENCES NOTE_REPORT(id),
        type_name    NVARCHAR(255) NOT NULL,
        brand_name   NVARCHAR(255),
        model_name   NVARCHAR(255),
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
        status_updated_at NVARCHAR(MAX)
    );
END

IF OBJECT_ID('dbo.NOTE_ITEM_RETURN_TRACKING', 'U') IS NULL
BEGIN
    CREATE TABLE NOTE_ITEM_RETURN_TRACKING (
        item_id           INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
        status            NVARCHAR(50) NOT NULL,
        rejection_reason  NVARCHAR(300),
        status_updated_at NVARCHAR(MAX)
    );
END

-- CREATE TABLE-if-missing (above) silently no-ops on a database that already has the table from
-- an older schema version, so columns added later must be migrated here too — kept in sync with
-- RemoteDatabaseService.ensureSchema()'s own migration block. Every ADD is its own existence
-- check (T-SQL has no ADD COLUMN IF NOT EXISTS).

-- requires_serial replaces ItemDialogController's old hardcoded "Notebook".equals(type.getName())
-- check. Only backfill requires_serial=1 for the existing Notebook-named type on the run that
-- actually adds the column (matching RemoteDatabaseService.ensureSchema()'s columnExists() check
-- in Java) — otherwise re-running this script would silently re-enable the flag for anyone who
-- deliberately turned it off for a type literally named "notebook" via the app's admin UI.
IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE lower(table_name) = 'type' AND lower(column_name) = 'requires_serial'
)
BEGIN
    ALTER TABLE TYPE ADD requires_serial INT NOT NULL DEFAULT 0;
    UPDATE TYPE SET requires_serial = 1 WHERE LOWER(name) = 'notebook';
END

IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_entrega_devolucion' AND lower(column_name) = 'failure_cause')
    ALTER TABLE NOTE_ENTREGA_DEVOLUCION ADD failure_cause NVARCHAR(MAX);
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_entrega_devolucion' AND lower(column_name) = 'failure_details')
    ALTER TABLE NOTE_ENTREGA_DEVOLUCION ADD failure_details NVARCHAR(MAX);
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_proveedor' AND lower(column_name) = 'responsible_name')
    ALTER TABLE NOTE_PROVEEDOR ADD responsible_name NVARCHAR(255);
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_proveedor' AND lower(column_name) = 'responsible_dni')
    ALTER TABLE NOTE_PROVEEDOR ADD responsible_dni NVARCHAR(255);
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'technician_name')
    ALTER TABLE NOTE_REPORT ADD technician_name NVARCHAR(255);
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_report' AND lower(column_name) = 'technician_dni')
    ALTER TABLE NOTE_REPORT ADD technician_dni NVARCHAR(255);
-- Préstamos section (added 2026-07-17) — baked into the CREATE TABLE blocks above for a fresh
-- database; this existence-checked ALTER covers a database that already had this table from
-- before that feature existed.
IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_entrega_devolucion' AND lower(column_name) = 'area_evento')
    ALTER TABLE NOTE_ENTREGA_DEVOLUCION ADD area_evento NVARCHAR(MAX);

-- NOTE_ITEM subtype-table normalization (added 2026-07-22): only relevant to a database still on
-- the old wide NOTE_ITEM shape (is_asset present) — on a brand-new install NOTE_ITEM is already
-- the slim shape from the CREATE TABLE block above, and these columns must NOT be re-added there
-- just because they're "missing". Walking a genuinely old database up to the full wide shape here
-- is a prerequisite for the backfill/DROP COLUMN block that follows, which splits it into the
-- 5-table shape.
IF EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'is_asset')
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'glpi_status')
        ALTER TABLE NOTE_ITEM ADD glpi_status NVARCHAR(50) NOT NULL DEFAULT 'N_A';
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'glpi_rejection_reason')
        ALTER TABLE NOTE_ITEM ADD glpi_rejection_reason NVARCHAR(MAX);
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'glpi_status_updated_at')
        ALTER TABLE NOTE_ITEM ADD glpi_status_updated_at NVARCHAR(MAX);
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'return_status')
        ALTER TABLE NOTE_ITEM ADD return_status NVARCHAR(50) NOT NULL DEFAULT 'N_A';
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'return_rejection_reason')
        ALTER TABLE NOTE_ITEM ADD return_rejection_reason NVARCHAR(MAX);
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE lower(table_name) = 'note_item' AND lower(column_name) = 'return_status_updated_at')
        ALTER TABLE NOTE_ITEM ADD return_status_updated_at NVARCHAR(MAX);

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

    DECLARE @col NVARCHAR(50), @constraintName NVARCHAR(128), @dynSql NVARCHAR(MAX);
    DECLARE colCursor CURSOR FOR SELECT name FROM @wideCols;
    OPEN colCursor;
    FETCH NEXT FROM colCursor INTO @col;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        SELECT @constraintName = dc.name
        FROM sys.columns c
        JOIN sys.default_constraints dc ON c.default_object_id = dc.object_id
        WHERE c.object_id = OBJECT_ID('NOTE_ITEM') AND c.name = @col;

        IF @constraintName IS NOT NULL
        BEGIN
            SET @dynSql = 'ALTER TABLE NOTE_ITEM DROP CONSTRAINT ' + @constraintName;
            EXEC sp_executesql @dynSql;
            SET @constraintName = NULL;
        END

        SET @dynSql = 'ALTER TABLE NOTE_ITEM DROP COLUMN ' + @col;
        EXEC sp_executesql @dynSql;

        FETCH NEXT FROM colCursor INTO @col;
    END
    CLOSE colCursor;
    DEALLOCATE colCursor;
END

COMMIT TRANSACTION;
