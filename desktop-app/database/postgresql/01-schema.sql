-- Generador de Notas IT — PostgreSQL schema
--
-- Mirrors RemoteDatabaseService.ensureSchema() exactly (desktop-app/src/main/java/.../services/RemoteDatabaseService.java).
-- The app already creates this schema automatically the first time it connects to a configured
-- remote database (Configuración → Base de Datos → Editar, admin mode), so running this script
-- by hand is OPTIONAL — it's here for DBAs who want to provision the database independently of
-- ever launching the app, or who want the schema under version control / change review before
-- the app touches it.
--
-- Safe to run multiple times (CREATE TABLE IF NOT EXISTS + ADD COLUMN IF NOT EXISTS).
-- Run 02-seed-equipment.sql afterward to populate the starting Type/Brand/Model catalog.

BEGIN;

CREATE TABLE IF NOT EXISTS TYPE (
    id              SERIAL PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    is_asset        INTEGER NOT NULL DEFAULT 1,
    requires_serial INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS BRAND (
    id   SERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS BRAND_TYPE_LINK (
    id       SERIAL PRIMARY KEY,
    type_id  INTEGER NOT NULL REFERENCES TYPE(id),
    brand_id INTEGER NOT NULL REFERENCES BRAND(id),
    UNIQUE(type_id, brand_id)
);

CREATE TABLE IF NOT EXISTS MODEL (
    id            SERIAL PRIMARY KEY,
    brand_type_id INTEGER NOT NULL REFERENCES BRAND_TYPE_LINK(id),
    name          TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS SN_VALIDATION (
    id            SERIAL PRIMARY KEY,
    model_id      INTEGER NOT NULL REFERENCES MODEL(id),
    regex_pattern TEXT,
    description   TEXT,
    is_active     INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS PROVIDER (
    id   SERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS TECHNICIAN_PROFILE (
    id               SERIAL PRIMARY KEY,
    windows_username TEXT NOT NULL UNIQUE,
    name             TEXT,
    dni              TEXT,
    email            TEXT
);

CREATE TABLE IF NOT EXISTS NOTE_REPORT (
    id              SERIAL PRIMARY KEY,
    created_at      TEXT NOT NULL,
    profile_type    TEXT NOT NULL,
    glpi_synced     INTEGER NOT NULL DEFAULT 0,
    technician_id   INTEGER REFERENCES TECHNICIAN_PROFILE(id),
    technician_name TEXT,
    technician_dni  TEXT
);

CREATE TABLE IF NOT EXISTS NOTE_ENTREGA_DEVOLUCION (
    note_report_id  INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
    user_name       TEXT,
    user_dni        TEXT,
    user_email      TEXT,
    motivo          TEXT,
    failure_cause   TEXT,
    failure_details TEXT
);

CREATE TABLE IF NOT EXISTS NOTE_PROVEEDOR (
    note_report_id   INTEGER PRIMARY KEY REFERENCES NOTE_REPORT(id),
    provider_name    TEXT,
    cuit             TEXT,
    motivo           TEXT,
    responsible_name TEXT,
    responsible_dni  TEXT
);

CREATE TABLE IF NOT EXISTS NOTE_ITEM (
    id                     SERIAL PRIMARY KEY,
    note_id                INTEGER NOT NULL REFERENCES NOTE_REPORT(id),
    type_name              TEXT NOT NULL,
    brand_name             TEXT,
    model_name             TEXT,
    serial_number          TEXT,
    a_f                    TEXT,
    quantity               INTEGER NOT NULL DEFAULT 1,
    observations           TEXT,
    is_asset               INTEGER NOT NULL DEFAULT 0,
    glpi_status            TEXT NOT NULL DEFAULT 'N_A',
    glpi_rejection_reason  TEXT,
    glpi_status_updated_at TEXT
);

-- CREATE TABLE IF NOT EXISTS silently no-ops on a database that already has the table from an
-- older schema version, so columns added later must be migrated here too — kept in sync with
-- RemoteDatabaseService.ensureSchema()'s own migration block.

-- requires_serial replaces ItemDialogController's old hardcoded "Notebook".equals(type.getName())
-- check. Only backfill requires_serial=1 for the existing Notebook-named type on the run that
-- actually adds the column (matching RemoteDatabaseService.ensureSchema()'s information_schema
-- check in Java) — otherwise re-running this script would silently re-enable the flag for anyone
-- who deliberately turned it off for a type literally named "notebook" via the app's admin UI.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE lower(table_name) = 'type' AND lower(column_name) = 'requires_serial'
    ) THEN
        ALTER TABLE TYPE ADD COLUMN requires_serial INTEGER NOT NULL DEFAULT 0;
        UPDATE TYPE SET requires_serial = 1 WHERE LOWER(name) = 'notebook';
    END IF;
END $$;

ALTER TABLE NOTE_ENTREGA_DEVOLUCION ADD COLUMN IF NOT EXISTS failure_cause TEXT;
ALTER TABLE NOTE_ENTREGA_DEVOLUCION ADD COLUMN IF NOT EXISTS failure_details TEXT;
ALTER TABLE NOTE_PROVEEDOR ADD COLUMN IF NOT EXISTS responsible_name TEXT;
ALTER TABLE NOTE_PROVEEDOR ADD COLUMN IF NOT EXISTS responsible_dni TEXT;
ALTER TABLE NOTE_REPORT ADD COLUMN IF NOT EXISTS technician_name TEXT;
ALTER TABLE NOTE_REPORT ADD COLUMN IF NOT EXISTS technician_dni TEXT;
ALTER TABLE NOTE_ITEM ADD COLUMN IF NOT EXISTS is_asset INTEGER NOT NULL DEFAULT 0;
ALTER TABLE NOTE_ITEM ADD COLUMN IF NOT EXISTS glpi_status TEXT NOT NULL DEFAULT 'N_A';
ALTER TABLE NOTE_ITEM ADD COLUMN IF NOT EXISTS glpi_rejection_reason TEXT;
ALTER TABLE NOTE_ITEM ADD COLUMN IF NOT EXISTS glpi_status_updated_at TEXT;

COMMIT;
