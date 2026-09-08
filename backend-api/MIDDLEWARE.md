# NexusIT Middleware — Consolidated Specification

**Authoritative reference for the whole middleware.** Consolidates
`backend-contract.md` (endpoint contract), `contract-behaviors.md` (testable
invariants), `glpi-adapter-notes.md` (GLPI adapter facts), `reconciliation-endpoint.md`
(reconciliation detail), and `auth-flow.md` (auth sequence). Concrete
deployment values (IdP issuer/client/realm, DB, hosts) live in a
deployment-local integration doc kept **out of the repo**. Those working
documents carry rationale, decision history, and per-item behaviors; **this
document is the single place to see what the middleware is and does.**

_Version: v2.0 (GLPI-adapter). Consolidated 2026-09-08. Branch
`backend-api-v2-glpi-adapter`._

---

## 1. What the middleware is

The desktop app (Generador de Notas IT) stops owning any local catalog or
database and becomes a thin REST client of this middleware, which is its **entire
backend** — catalog, auth, notes/history/audit/permissions, config. An external
asset-management system (GLPI today) is the source of truth for equipment and its
current state; the middleware translates between the app's domain and that
system. **Hard dependency: middleware down = app down** (no local degrade).

### 1.1 Architecture — Ports & Adapters (D1)

One codebase, one `main`:

```
core/       backend-agnostic — auth, notes/history, approval, the flow model
            (§7), RBAC, audit, config. No backend-specific code.
port/       internal adapter interface in generic vocabulary
            (searchAssets, getAssetBySerial, getCountableAvailability,
            assignAsset, releaseAsset, relocateAsset, getCatalogTypes, …).
adapters/
  glpi/     the only adapter today
  <other>/  a future backend = a new module + PR to main, never a branch
```

- The active adapter is configuration (`externalBackend=glpi`).
- Same-backend variation is config **inside** the adapter (field-id maps,
  status-value map, Sede→Location map).
- A different backend is one new adapter module, reviewed against `port/` +
  `contract-behaviors.md`, merged to `main`, selected by config. `core/` and the
  app are untouched.
- A backend that forces a `core/` change → a versioned contract revision behind a
  capability flag, shared by every deployment. Never a per-backend branch.
- **Missing-concept fallbacks are documented, not silent** (no countable notion →
  a middleware counter table; no inventory number → `af = null`).
- **Every adapter read of a collection MUST exhaust the backend's pagination.**
  No call may treat a first page as the whole result — asset search, availability
  counts, an asset's history/`Log`, the `Location` list, the reconciliation asset
  walk, directory search, `listSearchOptions`. Where only a **count/existence** is
  needed, use the backend's own reported total (GLPI search `totalcount`), not
  fetch-all-then-count. A missed page is a correctness bug (wrong stock, missed
  drift, stale holder) — see §13 K4.

### 1.2 Multi-organisation — instance-per-org (D1b)

Not multi-tenant. A second org runs its own deployment + store + adapter +
`GET /config`. `config` may later grow a `branding` block (additive).

---

## 2. Authentication & Session

**OIDC Authorization Code + PKCE against the IdP (Keycloak), Kerberos-brokered.**
The fleet is 100% domain-joined Windows; a domain-joined technician logs in with
no page shown. The desktop app never handles a password, never talks to the IdP's
backing directory or to GLPI directly. The middleware validates the IdP token
itself (JWKS) and mints its own opaque session token. GLPI is entered only
afterward, by the middleware, on business calls.

### 2.1 Discovery (unauthenticated)

```
GET /api/v1/auth/config
→ { issuer, clientId, scopes: ["openid","profile"], authBackendDisplayName }
```
Ships the app configured with only `middleware.baseUrl` (HTTPS); IdP coordinates
are learned at runtime.

### 2.2 Login

1. App generates PKCE `code_verifier`/`code_challenge` + `state`, starts a
   one-shot loopback listener `http://127.0.0.1:<random>/callback` (RFC 8252 —
   system browser, not an embedded webview).
2. App opens the browser to the IdP authorize endpoint. On a domain-joined
   machine with SPNEGO brokering, no login page; otherwise the IdP shows its own
   page (still no password in this app).
3. IdP redirects to the loopback with `code`; app exchanges `code` +
   `code_verifier` at the IdP token endpoint for an IdP access token (JWT).
4. `POST /api/v1/auth/login` with `Authorization: Bearer <IdP access token>`.
   The middleware validates the JWT (**signature via JWKS, `iss`, `exp`, and
   `aud` = the configured client id**), resolves role/Sede/permissions from its
   own store, enforces the registration + allowed-group gate, mints an opaque
   `sessionToken`:

```jsonc
// 200
{ "sessionToken", "expiresAt", "role": "USER|ADMIN|SUPERADMIN",
  "sedeId": "integer|null", "displayName",
  "permissions": ["APPROVE_NOTES","SYNC_EXTERNAL","VALIDATE_RETURNS"] }
// 401 — missing/invalid/expired IdP token
// 403 — valid identity, but not registered in APP_USER, OR not in the allowed
//        group and without bypass_group_check
```

Login-attempt throttling is the IdP's job (`/auth/login` never sees a password).

### 2.3 Authenticated requests / logout / token

- `Authorization: Bearer <sessionToken>` on every call.
- `401` → the app silently re-runs the OIDC flow (Kerberos-brokered, no UI) and
  retries once; only a failed re-auth surfaces "must sign in".
- `403` → valid session, lacks the permission, or targets a Sede the caller isn't
  scoped to.
- `POST /api/v1/auth/logout` — server-side revocation; best-effort from the app.
- Session token: **opaque, server-side store** (not a JWT, not the IdP token
  passed through). Sliding ~2h idle, ~12h absolute cap. In memory only on the
  client; no app-held refresh token (the silent re-auth is the "refresh").

### 2.4 Identity invariant

The acting user is **always** the token subject. **No endpoint reads identity
from a request body.** `POST /notes` does not accept `technicianName` /
`technicianDni` — the middleware derives and snapshots them from the session.

### 2.5 Keycloak deployment

Concrete values (issuer URI, client id, realm, allowed group, DB profile) live
in a **deployment-local integration doc, not in this repo**. Shape:

| item | value |
|---|---|
| Issuer | `https://<idp-host>/realms/<realm>` |
| Client ID | `<client-id>` — public, Auth Code + PKCE `S256`, **no secret** |
| Redirect | register the **path only** (`/callback`). Keycloak applies RFC 8252 and **ignores the port** on `127.0.0.1`/`localhost`; any ephemeral port matches. Do **not** register `http://127.0.0.1:*` — a literal `*` never matches. |
| Claims | `preferred_username` (username), `groups` (**bare** names, no leading `/`), `aud` = the client id |

Middleware config (deploy-time): `idp.issuer-uri`, `idp.client-id`,
`idp.username-claim` (`preferred_username`), `idp.groups-claim` (`groups`),
`idp.allowed-group-name` (**leave blank for now** — rely on the `APP_USER` gate;
if set it fails closed).

**Build gaps:** `IdpTokenValidator` currently checks only `iss`/`exp`/signature —
**add the `aud` check**. `SessionStore` is in-memory with no eviction sweep.
Enforce HTTPS for `middleware.baseUrl`.

**Deployment prerequisite (not built):** Kerberos/SPNEGO browser flow +
Kerberos user federation on the IdP, so a domain machine shows no login page.
Until then the flow falls back to the IdP login page — no app/contract change.

---

## 3. RBAC (D3, RBAC redesign 2026-07-30)

Three roles: `USER`, `ADMIN`, `SUPERADMIN`. **Deny-by-default** — an action with
no grant row is denied to everyone.

**Permission enum — exactly three:**

| Permission | Gates |
|---|---|
| `APPROVE_NOTES` | `approve` / `reject` a note |
| `SYNC_EXTERNAL` | a `SYNC`-kind step: `sync`, `reject-sync`, `retry`/`abandon` on a failed sync. Both item kinds. (Renamed from `SYNC_GLPI`.) |
| `VALIDATE_RETURNS` | a `RETURN`-kind step: `return`, `lost`, `retry`/`abandon` on a failed return |

No `EDIT_CONFIG` and **no `SUPERADMIN`-only permission**. `PUT /config` is gated
by `role == SUPERADMIN` checked **directly**, not via a permission.

**Sede fence.** A plain `ADMIN`'s every action and list read is implicitly scoped
to their own `APP_USER.sede_id` (approve / sync / return / `GET /sync-queue` /
`GET /audit/*` / `GET /reconciliation/drift`). `SUPERADMIN` = `ADMIN` without the
fence — acts on / reads any Sede. A Sede-mismatched note stays **readable** to a
plain ADMIN; only the actions are withheld.

**Administered out-of-band.** No in-app CRUD for `APP_USER` / `ROLE_PERMISSION` /
`SEDE` / `PROVIDER` — direct SQL against the middleware store. `GET /me` and the
login response carry the caller's current role/Sede/permissions so the app can
refresh its (cosmetic) UI gate; every endpoint enforces server-side regardless.

```
GET /api/v1/me
→ { username, role, sedeId, displayName, permissions[], registered,
    bypassGroupCheck, glpiTokenSet }
GET /api/v1/roles/{role}/permissions → ["APPROVE_NOTES", …]
```

---

## 4. Catalog

Backend-neutral replacement for `IEquipmentService`'s Type/Brand/Model surface.
Ids are **external-system opaque strings** (composite itemtype + local id for
GLPI), not the old local integers.

```
GET /api/v1/catalog/types                              → [{id,name,isAsset,requiresSerial}]
GET /api/v1/catalog/brands?typeId=                     → [{id,name}]
GET /api/v1/catalog/models?typeId=&brandId=            → [{id,name}]
```

### 4.1 Backend facts (GLPI, v2.0)

- **Itemtype coverage (N2).** `GET /catalog/types` flattens GLPI's five core
  physical itemtypes — **Computer, Peripheral, Phone, Monitor, Printer** — wired
  directly, **plus the four GLPI-11 custom-asset itemtypes** — **Multimedia,
  AudioEquipment, Security, Misc** — in v2.0 scope but each **gated on a
  per-itemtype investigation** against the real instance (does it carry
  `states_id` / `users_id` / `locations_id`, a Type/Model dropdown, the
  movement-stamp field; plan for `states_id = 0` Multimedia inventory).
  Multimedia is the priority. Custom assets: opaque composite ids →
  contract-compatible with no change.
- **Countables are backed by GLPI `Peripheral` only (N1)** for v2.0 — no per-Type
  "backing kind" flag, no second code path. The `Consumable` model (one-way
  `date_out`, no return dimension) is **deferred to a later release**.
- **Field ids are per-instance (N3)** — resolved once at adapter startup via
  `listSearchOptions`. Confirmed on dev: `serial` = 5, `otherserial` = 6 (the
  inventory/`af` number, read-only), `users_id` = 70 (holder), `contact` = 7
  (free-text recipient), `locations_id` = 3, movement stamp = plugin Fields
  container id 8 (`motivodemovimientofield` / `comentariosdemovimientofield`).

### 4.2 "Genérico / Otro" (D5b, shape E1b)

Not a stored catalog row — a config-driven fallback in `GET /config`:

```jsonc
"generic": {
  "label": "Genérico / Otro",
  "manufacturerId": "string|null",   // ONE global generic manufacturer (the "generic brand")
  "typeIds":  { "<itemtype>": "string|null" },   // per-itemtype generic <X>Type
  "modelIds": { "<itemtype>": "string|null" }    // per-itemtype generic <X>Model
}
```

The app appends a "Genérico / Otro" option (labeled `generic.label`, sorted last)
to Brand and Model pickers for **every** Type. For a **countable** it stores
`brandId = generic.manufacturerId`, `modelId = generic.modelIds[itemtype]` (either
may be `null`), names = `generic.label`; availability/sync/reconciliation resolve
against records under the generic manufacturer (Model-Y, §4.4). For an **asset**
it's just a search filter. **Degrade path:** every id may be `null` until the
GLPI admin creates the rows — a `null` id → "no id, `generic.label` name", counts
fall back to a Type-scoped count.

### 4.3 Asset search & countable availability

```
GET /api/v1/catalog/assets/search?typeId=&brandId=&modelId=&serialCriteria=
→ { results: [ { externalItemId, name, typeId, typeName, brandId, brandName,
                 modelId, modelName, serialNumber, af|null, assignedUser|null,
                 state: "AVAILABLE"|"IN_USE"|"UNAVAILABLE" } ] }
```
`typeId` scopes; `brandId`/`modelId` optional. Empty `results` is a normal `200`.
`serialCriteria` is substring. **Fully paginated** server-side (K4).

```
GET /api/v1/catalog/countables/availability?typeId=&brandId=&modelId=&sedeId=
→ { available: 12 }
```
Computed from GLPI search's **reported `totalcount`** (not fetch-all): `Peripheral`
records matching Type/Brand/Model with `states_id` in the AVAILABLE bucket and
`locations_id` under the `sedeId`'s Location subtree (§8).

**`state` bucket mapping — CONFIRMED (E1a):**

| bucket | GLPI `states_id` |
|---|---|
| `AVAILABLE` | `33` (En stock) |
| `IN_USE` | `31` (En uso), `34` (En préstamo), `36` (Instalado), `40` |
| `UNAVAILABLE` | everything else — `35` (En tránsito), `42`–`48` (reparación / garantía / obsoleto / baja / reciclaje / donación / scrap), and `0` / `null` |

`states_id` and `users_id` are independent GLPI fields and can disagree.
Availability is taken from `states_id`, holder from `users_id`; the disagreement
raises a `GLPI_CONTRADICTION` reconciliation row (§9) — never silently resolved
(E1a Q6).

### 4.4 Stock model — derived ("Model Y", D4)

"Stock" is **always a live derived count** of AVAILABLE records per model per
Sede — never a stored, mutated number. Consequences:
- **Approval moves nothing.** Only a `sync` step assigning/relocating real
  records changes an availability count.
- A shortage is the §10 sync-time conflict (queued job → `FAILED`), not an
  approval-time `409`.
- No `MODEL_STOCK` successor, no `applied` flag, no `STOCK_WOULD_GO_NEGATIVE`, no
  "Modifica stock" item flag.
- For GLPI in v2.0 every countable is a `Peripheral` (counts cleanly) → **no
  counter is ever used**.
- **Dual SSOT:** the external system is authoritative for *stock & current
  state*; the app's note history is authoritative for *assignments*. Every
  legitimate assignment flows through an approved + synced note → drift is
  detectable (§9).
- The item-dialog pre-generation shortage warning stays — advisory only.

```
GET /api/v1/assets?holder={username}
```
Assets currently assigned to a person (same row shape as search). Used for the
ENTREGA "recipient already holds a same-type asset" warning (computed
client-side) and by an admin before a DEVOLUCIÓN.

---

## 5. Notes, History, Approval

### 5.1 Create

```
POST /api/v1/notes
```
```jsonc
{
  "profileType": "ENTREGA" | "DEVOLUCIÓN" | "ENTREGA PERMANENTE" | "PRÉSTAMO"
               | "ENTREGA - PROVEEDOR" | "REMITO DE ENVÍO",
  "recipientName", "recipientDni", "recipientEmail": "string|null",
  "sedeId": "integer",                    // the note's own Sede (source, for REMITO)
  "destinationSedeId": "integer|null",    // REMITO DE ENVÍO only — a catalog Sede that
                                          // MUST map to a GLPI Location (§8). No free-text /
                                          // custom destination, no destinationRecipients.
  "motivo": "string|null",                // or expected-return-date for Préstamo
  "areaEvento": "string|null",            // Préstamo only
  "failureCause": "string|null", "failureDetails": "string|null",  // Devolución+Falla
  "providerId": "integer|null", "cuit": "string|null",
  "responsibleName": "string|null", "responsibleDni": "string|null",  // provider note
  "observations": "string|null",
  "items": [ {
    "kind": "ASSET" | "COUNTABLE",
    "externalItemId": "string|null",       // asset only
    "typeId": "string|null", "typeName": "string",     // ids + names, immutable snapshot (D5)
    "brandId": "string|null", "brandName": "string",   // id null-able for "Genérico / Otro"
    "modelId": "string|null", "modelName": "string",
    "serialNumber": "string|null", "af": "string|null",              // asset only
    "externalStatusAtAddTime": "AVAILABLE|IN_USE|UNAVAILABLE|null",   // asset only, snapshot
    "quantity": "integer|null",                                      // countable only
    "observations": "string|null"
  } ]
}
// 201 → { id, approvalStatus: "PENDING", createdAt }
```

Technician identity is **not** sent — derived + snapshotted from the session.
A new note is always `PENDING` (server-enforced).

**Create-time validation:**
- Zero items → `400`.
- **DEVOLUCIÓN**, an asset whose current external state is `AVAILABLE` →
  `409 ASSET_NOT_IN_USE`, not created. (The only asset-state hard block.)
- **REMITO DE ENVÍO**, missing `destinationSedeId` or one with no GLPI `Location`
  mapping → `422 SEDE_LOCATION_NOT_MAPPED`, not created.
- **ENTREGA - PROVEEDOR**, a `motivo` with no `syncTargets.proveedor` entry
  (state or `NONE`) → `422 PROVIDER_MOTIVO_NOT_CONFIGURED`, not created.
- Everything else at create time is a soft `warnings[]` entry (`201`): DEVOLUCIÓN
  with state `UNAVAILABLE`; DEVOLUCIÓN/PRÉSTAMO holder ≠ recipient; ENTREGA where
  the asset is already `IN_USE` or the recipient already holds a same-type asset.
- The authoritative holder check runs again, **gated**, at sync time (§7.5).

### 5.2 Get / list / filter

```
GET /api/v1/notes/{id}
GET /api/v1/notes?profileTypes=&itemTypes=&itemBrands=&itemModels=&sedes=
      &authorSearch=&recipientSearch=&dateFrom=&dateTo=
      &approvalStatuses=&syncStatuses=&returnStatuses=&page=&size=
```
Any-of within a field, and-across fields. `syncStatuses` / `returnStatuses` are
per-step-kind (D7) — a note matches if any item has a step of that kind in a
listed status. No `approvalStatuses` filter → returns `PENDING` + `APPROVED`,
excludes `REJECTED`. Paginated; the total reflects the full filtered set.

### 5.3 Approve / reject

```
PUT /api/v1/notes/{id}/approve            // needs APPROVE_NOTES, Sede-scoped
PUT /api/v1/notes/{id}/reject  { reason } // reason mandatory
```
Only a `PENDING` note is actionable (`409` otherwise). **Approval performs no
external write and moves no stock** — it only unlocks the item-level step actions
(§7). Rejecting bars every item step for that note.

---

## 6. Audit

Server-recorded successor to `AUDIT_LOGIN` / `AUDIT_STOCK` / `AUDIT_ITEM_STATUS` /
`AUDIT_ADMIN_ACTION`. **The app never writes an audit row** — the middleware
records one as a side effect of every state-changing action it performs. The app
only reads.

```
GET /api/v1/audit/login?username=&page=&size=
GET /api/v1/audit/item-status?noteId=&externalItemId=&page=&size=
GET /api/v1/audit/admin-actions?actor=&targetType=&page=&size=
```
ADMIN/SUPERADMIN only. Every state-changing call writes exactly one row (acting
user from the token, timestamp, before/after). No row ever contains a secret
value. A denied/failed call leaves no partial state.

---

## 7. Item flow model & external actions

### 7.1 Flow model (config-driven, server-side — §4.0)

Each note item's lifecycle is an ordered list of **steps**, selected by a
predicate over `(profileType, motivo)`:

```jsonc
[
  { "match": { "profileTypes": ["ENTREGA","ENTREGA PERMANENTE","DEVOLUCIÓN"] }, "steps": ["sync"] },
  { "match": { "profileTypes": ["REMITO DE ENVÍO"] },                           "steps": ["sync"] },
  { "match": { "profileTypes": ["PRÉSTAMO"] },                                  "steps": ["sync","return"] },
  { "match": { "profileTypes": ["ENTREGA - PROVEEDOR"], "motivoIn": ["<returnableMotivosProveedor>"] }, "steps": ["sync","return"] },
  { "match": { "profileTypes": ["ENTREGA - PROVEEDOR"] },                       "steps": ["sync"] }
]
```

Step kinds: `SYNC` (§7.3–7.4, gated `SYNC_EXTERNAL`), `RETURN` (§7.5, gated
`VALIDATE_RETURNS`). Engine rules:
- A step is actionable only once the note is `APPROVED` and every prior step
  reached terminal success.
- **A write step requires the acting technician's personal GLPI token** (§11) —
  otherwise `409 GLPI_TOKEN_NOT_SET`, nothing enqueued. `reject-sync` is exempt.
- Per-step status: `PENDING` → `REJECTED` (admin, pre-write, terminal) **or**
  `QUEUED` → `SYNCED` / `RETURNED` (terminal) **or** `FAILED` → `QUEUED` (retry)
  / `<STEP>_ABANDONED` (admin, terminal, reason).
- Rejecting a step ends **that item's** flow only — siblings and the note's
  `approvalStatus` are untouched.
- A `RETURN` step on a countable is batchable (partial quantities over multiple
  actions); on an asset it's one whole-item transition.
- The app never evaluates the flow — the middleware returns which actions are
  currently available per item; the app renders them.

### 7.2 `syncTargets` — GLPI `states_id` on a `sync` (E1a, CONFIRMED)

| note `profileType` (· Motivo) | `sync` sets `states_id` → | holder |
|---|---|---|
| ENTREGA / ENTREGA PERMANENTE | `31` En uso | recipient |
| PRÉSTAMO | `34` En préstamo | recipient |
| DEVOLUCIÓN (its single `sync` step *is* the return) | `33` En stock | cleared |
| ENTREGA - PROVEEDOR · Garantía | `43` En garantía | cleared; `contact` ← provider + CUIT |
| ENTREGA - PROVEEDOR · Reparación | `42` En reparación | cleared; `contact` ← provider + CUIT |
| ENTREGA - PROVEEDOR · Devolución de préstamo | `45` En baja † | cleared; `contact` ← provider + CUIT |
| ENTREGA - PROVEEDOR · Otro | `42` En reparación | cleared; `contact` ← provider + CUIT |
| REMITO DE ENVÍO | *unchanged* | *unchanged* — sets `locations_id` → destination Sede's Location |

- `PRÉSTAMO → 34` is uniform for assets and countables.
- Every `sync` write stamps GLPI's movement-reason field (Fields container 8) with
  the note reference + acting technician.
- **Provider `syncTargets.proveedor`** is a config map from every
  `motivoOptions.proveedor` value → a `states_id` **or** the sentinel `NONE` (sync
  completes with no external write; unused today). An unmapped provider Motivo →
  `422 PROVIDER_MOTIVO_NOT_CONFIGURED` at `POST /notes`. `Garantía` / `Reparación`
  are in `returnableMotivosProveedor` (return step → `33`, `contact` cleared);
  `Otro` / `Devolución de préstamo` are not.
  **†** `Devolución de préstamo` returns a **vendor-owned** asset the org held on
  long-term loan; default `45`. The GLPI admin may create a dedicated "Devuelto a
  proveedor" state and remap.

### 7.3 Sync ("Sincronizar")

```
POST /api/v1/notes/{noteId}/items/{itemId}/sync
  {}                                       // usually empty
  { "acknowledgeHolderMismatch": true }    // re-call after a 409 HOLDER_MISMATCH
→ 202 { "stepStatus": "QUEUED" }           // ENQUEUED (§10), not done in the request
→ 409 HOLDER_MISMATCH { details: { expectedRecipient, actualHolder } }
→ 409 GLPI_TOKEN_NOT_SET
```
On success (observed via `GET /notes/{id}`): for an asset the picked record is
assigned + its state set per §7.2; for a countable the middleware picks N
AVAILABLE records, persists their ids on `NOTE_ITEM_RETURN_ALLOCATION`, so a later
`/return` and reconciliation know what to release.

- **Over-allocation (D2):** if availability is below the note's quantity when the
  job runs, the job is all-or-nothing → `FAILED`, admins alerted. No partial
  allocation; the queue is serialised per model.
- **Holder-mismatch pre-check (D6b) — gated, not advisory.** Synchronous holder
  read before enqueuing; `409 HOLDER_MISMATCH` when the current holder
  contradicts the action (ENTREGA: already IN_USE by someone else; DEVOLUCIÓN /
  PRÉSTAMO return: holder ≠ recipient). Re-call with `acknowledgeHolderMismatch`
  to proceed.

### 7.4 Reject sync ("Rechazar")

```
POST /api/v1/notes/{noteId}/items/{itemId}/reject-sync  { reason }  // mandatory
→ 200 — step REJECTED, no external write
```
Valid only while `PENDING`. On a `QUEUED` / `FAILED` step → `409` (use
retry/abandon).

### 7.5 Return & Lost

```
PUT /api/v1/notes/{noteId}/items/{itemId}/return
  { "quantity": "integer|null", "acknowledgeHolderMismatch": "boolean|null" }
→ 202 QUEUED  |  409 HOLDER_MISMATCH  |  409 GLPI_TOKEN_NOT_SET
// on success: holder cleared, states_id → 33. EXCEPTION (D6a): a record that was
// UNAVAILABLE keeps its states_id (holder still cleared).

PUT /api/v1/notes/{noteId}/items/{itemId}/lost  { "reason", "quantity": "integer|null" }
→ 202 QUEUED
// on success: holder cleared, states_id → 45 (En baja) — NOT 48, never back to 33.
```
`quantity` — omit for a whole-item/whole-quantity action; on a countable,
cumulative (returned + lost) may never exceed the synced quantity (`400`).
`§7.3–7.4` and `§7.5` step statuses are independent — advancing one never changes
the other. These steps apply only to note types whose flow includes a `return`
step (§7.1).

---

## 8. Sedes & Locations (E2a, E2b)

```
GET /api/v1/sedes → { results: [ { id, name, locationMapped: true } ] }
```

The middleware keeps its own `SEDE` catalog (id + name) — the target of
`APP_USER.sedeId`, `NOTE_REPORT.sedeId`, a Remito's `destinationSedeId`.
Read-only from the app; administered out-of-band.

**E2a — a Sede *is* a GLPI `Location`.** The adapter holds a
`sede → locations_id` map (deploy config, §12). Locations are a **tree**, so "at
a Sede" = that node or any descendant (`searchtype = under`). The map drives:
per-Sede countable availability (§4.3), Remito relocation (§7.2), and the printed
Remito's destination address (read server-side from the Location's own address
fields). `locationMapped` tells the app which Sedes may be a Remito destination;
an unmapped one → `422 SEDE_LOCATION_NOT_MAPPED` at `POST /notes`.

**E2b — no middleware `SEDE_SHIPPING_INFO`.** No shipping-info table, no
`GET /sedes/{id}/shipping-info` endpoint, no free-text / custom Remito
destination, no `destinationRecipients` in the v2.0 payload (a deferred desktop
feature — Sede-select address auto-fill + recipient inputs — built later).

---

## 9. Reconciliation (drift detection) — §14 / Decision 8

Read-only detector. **Never auto-fixes** — which side is right is a human call.
In the v2.0 first release.

**Scope — "scope (b)", two passes:**
- **(i) Per-unit, direct-claim things** — serialized assets (via `externalItemId`
  on the note item) and currently-assigned non-serialized countable units (via
  GLPI ids the §10 worker records on `NOTE_ITEM_RETURN_ALLOCATION`). Check
  `states_id` bucket, holder, location.
- **(ii) Per-(category, Sede) aggregate** over *unassigned* non-serialized
  countables: `expected AVAILABLE = N(total) − K(assigned per open note)` vs
  `actual AVAILABLE` → one `COUNT_MISMATCH` per (category, Sede) with a limbo
  breakdown (En tránsito / En reparación / `states_id = 0` / assigned-no-note).

**Expected state is derived on-the-fly** from the latest SYNCED note-item action
per unit — **no `ASSET_PROJECTION` table** (recompute keeps a single source of
truth; a hand-maintained cache would reintroduce the drift class this feature
exists to catch; scope (ii) already forces recompute logic; the nightly scan's
cost is GLPI round-trips, not the local query). Mapping:

| latest SYNCED action | expected holder | expected `states_id` bucket | expected location |
|---|---|---|---|
| ENTREGA / ENTREGA PERMANENTE | recipient | IN_USE | note Sede |
| PRÉSTAMO | recipient | `34` | note Sede |
| DEVOLUCIÓN / return | none | AVAILABLE | Sede that processed the return |
| LOST | none | `45` | unchanged |
| REMITO relocation | unchanged | unchanged | destination Sede |

**Runs:** nightly + `POST /reconciliation/run` on-demand. **No per-sync
re-check** — the §10 queue precondition-checks the record before every write.
Sede-scoped; a nav-badge count (excludes pre-cutoff rows).

### 9.1 Read

```
GET /api/v1/reconciliation/drift
      ?sedeId=&driftKind=&acknowledged=&includePreCutoff=&page=&size=
```
One feed, row shapes discriminated by `target.kind`:

```jsonc
{ "scannedAt": "...",
  "rows": [
    { "id", "driftKind": "STATE_MISMATCH|HOLDER_MISMATCH|LOCATION_MISMATCH|ORPHAN_ASSIGNMENT|GLPI_CONTRADICTION",
      "target": { "kind": "ASSET_UNIT", "externalItemId", "itemtype", "serialNumber",
                  "typeName", "brandName", "modelName" },
      "sedeId",
      "expected": { "holder", "stateBucket", "sedeLocationId", "fromNoteId", "syncedAt" } | null,
      "actual":   { "holder", "stateBucket", "locationId" },
      // ORPHAN_ASSIGNMENT only:
      "assignmentDate": "2026-09-08 12:51:57", "assignmentDateSource": "LOG|DATE_MOD",
      "assignedBy": "string|null", "preCutoff": false,
      "acknowledged": { "by","at","reason" } | null, "detectedAt" },
    { "id", "driftKind": "COUNT_MISMATCH",
      "target": { "kind": "CATEGORY_SEDE", "categoryKey", "typeName", "brandName", "modelName", "sedeId" },
      "sedeId", "expectedAvailable": 40, "actualAvailable": 37, "delta": -3,
      "limbo": { "enTransito", "enReparacion", "stateZero", "assignedNoNote" },
      "acknowledged": {…} | null, "detectedAt" }
  ] }
```

| drift kind | meaning |
|---|---|
| `STATE_MISMATCH` | unit's `states_id` bucket ≠ expected |
| `HOLDER_MISMATCH` | unit's holder ≠ expected (covers "returned a unit, GLPI still shows it on them") |
| `LOCATION_MISMATCH` | unit's location ≠ expected Sede's Location |
| `COUNT_MISMATCH` | per-(category, Sede) `expectedAvailable` ≠ `actualAvailable` |
| `ORPHAN_ASSIGNMENT` | external asset has a holder but no open note explains it — §9.3 |
| `GLPI_CONTRADICTION` | GLPI's `states_id` and `users_id` disagree (E1a Q6) |

### 9.2 Resolve

```
POST /api/v1/reconciliation/{id}/repush        // STATE/HOLDER/LOCATION_MISMATCH only; needs SYNC_EXTERNAL
POST /api/v1/reconciliation/{id}/acknowledge  { reason }   // any kind; reason mandatory, audited
```
`repush` re-asserts `expected` via the §10 queue. On `COUNT_MISMATCH` /
`ORPHAN_ASSIGNMENT` / `GLPI_CONTRADICTION` → `400` (no single deterministic write
to re-assert; admin fixes GLPI by hand or `acknowledge`s). `acknowledge` is
scoped to the exact `expected`/`actual` pair — if either later changes the row
**re-surfaces un-acknowledged**. A row auto-clears when a scan finds
`expected == actual`. Scans `upsert` by `(driftKind, target)` — no duplicates.

### 9.3 Pre-app assignments (`ORPHAN_ASSIGNMENT` cutoff)

The external system is a live instance with years of history; run #1 could raise
hundreds of orphan rows. Handled by a go-live cutoff — **`ORPHAN_ASSIGNMENT`
only**, never the other kinds:

- **`reconciliation.orphanCutoff`** (config, ISO date; default = the earliest
  `NOTE_REPORT` timestamp). Each orphan row is stamped
  `preCutoff = assignmentDate < cutoff`.
- `GET /reconciliation/drift` and the nav-badge count **exclude `preCutoff`
  rows** by default; `?includePreCutoff=true` returns them (real rows —
  `acknowledge` works). `run` **stores** them (doesn't skip at scan time) so the
  count is known without a re-scan and a later cutoff change just recomputes
  `preCutoff`.
- **`assignmentDate`** — the middleware reads the asset's GLPI history: the
  per-item `Log` sub-resource (`GET /api.php/v1/…/{itemtype}/{id}/Log`, **paged
  until exhausted** — K4), filtered to `id_search_option == <holder search
  option>` AND `linked_action == 0`, newest `date_mod` wins, and `new_id` must
  equal the asset's current holder → `assignmentDateSource = "LOG"`. If no such
  entry survives (GLPI purges history), fall back to the asset's `date_mod` →
  `assignmentDateSource = "DATE_MOD"` (an upper bound, not the real date — the UI
  marks these "fecha aproximada"). Log-first fails toward *noise, not silence*: a
  `date_mod` upper bound can never hide a genuine post-go-live assignment.
  Confirmed `Log` row shape: `{ itemtype, items_id, linked_action: 0,
  id_search_option: 70, date_mod: "yyyy-MM-dd HH:mm:ss" (no TZ — GLPI server TZ),
  old_value/new_value ("name (id)"), old_id/new_id (raw users_id FKs),
  user_name }`.
- **`reconciliation.suppressApproximateOrphans`** (config, default `true`) —
  `false` to always show `DATE_MOD`-sourced orphans regardless of `preCutoff`.

---

## 10. External write queue — §10 / Decisions 3 + 7

**In the v2.0 first release.** Every external write — item `sync`, `return`,
`lost`, and a reconciliation `repush` — is **enqueued, not executed in the
request**. The action endpoint checks permission + flow state + the caller's GLPI
token, enqueues a job, returns `202` with the step `QUEUED`. **Approval enqueues
nothing** (§5.3).

**Three distinct concepts:** domain item status (permanent flow-step record) ·
`glpiStatus` (`PENDING` → `SYNCED` | `FAILED` — a mirror flag: does the external
record reflect this step yet) · the queue row (the pending job). **No optimistic
`glpiStatus`** — it stays `PENDING` until the worker confirms the write landed;
the app never shows `SYNCED` before `GET /notes` reports it.

### 10.1 Durability — `EXTERNAL_WRITE_QUEUE`

One row per owed write: `{ id, model_key, command (payload — itemtype, external
id(s), target states_id / users_id / locations_id, note ref), status
(PENDING/IN_PROGRESS/DONE/FAILED), attempt_count, last_error, next_retry_at,
lease_until }`.

- A **`@Scheduled` poller** claims due rows, **serialised per `model_key`** (a
  per-model lock — two jobs never race for the same external stock), under a
  lease. A crash mid-job lets the lease expire and another poller reclaims it —
  **restart-safe**, no lost or double-run jobs.
- Before writing, the worker **reads the live external record and
  precondition-checks it** against the job's expectation. On a mismatch it raises
  a §9 reconciliation drift row and does **not** blind-write.
- All enqueuing paths create rows the same way — no per-note-type or per-item-kind
  code path.

### 10.2 Processing, failure, resolution

- **All-or-nothing** per job (allocate/release all N or fail).
- **Transient failure** (external unreachable, `5xx`) → auto-retry with backoff;
  step stays `QUEUED`; no alert.
- **Unrecoverable conflict** (stock moved below the note's quantity; the record's
  state isn't what the action requires; the note is immutable) → step `FAILED`,
  **no timed auto-retry**, admins alerted.
- Enqueue is idempotent via the flow state guard — a step already `QUEUED` or
  past it → `409`.

```
POST /api/v1/notes/{noteId}/items/{itemId}/retry     // FAILED → QUEUED (manual only)
POST /api/v1/notes/{noteId}/items/{itemId}/abandon   { reason }   // FAILED → SYNC_ABANDONED / RETURN_ABANDONED
```
`abandon` is terminal; the note stays valid, only that item's write is given up.

### 10.3 Alerting

A step entering `FAILED` alerts through two channels: an in-app **"Cola de
sincronización"** view + nav-badge (Sede-scoped), and an email to the responsible
admin(s). `QUEUED` / completed jobs raise no alert.

```
GET /api/v1/sync-queue?status=FAILED&sedeId=&page=&size=
```
Feeds the in-app view — note id, item, step, requested vs. available, timestamp,
last error. Sede-scoped per the caller's role.

---

## 11. Global config & secrets

### 11.1 `GET /api/v1/config` — app-facing policy surface

```jsonc
{
  "afFormat": { "enabled": true, "prefix": "IT", "separator": "-" } | { "enabled": false },
  "noteItemLimit": "integer",
  "returnableMotivosProveedor": ["Garantía", "Reparación"],
  "failureTriggerMotivo": "Falla",
  "motivoOptions": { "entrega": [], "finDeContrato": [], "proveedor": [], "devolucion": [] },
  "fallaOptions": [],
  "generic": { "label", "manufacturerId": "string|null",
               "typeIds": { "<itemtype>": "string|null" }, "modelIds": { … } }
  // no "smtp" block — SMTP is middleware-internal (§11.3)
}
```

```
PUT /api/v1/config
```
**`SUPERADMIN` role-gated (Decision 6)** — checked directly against `role`, not a
permission; no `EDIT_CONFIG`. No `smtpPassword` field. No desktop Configuración
screen exists for this.

**No `/me/preferences`.** Per-user UI toggles (clear form on submit, close tab on
submit) stay desktop-local — never round-tripped through the middleware.

### 11.2 Personal GLPI token (F1)

```
PUT /api/v1/me/glpi-token  { "token": "string" }  → 204
```
The middleware holds one shared GLPI `App-Token` (deploy config) for reads; each
technician configures **their own** `user_token` here, stored encrypted in
`APP_USER.glpi_token_encrypted`. Every external write is made as that technician
(lands in GLPI's native history under their name). **Write-only** — never
returned; `GET /me` reports only `glpiTokenSet`. A technician can set only their
own token. **No token → `409 GLPI_TOKEN_NOT_SET` on `sync` / `return` / `lost`**;
reads are unaffected.

### 11.3 Middleware-internal config — NOT in any config response

Deploy-time settings the app never sees:
- **GLPI adapter** — E1a `states_id` → bucket map, `syncTargets` (incl.
  `syncTargets.proveedor`), the Sede → `locations_id` map, per-instance field-id
  resolution (N3).
- **Reconciliation** — `reconciliation.orphanCutoff`,
  `reconciliation.suppressApproximateOrphans`.
- **IdP** — `idp.issuer-uri` / `.client-id` / `.username-claim` / `.groups-claim`
  / `.allowed-group-name`.
- **SMTP** — `smtp.host` / `.port` / `.senderAddress` / `.password`, used **only**
  for §10.3 alert emails. No app-facing surface (Decision 6).
- **Secrets** — the GLPI `App-Token`, per-user `user_token`s (encrypted at
  rest), the IdP client details, `middleware.security.encryption-key`. Never in
  any response.

**Open (F2):** storage/rotation mechanics for the middleware's own credentials —
deployment/ops, unaddressed.

---

## 12. GLPI adapter reference

**Instance:** the dev GLPI instance, **GLPI 11.0.7**, HL API `api.php/v1/`.
**Auth:** one shared `App-Token` + a per-user `user_token` → a `Session-Token`
(NOT OAuth on GLPI itself — OAuth/OIDC is only between the app and Keycloak).

**Field ids (per-instance, resolved via `listSearchOptions` — N3; dev values):**
`serial` = 5 · `otherserial` = 6 (= `af`, read-only) · `users_id` = 70 (holder) ·
`contact` = 7 (free-text recipient) · `locations_id` = 3 (tree) · movement stamp =
plugin Fields container id 8 (`motivodemovimientofield` /
`comentariosdemovimientofield`).

**`states_id` list (physical-asset leaf states):** `31` En uso · `33` En stock ·
`34` En préstamo · `35` En tránsito · `36` Instalado · `42` En reparación ·
`43` En garantía · `44` Obsoleto · `45` En baja · `46` En reciclaje · `47` En
donación · `48` Scrap. Buckets in §4.3.

**Itemtypes:** Core 5 (Computer, Peripheral, Phone, Monitor, Printer) direct;
custom assets Multimedia / AudioEquipment / Security / Misc gated on investigation
(§4.1). Type catalog flattens all of them with composite opaque ids.

**Sede = GLPI `Location`** (`api.php/v1/Location`, `locations_id` field 3, tree,
`searchtype = under`). Sede → Location-id map is adapter config, built from
`GET /Location?range=0-200` (paged — K4). Location natively carries
address/postcode/town/state/country/building/room.

**Generic rows** (`generic.manufacturerId` / `typeIds` / `modelIds`) **do not
exist in GLPI yet** — GLPI admin creates them; config entries `null` until then.

**Every adapter collection read is fully paginated** (K4) — asset search,
availability (`totalcount`), `Log` history, `Location` list, `listSearchOptions`,
the reconciliation walk, directory search.

---

## 13. Conventions

**Error envelope** — every non-2xx:
```jsonc
{ "error": { "code": "STABLE_MACHINE_CODE", "message": "Spanish, human",
             "details": { } } }
```
Clients branch on `code`, never the message.

**Status codes:** `400` malformed input · `401` missing/invalid/expired session ·
`403` permission / Sede-scope · `404` unknown resource · `409` state conflict
(flow guard, holder mismatch, GLPI token not set) · `422` semantic precondition
(`SEDE_LOCATION_NOT_MAPPED`, `PROVIDER_MOTIVO_NOT_CONFIGURED`) · `5xx` middleware
or downstream failure.

**Idempotency (K2):** every mutating flow endpoint is guarded by its own state —
a transition that already happened → `409` or a no-op. `POST /notes` accepts an
optional `Idempotency-Key` header (a retry with the same key returns the original
`201`).

**Pagination (K4):** every middleware→backend read of a collection loops until
the backend reports no more pages; a partial page is never treated as complete. A
pure count uses the backend's reported `totalcount`.

**Sede-scoping (H2–H4):** enforced server-side on every action and list read;
bypassing the client changes nothing.

---

## 14. Deployment prerequisites & open items

| | State |
|---|---|
| **Remote SQL Server** for the `sqlserver` profile | not provisioned — dev runs H2, no real schema. **Pre-release blocker.** |
| **Middleware deployment** | runs only via `mvn spring-boot:run` |
| **Keycloak — Kerberos/SPNEGO federation** | not configured; login falls back to the KC page until then |
| **Keycloak — `aud` validation** in `IdpTokenValidator` | build task |
| **`SessionStore` eviction** + HTTPS enforcement | build tasks |
| **GLPI generic rows** (Manufacturer + per-itemtype Type/Model) | GLPI admin — look up ids for `generic.*` |
| **Sede → GLPI `Location` map** | GLPI admin — `GET /Location`, complete for every Remito-destination Sede |
| **Custom-asset investigation** (Multimedia / AudioEquipment / Security / Misc) | not started — blocks the custom-asset adapter path only, not Core 5 |
| **Provider "Devuelto a proveedor" state** (optional) | GLPI admin — default maps to `45` |
| **F2** — middleware secret storage/rotation | deployment/ops, unaddressed |
| **The strip** (v1-no-adapter compensations → core-only) | not started — precedes the real adapter build |
| **Real `adapters/glpi/` implementation** | stubs only; blocked on the GLPI-admin data + a live GLPI to test against |
| **Phase B** — desktop app → REST client | planned, not started |
