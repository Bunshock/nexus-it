-- H2-native test schema for AuditRepositoryTest — same "dialect-neutral stand-in" precedent as
-- the other *-repository-test-schema.sql files. IF NOT EXISTS matters for the same reason
-- documented there (this @Sql script re-runs before every test method).

CREATE TABLE IF NOT EXISTS TYPE (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS BRAND (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
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
    name VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS SEDE (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS NOTE_REPORT (
    id INT AUTO_INCREMENT PRIMARY KEY,
    profile_type VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS NOTE_ITEM (
    id INT AUTO_INCREMENT PRIMARY KEY,
    note_id INT NOT NULL REFERENCES NOTE_REPORT(id)
);

CREATE TABLE IF NOT EXISTS AUDIT_LOGIN (
    id             INT AUTO_INCREMENT PRIMARY KEY,
    username       VARCHAR(100) NOT NULL,
    success        INT NOT NULL,
    failure_reason VARCHAR(255),
    attempted_at   TIMESTAMP NOT NULL
);

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
