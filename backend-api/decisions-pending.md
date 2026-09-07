# Open Decisions — Middleware Contract

Working log for `backend-contract.md` + `contract-behaviors.md`. Decisions
are worked **one at a time, top to bottom**. When one is settled it moves
to the *Resolved* section at the bottom with the outcome, the date, and
where it landed in the contract.

Tier 3 (external facts) can be gathered in parallel — it doesn't block the
others except where noted.

Last updated: 2026-08-31.

---

## Tier 1 — Architecture

*(D1, D1b resolved 2026-08-31 — see Resolved section.)*

---

## Tier 2 — Contract design (your call; blocks finalizing the contract)

**Tier 2 is done** — D2–D9 all resolved 2026-08-31 (see Resolved).

---

## Tier 3 — External facts to gather (parallel; not decisions)

### E1 · Real GLPI status list → generic-enum mapping
Inspect the org's GLPI instance's configured status values; map each to
`AVAILABLE` / `IN_USE` / `UNAVAILABLE`. **Also:** does GLPI have a
"Generic" *model* entry (in the relevant PeripheralModel / model list)? →
`config.genericModelId` = its real id, else `null` (D5b — generic
*manufacturer* is confirmed present).
- Touches: §3.2, §3.1, companion §10.3, contract §13.8. Owner: GLPI admin.

### E2 · GLPI location model for per-Sede availability + where SEDE_SHIPPING_INFO lives
(a) Does GLPI have a location/entity field the middleware can filter
countable counts by, matching our Sede concept? (b) `SEDE_SHIPPING_INFO`
(per-Sede addresses/recipients for Remito pre-fill) has no home — GLPI
holds only equipment + providers, the app's local DB is being eliminated.
Options: middleware's own store owns `SEDE` + `SEDE_SHIPPING_INFO` (lean —
app-domain data) / a GLPI custom section / drop the pre-fill (Remito
destination always free text).
- Touches: §3.3, §7.4, contract §13.8. **Owner:** research (GLPI admin).

### E3 · "GLPI sync queue" recap
RESOLVED 2026-08-31 — folded into D2. §10 is now the "external write
queue". See Resolved.

---

## Tier 4 — Middleware-internal (note; does not block the contract)

### F1 · Middleware → GLPI identity for writes
Act as the real technician's GLPI account (needs every tech in GLPI + a
Keycloak→GLPI token-exchange/impersonation path) or one shared service
account with the real actor in the middleware's own audit?
- Touches: §2.6, companion §10.9. Owner: middleware build.

### F2 · Middleware's own secret storage/rotation
How the middleware holds and rotates its GLPI key / AD bind account / SMTP
password. Deployment/ops.
- Touches: §9.

---

## Tier 5 — Defer to implementation

- **G1** · List-summary response field list (§5.2).
- **G2** · Audit table schema (§6).
- **G3** · API deprecation policy (§12).

---

## Tier 6 — Desktop feature-removal sign-offs (Phase 6, not now)

- **H1** · Remove Base de Datos "CATÁLOGO DE EQUIPOS" toggle.
- **H2** · Remove Configuración "S/N Validation" panel.
- **H3** · Remove `CatalogMigrationTool`.
- **H4** · Remove obsolete catalog tests.

---

## Resolved

- **D8 · recipient / directory user-search endpoint** — RESOLVED
  2026-08-31. `GET /api/v1/directory/users?name=&dni=&username=` replaces
  `IADService.search()`. Params AND-combined, ≥1 required else `400`;
  returns `{ results: [{ username, fullName, email, dni }] }`; no matches
  → `200` (not `404`); directory down → `5xx` (distinct). Any
  authenticated session, no special permission, not Sede-scoped, results
  capped. Does NOT return assets — `GET /assets?holder=` (§3.5, D6c) is
  the separate call for the F4 warning. `backend-contract.md` §7.6,
  `contract-behaviors.md` K3.
- **D7 · per-step status filter params** — RESOLVED 2026-08-31. Two params,
  one per flow-step kind: `syncStatuses` + `returnStatuses` (not one
  combined `externalStatuses`, which would be ambiguous). Comma-separated
  multi-select, any-of within / and-across; a note matches if any item has
  a step of that kind in a listed status; a future 3rd kind → a 3rd param.
  `backend-contract.md` §5.2, `contract-behaviors.md` G1.
- **D6 · asset-state warnings** — RESOLVED 2026-08-31.
  **D6a** — only `AVAILABLE` hard-blocks a DEVOLUCIÓN (`409
  ASSET_NOT_IN_USE`); `UNAVAILABLE` (broken/lost) is a soft warning
  (returning dead equipment is legitimate), and its `return` sync clears
  the holder but leaves state `UNAVAILABLE` (never forces `AVAILABLE`).
  **D6b** — sync/return holder-mismatch is **gated, not advisory**:
  synchronous `409 HOLDER_MISMATCH` (nothing enqueued), re-called with
  `{ acknowledgeHolderMismatch: true }`. Generalised beyond DEVOLUCIÓN —
  also ENTREGA (asset already `IN_USE` by ≠recipient). Applies where the
  note has a recipient user; Provider-return holder semantics deferred.
  **D6c** — added `GET /api/v1/assets?holder={username}` (§3.5, §3.2 row
  shape); the ENTREGA F4 "already holds a same-type asset" warning is
  computed client-side from it at recipient-selection time. Landed in
  `backend-contract.md` §3.5 (new) / §4.1 / §4.3 / §5.1 / §13.10–12,
  `contract-behaviors.md` F1 / F1b / F2 / F3 / F4.
- **D5b · "Genérico / Otro" brand/model** — RESOLVED 2026-08-31. Not a
  stored catalog row — config-driven fallback: `GET /config` carries
  `genericLabel` / `genericBrandId` (real GLPI manufacturer id, confirmed
  present) / `genericModelId` (`null` pending a GLPI check → E1). Desktop
  app appends "Genérico / Otro" to Brand/Model pickers **for every Type**
  (the countable-only restriction was rolled back — "just in case"). For a
  **countable** it's the not-in-catalog fallback → `brandId`/`modelId` =
  config value (may be `null`), names = `genericLabel`. For an **asset**
  it's just an optional search filter; the picked GLPI record's real
  brand/model always populate the item. Generic countables are real GLPI
  Peripherals under the generic manufacturer → Model-Y derived stock/
  sync/reconciliation, **no middleware counter**. §1.1 counter fallback
  stays documented-but-unbuilt. Landed in `backend-contract.md` §3.1 /
  §3.2 / §3.4 / §8 / §13.8, `contract-behaviors.md` F7–F8.
- **D5 · `NOTE_ITEM` catalog-id fate** — RESOLVED 2026-08-31. Question
  dissolved (no local DB, no migration, greenfield middleware store). The
  stored note-item record keeps **both** the external catalog ids
  (`typeId`/`brandId`/`modelId` strings, null-able for "Genérico / Otro")
  **and** the snapshot names — as contract §5.1 already specifies. Both
  captured at selection time, never updated (immutable snapshot). The ids
  are load-bearing for countable `sync` allocation and countable
  reconciliation, not decoration. Landed in `backend-contract.md` §5.1,
  `contract-behaviors.md` B8, companion §6.
- **D9 · Reconciliation (drift detection)** — RESOLVED 2026-08-31. **In
  v1.** Read-only middleware check: per app-touched asset, expected
  holder/state (latest approved+synced note) vs. actual (external); per
  (model, Sede) and per (model, user) for countables, expected vs. actual
  IN_USE count. Drift kinds: `HOLDER_MISMATCH` / `STATE_MISMATCH` /
  `UNTRACKED_ASSIGNMENT` / `STALE_RETURN` (assets), `COUNT_MISMATCH` /
  `USER_COUNT_MISMATCH` (countables). No baseline noise — GLPI and the app
  go live together. Runs nightly + on-demand + post-sync per-asset
  re-check. Sede-scoped, nav badge. Endpoints: `GET .../assets`,
  `GET .../countables`, `POST .../run`, `POST .../{id}/repush` (**assets
  only in v1** — countable repush is a v2 candidate), `POST
  .../{id}/acknowledge {reason}` (mandatory reason, audited, re-surfaces
  if values change). Never auto-fixes; a row auto-clears when a scan finds
  expected == actual. Landed in `backend-contract.md` §14 (new) + §3.4,
  `contract-behaviors.md` §M.
- **D4 · "Modifica stock" flag → removed; stock model = derived (Model Y)**
  — RESOLVED 2026-08-31. GLPI is SSOT for *stock & current state*; the
  app's note history is SSOT for *assignments* (every assignment flows
  through an approved+synced note). "Stock" is only ever a live derived
  count of AVAILABLE records — no `MODEL_STOCK` successor, no counter, no
  `applied` flag for stock, no `STOCK_WOULD_GO_NEGATIVE`, no approval-time
  stock movement. A `sync` step changing real records' holder/state/
  location is the *only* thing that moves an availability count; a
  shortage is the §L4 queued-job `FAILED`. The "Modifica stock" checkbox,
  its dialog, `NOTE_ITEM_STOCK_EXCEPTION`, `NOTE_ITEM.modifies_stock`, and
  the badge are all removed — a formalized item just syncs (harmless
  no-op if GLPI already matches). Pre-generation shortage warning stays
  (advisory, live derived). Whether the middleware truly derives or keeps
  an internal counter for un-countable backends is an adapter detail
  (§1.1). Spawned D9 (reconciliation). Landed in `backend-contract.md`
  §3.4 (new) / §5.1 / §5.3 / §10 / §11, `contract-behaviors.md` §E
  (rewritten).
- **D3 · Permission enum cleanup** — RESOLVED 2026-08-31. Enum is exactly
  `APPROVE_NOTES` / `SYNC_EXTERNAL` (renamed from `SYNC_GLPI`) /
  `VALIDATE_RETURNS`. `retry` / `abandon` gated by the failed step's own
  permission. All others retired (catalog/stock/S-N/config/profile screens
  gone). **No `SUPERADMIN`-only permission** — SUPERADMIN = ADMIN without
  the Sede fence (acts on / reads any Sede). §4.0 step kind `GLPI` →
  `SYNC`. Provider & Sede become **read-only from the app** (no in-app
  CRUD). Carve-out → E2: where `SEDE_SHIPPING_INFO` lives is open (lean:
  middleware store). Landed in `backend-contract.md` §4.0 / §7.3 / §7.4 /
  §7.5 H4 / §13.4, `contract-behaviors.md` H4.
- **D2 + E3 · Over-allocation race → external write queue** — RESOLVED
  2026-08-31. Approved notes are immutable (printed + signed). Item-level
  external writes (`sync` / `return` / `lost`) are **enqueued** on the
  middleware, not synchronous. One worker, serialised per model → no race.
  Each job all-or-nothing (no partial allocation). Transient failure →
  auto-retry with backoff. Unrecoverable conflict (stock moved, note
  can't change) → step `FAILED` + a two-channel admin alert (in-app
  "Cola de sincronización" view + nav badge, Sede-scoped; and email).
  Resolve a `FAILED` step by **manual** `retry` (after re-stocking) or
  `SYNC_ABANDONED` / `RETURN_ABANDONED` (terminal, mandatory reason,
  audited — note stays valid, only that item's write is given up).
  `REJECTED` (pre-write, admin declines the item) stays distinct from
  `<STEP>_ABANDONED` (post-failed-write, admin gives up) and is only
  reachable from `PENDING`. Approval's own `STOCK_WOULD_GO_NEGATIVE`
  counter check stays synchronous (not queued). Landed in
  `backend-contract.md` §4.0 / §4.1–4.4 / §10 / §11, `contract-behaviors.md`
  §L.
- **D1 · Middleware only, Ports & Adapters** — RESOLVED 2026-08-31. No
  GLPI-plugin-as-backend. One codebase, one `main`: `core/` (backend-
  agnostic) + `port/` (adapter interface, generic vocabulary) +
  `adapters/{glpi,…}/`. Active adapter is config (`externalBackend=glpi`);
  minor same-backend variation is config inside the adapter (field/status/
  location maps); a different backend is one new adapter module + PR to
  `main`, never a branch; a `core/` change a new backend forces is a
  versioned contract revision behind a capability flag, shared by all
  deployments. Missing-concept fallbacks are documented (counter table for
  no-countables, `af=null` for no inventory number). Landed in
  `backend-contract.md` §1.1.
- **D1b · Multi-organisation = instance-per-org** — RESOLVED 2026-08-31.
  Not multi-tenant. A second org runs its own deployment + store + adapter
  + `GET /config`. Branding door kept open (`config` may grow a `branding`
  block; branding stays in `styles.css`/resources, not hardcoded). Not
  built now: multi-tenancy, theming engine, rules engine. Landed in
  `backend-contract.md` §1.2, §8.
- **Auth mechanism / session / identity model** — RESOLVED 2026-08-31
  before this log existed. OIDC Auth Code + PKCE against Keycloak,
  Kerberos-brokered; opaque middleware session token; per-user identity,
  `GET /me`, deny-by-default, server-side RBAC + Sede-scoping. Landed in
  `backend-contract.md` §2 / §7.5, `auth-flow.md`, `contract-behaviors.md`
  §A / §H.
- **Per-item lifecycle** — RESOLVED 2026-08-28. Config-driven flow model
  (ordered steps by `(profileType, motivo)` predicate), server-side.
  `backend-contract.md` §4.0.
- **Approval ≠ external write-back** — RESOLVED 2026-08-27. Approval only
  unlocks manual per-item actions; it does apply the per-Sede stock
  side-effect. §4 header / §5.3.
- **Countable `sync`** — RESOLVED 2026-08-27. Same treatment as assets
  (widened from asset-only). §4.
- **No in-app config/secrets UI** — RESOLVED 2026-08-28. Middleware-only
  admin surface. §8 / §9.
