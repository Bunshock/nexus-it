-- dev-seed.sql — minimal working dataset for the `dev` Spring profile ONLY.
--
-- Run by spring.sql.init AFTER dev-schema.sql. Just enough to log in (POST /auth/dev-login) and
-- exercise the catalog + note-creation flows from the Phase B desktop client without a real
-- Keycloak, SQL Server, or org data.
--
-- Every statement is idempotent (MERGE / guarded INSERT) so a devtools restart re-runs harmlessly.
-- Catalog rows (TYPE/BRAND/MODEL/...) are inserted by name lookup with no explicit id, so the H2
-- identity sequences stay clean for the rows the API creates afterward. ROLE/SEDE/APP_USER have no
-- CRUD endpoint in v1, so their ids are pinned.

-- ── roles ────────────────────────────────────────────────────────────────────
MERGE INTO ROLE (id, name) KEY (id) VALUES (1, 'USER'), (2, 'ADMIN'), (3, 'SUPERADMIN');

-- ── sedes ────────────────────────────────────────────────────────────────────
MERGE INTO SEDE (id, name) KEY (id) VALUES (1, 'Casa Central'), (2, 'Sucursal Norte');

-- ── users ────────────────────────────────────────────────────────────────────
-- Pass one of these usernames to POST /api/v1/auth/dev-login.
MERGE INTO APP_USER (id, username, role_id, sede_id, bypass_group_check) KEY (id) VALUES
    (1, 'dev.superadmin', 3, 1, 0),
    (2, 'dev.admin',      2, 1, 0),
    (3, 'dev.user',       1, 1, 0);

-- ── permission grants ────────────────────────────────────────────────────────
-- SUPERADMIN: every Permission. ADMIN: every Permission except EDIT_SMTP_CONFIG (SUPERADMIN-only,
-- matching the desktop app's seedDefaultRolePermissions precedent). USER: none.
DELETE FROM ROLE_PERMISSION;
INSERT INTO ROLE_PERMISSION (role_id, permission) VALUES
    (3, 'MANAGE_TYPES'), (3, 'MANAGE_BRANDS'), (3, 'MANAGE_MODELS'), (3, 'MANAGE_STOCK'),
    (3, 'EDIT_SN_VALIDATION'), (3, 'EDIT_SMTP_CONFIG'), (3, 'EDIT_GLPI_CONFIG'), (3, 'EDIT_AD_CONFIG'),
    (3, 'EDIT_AF_FORMAT_CONFIG'), (3, 'APPROVE_NOTES'), (3, 'SYNC_GLPI'), (3, 'VALIDATE_RETURNS'),
    (2, 'MANAGE_TYPES'), (2, 'MANAGE_BRANDS'), (2, 'MANAGE_MODELS'), (2, 'MANAGE_STOCK'),
    (2, 'EDIT_SN_VALIDATION'), (2, 'EDIT_GLPI_CONFIG'), (2, 'EDIT_AD_CONFIG'),
    (2, 'EDIT_AF_FORMAT_CONFIG'), (2, 'APPROVE_NOTES'), (2, 'SYNC_GLPI'), (2, 'VALIDATE_RETURNS');

-- ── config (mirrors V2__config.sql's shipped defaults) ───────────────────────
MERGE INTO APP_CONFIG (id, note_item_limit) KEY (id) VALUES (1, 8);

DELETE FROM APP_CONFIG_MOTIVO_OPTION;
INSERT INTO APP_CONFIG_MOTIVO_OPTION (category, motivo, sort_order) VALUES
    ('entrega', 'Nuevo ingreso', 0),
    ('entrega', 'Recambio - Falla', 1),
    ('entrega', 'Recambio - Obsolescencia', 2),
    ('entrega', 'Asignación inicial', 3),
    ('entrega', 'Reparación', 4),
    ('entrega', 'Otro', 5),
    ('finDeContrato', 'Fin de contrato', 0),
    ('finDeContrato', 'Otro', 1),
    ('proveedor', 'Garantía', 0),
    ('proveedor', 'Reparación', 1),
    ('proveedor', 'Devolución de préstamo', 2),
    ('proveedor', 'Otro', 3),
    ('devolucion', 'Baja / Desvinculación', 0),
    ('devolucion', 'Falla', 1),
    ('devolucion', 'Obsolescencia', 2),
    ('devolucion', 'Otro', 3);

DELETE FROM APP_CONFIG_FALLA_OPTION;
INSERT INTO APP_CONFIG_FALLA_OPTION (option_value, sort_order) VALUES
    ('Pantalla rota', 0),
    ('No enciende', 1),
    ('Daño por líquido', 2),
    ('Batería agotada / no carga', 3),
    ('Falla de software / sistema operativo', 4),
    ('Componente faltante o dañado', 5),
    ('Otro', 6);

DELETE FROM APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO;
INSERT INTO APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO (motivo) VALUES ('Garantía'), ('Reparación');

-- ── catalog ──────────────────────────────────────────────────────────────────
MERGE INTO TYPE (name, is_asset, requires_serial) KEY (name) VALUES
    ('NOTEBOOK', 1, 1),
    ('MONITOR', 1, 0),
    ('MOUSE', 0, 0);

MERGE INTO BRAND (name) KEY (name) VALUES ('DELL'), ('LOGITECH'), ('Genérico / Otro');

MERGE INTO PROVIDER (name) KEY (name) VALUES ('Proveedor Demo');

-- Brand/Type links
INSERT INTO BRAND_TYPE_LINK (type_id, brand_id)
    SELECT t.id, b.id FROM TYPE t JOIN BRAND b ON b.name = 'DELL' WHERE t.name = 'NOTEBOOK'
    AND NOT EXISTS (SELECT 1 FROM BRAND_TYPE_LINK l WHERE l.type_id = t.id AND l.brand_id = b.id);
INSERT INTO BRAND_TYPE_LINK (type_id, brand_id)
    SELECT t.id, b.id FROM TYPE t JOIN BRAND b ON b.name = 'DELL' WHERE t.name = 'MONITOR'
    AND NOT EXISTS (SELECT 1 FROM BRAND_TYPE_LINK l WHERE l.type_id = t.id AND l.brand_id = b.id);
INSERT INTO BRAND_TYPE_LINK (type_id, brand_id)
    SELECT t.id, b.id FROM TYPE t JOIN BRAND b ON b.name = 'LOGITECH' WHERE t.name = 'MOUSE'
    AND NOT EXISTS (SELECT 1 FROM BRAND_TYPE_LINK l WHERE l.type_id = t.id AND l.brand_id = b.id);

-- Scoped models
INSERT INTO MODEL (brand_type_id, name)
    SELECT l.id, 'Latitude 5420' FROM BRAND_TYPE_LINK l
    JOIN TYPE t ON t.id = l.type_id JOIN BRAND b ON b.id = l.brand_id
    WHERE t.name = 'NOTEBOOK' AND b.name = 'DELL'
    AND NOT EXISTS (SELECT 1 FROM MODEL m WHERE m.brand_type_id = l.id AND m.name = 'Latitude 5420');
INSERT INTO MODEL (brand_type_id, name)
    SELECT l.id, 'P2419H' FROM BRAND_TYPE_LINK l
    JOIN TYPE t ON t.id = l.type_id JOIN BRAND b ON b.id = l.brand_id
    WHERE t.name = 'MONITOR' AND b.name = 'DELL'
    AND NOT EXISTS (SELECT 1 FROM MODEL m WHERE m.brand_type_id = l.id AND m.name = 'P2419H');
INSERT INTO MODEL (brand_type_id, name)
    SELECT l.id, 'M90' FROM BRAND_TYPE_LINK l
    JOIN TYPE t ON t.id = l.type_id JOIN BRAND b ON b.id = l.brand_id
    WHERE t.name = 'MOUSE' AND b.name = 'LOGITECH'
    AND NOT EXISTS (SELECT 1 FROM MODEL m WHERE m.brand_type_id = l.id AND m.name = 'M90');

-- Global "Genérico / Otro" model (brand_type_id NULL — one row, offered for every brand+type)
INSERT INTO MODEL (brand_type_id, name)
    SELECT NULL, 'Genérico / Otro' FROM (SELECT 1) x
    WHERE NOT EXISTS (SELECT 1 FROM MODEL WHERE brand_type_id IS NULL AND name = 'Genérico / Otro');

-- Stock at Casa Central (sede 1)
INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock)
    SELECT m.brand_type_id, m.id, 1, 15 FROM MODEL m
    JOIN BRAND_TYPE_LINK l ON l.id = m.brand_type_id
    JOIN TYPE t ON t.id = l.type_id JOIN BRAND b ON b.id = l.brand_id
    WHERE t.name = 'NOTEBOOK' AND b.name = 'DELL' AND m.name = 'Latitude 5420'
    AND NOT EXISTS (SELECT 1 FROM MODEL_STOCK s WHERE s.model_id = m.id AND s.sede_id = 1);
INSERT INTO MODEL_STOCK (brand_type_id, model_id, sede_id, stock)
    SELECT m.brand_type_id, m.id, 1, 40 FROM MODEL m
    JOIN BRAND_TYPE_LINK l ON l.id = m.brand_type_id
    JOIN TYPE t ON t.id = l.type_id JOIN BRAND b ON b.id = l.brand_id
    WHERE t.name = 'MOUSE' AND b.name = 'LOGITECH' AND m.name = 'M90'
    AND NOT EXISTS (SELECT 1 FROM MODEL_STOCK s WHERE s.model_id = m.id AND s.sede_id = 1);

-- Shipping info for Casa Central (needed for Remito notes with a catalog-Sede destination)
INSERT INTO SEDE_SHIPPING_INFO (sede_id, destination_label, address, recipients)
    SELECT 1, 'Casa Central - Depósito IT', 'Av. Siempre Viva 123, CABA', 'Mesa de Ayuda IT'
    FROM (SELECT 1) x
    WHERE NOT EXISTS (SELECT 1 FROM SEDE_SHIPPING_INFO WHERE sede_id = 1 AND deprecated = 0);
