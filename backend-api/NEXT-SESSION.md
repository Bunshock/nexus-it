# v1 (no-adapter) middleware — resume / what's left

Where the `backend-api-v1-no-adapter` line stands and what's needed to call it
"fully functional". Companions: `IMPLEMENTED_ENDPOINTS.md` (what's built, per
endpoint), `MIDDLEWARE-COMPARE.md` (per-feature v1-vs-v2 diff — keep updated).

- **Branch**: `backend-api-v1-no-adapter`, **GitHub only** (not replicated to
  GitLab — routing rule: only `backend-api-v2-glpi-adapter` goes to GitLab).
- **This version ships first** — internal-DB catalog + stored stock, no external
  asset system. The GLPI-adapter version (`backend-api-v2-glpi-adapter`,
  `MIDDLEWARE.md`) is the long-term target; it's paused.
- **Baseline**: `mvn test` → **129 tests green**. Dev runs H2; `V1__init.sql` is
  real T-SQL that has never run against a real SQL Server (see #6).

_Last updated: 2026-09-09._

---

## Done (2026-09-08, pushed)

| # | | commit |
|---|---|---|
| 1 | Audit pagination: the three `AuditRepository` read queries ordered by a bare timestamp with no tiebreaker → non-deterministic `OFFSET` paging when rows tie on the same second. Added `, id DESC`. | `1196c0a` |
| 2 | Technician name + DNI resolved from the directory at login — `AuthController.login` calls `DirectoryService.findExactByUsername` (exact match only, never a substring hit), stamps the real full name + DNI onto the session. Best-effort: a directory outage is caught, login still succeeds (degrades to username + null DNI). | `7687100` |
| 3 | `GET /roles/{role}/permissions` — enforcement was already built, only the read endpoint was missing. `RolesController`, session-only, `[]` for an unknown role. First direct DB test for `getPermissionsForRole`. | `2a80ecf` |
| 4 | `GLPI_RETURN` tracking dimension — seeded `PENDING` when a returnable-Provider asset's `RETURN` reaches `RETURNED`; driven by `POST .../sync-return` / `.../reject-sync-return`; `COALESCE(gr, g)` makes it supersede the original `GLPI` status in History summary counts; `409 RETURN_NOT_VALIDATED` before the return is validated. | `bfbf7f9` |

Plus `MIDDLEWARE-COMPARE.md` (`fff31aa`).

## Done (2026-09-09, not yet committed)

| # | | |
|---|---|---|
| 5 | **`aud` check in `IdpTokenValidator`** — `buildDecoder()` now casts the Nimbus decoder and sets `createDefaultWithValidators(new JwtIssuerValidator(issuer), audienceValidator(clientId))`, so a valid same-realm token minted for a *different* client is rejected `401 INVALID_IDP_TOKEN`. Enforced only when `middleware.idp.client-id` is set (blank → skipped + `log.warn`, matching the `allowed-group-name` "blank = off" convention; every real deployment sets it). `audienceValidator(String)` is package-private static. Test: `IdpTokenValidatorTest` (4 cases, pure predicate, no context). **129 → 133 tests green.** |
| 6 | **`backend-api/local-keycloak/` harness** — `docker-compose.yml` (Keycloak 26.x, `--import-realm`, port 8081), `realm/siglo21-realm.json` (public PKCE `S256` client `nexus-it` with `groups`+`aud` mappers, a second `other-app` client for the aud-rejection test, group `nexus-it-users`, 3 users), `dev-auth-schema.sql` + `dev-auth-seed.sql` (minimal `ROLE`/`APP_USER`/`ROLE_PERMISSION`/`AUDIT_LOGIN` for the `dev` H2 profile via `spring.sql.init`, written for `MODE=MSSQLServer`), and `README.md` — an 8-step runbook (happy path, `USER_NOT_REGISTERED`, `aud` rejection, group gate + `bypass_group_check`, fail-closed check). **App-side validated without Docker**: boots clean on `dev` with the harness env vars, `GET /auth/config` serves the harness issuer, `POST /auth/login` reaches the new `buildDecoder()` path (`503 IDP_UNREACHABLE`, KC down). The token-exchange steps need Docker — the user runs those. |

---

## Blocked — need infrastructure (both confirmed in scope)

### #5 — validate against a real Keycloak — **code + harness ready, only the run is blocked**

The `aud` code fix and the local-Keycloak harness are both **done** (see "Done
2026-09-09" above). What's left is genuinely just running it:

- **Blocked**: no Docker / Podman on the current machine.
- **To run it**: `cd backend-api/local-keycloak` → follow `README.md`. Against a
  **local** container it's fully self-contained (no org values needed). Against the
  **org** Keycloak, only `MIDDLEWARE_IDP_ISSUER_URI` / `MIDDLEWARE_IDP_CLIENT_ID`
  change (real values in the local, uncommitted `keycloak-integration.md` on the
  `backend-api-v2-glpi-adapter` worktree) — plus `SPRING_PROFILES_ACTIVE=sqlserver`,
  `DB_*`, `MIDDLEWARE_SECURITY_ENCRYPTION_KEY`, and a provisioned `APP_USER`.
- **Still owed regardless of the run** (hardening, `keycloak-integration.md §4`):
  `SessionStore` eviction (`@Scheduled` sweep / size cap; needed before a 2nd
  replica) and HTTPS enforcement for `middleware.baseUrl`. Neither is blocked.

### #6 — validate against a real SQL Server

- **Blocked**: no Docker, no SQL Server LocalDB (only `SQLCMD.EXE`, a client).
- Every repository test runs against **hand-maintained H2 stand-in schemas**
  (`src/test/resources/*-repository-test-schema.sql`) — the real
  `V1__init.sql` / `V2__config.sql` T-SQL is never executed by the suite. The
  `sqlserver` Spring profile has never booted against a real instance.
- **Path forward**:
  1. Verify the `sqlserver` profile is complete (Flyway on, JDBC URL / driver,
     no H2 leakage; the schema files parse as SQL Server T-SQL).
  2. A `docker-compose.yml` for `mssql/server` + a README: bring it up, run the
     app on `SPRING_PROFILES_ACTIVE=sqlserver` (Flyway applies `V1`/`V2`), then a
     curl smoke script hitting the main read/write endpoints.
  This actually exercises the real migrations for the first time.

---

## Minor / pre-deploy (not started)

- `swagger-ui` + `/v3/api-docs` are `permitAll` in `SecurityConfig` — fine for
  dev, decide before a real deployment whether the API shape should be public.
- Audit read endpoints (`GET /audit/*`) are ADMIN/SUPERADMIN but **not
  Sede-scoped** — a plain ADMIN sees every Sede's rows. Flagged as a v1
  simplification in `IMPLEMENTED_ENDPOINTS.md`.
- The user-editable "Nombre para mostrar" greeting *preference* (a per-user
  override on top of the AD name) isn't ported — needs its own table + endpoint.
  Small.

---

## Deliberate v1 deviations — NOT gaps, don't "fix" them

Legitimate under `backend-contract.md` §1.1 / §3.4's "adapter detail" carve-out
(v1 took the internal-counter branch of a fork the design left open). Documented
in `IMPLEMENTED_ENDPOINTS.md` and `MIDDLEWARE-COMPARE.md`:

- stored `MODEL_STOCK` counter (+ approval-time stock movement + `409
  STOCK_WOULD_GO_NEGATIVE`) instead of derived "Model Y"
- synchronous `sync` / `return` status flips (`200`) instead of the §10 queue
- server-resolved Sede (from the caller's `APP_USER`) instead of a
  client-supplied `sedeId` on `POST /notes` — this one the **v2 contract should
  actually adopt** (tracked in `MIDDLEWARE-COMPARE.md`'s "Open reconciliations")
- 12-value `Permission` enum (catalog/config/S-N screens stay in the app)
