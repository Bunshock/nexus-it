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
  "permissions": ["MANAGE_STOCK", "APPROVE_NOTES", "..."]  // see §7.3
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

### 2.6 Open items for this section

- **Middleware → GLPI per-user identity propagation** (companion §4.7 /
  §10.9): when the middleware performs a write against GLPI (asset
  assignment, status change), does it act as the real technician's own
  GLPI account (requires every technician provisioned in GLPI + a
  token-exchange / impersonation path from the IdP), or as one shared GLPI
  service account while recording the real actor only in its own audit
  (§6)? The parallel contract's "authenticate as the real end user to
  GLPI" implies the former. GLPI-adapter detail, invisible to this
  app-facing contract, but the middleware build needs the answer.
- **IdP client registration** — a deployment prerequisite, not a design
  open: register a public OIDC client (Auth Code + PKCE, loopback redirect
  `http://127.0.0.1:*`), enable the Kerberos/SPNEGO browser flow and
  Kerberos user federation. Checklist item for whoever administers
  Keycloak.
- Non-domain login path — **no action needed**: the fleet is 100%
  domain-joined, and if that ever changes the IdP's own login page covers
  it with no change to this contract.

---

## 3. Catalog

Backend-neutral replacement for `IEquipmentService`'s Type/Brand/Model/Stock
surface. GLPI is the concrete backend today (Peripherals for countables,
whatever GLPI's asset types are for serialized items) — the middleware
does the translation; this contract never sees GLPI's own field names.

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

**"Genérico / Otro" (decision D5b).** Not a stored catalog row — a
config-driven fallback. `GET /config` (§8) carries `genericLabel`,
`genericBrandId` (`string | null`), `genericModelId` (`string | null`).
The desktop app appends a "Genérico / Otro" option (labeled `genericLabel`,
sorted last, fallback styling) to the Brand and Model pickers **for every
Type**, asset or countable:
- **countable** — it's the "not in the catalog" fallback. Picking it
  stores `brandId`/`modelId` = the config value (may be `null`) and
  `brandName`/`modelName` = `genericLabel` (matches §5.1's null-able id /
  label name). Availability, `sync` allocation and reconciliation resolve
  against "records of this Type with the generic manufacturer" (and the
  generic model if `genericModelId` is set) — all Model-Y derived (§3.4),
  no middleware counter.
- **asset** — Brand/Model are optional **search filters** (§3.2); picking
  "Genérico / Otro" narrows the serial search to generic-manufacturer
  assets. The item's actual brand/model always come from the picked
  record, generic or not.

Today: `genericBrandId` is a real GLPI manufacturer id; `genericModelId`
is pending a GLPI check (§13).

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

The `state` enum's exact GLPI-value mapping is still open (companion §10.3)
— someone needs to go look at the real GLPI instance's configured status
list. Suggested default bucketing until that's confirmed:
- `AVAILABLE` — GLPI "disponible"/"nuevo"/unassigned-and-not-broken states
- `IN_USE` — GLPI "en uso"/assigned-to-someone states
- `UNAVAILABLE` — GLPI "de baja"/"roto"/"perdido"/"robado" or similar
  permanently-unavailable states

### 3.3 Countable availability (aggregate)

```
GET /api/v1/catalog/countables/availability?typeId={typeId}&brandId={brandId}&modelId={modelId}&sedeId={sedeId}
```

```jsonc
// Response 200
{ "available": 12 }
```

A single aggregate number — computed server-side by counting
GLPI Peripheral records matching Type/Brand/Model with state = AVAILABLE
(companion doc §4.5). The app never sees or selects individual Peripheral
records for a countable item. `sedeId` scopes the count to one site,
though whether GLPI's own data model has a Sede-equivalent location field
to filter on is itself unconfirmed — placeholder param, tied to E2 (§13).

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
  availability follows." **Generic-brand countables are still real backend
  records** (under the generic manufacturer — §3.1 / D5b), so they are
  Model-Y derived like anything else; no counter is needed for this org.
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
  { "match": { "profileTypes": ["PRÉSTAMO"] },
    "steps": ["sync", "return"] },
  { "match": { "profileTypes": ["ENTREGA - PROVEEDOR"], "motivoIn": ["Garantía", "Reparación"] },
    "steps": ["sync", "return"] },
  { "match": { "profileTypes": ["ENTREGA - PROVEEDOR"] },
    "steps": ["sync"] }   // fallback: non-returnable Motivo
]
```
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
their ids against the note item, so a later `/return` (§4.3) knows what to
release without the app tracking them.

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
//   allocated. An asset that was IN_USE → AVAILABLE; an asset that was
//   UNAVAILABLE (broken/lost — a legitimate DEVOLUCIÓN, decision D6a) →
//   holder cleared, state STAYS UNAVAILABLE. Unrecoverable conflict → step
//   FAILED, resolvable by retry or RETURN_ABANDONED (§10.2).
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
// On success: flips state to UNAVAILABLE, NOT back to AVAILABLE.
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
  "profileType": "ENTREGA" | "DEVOLUCIÓN" | "ENTREGA PERMANENTE" | "PRÉSTAMO" | "ENTREGA - PROVEEDOR",
  "recipientName": "string", "recipientDni": "string", "recipientEmail": "string | null",
  // technician identity is NOT sent — the middleware derives and snapshots
  // it from the session token (§2.5); it still comes back on GET responses
  "sedeId": "integer",
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
- **DEVOLUCIÓN — the only hard block:** an asset item whose current
  external state is `AVAILABLE` (in stock, held by no one) →
  `409 ASSET_NOT_IN_USE`, note not created. A user cannot "return" an
  asset that isn't out.
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

Backend-neutral successor to `IUserRoleService` + the `Sede`/
`SEDE_SHIPPING_INFO` half of `IEquipmentService`. **Read-only from the
app's side, by design** — mirrors today's explicit "no in-app CRUD for
APP_USER/ROLE_PERMISSION, direct-SQL-only" convention (companion doc
carries this forward; someone still administers users/roles/permissions
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
  "permissions": ["MANAGE_STOCK", "APPROVE_NOTES", "..."],
  "registered": true,
  "bypassGroupCheck": false
}
```

Same data the login response (§2.1) carries, re-fetchable without a fresh
login — lets the app refresh its UI gate after a mid-session role / Sede /
permission change. Identity is the token subject, never a parameter (§2.5).
The client gate is **cosmetic**; every endpoint enforces its own permission
server-side regardless of what the app renders (§7.5).

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
GET /api/v1/sedes/{id}/shipping-info
```
Same shape/purpose as today's `SEDE` / `SEDE_SHIPPING_INFO` tables.
**Read-only from the app** (decision D3) — no in-app add/edit/delete for
Sedes or Providers; both are lists the app only reads, edited out-of-band
like `APP_USER` / roles.

**Where `SEDE_SHIPPING_INFO` lives is OPEN (§13, tied to E2/§3.3).** GLPI
holds only equipment + providers, not Sede addresses, and the desktop
app's local DB is being eliminated. Options: (a) the **middleware's own
store** owns `SEDE` + `SEDE_SHIPPING_INFO` (app-domain data, not
asset-of-record — the lean), (b) a GLPI-side custom section, (c) drop the
Remito shipping pre-fill and make the destination always free text.
Flagged as needing research.

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
  "smtp": { "host": "string", "port": 587, "senderAddress": "string" },
  // password NOT included — see §9, this is config, not secret
  "noteItemLimit": "integer",
  "returnableMotivosProveedor": ["Garantía", "Reparación"],
  "failureTriggerMotivo": "Falla",
  "motivoOptions": { "entrega": ["..."], "finDeContrato": ["..."], "proveedor": ["..."], "devolucion": ["..."] },
  "fallaOptions": ["..."],
  "genericLabel": "Genérico / Otro",       // §3.1 fallback (decision D5b)
  "genericBrandId": "string | null",       // external id of the backend's generic manufacturer
  "genericModelId": "string | null"        // external id of a generic model, if the backend has one
  // may grow a "branding": { "appName", "logoUrl", "themeTokens": {...} }
  // block later (§1.2) — additive, non-breaking
}
```

This is the per-organisation policy surface (§1.2 — instance-per-org). A
second organisation runs its own deployment with its own `config`.

```
PUT /api/v1/config
```
Admin/Superadmin-only (exact permission TBD alongside §7.3's enum
cleanup). Same "confirm this app-side" pattern as today's `SettingsController`
— **RESOLVED 2026-08-28**: no desktop Configuración screen for this at all.
Same decision as §9 — config/secrets administration is a middleware-only
admin surface, no app-facing UI.

---

## 9. Secrets / credential custody

**Genuinely open — not designed** (companion doc §4.7/§10.4/§10.5).
What's clear:
- GLPI API key and AD service-account credentials are held **only** by the
  middleware, never transmitted to or stored by the desktop app.
- SMTP password's fate is grouped in here per this session ("global
  configs like smtp") — likely also middleware-only custody, not returned
  by `GET /config` above (config = policy, secret = never leaves the
  middleware).

**RESOLVED 2026-08-28**: the desktop app gets NO endpoint here at all.
Secret configuration is entirely an out-of-band, middleware-only admin
concern (an admin editing the middleware's own config/environment
directly) — today's `SettingsController` in-app UI is dropped, not
re-pointed. Rationale, as stated by the user: nothing that could be
reverse-engineered out of the client binary should exist there in the
first place — nothing changes that calculus, so the app-facing surface
for secrets is simply absent, not merely reduced.

Still **not** decided:
- Storage/rotation mechanics for the middleware's own credentials (its
  GLPI key, its AD bind account) — a deployment/ops concern more than an
  API contract concern, but still fully unaddressed.

No endpoint is specified here — and none is planned for this section.

---

## 10. External write queue

**RESOLVED 2026-08-31 (decisions D2 + E3).** Every item-level external
write — `sync` (§4.1), `return` (§4.3), `lost` (§4.4) — is **enqueued on
the middleware, not executed in the request**. The action endpoint checks
permissions (§7.5) and the flow state, enqueues a job, and returns
`202 Accepted` with the step now `QUEUED`.

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
The app may optimistically poll `GET /notes/{id}` for a few seconds so the
common (fast, no-conflict) case still reads as instant, falling back to
"en proceso — ver cola" if it's slow. `FAILED` and `<STEP>_ABANDONED` are
each a **visually distinct** state on the item card, separate from
`SYNCED` and from `REJECTED`.

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
6. §9 — secret storage/rotation mechanics, fully unaddressed.
7. RESOLVED 2026-08-31 (decisions D2 + E3) — §10 is now the "external
   write queue": all item-level external writes (`sync` / `return` /
   `lost`) are enqueued, consumed serially per model, auto-retried on
   transient failure, and land in `FAILED` + a two-channel admin alert
   (in-app queue view + email) on an unrecoverable conflict, resolvable by
   `retry` or `<STEP>_ABANDONED`.
8. Companion doc's still-open items block parts of this contract from being
   final: (a) real GLPI status list → the `AVAILABLE`/`IN_USE`/`UNAVAILABLE`
   map (§3.2); (b) exact Sede↔GLPI-location mapping for per-Sede countable
   availability (§3.3) — **and, tied to it, where `SEDE_SHIPPING_INFO`
   lives** (§7.4: middleware store vs. GLPI custom section vs. drop the
   pre-fill); (c) does GLPI have a generic *model* entry →
   `config.genericModelId` real id or `null` (§3.1 / D5b — generic
   *manufacturer* is confirmed to exist). (GSSAPI wire mechanics: RESOLVED
   2026-08-31 — OIDC/Keycloak, §2.)
9. §2.6 — middleware → GLPI per-user identity propagation (act as the real
   technician's own GLPI account vs. one shared service account + attributed
   audit). GLPI-adapter detail; does not block the app-facing contract, but
   the middleware build needs the answer.
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

---

## 14. Reconciliation (drift detection)

**RESOLVED 2026-08-31 (decision D9). In v1.** The dual-SSOT model (§3.4 —
external system authoritative for stock/state, app note history
authoritative for assignments) only holds if the two actually agree.
Reconciliation is the read-only check that surfaces where they don't. It
**never auto-fixes** — which side is right is a human call.

**Scope:** assets and countable models the app has touched (≥ 1 approved
note). There is no pre-app baseline — GLPI and the app go live together —
so a countable count delta is real signal, not noise. An asset the app has
never touched is out of scope entirely.

**Runs:** nightly scheduled + `POST /reconciliation/run` on-demand + a
quick per-asset re-check right after that asset's sync job completes
(catches "sync reported OK but the external system didn't take it" fast).
Every response carries a `scannedAt`.

**Sede-scoped** (§7.5): a plain `ADMIN` sees / acts on their own Sede's
rows only; `SUPERADMIN` all. A nav-badge count, like the sync-queue badge.

### 14.1 Reads

```
GET /api/v1/reconciliation/assets?sedeId=&driftKind=&acknowledged=&page=&size=
```
```jsonc
{
  "scannedAt": "ISO-8601",
  "rows": [{
    "id": "string",
    "externalItemId": "string", "serialNumber": "string",
    "typeName": "...", "brandName": "...", "modelName": "...",
    "sedeId": 3,
    "driftKind": "HOLDER_MISMATCH" | "STATE_MISMATCH"
               | "UNTRACKED_ASSIGNMENT" | "STALE_RETURN",
    "expected": { "holder": "maria", "state": "IN_USE",
                  "fromNoteId": 412, "syncedAt": "..." },
    "actual":   { "holder": "juan",  "state": "IN_USE" },
    "acknowledged": { "by": "...", "at": "...", "reason": "..." } | null,
    "detectedAt": "..."
  }]
}
```

```
GET /api/v1/reconciliation/countables?sedeId=&driftKind=&acknowledged=&page=&size=
```
```jsonc
{
  "scannedAt": "ISO-8601",
  "rows": [{
    "id": "string",
    "typeName": "...", "brandName": "...", "modelName": "...",
    "sedeId": 3,
    "driftKind": "COUNT_MISMATCH"       // per (model, Sede)
               | "USER_COUNT_MISMATCH", // per (model, user) — the "who has what" check
    "scope": { "sedeId": 3 } | { "sedeId": 3, "username": "maria" },
    "expectedCount": 40, "actualCount": 42, "delta": 2,
    "acknowledged": { ... } | null,
    "detectedAt": "..."
  }]
}
```
Countable rows are always a **count** comparison — with no per-unit
identity there is no "which unit" or "which note", only a delta for that
(model, Sede) or (model, user).

### 14.2 Resolving a drift row

- **`POST /api/v1/reconciliation/{id}/repush`** — **assets only (v1)**.
  Re-asserts the app's `expected` holder/state onto the external system,
  through the §10 queue (same `QUEUED` → `SYNCED` / `FAILED` → retry /
  abandon path). Bypasses the flow-step state guard — the originating
  note's sync step may already be terminal. Needs `SYNC_EXTERNAL`. On a
  countable row → `400` (no countable repush yet; a v2 candidate where the
  middleware adjusts records to match the app's count).
- **Manual external edit** — an admin corrects the external system by hand
  (e.g. a state the app can't set); the next scan clears the row. No
  endpoint.
- **Corrective note** — for the "reality changed, the app missed it" case:
  create a normal Entrega / Devolución; once approved + synced, history
  matches reality and the row clears. No special endpoint.
- **`POST /api/v1/reconciliation/{id}/acknowledge`** `{ "reason": "string" }`
  — accept a known-OK discrepancy. `reason` mandatory, audited. Scoped to
  the exact `expected` / `actual` pair: if either value later changes, the
  row **re-surfaces un-acknowledged**.

### 14.3 Auto-clear

A drift row is removed as soon as any scan finds `expected == actual`
again — whether that came from a `repush`, a manual edit, or a corrective
note. No manual "close" step.
