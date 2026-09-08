-- H2-native test schema for RolePermissionRepositoryTest — same "dialect-neutral stand-in"
-- precedent as the other *-repository-test-schema.sql files. IF NOT EXISTS matters for the
-- same reason documented there (this @Sql script re-runs before every test method against
-- one persistent embedded H2 instance).

CREATE TABLE IF NOT EXISTS ROLE (
    id   INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS ROLE_PERMISSION (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    role_id    INT NOT NULL REFERENCES ROLE(id),
    permission VARCHAR(50) NOT NULL,
    CONSTRAINT uq_role_permission UNIQUE (role_id, permission)
);

MERGE INTO ROLE (id, name) KEY (id) VALUES (1, 'USER'), (2, 'ADMIN'), (3, 'SUPERADMIN');
