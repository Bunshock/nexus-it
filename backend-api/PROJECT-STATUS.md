# NexusIT middleware — progress map

Single view of where the middleware + GLPI-adapter + desktop-cutover work stands.
Companions: `decisions-pending.md` (D1–D9 rationale), `glpi-adapter-notes.md`
(Tier 3/4 detail), `IMPLEMENTED_ENDPOINTS.md` (what's actually running).

_Last updated: 2026-09-07._

Legend: ✅ done · 🔨 in progress · ⏳ next · 📋 planned, not started · 🚫 blocked (external) · ⚪ deferred

---

## Where we are right now

The **no-adapter v1** middleware is fully built and pushed. The team then decided
to **re-add the GLPI adapter and make v2 replace v1**. Tier 3/4 contract
decisions are all made. The `port/` + `adapters/glpi/` skeleton is in place
(stubs only). Next: fold the decisions into the formal contract, strip the
no-adapter compensations, then build the real GLPI adapter + write queue +
reconciliation. Nothing is deployed anywhere yet.

---

## v2.0 scope decisions (2026-09-07 session)

Working session refining what the first release covers. Each folds into the docs cited.

| # | Decision | Detail |
|---|---|---|
| 1 | **Countables = `Peripheral`-only** | Every countable backed by GLPI `Peripheral`, per-unit return/loss unchanged. `Consumable` path deferred to a later release. (N1) |
| 2 | **Remito dest = catalog Sede only** | Must resolve to a real GLPI `Location` (no free text, no create-on-the-fly); unmapped Sede = hard `POST /notes` error. `destinationRecipients` dropped for v2.0. `SEDE_SHIPPING_INFO` + `NOTE_REMITO_OTHER` removed. (E2b) |
| 3 | **Async write queue IS in v2.0** | Full shape (`202 QUEUED`, per-model worker, retry→abandon+alert, status polling, desktop "queued" handling) — not a fast-follow. (§10 / D2) |
| 4 | **Full reconciliation IS in v2.0** | `POST /reconciliation/run` (nightly + manual), `GET /reconciliation/drift`, `POST /{id}/repush`, `POST /{id}/acknowledge`, full drift taxonomy incl. Q6 intra-GLPI contradiction. (§14 / D9) |
| 5 | **All 9 itemtypes in v2.0** | Core 5 (Computer/Peripheral/Phone/Monitor/Printer) direct; custom assets (Multimedia/AudioEquipment/Security/Misc) also in, each gated on a field/workflow investigation vs dev GLPI — Multimedia is priority. (N2) |
| 6 | **Config module** | `APP_CONFIG` (+3 child tables) stays as the org-config store; strip drops only the 4 SMTP columns + SMTP encryption/permission branch + generic-id resolution. `GET /config` unchanged; `PUT /config` stays, **SUPERADMIN role-gated** (enum stays 3, no `EDIT_CONFIG`). UI toggles (clear forms / close tab) stay **desktop-local** — no `/me/preferences`, nothing per-user in the middleware. |

---

## Branches

| Branch | Purpose | State |
|---|---|---|
| `backend-api-v1-no-adapter` | middleware without the GLPI adapter (internal DB catalog + stored stock) | ✅ built, `a41437c`, pushed to **GitHub + GitLab** |
| `backend-api-v2-glpi-adapter` | the real target — GLPI-adapter middleware, replaces v1 | 🔨 `88892f1`, off v1, **unpushed**. Contract docs + Tier 3/4 tracker + port/adapter skeleton so far |
| `backend-api-middleware-contract` | original contract-drafting branch | ✅ historical |
| `glpi-middleware-redesign` (off `desktop-app-first-release`) | eventual desktop-app code changes | ⚪ empty |
| `desktop-app-first-release` | the JavaFX desktop app today | current dev line, `6f64172` |

---

## Tier 1–2 — architecture & contract design  ✅ ALL RESOLVED (2026-08-31)

| | Decision | Outcome |
|---|---|---|
| D1 / D1b | Architecture | ✅ Middleware only, Ports & Adapters (`core/` + `port/` + `adapters/{glpi,…}/`); instance-per-org |
| Auth | Login / session | ✅ OIDC Auth Code + PKCE vs Keycloak (Kerberos-brokered), opaque middleware session token |
| D2 + E3 | Over-allocation race | ✅ approved writes go through an **external write queue** (`202`, one worker/model, `FAILED`+retry/abandon) |
| D3 | Permission enum | ✅ trim to 3: `APPROVE_NOTES` / `SYNC_EXTERNAL` / `VALIDATE_RETURNS`; catalog/config/S-N screens gone; Provider+Sede read-only |
| D4 | Stock model | ✅ derived (live count of AVAILABLE), no counter, no approval-time movement, "Modifica stock" removed |
| D5 / D5b | `NOTE_ITEM` catalog ids | ✅ store external id strings + snapshot names; "Genérico" is config-driven, not a stored row |
| D6 | Asset-state warnings | ✅ only AVAILABLE hard-blocks a return; holder-mismatch is gated (`409` + `acknowledge`); `GET /assets?holder=` |
| D7 | Status filters | ✅ `syncStatuses` + `returnStatuses` params |
| D8 | Directory lookup | ✅ `GET /directory/users` replaces `IADService.search()` |
| D9 | Reconciliation | ✅ in v1 — read-only drift detection (`HOLDER_MISMATCH`/`STATE_MISMATCH`/`COUNT_MISMATCH`/…), `run`/`repush`/`acknowledge` |

---

## Tier 3 — GLPI external facts  ✅ DECIDED (2026-09-07), data-gathering owed

| | Item | State |
|---|---|---|
| E1a | GLPI `states_id` → `AVAILABLE`/`IN_USE`/`UNAVAILABLE` buckets | ✅ set (33→AVAILABLE, 31/34/36/40→IN_USE, rest + 0/null→UNAVAILABLE) |
| E1a Q6 | `states_id` vs `users_id` contradiction | ✅ raise a **D9 drift row** |
| E1a write | `syncTargets` (movement → state) | ✅ ENTREGA→31, PRESTAMO→34 (assets + countables), DEVOLUCION/RETURN→33, LOST→45 |
| E1b | generic Type/Manufacturer/Model ids | ✅ config shape = per-itemtype maps (`genericTypeIds`/`genericModelIds`) + one global `genericManufacturerId`. **Rows don't exist in GLPI yet.** |
| E2a | Sede = GLPI `Location` | ✅ confirmed (`locations_id` field 3, tree) |
| E2b | address + recipients | ✅ **Remito dest = catalog Sede only** (must map to a real GLPI `Location`; no free text / no create-on-the-fly); address read from that `Location`, **no middleware `SEDE_SHIPPING_INFO`**; `destinationRecipients` dropped for v2.0 (deferred desktop feature) |
| N1 | countable backing | ✅ **v2.0 = `Peripheral`-only** (2026-09-07); `Consumable` path deferred to a later release |
| N2 | itemtype coverage | ✅ **all 9 in v2.0** (2026-09-07): Core 5 (Computer/Peripheral/Phone/Monitor/Printer) direct; custom assets (Multimedia/AudioEquipment/Security/Misc) also in, each gated on a field/workflow investigation — Multimedia is priority (much delivered gear is Multimedia) |
| N3 | per-instance field ids | ✅ noted (composite ids; resolve via `listSearchOptions` at startup) |

**GLPI-admin data / investigation still owed** (not decisions):
1. Create the "Genérico" rows in GLPI for the Core 5 (one `Manufacturer`; one per `<X>Type`/`<X>Model`) → look up their ids.
2. `GET /Location?range=0-200` → confirm sedes exist as Location records → build the Sede→Location-id map (create any missing). Must be complete for every Sede that can be a Remito destination (E2b — unmapped Sede = hard error).
3. **Custom-asset investigation** (Multimedia/AudioEquipment/Security/Misc) vs dev GLPI: `states_id`/`users_id`/`locations_id` present? Type/Model dropdowns? Fields container 8 attached? Plan for the `states_id=0` Multimedia inventory. Feeds their generic rows + movement path.

---

## Tier 4 — middleware-internal

| | Item | State |
|---|---|---|
| F1 | GLPI write identity | ✅ **per-user**: one shared `App-Token` (admin config) + each technician's own `user_token` (`PUT /me/glpi-token`, `APP_USER.glpi_token_encrypted`) |
| F2 | secret storage / rotation | ⚪ deploy-phase (the per-user GLPI token store is already built) |

## Tier 5 — defer to implementation

G1 list-summary field list · G2 audit table schema · G3 API deprecation policy — ⚪ decided as they come up during the build.

## Tier 6 — desktop feature-removal sign-offs (Phase 6)

H1 remove Base de Datos "CATÁLOGO DE EQUIPOS" · H2 remove Configuración "S/N Validation" panel · H3 remove `CatalogMigrationTool` · H4 remove obsolete catalog tests — 📋 done as part of the desktop cutover (Phase B).

---

## Middleware build (branch `backend-api-v2-glpi-adapter`)

### Backend-agnostic core — ✅ built (inherited from v1)

| Module | State |
|---|---|
| Scaffold — security chain, `ApiException` envelope, Flyway schema, OpenAPI | ✅ |
| Auth — `/auth/config`, `/auth/login` (JWKS), `/auth/logout`, `/me` + session store | ✅ (never run vs a live Keycloak) |
| RBAC — `Permission` enum, `ROLE`/`ROLE_PERMISSION`, `PermissionGuard` (Sede fence) | ✅ — **enum shrinks 12→3 in the strip** (`APPROVE_NOTES`/`SYNC_EXTERNAL`/`VALIDATE_RETURNS`); `PUT /config` becomes **SUPERADMIN role-gated**, not a permission |
| Audit — `AUDIT_LOGIN`/`STOCK`/`ITEM_STATUS`/`ADMIN_ACTION`, reads + all write points | ✅ |
| Directory — `GET /directory/users` (ports `AdApiService.search` compensation logic) | ✅ |
| Notes / History / Approval + item sync/return + Remito de Envío | ✅ — **stock movement + `NOTE_ITEM` FKs + "modifica stock" removed in the strip; Remito reworked to `destinationSedeId`-only per E2b** |
| Config — `GET`/`PUT /config` | ✅ — **strip: drop 4 SMTP columns + SMTP encryption/permission branch + generic-id resolution; `GET`/`PUT` both stay, `PUT` SUPERADMIN-only. UI toggles (clear forms / close tab) stay desktop-local, no `/me/preferences`.** |

### GLPI-adapter layer

| Piece | State |
|---|---|
| `port/` interfaces (`CatalogPort`/`AssetPort`/`MovementPort`) + generic DTOs | ✅ skeleton (`c8a81d0`) |
| `adapters/glpi/GlpiAdapterProperties` (state/sync maps pre-filled; generic-id + Sede maps empty) | ✅ skeleton |
| `GlpiClient` (`initSession`/`killSession` shape) + `GlpiUserTokenResolver` | ✅ skeleton (untested vs live GLPI) |
| `Glpi*Adapter` port implementations | 🔨 `NOT_IMPLEMENTED` stubs |
| F1 — `PUT /me/glpi-token` + `APP_USER.glpi_token_encrypted` | ✅ built (`c8a81d0`) |
| **The strip** — remove `catalog/` CRUD, `MODEL_STOCK`, S/N validation, approval-time stock, `NOTE_ITEM_STOCK_EXCEPTION`, catalog FK columns, `Permission` 12→3, SMTP columns + email-note feature, `SEDE_SHIPPING_INFO` + `NOTE_REMITO_OTHER`; rework Remito to `destinationSedeId`-only | ⏳ **next after the contract fold** |
| Real `adapters/glpi/` implementation (catalog reads, asset lookup, movement writes) | 🚫 blocked on GLPI-admin data + a live GLPI to test against |
| §10 external write queue (`202`, worker, `FAILED`/retry/abandon, admin alert) | 📋 not started — **confirmed IN the v2.0 first release** (2026-09-07), not a fast-follow. Full shape: `202 QUEUED {jobId}`, one worker per model, retry→abandon+alert, `GET .../sync/status`, desktop-side "queued" handling. |
| §14 reconciliation (`/reconciliation/*`, nightly + on-demand, drift rows) | 📋 not started — **confirmed IN the v2.0 first release** (2026-09-07), full shape: `POST /reconciliation/run` (nightly + manual), `GET /reconciliation/drift`, `POST /{id}/repush` (corrective re-queue via §10), `POST /{id}/acknowledge`, full drift taxonomy incl. the Q6 intra-GLPI contradiction row. |
| `core/` wired to the ports (endpoints delegate to the adapter instead of local SQL) | 📋 not started |
| Package move into `core/` per D1 layout | ⚪ optional, with the wiring |

---

## Phase B — desktop app → REST client  📋 PLANNED, NOT STARTED

Plan: `wondrous-singing-shannon.md` (**stale** — it assumed v1-no-adapter was the
cutover target; now downstream of the adapter). Shape unchanged:
`MiddlewareClient` + session → OIDC+PKCE `OidcLoginFlow` + login-screen rewrite →
`Rest*Service` swapped into `ServiceLocator` per domain → `MainController`
connectivity rework → cleanup commit deleting the local-DB stack. "Email note"
feature dropped (D3). Includes the Tier 6 removals (H1–H4).

---

## External blockers / owed

| | State |
|---|---|
| **Keycloak wiring** — org Keycloak IS deployed; middleware needs: issuer URI, public client id (PKCE, loopback redirects), username claim, groups claim | 🚫 waiting on those values; `middleware.idp.*` still blank |
| **GLPI generic rows + Sede→Location map** (Tier 3 data above) | 🚫 GLPI admin |
| **Custom-asset investigation** (Multimedia/AudioEquipment/Security/Misc field set + workflow vs dev GLPI) | 🚫 not started — blocks the custom-asset adapter path, not Core 5 |
| **SQL Server instance** for the middleware (`sqlserver` profile) | 🚫 not provisioned; dev runs H2 with no real schema |
| **Middleware deployment** | 🚫 runs only via `mvn spring-boot:run` |
| **Live GLPI to test the adapter against** | 🚫 dev `<dev-glpi-host>` exists; adapter code not written yet |

---

## Next steps, in order

1. ⏳ **Contract fold** — write the Tier 3/4 decisions into `backend-contract.md` (§8/D5b, §3.3/§7.4, §3.1–3.4, §2.6/§7) + `contract-behaviors.md` (status buckets, Q6, "no `user_token` → can't sync").
2. **The strip** — remove the no-adapter compensations per D3/D4/D5 (see the middleware-build table). Gets the branch to a clean core-only compiling + green state.
3. **Real GLPI adapter** — implement `Glpi*Adapter` against dev GLPI (needs the generic rows + Sede map).
4. **Wire `core/` → ports**; endpoints delegate to the adapter.
5. **§10 write queue** + **§14 reconciliation**.
6. **Phase B** — desktop cutover (its own planning pass; re-derive from `wondrous-singing-shannon.md`).
7. **Deploy** — Keycloak wired, SQL Server provisioned, middleware deployed, `APP_USER`/roles/tokens seeded.
