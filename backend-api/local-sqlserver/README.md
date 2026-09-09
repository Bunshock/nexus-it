# Local SQL Server — `sqlserver` profile + Flyway migration harness

The middleware's `sqlserver` Spring profile and its Flyway migrations
(`db/migration/V1__init.sql`, `V2__config.sql`) have **never run against a real SQL
Server**. Every repository test uses a hand-maintained H2 stand-in schema; the real
migrations are exercised by nothing. This harness runs them for the first time, against a
throwaway SQL Server container.

> **Prerequisite:** Docker (Desktop or Engine) with Compose v2, ~2 GB free RAM for the
> `mssql/server` container. Nothing else.

Nothing here is a real secret — `sa` / `Local_Dev_Pass123` is a dev-only password on a
container that's wiped with `down -v`. Safe to commit.

---

## What this validates

- `V1__init.sql` (fresh-install schema, 30 tables + 4 filtered indexes) parses and runs
  under Flyway's SQL Server dialect. **This file was rewritten 2026-09-09** from the
  desktop app's hand-run DBA script — the old copy had batch-scoped `DECLARE @var` used
  across statement boundaries, cursors, and `EXEC sp_executesql` that Flyway's statement
  splitter can't run. The new one is fresh-install DDL only.
- `V2__config.sql` runs and seeds `APP_CONFIG` + the motivo/falla/returnable-provider
  option tables.
- The `sqlserver` profile boots the app end to end (datasource, driver, Flyway
  auto-run on startup) with only `DB_USERNAME` / `DB_PASSWORD` supplied.
- Optionally (with `local-keycloak` also up): a real authenticated read hits the
  repositories against actual SQL Server, not H2.

---

## 1. Start SQL Server

```powershell
cd backend-api\local-sqlserver
docker compose up -d
docker compose logs mssql-init      # should print "created notesit"
docker compose ps                   # mssql = healthy; mssql-init = exited (0)
```

`mssql-init` is a one-shot that creates an empty `notesit` database once the server is
healthy — Flyway creates tables *within* a database, never the database itself.

## 2. Run the middleware on the `sqlserver` profile

From `backend-api/`. `DB_URL` defaults to the local container's JDBC URL
(`jdbc:sqlserver://localhost:1433;databaseName=notesit;encrypt=true;trustServerCertificate=true`),
so only the credentials need supplying:

**PowerShell**

```powershell
cd ..
$env:SPRING_PROFILES_ACTIVE = "sqlserver"
$env:DB_USERNAME            = "sa"
$env:DB_PASSWORD            = "Local_Dev_Pass123"
.\mvnw.cmd spring-boot:run
```

**bash**

```bash
cd ..
SPRING_PROFILES_ACTIVE=sqlserver DB_USERNAME=sa DB_PASSWORD=Local_Dev_Pass123 ./mvnw spring-boot:run
```

Watch the startup log for Flyway:

```
Flyway ... Migrating schema [dbo] to version "1 - init"
Flyway ... Migrating schema [dbo] to version "2 - config"
Flyway ... Successfully applied 2 migrations to schema [dbo] ...
```

If a migration fails, the app **does not start** — the stack trace names the failing
statement. That failure is itself the harness's finding; capture it.

> `middleware.security.encryption-key` and `middleware.idp.*` can stay blank here — boot,
> Flyway, and unauthenticated reads don't need them. `GET /auth/config` returns `503` (IdP
> not configured), which is expected on this profile alone.

## 3. Run the smoke checks

With the container up and the app started, from `backend-api/local-sqlserver/`:

```powershell
.\smoke.ps1          # PowerShell
```
```bash
./smoke.sh           # bash
```

Checks: `/health` is 200 · `flyway_schema_history` has V1 + V2 both `success=1` · the
core tables exist · V2 seed rows are present (16 motivo options, 3 roles).

Spot-check by hand if you prefer:

```powershell
docker compose exec mssql /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P Local_Dev_Pass123 `
  -d notesit -Q "SELECT version, description, success FROM dbo.flyway_schema_history ORDER BY installed_rank;"
```

## 4. Optional — full end-to-end with `local-keycloak`

To exercise the repositories (not just the schema) against real SQL Server:

1. Bring up `../local-keycloak` (see its README) **and** this container.
2. Run the app with **both** the `sqlserver` DB vars from step 2 **and** the Keycloak vars
   from `local-keycloak/README.md` step 2 (`MIDDLEWARE_IDP_ISSUER_URI`,
   `MIDDLEWARE_IDP_CLIENT_ID`) — but **omit** the `SPRING_SQL_INIT_*` vars (those seed the
   H2 dev-auth tables; on `sqlserver` the real `APP_USER`/`ROLE` come from Flyway + your
   seed SQL).
3. You'll need an `APP_USER` row — the `sqlserver` profile's `APP_USER` starts empty
   (unlike the dev-auth seed). Insert one to match a Keycloak realm user:
   ```powershell
   docker compose -f local-sqlserver/docker-compose.yml exec mssql /opt/mssql-tools18/bin/sqlcmd `
     -C -S localhost -U sa -P Local_Dev_Pass123 -d notesit -Q `
     "INSERT INTO APP_USER (username, role_id, sede_id, bypass_group_check) VALUES ('emanuel.arias', 2, NULL, 0); INSERT INTO ROLE_PERMISSION (role_id, permission) VALUES (2,'APPROVE_NOTES'),(2,'SYNC_GLPI'),(2,'VALIDATE_RETURNS');"
   ```
4. Get a token + session per `local-keycloak/README.md` step 4, then:
   `TOKEN=<sessionToken> ./smoke.sh` (or `$env:TOKEN` for PowerShell) — check 5 now runs a
   real `GET /api/v1/config` against SQL Server.

## 5. Tear down

```bash
docker compose down -v      # -v wipes the data volume; next `up` is a clean notesit
```

---

## Notes / gotchas

- **`sqlcmd` path is `/opt/mssql-tools18/bin/sqlcmd`** on the 2022 image (tools v18), and
  needs `-C` to trust the instance's self-signed TLS cert. An older image with tools v17
  uses `/opt/mssql-tools/bin/sqlcmd` and no `-C`.
- **The app's `DB_URL` already has `trustServerCertificate=true`** (`application.yml`
  `sqlserver` profile) — required, since the container's cert is self-signed. Same gotcha
  as the desktop app's `database/sqlserver/README.md`.
- **Flyway runs inside the app process on startup**, not as a separate step. There's no
  `flyway migrate` CLI here — starting the app on the `sqlserver` profile *is* running the
  migrations.
- **Re-running the app** against an already-migrated `notesit` is fine — Flyway sees V1/V2
  in `flyway_schema_history` and skips them. To re-test the migrations from scratch,
  `docker compose down -v && docker compose up -d`.
- **Not a substitute for the org's real SQL Server** — this is Developer Edition on Linux;
  collation, TLS, and instance config will differ. It validates the migrations and the
  profile wiring, not deployment specifics.
