-- APP_USER rows that pair with the Keycloak realm users in realm/siglo21-realm.json:
--     emanuel.arias -> registered, ADMIN, in nexus-it-users  -> login succeeds
--     no.role       -> deliberately NOT here                  -> 403 USER_NOT_REGISTERED
--     outsider      -> registered, USER, NOT in the group     -> 403 NOT_IN_ALLOWED_GROUP (only when the group gate is on)
--
-- spring.sql.init re-runs this on every boot; every statement is `INSERT ... WHERE NOT EXISTS`
-- so it's idempotent and mode-agnostic (H2's MERGE isn't available under MODE=MSSQLServer).

INSERT INTO ROLE (id, name) SELECT 1, 'USER'       WHERE NOT EXISTS (SELECT 1 FROM ROLE WHERE id = 1);
INSERT INTO ROLE (id, name) SELECT 2, 'ADMIN'      WHERE NOT EXISTS (SELECT 1 FROM ROLE WHERE id = 2);
INSERT INTO ROLE (id, name) SELECT 3, 'SUPERADMIN' WHERE NOT EXISTS (SELECT 1 FROM ROLE WHERE id = 3);

INSERT INTO APP_USER (username, role_id, sede_id, bypass_group_check)
    SELECT 'emanuel.arias', 2, NULL, 0
    WHERE NOT EXISTS (SELECT 1 FROM APP_USER WHERE username = 'emanuel.arias');
INSERT INTO APP_USER (username, role_id, sede_id, bypass_group_check)
    SELECT 'outsider', 1, NULL, 0
    WHERE NOT EXISTS (SELECT 1 FROM APP_USER WHERE username = 'outsider');

-- Representative ADMIN grants so the login response's `permissions[]` comes back visibly populated.
INSERT INTO ROLE_PERMISSION (role_id, permission)
    SELECT 2, 'APPROVE_NOTES'    WHERE NOT EXISTS (SELECT 1 FROM ROLE_PERMISSION WHERE role_id = 2 AND permission = 'APPROVE_NOTES');
INSERT INTO ROLE_PERMISSION (role_id, permission)
    SELECT 2, 'SYNC_GLPI'        WHERE NOT EXISTS (SELECT 1 FROM ROLE_PERMISSION WHERE role_id = 2 AND permission = 'SYNC_GLPI');
INSERT INTO ROLE_PERMISSION (role_id, permission)
    SELECT 2, 'VALIDATE_RETURNS' WHERE NOT EXISTS (SELECT 1 FROM ROLE_PERMISSION WHERE role_id = 2 AND permission = 'VALIDATE_RETURNS');
INSERT INTO ROLE_PERMISSION (role_id, permission)
    SELECT 2, 'MANAGE_MODELS'    WHERE NOT EXISTS (SELECT 1 FROM ROLE_PERMISSION WHERE role_id = 2 AND permission = 'MANAGE_MODELS');
INSERT INTO ROLE_PERMISSION (role_id, permission)
    SELECT 2, 'MANAGE_STOCK'     WHERE NOT EXISTS (SELECT 1 FROM ROLE_PERMISSION WHERE role_id = 2 AND permission = 'MANAGE_STOCK');
