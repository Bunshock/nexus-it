-- Minimal stand-in schema so POST /api/v1/auth/login can run on the `dev` (H2) profile without
-- the full SQL Server Flyway migration (V1__init.sql is SQL-Server-only T-SQL). Only the tables
-- the login path actually touches:
--     AppUserRepository           -> APP_USER JOIN ROLE   (reads username, role name, sede_id, bypass_group_check)
--     RolePermissionRepository    -> ROLE_PERMISSION JOIN ROLE
--     AuditRepository.recordLogin -> INSERT INTO AUDIT_LOGIN (username, success, failure_reason, attempted_at)
--
-- The `dev` datasource runs H2 in MODE=MSSQLServer, which rejects AUTO_INCREMENT. None of these
-- surrogate keys are read by the login path, so they're just plain columns here (ROLE.id is
-- supplied explicitly by the seed). Same "dialect-neutral minimal stand-in" spirit as
-- src/test/resources/*-repository-test-schema.sql. sede_id is a plain nullable INT (no SEDE
-- table / FK) — note creation is not exercised by this harness. Loaded via spring.sql.init;
-- see local-keycloak/README.md.

CREATE TABLE IF NOT EXISTS ROLE (
    id   INT PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS APP_USER (
    username           VARCHAR(100) NOT NULL PRIMARY KEY,
    role_id            INT NOT NULL REFERENCES ROLE(id),
    sede_id            INT,
    bypass_group_check INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ROLE_PERMISSION (
    role_id    INT NOT NULL REFERENCES ROLE(id),
    permission VARCHAR(50) NOT NULL,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission)
);

CREATE TABLE IF NOT EXISTS AUDIT_LOGIN (
    username       VARCHAR(100) NOT NULL,
    success        INT NOT NULL,
    failure_reason VARCHAR(255),
    attempted_at   TIMESTAMP NOT NULL
);
