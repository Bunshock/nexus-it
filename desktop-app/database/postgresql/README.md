# PostgreSQL setup

Scripts to provision a remote PostgreSQL database for Generador de Notas IT. See `docs/database.md` for the full schema (ERD) and `README.md`'s "Remote database (PostgreSQL)" section for how the app connects to it.

## Files

| File | Purpose |
|------|---------|
| `01-schema.sql` | Creates every table. Contains no organization data (pure structure), so it's committed as-is. **Optional to run** — the app creates this automatically the first time it connects to a configured, empty database (`RemoteDatabaseService.ensureSchema()`). Run it by hand only if you want the database provisioned before ever launching the app, or want the DDL under change review. |
| `02-seed-equipment.sql.example` | **Template**, not real data — same pattern as `config/app-config.json.example`. Has a handful of placeholder Type/Brand/Model rows, S/N validation rules, and provider rows illustrating the format. Copy it to `02-seed-equipment.sql` (gitignored — see repo root `.gitignore`) and replace the example rows with your organization's actual equipment catalog, S/N patterns, and provider list before running it. Never commit the real, filled-in file. |

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

## Already have a real catalog in the local SQLite database?

If you've been building up the real equipment catalog (and S/N validation rules) locally through the app's own "Base de Datos" UI — e.g. because a remote PostgreSQL server wasn't available yet — you don't need to re-type everything into `02-seed-equipment.sql`. `CatalogMigrationTool` (`src/main/java/.../utils/CatalogMigrationTool.java`) copies Type, Brand, Brand-Type links, Model, S/N validation rules, and Provider straight from `data/noteapp.db` into a configured PostgreSQL database, remapping autoincrement ids correctly along the way (Postgres's `SERIAL` sequence assigns its own ids independent of whatever SQLite happened to use locally). It creates the schema itself (same `ensureSchema()` call the app makes), so an empty database is all you need on the Postgres side — you can skip `01-schema.sql` entirely if using this path. Run it from `desktop-app/`:

```bash
mvn exec:java -Dexec.mainClass="com.bunshock.note_app_for_it_frontend.utils.CatalogMigrationTool"
```

It prompts interactively for the connection details and is safe to re-run (upserts by name, so running it again after adding more local data only inserts what's new). Note this only covers the equipment catalog — it does not migrate History (`NOTE_REPORT` and related tables).

## S/N validation rules

`02-seed-equipment.sql.example` now covers `SN_VALIDATION` too (added 2026-07-14, for parity with `database/sqlite/starter-template.sql.example` — the equivalent template for the local SQLite database) — a rule per model, resolved by type+brand+model name to the model the schema actually assigned once it exists, delete-then-inserted so editing a rule and re-running the script updates it rather than duplicating it. Fill in your organization's real regex patterns in the same file, same as the equipment catalog rows above.

You don't have to use the script, though — the app's own "Validación de S/N por modelo" screen (**Configuración → Validación de S/N por modelo → Ver tabla**, admin mode) shows *every* asset-type model via a `LEFT JOIN` against `SN_VALIDATION`, whether or not a rule exists yet for that model, and lets an admin set the regex and toggle a rule active per model directly (`upsertSnValidation()`) — fine for a handful of rules, more tedious for a large catalog. If you've already set up rules locally this way (through the SQLite database), `CatalogMigrationTool` (see above) copies them over along with the rest of the catalog, so you don't need to also fill in this Postgres template by hand in that case.
