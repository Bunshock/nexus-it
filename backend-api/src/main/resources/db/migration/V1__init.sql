-- Generador de Notas IT — middleware v2 (GLPI adapter) schema.
--
-- M1 (GLPI-adapter strip): rewritten directly rather than layered as a new migration on top of
-- the old v1 (no-adapter) shape (decision D-2) — nothing was ever deployed against the old
-- version of this file, so there is no existing installation to migrate forward and no need to
-- carry the v1-era ALTER TABLE/backfill blocks that used to live here. If this schema is ever
-- deployed and later needs to change, that's when a real Vn__ migration (not another V1 rewrite)
-- should start being used.
--
-- Removed entirely vs. the old (v1, no-adapter) shape: MODEL_STOCK (+ approval-time stock
-- movement, STOCK_WOULD_GO_NEGATIVE), SN_VALIDATION, NOTE_ITEM_STOCK_EXCEPTION (+ NOTE_ITEM's
-- modifies_stock column), SEDE_SHIPPING_INFO, NOTE_REMITO_SEDE/NOTE_REMITO_OTHER (collapsed into
-- one NOTE_REMITO with a single destination_sede_id — see below), NOTE_REPORT.stock_applied. Local
-- catalog CRUD (add/rename/remove Type/Brand/Model) also went away at the application layer, but
-- TYPE/BRAND/BRAND_TYPE_LINK/MODEL/PROVIDER/SEDE themselves are UNCHANGED here — CatalogController
-- still reads them directly until M3 rewires it onto the (currently-stubbed) GlpiCatalogAdapter.
--
-- Added: NOTE_ITEM.external_item_id (M4's job to populate — the eventual GLPI asset id a note
-- item resolves to), NOTE_ITEM_RETURN_ALLOCATION.external_item_id (same idea, per allocated unit —
-- M5b's write-queue worker records which GLPI Peripheral row(s) it picked here).

BEGIN TRANSACTION;

-- ============================================================================
-- Local catalog mirror — read-only from the app as of M1 (see CatalogController/CatalogRepository).
-- Kept as real tables (not dropped) so NOTE_ITEM can still FK-reference a type/brand/model and the
-- browse endpoints keep working before M3 lands; a straight read-only port of the desktop app's
-- own schema otherwise.
-- ============================================================================

CREATE TABLE TYPE (
    id              INT IDENTITY(1,1) PRIMARY KEY,
    name            NVARCHAR(255) NOT NULL UNIQUE,
    is_asset        INT NOT NULL DEFAULT 0,
    requires_serial INT NOT NULL DEFAULT 0,
    deprecated      INT NOT NULL DEFAULT 0
);

CREATE TABLE BRAND (
    id         INT IDENTITY(1,1) PRIMARY KEY,
    name       NVARCHAR(255) NOT NULL UNIQUE,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE BRAND_TYPE_LINK (
    id       INT IDENTITY(1,1) PRIMARY KEY,
    type_id  INT NOT NULL REFERENCES TYPE(id),
    brand_id INT NOT NULL REFERENCES BRAND(id),
    CONSTRAINT uq_brand_type_link UNIQUE(type_id, brand_id)
);

-- brand_type_id is nullable — NULL is reserved for the single global "Genérico / Otro" model,
-- not scoped to any particular brand+type link.
CREATE TABLE MODEL (
    id            INT IDENTITY(1,1) PRIMARY KEY,
    brand_type_id INT NULL REFERENCES BRAND_TYPE_LINK(id),
    name          NVARCHAR(255) NOT NULL,
    deprecated    INT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_model_brand_type_name ON MODEL(brand_type_id, name);
-- idx_model_brand_type_name above never protects the global generic row (brand_type_id IS
-- NULL) — every SQL engine treats NULL as never-equal-to-NULL, even in a unique index. These
-- two indexes key off name/deprecated instead, which hold real comparable values: one stops an
-- accidental duplicate global-generic name, the other guarantees at most one ACTIVE global row.
CREATE UNIQUE INDEX idx_model_global_generic_name ON MODEL(name) WHERE brand_type_id IS NULL;
CREATE UNIQUE INDEX idx_model_single_active_generic ON MODEL(deprecated) WHERE brand_type_id IS NULL AND deprecated = 0;

CREATE TABLE PROVIDER (
    id         INT IDENTITY(1,1) PRIMARY KEY,
    name       NVARCHAR(255) NOT NULL UNIQUE,
    deprecated INT NOT NULL DEFAULT 0
);

CREATE TABLE SEDE (
    id         INT IDENTITY(1,1) PRIMARY KEY,
    name       NVARCHAR(255) NOT NULL UNIQUE,
    deprecated INT NOT NULL DEFAULT 0
);

-- ============================================================================
-- Roles / permissions / accounts
-- ============================================================================

-- ROLE is a plain lookup table for the 3 fixed role names — APP_USER.role_id and
-- ROLE_PERMISSION.role_id both reference it, instead of each independently duplicating the same
-- CHECK (role IN (...)) constraint with nothing enforcing the two stay in sync.
CREATE TABLE ROLE (
    id   INT IDENTITY(1,1) PRIMARY KEY,
    name NVARCHAR(20) NOT NULL UNIQUE
);
INSERT INTO ROLE (name) VALUES ('USER'), ('ADMIN'), ('SUPERADMIN');

-- Login-time role/Sede lookup — not the same thing as AD group membership, which gates app
-- access at all and is checked against the AD API at login time, not stored here.
-- glpi_token_encrypted: each technician's own GLPI user_token, AES-encrypted (F1) — set via
-- PUT /api/v1/me/glpi-token, read by the adapter's GlpiUserTokenResolver.
-- bypass_group_check: lets a specific account skip the AD-group login gate without needing an
-- AD group of its own (e.g. intern technicians) — superadmin-set via direct SQL only.
CREATE TABLE APP_USER (
    id                     INT IDENTITY(1,1) PRIMARY KEY,
    username               NVARCHAR(100) NOT NULL UNIQUE,
    role_id                INT NOT NULL REFERENCES ROLE(id),
    sede_id                INT REFERENCES SEDE(id),
    bypass_group_check     INT NOT NULL DEFAULT 0,
    glpi_token_encrypted   NVARCHAR(500) NULL
);

-- Deny-by-default permission grants: a Permission is denied unless a matching row exists here.
-- Edited directly via SQL by a superadmin, same "no in-app CRUD" precedent as APP_USER itself.
CREATE TABLE ROLE_PERMISSION (
    id         INT IDENTITY(1,1) PRIMARY KEY,
    role_id    INT NOT NULL REFERENCES ROLE(id),
    permission NVARCHAR(50) NOT NULL,
    CONSTRAINT uq_role_permission UNIQUE (role_id, permission)
);

-- ============================================================================
-- Audit trail — append-only. AUDIT_ADMIN_ACTION is deliberately generic (target_id NVARCHAR, not
-- a typed FK) since it covers heterogeneous admin actions with no single shared FK target;
-- AUDIT_ITEM_STATUS stays typed since it has one clear FK target. old_value/new_value must NEVER
-- hold an actual secret value — only that a change happened; enforced by the caller, not this
-- schema. AUDIT_STOCK is left in place from the old (no-adapter) schema even though nothing
-- writes to it any more post-strip (no local stock counter left to audit) — flagged as a real
-- open question in the M1 handoff report rather than dropped unilaterally.
-- ============================================================================

CREATE TABLE AUDIT_LOGIN (
    id             INT IDENTITY(1,1) PRIMARY KEY,
    username       NVARCHAR(100) NOT NULL,
    success        INT NOT NULL,
    failure_reason NVARCHAR(255),
    attempted_at   DATETIME2 NOT NULL
);

CREATE TABLE AUDIT_STOCK (
    id            INT IDENTITY(1,1) PRIMARY KEY,
    brand_type_id INT NOT NULL REFERENCES BRAND_TYPE_LINK(id),
    model_id      INT NOT NULL REFERENCES MODEL(id),
    sede_id       INT NOT NULL REFERENCES SEDE(id),
    username      NVARCHAR(100) NOT NULL,
    old_stock     INT NOT NULL,
    new_stock     INT NOT NULL,
    reason        NVARCHAR(500) NOT NULL,
    changed_at    DATETIME2 NOT NULL
);

CREATE TABLE AUDIT_ADMIN_ACTION (
    id           INT IDENTITY(1,1) PRIMARY KEY,
    username     NVARCHAR(100) NOT NULL,
    action       NVARCHAR(100) NOT NULL,
    target_type  NVARCHAR(100) NOT NULL,
    target_id    NVARCHAR(255),
    old_value    NVARCHAR(1000),
    new_value    NVARCHAR(1000),
    reason       NVARCHAR(500),
    performed_at DATETIME2 NOT NULL
);

-- ============================================================================
-- Notes
-- ============================================================================

CREATE TABLE NOTE_REPORT (
    id              INT IDENTITY(1,1) PRIMARY KEY,
    created_at      DATETIME2 NOT NULL,
    profile_type    NVARCHAR(255) NOT NULL,
    technician_name NVARCHAR(255),
    technician_dni  NVARCHAR(255),
    observations    NVARCHAR(300),
    sede_id         INT REFERENCES SEDE(id),
    approval_status NVARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

-- Row exists only for a note an admin has actually rejected — not a NULL sentinel on every
-- NOTE_REPORT row that's PENDING/APPROVED.
CREATE TABLE NOTE_REPORT_REJECTION (
    note_report_id   INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
    rejection_reason NVARCHAR(300) NOT NULL
);

CREATE TABLE NOTE_ENTREGA_DEVOLUCION (
    note_report_id INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
    user_name      NVARCHAR(255),
    user_dni       NVARCHAR(255),
    user_email     NVARCHAR(255),
    motivo         NVARCHAR(100)
);

-- Row exists only for a Devolución note whose Motivo triggered the Falla popup.
CREATE TABLE NOTE_DEVOLUCION_FALLA (
    note_report_id  INT PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
    failure_cause   NVARCHAR(100) NOT NULL,
    failure_details NVARCHAR(200)
);

-- Row exists only when a Préstamo note actually captured an Área/Evento (itself optional).
CREATE TABLE NOTE_PRESTAMO_AREA_EVENTO (
    note_report_id INT PRIMARY KEY REFERENCES NOTE_ENTREGA_DEVOLUCION(note_report_id),
    area_evento    NVARCHAR(200) NOT NULL
);

CREATE TABLE NOTE_PROVEEDOR (
    note_report_id   INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
    provider_id      INT NOT NULL REFERENCES PROVIDER(id),
    cuit             NVARCHAR(255),
    motivo           NVARCHAR(100),
    responsible_name NVARCHAR(255),
    responsible_dni  NVARCHAR(255)
);

-- M1: collapsed from the old NOTE_REMITO_SEDE/NOTE_REMITO_OTHER split (a catalog-Sede destination
-- vs. a free-text custom one, the latter backed by SEDE_SHIPPING_INFO for address/recipients) down
-- to a single mandatory destination_sede_id — a custom/manual destination with no catalog row is
-- no longer supported; address/recipient info for a Remito destination is expected to come from
-- GLPI's Location entity once M3 lands, not a local shipping-info table.
CREATE TABLE NOTE_REMITO (
    note_report_id     INT PRIMARY KEY REFERENCES NOTE_REPORT(id),
    destination_sede_id INT NOT NULL REFERENCES SEDE(id)
);

-- Slim base table — asset-only/countable-only/GLPI-tracking/return-tracking fields each live in
-- their own subtype table below. external_item_id (M1, added for M4 to populate): the eventual
-- GLPI asset id this item resolves to, once the item dialog becomes a GLPI asset picker instead of
-- free-text entry against the local catalog — NULL until M4 wires it up.
CREATE TABLE NOTE_ITEM (
    id               INT IDENTITY(1,1) PRIMARY KEY,
    note_id          INT NOT NULL REFERENCES NOTE_REPORT(id),
    type_id          INT NOT NULL REFERENCES TYPE(id),
    brand_id         INT NOT NULL REFERENCES BRAND(id),
    model_id         INT NOT NULL REFERENCES MODEL(id),
    observations     NVARCHAR(200),
    external_item_id NVARCHAR(255) NULL
);
CREATE INDEX idx_note_item_external_item_id ON NOTE_ITEM(external_item_id);

CREATE TABLE NOTE_ITEM_ASSET (
    item_id       INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
    serial_number NVARCHAR(255),
    a_f           NVARCHAR(255)
);

CREATE TABLE NOTE_ITEM_COUNTABLE (
    item_id  INT PRIMARY KEY REFERENCES NOTE_ITEM(id),
    quantity INT NOT NULL DEFAULT 1
);

-- No 'N_A' value/default here — a row simply doesn't exist for a tracking dimension that doesn't
-- apply to an item, instead of always existing with a sentinel value. One table for all 3
-- tracking dimensions (GLPI sync-out, Préstamo/Provider return, GLPI sync-back after a return) —
-- byte-identical in shape, differing only in which dimension a row belongs to, so tracking_type is
-- the discriminator. UNIQUE(item_id, tracking_type) is what used to be each dimension's own
-- item_id PK — an item can now legitimately hold up to 3 rows at once.
CREATE TABLE NOTE_ITEM_STATUS_TRACKING (
    id                INT IDENTITY(1,1) PRIMARY KEY,
    item_id           INT NOT NULL REFERENCES NOTE_ITEM(id),
    tracking_type     NVARCHAR(20) NOT NULL CHECK (tracking_type IN ('GLPI', 'RETURN', 'GLPI_RETURN')),
    status            NVARCHAR(50) NOT NULL,
    rejection_reason  NVARCHAR(300),
    status_updated_at DATETIME2,
    CONSTRAINT uq_note_item_status_tracking UNIQUE (item_id, tracking_type)
);

-- Covers both GLPI and Préstamo/Provider return-status transitions in one table via a
-- status_kind discriminator — NOTE_ITEM_STATUS_TRACKING only ever keeps the latest status per
-- dimension, never prior transitions.
CREATE TABLE AUDIT_ITEM_STATUS (
    id          INT IDENTITY(1,1) PRIMARY KEY,
    item_id     INT NOT NULL REFERENCES NOTE_ITEM(id),
    status_kind NVARCHAR(20) NOT NULL CHECK (status_kind IN ('GLPI', 'RETURN')),
    old_status  NVARCHAR(50) NOT NULL,
    new_status  NVARCHAR(50) NOT NULL,
    reason      NVARCHAR(500),
    quantity    INT NOT NULL DEFAULT 1,
    username    NVARCHAR(100) NOT NULL,
    changed_at  DATETIME2 NOT NULL
);

-- Countable items can be resolved in partial batches over time (e.g. 5 loaned headsets: 3
-- returned now, 1 lost later, 1 still pending) — a single status column can't express that, so
-- each partial action gets its own append-only row here instead. PENDING is never stored — the
-- remaining pending quantity is always NOTE_ITEM_COUNTABLE.quantity minus the sum of allocations
-- for that item. external_item_id (M1, added for M5b to populate): which specific GLPI Peripheral
-- row this allocation's units resolved to when the write-queue worker actually picks them, once
-- that exists — NULL until then.
CREATE TABLE NOTE_ITEM_RETURN_ALLOCATION (
    id               INT IDENTITY(1,1) PRIMARY KEY,
    item_id          INT NOT NULL REFERENCES NOTE_ITEM(id),
    status           NVARCHAR(50) NOT NULL,
    quantity         INT NOT NULL,
    reason           NVARCHAR(300),
    updated_at       DATETIME2 NOT NULL,
    external_item_id NVARCHAR(255) NULL
);

COMMIT TRANSACTION;
