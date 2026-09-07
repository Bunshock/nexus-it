-- Minimal, H2-native test schema for CatalogRepositoryTest — NOT a copy of
-- db/migration/V1__init.sql (that's real SQL-Server-only T-SQL, see
-- application.yml's dev-profile comment on why H2 can't run it). Same
-- "dialect-neutral stand-in, not the real schema" precedent the desktop
-- app's own CatalogMigrationToolTest/SqliteEquipmentServiceTest already use.
--
-- IF NOT EXISTS matters here: @Sql on the test class re-runs this script before
-- EVERY test method, but @JdbcTest's embedded H2 instance (and its schema, unlike
-- the per-test-rolled-back DML) persists across methods within the same class.

CREATE TABLE IF NOT EXISTS TYPE (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    is_asset INT NOT NULL DEFAULT 0,
    requires_serial INT NOT NULL DEFAULT 0,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS BRAND (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS BRAND_TYPE_LINK (
    id INT AUTO_INCREMENT PRIMARY KEY,
    type_id INT NOT NULL REFERENCES TYPE(id),
    brand_id INT NOT NULL REFERENCES BRAND(id),
    CONSTRAINT uq_brand_type_link UNIQUE (type_id, brand_id)
);

CREATE TABLE IF NOT EXISTS MODEL (
    id INT AUTO_INCREMENT PRIMARY KEY,
    brand_type_id INT REFERENCES BRAND_TYPE_LINK(id),
    name VARCHAR(255) NOT NULL,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS SEDE (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS MODEL_STOCK (
    brand_type_id INT NOT NULL REFERENCES BRAND_TYPE_LINK(id),
    model_id INT NOT NULL REFERENCES MODEL(id),
    sede_id INT NOT NULL REFERENCES SEDE(id),
    stock INT NOT NULL DEFAULT 0,
    CONSTRAINT pk_model_stock PRIMARY KEY (brand_type_id, model_id, sede_id)
);

CREATE TABLE IF NOT EXISTS PROVIDER (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS SN_VALIDATION (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    model_id      INT NOT NULL REFERENCES MODEL(id),
    regex_pattern VARCHAR(500),
    is_active     INT NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS SEDE_SHIPPING_INFO (
    id INT AUTO_INCREMENT PRIMARY KEY,
    sede_id INT NOT NULL REFERENCES SEDE(id),
    destination_label VARCHAR(255) NOT NULL,
    address VARCHAR(500),
    recipients VARCHAR(500),
    deprecated INT NOT NULL DEFAULT 0
);

-- APP_CONFIG — needed because CatalogRepository.genericLabel() now reads live from
-- ConfigRepository instead of a hardcoded constant. Single seeded row, id = 1.
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
MERGE INTO APP_CONFIG (id) KEY (id) VALUES (1);

-- Needed because CatalogRepository.setModelStock() now writes an AUDIT_STOCK row on every actual
-- stock change (via AuditRepository).
CREATE TABLE IF NOT EXISTS AUDIT_STOCK (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    brand_type_id INT NOT NULL REFERENCES BRAND_TYPE_LINK(id),
    model_id      INT NOT NULL REFERENCES MODEL(id),
    sede_id       INT NOT NULL REFERENCES SEDE(id),
    username      VARCHAR(100) NOT NULL,
    old_stock     INT NOT NULL,
    new_stock     INT NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    changed_at    TIMESTAMP NOT NULL
);

-- Needed because CatalogRepository's Type/Brand/Model add/rename/remove methods now write an
-- AUDIT_ADMIN_ACTION row on every real change (via AuditRepository).
CREATE TABLE IF NOT EXISTS AUDIT_ADMIN_ACTION (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(100) NOT NULL,
    action       VARCHAR(100) NOT NULL,
    target_type  VARCHAR(100) NOT NULL,
    target_id    VARCHAR(255),
    old_value    VARCHAR(1000),
    new_value    VARCHAR(1000),
    reason       VARCHAR(500),
    performed_at TIMESTAMP NOT NULL
);
