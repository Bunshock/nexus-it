# PostgreSQL setup

Scripts to provision a remote PostgreSQL database for Generador de Notas IT. See `docs/database.md` for the full schema (ERD) and `README.md`'s "Remote database (PostgreSQL)" section for how the app connects to it.

## Files

| File | Purpose |
|------|---------|
| `01-schema.sql` | Creates every table. Contains no organization data (pure structure), so it's committed as-is. **Optional to run** — the app creates this automatically the first time it connects to a configured, empty database (`RemoteDatabaseService.ensureSchema()`). Run it by hand only if you want the database provisioned before ever launching the app, or want the DDL under change review. |
| `02-seed-equipment.sql.example` | **Template**, not real data — same pattern as `config/app-config.json.example`. Has a handful of placeholder Type/Brand/Model rows and provider rows illustrating the format. Copy it to `02-seed-equipment.sql` (gitignored — see repo root `.gitignore`) and replace the example rows with your organization's actual equipment catalog and provider list before running it. Never commit the real, filled-in file. |

Both scripts are idempotent — safe to re-run; they only insert rows that don't already exist.

## Setting up a remote server

1. **Install PostgreSQL** on the server machine (any recent version — the app has no version-specific requirements). On Windows Server, the official installer from postgresql.org works; on Linux, use your distro's package manager.
2. **Create a database and a login role** for the app, e.g.:
   ```sql
   CREATE DATABASE notas_it;
   CREATE USER notas_it_app WITH PASSWORD '<a strong password>';
   GRANT ALL PRIVILEGES ON DATABASE notas_it TO notas_it_app;
   ```
3. **Allow remote connections**, if the app runs on a different machine than the database server:
   - In `postgresql.conf`, set `listen_addresses = '*'` (or the specific interface).
   - In `pg_hba.conf`, add a line allowing the app's machine(s)/subnet to connect, e.g. `host notas_it notas_it_app 10.0.0.0/24 scram-sha-256`.
   - Open the PostgreSQL port (default `5432`) in the server's firewall.
   - Restart PostgreSQL for `postgresql.conf` changes to take effect.
4. **Copy the seed template and fill in your real data**:
   ```bash
   cp 02-seed-equipment.sql.example 02-seed-equipment.sql
   # edit 02-seed-equipment.sql: replace the example rows with your organization's actual
   # Type/Brand/Model catalog and provider list
   ```
5. **Run the scripts** against the new database:
   ```bash
   psql -h <server-host> -U notas_it_app -d notas_it -f 01-schema.sql
   psql -h <server-host> -U notas_it_app -d notas_it -f 02-seed-equipment.sql
   ```
   (Skip `01-schema.sql` if you'd rather let the app create the schema itself on first connect — see the table above.)
6. **Point the app at it**: launch the app, open **Configuración → Base de Datos → ✏ Editar** (admin mode required), and enter the host, port, database name, username, and password from step 2. Saving tests the connection before persisting. From then on, the app runs `ensureSchema()` on every startup (a no-op if the tables already exist) and reads/writes through `RemoteDatabaseService`, falling back to local SQLite automatically if the remote database becomes unreachable.

## S/N validation rules — not a SQL script

Unlike the equipment catalog, **S/N validation rules aren't seeded via SQL and don't need to be** — `SN_VALIDATION` is meant to start empty. The app's own "Validación de S/N por modelo" screen (**Configuración → Validación de S/N por modelo → Ver tabla**, admin mode) already shows *every* asset-type model via a `LEFT JOIN` against `SN_VALIDATION`, whether or not a rule exists yet for that model — models without a rule just show blank/inactive. From there, an admin sets the regex and toggles a rule active per model directly through the UI (`upsertSnValidation()`), which is the only way rows land in that table; there's no bulk-import path today.

Practical order of operations for a fresh remote database: run your filled-in `02-seed-equipment.sql` first (so the models exist to attach rules to), then configure S/N validation rules one by one through the app's UI afterward. If you have a large number of rules to set up and typing them one by one in the UI is impractical, let me know — a one-off SQL script for a *specific* set of rules is easy to generate once you have the exact regex-per-model list, it just isn't a generic "starting data" script the way the equipment catalog is, since S/N rules are inherently org-specific and don't have a sensible universal default.
