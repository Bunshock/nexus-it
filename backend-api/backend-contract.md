# Backend API Contract — Generador de Notas IT Middleware

Status: **DRAFT** — under active discussion, no code written against this yet.
Branch: `backend-api-middleware-contract` (off `backend-api`).
Companion documents:
- `new-middleware-app.txt` (desktop-app-side planning doc, kept outside
  this repo — OneDrive) — the *why* and the desktop-side consequences.
- `auth-flow.md` — auth sequence diagrams (this repo).
- `contract-behaviors.md` — the testable Given/When/Then invariants any
  implementation must pass (this repo). This document is the *what*; that
  one is the *must pass*.
Last updated: 2026-08-31.

This document assumes you've read the companion planning doc, but restates
the load-bearing decisions inline where they shape an endpoint, so it can
stand alone for implementation purposes.

---

## 0. Scope, restated (bigger than it first sounds)

This is **not** just a GLPI catalog proxy. The middleware is the app's
**entire backend**. The desktop app (JavaFX) will have:

- **Zero direct database access.** No JDBC to SQL Server, no local SQLite.
  Every read and write goes through this API.
- **Zero locally-held secrets.** GLPI API key, AD service-account
  credentials, SMTP password, SQL Server credentials — none of it ships in
  the app binary or a local file ever again.
- **Zero local equipment catalog.** Type/Brand/Model/Stock all live in
  the external asset-of-record system (GLPI today), reached only through
  this API.

Practically, this means most of what `desktop-app`'s `services/` package
does today (`SqliteEquipmentService`, `SqliteHistoryService`,
`SqliteUserRoleService`, `SqliteAuditService`, `AppKeyEncryptionService`,
`RemoteDatabaseService`, `DatabaseService`'s entire SQLite schema/migration
history) has a REST equivalent defined below, and the local implementations
become candidates for deletion once the desktop app is rewritten against
this contract. That rewrite is **not** part of this document — this
document only defines the contract itself.

---

## 1. Design principles

1. **Backend-neutral naming.** GLPI is what's configured today. No endpoint,
   field, or enum value in this contract may be named after GLPI
   specifically (no `glpiStatus`, no `/glpi/...` paths). Internally, the
   middleware's GLPI-facing adapter code can and should use GLPI's own
   vocabulary — that's an implementation detail behind this contract, not
   part of it.
2. **One middleware, one base URL.** The desktop app is configured with a
   single endpoint (working name `middleware.baseUrl`) and talks to nothing
   else external.
3. **The app is a thin, stateless-per-request REST client.** No caching
   assumptions baked into the contract — if the desktop app wants to cache
   something client-side for UX responsiveness (e.g. the last-fetched
   catalog list, per this app's existing "background the slow calls"
   lesson), that's the app's own concern, not something the API needs to
   support specially.
4. **Session-token auth on every request** (see §2) — no endpoint in this
   contract is unauthenticated except the discovery endpoint
   (`GET /auth/config`), the login endpoint itself, and a basic health
   check.
5. **JSON over HTTPS.** No assumption yet about API versioning strategy
   beyond a URL prefix (`/api/v1/...`) — see §12.
6. **Fail loud, fail structured.** See §11 for the error envelope every
   endpoint uses on failure. No endpoint should return a 200 with an
   embedded "actually this failed" body.

### 1.1 Adapter model (how "swap the backend" actually works)

**RESOLVED 2026-08-31 (decision D1).** One codebase, one `main`. Ports &
Adapters:

```
core/       backend-agnostic — auth, notes/history, approval, the flow
            model (§4.0), RBAC, audit, config. Never contains
            backend-specific code.
port/       the internal adapter interface, in this contract's generic
            vocabulary (searchAssets, getAssetBySerial,
            getCountableAvailability, assignAsset, releaseAsset,
            getCatalogTypes, …). Stable.
adapters/
  glpi/     the only adapter today
  <other>/  added later as a module + PR to main — never a branch
```

- **Which adapter is active is configuration** (`externalBackend=glpi`).
- **Minor variations of the same backend are configuration inside that
  adapter** — field-name maps, a status-value map (`{backend status →
  AVAILABLE | IN_USE | UNAVAILABLE}`), the location/entity → Sede map.
- **A genuinely different backend is one new adapter module** — reviewed
  against `port/` and `contract-behaviors.md`, merged to `main`, selected
  by config. `core/` and the desktop app are untouched.
- **A new backend that needs a `core/` change** (a fallback for a concept
  it lacks, a new config knob, rarely a new contract field) is a normal
  versioned contract revision merged to `main` behind a
  capability/config flag so it stays inert for backends that don't need
  it. **Never a per-backend branch.**
- **Missing-concept fallbacks are documented, not silent.** A backend with
  no countable/consumable notion → `/catalog/countables/availability`
  served from a middleware counter table. No server-generated inventory
  number → `af` is `null`.
- **Every adapter read of a collection MUST exhaust the backend's
  pagination.** No adapter call may act on a first page as if it were the
  whole result — asset search, availability counts, an asset's
  history/`Log`, the `Location` list, the reconciliation asset walk,
  directory search, `listSearchOptions`, any `GET /{itemtype}` collection.
  Where only a **count or existence** is needed, use the backend's own
  reported total (e.g. GLPI search's `totalcount`) rather than fetching all
  rows and counting them. A partial page silently treated as complete is a
  correctness bug (a wrong stock number, a missed drift row, a stale
  holder) — see `contract-behaviors.md` K4.

Rule of thumb: ~90% of a new backend is its adapter; the ~10% that isn't
is a deliberate, reviewed `core/` change shared by every deployment —
never a fork.

### 1.2 Multi-organisation

**RESOLVED 2026-08-31 (decision D1b).** Strategy is **instance-per-org**,
not multi-tenant: a second organisation runs its own middleware
deployment, its own store, its own adapter choice, its own `GET /config`.
Per-org policy already lives in `GET /config` (§8); per-org printed
templates are already files; per-org flow shape is already `GET`-config
(§4.0). Branding (colours / logo / app name) is not modelled yet but the
door is kept open — `GET /config` may grow a `branding` block later with
no breaking change, and the desktop app keeps branding centralised in
`styles.css` / resources rather than hardcoded in Java/FXML. **Not built
now:** multi-tenancy, a theming engine, a rules engine.

---

## 2. Authentication & Session

**RESOLVED 2026-08-31.** The identity provider in front of GLPI is Keycloak
(an OIDC/SAML IdP); the machine fleet is 100% domain-joined Windows. Given
both, auth is **OIDC Authorization Code + PKCE against the IdP**, with the
IdP brokering Kerberos so a domain-joined technician logs in with no page
shown. The desktop app never handles a password. This supersedes the
earlier "SPNEGO vs. explicit-credential POST, pending GSSAPI research"
framing — the app and middleware hand-roll no Kerberos; SPNEGO, if it
happens at all, is entirely between the browser and Keycloak.

"Keycloak" is the concrete IdP today; per §1 nothing in this contract is
named after it. The auth model here — per-user identity end to end,
`GET /me`, server-authoritative RBAC, deny-by-default — is shared with a
parallel contract effort covering the same middleware; §7.5 adopts its
acceptance criteria.

### 2.0 Discovery (unauthenticated)

```
GET /api/v1/auth/config
```
```jsonc
// Response 200
{
  "issuer": "https://idp.example/realms/xxx",  // OIDC issuer URL
  "clientId": "string",                         // public client — PKCE, no secret
  "scopes": ["openid", "profile"],
  "authBackendDisplayName": "GLPI"              // human label for "Buscar en …" (companion §2.3)
}
```

Lets the app ship configured with only `middleware.baseUrl` (§1 #2) and
learn the IdP coordinates at runtime. Unauthenticated, alongside the health
check.

### 2.1 Login / session establishment

Client flow:

1. App generates a PKCE `code_verifier` / `code_challenge` and starts a
   one-shot loopback listener on `http://127.0.0.1:<random>/callback`
   (RFC 8252 native-app pattern — system browser, **not** an embedded
   webview).
2. App opens the system browser to the IdP's authorization endpoint
   (resolved from `issuer` via standard OIDC discovery) with `client_id`,
   the loopback `redirect_uri`, `code_challenge`, `state`, `scope`.
3. The IdP authenticates the user. On a domain-joined machine with the
   IdP's Kerberos/SPNEGO browser flow enabled this shows **no login page**.
   If Kerberos brokering fails, the IdP shows its own login page — still no
   password handled by this app.
4. The IdP redirects to the loopback with an authorization `code`; the app
   exchanges `code` + `code_verifier` at the IdP's token endpoint for an
   IdP access token (JWT).
5. App calls:

```
POST /api/v1/auth/login
Authorization: Bearer <IdP access token>
```
```jsonc
// Request body: none
// Response 200
{
  "sessionToken": "string",       // opaque middleware bearer token
  "expiresAt": "ISO-8601 datetime",
  "role": "USER" | "ADMIN" | "SUPERADMIN",
  "sedeId": "integer | null",
  "displayName": "string",
  "permissions": ["APPROVE_NOTES", "SYNC_EXTERNAL", "VALIDATE_RETURNS"]  // see §7.3
}

// Response 401 — missing / invalid / expired IdP token
// Response 403 — valid IdP identity, but not registered in the user store,
//   OR not in the allowed group and without the bypass-group-check flag
//   (mirrors today's APP_USER registration gate + AD-group gate)
```

The middleware validates the IdP token (signature via the IdP's JWKS,
`iss` / `aud` / `exp`), resolves the caller's role / Sede / permission set
from its own store, mints an opaque `sessionToken`, and returns the shape
above — everything the session needs in one shot, mirroring
`TechnicianSessionService.loginResolved()` today.

**Login-attempt rate limiting is the IdP's job now**, not this endpoint's —
`/auth/login` never sees a password, so it cannot be brute-forced. Today's
`countRecentFailedLoginAttempts()` (5 / 15 min) is replaced by Keycloak's
own brute-force protection.

### 2.2 Authenticated requests

```
Authorization: Bearer <sessionToken>
```

- `401` — missing / expired / invalid middleware session token. The app
  responds by **silently re-running the OIDC flow** (§2.1 steps 1–5;
  Kerberos-brokered, no UI on a domain machine) and retrying the call
  once. Only if that re-auth itself fails does the app surface a
  "must sign in" state. This is the one sanctioned silent retry — it
  re-establishes identity, it does not retry without auth.
- `403` — valid session, but the acting user lacks the permission the
  endpoint requires, or the action targets a Sede the user isn't scoped to
  (§7.3, §7.5).

### 2.3 Logout

```
POST /api/v1/auth/logout
```

Invalidates the middleware session token server-side. Best-effort from the
app (fire-and-forget on close is fine); the middleware may also call the
IdP's end-session endpoint. Server-side TTL (§2.4) means a missed logout is
not a real gap.

### 2.4 Session token

- **Opaque, server-side session store** — not a JWT, and not the IdP's own
  access token passed through. Lets the middleware own the lifetime and
  makes `/auth/logout` a real revocation.
- **Sliding ~2h idle timeout**, renewed on every authenticated request;
  **~12h absolute cap.** Fits a technician who opens the app once per
  shift; the cap forces re-auth on a machine left running overnight.
- **No refresh token held by the app.** On expiry or a `401` the app
  re-runs the (silent) OIDC flow — simpler than storing and protecting a
  refresh token, and invisible to the user anyway.
- Held **in memory only** on the client, for the life of the app process —
  never written to disk, consistent with today's session-only technician
  identity.

### 2.5 Identity invariant

The acting user is **always** the subject of the presented token. **No
endpoint reads the caller's identity from a request body** (parallel
contract's A2). Consequence: `POST /api/v1/notes` (§5.1) does **not**
accept `technicianName` / `technicianDni` — the middleware derives them
from the session identity and snapshots them onto the note. Response bodies
still carry those snapshotted values (History displays them); only the
create *request* omits them.

### 2.6 Resolved / deployment notes for this section

- **Middleware → GLPI per-user identity — RESOLVED (F1, §7.1.1).** The
  middleware holds one shared GLPI `App-Token` for reads; each technician
  provides **their own** GLPI `user_token` via `PUT /me/glpi-token`
  (write-only, `APP_USER.glpi_token_encrypted`). Every external write is
  made as that technician, so it lands in GLPI's native history under their
  name and stamps the movement-reason field (N3). A technician with no
  token set → `409 GLPI_TOKEN_NOT_SET` on any write step; reads are
  unaffected. No shared-service-account write fallback.
- **IdP deployment values** (issuer URI, client id, realm, allowed group) live
  in the deployment's own Keycloak integration doc, kept **out of the repo**.
  The public OIDC client is registered as Auth Code + PKCE `S256`, no secret.
  **Loopback redirect: register the path only** (`/callback`) — Keycloak
  applies RFC 8252 and **ignores the port** on `127.0.0.1` / `localhost`, so
  any ephemeral listener port matches. Do **not** register
  `http://127.0.0.1:*`; a literal `*` in the port position never matches. The
  middleware must also validate `aud` (the realm stamps `aud` = the client
  id) — a build task; `IdpTokenValidator` currently checks only
  `iss` / `exp` / signature.
- **Still a deployment prerequisite:** the Kerberos/SPNEGO browser flow +
  Kerberos user federation on the IdP, so a domain-joined machine shows no
  login page. Until then, login falls back to the IdP's own
  username/password page — no app or contract change either way.
- Non-domain login path — **no action needed**: the fleet is 100%
  domain-joined; the IdP's own login page covers any exception with no
  contract change.

---

## 3. Catalog

Backend-neutral replacement for `IEquipmentService`'s Type/Brand/Model/Stock
surface. GLPI is the concrete backend today (Peripherals for countables,
whatever GLPI's asset types are for serialized items) — the middleware
does the translation; this contract never sees GLPI's own field names.

**Backend facts (GLPI, v2.0 — detail in `glpi-adapter-notes.md`):**
- **Itemtype coverage (N2).** `GET /catalog/types` flattens GLPI's five
  core physical itemtypes (Computer, Peripheral, Phone, Monitor, Printer)
  **plus the four GLPI-11 custom-asset itemtypes** (Multimedia,
  AudioEquipment, Security, Misc). The Core 5 are wired directly; the
  custom four are in v2.0 scope but each **gated on a per-itemtype
  investigation** against the real instance — whether it carries
  `states_id` / `users_id` / `locations_id`, a Type/Model dropdown, and the
  movement-stamp field. Multimedia is the priority. Catalog ids stay opaque
  strings (composite `itemtype` + local id), so this needs no contract
  change.
- **Countables are backed by GLPI `Peripheral` only (N1)** for v2.0; the
  `Consumable` path is deferred to a later release (§3.3).
- **Field ids are per-instance (N3).** `serial`, the inventory-number
  field, `users_id` (holder), and the movement-stamp field are resolved
  once at adapter startup via GLPI's `listSearchOptions` — never named in
  this contract.

### 3.1 Browse catalog (Type → Brand → Model cascade)

```
GET /api/v1/catalog/types
GET /api/v1/catalog/brands?typeId={typeId}
GET /api/v1/catalog/models?typeId={typeId}&brandId={brandId}
```

```jsonc
// GET /catalog/types response
[{ "id": "string", "name": "string", "isAsset": true, "requiresSerial": true }]
// isAsset mirrors today's TYPE.is_asset — distinguishes searchable assets
// (§3.2) from aggregate countables (§3.3) at the Type level, same as today.
```

Brand/Model responses follow the same `{id, name}` shape, scoped by the
query params. **Ids here are external-system ids** (strings, not
necessarily numeric — GLPI's own ids, opaque to the app) — not the
desktop app's old local `TYPE.id`/`BRAND.id`/`MODEL.id` integers.

**"Genérico / Otro" (decision D5b; config shape revised by E1b).** Not a
stored catalog row — a config-driven fallback. `GET /config` (§8) carries a
`generic` block:
- `label` — the display string ("Genérico / Otro").
- `manufacturerId` (`string | null`) — the backend's **one global**
  generic manufacturer id. GLPI Manufacturers are global (not per-itemtype),
  so this is a single id — the "generic brand".
- `typeIds` / `modelIds` (`{ "<itemtype>": "string | null" }`) —
  **per-itemtype** generic `<X>Type` / `<X>Model` ids. A generic
  `PeripheralModel` is a different row in a different id space from a
  generic `ComputerModel`, so these are maps keyed by itemtype, not single
  values.

The desktop app appends a "Genérico / Otro" option (labeled `generic.label`,
sorted last, fallback styling) to the Brand and Model pickers **for every
Type**, asset or countable:
- **countable** — the "not in the catalog" fallback. Picking it stores
  `brandId` = `generic.manufacturerId` and `modelId` =
  `generic.modelIds[itemtype]` (either may be `null`), `brandName` /
  `modelName` = `generic.label` (matches §5.1's null-able id / label name).
  Availability, `sync` allocation and reconciliation resolve against
  records of this Type under the generic manufacturer (and the generic
  model where its id is set) — Model-Y derived (§3.4), no middleware
  counter.
- **asset** — Brand/Model are optional **search filters** (§3.2); picking
  "Genérico / Otro" narrows the serial search to generic-manufacturer
  assets. The item's actual brand/model always come from the picked record.

**Degrade path (unchanged from D5b).** Every id in `generic` may be `null`
until the GLPI admin creates the rows (Tier 3 data owed). A `null` id means
the generic option resolves to "no id, `generic.label` name", and a
generic-countable count falls back to a Type-scoped count.

### 3.2 Search assets (serial-numbered items)

```
GET /api/v1/catalog/assets/search?typeId={typeId}&brandId={brandId}&modelId={modelId}&serialCriteria={partial}
```
`typeId` scopes the search; **`brandId` / `modelId` are optional** narrowing
filters. A generic-manufacturer asset is found by serial without a brand
filter, and the picked record carries its real (generic) brand.

```jsonc
// Response 200
{
  "results": [
    {
      "externalItemId": "string",
      "name": "string",
      "typeId": "string", "typeName": "string",
      "brandId": "string", "brandName": "string",
      "modelId": "string", "modelName": "string",
      "serialNumber": "string",
      "af": "string | null",           // server-generated, read-only (§0)
      "assignedUser": "string | null", // designated user, if any
      "state": "AVAILABLE" | "IN_USE" | "UNAVAILABLE"  // generic enum,
                                         // see companion doc §2.5 — the
                                         // middleware maps GLPI's real
                                         // status values into this
    }
  ]
}
```

Empty `results` array is a valid, normal response (no matches) — not a 404.
`serialCriteria` is a partial/substring match, per the companion doc's item
dialog spec (§5.2) — the middleware owns the actual matching logic against
whatever the backend supports (GLPI's own search API, most likely a
"contains" filter on the serial field).

**`state` bucket mapping — CONFIRMED (E1a, against the dev GLPI instance).**
The middleware maps GLPI `states_id` to the generic enum:
- `AVAILABLE` — `33` (En stock).
- `IN_USE` — `31` (En uso), `34` (En préstamo), `36` (Instalado), `40`.
- `UNAVAILABLE` — everything else, including `35` (En tránsito), `42`–`48`
  (reparación / garantía / obsoleto / baja / reciclaje / donación / scrap),
  and `0` / `null`.

`states_id` and `users_id` are independent GLPI fields and can disagree
(e.g. `33` with a holder set): availability is taken from `states_id`,
holder from `users_id`, and the disagreement raises a `GLPI_CONTRADICTION`
reconciliation row (§14) — never silently resolved (E1a Q6).

### 3.3 Countable availability (aggregate)

```
GET /api/v1/catalog/countables/availability?typeId={typeId}&brandId={brandId}&modelId={modelId}&sedeId={sedeId}
```

```jsonc
// Response 200
{ "available": 12 }
```

A single aggregate number — computed server-side from the backend's own
**reported total** for a filtered search (GLPI search `totalcount`), **not**
by fetching all matching records and counting them (§1.1 / K4). The filter:
`Peripheral` records matching Type/Brand/Model with `states_id` in the
AVAILABLE bucket (`33` — §3.2) and `locations_id` under the `sedeId`'s GLPI
Location subtree (E2a — `sedeId` resolves to a Location id; Locations are a
tree, so "at a Sede" = that node or any descendant, `searchtype = under`;
§7.4). The app never sees or selects individual records for a countable
item.

**Backing type (N1).** For the v2.0 first release, **every countable is
backed by GLPI `Peripheral`** — no per-Type "backing kind" flag, no second
code path. The GLPI `Consumable` model (one-way `date_out`, no return
dimension) is **deferred to a later release**; when it lands it adds a
per-Type backing flag and its own allocation path. Until then a countable
that is really consumable (toner, cable, adapter) is modelled as a
`Peripheral` like any other.

### 3.4 Stock model (derived — "Model Y")

**RESOLVED 2026-08-31 (decision D4).** "Stock" is **always a live derived
count** of records in AVAILABLE state in the external system, per model
per Sede — never a number the middleware stores and mutates. Consequences:

- **Approval moves nothing** (§5.3). The only thing that changes an
  availability count is a `sync` step actually assigning / relocating real
  records (§4.1).
- A **shortage** is the §10 sync-time conflict (queued job → `FAILED`),
  not an approval-time `409`.
- No `MODEL_STOCK` successor table, no `applied` flag for stock, no
  `STOCK_WOULD_GO_NEGATIVE`, no "Modifica stock" item flag.
- Whether the middleware truly derives the count or keeps an internal
  counter for backends that can't be counted cleanly is an **adapter
  detail** (§1.1) — the contract only promises "read availability; `sync`;
  availability follows." For GLPI in v2.0, every countable is a `Peripheral`
  (N1, §3.3), which counts cleanly, so **no counter is ever used**.
  **Generic-brand countables are still real backend records** (under the
  generic manufacturer — §3.1 / D5b), so they are Model-Y derived like
  anything else.
- **Dual SSOT:** the external system is authoritative for *stock &
  current state*; the app's own note history is authoritative for
  *assignments* ("who was assigned what, by which note"). Every legitimate
  assignment flows through an approved + synced note, so drift between the
  two is detectable — see §14 (Reconciliation).

The **pre-generation shortage warning** in the item dialog stays — it
reads live derived availability at add-time and is advisory only; the
authoritative check is the sync-time conflict above.

### 3.5 Assets held by a person

```
GET /api/v1/assets?holder={username}
```
Returns the assets currently assigned to that person in the external
system, same row shape as §3.2's search results. **Added by decision D6**
— the desktop app calls it when a note's recipient is selected, to compute
the "recipient already holds a same-type asset" warning (`contract-behaviors.md`
§F4) client-side. Independently useful (an admin checking what someone
holds before a DEVOLUCIÓN). `username` is an external-directory username,
not necessarily an `APP_USER`.

---

## 4. Item sync / return actions — CORRECTED 2026-08-27, manual, unified across item kinds

**This section originally described these calls as automatically
triggered by note approval, for both item kinds. That was wrong, and has
been corrected** after direct user pushback: the app's existing manual
"Sincronizar"/"Rechazar" and "Devuelto"/"No devuelto" (or "Recibido"/"No
recibido") buttons must stay exactly where technicians expect them —
approval only *unlocks* those buttons (same as today's `"APPROVED".
equals(status)` gate), it does not itself perform any external write. See
companion doc §4.6 for the full corrected decision, including why (nothing
about moving responsibility to the middleware requires collapsing an
existing two-step flow into one).

**Also corrected**: countables get the exact same treatment as assets now
(previously GLPI-sync was asset-only, per today's `GlpiStatus` always
being `N_A` for countables) — there is no more asset/countable branching
in this section. Both kinds go through the identical action set below,
scoped to a specific note item.

These endpoints are called directly by the desktop app's own UI buttons
(the item-card action row), not by §5.3's approve/reject endpoints.

**§4.3/§4.4 don't apply to every note type — verified against the current
implementation's `needsReturnTracking` gate** (`SqliteHistoryService.
save()`): only Préstamo notes, and Provider notes whose Motivo is on the
returnable list (Garantía, Reparación, etc.), get a return dimension at
all. Devolución and plain Entrega/Entrega Permanente items only ever go
through §4.1/§4.2 (sync/reject) — §4.3/§4.4 (return/lost) never apply to
them, same as today. This gate must carry over unchanged; it's not
something this redesign touches. See §4.0 for the generalized, config-driven
version of this gate.

### 4.0 Item flow model (generic, config-driven — added 2026-08-28)

Rather than hardcoding "assets get X, countables get Y" or "Préstamo gets
a return step, Entrega doesn't" as scattered conditionals, each note
item's lifecycle is driven by a small, server-side, configurable **flow**:
an ordered list of **steps**, selected by a predicate over
`(profileType, motivo)` — not looked up by profileType alone, since
Provider notes' need for a return step depends on Motivo too.

Step shape:
```jsonc
{
  "id": "sync",              // unique within a flow, even if two steps share a kind
  "kind": "SYNC",             // backend-neutral. groups same-flavor steps for UI/permission
                              //   — SYNC → SYNC_EXTERNAL permission, RETURN → VALIDATE_RETURNS
  "actions": { "advance": "SINCRONIZAR", "reject": "RECHAZAR" },
  "batchable": false          // true only for a RETURN-kind step on a countable item
}
```

Flow selection, by predicate (first match wins), not a flat
`{profileType: flow}` dict:
```jsonc
[
  { "match": { "profileTypes": ["ENTREGA", "ENTREGA PERMANENTE", "DEVOLUCIÓN"] },
    "steps": ["sync"] },
  { "match": { "profileTypes": ["REMITO DE ENVÍO"] },
    "steps": ["sync"] },   // sync = relocate (locations_id → dest Location, §4.1)
  { "match": { "profileTypes": ["PRÉSTAMO"] },
    "steps": ["sync", "return"] },
  { "match": { "profileTypes": ["ENTREGA - PROVEEDOR"], "motivoIn": ["<returnableMotivosProveedor>"] },
    "steps": ["sync", "return"] },
  { "match": { "profileTypes": ["ENTREGA - PROVEEDOR"] },
    "steps": ["sync"] }   // fallback: non-returnable Motivo
]
```
(`motivoIn` is fed from the `returnableMotivosProveedor` config list, not a
hardcoded pair — today that list is `["Garantía", "Reparación"]`.)
This predicate is the generalized, config-driven form of today's
`needsReturnTracking = isPrestamo(profileType) ||
isProviderReturnable(profileType, motivo)` check
(`SqliteHistoryService.save()`) — same gate, expressed as data instead of
scattered Java conditionals.

Only two step kinds exist today (`SYNC` = §4.1/4.2, `RETURN` = §4.3/4.4;
`SYNC` renamed from `GLPI` per §1 #1, decision D3).
A third, `GLPI_RETURN`, exists in the current schema
(`NOTE_ITEM_STATUS_TRACKING.tracking_type`) but only for Provider-returnable
notes, to work around today's GLPI sync being one-way/no-revert. It's
**obsolete under this contract** — §4.3's `/return` already performs the
real external release write directly, for any note type or item kind, so
there's nothing left for a third step to track.

Engine rules (apply uniformly — not re-declared per flow):
- A step only becomes actionable once the note is `APPROVED` and every
  prior step in its flow has reached a terminal success state.
- **A write step (`SYNC` or `RETURN` kind) requires the acting technician
  to have a personal GLPI token set** (F1, §7.1.1) — otherwise
  `409 GLPI_TOKEN_NOT_SET`, nothing enqueued. `reject-sync` (no external
  write) is exempt.
- **Per-step status vocabulary** (queue mechanics in §10): `PENDING` →
  `REJECTED` (admin, pre-write, terminal) **or** `QUEUED` (write enqueued)
  → `SYNCED` / `RETURNED` / … (terminal success) **or** `FAILED` (write
  couldn't complete) → `QUEUED` again on admin retry **or**
  `<STEP>_ABANDONED` (admin, terminal, mandatory reason). `REJECTED` is
  only reachable from `PENDING`.
- **Rejecting a step ends that ITEM's flow only — not the note.** A note
  can have one item rejected at its sync step while sibling items proceed
  through their own flows independently, and the note's own
  `approvalStatus` is entirely unaffected. Rejection is scoped per-item,
  never propagates to sibling items or to the note level.
- A `RETURN`-kind step on a countable item is batchable (partial
  quantities resolved over multiple actions, via the existing append-only
  allocation mechanism — companion doc §6); on an asset item it's always
  a single whole-item transition.

**This lives server-side, in the middleware — not the desktop app.** The
app never evaluates the flow or the predicate itself; per item, the
middleware returns which actions are currently available (e.g.
`"availableActions": ["SINCRONIZAR", "RECHAZAR"]`), and the app just
renders whatever comes back. This extends §1's backend-agnostic design
principle from "which catalog backend" to "which workflow shape."

This supersedes the plain "two independent dimensions" framing previously
in §5.2/§13 item 2 with a generalized, extensible version of the same
idea — still independent, per-step tracking, not merged into a single
state machine, but expressed as configurable steps instead of two
hardcoded columns plus scattered conditionals.

### 4.1 Sync (egress — "Sincronizar")

```
POST /api/v1/notes/{noteId}/items/{itemId}/sync
```
```jsonc
// Request — usually empty; the middleware knows the item from the note
{}
// after a HOLDER_MISMATCH pre-check (see below), re-call with:
{ "acknowledgeHolderMismatch": true }

// Response 202 Accepted — the write is ENQUEUED, not done yet (§10)
{ "stepStatus": "QUEUED" }

// Response 409 HOLDER_MISMATCH — synchronous pre-check failed, nothing enqueued
{ "error": { "code": "HOLDER_MISMATCH",
             "details": { "expectedRecipient": "maria", "actualHolder": "juan" } } }
```
Clicking "Sincronizar" **enqueues** the external write (§10); it is not
performed in the request. The eventual result is observed via
`GET /notes/{id}` (the item's step status) — `SYNCED` on success, `FAILED`
on an unrecoverable conflict. On success, for an asset the ONE record the
technician picked via search (§3.2) is assigned to the recipient and its
state flipped; for a countable the middleware picks WHICH specific N
records to allocate (companion §4.5 — not the app's concern) and persists
their ids against the note item (`NOTE_ITEM_RETURN_ALLOCATION`), so a later
`/return` (§4.3) — and reconciliation (§14) — know what to release without
the app tracking them.

**`states_id` write target on `sync` success (E1a `syncTargets` — CONFIRMED
for asset *and* countable):**

| note `profileType` (· Motivo) | `sync` sets `states_id` → | holder |
|---|---|---|
| ENTREGA / ENTREGA PERMANENTE | `31` En uso | recipient |
| PRÉSTAMO | `34` En préstamo | recipient |
| DEVOLUCIÓN (its single `sync` step *is* the return) | `33` En stock | cleared |
| ENTREGA - PROVEEDOR · Garantía | `43` En garantía | cleared; `contact` ← provider name + CUIT |
| ENTREGA - PROVEEDOR · Reparación | `42` En reparación | cleared; `contact` ← provider name + CUIT |
| ENTREGA - PROVEEDOR · Devolución de préstamo | `45` En baja † | cleared; `contact` ← provider name + CUIT |
| ENTREGA - PROVEEDOR · Otro | `42` En reparación | cleared; `contact` ← provider name + CUIT |
| REMITO DE ENVÍO | *unchanged* | *unchanged* — `sync` sets `locations_id` → destination Sede's GLPI Location (E2a); no `states_id` / holder change |

`PRÉSTAMO → 34` is uniform for assets and countables — a loaned Peripheral
runs `33 → 34 → 33`, no per-itemtype branch. Every `sync` write also stamps
GLPI's movement-reason field (N3) with the note reference; provider syncs
also clear `users_id` and write the provider name + CUIT into GLPI's
free-text `contact` field (the provider is a company, not a GLPI user). The
`return` step for a returnable provider Motivo (Garantía / Reparación)
resolves generically to `33` and clears `contact` (§4.3).

**Provider targets are per-Motivo** — a `syncTargets.proveedor` config map
from each `motivoOptions.proveedor` value to a `states_id` **or** the
sentinel `NONE`:
- `Garantía → 43`, `Reparación → 42` — equipment is temporarily at the
  vendor; these Motivos are in `returnableMotivosProveedor`, so the note's
  `return` step brings the item back to `33`.
- `Otro → 42` En reparación — a general "being worked on at the vendor"
  action (system analysis, diagnostics, etc.). `Otro` is **not** in
  `returnableMotivosProveedor` (its flow is `["sync"]` only), so the asset
  sits at `42` with no in-app return path — a later corrective Devolución
  note or a manual GLPI edit brings it back. Accepted, not a bug.
- `Devolución de préstamo → 45` — a **vendor-owned** asset the org held on
  long-term loan (tracked in GLPI while in the org's hands) is returned to
  the vendor for good; it leaves the org's inventory. **†** The GLPI admin
  *may* create a dedicated "Devuelto a proveedor" state and remap this —
  `45 En baja` carries a written-off connotation that doesn't fit "returned
  in good order"; bucket is `UNAVAILABLE` either way.
- A map value may also be the sentinel **`NONE`** (the `sync` step
  completes with no external write) — no current Motivo uses it, but it's
  available for a future paperwork-only Motivo an org might add.
- **Every `motivoOptions.proveedor` value must have an entry** (a state or
  `NONE`). An unmapped Motivo makes `POST /notes` fail —
  `422 PROVIDER_MOTIVO_NOT_CONFIGURED` (§5.1), same "fail at creation, not
  at sign-time" principle as E2b's unmapped-Sede rejection.

**Over-allocation race — RESOLVED 2026-08-31 (decision D2).** If, by the
time the queued job runs, availability is below the note's quantity
(external stock moved; the note is immutable — printed + signed), the job
is **all-or-nothing**: nothing is allocated, the step goes to `FAILED`,
and admins are alerted (§10.3). No partial allocation — an approved note's
quantity is never renegotiated. Resolution is re-stock-then-retry, or
`SYNC_ABANDONED` (§10.2). The queue is serialised per model, so two
concurrent syncs can never both take the last unit.

**Holder-mismatch pre-check — RESOLVED 2026-08-31 (decision D6b; the
parallel contract's G5). Gated, not advisory.** Before enqueuing, the
middleware does a synchronous holder read against the external system. It
rejects with `409 HOLDER_MISMATCH` (nothing enqueued) when the current
holder contradicts the action:
- **ENTREGA / ENTREGA PERMANENTE** — the picked asset is already `IN_USE`
  by someone other than the note's recipient (a silent steal).
- **DEVOLUCIÓN / PRÉSTAMO** return — the current holder ≠ the note's
  recipient (see §4.3).

The client shows a confirm dialog and re-calls with
`{ "acknowledgeHolderMismatch": true }` to proceed — the write is then
enqueued normally. A further holder change between the pre-check and the
queued job running is covered by that acknowledgement (the job proceeds).
Applies only where the note has a recipient user; Provider-return holder
semantics are deferred.

### 4.2 Reject sync ("Rechazar")

```
POST /api/v1/notes/{noteId}/items/{itemId}/reject-sync
```
```jsonc
// Request
{ "reason": "string" }  // mandatory
// Response 200 — step marked REJECTED, no external write performed
```
`reject-sync` is only valid while the step is `PENDING` — a deliberate
"this item should not be synced" decision made **before** any write. Once
`sync` has enqueued a write the resolution path is retry-or-abandon
(§10.2), not reject: a `reject-sync` on a `QUEUED` / `FAILED` step → `409`.

### 4.3 Return ("Devuelto" / "Recibido")

```
PUT /api/v1/notes/{noteId}/items/{itemId}/return
```
```jsonc
// Request — countables only need "quantity" when returning less than the
// full synced quantity; omit for a whole-item/whole-quantity return.
// "acknowledgeHolderMismatch" re-call after a 409 HOLDER_MISMATCH (§4.1).
{ "quantity": "integer | null", "acknowledgeHolderMismatch": "boolean | null" }

// Response 202 Accepted — the release is ENQUEUED (§10); step → QUEUED.
// Response 409 HOLDER_MISMATCH — synchronous pre-check: the asset's current
//   external holder ≠ the note's recipient. Nothing enqueued; re-call with
//   acknowledgeHolderMismatch:true to proceed (decision D6b, §4.1).
// On success (via GET /notes/{id}): releases what this item's sync (§4.1)
//   allocated. holder cleared; states_id → 33 (En stock) — the syncTargets
//   RETURN target. EXCEPTION (D6a): a record that was UNAVAILABLE
//   (broken/lost — a legitimate DEVOLUCIÓN) has its holder cleared but
//   states_id left as-is, never forced back to 33. Unrecoverable conflict
//   → step FAILED, resolvable by retry or RETURN_ABANDONED (§10.2).
```

### 4.4 Lost / not received ("No devuelto" / "No recibido")

```
PUT /api/v1/notes/{noteId}/items/{itemId}/lost
```
```jsonc
// Request
{ "reason": "string", "quantity": "integer | null" }  // reason mandatory,
                                                         // quantity same
                                                         // partial-batch
                                                         // meaning as §4.3
// Response 202 Accepted — ENQUEUED (§10); step → QUEUED.
// On success: holder cleared; states_id → 45 (En baja) — NOT 48 (Scrap),
//   and never back to 33/AVAILABLE. (syncTargets LOST target, E1a.)
// Unrecoverable conflict → step FAILED, resolvable by retry or
// <STEP>_ABANDONED (§10.2).
```

---

## 5. Notes, History, Approval

Backend-neutral replacement for `IHistoryService` — this is the biggest
single surface in this contract, since it absorbs everything
`SqliteHistoryService`/`RemoteDatabaseService` do today for
`NOTE_REPORT`/`NOTE_ITEM` and their subtype tables.

### 5.1 Create a note

```
POST /api/v1/notes
```
```jsonc
// Request — shape mirrors today's NoteReport + NoteReportItem closely,
// since that's a well-exercised, already-normalized domain shape (see
// CLAUDE.md's extensive schema history) — no reason to redesign it, just
// re-host it behind REST instead of a local DB write.
{
  "profileType": "ENTREGA" | "DEVOLUCIÓN" | "ENTREGA PERMANENTE" | "PRÉSTAMO"
               | "ENTREGA - PROVEEDOR" | "REMITO DE ENVÍO",
  "recipientName": "string", "recipientDni": "string", "recipientEmail": "string | null",
  // technician identity is NOT sent — the middleware derives and snapshots
  // it from the session token (§2.5); it still comes back on GET responses
  "sedeId": "integer",                    // the note's own Sede (source, for REMITO)
  "destinationSedeId": "integer | null",  // REMITO DE ENVÍO only — a catalog Sede id that
                                          // MUST map to a GLPI Location (E2b). No free-text /
                                          // custom destination, no destinationRecipients.
  "motivo": "string | null",              // or expected-return-date for Préstamo
  "areaEvento": "string | null",          // Préstamo only
  "failureCause": "string | null", "failureDetails": "string | null",  // Devolución+Falla only
  "providerId": "integer | null", "cuit": "string | null",
  "responsibleName": "string | null", "responsibleDni": "string | null",  // provider note
  "observations": "string | null",
  "items": [
    {
      "kind": "ASSET" | "COUNTABLE",
      "externalItemId": "string | null",      // asset only — see companion §6
      // type/brand/model: external ids + names, BOTH captured at selection
      // time and stored as an immutable snapshot (decision D5). id is
      // null-able for the "Genérico / Otro" case; name still carries the
      // configured generic label. The ids are load-bearing for countable
      // sync (§4.1) and countable reconciliation (§14), not decoration.
      "typeId": "string | null", "typeName": "string",
      "brandId": "string | null", "brandName": "string",
      "modelId": "string | null", "modelName": "string",
      "serialNumber": "string | null", "af": "string | null",          // asset only
      "externalStatusAtAddTime": "AVAILABLE|IN_USE|UNAVAILABLE | null", // asset only, snapshot
      "quantity": "integer | null",                                     // countable only
      "observations": "string | null"
      // "modifiesStock" / "modifiesStockReason" REMOVED (decision D4) —
      // stock is derived (Model Y, §3.4), so there is no counter to skip
    }
  ]
}

// Response 201
{ "id": "integer", "approvalStatus": "PENDING", "createdAt": "ISO-8601 datetime" }
```

A newly-created note always starts `PENDING` — server-enforced, not
client-settable, same as today's `NoteReport.approvalStatus` default.

**Create-time validation (server-enforced; see `contract-behaviors.md`
§B, §F). RESOLVED 2026-08-31, decision D6:**
- A note with zero items → `400` (min 1 item).
- **DEVOLUCIÓN — the only asset-state hard block:** an asset item whose
  current external state is `AVAILABLE` (in stock, held by no one) →
  `409 ASSET_NOT_IN_USE`, note not created. A user cannot "return" an
  asset that isn't out.
- **REMITO DE ENVÍO** — missing `destinationSedeId`, or one that has no
  GLPI `Location` mapping (E2b) → `422 SEDE_LOCATION_NOT_MAPPED`, note not
  created.
- **ENTREGA - PROVEEDOR** — a `motivo` with no `syncTargets.proveedor`
  entry (neither a `states_id` nor `NONE`) → `422
  PROVIDER_MOTIVO_NOT_CONFIGURED`, note not created. Both `422`s follow the
  "fail at creation, not at sign-time" principle — an already printed +
  signed note must never be un-syncable.
- Everything else at create time is a **soft `warnings[]` entry** (`201`,
  allowed; the client warns + confirms at item-add time, never blocks):
  - **DEVOLUCIÓN**, asset state `UNAVAILABLE` (broken / lost / scrapped) —
    "GLPI marks this broken/lost; the return will be recorded, its state
    stays `UNAVAILABLE`." (Returning dead equipment is legitimate.)
  - **DEVOLUCIÓN / PRÉSTAMO**, current holder ≠ `recipient`.
  - **ENTREGA / ENTREGA PERMANENTE**, the picked asset is already `IN_USE`
    by someone, or the recipient already holds an asset of the **same
    type** (computed from `GET /assets?holder=` — §3.5).
- The authoritative holder check happens again at **sync time** and is
  **gated** there, not advisory — see §4.1 / §4.3.

### 5.2 Get / list / filter

```
GET /api/v1/notes/{id}
GET /api/v1/notes?profileTypes=...&itemTypes=...&sedes=...&authorSearch=...&recipientSearch=...&dateFrom=...&dateTo=...&approvalStatuses=...&syncStatuses=...&returnStatuses=...&page=&size=
```

The filter query params mirror today's `HistoryFilter` fields closely
(profileTypes, item type/brand/model, sedes, author/recipient search,
date range, approval status). Per-item status stays independently tracked
per flow step (§4.0), not merged into one state machine (RESOLVED
2026-08-28).

**Per-step status filters — RESOLVED 2026-08-31 (decision D7).** One param
per step kind, not one combined `externalStatuses`:
- **`syncStatuses`** — any of `PENDING` / `QUEUED` / `SYNCED` / `REJECTED`
  / `FAILED` / `SYNC_ABANDONED` (§4.0 vocabulary).
- **`returnStatuses`** — any of `PENDING` / `QUEUED` / `RETURNED` / `LOST`
  / `FAILED` / `RETURN_ABANDONED`.

Comma-separated multi-select, **any-of within** a param, **and-across**
with the other filters (matches `contract-behaviors.md` G1). A note
matches if **any** of its items has a step of that kind in one of the
listed statuses. A future third step kind → a third param (additive).

`GET /notes/{id}` returns the full shape from §5.1 plus:
```jsonc
{
  "approvalStatus": "PENDING" | "APPROVED" | "REJECTED",
  "rejectionReason": "string | null",
  "items": [ /* ...as above, plus current per-item state where relevant */ ]
}
```

List responses return a **summary** shape (not the full item list) for
table-row rendering — mirrors today's `NoteReport` summary vs. full-detail
split (`mapSummary()` vs. `getById()`). Exact summary field list TBD, but
should include everything today's History table columns show (asset/
countable counts, approval-status color, Sede, Motivo, etc.).

### 5.3 Update / approve / reject

```
PUT /api/v1/notes/{id}/approve
PUT /api/v1/notes/{id}/reject
```
```jsonc
// reject request body
{ "reason": "string" }  // mandatory, mirrors today's rejection-reason requirement
```

**CORRECTED 2026-08-27**: approving a note does **not** trigger any §4
write-back to the external system. It only unlocks the per-item
sync/return actions in §4 — those remain separate, manual clicks on the
item card, same as today's existing behavior (see §4's header note and
companion doc §4.6 for the full correction).

**Approval moves no stock** (RESOLVED 2026-08-31, decision D4 — Model Y,
§3.4). There is no middleware stock counter to decrement/increment, no
approval-time stock side-effect, no `applied` flag for stock, and no
`STOCK_WOULD_GO_NEGATIVE`. Stock is a live derived count of AVAILABLE
records in the external system; it changes **only** as a consequence of a
`sync` step actually assigning / relocating real records (§4.1, §10). A
shortage surfaces there — the queued job goes `FAILED` (§10.1, §L4) —
never at approval. On reject, the endpoint records the reason.

Sede-scoped permission enforcement (today's `hasPermission(Permission,
Integer noteSedeId)` — a plain ADMIN can only approve their own Sede's
notes, SUPERADMIN bypasses) applies here server-side — `403` with a clear
reason if the acting user's role/Sede doesn't authorize this specific note.

Per-item sync/return/loss actions (Préstamo, Provider returns, and now
GLPI sync for both item kinds) moved to §4 — see §4.1-4.4. (An earlier
draft of this document had a separate, narrower `§5.4 Per-item return/
loss actions` here, asset/countable-branching and missing the sync half
entirely; superseded by §4's unified version, not left duplicated.)

---

## 6. Audit

Backend-neutral, server-recorded successor to today's `AUDIT_STOCK` /
`AUDIT_ITEM_STATUS` / `AUDIT_ADMIN_ACTION` / `AUDIT_LOGIN` tables
(companion doc §4.6). **The app never writes an audit row directly** — every
audit row is a side effect the middleware records itself when it performs
an action (approval, item write-back, login, admin config change). The app
only ever *reads* audit history.

```
GET /api/v1/audit/item-status?noteId=&externalItemId=&page=&size=
GET /api/v1/audit/login?username=&page=&size=
GET /api/v1/audit/admin-actions?actor=&targetType=&page=&size=
```

Exact schema TBD (companion §4.6 says "likely a close cousin of
AUDIT_STOCK/AUDIT_ITEM_STATUS rather than a brand-new shape") — listed here
as read-only endpoints so the desktop app's future Auditoría-equivalent
screen (if reintroduced — see CLAUDE.md's history of this feature being
built, descoped, and partially rebuilt) has somewhere to read from.

---

## 7. Users, Roles, Permissions, Sedes

Backend-neutral successor to `IUserRoleService` + the `Sede` half of
`IEquipmentService` (there is no more `SEDE_SHIPPING_INFO` — E2b, §7.4).
**Read-only from the app's side, by design** — mirrors today's explicit
"no in-app CRUD for APP_USER/ROLE_PERMISSION, direct-SQL-only" convention
(companion doc carries this forward; someone still administers users/roles/permissions
directly against the middleware's own store, not through the desktop app).

### 7.1 Current caller

```
GET /api/v1/me
```
```jsonc
// Response 200
{
  "username": "string",
  "role": "USER" | "ADMIN" | "SUPERADMIN",
  "sedeId": "integer | null",
  "displayName": "string",
  "permissions": ["APPROVE_NOTES", "SYNC_EXTERNAL", "VALIDATE_RETURNS"],
  "registered": true,
  "bypassGroupCheck": false,
  "glpiTokenSet": true            // F1 — whether this user has a personal GLPI token stored
}
```

Same data the login response (§2.1) carries, re-fetchable without a fresh
login — lets the app refresh its UI gate after a mid-session role / Sede /
permission change. Identity is the token subject, never a parameter (§2.5).
The client gate is **cosmetic**; every endpoint enforces its own permission
server-side regardless of what the app renders (§7.5).

### 7.1.1 Personal GLPI token (F1 — per-user write identity)

```
PUT /api/v1/me/glpi-token
```
```jsonc
// Request
{ "token": "string" }   // the caller's own GLPI user_token
// Response 204 — stored (encrypted) in APP_USER.glpi_token_encrypted
```

**RESOLVED (Tier 4 F1) — per-user, not a shared service account.** The
middleware holds one shared GLPI `App-Token` (deploy config); each
technician configures **their own** `user_token` here. Every external
write (`sync` / `return` / `lost`) is then made as that technician in
GLPI — it lands in GLPI's native history under their name, and still
stamps the movement-reason field (N3) with the note ref.

- **Write-only.** The token is never returned by any endpoint; `GET /me`
  only reports `glpiTokenSet` (boolean).
- **No token → no external writes.** A technician who hasn't set one gets
  `409 GLPI_TOKEN_NOT_SET` on `sync` / `return` / `lost` (§4.1, §4.3,
  §4.4). Read endpoints (catalog, search, availability, `GET /notes`) are
  unaffected — those use the shared `App-Token`.
- Any authenticated session may set **its own** token; there is no
  admin-sets-another-user's-token path (that would defeat the "acts as the
  real technician" point).

### 7.2 Look up a user

```
GET /api/v1/users/{username}
```
```jsonc
{ "username": "string", "role": "USER"|"ADMIN"|"SUPERADMIN",
  "sedeId": "integer | null", "registered": true, "bypassGroupCheck": false }
```

### 7.3 Permissions

```
GET /api/v1/roles/{role}/permissions
```
```jsonc
["APPROVE_NOTES", "SYNC_EXTERNAL", "VALIDATE_RETURNS"]
```

**RESOLVED 2026-08-31 (decision D3).** The enum is **exactly these three**:

| Permission | Gates |
|------------|-------|
| `APPROVE_NOTES` | `approve` / `reject` a note (§5.3) |
| `SYNC_EXTERNAL` | a `SYNC`-kind step: `sync`, `reject-sync`, and `retry` / `abandon` on a failed sync step (§4.1–4.2, §10.2). Both item kinds. Renamed from `SYNC_GLPI` per §1 #1. |
| `VALIDATE_RETURNS` | a `RETURN`-kind step: `return`, `lost`, and `retry` / `abandon` on a failed return step (§4.3–4.4, §10.2) |

Retired (their screens / features no longer exist in the middleware
world): `MANAGE_TYPES` / `MANAGE_BRANDS` / `MANAGE_MODELS` (catalog is
external, read-only), `MANAGE_STOCK` (no direct stock edit — approval
side-effect only), `MANAGE_PROVIDERS` / `MANAGE_SEDES` (Provider & Sede
read-only from the app, §7.4), `EDIT_SN_VALIDATION` (feature removed),
`EDIT_SMTP_CONFIG` / `EDIT_GLPI_CONFIG` / `EDIT_AD_CONFIG` /
`EDIT_AF_FORMAT_CONFIG` (no in-app config/secrets UI — §8/§9),
`OVERRIDE_PROFILE_FIELDS` (technician identity is token-derived, §2.5).

**No `SUPERADMIN`-only permission exists.** `SUPERADMIN` = `ADMIN` whose
actions and reads are **not** Sede-fenced (approve / sync / return for any
Sede; `GET /sync-queue` and `GET /audit/*` unscoped). A plain `ADMIN` is
implicitly scoped to their own `sedeId` everywhere (§7.5 H2–H4).

### 7.4 Sedes

```
GET /api/v1/sedes
```
```jsonc
{ "results": [ { "id": 3, "name": "Campus Norte", "locationMapped": true } ] }
```

The middleware keeps its own `SEDE` catalog (id + name) — the target of
`APP_USER.sedeId`, `NOTE_REPORT.sedeId`, and a Remito's
`destinationSedeId`. **Read-only from the app** (decision D3): no in-app
add/edit/delete; administered out-of-band like `APP_USER` / roles.
(Providers, §7.x, are the same — read-only.)

**E2a — a Sede *is* a GLPI `Location`.** The GLPI adapter holds a
`sede → locations_id` map (middleware-internal config, §8). GLPI Locations
are a **tree**, so "at a Sede" = that node or any descendant
(`searchtype = under`). The map drives:
- per-Sede countable availability (§3.3) — filter `locations_id` under the
  Sede's Location subtree;
- Remito relocation (§4.1) — `sync` sets the item's `locations_id` to the
  destination Sede's Location;
- the printed Remito's destination address — read server-side from the
  GLPI Location's own address fields.

`locationMapped` tells the app which Sedes may be a Remito destination; an
unmapped one is rejected at `POST /notes` (`422 SEDE_LOCATION_NOT_MAPPED`,
§5.1).

**E2b — no middleware `SEDE_SHIPPING_INFO`.** GLPI's `Location` holds the
address; the middleware reads it. There is **no** middleware shipping-info
table, **no** `GET /sedes/{id}/shipping-info` endpoint, **no** free-text /
custom Remito destination, and **no** `destinationRecipients` in the v2.0
payload — a deferred desktop feature (Sede-select address auto-fill +
recipient inputs), built later.

### 7.5 Authorization — acceptance criteria

Backend-neutral restatement of the parallel contract's A1–A3 / H1–H5, which
this contract adopts wholesale:

- **A1** — no token, or an invalid / expired one, on any call → `401` with
  **no state change**.
- **A2** — a valid token + `GET /me` → returns that user's `username`,
  `role`, `sedeId`, `permissions[]`. Identity is never read from a request
  body, on this or any other endpoint (§2.5).
- **A3** — two different users' sessions acting → each resulting audit row
  (§6) names the **real acting user**, never a shared account.
- **H1 (deny-by-default)** — a permission not granted to the caller's role
  → the guarded action returns `403`. A newly added action with no grant
  row in the permission store is denied to **everyone** until a row is
  added.
- **H2** — a plain `ADMIN` whose Sede ≠ the note's Sede → `approve` /
  `reject` / `sync` / `return` / `lost` on that note returns `403`; the
  note stays **readable** (`GET /notes/{id}` still succeeds).
- **H3** — a `SUPERADMIN` performing the same cross-Sede action → allowed.
- **H4** — there is **no `SUPERADMIN`-only permission** (decision D3).
  `SUPERADMIN` differs from `ADMIN` only by **Sede-fence bypass**: a plain
  `ADMIN`'s every action and list read is implicitly scoped to their own
  `sedeId` (approve / sync / return / `GET /sync-queue` / `GET /audit/*`);
  a `SUPERADMIN`'s is not. H2 / H3 are the concrete cases.
- **H5** — every check above is enforced **server-side**: bypassing the
  desktop client and calling the API directly still enforces A1–H4. This
  is the whole point of moving off direct-DB access.

### 7.6 Directory search (note recipients)

**RESOLVED 2026-08-31 (decision D8).** Replaces today's
`IADService.search()` — the middleware queries the org's people directory
(AD, via the IdP / AD adapter, server-side); the app never talks to AD.

```
GET /api/v1/directory/users?name={partial}&dni={partial}&username={partial}&page=&size=
```
```jsonc
// Response 200
{ "results": [
  { "username": "string", "fullName": "string",
    "email": "string | null", "dni": "string | null" }
] }
```
- Params mirror today's three typed fields, **AND-combined** (every
  supplied param must match). At least one param, or a minimum length, is
  required — else `400`.
- Empty `results` → `200` (not `404`). Directory unreachable → `5xx`,
  distinct from an empty result.
- **Any authenticated session**; no special permission; **not**
  Sede-scoped (a recipient can be anyone in the org). Results capped
  server-side.
- Does **not** return the person's assets — that's `GET /assets?holder=`
  (§3.5), a separate call the app makes with the picked `username`.

---

## 8. Global configuration

Single-org config store (per this session's decision — not multi-tenant).
Replaces `app-config.json`'s non-secret settings for the values called out
below; the rest of `app-config.json` (things with no server-side meaning,
like window-sizing constants) presumably stays a local file — this
contract only covers what's genuinely "organization policy," not app UI
tuning.

```
GET /api/v1/config
```
```jsonc
{
  "afFormat": { "enabled": true, "prefix": "IT", "separator": "-" } | { "enabled": false },
  // "enabled": false covers "maybe some organizations don't implement A/F"
  // no "smtp" block — SMTP is middleware-internal now (alert emails only,
  // §10.3); the in-app SMTP settings screen is removed (Decision 6 / D3)
  "noteItemLimit": "integer",
  "returnableMotivosProveedor": ["Garantía", "Reparación"],
  "failureTriggerMotivo": "Falla",
  "motivoOptions": { "entrega": ["..."], "finDeContrato": ["..."], "proveedor": ["..."], "devolucion": ["..."] },
  "fallaOptions": ["..."],
  "generic": {                             // §3.1 fallback (D5b; shape revised by E1b)
    "label": "Genérico / Otro",
    "manufacturerId": "string | null",     // one global generic manufacturer (the "generic brand")
    "typeIds":  { "Computer": "string | null", "Peripheral": "string | null" /* … per itemtype */ },
    "modelIds": { "Computer": "string | null", "Peripheral": "string | null" /* … per itemtype */ }
  }
  // may grow a "branding": { "appName", "logoUrl", "themeTokens": {...} }
  // block later (§1.2) — additive, non-breaking
}
```

This is the per-organisation policy surface (§1.2 — instance-per-org) — the
values the **desktop app** consumes. A second organisation runs its own
deployment with its own `config`.

**Middleware-internal config — NOT in `GET /config`.** Deploy-time settings
the app never sees, held in the middleware's own configuration
(`application.yml` / adapter properties):
- **GLPI adapter** — the E1a `states_id` → bucket map, `syncTargets`
  (incl. `syncTargets.proveedor`, §4.1), the Sede → GLPI `Location` id map
  (§7.4), per-instance field-id resolution (N3).
- **Reconciliation** — `reconciliation.orphanCutoff`,
  `reconciliation.suppressApproximateOrphans` (§14.4).
- **IdP** — `idp.issuer-uri`, `idp.client-id`, `idp.username-claim`,
  `idp.groups-claim`, `idp.allowed-group-name` (§2).
- **SMTP** — `smtp.host` / `smtp.port` / `smtp.senderAddress` /
  `smtp.password`, used **only** for §10.3 queue-failure alert emails. No
  app-facing surface at all (Decision 6); the password is never in any
  config response (§9).
- **Secrets** — the GLPI `App-Token`, per-user GLPI `user_token`s
  (`APP_USER.glpi_token_encrypted`, §7.1), and the IdP client details:
  never in any config response (§9).

```
PUT /api/v1/config
```
**`SUPERADMIN` role-gated (Decision 6)** — checked directly against the
caller's `role`, **not** a permission. The `Permission` enum stays at
three (§7.3); **no `EDIT_CONFIG` is added**. `smtpPassword` is no longer a
`PUT /config` field (SMTP is middleware-internal — above). No desktop
Configuración screen exists for this (RESOLVED 2026-08-28) — config
administration is a middleware-only admin surface.

**No `/me/preferences`.** The per-user UI toggles the desktop app has today
(clear the form on submit, close the tab on submit) stay **desktop-local**
— the app stores them on the client, never round-tripped through the
middleware (Decision 6). Nothing per-user lives in `config` or any other
endpoint.

---

## 9. Secrets / credential custody

**Middleware-held, never app-held:**
- The GLPI **`App-Token`** (one, deploy config) and the middleware's AD /
  directory service credentials — used for every read, and as the outer
  token for writes (F1).
- The **SMTP password** — deploy config, used only for §10.3 alert emails.
  Dropped from `APP_CONFIG` and `PUT /config` (Decision 6).
- The IdP client details (§2) — deploy config.

None of these are returned by any endpoint.

**RESOLVED 2026-08-28**: the desktop app gets **no config/secrets admin
endpoint at all**. `SettingsController`'s in-app UI is dropped, not
re-pointed — nothing that could be reverse-engineered out of the client
binary should exist there.

**One deliberate exception — the per-user GLPI `user_token` (F1, §7.1.1).**
`PUT /me/glpi-token` lets a technician store **their own** GLPI token
(`APP_USER.glpi_token_encrypted`, encrypted at rest). It is write-only
(never read back; `GET /me` reports only `glpiTokenSet`), sets only the
caller's own token, and is a personal credential — not organization
config. This is not a re-opening of the "no in-app secrets UI" decision:
it's each user provisioning their own write identity, the same way they'd
paste a token into GLPI itself.

Still **not** decided:
- Storage/rotation mechanics for the middleware's own credentials (its
  GLPI `App-Token`, its directory bind account, the SMTP password, the
  at-rest encryption key for `glpi_token_encrypted`) — a deployment/ops
  concern, still unaddressed (F2).

---

## 10. External write queue

**RESOLVED 2026-08-31 (D2 + E3); durability + scope confirmed 2026-09-07
(Decisions 3 + 7). In the v2.0 first release — not deferred.** Every
external write — item `sync` (§4.1), `return` (§4.3), `lost` (§4.4), and a
reconciliation `repush` (§14.2) — is **enqueued on the middleware, not
executed in the request**. The action endpoint checks permissions (§7.5),
the flow state, and the caller's GLPI token (§7.1.1), enqueues a job, and
returns `202 Accepted` with the step now `QUEUED`. **Note approval itself
enqueues nothing** (D4 / §5.3) — only a manual step action or a `repush`
ever produces a write.

**Three distinct concepts, kept separate:**

| | |
|---|---|
| **domain item status** (`REJECTED` / `SYNC_ABANDONED` / …) | the permanent flow-step record — never rewound |
| **`glpiStatus`** (`PENDING` → `SYNCED` \| `FAILED`) | a mirror flag: does the external record reflect this step yet |
| **the queue row** (§10.0) | the pending job that will try to make `glpiStatus` true |

**No optimistic `glpiStatus`** (Decision 7) — it stays `PENDING` until the
worker confirms the external write landed; the app never shows a step as
`SYNCED` before that.

### 10.0 Durability — `EXTERNAL_WRITE_QUEUE` (Decision 7)

One row per owed external write:
`{ id, model_key, command (write payload — itemtype, external id(s), target
states_id / users_id / locations_id, note ref), status (PENDING /
IN_PROGRESS / DONE / FAILED), attempt_count, last_error, next_retry_at,
lease_until }`.

- A **`@Scheduled` poller** claims due rows, **serialised per `model_key`**
  (a per-model lock — two jobs never race for the same external stock),
  taking a **lease** (`lease_until`). A crash mid-job lets the lease expire
  and another poller reclaims the row — **restart-safe**, no lost or
  double-run jobs.
- Before writing, the worker **reads the live external record and
  precondition-checks it** against what the job expects. On a mismatch it
  raises a **§14 reconciliation drift row** and does **not** blind-write
  (ties D6 / E1a Q6).
- All enqueuing paths (`sync` / `return` / `lost` / `repush`) create rows
  the same way — no separate code path per note type or item kind.

### 10.1 Processing

- One worker consumes the queue, **serialised per model**, so two jobs
  never race for the same external stock. Each job is **all-or-nothing** —
  allocate / release all N records or fail, never partial.
- **Transient failure** (external system unreachable, `5xx`, network) →
  automatic retry with backoff; the step stays `QUEUED`; no alert.
- **Unrecoverable conflict** (the approved action is genuinely impossible
  now — external stock moved below the note's quantity, or an asset's
  external state isn't what the action requires; the note itself is
  immutable) → step → `FAILED`, **no timed auto-retry**, admins alerted
  (§10.3).
- Enqueue is idempotent via the flow state guard (§11.1): a step already
  `QUEUED` or past it can't be re-enqueued (`409`).

### 10.2 Resolving a FAILED step

The note is never edited. Two admin actions on a `FAILED` step, both
Sede-scoped (§7.5) and audited (§6):

```
POST /api/v1/notes/{noteId}/items/{itemId}/retry     // -> step QUEUED
POST /api/v1/notes/{noteId}/items/{itemId}/abandon    { "reason": "string" }
```

- **retry** — after the cause is fixed externally (e.g. stock
  replenished). Re-enqueues the same job. `FAILED → QUEUED`. **Manual
  only** — no automatic retry when stock happens to recover (deliberate:
  keeps the decision with a human).
- **abandon** — terminal. `reason` mandatory. `FAILED → SYNC_ABANDONED` /
  `RETURN_ABANDONED`. The note stays valid; only that one item's external
  write is given up. Sibling items and the note's `approvalStatus` are
  unaffected.

Both target the item's single currently-`FAILED` step (steps are
sequential — at most one is in flight per item).

### 10.3 Alerting

A step entering `FAILED` alerts through **two channels**:
- an in-app **"Cola de sincronización"** view + a nav-badge count,
  **Sede-scoped** — a plain `ADMIN` sees their Sede's failed jobs, a
  `SUPERADMIN` sees all;
- an **email** to the responsible admin(s).

`QUEUED` and successfully-completed jobs raise no alert.

```
GET /api/v1/sync-queue?status=FAILED&sedeId={id}&page=&size=
```
Feeds the in-app view — failed (and optionally in-flight) jobs across all
notes, each with note id, item, step, requested vs. available, timestamp,
last error. Sede-scoped per the caller's role.

### 10.4 UI feel (desktop app)

"Sincronizar" / "Devuelto" / etc. show the step as **"En cola"** on click.
The app polls `GET /notes/{id}` for a few seconds so the common (fast,
no-conflict) case flips to `SYNCED` quickly once the worker confirms it —
but the card **never shows `SYNCED` before `GET /notes` reports it** (no
optimistic flip, Decision 7). Still `QUEUED` after the poll window → the
card shows "en proceso — ver cola". `FAILED` and `<STEP>_ABANDONED` are
each a **visually distinct** state on the item card, separate from `SYNCED`
and from `REJECTED`.

---

## 11. Error handling conventions

Every non-2xx response uses one envelope:

```jsonc
{
  "error": {
    "code": "string",       // stable, machine-readable — e.g. "SEDE_MISMATCH", "INSUFFICIENT_STOCK"
    "message": "string",    // human-readable, Spanish (matches this app's UI-language convention)
    "details": { }          // optional, endpoint-specific extra context
  }
}
```

Standard HTTP status code usage:
- `400` — malformed request (validation failure on input shape)
- `401` — missing/invalid/expired session token
- `403` — authenticated, but not authorized for this specific action
  (permission missing, or Sede mismatch)
- `404` — resource doesn't exist
- `409` — conflict (e.g. a step action attempted from the wrong state).
  Stock shortage is **not** a `409` — approval moves no stock (§3.4,
  decision D4), and the §4 over-allocation race surfaces as a queued-job
  `FAILED` outcome (§10), not a synchronous response.
- `429` — rate-limited. Login-attempt throttling now lives in the IdP
  (§2.1); this covers any per-endpoint limits the middleware itself imposes.
- `5xx` — middleware or upstream (GLPI/AD/SQL Server) failure. Per §0's
  hard-dependency stance, the app shows this plainly rather than degrading
  — no silent retry-and-hope loop.

### 11.1 Idempotency

Every mutating endpoint is safe to retry (`contract-behaviors.md` §K2):

- `approve` / `reject` / `sync` / `reject-sync` / `return` / `lost` /
  `retry` / `abandon` — guarded by the flow model's own state (§4.0). A
  transition that already happened is not a valid transition again → `409`
  (or a no-op). No client-supplied key needed. The queued writes (`sync` /
  `return` / `lost`) return `202`; re-issuing while the step is `QUEUED`
  is `409`. (Approval has no stock side-effect to guard — §3.4.)
- `POST /notes` (create) — accepts an optional `Idempotency-Key` request
  header; a retry with the same key returns the original `201` result
  without creating a second note. This is the one operation a state guard
  can't cover (a dropped create response).

---

## 12. Versioning

`/api/v1/...` prefix on every path in this document. No deprecation
policy defined yet — fine for now, since there are zero real clients yet.
Revisit once the desktop app is actually being built against a `v1` and a
breaking change is on the table.

---

## 13. Open items (collected)

1. RESOLVED 2026-08-31 (decision D2) — over-allocation on a countable's
   sync is all-or-nothing: the queued job (§10) goes to `FAILED`, admins
   are alerted, and the note (immutable once printed + signed) is never
   renegotiated. No partial allocation. Resolve by re-stock-then-retry or
   `SYNC_ABANDONED`. See §4.1, §10.
2. RESOLVED 2026-08-28 — per-item status stays independently tracked, not
   merged into one state machine, now generalized into a config-driven
   flow model (ordered steps, selected by a `(profileType, motivo)`
   predicate rather than hardcoded per-type conditionals). See §4.0 for
   the full model, §5.2 for the filter-query consequence.
3. Removed, 2026-08-27 — was "partial-failure shape when approval's
   write-back fails partway through multiple items." No longer applies:
   approval no longer performs any write-back at all (§5.3), so there's
   no multi-item write to partially fail. §4.1's single-item race
   condition (item 1 above) is the only remaining failure-mode question
   in this area.
4. RESOLVED 2026-08-31 (decision D3) — `Permission` enum is exactly
   `APPROVE_NOTES` / `SYNC_EXTERNAL` / `VALIDATE_RETURNS`; all others
   retired; no `SUPERADMIN`-only permission (SUPERADMIN = ADMIN without
   the Sede fence). §4.0 step kind `GLPI` → `SYNC`. See §7.3, §7.5 H4.
5. RESOLVED 2026-08-28 — no Configuración/secrets-entry UI in the desktop
   app at all. Config/secrets administration moves entirely out-of-band
   to a middleware-only admin surface. See §8/§9 for the resolved framing.
6. **OPEN (F2)** — secret storage / rotation mechanics for the middleware's
   own credentials (GLPI `App-Token`, directory bind account, SMTP
   password, the at-rest key for `glpi_token_encrypted`). Deployment/ops,
   still unaddressed. §9.
7. RESOLVED 2026-08-31 (D2 + E3); **durability + scope confirmed 2026-09-07
   (Decisions 3 + 7)** — §10 is the external write queue, **in the v2.0
   first release**. Durable as an `EXTERNAL_WRITE_QUEUE` table; a
   `@Scheduled` poller claims rows serialised per `model_key` under a lease
   (restart-safe); the worker precondition-checks the live record and
   raises a §14 drift row on mismatch instead of blind-writing; no
   optimistic `glpiStatus`. `repush` (§14.2) enqueues the same way;
   approval enqueues nothing.
8. RESOLVED — all three sub-items folded 2026-09-08: (a) GLPI status
   buckets confirmed (E1a — §3.2); (b) Sede = GLPI `Location`, adapter
   holds the `sede → locations_id` map, **no middleware `SEDE_SHIPPING_INFO`**
   (E2a / E2b — §7.4); (c) generic ids are the per-itemtype
   `config.generic.{manufacturerId,typeIds,modelIds}` block (E1b — §3.1 /
   §8), all `null` until the GLPI admin creates the rows.
9. RESOLVED (F1, 2026-09-07) — middleware → GLPI writes act as the real
   technician's own `user_token` (`PUT /me/glpi-token`,
   `APP_USER.glpi_token_encrypted`); shared `App-Token` for reads; no token
   → `409 GLPI_TOKEN_NOT_SET` on writes. No shared-service-account write
   fallback. §7.1.1, §2.6.
10. RESOLVED 2026-08-31 (decision D6a) — only `AVAILABLE` hard-blocks a
    DEVOLUCIÓN. `UNAVAILABLE` (broken/lost) is a soft warning — the return
    is recorded and the asset's state stays `UNAVAILABLE`. §5.1, §4.3.
11. RESOLVED 2026-08-31 (decision D6b) — sync/return holder-mismatch is
    **gated**: synchronous `409 HOLDER_MISMATCH`, re-called with
    `acknowledgeHolderMismatch: true`. Not advisory. §4.1, §4.3.
12. RESOLVED 2026-08-31 (decision D6c) — added `GET /assets?holder={username}`
    (§3.5); the ENTREGA same-type-already-held warning is computed
    client-side from it.
13. RESOLVED 2026-08-31 (decision D8) — `GET /api/v1/directory/users`
    (§7.6) replaces `IADService.search()` for the recipient picker.
14. RESOLVED 2026-09-07/08 (Tier 3/4 + v2.0 scope fold) —
    (a) **N1**: countables are GLPI `Peripheral` only for v2.0; `Consumable`
    deferred (§3.3);
    (b) **N2**: catalog covers all 9 itemtypes — Core 5 direct, the 4
    custom assets (Multimedia / AudioEquipment / Security / Misc) in scope
    but each gated on a per-itemtype investigation vs the real instance
    (§3 intro);
    (c) **E2b**: Remito destination = a catalog Sede that maps to a GLPI
    `Location` (`destinationSedeId`), no free text / custom destination, no
    `destinationRecipients`; unmapped Sede → `422 SEDE_LOCATION_NOT_MAPPED`
    at `POST /notes` (§5.1 / §7.4);
    (d) **Provider `syncTargets`**: Garantía → `43`, Reparación → `42`,
    Devolución de préstamo → `45`, Otro → `42`; unmapped Motivo →
    `422 PROVIDER_MOTIVO_NOT_CONFIGURED` (§4.1 / §5.1);
    (e) **Decision 6**: `APP_CONFIG` stays; SMTP is middleware-internal
    (dropped from `GET`/`PUT /config`); `PUT /config` is `SUPERADMIN`
    role-gated (no `EDIT_CONFIG`); no `/me/preferences` (§8 / §9).

**Still open:** F2 (item 6). Provider "Devuelto a proveedor" dedicated GLPI
state (optional — default maps to `45`). Custom-asset field/workflow
investigation vs dev GLPI (blocks the custom-asset adapter path only).
`GET /Location` → the Sede → Location map (GLPI-admin data).

---

## 14. Reconciliation (drift detection)

**RESOLVED 2026-08-31 (D9); redesigned for v2.0 2026-09-07 (Decision 8) —
full rationale + GLPI-log mechanics in `reconciliation-endpoint.md`.** The
dual-SSOT model (§3.4 — external system authoritative for stock/state, app
note history authoritative for assignments) only holds if the two agree.
Reconciliation is the read-only check that surfaces where they don't. It
**never auto-fixes** — which side is right is a human call.

**In the v2.0 first release.**

**Scope — "scope (b)", two passes:**
- **(i) Per-unit, everything the app has a direct claim on:** serialized
  assets (matched via `externalItemId` on the note item) and
  currently-assigned non-serialized countable units (matched via the
  external ids the §10 worker records on `NOTE_ITEM_RETURN_ALLOCATION` at
  assignment). Each checked for `states_id` bucket, holder, and location.
- **(ii) Per-(category, Sede) aggregate** over *unassigned* non-serialized
  countables (individually untrackable — no serial, identical rows):
  `expected AVAILABLE = N(total in category+Sede) − K(assigned per open
  note)` vs `actual AVAILABLE` → one `COUNT_MISMATCH` per (category, Sede)
  with a limbo breakdown.

Unlike D9's original assumption, there **is** pre-app history in the
external system — see §14.4 for how legacy `ORPHAN_ASSIGNMENT` noise is
kept out of the default view.

**Expected state is derived on-the-fly** from the latest SYNCED note-item
action for each unit (ENTREGA → holder + IN_USE + note Sede's location;
PRÉSTAMO → holder + `34`; DEVOLUCIÓN/return → no holder + AVAILABLE; LOST →
no holder + `45`; REMITO → destination location). **No `ASSET_PROJECTION`
table** (`reconciliation-endpoint.md §6`).

**Runs:** nightly scheduled + `POST /api/v1/reconciliation/run` on-demand.
**No per-sync re-check** — the §10 queue precondition-checks the external
record before every write (§10.1), so same-day drift surfaces there first.
Every response carries a `scannedAt`.

**Sede-scoped** (§7.5): a plain `ADMIN` sees / acts on their own Sede's
rows only; `SUPERADMIN` all. A nav-badge count (excludes pre-cutoff rows —
§14.4), like the sync-queue badge.

### 14.1 Reads

```
GET /api/v1/reconciliation/drift
      ?sedeId=&driftKind=&acknowledged=&includePreCutoff=&page=&size=
```
One feed, mixed row shapes discriminated by `target.kind`.

```jsonc
{
  "scannedAt": "ISO-8601",
  "rows": [
    {                                   // per-unit row
      "id": "string",
      "driftKind": "STATE_MISMATCH" | "HOLDER_MISMATCH" | "LOCATION_MISMATCH"
                 | "ORPHAN_ASSIGNMENT" | "GLPI_CONTRADICTION",
      "target": {
        "kind": "ASSET_UNIT",
        "externalItemId": "string", "itemtype": "Computer",
        "serialNumber": "string",
        "typeName": "...", "brandName": "...", "modelName": "..."
      },
      "sedeId": 3,
      "expected": { "holder": "maria", "stateBucket": "IN_USE",
                    "sedeLocationId": 91, "fromNoteId": 412, "syncedAt": "..." }
                  | null,               // null for ORPHAN_ASSIGNMENT / GLPI_CONTRADICTION
      "actual":   { "holder": "juan", "stateBucket": "IN_USE", "locationId": 91 },

      // ORPHAN_ASSIGNMENT only:
      "assignmentDate": "2026-09-08 12:51:57",
      "assignmentDateSource": "LOG" | "DATE_MOD",
      "assignedBy": "jdoe (7)" | null,     // GLPI display name (id) of who set the holder
      "preCutoff": false,

      "acknowledged": { "by": "...", "at": "...", "reason": "..." } | null,
      "detectedAt": "..."
    },
    {                                   // per-(category, Sede) aggregate row
      "id": "string",
      "driftKind": "COUNT_MISMATCH",
      "target": {
        "kind": "CATEGORY_SEDE",
        "categoryKey": "Peripheral/headset",
        "typeName": "...", "brandName": "...", "modelName": "...",
        "sedeId": 3
      },
      "sedeId": 3,
      "expectedAvailable": 40, "actualAvailable": 37, "delta": -3,
      "limbo": { "enTransito": 1, "enReparacion": 1,
                 "stateZero": 1, "assignedNoNote": 0 },
      "acknowledged": { ... } | null,
      "detectedAt": "..."
    }
  ]
}
```

**Drift kinds:**

| kind | meaning |
|---|---|
| `STATE_MISMATCH` | unit's `states_id` bucket ≠ expected |
| `HOLDER_MISMATCH` | unit's holder ≠ expected (covers "user returned a unit, GLPI still shows it on them") |
| `LOCATION_MISMATCH` | unit's location ≠ expected Sede's GLPI Location |
| `COUNT_MISMATCH` | per-(category, Sede) `expectedAvailable` ≠ `actualAvailable` |
| `ORPHAN_ASSIGNMENT` | external asset has a holder but no open note explains it — §14.4 |
| `GLPI_CONTRADICTION` | GLPI's own fields disagree — `states_id` says AVAILABLE but a holder is set, or vice versa (E1a Q6). Availability is taken from `states_id`, holder from `users_id`; the disagreement is flagged, not silently resolved. |

`USER_COUNT_MISMATCH` and `STALE_RETURN` from the D9 draft are gone —
assigned countable units are now checked individually as `HOLDER_MISMATCH`
(pass (i)), and a stale return is a `STATE_MISMATCH`.

### 14.2 Resolving a drift row

- **`POST /api/v1/reconciliation/{id}/repush`** — for `STATE_MISMATCH` /
  `HOLDER_MISMATCH` / `LOCATION_MISMATCH` only. Re-asserts `expected` onto
  the external system through the §10 queue (`QUEUED` → `SYNCED` /
  `FAILED`). Bypasses the flow-step guard (the originating note's sync step
  may already be terminal). Needs `SYNC_EXTERNAL`. On `COUNT_MISMATCH` /
  `ORPHAN_ASSIGNMENT` / `GLPI_CONTRADICTION` → `400` (no single
  deterministic write to re-assert — a bulk records-adjust for
  `COUNT_MISMATCH` is a post-v2.0 candidate).
- **`POST /api/v1/reconciliation/{id}/acknowledge`** `{ "reason": "string" }`
  — accept a known-OK discrepancy. `reason` mandatory, audited. Scoped to
  the exact `expected` / `actual` pair (for an aggregate row, the
  `delta` + `limbo` shape): if either later changes, the row
  **re-surfaces un-acknowledged**. The path for a legacy `ORPHAN_ASSIGNMENT`
  an admin has reviewed and accepts.
- **Manual external edit** — an admin fixes GLPI by hand (a state the app
  can't set, or a `GLPI_CONTRADICTION`); the next scan clears the row. No
  endpoint.
- **Corrective note** — for "reality changed, the app missed it": create a
  normal Entrega / Devolución; once approved + synced, history matches
  reality and the row clears. No special endpoint.

### 14.3 Auto-clear

A drift row is removed as soon as any scan finds `expected == actual`
again — from a `repush`, a manual edit, or a corrective note. No manual
"close" step. Scans `upsert` by `(driftKind, target)` so a re-run never
duplicates an unresolved row.

### 14.4 Pre-app assignments (`ORPHAN_ASSIGNMENT` cutoff)

The external system is a live instance with years of history; run #1 could
raise hundreds of `ORPHAN_ASSIGNMENT` rows for assignments predating the
app. Handled by a go-live cutoff — **applies to `ORPHAN_ASSIGNMENT` only**,
never the other kinds:

- **`reconciliation.orphanCutoff`** (config, ISO date; default = the
  earliest `NOTE_REPORT` timestamp). Every orphan row is stamped
  `preCutoff = assignmentDate < cutoff`.
- **`GET /reconciliation/drift` and the nav-badge count exclude
  `preCutoff = true` by default.** `?includePreCutoff=true` returns them —
  for an admin auditing the legacy backlog. They are real rows:
  `acknowledge` works on them normally. `run` **stores** them (doesn't skip
  at scan time), so the count is known without a re-scan and a later cutoff
  change just recomputes `preCutoff`.
- **`assignmentDate`** — the middleware reads the asset's GLPI history
  (per-item `Log` sub-resource, filtered to holder-field changes:
  `id_search_option` = the holder search option, `linked_action = 0`,
  newest wins, `new_id` must equal the asset's current holder), giving an
  exact date with `assignmentDateSource = "LOG"`. If no such entry survives
  (GLPI purges history), it falls back to the asset's `date_mod` with
  `assignmentDateSource = "DATE_MOD"` (an upper bound, not the real date —
  the UI marks these "fecha aproximada"). Mechanics: `reconciliation-endpoint.md §5.6`.
- **`reconciliation.suppressApproximateOrphans`** (config, default `true`)
  — when `true`, `preCutoff` suppression applies regardless of source; set
  `false` to always show `DATE_MOD`-sourced orphans in the default view.
