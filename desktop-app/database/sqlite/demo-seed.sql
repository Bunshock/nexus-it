-- Demo/starter data for the local SQLite database (data/noteapp.db).
--
-- This used to run automatically on every fresh database via
-- DatabaseService.seedEquipmentData()/seedHistoryData() — removed 2026-07-14 per explicit user
-- direction so a freshly created (or deleted-and-recreated) local database starts genuinely
-- empty, with just the schema, ready to load real organizational data without any pre-existing
-- rows mixed in. This script preserves that same data (unchanged) for optional, on-demand use —
-- e.g. before a demo, or as a realistic example dataset.
--
-- Run against an existing data/noteapp.db (the schema must already exist — start the app once,
-- or run this after the app has created data/noteapp.db on its own):
--   sqlite3 data/noteapp.db < database/sqlite/demo-seed.sql
--
-- Safe to re-run: every insert is either OR IGNORE (equipment catalog) or guarded by an
-- existing-row check (history notes) below, so running this twice does not duplicate rows.

-- ── Equipment catalog (132 curated type/brand/model combinations) ──────────────────────────

CREATE TEMP TABLE _seed_rows (
    type_name  TEXT NOT NULL,
    is_asset   INTEGER NOT NULL,
    brand_name TEXT NOT NULL,
    model_name TEXT
);

INSERT INTO _seed_rows (type_name, is_asset, brand_name, model_name) VALUES
('ADAPTADOR WIFI',        0, 'TP-LINK',            'AX1800'),
('ALL-IN-ONE',            1, 'DELL',               'OPTIPLEX 9030'),
('ARO DE LUZ',            0, 'DX260',              'DX260'),
('BARRA DE SONIDO',       1, 'LENOVO',             'THINKSMART BAR (L10TSS2M)'),
('BRAZO ROBOT',           1, 'ARDUINO',            'SPLR002'),
('CAMARA',                1, 'LOGITECH',           'C270'),
('CAMARA',                1, 'POLYCOM',            'POLY P009'),
('CARGADOR NOTEBOOK',     0, 'LENOVO',             '65W'),
('CARGADOR NOTEBOOK',     0, 'LENOVO',             'USB C'),
('CELULAR',               1, 'MOTOROLA',           'E4 PLUS'),
('CELULAR',               1, 'MOTOROLA',           'E6 PLAY'),
('CELULAR',               1, 'MOTOROLA',           'G6 PLUS'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A03'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A04'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A06'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A11'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A13'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A14'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A20s'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A24'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A31'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A52s 5G'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A53 5G'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A54'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY A54 5G'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY M12'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY S21'),
('CELULAR',               1, 'SAMSUNG',            'GALAXY S25 FE'),
('FINDER',                1, 'ARDUINO',            'OPTA 8A.04'),
('FINDER',                1, 'ARDUINO',            'OPTA EXT A0602'),
('GABINETE',              0, 'ASUS',               'C/FUENTE'),
('HEADSET',               0, 'GENIUS',             'HS-230U'),
('HEADSET',               0, 'LOGITECH',           'H390'),
('HEADSET',               0, 'LOGITECH',           'M170'),
('HEADSET',               0, 'TRUST',              'AYDA'),
('HEADSET',               0, 'TRUST',              'AYDA (JACK 3.5)'),
('HEADSET',               0, 'TRUST',              'CARUS GXT493'),
('LINEA CORPORATIVA',     0, 'PERSONAL',           NULL),
('MICROFONO',             0, 'K9',                 NULL),
('MICROFONO CORBATERO',   0, 'LAMBO TECH',         'K8'),
('MINI PC',               1, 'ACER',               'VN90G SERIES'),
('MINI PC',               1, 'LENOVO',             'NEO 50Q GEN 4'),
('MINI PC',               1, 'LENOVO',             'NEO 55Q GEN 6'),
('MODEM',                 1, 'HUAWEI',             'B310s'),
('MONITOR',               1, 'ACER',               'V227Q'),
('MONITOR',               1, 'BANGHO',             'EM2131W'),
('MONITOR',               1, 'BANGHO',             'LED 22"'),
('MONITOR',               1, 'LG',                 '23EA53V'),
('MONITOR',               1, 'NETSYS',             'L24W9-K.AR'),
('MONITOR',               1, 'SAMSUNG',            '22"'),
('MONITOR',               1, 'SAMSUNG',            'E19'),
('MONITOR',               1, 'SAMSUNG',            'ESSENTIAL MONITOR'),
('MONITOR',               1, 'SAMSUNG',            'F220T350FHL'),
('MONITOR',               1, 'SAMSUNG',            'LS22D300FYCZB'),
('MONITOR',               1, 'SAMSUNG',            'LS22F350FHLXZB'),
('MONITOR',               1, 'SAMSUNG',            'S19B150N'),
('MONITOR',               1, 'SAMSUNG',            'S22F350FH'),
('MONITOR',               1, 'SAMSUNG',            'S22F350FHL (22")'),
('MOUSE',                 0, 'ACER',               'MOANUOA'),
('MOUSE',                 0, 'DELL',               NULL),
('MOUSE',                 0, 'GENIUS',             'DX-120'),
('MOUSE',                 0, 'GENIUS',             'DX120'),
('MOUSE',                 0, 'GENIUS',             'NX-7000'),
('MOUSE',                 0, 'GENIUS',             'NX-7000SE'),
('MOUSE',                 0, 'GENIUS',             'NX-7123'),
('MOUSE',                 0, 'LENOVO',             'AB1AS3Z'),
('MOUSE',                 0, 'LENOVO',             'EMS-537A'),
('MOUSE',                 0, 'LOGITECH',           'M170'),
('NOTEBOOK',              1, 'ASUS',               'AORUS 15P RX5L'),
('NOTEBOOK',              1, 'LENOVO',             '14s-IML'),
('NOTEBOOK',              1, 'LENOVO',             '14s-IWL'),
('NOTEBOOK',              1, 'LENOVO',             'B50-80'),
('NOTEBOOK',              1, 'LENOVO',             'E14 GEN 2'),
('NOTEBOOK',              1, 'LENOVO',             'E14 GEN 3'),
('NOTEBOOK',              1, 'LENOVO',             'E14 GEN 4'),
('NOTEBOOK',              1, 'LENOVO',             'E14 GEN 5'),
('NOTEBOOK',              1, 'LENOVO',             'E14 GEN 6'),
('NOTEBOOK',              1, 'LENOVO',             'E14 GEN 7'),
('NOTEBOOK',              1, 'LENOVO',             'E16 GEN 1'),
('NOTEBOOK',              1, 'LENOVO',             'E16 GEN 2'),
('NOTEBOOK',              1, 'LENOVO',             'L14 GEN 1'),
('NOTEBOOK',              1, 'LENOVO',             'L15 GEN 1'),
('NOTEBOOK',              1, 'LENOVO',             'L15 GEN 4'),
('NOTEBOOK',              1, 'LENOVO',             'LEGION 5 15ACH6H'),
('NOTEBOOK',              1, 'LENOVO',             'LEGION 5 16IRX9'),
('NOTEBOOK',              1, 'LENOVO',             'LEGION PRO 5 16ADR10'),
('NOTEBOOK',              1, 'LENOVO',             'LEGION SLIM 5 16ARP9'),
('NOTEBOOK',              1, 'LENOVO',             'LOQ 15IAX9E'),
('NOTEBOOK',              1, 'LENOVO',             'THINKBOOK 16 G6 ABP'),
('NOTEBOOK',              1, 'LENOVO',             'THINKBOOK 16 G7 ARP'),
('NOTEBOOK',              1, 'LENOVO',             'V15 G4 IRU'),
('NOTEBOOK',              1, 'LENOVO',             'V310-15ISK'),
('NOTEBOOK',              1, 'LENOVO',             'V330-14IKB'),
('NOTEBOOK',              1, 'LENOVO',             'V330-15IKB'),
('NOTEBOOK',              1, 'LENOVO',             'X1 CARBON'),
('NOTEBOOK',              1, 'LENOVO',             'X1 YOGA'),
('NOTEBOOK',              1, 'MSI',                'KATANA'),
('PALO DE SELFIE',        0, 'H1S',                '3556'),
('PANTALLA INTERACTIVA',  1, 'LEGAMASTER',         'ETX-6500 (65")'),
('PANTALLA INTERACTIVA',  1, 'LEGAMASTER',         'ETX-6510UHD'),
('PC',                    1, 'APPLE',              'iMAC (EMC: 3442)'),
('PC',                    1, 'APPLE',              'iMac (EMC: 2833)'),
('PC',                    1, 'APPLE',              'iMac (EMC: 3069)'),
('PC',                    1, 'APPLE',              'iMac (EMC: 3195)'),
('PC',                    1, 'DELL',               'OPTIPLEX 3050'),
('PC',                    1, 'INTEL',              'NUC 7i5BNH'),
('PC',                    1, 'INTEL',              'NUC i5'),
('PC',                    1, 'INTEL',              'NUC7i5BNH'),
('PC',                    1, 'INTEL',              'NUC7i5BNHL'),
('PENDRIVE',              0, 'SANDISK',            '32 GB'),
('PROYECTOR',             1, 'EPSON',              'H854A'),
('RAM',                   0, 'ADATA',              '16GB DDR5 5600MHZ'),
('RAM',                   0, 'ADATA',              'DDR4'),
('RAM',                   0, 'CRUCIAL',            'DDR4'),
('RECEPTOR WIFI',         0, 'TP-LINK',            'AX1800'),
('ROBOT KIT',             1, 'ARDUINO',            'ARVIK'),
('SSD',                   0, 'GIGABYTE',           'NVMe M2 256GB'),
('TABLET',                1, 'LENOVO',             'TAB M8'),
('TABLET',                1, 'REDMI',              'REDMI PAD PRO'),
('TABLET',                1, 'XIAOMI',             'REDMI PAD PRO'),
('TARJETA DE MEMORIA',    0, 'SANDISK',            'EXTREME PRO 128GB RIA'),
('TECLADO',               0, 'ACER',               'PR1101V'),
('TONER',                 0, 'BROTHER',            '3479N'),
('TONER',                 0, 'KATUN PERFORMANCE',  'TN-221K (NEGRO)'),
('TONER',                 0, 'KATUN PERFORMANCE',  'TN-221Y (AMARILLO)'),
('TONER',                 0, 'RICOH SAVIN LANIER', 'IM C300 (NEGRO)'),
('TV',                    1, 'MOTOROLA',           'ANDROID TV (43")'),
('TV',                    1, 'MOTOROLA',           'MT5000'),
('TV',                    1, 'SAMSUNG',            '43"'),
('UPS',                   1, 'TRV',                'NEO 850'),
('VR',                    1, 'META',               'QUEST 3'),
('WEBCAM',                0, 'VIDLOK',             'W77');

INSERT OR IGNORE INTO TYPE (name, is_asset, requires_serial)
SELECT DISTINCT type_name, is_asset, CASE WHEN UPPER(type_name) = 'NOTEBOOK' THEN 1 ELSE 0 END
FROM _seed_rows;

INSERT OR IGNORE INTO BRAND (name)
SELECT DISTINCT brand_name FROM _seed_rows;

INSERT OR IGNORE INTO BRAND_TYPE_LINK (type_id, brand_id)
SELECT DISTINCT
    (SELECT id FROM TYPE  WHERE name = r.type_name),
    (SELECT id FROM BRAND WHERE name = r.brand_name)
FROM _seed_rows r;

INSERT OR IGNORE INTO MODEL (brand_type_id, name)
SELECT DISTINCT
    (SELECT btl.id FROM BRAND_TYPE_LINK btl
       JOIN TYPE  t ON t.id = btl.type_id  AND t.name = r.type_name
       JOIN BRAND b ON b.id = btl.brand_id AND b.name = r.brand_name),
    r.model_name
FROM _seed_rows r
WHERE r.model_name IS NOT NULL;

DROP TABLE _seed_rows;

-- ── Demo history notes (8 fictional notes, illustrating every GLPI status and note type) ───
-- Guarded as a whole: skip entirely if NOTE_REPORT already has rows, so re-running this script
-- (or running it against a database that already has real notes) never mixes in fake ones.
--
-- The "should we seed at all" check is captured once, up front, into a temp flag rather than
-- re-checked per note — checking it per note by testing whether note 1's specific timestamp
-- exists is NOT equivalent: if NOTE_REPORT already had an unrelated real row before this script
-- ran, note 1 correctly gets skipped, but notes 2-8 would then find that note 1's own timestamp
-- genuinely doesn't exist yet and incorrectly insert anyway. A single flag computed before any
-- insert happens avoids that entirely.
CREATE TEMP TABLE _should_seed_history AS
SELECT (SELECT COUNT(*) FROM NOTE_REPORT) = 0 AS should_seed;

INSERT INTO NOTE_REPORT (created_at, profile_type)
SELECT '2026-06-10T09:15:00', 'Entrega'
WHERE (SELECT should_seed FROM _should_seed_history);

INSERT INTO NOTE_ENTREGA_DEVOLUCION (note_report_id, user_name, user_dni, user_email, motivo)
SELECT id, 'María López', '28471923', 'mlopez@ues21.edu.ar', 'Incorporación'
FROM NOTE_REPORT WHERE created_at = '2026-06-10T09:15:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ENTREGA_DEVOLUCION WHERE note_report_id = NOTE_REPORT.id);

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'NOTEBOOK', 'LENOVO', 'E14 GEN 5', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-10T09:15:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ITEM WHERE note_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
SELECT last_insert_rowid(), 'R9XK2048', '0001' WHERE changes() = 1;
INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
SELECT last_insert_rowid(), 'PENDING', NULL, NULL WHERE changes() = 1;

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'MOUSE', 'GENIUS', 'NX-7000', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-10T09:15:00';
INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity)
SELECT last_insert_rowid(), 1 WHERE changes() = 1;

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'HEADSET', 'TRUST', 'AYDA', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-10T09:15:00';
INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity)
SELECT last_insert_rowid(), 1 WHERE changes() = 1;

INSERT INTO NOTE_REPORT (created_at, profile_type)
SELECT '2026-06-12T14:30:00', 'Devolución'
WHERE (SELECT should_seed FROM _should_seed_history);
INSERT INTO NOTE_ENTREGA_DEVOLUCION (note_report_id, user_name, user_dni, user_email, motivo)
SELECT id, 'Juan Pérez', '35102847', 'jperez@ues21.edu.ar', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-12T14:30:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ENTREGA_DEVOLUCION WHERE note_report_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'NOTEBOOK', 'LENOVO', 'X1 CARBON', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-12T14:30:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ITEM WHERE note_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
SELECT last_insert_rowid(), 'PF3G9012', '0084' WHERE changes() = 1;
INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
SELECT last_insert_rowid(), 'SYNCED', NULL, '2026-06-13T10:00:00' WHERE changes() = 1;

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'MONITOR', 'SAMSUNG', 'S22F350FHL (22")', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-12T14:30:00';
INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
SELECT last_insert_rowid(), 'M22FE001', '0201' WHERE changes() = 1;
INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
SELECT last_insert_rowid(), 'SYNCED', NULL, '2026-06-13T10:00:00' WHERE changes() = 1;

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'MOUSE', 'LENOVO', 'AB1AS3Z', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-12T14:30:00';
INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity)
SELECT last_insert_rowid(), 1 WHERE changes() = 1;

INSERT INTO NOTE_REPORT (created_at, profile_type)
SELECT '2026-06-18T11:00:00', 'Fin de Contrato'
WHERE (SELECT should_seed FROM _should_seed_history);
INSERT INTO NOTE_ENTREGA_DEVOLUCION (note_report_id, user_name, user_dni, user_email, motivo)
SELECT id, 'Carlos Gómez', '20384756', 'cgomez@ues21.edu.ar', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-18T11:00:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ENTREGA_DEVOLUCION WHERE note_report_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'NOTEBOOK', 'LENOVO', 'V330-15IKB', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-18T11:00:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ITEM WHERE note_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
SELECT last_insert_rowid(), 'MP4R1199', '0037' WHERE changes() = 1;
INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
SELECT last_insert_rowid(), 'SYNCED', NULL, '2026-06-19T08:30:00' WHERE changes() = 1;

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'CELULAR', 'SAMSUNG', 'GALAXY A54', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-18T11:00:00';
INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
SELECT last_insert_rowid(), 'RF8N4400', '0112' WHERE changes() = 1;
INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
SELECT last_insert_rowid(), 'REJECTED', 'Número de serie inválido en GLPI', '2026-06-19T08:35:00' WHERE changes() = 1;

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'HEADSET', 'LOGITECH', 'H390', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-18T11:00:00';
INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity)
SELECT last_insert_rowid(), 1 WHERE changes() = 1;

INSERT INTO NOTE_REPORT (created_at, profile_type)
SELECT '2026-06-20T10:00:00', 'Entrega - Proveedor'
WHERE (SELECT should_seed FROM _should_seed_history);
INSERT INTO NOTE_PROVEEDOR (note_report_id, provider_name, cuit, motivo)
SELECT id, 'TechCorp S.A.', '30-71234567-8', 'Garantía'
FROM NOTE_REPORT WHERE created_at = '2026-06-20T10:00:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_PROVEEDOR WHERE note_report_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'NOTEBOOK', 'LENOVO', 'E14 GEN 6', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-20T10:00:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ITEM WHERE note_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
SELECT last_insert_rowid(), 'PF4A0011', '0210' WHERE changes() = 1;
INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
SELECT last_insert_rowid(), 'PENDING', NULL, NULL WHERE changes() = 1;

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'NOTEBOOK', 'LENOVO', 'E14 GEN 6', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-20T10:00:00';
INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
SELECT last_insert_rowid(), 'PF4A0012', '0211' WHERE changes() = 1;
INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
SELECT last_insert_rowid(), 'PENDING', NULL, NULL WHERE changes() = 1;

INSERT INTO NOTE_REPORT (created_at, profile_type)
SELECT '2026-06-22T16:45:00', 'Entrega'
WHERE (SELECT should_seed FROM _should_seed_history);
INSERT INTO NOTE_ENTREGA_DEVOLUCION (note_report_id, user_name, user_dni, user_email, motivo)
SELECT id, 'Ana García', '41829374', 'agarcia@ues21.edu.ar', 'Incorporación'
FROM NOTE_REPORT WHERE created_at = '2026-06-22T16:45:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ENTREGA_DEVOLUCION WHERE note_report_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'CELULAR', 'SAMSUNG', 'GALAXY A13', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-22T16:45:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ITEM WHERE note_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
SELECT last_insert_rowid(), 'RZ9K3301', '0155' WHERE changes() = 1;
INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
SELECT last_insert_rowid(), 'REJECTED', 'Activo ya registrado en GLPI con otro usuario', '2026-06-23T09:00:00' WHERE changes() = 1;

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'CELULAR', 'SAMSUNG', 'GALAXY A14', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-22T16:45:00';
INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
SELECT last_insert_rowid(), 'RZ9K4402', '0156' WHERE changes() = 1;
INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
SELECT last_insert_rowid(), 'REJECTED', 'Activo ya registrado en GLPI con otro usuario', '2026-06-23T09:00:00' WHERE changes() = 1;

INSERT INTO NOTE_REPORT (created_at, profile_type)
SELECT '2026-06-25T09:30:00', 'Devolución'
WHERE (SELECT should_seed FROM _should_seed_history);
INSERT INTO NOTE_ENTREGA_DEVOLUCION (note_report_id, user_name, user_dni, user_email, motivo)
SELECT id, 'Pedro Silva', '29384756', 'psilva@ues21.edu.ar', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-25T09:30:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ENTREGA_DEVOLUCION WHERE note_report_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'HEADSET', 'TRUST', 'CARUS GXT493', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-25T09:30:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ITEM WHERE note_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity)
SELECT last_insert_rowid(), 2 WHERE changes() = 1;

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'MOUSE', 'GENIUS', 'DX-120', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-25T09:30:00';
INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity)
SELECT last_insert_rowid(), 1 WHERE changes() = 1;

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'CABLE HDMI', 'Genérico', '1.5 MTS', NULL
FROM NOTE_REPORT WHERE created_at = '2026-06-25T09:30:00';
INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity)
SELECT last_insert_rowid(), 1 WHERE changes() = 1;

INSERT INTO NOTE_REPORT (created_at, profile_type)
SELECT '2026-06-28T13:00:00', 'Préstamo'
WHERE (SELECT should_seed FROM _should_seed_history);
INSERT INTO NOTE_ENTREGA_DEVOLUCION (note_report_id, user_name, user_dni, user_email, motivo)
SELECT id, 'Lucía Torres', '38201934', 'ltorres@ues21.edu.ar', 'Capacitación'
FROM NOTE_REPORT WHERE created_at = '2026-06-28T13:00:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ENTREGA_DEVOLUCION WHERE note_report_id = NOTE_REPORT.id);
-- Préstamo note: this item's GLPI status is normally PENDING for any other note type, but a
-- Préstamo asset is deliberately excluded from GLPI sync (see CLAUDE.md's "Préstamo assets are
-- deliberately excluded from GLPI sync") — so this one has no NOTE_ITEM_GLPI_TRACKING row, and
-- instead gets a NOTE_ITEM_RETURN_TRACKING row (return_status = PENDING), matching what the real
-- app writes for every item on a Préstamo note. The original pre-normalization version of this
-- seed file never set return_status at all (a latent gap, fixed here while touching this file).
INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'TABLET', 'LENOVO', 'TAB M8', 'Uso temporal sala de capacitación'
FROM NOTE_REPORT WHERE created_at = '2026-06-28T13:00:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ITEM WHERE note_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
SELECT last_insert_rowid(), 'TP3A0011', '0099' WHERE changes() = 1;
INSERT INTO NOTE_ITEM_RETURN_TRACKING (item_id, status, rejection_reason, status_updated_at)
SELECT last_insert_rowid(), 'PENDING', NULL, NULL WHERE changes() = 1;

INSERT INTO NOTE_REPORT (created_at, profile_type)
SELECT '2026-07-01T08:00:00', 'Entrega - Proveedor'
WHERE (SELECT should_seed FROM _should_seed_history);
INSERT INTO NOTE_PROVEEDOR (note_report_id, provider_name, cuit, motivo)
SELECT id, 'Distribuidora IT Sur', '20-98765432-1', 'Reposición'
FROM NOTE_REPORT WHERE created_at = '2026-07-01T08:00:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_PROVEEDOR WHERE note_report_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'MONITOR', 'SAMSUNG', 'ESSENTIAL MONITOR', NULL
FROM NOTE_REPORT WHERE created_at = '2026-07-01T08:00:00'
  AND NOT EXISTS (SELECT 1 FROM NOTE_ITEM WHERE note_id = NOTE_REPORT.id);
INSERT INTO NOTE_ITEM_ASSET (item_id, serial_number, a_f)
SELECT last_insert_rowid(), 'LSEM2200', '0312' WHERE changes() = 1;
INSERT INTO NOTE_ITEM_GLPI_TRACKING (item_id, status, rejection_reason, status_updated_at)
SELECT last_insert_rowid(), 'SYNCED', NULL, '2026-07-01T12:00:00' WHERE changes() = 1;

INSERT INTO NOTE_ITEM (note_id, type_name, brand_name, model_name, observations)
SELECT id, 'TECLADO', 'ACER', 'PR1101V', NULL
FROM NOTE_REPORT WHERE created_at = '2026-07-01T08:00:00';
INSERT INTO NOTE_ITEM_COUNTABLE (item_id, quantity)
SELECT last_insert_rowid(), 2 WHERE changes() = 1;

DROP TABLE _should_seed_history;
