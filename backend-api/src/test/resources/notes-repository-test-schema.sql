-- H2-native test schema for NotesRepositoryTest — same "dialect-neutral stand-in" precedent as
-- catalog-repository-test-schema.sql. IF NOT EXISTS matters for the same reason documented there
-- (this @Sql script re-runs before every test method against one persistent embedded instance).

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

CREATE TABLE IF NOT EXISTS NOTE_REPORT (
    id INT AUTO_INCREMENT PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
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

CREATE TABLE IF NOT EXISTS NOTE_ITEM (
    id INT AUTO_INCREMENT PRIMARY KEY,
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
    id INT AUTO_INCREMENT PRIMARY KEY,
    item_id INT NOT NULL REFERENCES NOTE_ITEM(id),
    tracking_type VARCHAR(20) NOT NULL,
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
    id INT AUTO_INCREMENT PRIMARY KEY,
    item_id INT NOT NULL REFERENCES NOTE_ITEM(id),
    status VARCHAR(50) NOT NULL,
    quantity INT NOT NULL,
    reason VARCHAR(300),
    updated_at VARCHAR(50) NOT NULL
);

-- APP_CONFIG(+seed) is needed because CatalogRepository (a NotesRepository dependency) now reads
-- genericLabel() live from ConfigRepository. APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO is needed
-- directly — NotesRepository.isProviderReturnable() reads it instead of a hardcoded constant now.
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

CREATE TABLE IF NOT EXISTS APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO (
    id     INT AUTO_INCREMENT PRIMARY KEY,
    motivo VARCHAR(100) NOT NULL
);
DELETE FROM APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO;
INSERT INTO APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO (motivo) VALUES ('Garantía'), ('Reparación');

-- Needed because NotesRepository now writes AUDIT_STOCK (approval-time moves) and
-- AUDIT_ITEM_STATUS (sync/reject-sync/return/lost/allocate) rows via AuditRepository.
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

CREATE TABLE IF NOT EXISTS AUDIT_ITEM_STATUS (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    item_id     INT NOT NULL REFERENCES NOTE_ITEM(id),
    status_kind VARCHAR(20) NOT NULL,
    old_status  VARCHAR(50) NOT NULL,
    new_status  VARCHAR(50) NOT NULL,
    reason      VARCHAR(500),
    quantity    INT NOT NULL DEFAULT 1,
    username    VARCHAR(100) NOT NULL,
    changed_at  TIMESTAMP NOT NULL
);
