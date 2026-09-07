-- H2-native test schema for ConfigRepositoryTest — same "dialect-neutral stand-in" precedent as
-- catalog-repository-test-schema.sql/notes-repository-test-schema.sql. IF NOT EXISTS / MERGE
-- matter for the same reason documented there (this @Sql script re-runs before every test method
-- against one persistent embedded H2 instance).

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
MERGE INTO APP_CONFIG (id, smtp_host, smtp_port, smtp_sender_address)
    KEY (id) VALUES (1, 'smtp.example.org', 587, 'notas@example.org');

CREATE TABLE IF NOT EXISTS APP_CONFIG_MOTIVO_OPTION (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    category   VARCHAR(20) NOT NULL,
    motivo     VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0
);
DELETE FROM APP_CONFIG_MOTIVO_OPTION;
INSERT INTO APP_CONFIG_MOTIVO_OPTION (category, motivo, sort_order) VALUES
    ('entrega', 'Nuevo ingreso', 0),
    ('entrega', 'Otro', 1),
    ('devolucion', 'Falla', 0);

CREATE TABLE IF NOT EXISTS APP_CONFIG_FALLA_OPTION (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    option_value VARCHAR(100) NOT NULL,
    sort_order   INT NOT NULL DEFAULT 0
);
DELETE FROM APP_CONFIG_FALLA_OPTION;
INSERT INTO APP_CONFIG_FALLA_OPTION (option_value, sort_order) VALUES ('No enciende', 0), ('Otro', 1);

CREATE TABLE IF NOT EXISTS APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO (
    id     INT AUTO_INCREMENT PRIMARY KEY,
    motivo VARCHAR(100) NOT NULL
);
DELETE FROM APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO;
INSERT INTO APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO (motivo) VALUES ('Garantía'), ('Reparación');

-- ConfigRepository now takes an AuditRepository dependency (recordAdminAction() call sites) —
-- needs AUDIT_ADMIN_ACTION to exist even though this test file asserts nothing about it directly.
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
