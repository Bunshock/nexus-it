# Implemented Endpoints — v2 (GLPI adapter)

Living document, updated alongside the code as each endpoint is actually built and tested — not
the aspirational design. `backend-contract.md` is the full GLPI-shaped target contract;
`MIDDLEWARE.md` is the execution plan. This file tracks what's **actually running** on
`backend-api-v2-glpi-adapter` today, including every place it still deliberately differs from
either of those (mostly: real GLPI adapter work hasn't started yet — M3/M5 in the plan).

**Rewritten wholesale 2026-09-22** — the previous version of this file described
`backend-api-v1-no-adapter`'s shape (full catalog CRUD, stock counter, S/N validation, the old
12-value `Permission` enum) verbatim, unchanged since v2 was forked off v1. That's not "37 stale
references," it's the wrong branch's document. M1 (the strip) already removed everything that
version described as CRUD; this rewrite reflects what's actually in the code as of `a30a5ae`.

Status legend: ✅ built + tested · 🚧 designed, not built yet · — not in v2 scope for now.

**Live, interactive docs**: `springdoc-openapi` is wired in (`GET /v3/api-docs`,
`GET /swagger-ui/index.html` — "Authorize" accepts a `sessionToken` as a Bearer token). Both
`permitAll` in `SecurityConfig` for now (dev convenience). Generated straight from the live
controllers, so always in sync with request/response shapes — but carries none of the "why."

All endpoints below require `Authorization: Bearer <sessionToken>` (`401` otherwise) unless noted
unauthenticated. Every non-2xx response uses the envelope: `{"error": {"code", "message", "details"}}`.

---

## Auth (`/api/v1/auth`, `/api/v1/me`) — ✅ all built

| Method & path | Auth | Status |
|---|---|---|
| `GET /api/v1/auth/config` | none | ✅ |
| `POST /api/v1/auth/login` | IdP bearer token | ✅ |
| `POST /api/v1/auth/dev-login` `{username}` | none (dev profile only) | ✅ (M0) |
| `POST /api/v1/auth/logout` | session | ✅ |
| `GET /api/v1/me` | session | ✅ |
| `PUT /api/v1/me/glpi-token` `{token}` | session | ✅ |
| `GET /api/v1/health` | none | ✅ |

- `GET /auth/config` → `503 IDP_NOT_CONFIGURED` if `middleware.idp.issuer-uri` is blank. Otherwise
  `{issuer, clientId, scopes, authBackendDisplayName}`.
- `POST /auth/login` — body: none, `Authorization: Bearer <IdP access token>`. Validates the JWT,
  checks `APP_USER` registration (`403 USER_NOT_REGISTERED`), the allowed-group claim unless
  `bypassGroupCheck` (`403 NOT_IN_ALLOWED_GROUP`), takes a best-effort AD identity snapshot
  (directory outage never blocks login — degrades to username + no DNI), mints an opaque session,
  writes `AUDIT_LOGIN`. Returns `LoginResponse{sessionToken, expiresAt, username, role, sedeId,
  sedeName, fullName, dni, permissions[]}`.
- **`POST /auth/dev-login`** (new, M0) — `@Profile("dev")` only, physically absent from a
  `sqlserver`/prod build. Mints a session for an already-seeded `APP_USER` with no Keycloak in the
  loop; response is byte-identical to real login. Same `USER_NOT_REGISTERED` check, no group/IdP
  checks at all (there's no token to check a group claim on).
- `POST /auth/logout` — invalidates the session token server-side.
- `GET /me` — `MeResponse{username, role, sedeId, sedeName, fullName, dni, permissions[],
  registered, bypassGroupCheck}`.
  - **Known gap, not yet fixed (M6)**: no `glpiTokenSet` boolean — a client can't tell whether a
    technician has already set their GLPI token without a failed write attempt.
- `PUT /me/glpi-token` — write-only, AES-encrypted into `APP_USER.glpi_token_encrypted`, never
  returned by `GET /me`. **Known gap, not yet fixed (M6)**: returns `200` (default), spec wants `204`.
- `GET /health` → `{"status": "UP"}`, always unauthenticated.

**Session model**: opaque, server-side, in-memory (`SessionStore`), sliding/absolute cap per
`middleware.session.*`. **Never run against a live Keycloak as of this writing** — validated only
via `dev-login` and unit tests.

---

## RBAC — ✅ enforcement built, no dedicated read endpoint

`PermissionGuard.require()`/`requireSedeScoped()` enforce every check server-side. No
`GET /roles/{role}/permissions` endpoint — a client reads `permissions[]` off the login/`/me`
response instead.

**`Permission` enum — trimmed to 4 values (was 12 pre-strip):** `APPROVE_NOTES`,
`SYNC_EXTERNAL` (renamed from `SYNC_GLPI`), `VALIDATE_RETURNS`, `CREATE_ASSETS` (new, ungated by
any endpoint yet — reserved for the not-yet-built Alta de equipos / M7). Deny-by-default: absence
of a `ROLE_PERMISSION` row is the denial, no separate flag.

**Sede-fence**: a plain `ADMIN` is scoped to their own `APP_USER.sede_id` on every Sede-scoped
action; `SUPERADMIN` bypasses it. An unset Sede — caller or target — never matches (fail-safe).
**`APP_USER.sede_id` is now a `String`** (external GLPI Location id, or a placeholder pending M3 —
see the Sede-linking note under Notes below); `PermissionGuard.requireSedeScoped` bridges this
against `NOTE_REPORT.sede_id` (still a local int) via a string-comparison, marked `TODO(M3)`.

---

## Catalog (`/api/v1/catalog`) — ✅ read-only

| Method & path | Auth |
|---|---|
| `GET /api/v1/catalog/types` | session only |
| `GET /api/v1/catalog/brands?typeId=` | session only |
| `GET /api/v1/catalog/models?typeId=&brandId=` | session only |
| `GET /api/v1/catalog/providers` | session only |
| `GET /api/v1/catalog/sedes` | session only |

**Everything else is gone (M1, the strip)**: all Type/Brand/Model add/rename/remove/
requires-serial endpoints, `MODEL_STOCK` read/write, S/N Validation (`sn-validations`,
`{modelId}/sn-validation`), Sede shipping-info endpoints, `SEDE_SHIPPING_INFO`/
`NOTE_REMITO_OTHER`. GLPI is the catalog's source of truth going forward — there is nothing left
for an admin to curate through this app.

- **Still backed by the local tables, not GLPI, for now** — `CatalogRepository` still runs plain
  `SELECT`s against `TYPE`/`BRAND`/`BRAND_TYPE_LINK`/`MODEL`/`PROVIDER`/`SEDE`. M3
  ("rewire `CatalogController` to the ports") is what replaces this with real `GlpiCatalogAdapter`
  reads — not done yet. Until then, these 5 endpoints return whatever was already in the local
  catalog tables (same data v1 had), not live GLPI data.
- The global "Genérico / Otro" Model (`brandTypeId: null`) is still UNION'd into every
  Type+Brand combination server-side — unaffected by the strip, since it's a read-path behavior.
- `Permission.CREATE_ASSETS` exists but nothing calls it yet — no `POST /catalog/assets`
  (Alta de equipos, M7/X9) has been built.

---

## Directory (`/api/v1/directory`) — ✅ built, unaffected by M1

| Method & path | Auth |
|---|---|
| `GET /api/v1/directory/users?name=&dni=&username=` | session only |

Unchanged by the strip — replaces the desktop app's `IADService.search()`. AND-combined criteria,
at least one required (`400 MISSING_SEARCH_CRITERIA`). No match → `200 {results: []}`. Response:
`{username, fullName, email, dni, ou}`. `middleware.directory.base-url`/`.token` blank →
`503 DIRECTORY_NOT_CONFIGURED`; unreachable/non-200 → `502 DIRECTORY_UNAVAILABLE`.

---

## Notes / History / Approval + Item Sync/Return (`/api/v1/notes`) — ✅ built, still local-catalog-backed

| Method & path | Permission |
|---|---|
| `POST /api/v1/notes` | session only |
| `GET /api/v1/notes?profileTypes=&approvalStatuses=&sedes=&authorSearch=&recipientSearch=&dateFrom=&dateTo=&itemTypes=&itemBrands=&itemModels=&syncStatuses=&returnStatuses=` | session only |
| `GET /api/v1/notes/{id}` | session only |
| `GET /api/v1/notes/items/{itemId}/note-id` | session only |
| `PUT /api/v1/notes/{id}/approve` | `APPROVE_NOTES`, Sede-scoped |
| `PUT /api/v1/notes/{id}/reject` `{reason}` | `APPROVE_NOTES`, Sede-scoped |
| `POST /api/v1/notes/{noteId}/items/{itemId}/sync` | `SYNC_EXTERNAL`, Sede-scoped |
| `POST /api/v1/notes/{noteId}/items/{itemId}/reject-sync` `{reason}` | `SYNC_EXTERNAL`, Sede-scoped |
| `PUT /api/v1/notes/{noteId}/items/{itemId}/return` `{quantity?}` | `VALIDATE_RETURNS`, Sede-scoped |
| `PUT /api/v1/notes/{noteId}/items/{itemId}/lost` `{reason, quantity?}` | `VALIDATE_RETURNS`, Sede-scoped |

### Create — `POST /notes`
Server resolves the creating technician's Sede from their own session (`caller.sedeId()`), not a
client-supplied field — `400 SEDE_NOT_ASSIGNED` if unset.

- **`NOTE_ITEM` still stores local catalog FKs** (`type_id`/`brand_id`/`model_id`,
  `NOTE_PROVEEDOR.provider_id`) — a deliberate M1 deviation from the original strip instruction
  (removing them now would have broken note creation with no GLPI-backed replacement ready). M1
  additively added `NOTE_ITEM.external_item_id` (and `NOTE_ITEM_RETURN_ALLOCATION.external_item_id`)
  for M4 to populate later; the old FKs are still what's actually used today.
- **Sede id bridge (`TODO(M3)`)**: `caller.sedeId()` is now a `String` (external/GLPI-Location
  space), but `NOTE_REPORT.sede_id` is still a local `SEDE(id)` int. `NotesController.localSedeId()`
  parses it back to an int and fails loud with `409 SEDE_NOT_LOCAL` if a real, non-numeric GLPI
  Location id is ever assigned before M3 lands — not a silent truncation.
- GLPI-sync eligibility per item is computed server-side: every non-Préstamo `ASSET` gets
  `glpiStatus: PENDING`; Préstamo assets and every `COUNTABLE` get `N_A`. Return-tracking is
  `PENDING` for every item on a Préstamo note, or a Provider note whose `motivo` is in the
  returnable list (`APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO` — config-driven, not hardcoded).
- Response: `201 {id, approvalStatus: "PENDING", createdAt}`.

**Remito de Envío**: M1 collapsed the payload to a single `destinationSedeId` (int, local Sede)
— the old `shippingInfoId`/`destinationLabel`/`destinationAddress`/`destinationRecipients`
free-text trio and the `SEDE_SHIPPING_INFO`/`NOTE_REMITO_OTHER` split are gone. A custom
(non-catalog) Remito destination is no longer representable at all until the GLPI-Location-backed
version of this is rebuilt.

### List / Get
Filter set unchanged by M1: `itemTypes`/`itemBrands`/`itemModels` (AND-ed per matching item,
matched by catalog name regardless of `deprecated`), `syncStatuses`/`returnStatuses`
(per-note aggregate, filtered in Java after the query, any-of).

### Approve / Reject
**No stock movement of any kind** — M1 removed `MODEL_STOCK`, the approval-time
decrement/increment, `NOTE_REPORT.stock_applied`, and `409 STOCK_WOULD_GO_NEGATIVE` entirely.
`approve`/`reject` are now a plain `NOTE_REPORT.approval_status` flip, nothing else. (The old
`backend-api-v1-no-adapter` branch's stock-movement behavior — described in an earlier version of
this file — does not apply here; that was v1-only and was never carried into v2.)

### Item sync / return
**Still direct, synchronous status flips — `200`, not `202`/`QUEUED`.** This is *not* stale — M5
(the real external write queue) hasn't been built yet, so this remains today's actual behavior,
matching the plan's own "core work" ordering (M5 comes after M2–M4). No external write, no D6
holder-mismatch pre-check yet either.

- `sync` → `glpiStatus: SYNCED`. `reject-sync` (mandatory `reason`) → `REJECTED`.
- `return`/`lost` on an asset: whole-item flip, `quantity` ignored if sent. On a countable:
  `quantity` required (`400 QUANTITY_REQUIRED`), partial-batch allocation via
  `NOTE_ITEM_RETURN_ALLOCATION` (`409` if it overshoots what's pending).
- **No stock credit-back on return** (M1 removed it along with the rest of `MODEL_STOCK` — the
  `modifiesStock`/`modifiesStockReason` fields on an item still exist and are still stored, but
  nothing reads them for a stock effect anymore since there's no stock counter left to credit).

**Audit**: `sync`/`reject-sync`/`return`/`lost` all still write `AUDIT_ITEM_STATUS`
(`recordItemStatusChange`) — unaffected by the strip. **`AUDIT_STOCK` writes are gone** — the one
call site that used to produce them (`recordStockChange`, from approval-time stock movement) was
removed along with `MODEL_STOCK` itself; the `AUDIT_STOCK` table is still in the schema but
nothing writes to it anymore.

---

## Config (`/api/v1/config`) — ✅ built, reworked by M1

| Method & path | Permission |
|---|---|
| `GET /api/v1/config` | session only |
| `PUT /api/v1/config` `{...}` | flat SUPERADMIN (not a `Permission` — a role check) |

- **`PUT` is now a single flat SUPERADMIN gate**, not the old per-field-group
  (`EDIT_AF_FORMAT_CONFIG`/`EDIT_SMTP_CONFIG`) split — those two permissions don't exist in the
  4-value enum anymore. One `ApiException.forbidden` if the caller isn't SUPERADMIN; otherwise the
  whole document is written in one call.
- **SMTP is gone entirely** — not just write-only-and-encrypted like before, the 4 SMTP columns
  and all SMTP logic were dropped from `APP_CONFIG` in M1. There is no SMTP config surface left in
  this API at all.
- `genericBrandId`/`genericModelId` in the `GET`/`PUT` response are still resolved live from the
  local catalog (`CatalogRepository.findGenericBrandId()`/`findGlobalGenericModelId()`) — unchanged
  by the strip, since the generic-id concept is local-table-backed either way for now.
- `genericLabel` and `returnableMotivosProveedor` are still config-driven (`APP_CONFIG.generic_label`,
  `APP_CONFIG_RETURNABLE_PROVIDER_MOTIVO`), read live by `CatalogRepository`/`NotesRepository`.
- Every real `PUT /config` call now writes exactly one `AUDIT_ADMIN_ACTION` row
  (`EDIT_GENERAL_CONFIG`) unconditionally — not diffed field-by-field, and there's no separate
  SMTP audit action anymore (nothing left to audit there).

---

## Audit (`/api/v1/audit/*`) — ✅ built, one write point removed by M1

| Method & path | Permission |
|---|---|
| `GET /api/v1/audit/login?username=&page=&size=` | SUPERADMIN only |
| `GET /api/v1/audit/item-status?noteId=&itemId=&page=&size=` | SUPERADMIN only |
| `GET /api/v1/audit/admin-actions?actor=&targetType=&page=&size=` | SUPERADMIN only |

Reads unaffected by M1. Write points:

- `recordLogin` — from `AuthController`/`DevAuthController`, every real login outcome.
- `recordItemStatusChange` — from `NotesRepository`'s sync/reject-sync/return/lost/
  allocateCountableReturn methods (3 call sites). Unaffected by the strip.
- `recordAdminAction` — now only **one** call site left (`ConfigRepository.updateConfig`,
  `EDIT_GENERAL_CONFIG`). The 11 catalog-CRUD call sites (`ADD_TYPE`/`RENAME_TYPE`/etc.) and the
  `EDIT_SMTP_CONFIG` call site are all gone along with the endpoints/columns that produced them.
- **`recordStockChange` no longer has any caller at all** — its one call site
  (approval-time stock movement) was removed with `MODEL_STOCK`. The method and the `AUDIT_STOCK`
  table both still exist in the code/schema (not dropped — may matter again for M8 reconciliation
  later) but nothing writes to `AUDIT_STOCK` today.
- **Not Sede-scoped** — any SUPERADMIN reads every Sede's rows (moot now since only SUPERADMIN can
  read audit at all — see the note below).
- Paging: `OFFSET ... FETCH NEXT ... ROWS ONLY`, default 50, hard-clamped to 200.

**Known stale comment, not fixed here (docs-only task, out of scope for a code change)**:
`AuditController.java`'s own Javadoc still says "No dedicated `VIEW_AUDIT` permission exists in
the ported 12-value `Permission` enum" — the enum is 4 values now, not 12. The actual gating logic
(flat SUPERADMIN check) is correct and unaffected; only the comment's parenthetical is stale.

---

## Cross-cutting notes (unaffected by M1, still accurate)

- **JdbcTemplate, not JPA** — every repository is a plain `@Repository` wrapping `JdbcTemplate`.
  Multi-step writes use `@Transactional` on the repository method.
- **Error codes are UPPER_SNAKE_CASE, thrown as `ApiException`**, caught by
  `GlobalExceptionHandler` into the standard envelope.
- **`status_updated_at`/`updated_at`-style columns are plain ISO-8601 strings**, not JDBC
  `Timestamp` — `NOTE_REPORT.created_at` is the one exception (real JDBC `Timestamp`).
- **Every new repository needs its own H2-native test schema file** — `V1__init.sql` is real
  SQL-Server-only T-SQL and cannot run against H2 test slices.
- **`dev-schema.sql`/`dev-seed.sql` (M0) are regenerated against the post-strip schema** — do not
  copy the pre-strip v1 versions of these files verbatim if referencing them for anything.
