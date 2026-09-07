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
| N2 / N3 | itemtype flattening / per-instance field ids | ✅ noted (composite ids; resolve via `listSearchOptions` at startup) |

**GLPI-admin data still owed** (not decisions):
1. Create the "Genérico" rows in GLPI (one `Manufacturer`; one per `<X>Type`/`<X>Model`) → look up their ids.
2. `GET /Location?range=0-200` → confirm sedes exist as Location records → build the Sede→Location-id map (create any missing).

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
| RBAC — `Permission` enum, `ROLE`/`ROLE_PERMISSION`, `PermissionGuard` (Sede fence) | ✅ — **enum will shrink 12→3 in the strip** |
| Audit — `AUDIT_LOGIN`/`STOCK`/`ITEM_STATUS`/`ADMIN_ACTION`, reads + all write points | ✅ |
| Directory — `GET /directory/users` (ports `AdApiService.search` compensation logic) | ✅ |
| Notes / History / Approval + item sync/return + Remito de Envío | ✅ — **stock movement + `NOTE_ITEM` FKs + "modifica stock" removed in the strip** |
| Config — `GET`/`PUT /config` | ✅ — **`PUT` + SMTP write + generic-id resolution removed in the strip** |

### GLPI-adapter layer

| Piece | State |
|---|---|
| `port/` interfaces (`CatalogPort`/`AssetPort`/`MovementPort`) + generic DTOs | ✅ skeleton (`c8a81d0`) |
| `adapters/glpi/GlpiAdapterProperties` (state/sync maps pre-filled; generic-id + Sede maps empty) | ✅ skeleton |
| `GlpiClient` (`initSession`/`killSession` shape) + `GlpiUserTokenResolver` | ✅ skeleton (untested vs live GLPI) |
| `Glpi*Adapter` port implementations | 🔨 `NOT_IMPLEMENTED` stubs |
| F1 — `PUT /me/glpi-token` + `APP_USER.glpi_token_encrypted` | ✅ built (`c8a81d0`) |
| **The strip** — remove `catalog/` CRUD, `MODEL_STOCK`, S/N validation, approval-time stock, `NOTE_ITEM_STOCK_EXCEPTION`, catalog FK columns, `Permission` 12→3, `PUT /config` | ⏳ **next after the contract fold** |
| Real `adapters/glpi/` implementation (catalog reads, asset lookup, movement writes) | 🚫 blocked on GLPI-admin data + a live GLPI to test against |
| §10 external write queue (`202`, worker, `FAILED`/retry/abandon, admin alert) | 📋 not started |
| §14 reconciliation (`/reconciliation/*`, nightly + on-demand, drift rows) | 📋 not started |
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
