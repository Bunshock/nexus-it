-- dev-schema.sql — H2-native consolidated schema for the `dev` Spring profile ONLY.
--
-- Run by spring.sql.init (see application.yml's dev document) against the in-memory H2 the dev
-- profile uses (URL has MODE=MSSQLServer), BEFORE dev-seed.sql. This exists so `mvn spring-boot:run`
-- (dev) — and the Phase B desktop client that develops against it — has a real, populated database
-- with no Docker / no SQL Server.
--
-- This is NOT a migration and NOT the schema source of truth. That is db/migration/V1__init.sql +
-- V2__config.sql (real SQL-Server T-SQL, Flyway-applied under the `sqlserver` profile). This file
-- is a hand-maintained mirror, table set + columns matching V1/V2. Identity + datetime types use
-- the MSSQL-compatible forms H2's MODE=MSSQLServer accepts (AUTO_INCREMENT / TIMESTAMP do not
-- parse under that mode). Keep it in sync when V1/V2 change.
--
-- Every statement is IF NOT EXISTS so a devtools restart (H2 mem can survive one) re-runs harmlessly.
-- Filtered unique indexes (idx_model_global_generic_name / idx_model_single_active_generic /
-- idx_sede_shipping_single_active in V1) are deliberately omitted — H2 has no partial-index support;
-- the dev seed just inserts one global-generic MODEL row and one SEDE_SHIPPING_INFO row per Sede.

-- ============================================================================
-- Roles / users / permissions
-- ============================================================================

CREATE TABLE IF NOT EXISTS ROLE (
    id   INT IDENTITY(1,1) PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS SEDE (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS APP_USER (
    id                 INT IDENTITY(1,1) PRIMARY KEY,
    username           VARCHAR(100) NOT NULL UNIQUE,
    role_id            INT NOT NULL REFERENCES ROLE(id),
    sede_id            INT REFERENCES SEDE(id),
    bypass_group_check INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ROLE_PERMISSION (
    id         INT IDENTITY(1,1) PRIMARY KEY,
    role_id    INT NOT NULL REFERENCES ROLE(id),
    permission VARCHAR(50) NOT NULL,
    CONSTRAINT uq_role_permission UNIQUE (role_id, permission)
);

-- ============================================================================
-- Equipment catalog
-- ============================================================================

CREATE TABLE IF NOT EXISTS TYPE (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    is_asset INT NOT NULL DEFAULT 0,
    requires_serial INT NOT NULL DEFAULT 0,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS BRAND (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS BRAND_TYPE_LINK (
    id INT IDENTITY(1,1) PRIMARY KEY,
    type_id INT NOT NULL REFERENCES TYPE(id),
    brand_id INT NOT NULL REFERENCES BRAND(id),
    CONSTRAINT uq_brand_type_link UNIQUE (type_id, brand_id)
);

-- brand_type_id NULL is reserved for the single global "Genérico / Otro" model.
CREATE TABLE IF NOT EXISTS MODEL (
    id INT IDENTITY(1,1) PRIMARY KEY,
    brand_type_id INT REFERENCES BRAND_TYPE_LINK(id),
    name VARCHAR(255) NOT NULL,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS MODEL_STOCK (
    brand_type_id INT NOT NULL REFERENCES BRAND_TYPE_LINK(id),
    model_id INT NOT NULL REFERENCES MODEL(id),
    sede_id INT NOT NULL REFERENCES SEDE(id),
    stock INT NOT NULL DEFAULT 0,
    CONSTRAINT pk_model_stock PRIMARY KEY (brand_type_id, model_id, sede_id)
);

CREATE TABLE IF NOT EXISTS SN_VALIDATION (
    id            INT IDENTITY(1,1) PRIMARY KEY,
    model_id      INT NOT NULL REFERENCES MODEL(id),
    regex_pattern VARCHAR(500),
    is_active     INT NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS PROVIDER (
    id INT IDENTITY(1,1) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS SEDE_SHIPPING_INFO (
    id INT IDENTITY(1,1) PRIMARY KEY,
    sede_id INT NOT NULL REFERENCES SEDE(id),
    destination_label VARCHAR(255) NOT NULL,
    address VARCHAR(500),
    recipients VARCHAR(500),
    deprecated INT NOT NULL DEFAULT 0
);

-- ============================================================================
-- Config (V2__config.sql equivalent)
-- ============================================================================

CREATE TABLE IF NOT EXISTS APP_CONFIG (
    id                      INT NOT NULL PRIMARY KEY,
    af_enabled              INT NOT NULL DEFAULT 1,
    af_prefix               VARCHAR(50) NOT NULL DEFAULT 'IT',
    af_separator            VARCHAR(10) NOT NULL DEFAULT '-',
    smtp_host               VARCHAR(255),
    smtp_port               INT,
    smtp_sender_address     VARCHAR(255),
    smtp_password_encrypted VARCHAR(500),
    note_item_limit         INT NOT NULL DEFAULT 50,
    failure_trigger_motivo  VARCHAR(100) NOT NULL DEFAULT 'Falla',
    generic_label           VARCHAR(255) NOT NULL DEFAULT 'Genérico / Otro'
);

CREATE TABLE IF NOT EXISTS APP_CONFIG_MOTIVO_OPTION (
    id         INT IDENTITY(1,1) PRIMARY KEY,
    category   VARCHAR(20) NOT NULL,
    motivo     VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS APP_CONFIG_FALLA_OPTION (
    id           INT IDENTITY(1,1) PRIMARY KEY,
    option_value VARCHAR(100) NOT NULL,
    sort_order   INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO (
    id     INT IDENTITY(1,1) PRIMARY KEY,
    motivo VARCHAR(100) NOT NULL
);

-- ============================================================================
-- Audit trail (append-only)
-- ============================================================================

CREATE TABLE IF NOT EXISTS AUDIT_LOGIN (
    id             INT IDENTITY(1,1) PRIMARY KEY,
    username       VARCHAR(100) NOT NULL,
    success        INT NOT NULL,
    failure_reason VARCHAR(255),
    attempted_at   DATETIME2 NOT NULL
);

CREATE TABLE IF NOT EXISTS AUDIT_STOCK (
    id            INT IDENTITY(1,1) PRIMARY KEY,
    brand_type_id INT NOT NULL REFERENCES BRAND_TYPE_LINK(id),
    model_id      INT NOT NULL REFERENCES MODEL(id),
    sede_id       INT NOT NULL REFERENCES SEDE(id),
    username      VARCHAR(100) NOT NULL,
    old_stock     INT NOT NULL,
    new_stock     INT NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    changed_at    DATETIME2 NOT NULL
);

CREATE TABLE IF NOT EXISTS AUDIT_ADMIN_ACTION (
    id           INT IDENTITY(1,1) PRIMARY KEY,
    username     VARCHAR(100) NOT NULL,
    action       VARCHAR(100) NOT NULL,
    target_type  VARCHAR(100) NOT NULL,
    target_id    VARCHAR(255),
    old_value    VARCHAR(1000),
    new_value    VARCHAR(1000),
    reason       VARCHAR(500),
    performed_at DATETIME2 NOT NULL
);

-- ============================================================================
-- Notes
-- ============================================================================

CREATE TABLE IF NOT EXISTS NOTE_REPORT (
    id INT IDENTITY(1,1) PRIMARY KEY,
    created_at DATETIME2 NOT NULL,
    profile_type VARCHAR(255) NOT NULL,
    technician_name VARCHAR(255),
    technician_dni VARCHAR(255),
    observations VARCHAR(300),
    sede_id INT REFERENCES SEDE(id),
    approval_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    stock_applied INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS NOTE_REPORT_REJECTION (
    note_report_id INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
    rejection_reason VARCHAR(300) NOT NULL
);

CREATE TABLE IF NOT EXISTS NOTE_ENTREGA_DEVOLUCION (
    note_report_id INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
    user_name VARCHAR(255),
    user_dni VARCHAR(255),
    user_email VARCHAR(255),
    motivo VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS NOTE_DEVOLUCION_FALLA (
    note_report_id INT PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
    failure_cause VARCHAR(100) NOT NULL,
    failure_details VARCHAR(200)
);

CREATE TABLE IF NOT EXISTS NOTE_PRESTAMO_AREA_EVENTO (
    note_report_id INT PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
    area_evento VARCHAR(200) NOT NULL
);

CREATE TABLE IF NOT EXISTS NOTE_PROVEEDOR (
    note_report_id INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
    provider_id INT NOT NULL REFERENCES PROVIDER(id),
    cuit VARCHAR(255),
    motivo VARCHAR(100),
    responsible_name VARCHAR(255),
    responsible_dni VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS NOTE_REMITO_SEDE (
    note_report_id INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
    shipping_info_id INT NOT NULL REFERENCES SEDE_SHIPPING_INFO(id)
);

CREATE TABLE IF NOT EXISTS NOTE_REMITO_OTHER (
    note_report_id INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
    destination_label VARCHAR(255) NOT NULL,
    address VARCHAR(500),
    recipients VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS NOTE_ITEM (
    id INT IDENTITY(1,1) PRIMARY KEY,
    note_id INT NOT NULL REFERENCES NOTE_REPORT(id),
    type_id INT NOT NULL REFERENCES TYPE(id),
    brand_id INT NOT NULL REFERENCES BRAND(id),
    model_id INT NOT NULL REFERENCES MODEL(id),
    observations VARCHAR(200),
    modifies_stock INT NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS NOTE_ITEM_ASSET (
    item_id INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
    serial_number VARCHAR(255),
    a_f VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS NOTE_ITEM_COUNTABLE (
    item_id INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
    quantity INT NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS NOTE_ITEM_STATUS_TRACKING (
    id INT IDENTITY(1,1) PRIMARY KEY,
    item_id INT NOT NULL REFERENCES NOTE_ITEM(id),
    tracking_type VARCHAR(20) NOT NULL CHECK (tracking_type IN ('GLPI', 'RETURN', 'GLPI_RETURN')),
    status VARCHAR(50) NOT NULL,
    rejection_reason VARCHAR(300),
    status_updated_at VARCHAR(50),
    CONSTRAINT uq_note_item_status_tracking UNIQUE (item_id, tracking_type)
);

CREATE TABLE IF NOT EXISTS NOTE_ITEM_STOCK_EXCEPTION (
    item_id INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
    reason VARCHAR(300) NOT NULL
);

CREATE TABLE IF NOT EXISTS NOTE_ITEM_RETURN_ALLOCATION (
    id INT IDENTITY(1,1) PRIMARY KEY,
    item_id INT NOT NULL REFERENCES NOTE_ITEM(id),
    status VARCHAR(50) NOT NULL,
    quantity INT NOT NULL,
    reason VARCHAR(300),
    updated_at VARCHAR(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS AUDIT_ITEM_STATUS (
    id          INT IDENTITY(1,1) PRIMARY KEY,
    item_id     INT NOT NULL REFERENCES NOTE_ITEM(id),
    status_kind VARCHAR(20) NOT NULL CHECK (status_kind IN ('GLPI', 'RETURN', 'GLPI_RETURN')),
    old_status  VARCHAR(50) NOT NULL,
    new_status  VARCHAR(50) NOT NULL,
    reason      VARCHAR(500),
    quantity    INT NOT NULL DEFAULT 1,
    username    VARCHAR(100) NOT NULL,
    changed_at  DATETIME2 NOT NULL
);
