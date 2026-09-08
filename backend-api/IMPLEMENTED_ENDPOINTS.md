# Implemented Endpoints — v1 (no GLPI adapter)

Living document, updated alongside the code as each endpoint is actually built and tested — not
the aspirational design. `backend-contract.md` is the full, GLPI-shaped target contract (Tier 1/2,
resolved 2026-08-31); this file tracks what's **actually running** in the `backend-api-v1-no-adapter`
build today, including every place it deliberately deviates from that contract text. See
`backend-contract.md` §1.1/§3.4 and the pivot note in `[[project_glpi_middleware_redesign]]`
(Claude's memory) for why a no-adapter v1 is a legitimate branch of the design, not a fork of it.

Status legend: ✅ built + tested · 🚧 designed, not built yet · — not in v1 scope (see "Deferred").

**Live, interactive docs**: `springdoc-openapi` is wired in (`GET /v3/api-docs` for the raw spec,
`GET /swagger-ui/index.html` for the UI — "Authorize" accepts a `sessionToken` from
`POST /auth/login` as a Bearer token). Both are `permitAll` in `SecurityConfig` for now (dev
convenience — revisit before a real deployment if the API shape shouldn't be public). This is
generated straight from the live controllers, so it's always in sync with the code and shows real
request/response schemas — but it carries none of the "why": permission requirements read fine
there too (`@SecurityRequirement`), but deviations from `backend-contract.md`, what's deferred vs.
not started, and the reasoning behind either only live in this file. Keep both.

All endpoints below are unauthenticated only where noted; everything else requires
`Authorization: Bearer <sessionToken>` (§2.2) and returns `401` otherwise. Every non-2xx response
uses the §11 envelope: `{"error": {"code", "message", "details"}}`.

---

## Auth (`/api/v1/auth`, `/api/v1/me`) — ✅ all built

| Method & path | Auth | Permission | Status |
|---|---|---|---|
| `GET /api/v1/auth/config` | none | — | ✅ |
| `POST /api/v1/auth/login` | IdP bearer token | — | ✅ |
| `POST /api/v1/auth/logout` | session | — | ✅ |
| `GET /api/v1/me` | session | — | ✅ |
| `GET /api/v1/health` | none | — | ✅ |

- `GET /auth/config` → `503 IDP_NOT_CONFIGURED` if `middleware.idp.issuer-uri` is blank (real
  case today — no Keycloak reachable yet in dev). Otherwise `{issuer, clientId, scopes,
  authBackendDisplayName}`.
- `POST /auth/login` — body: none, `Authorization: Bearer <IdP access token>`. Validates via
  `JwtDecoders.fromIssuerLocation`, checks `APP_USER` registration (`403 USER_NOT_REGISTERED`) and
  the allowed-group claim unless `bypass_group_check` (`403 NOT_IN_ALLOWED_GROUP`), **resolves the
  technician's directory profile** (see next bullet), mints an opaque session token, writes
  `AUDIT_LOGIN`. Returns `{sessionToken, expiresAt, role, sedeId, displayName, permissions[]}`.
  - **Directory profile snapshot** — after the registration/group gates pass, `login` calls
    `DirectoryService.findExactByUsername(username)` and stamps the resolved `fullName` +
    `dni` onto the session (`SessionStore`/`CallerPrincipal` now carry `displayName`/`dni`).
    `displayName` in the response is that real name. **Best-effort**: a directory outage
    (`503 DIRECTORY_NOT_CONFIGURED` / `502 DIRECTORY_UNAVAILABLE`) is caught — login still
    succeeds, `displayName` degrades to the username and `dni` to `null`. **Exact match only**
    — a substring username hit is never treated as the caller's own account (same guard as the
    desktop app's `containsExactUsernameMatch`). Mirrors the desktop `TechnicianSessionService`
    resolving identity once at session start, not per action.
  - The user-editable "Nombre para mostrar" greeting *preference* (a separate, per-user stored
    override on top of the AD name) is still not ported — a small follow-up, needs its own
    table + endpoint.
- `POST /auth/logout` — invalidates the session token server-side.
- `GET /me` — re-fetchable identity/permission snapshot: `{username, role, sedeId, displayName,
  permissions[], registered, bypassGroupCheck}`. `displayName` is the session's
  directory-resolved name (no fresh directory call).
- `GET /health` → `{"status": "UP"}`, always unauthenticated.

**Session model**: opaque, server-side, in-memory (`SessionStore`) — sliding ~2h idle / ~12h
absolute cap (`middleware.session.*`), matches §2.4 exactly.

---

## RBAC (`/api/v1/roles/{role}/permissions`) — ✅ built

`PermissionGuard.require()`/`requireSedeScoped()` enforce every permission check server-side
(H1–H3 all covered by `PermissionGuardTest`). **`GET /api/v1/roles/{role}/permissions`**
(`RolesController`, session-only) returns a role's granted permission names, sorted — the app
uses it to render the UI gate for a role other than the caller's own (the caller's own set is
already on the login / `/me` response). Read-only; `ROLE_PERMISSION` is edited by direct SQL.
An unknown role or one with no grants → `[]` (a valid deny-by-default state, not `404`). A
stray permission string from hand-edited SQL is silently dropped. `RolePermissionRepositoryTest`
gives `getPermissionsForRole` its first direct DB coverage (was only mocked before).

**Permission enum — the OLD, full 12-value set, not the contract's trimmed 3-permission enum**:
`MANAGE_TYPES`, `MANAGE_BRANDS`, `MANAGE_MODELS`, `MANAGE_STOCK`, `EDIT_SN_VALIDATION`,
`EDIT_SMTP_CONFIG`, `EDIT_GLPI_CONFIG`, `EDIT_AD_CONFIG`, `EDIT_AF_FORMAT_CONFIG`,
`APPROVE_NOTES`, `SYNC_GLPI`, `VALIDATE_RETURNS`. Restored on purpose — v1 has no external adapter,
so the catalog-CRUD/config permissions the full contract's D3 trim assumed were obsolete are
needed again.

Sede-fence: a plain `ADMIN` is scoped to their own `APP_USER.sede_id` on every sede-scoped action
(`requireSedeScoped`); `SUPERADMIN` bypasses it. An unset Sede — on either the caller or the
target — never matches (fail-safe, not fail-open).

---

## Catalog (`/api/v1/catalog`) — ✅ browse, stock, full Type/Brand/Model CRUD, and S/N Validation

| Method & path | Permission |
|---|---|
| `GET /api/v1/catalog/types` | session only |
| `GET /api/v1/catalog/brands?typeId=` | session only |
| `GET /api/v1/catalog/models?typeId=&brandId=` | session only |
| `GET /api/v1/catalog/models/{modelId}/stock?brandId=&typeId=&sedeId=` | session only |
| `PUT /api/v1/catalog/models/{modelId}/stock?brandId=&typeId=&sedeId=` `{stock}` | `MANAGE_STOCK`, Sede-scoped |
| `POST /api/v1/catalog/types` `{name, isAsset}` | `MANAGE_TYPES` |
| `PUT /api/v1/catalog/types/{id}` `{name}` | `MANAGE_TYPES` |
| `PUT /api/v1/catalog/types/{id}/requires-serial` `{requiresSerial}` | `MANAGE_TYPES` |
| `DELETE /api/v1/catalog/types/{id}` | `MANAGE_TYPES` |
| `POST /api/v1/catalog/brands[?typeId=]` `{name}` | `MANAGE_BRANDS` |
| `PUT /api/v1/catalog/brands/{id}` `{name}` | `MANAGE_BRANDS` |
| `DELETE /api/v1/catalog/brands/{id}` | `MANAGE_BRANDS` |
| `POST /api/v1/catalog/models?typeId=&brandId=` `{name}` | `MANAGE_MODELS` |
| `PUT /api/v1/catalog/models/{id}` `{name}` | `MANAGE_MODELS` |
| `DELETE /api/v1/catalog/models/{id}` | `MANAGE_MODELS` |
| `GET /api/v1/catalog/sn-validations` | session only |
| `GET /api/v1/catalog/models/{modelId}/sn-validation` | session only |
| `PUT /api/v1/catalog/models/{modelId}/sn-validation` `{regexPattern?, active}` | `EDIT_SN_VALIDATION` |
| `GET /api/v1/catalog/providers` | session only |
| `GET /api/v1/catalog/sedes` | session only |
| `GET /api/v1/catalog/sedes/{id}/shipping-info` | session only |
| `GET /api/v1/catalog/sedes/shipping-info-ids` | session only |

- Types/Brands/Models mirror `TYPE`/`BRAND`/`BRAND_TYPE_LINK`/`MODEL` exactly, including the
  global "Genérico / Otro" model (`brandTypeId: null`) UNION'd into every Type+Brand combination
  regardless of whether a real link exists.
- **Stock is the OLD model — a real stored `MODEL_STOCK` counter, per (Type, Brand, Sede) — NOT
  derived "Model Y" (§3.4's other fork).** This is the central v1-vs-full-contract deviation;
  see `backend-contract.md` §3.4's own "adapter detail" carve-out for why this is a legitimate
  branch, not a violation.
- **Rename is deprecate-old-row + create-or-reactivate-new-row, never a plain `UPDATE`** — a name
  may only ever live on one row at a time (active or deprecated), so a historical `NOTE_ITEM`
  pointing at the old row keeps resolving to the name it had when the note was made. Renaming a
  Type or Brand **cascades**: every `BRAND_TYPE_LINK` using the old id gets an equivalent link
  under the new id, and every active Model under that link is cloned forward (merging
  `MODEL_STOCK`, not overwriting it). Renaming the global generic Model deprecates the old row
  and reactivates/creates the replacement, still with `brandTypeId: null` — confirmed by test
  that the row stays global, doesn't get scoped to whatever combination triggered the rename.
- `POST /catalog/brands` with no `typeId` calls the "just add a global brand" path; with `typeId`
  it also creates the `BRAND_TYPE_LINK`. Both no-op/reactivate on the generic-brand name rather
  than creating a stray link for it (matches the desktop app's own `addBrandForType` comment).
- The global generic Model can be renamed but never removed (`400
  CANNOT_REMOVE_GLOBAL_GENERIC` — nothing to scope a replacement to). The generic Brand can never
  be removed either (`400 CANNOT_REMOVE_GENERIC`).
- **Provider/Sede are read-only from the app, by design** — confirmed by reading the desktop
  app's actual current `SqliteEquipmentService`: no `addProvider`/`addSede`/etc. methods exist
  there either. Both are edited directly via SQL, same convention as `APP_USER`.
- `GET /sedes/shipping-info-ids` → a bare `Set<Integer>` of the Sedes that have active
  `SEDE_SHIPPING_INFO` — the desktop filters the Remito destination combo to these (an
  unconfigured Sede can't be a `NOTE_REMITO_SEDE` destination). Ported from
  `IEquipmentService.getSedeIdsWithShippingInfo()`.
- **S/N Validation** (`SN_VALIDATION`, regex-per-model rules) — ported from
  `SqliteEquipmentService.getSnValidation()`/`getAllSnValidationRows()`/`upsertSnValidation()` and
  the desktop `SettingsController` S/N edit dialog. `GET /sn-validations` is the admin panel's
  table (every active asset-type model LEFT-joined to its optional rule; `regexPattern: null` /
  `active: false` = no rule). `GET /models/{modelId}/sn-validation` is the single active rule
  `ItemDialogController` needs while a technician types a serial (`404 SN_VALIDATION_NOT_FOUND`
  when none). `PUT` is a delete-then-insert upsert (one row per model), gated by
  `EDIT_SN_VALIDATION` (**not** Sede-scoped — a rule is global to the model). Server-side
  validation matches the desktop dialog: blank `regexPattern` clears the pattern but keeps the
  rule row; a non-blank pattern must compile (`400 INVALID_REGEX`, with
  `PatternSyntaxException.getDescription()`) and be ≤ 500 chars (`400 REGEX_TOO_LONG`, also
  enforced by the DTO's `@Size`); unknown model → `404 MODEL_NOT_FOUND`. Writes one
  `AUDIT_ADMIN_ACTION` row (`EDIT_SN_VALIDATION`, old/new `"regex=…, activa=…"`) only when the
  stored value actually changes — same no-op-writes-no-audit-row convention as stock/rename.
- **This feature is expected to be removed in v2 once the GLPI adapter lands** (it's on the
  contract's Tier 6 desktop feature-removal list), but stays for the first release — GLPI won't
  be up, so the app still needs it.

---

## Directory (`/api/v1/directory`) — ✅ built (Phase B gap #1)

| Method & path | Permission |
|---|---|
| `GET /api/v1/directory/users?name=&dni=&username=` | session only |

- Replaces the desktop app's direct `IADService.search()` (contract §7.6 / D8). Any authenticated
  session; no permission, not Sede-scoped. AND-combined criteria, **at least one required**
  (`400 MISSING_SEARCH_CRITERIA` otherwise). No match → `200 {results: []}`, never `404`.
- Response item: `{username, fullName, email, dni, ou}` — the contract's literal shape is the
  first four; `ou` (the account's OU / distinguished-name path) is kept as a **lossless
  extension**, since the desktop app's AD user-selection popup displays it.
- **`DirectoryService` is a port of the desktop `AdApiService.search()` logic, not a thin proxy**
  — the org's directory API does loose substring matching and does **not** reliably AND its
  params, so the middleware still has to: fan the query into digit + dotted-DNI variants and
  comma-reordered name variants ("Nombre Apellido" typed vs. "Apellido, Nombre" stored), merge
  by `samAccountName` preferring the more complete record, run a per-word fallback round only
  when the precise variants found nothing, then **re-verify every supplied field client-side**
  (any candidate failing one field is dropped). Results are normalized at the boundary: `dni` →
  digits only, `fullName` → comma stripped.
- **Config**: `middleware.directory.base-url` / `.token` (`application.yml`, real values via
  `MIDDLEWARE_DIRECTORY_BASEURL` / `MIDDLEWARE_DIRECTORY_TOKEN`). Blank →
  `503 DIRECTORY_NOT_CONFIGURED` (same "genuinely open" convention as `idp.issuer-uri` /
  `security.encryption-key`). Directory unreachable / non-200 → `502 DIRECTORY_UNAVAILABLE`.
- **`findExactByUsername(username)`** (not an endpoint — used by `AuthController.login`): runs
  `search(null, null, username)` then keeps only a case-insensitive **exact** username match.
  Empty when nothing matches exactly; propagates the same `503`/`502` as `search()`.
- **Not ported**: `validateCredentials()` (Keycloak owns auth under the OIDC login) and
  `testConnection()` (was for the desktop Settings dialog, which is going away).
- **Tests**: `DirectoryServiceTest` — plain unit test, a scriptable fake `AdApiClient` (no HTTP,
  no Spring): unconfigured→503, no-criteria→empty-and-no-call, exact-name-one-round + normalize,
  DNI-only match dropped by name re-verification, variant merge prefers fuller record, word
  fallback runs only after precise fails, both DNI forms queried, separator-agnostic username.
  The HTTP client (`HttpAdApiClient`) has no test — same "no live external service / never leave
  a real-network test in the suite" precedent; verified by live boot (`401` unauthenticated,
  registered in `/v3/api-docs`).

---

## Notes / History / Approval + Item Sync/Return (`/api/v1/notes`) — ✅ (incl. Remito de Envío)

| Method & path | Permission |
|---|---|
| `POST /api/v1/notes` | session only |
| `GET /api/v1/notes?profileTypes=&approvalStatuses=&sedes=&authorSearch=&recipientSearch=&dateFrom=&dateTo=&itemTypes=&itemBrands=&itemModels=&syncStatuses=&returnStatuses=` | session only |
| `GET /api/v1/notes/{id}` | session only |
| `PUT /api/v1/notes/{id}/approve` | `APPROVE_NOTES`, Sede-scoped |
| `PUT /api/v1/notes/{id}/reject` `{reason}` | `APPROVE_NOTES`, Sede-scoped |
| `POST /api/v1/notes/{noteId}/items/{itemId}/sync` | `SYNC_GLPI`, Sede-scoped |
| `POST /api/v1/notes/{noteId}/items/{itemId}/reject-sync` `{reason}` | `SYNC_GLPI`, Sede-scoped |
| `PUT /api/v1/notes/{noteId}/items/{itemId}/return` `{quantity?}` | `VALIDATE_RETURNS`, Sede-scoped |
| `PUT /api/v1/notes/{noteId}/items/{itemId}/lost` `{reason, quantity?}` | `VALIDATE_RETURNS`, Sede-scoped |

### Create — `POST /notes`
Body: `{profileType, userName, userDni, userEmail, motivo, areaEvento, failureCause,
failureDetails, providerId, cuit, responsibleName, responsibleDni, observations,
shippingInfoId, destinationLabel, destinationAddress, destinationRecipients, items[]}`. Each
item: `{kind: "ASSET"|"COUNTABLE", typeId, brandId, modelId, serialNumber, af, quantity,
observations, modifiesStock, modifiesStockReason}`.

**Remito de Envío** (`profileType: "REMITO DE ENVÍO"`) — the *destination* is a real client
choice (unlike the technician's own Sede): send **either** `shippingInfoId` (the FK of a catalog
Sede's active `SEDE_SHIPPING_INFO` row, from `GET /catalog/sedes/{id}/shipping-info`) → row in
`NOTE_REMITO_SEDE`, **or** the `destination*` free-text trio (a custom CAU with no catalog row) →
row in `NOTE_REMITO_OTHER`. Neither → `400 REMITO_DESTINATION_REQUIRED`. Items get GLPI-PENDING /
RETURN-N_A like any non-Préstamo note (unchanged rule). `GET /notes/{id}` returns
`destinationSedeId` (null for a custom destination) + `destinationLabel`/`destinationAddress`/
`destinationRecipients`; `GET /notes` summary rows carry the same three plus `recipient` =
the destination label.

**`technicianName` / `technicianDni`** are stamped from the caller's **login-time directory
profile** (`CallerPrincipal.displayName()` / `.dni()`) — the real full name + DNI, or the
username + `null` if the directory was unreachable at login. Same "snapshot, don't reference"
rule the desktop app uses for `NOTE_REPORT.technician_name`/`technician_dni`.

**Deviations from `backend-contract.md` §5.1, both deliberate:**
- **No `sedeId` in the request at all.** Checked the real desktop-app controllers before building
  this: Sede was never a client-chosen value there — always the creating technician's own
  `APP_USER.sede_id`, mandatory. The server now resolves it the same way from the caller's
  session (`400 SEDE_NOT_ASSIGNED` if the technician has none) — more faithful to actual behavior
  *and* safer (a client can no longer stamp a note under an arbitrary Sede). Worth reconciling
  into the contract text itself later, not just a v1 shortcut.
- **No `externalItemId`/`externalStatusAtAddTime` per item** — no external system to have picked
  a record from.
- **GLPI-sync eligibility per item is computed server-side**, not client-supplied: every
  non-Préstamo `ASSET` item gets `glpiStatus: PENDING`; Préstamo assets and every `COUNTABLE`
  get `N_A` (GLPI sync there is one-way/no-revert — a loaned asset must never look synced while
  still out). Return-tracking (`RETURN` dimension) is `PENDING` for every item (asset or
  countable) on a Préstamo note, or a Provider note whose `motivo` is in the returnable list
  (currently hardcoded `["Garantía", "Reparación"]` — 🚧 TODO once the Config module exists).
- Response: `201 {id, approvalStatus: "PENDING", createdAt}`.

### List / Get
`GET /notes` filter set is complete (§5.2 / D7, Phase B gap #2 — extended 2026-09-07):

- `itemTypes` / `itemBrands` / `itemModels` — match a note whose `NOTE_ITEM` rows include **one
  item** satisfying all supplied criteria at once (`AND`ed within the subquery, ported from the
  desktop app's `appendInViaCatalog`). Matched by **catalog name regardless of `deprecated`**, so
  filtering by a name a type/brand/model was later renamed away from still finds the old notes.
- `syncStatuses` (`PENDING`/`SYNCED`/`REJECTED`/`N_A`) / `returnStatuses`
  (`PENDING`/`RETURNED`/`LOST`/`N_A`) — a **per-note aggregate** over the summary-row counts, not
  a SQL predicate: filtered in Java after the query (mirrors the desktop `filterByGlpiStatus` /
  `matchesGlpiStatus`). `N_A` = "nothing tracked" (`pending+synced+rejected == 0`, resp.
  `returnPending+returned+lost == 0`) — deliberately **not** "no asset items", since a Préstamo
  note's assets are all `N_A`. Multi-select is any-of.

`GET /notes/{id}` returns the full detail shape incl. `items[]` with per-item
`glpiStatus`/`returnStatus`/etc.

### Approve / Reject
Approving **moves real stock** — the old, pre-redesign model, restored on purpose (see Catalog
section above): `MODEL_STOCK` is decremented for egress profile types (Entrega/Entrega
Permanente/Préstamo/Entrega-Proveedor) or incremented for Devolución, guarded by
`NOTE_REPORT.stock_applied` so re-approving never double-moves it. Insufficient stock →
`409 STOCK_WOULD_GO_NEGATIVE`, nothing moved. This is a real, deliberate divergence from
`backend-contract.md` §5.3/§3.4 ("approval moves no stock... Model Y is always derived") — that
design assumed an external system existed to derive stock from; v1 doesn't have one.

**Remito** does a **dual-Sede move** instead: the note's own `sede_id` (source) always
decrements; the destination Sede (resolved via `NOTE_REMITO_SEDE ⋈ SEDE_SHIPPING_INFO.sede_id`)
increments **only when it's a catalog Sede** — a custom `NOTE_REMITO_OTHER` destination has
nothing to receive. Source-stock shortage → `409 STOCK_WOULD_GO_NEGATIVE`. Both legs write
`AUDIT_STOCK`. Ported from the desktop `applyRemitoStock()`.

### Item sync / return
**Direct, synchronous status flips — `200`, not `202`/`QUEUED`.** No external write, no §10 queue,
no D6 holder-mismatch pre-check. This isn't a shortcut so much as an accurate match: the desktop
app's own GLPI sync was *already* a manual, one-way, no-revert flag with zero live external write
before any of this redesign began — v1 just keeps doing exactly that, server-side now instead of
in local SQLite.

- `sync` → `glpiStatus: SYNCED`. `reject-sync` (mandatory `reason`) → `REJECTED`.
- `return`/`lost` on an **asset** item: whole-item flip, `quantity` ignored if sent.
  `return`/`lost` on a **countable**: `quantity` is *required* (`400 QUANTITY_REQUIRED` if
  missing), partial-batch allocation via `NOTE_ITEM_RETURN_ALLOCATION`
  (`409 QUANTITY_EXCEEDS_PENDING` if it overshoots what's left pending).
- A whole-item `RETURNED` or a countable `RETURNED` allocation credits `MODEL_STOCK` back
  (skipped entirely if the item was flagged `modifiesStock: false` at creation). `LOST` never
  credits stock back, on either shape.

**Deferred, not built** (🚧): the `GLPI_RETURN` tracking dimension (re-sync after a returnable
Provider note's item comes back — real feature, but niche; `NOTE_ITEM_STATUS_TRACKING` already
has the `tracking_type` slot ready for it).

**Audit-row writes are wired** (see Audit section below) — approve/reject stock movement writes
`AUDIT_STOCK` (one row per model actually moved, skipped when nothing moved); sync/reject-sync/
return/lost all write `AUDIT_ITEM_STATUS` (old→new status, `reason`, `quantity` — `1` for a
whole-item flip, the real allocated amount for a countable partial return/loss). All four call
sites now take the acting caller's `username` as a parameter.

---

## Config (`/api/v1/config`) — ✅ built

| Method & path | Permission |
|---|---|
| `GET /api/v1/config` | session only |
| `PUT /api/v1/config` `{...}` | `EDIT_AF_FORMAT_CONFIG` and/or `EDIT_SMTP_CONFIG` — see below |

- **A genuinely new table, `APP_CONFIG` (+3 list tables), not in `V1__init.sql`.** In the old
  dual-store desktop app this data lived in a local `app-config.json` file + local-only SQLite —
  never synced to the shared remote DB. The middleware IS that one shared store now, so this
  needed a real migration (`db/migration/V2__config.sql`), seeded with the same defaults the
  desktop app ships (motivoOptions, fallaOptions, `returnableMotivosProveedor`, `af` prefix/
  separator, `noteItemLimit`) — SMTP host/port/sender deliberately left blank in the seed rather
  than copying the real org's mail server into a checked-in script.
- **`genericLabel` and `returnableMotivosProveedor` now come from here**, not hardcoded constants
  — `CatalogRepository`/`NotesRepository` both read live via `ConfigRepository` (closes the two
  `TODO(Config module)` comments left when those were built).
- **`genericBrandId`/`genericModelId` in the response are resolved live from the catalog** (the
  actual "Genérico / Otro" BRAND/MODEL row ids, stringified) — not stored config. v1 has no
  external adapter, so these already ARE the real internal ids.
- **`smtpPassword` is write-only** — accepted by `PUT`, AES-256/GCM-encrypted (`EncryptionService`
  — same algorithm as the desktop app's `AppKeyEncryptionService`, but a DIFFERENT key: the
  middleware takes a real per-deployment key via `MIDDLEWARE_SECURITY_ENCRYPTION_KEY`, no
  hardcoded default shipped anywhere — blank key = every encrypt/decrypt call fails loud with
  `503 ENCRYPTION_NOT_CONFIGURED`, same "genuinely open, don't guess a value" convention as
  `middleware.idp.issuer-uri`). Never returned by `GET`. Blank/omitted on `PUT` = leave the
  stored value unchanged (same write-only-field convention the desktop app uses everywhere else
  for secrets).
- **Permission gating is per-field-group, with a fallback-to-current-value on the ungranted
  group** — not an all-or-nothing 403 — mirroring the desktop app's own `SettingsController
  .handleSave()` exactly: `PUT` always sends the whole document, so a plain `ADMIN` (who has
  `EDIT_AF_FORMAT_CONFIG` but not `EDIT_SMTP_CONFIG`, per the seeded `ROLE_PERMISSION` grants —
  SMTP stays `SUPERADMIN`-only) can submit the form without silently wiping SMTP settings they
  can't see. `403` only if the caller has NEITHER group.
- **Flagged interpretation, not a certainty**: `EDIT_AF_FORMAT_CONFIG` is reused as the gate for
  every "general" field (motivoOptions, fallaOptions, returnableMotivosProveedor, noteItemLimit,
  failureTriggerMotivo, genericLabel), not just `af`. The old 12-value `Permission` enum never had
  a dedicated permission for these — they were `app-config.json`-file-only, never editable through
  any in-app UI at all — so there's no existing precedent to port faithfully here, only the
  closest analog. Revisit if this doesn't match actual intent once there's a real admin UI to try
  it against.

---

## Audit (`/api/v1/audit/*`) — ✅ built (reads + every write point)

| Method & path | Permission |
|---|---|
| `GET /api/v1/audit/login?username=&page=&size=` | ADMIN or SUPERADMIN |
| `GET /api/v1/audit/item-status?noteId=&itemId=&page=&size=` | ADMIN or SUPERADMIN |
| `GET /api/v1/audit/admin-actions?actor=&targetType=&page=&size=` | ADMIN or SUPERADMIN |

- **One consolidated `AuditRepository`**, not four — replaces the earlier `AuditLoginRepository`
  stub. Both writes (called from `auth`/`catalog`/`notes`) and reads (these 3 endpoints) live in
  the same class.
- **`recordLogin(username, success, failureReason)`** — called from `AuthController.login()` on
  every real outcome (success, not-registered, not-in-allowed-group, IdP-token-invalid). Matches
  the desktop app's own `AUDIT_LOGIN` write points.
- **`recordStockChange(modelId, brandId, typeId, sedeId, username, oldStock, newStock, reason)`**
  — called from `CatalogRepository.setModelStock()` (manual admin stock edits, `reason` is a
  caller-supplied `StockUpdateRequest.reason()`, now mandatory) and from
  `NotesRepository.applyDirectionalStock()` (approval-time stock movement, `reason` is always
  `"Aprobación de nota #" + reportId`). **Resolves `BRAND_TYPE_LINK` internally** from
  `(brandId, typeId)` — every real caller already has those two on hand, same as the desktop app's
  own `recordStockChange()`. **Silently no-ops (does not throw) if no matching
  `BRAND_TYPE_LINK` exists** — an audit write must never be the reason a real stock mutation fails;
  confirmed by a dedicated test (`recordStockChangeSilentlyNoOpsWhenNoBrandTypeLinkExists`).
  `setModelStock()` only writes a row when the value actually changed (`before != stock`), not on
  every call — a same-value "edit" writes nothing.
- **`recordItemStatusChange(itemId, statusKind, oldStatus, newStatus, reason, quantity, username)`**
  — `statusKind` is `"GLPI"` or `"RETURN"` (matches `NOTE_ITEM_STATUS_TRACKING.tracking_type`, not
  yet `"GLPI_RETURN"` since that dimension isn't built — see Notes section above). Called from all
  4 `NotesRepository` mutation methods: `updateItemGlpiStatus`/`updateItemReturnStatus` (whole-item
  flips, `quantity` always `1`) and `allocateCountableReturn` (`quantity` is the real allocated
  amount, `oldStatus` is always `"PENDING"` since that's the only state a pending quantity can be
  allocated from — matches the desktop app's own documented convention for this exact case).
- **`recordAdminAction(username, action, targetType, targetId, oldValue, newValue, reason)`** —
  now wired into all 9 Catalog CRUD call sites (`ADD_TYPE`/`RENAME_TYPE`/`SET_REQUIRES_SERIAL`/
  `REMOVE_TYPE`, `ADD_BRAND`/`ADD_BRAND_FOR_TYPE`/`RENAME_BRAND`/`REMOVE_BRAND`, `ADD_MODEL`/
  `RENAME_MODEL`/`REMOVE_MODEL` — 11 distinct actions across the 9 endpoints, since `addBrand`/
  `addBrandForType` and rename/remove each get their own action name) plus both of Config's
  permission-gated field groups (`EDIT_GENERAL_CONFIG`, `EDIT_SMTP_CONFIG`).
  - **Renames only write a row when the name actually changes** (case-only renames included —
    `"NOTEBOOK"` → `"Notebook"` still logs; renaming to the exact same string, same case, is a
    true no-op and writes nothing). Removes always log the removed row's name as `oldValue`;
    add/create actions resolve the newly-created-or-reactivated row's real id for `targetId`
    rather than leaving it null.
  - **SMTP password is never written to `old_value`/`new_value`, per this codebase's own inherited
    rule (see the desktop app's `CLAUDE.md`, "Secrets are never written to old_value/new_value")**
    — `EDIT_SMTP_CONFIG`'s audit row logs host/port/sender plainly (not secrets) plus only
    `"(password changed)"` when the password field was actually submitted, never the value itself,
    encrypted or not. Verified by a dedicated test
    (`updateConfigWritesAnEditSmtpConfigAuditRowThatNeverContainsThePlaintextPassword`).
  - **Both field groups are logged unconditionally when granted** (not diffed against the prior
    value first, unlike the desktop app's own `SettingsController.handleSave()` which only logs on
    an actual change) — a deliberate v1 simplification for proportionality; every real `PUT
    /config` call from a caller with that permission writes one row, whether or not any field in
    that group actually differs. Revisit if this turns out too noisy in practice.
  - Tests: `renameTypeWritesAnAuditAdminActionRow`, `removeTypeWritesAnAuditAdminActionRowWithTheRemovedName`,
    `updateConfigWritesAnEditGeneralConfigAuditRowOnlyWhenGranted`,
    `updateConfigWritesAnEditSmtpConfigAuditRowThatNeverContainsThePlaintextPassword`.
- **Not Sede-scoped** — any ADMIN can read audit rows for every Sede, not just their own. Flagged
  in the controller's own Javadoc as a v1 simplification worth revisiting (the desktop app has no
  precedent either way — audit reads didn't exist there before this middleware).
- **Paging**: ANSI `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` (works on both SQL Server 2012+ and H2 —
  no MySQL/Postgres-only LIMIT/OFFSET syntax). Default page size 50, hard-clamped to 200 max
  (`oversizedPageSizeIsClampedNotRejected` — never a 400, just silently capped).

## SN Validation — ✅ built (folded into the Catalog module — see the Catalog section above)

---

## Cross-cutting notes worth remembering while adding more endpoints

- **JdbcTemplate, not JPA** — every repository is a plain `@Repository` wrapping `JdbcTemplate`.
  Multi-step writes (`createNote`, `updateApprovalStatus`, `allocateCountableReturn`) use
  `@Transactional` on the repository method, not manual `Connection.setAutoCommit(false)`.
- **Error codes are UPPER_SNAKE_CASE, thrown as `ApiException`**, caught by
  `GlobalExceptionHandler` into the §11 envelope. Add a new one there, don't invent a new error
  shape.
- **`status_updated_at`/`updated_at`-style columns are plain ISO-8601 strings**, not JDBC
  `Timestamp` — written via `LocalDateTime.now().toString()`, read back via `rs.getString(...)`
  wrapped in `normalizeTimestampString()` (handles SQL Server's space-separated `DATETIME2`
  rendering vs. the 'T'-separated string that was written). `NOTE_REPORT.created_at` is the
  exception — that one really is a JDBC `Timestamp` end to end. Don't mix the two conventions on
  a new column without checking which the real schema column type expects.
- **Every new repository needs its own H2-native test schema file** (see
  `catalog-repository-test-schema.sql`/`notes-repository-test-schema.sql`) — `db/migration/
  V1__init.sql` is real SQL-Server-only T-SQL and cannot run against H2. `CREATE TABLE IF NOT
  EXISTS` is required in these test schemas (not just nice-to-have) — `@Sql`-driven scripts
  re-run before every test method against one persistent embedded H2 instance.
