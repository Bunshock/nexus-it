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

_Last updated: 2026-09-08._

---

## Done (2026-09-08, pushed)

| # | | commit |
|---|---|---|
| 1 | Audit pagination: the three `AuditRepository` read queries ordered by a bare timestamp with no tiebreaker → non-deterministic `OFFSET` paging when rows tie on the same second. Added `, id DESC`. | `1196c0a` |
| 2 | Technician name + DNI resolved from the directory at login — `AuthController.login` calls `DirectoryService.findExactByUsername` (exact match only, never a substring hit), stamps the real full name + DNI onto the session. Best-effort: a directory outage is caught, login still succeeds (degrades to username + null DNI). | `7687100` |
| 3 | `GET /roles/{role}/permissions` — enforcement was already built, only the read endpoint was missing. `RolesController`, session-only, `[]` for an unknown role. First direct DB test for `getPermissionsForRole`. | `2a80ecf` |
| 4 | `GLPI_RETURN` tracking dimension — seeded `PENDING` when a returnable-Provider asset's `RETURN` reaches `RETURNED`; driven by `POST .../sync-return` / `.../reject-sync-return`; `COALESCE(gr, g)` makes it supersede the original `GLPI` status in History summary counts; `409 RETURN_NOT_VALIDATED` before the return is validated. | `bfbf7f9` |

Plus `MIDDLEWARE-COMPARE.md` (`fff31aa`).

---

## Blocked — need infrastructure (both confirmed in scope)

### #5 — validate against a real Keycloak

The auth code (`GET /auth/config`, `POST /auth/login` JWKS validation, the
registration / allowed-group / `bypass_group_check` paths) has **never run
against a real Keycloak**.

- **Blocked**: no Docker / Podman / standalone Keycloak on the current machine.
- **Real realm values** (issuer, client id, claims, allowed group) are in the
  local, uncommitted `keycloak-integration.md` on the `backend-api-v2-glpi-adapter`
  worktree — not in this repo.
- **Also needs a code fix first**: `IdpTokenValidator` validates
  `iss` / `exp` / signature only — **add the `aud` check** (a realm token for a
  different client must be rejected).
- **Path forward** (code-side prep so it becomes a checklist):
  1. Add the `aud` `JwtClaimValidator` to `IdpTokenValidator`.
  2. A `docker-compose.yml` for Keycloak + a realm-export JSON shaped like the
     real realm: public client, PKCE `S256`, loopback `/callback` redirect
     (path-only), `groups` (bare names) + `aud` mappers, a couple of test users.
  3. A step-by-step: get a token from the KC token endpoint → `POST /auth/login`
     → verify the profile snapshot, the group gate, `USER_NOT_REGISTERED`.
  Then either the user runs it, or installs Docker and a session drives it.

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
