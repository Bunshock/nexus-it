-- Config storage — did NOT exist in V1__init.sql (a straight copy of the desktop app's
-- database/sqlserver/01-schema.sql), because in the old dual-store architecture this data lived
-- in a local app-config.json file plus a local-only SQLite APP_SETTINGS table, never synced to
-- the shared remote database ("APP_SETTINGS and SMTP config are always local SQLite regardless
-- of remote config" — desktop app's own CLAUDE.md). The middleware IS the one shared server now,
-- so this genuinely needs its own table — there's no per-machine local copy anymore.
--
-- Single-row APP_CONFIG (id is CHECK'd to 1) rather than a generic key-value table: v1 is
-- single-org (D1b — instance-per-org, not multi-tenant), so there is exactly one configuration,
-- and typed columns are easier to read/validate than a key-value blob for that shape.
-- smtp_password_encrypted is AES-256/GCM ciphertext (see EncryptionService) — never returned by
-- GET /config, write-only via PUT, same convention as the desktop app's own SettingsController.

IF OBJECT_ID('dbo.APP_CONFIG', 'U') IS NULL
BEGIN
    CREATE TABLE APP_CONFIG (
        id                       INT NOT NULL PRIMARY KEY CHECK (id = 1),
        af_enabled               INT NOT NULL DEFAULT 1,
        af_prefix                NVARCHAR(50) NOT NULL DEFAULT 'IT',
        af_separator             NVARCHAR(10) NOT NULL DEFAULT '-',
        smtp_host                NVARCHAR(255),
        smtp_port                INT,
        smtp_sender_address      NVARCHAR(255),
        smtp_password_encrypted  NVARCHAR(500),
        note_item_limit          INT NOT NULL DEFAULT 50,
        failure_trigger_motivo   NVARCHAR(100) NOT NULL DEFAULT 'Falla',
        generic_label            NVARCHAR(255) NOT NULL DEFAULT 'Genérico / Otro'
    );
    INSERT INTO APP_CONFIG (id, note_item_limit) VALUES (1, 8);
END

-- category is app-layer validated against the 4 known motivoOptions keys (entrega/finDeContrato/
-- proveedor/devolucion) — no DB CHECK constraint, since T-SQL CHECK constraints on a value list
-- are fine but this project keeps that kind of validation in the service layer elsewhere too
-- (see e.g. ReasonRequest/NameRequest bean validation) for a consistent error-envelope message.
IF OBJECT_ID('dbo.APP_CONFIG_MOTIVO_OPTION', 'U') IS NULL
BEGIN
    CREATE TABLE APP_CONFIG_MOTIVO_OPTION (
        id         INT IDENTITY(1,1) PRIMARY KEY,
        category   NVARCHAR(20) NOT NULL,
        motivo     NVARCHAR(100) NOT NULL,
        sort_order INT NOT NULL DEFAULT 0
    );
END

IF OBJECT_ID('dbo.APP_CONFIG_FALLA_OPTION', 'U') IS NULL
BEGIN
    CREATE TABLE APP_CONFIG_FALLA_OPTION (
        id         INT IDENTITY(1,1) PRIMARY KEY,
        option_value NVARCHAR(100) NOT NULL,
        sort_order INT NOT NULL DEFAULT 0
    );
END

IF OBJECT_ID('dbo.APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO', 'U') IS NULL
BEGIN
    CREATE TABLE APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO (
        id     INT IDENTITY(1,1) PRIMARY KEY,
        motivo NVARCHAR(100) NOT NULL
    );
END

-- Seed defaults matching the desktop app's shipped app-config.json — real, useful dropdown
-- values (not sensitive), but SMTP host/port/sender deliberately left unset here rather than
-- copying the real org's mail server into a checked-in migration script; set those via PUT
-- /api/v1/config once deployed.
IF NOT EXISTS (SELECT 1 FROM APP_CONFIG_MOTIVO_OPTION)
BEGIN
    INSERT INTO APP_CONFIG_MOTIVO_OPTION (category, motivo, sort_order) VALUES
        ('entrega', 'Nuevo ingreso', 0),
        ('entrega', 'Recambio - Falla', 1),
        ('entrega', 'Recambio - Obsolescencia', 2),
        ('entrega', 'Asignación inicial', 3),
        ('entrega', 'Reparación', 4),
        ('entrega', 'Otro', 5),
        ('finDeContrato', 'Fin de contrato', 0),
        ('finDeContrato', 'Otro', 1),
        ('proveedor', 'Garantía', 0),
        ('proveedor', 'Reparación', 1),
        ('proveedor', 'Devolución de préstamo', 2),
        ('proveedor', 'Otro', 3),
        ('devolucion', 'Baja / Desvinculación', 0),
        ('devolucion', 'Falla', 1),
        ('devolucion', 'Obsolescencia', 2),
        ('devolucion', 'Otro', 3);
END

IF NOT EXISTS (SELECT 1 FROM APP_CONFIG_FALLA_OPTION)
BEGIN
    INSERT INTO APP_CONFIG_FALLA_OPTION (option_value, sort_order) VALUES
        ('Pantalla rota', 0),
        ('No enciende', 1),
        ('Daño por líquido', 2),
        ('Batería agotada / no carga', 3),
        ('Falla de software / sistema operativo', 4),
        ('Componente faltante o dañado', 5),
        ('Otro', 6);
END

IF NOT EXISTS (SELECT 1 FROM APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO)
BEGIN
    INSERT INTO APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO (motivo) VALUES ('Garantía'), ('Reparación');
END
