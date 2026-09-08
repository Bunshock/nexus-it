# Middleware — v1 (no-adapter) vs v2 (GLPI-adapter) comparison

Per-feature reference for how the two middleware versions differ. Keep it updated
as either version changes.

- **v1** = `backend-api-v1-no-adapter` — internal DB is the catalog + stock source
  of truth; no external asset system. Ships first; `IMPLEMENTED_ENDPOINTS.md` is
  its live status.
- **v2** = `backend-api-v2-glpi-adapter` — GLPI is the source of truth via a
  Ports & Adapters layer; the internal DB holds only notes / audit / config /
  `APP_USER`. Contract fold done, adapter not built. `MIDDLEWARE.md` is its spec.

Status key: **=** same · **≠** diverges · **v2‑only** · **v1‑only** · **gap**
(planned, not built) · **fix** (a known inconsistency to reconcile).

_Last updated: 2026-09-08 — after v1 gap #2 (directory profile at login)._

---

## Architecture

| Feature | v1 | v2 | |
|---|---|---|---|
| Source of truth for equipment / stock / state | the internal DB (`TYPE`/`BRAND`/`MODEL`/`MODEL_STOCK`) | GLPI, via `port/` + `adapters/glpi/` | ≠ |
| Internal DB role | catalog + stock + notes + audit + config + users | notes + audit + config + users only | ≠ |
| Adapter layer | none | `CatalogPort` / `AssetPort` / `MovementPort` + `adapters/glpi/*` | v2‑only |
| Pagination of external reads | n/a | K4 invariant — every adapter collection read must exhaust the backend's pagination; counts use the backend's `totalcount` | v2‑only |

## Auth & session

| Feature | v1 | v2 | |
|---|---|---|---|
| Login | OIDC Auth Code + PKCE; `POST /auth/login` exchanges the IdP token for an opaque session | same | = |
| Session store | in-memory, sliding ~2h idle / ~12h absolute | same | = |
| `GET /auth/config` | issuer / clientId / scopes; `503` until configured | same | = |
| Directory profile at login | **yes (added 2026-09-08)** — `AuthController` resolves the caller's real name + DNI via `DirectoryService.findExactByUsername`, stamps them on the session; degrades to username on a directory outage | same intent (§2.4); F1 also adds `glpiTokenSet` to `GET /me` | = (v2 also carries `glpiTokenSet`) |
| `aud` token validation | not enforced (`JwtDecoders.fromIssuerLocation` only) | flagged as a required build task | gap (both) |
| "Nombre para mostrar" greeting override | not ported (needs its own table + endpoint) | not in scope yet | gap (both) |

## RBAC

| Feature | v1 | v2 | |
|---|---|---|---|
| `Permission` enum | **12** — `MANAGE_TYPES/BRANDS/MODELS/STOCK`, `EDIT_SN_VALIDATION`, `EDIT_SMTP/GLPI/AD/AF_FORMAT_CONFIG`, `APPROVE_NOTES`, `SYNC_GLPI`, `VALIDATE_RETURNS` | **3** — `APPROVE_NOTES`, `SYNC_EXTERNAL`, `VALIDATE_RETURNS` (`SYNC_GLPI` renamed) | ≠ |
| `PUT /config` gate | per-field-group permission (`EDIT_AF_FORMAT_CONFIG` / `EDIT_SMTP_CONFIG`) | `SUPERADMIN` role check, no permission | ≠ |
| Sede fence | plain `ADMIN` scoped to own `sede_id`; `SUPERADMIN` bypasses | same | = |
| `GET /roles/{role}/permissions` | enforcement built, **endpoint not exposed** | exposed (§7.3) | gap (v1 #3) |
| Deny-by-default, server-side enforcement | yes | yes | = |

## Catalog

| Feature | v1 | v2 | |
|---|---|---|---|
| Browse Type/Brand/Model | from internal tables; ids are internal ints (stringified) | from GLPI; ids are opaque composite strings | ≠ (same endpoints) |
| Type/Brand/Model **CRUD** (add/rename/remove, cascade, deprecate) | **full** (`MANAGE_*` permissions) | **none** — catalog is GLPI, read-only | v1‑only |
| S/N Validation (regex-per-model) | **full** (`GET`/`PUT`, `EDIT_SN_VALIDATION`) | **removed** (Tier 6) | v1‑only |
| "Genérico / Otro" | real internal `BRAND`/`MODEL` rows; ids resolved live | config `generic` block — `manufacturerId` (global) + per-itemtype `typeIds`/`modelIds`, `null` until GLPI rows exist | ≠ |
| Providers / Sedes | read-only endpoints from internal tables | read-only; `SEDE` maps to a GLPI `Location` | ≈ |

## Stock

| Feature | v1 | v2 | |
|---|---|---|---|
| Model | real stored `MODEL_STOCK` counter per (Type, Brand, Sede) | derived — live count of AVAILABLE records in GLPI ("Model Y"), no counter | ≠ |
| Manual stock edit | `PUT /catalog/models/{id}/stock` (`MANAGE_STOCK`, mandatory reason, `AUDIT_STOCK`) | no endpoint — stock only moves when a `sync` changes real records | v1‑only |
| Approval-time stock movement | **yes** — decrements egress / increments Devolución, guarded by `stock_applied`; `409 STOCK_WOULD_GO_NEGATIVE` | **no** — approval moves nothing | ≠ |
| Shortage signal | synchronous `409` at approval | the §10 queued job goes `FAILED` + admin alert | ≠ |
| `modifiesStock` / `modifiesStockReason` on an item | present (skips the stock move) | **removed** (D4) | v1‑only |

## Notes — create

| Feature | v1 | v2 | |
|---|---|---|---|
| `sedeId` in the request | **no** — server-resolved from the caller's `APP_USER.sede_id` (`400 SEDE_NOT_ASSIGNED`) | contract §5.1 text still lists it as client-supplied | **fix** — v2 should adopt v1's server-resolve |
| `technician_name` / `technician_dni` | stamped from the login-time directory profile (`CallerPrincipal.displayName`/`dni`) | same intent | = |
| Item catalog ids | internal ids + snapshot names; no `externalItemId` | external opaque ids + snapshot names; `externalItemId` for assets; `externalStatusAtAddTime` | ≠ |
| Create-time hard blocks | DEVOLUCIÓN of an `AVAILABLE` asset → `409 ASSET_NOT_IN_USE` (client-side check only in v1, since there's no live asset state) | same, plus `422 SEDE_LOCATION_NOT_MAPPED` (Remito) and `422 PROVIDER_MOTIVO_NOT_CONFIGURED` | v2 adds two |
| Provider "returnable" motivos | from `APP_CONFIG` (`returnableMotivosProveedor`) | from config + `syncTargets.proveedor` map (per-Motivo GLPI state) | ≈ |

## Notes — approval & item actions

| Feature | v1 | v2 | |
|---|---|---|---|
| Approve / reject | `PUT /notes/{id}/approve|reject`, `APPROVE_NOTES`, Sede-scoped | same | = |
| `sync` / `reject-sync` | **synchronous status flip** (`200`) — no external write, no queue, no holder check. Matches the old desktop GLPI-sync (manual, one-way, no-revert) | **enqueued** (`202 QUEUED`) → real GLPI write via `EXTERNAL_WRITE_QUEUE`; holder-mismatch gate (`409`); `syncTargets` state write; requires the caller's GLPI `user_token` | ≠ |
| `return` / `lost` | synchronous flip; countable partial-batch via `NOTE_ITEM_RETURN_ALLOCATION`; `RETURNED` credits `MODEL_STOCK` back, `LOST` doesn't | enqueued; `states_id → 33` / `45` writes; same partial-batch mechanic; no stock counter to credit | ≠ |
| Holder-mismatch pre-check | none | `409 HOLDER_MISMATCH` + `acknowledgeHolderMismatch` (D6b) | v2‑only |
| `GLPI_RETURN` tracking dimension (re-sync a returned Provider item) | **deferred** | full | gap (v1 #4) |
| External write queue (retry / abandon / alert / `GET /sync-queue`) | none | full — `EXTERNAL_WRITE_QUEUE`, `@Scheduled` poller, per-`model_key` lease, no optimistic `glpiStatus` | v2‑only |

## Reconciliation

| Feature | v1 | v2 | |
|---|---|---|---|
| Drift detection | **none** — one SSOT, nothing to reconcile | full — `POST /reconciliation/run` (nightly + manual), `GET /reconciliation/drift`, `repush` / `acknowledge`; scope (b); taxonomy `STATE`/`HOLDER`/`LOCATION`/`COUNT_MISMATCH` / `ORPHAN_ASSIGNMENT` / `GLPI_CONTRADICTION`; on-the-fly expected-state; `ORPHAN_ASSIGNMENT` go-live cutoff + `assignmentDate` from the GLPI `Log` | v2‑only |

## Remito de Envío

| Feature | v1 | v2 | |
|---|---|---|---|
| Destination | `shippingInfoId` (a catalog Sede's `SEDE_SHIPPING_INFO`) → `NOTE_REMITO_SEDE`, **or** free-text `destinationLabel`/`Address`/`Recipients` → `NOTE_REMITO_OTHER`; neither → `400` | **catalog Sede only** — `destinationSedeId`, must map to a GLPI `Location`; no free text; `422 SEDE_LOCATION_NOT_MAPPED`; `NOTE_REMITO_OTHER` + `destinationRecipients` removed | ≠ |
| Stock effect on approval | dual-Sede move on `MODEL_STOCK` (source decrements, catalog destination increments) | `sync` relocates `locations_id`; derived counts follow | ≠ |
| `SEDE_SHIPPING_INFO` table | **yes** (address + recipients) | **none** — address read from the GLPI `Location` | v1‑only |

## Config & secrets

| Feature | v1 | v2 | |
|---|---|---|---|
| `GET /config` | AF format, motivo/falla options, `returnableMotivosProveedor`, `noteItemLimit`, generic ids, **`smtp` host/port/sender** | same minus the `smtp` block; `generic` is the per-itemtype block | ≠ |
| `PUT /config` | per-field-group permission gating; `smtpPassword` write-only, AES in `APP_CONFIG` | `SUPERADMIN` role-gated; **no `smtpPassword` field** — SMTP is deploy-time middleware config | ≠ |
| `/me/preferences` | none | none (UI toggles stay desktop-local, Decision 6) | = |
| SMTP custody | write-only field in `APP_CONFIG`, AES-encrypted | deploy-time config, used only for §10.3 alert emails | ≠ |
| Per-user GLPI token | n/a | `PUT /me/glpi-token` (write-only, `APP_USER.glpi_token_encrypted`); `409 GLPI_TOKEN_NOT_SET` on a write with none | v2‑only |
| Other secrets (GLPI App-Token, IdP, directory) | env / deploy config | env / deploy config | = |

## Directory & audit

| Feature | v1 | v2 | |
|---|---|---|---|
| `GET /directory/users` | ported `AdApiService.search` fan-out + AND re-verification; `502`/`503` on outage | same (§7.6) | = |
| `findExactByUsername` (login profile) | yes (added with gap #2) | same pattern | = |
| Audit tables + read endpoints | `AUDIT_LOGIN`/`STOCK`/`ITEM_STATUS`/`ADMIN_ACTION`; reads ADMIN/SUPERADMIN, **not Sede-scoped**; every write point wired | same shape (§6) | = |

---

## Deliberate v1 deviations that are NOT bugs

Documented in `IMPLEMENTED_ENDPOINTS.md`, legitimate under `backend-contract.md`
§1.1 / §3.4's "adapter detail" carve-out — v1 chose the internal-counter branch of
a fork the design explicitly left open:

- stored `MODEL_STOCK` instead of derived Model-Y
- synchronous `sync`/`return` instead of the §10 queue
- server-resolved Sede instead of a client-supplied `sedeId` (v2 contract text
  should actually adopt this — see the **fix** row above)

## Open reconciliations between the two

| # | Item |
|---|---|
| 1 | v2 `backend-contract.md`/`MIDDLEWARE.md` §5.1 still shows `sedeId` in the `POST /notes` request — should switch to v1's server-resolve. |
