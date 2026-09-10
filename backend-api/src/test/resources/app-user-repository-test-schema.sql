-- H2-native test schema for AppUserRepositoryTest — same "dialect-neutral stand-in" precedent as
-- the other *-repository-test-schema.sql files. IF NOT EXISTS matters for the same reason
-- documented there (this @Sql script re-runs before every test method against one persistent
-- embedded H2 instance).

CREATE TABLE IF NOT EXISTS ROLE (
    id   INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS SEDE (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL UNIQUE,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS APP_USER (
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    username           VARCHAR(100) NOT NULL UNIQUE,
    role_id            INT NOT NULL REFERENCES ROLE(id),
    sede_id            INT REFERENCES SEDE(id),
    bypass_group_check INT NOT NULL DEFAULT 0
);

MERGE INTO ROLE (id, name) KEY (id) VALUES (1, 'USER'), (2, 'ADMIN'), (3, 'SUPERADMIN');
