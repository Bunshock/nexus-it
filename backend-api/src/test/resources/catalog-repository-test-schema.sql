-- Minimal, H2-native test schema for CatalogRepositoryTest — NOT a copy of
-- db/migration/V1__init.sql (that's real SQL-Server-only T-SQL, see
-- application.yml's dev-profile comment on why H2 can't run it). Same
-- "dialect-neutral stand-in, not the real schema" precedent the desktop
-- app's own CatalogMigrationToolTest/SqliteEquipmentServiceTest already use.
--
-- M1 (GLPI-adapter strip): MODEL_STOCK/SN_VALIDATION/SEDE_SHIPPING_INFO/AUDIT_STOCK/
-- AUDIT_ADMIN_ACTION dropped from this file along with the CatalogRepository methods that used to
-- write/read them — CatalogRepository is read-only from M1 on.
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

CREATE TABLE IF NOT EXISTS PROVIDER (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    deprecated INT NOT NULL DEFAULT 0
);

-- APP_CONFIG — needed because CatalogRepository.genericLabel() reads live from ConfigRepository
-- instead of a hardcoded constant. Single seeded row, id = 1. SMTP columns removed (M1).
CREATE TABLE IF NOT EXISTS APP_CONFIG (
    id                      INT NOT NULL PRIMARY KEY,
    af_enabled              INT NOT NULL DEFAULT 1,
    af_prefix               VARCHAR(50) NOT NULL DEFAULT 'IT',
    af_separator            VARCHAR(10) NOT NULL DEFAULT '-',
    note_item_limit         INT NOT NULL DEFAULT 50,
    failure_trigger_motivo  VARCHAR(100) NOT NULL DEFAULT 'Falla',
    generic_label           VARCHAR(255) NOT NULL DEFAULT 'Genérico / Otro'
);
MERGE INTO APP_CONFIG (id) KEY (id) VALUES (1);
