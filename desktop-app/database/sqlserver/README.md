# Microsoft SQL Server (Express) setup

Scripts to provision a remote SQL Server database for Generador de Notas IT. See `docs/database.md` for the full schema (ERD) and `README.md`'s "Remote database (SQL Server)" section for how the app connects to it.

## Files

| File | Purpose |
|------|---------|
| `01-schema.sql` | Creates every table. Contains no organization data (pure structure), so it's committed as-is. **Optional to run** — the app creates this automatically the first time it connects to a configured, empty database (`RemoteDatabaseService.ensureSchema()`). Run it by hand only if you want the database provisioned before ever launching the app, or want the DDL under change review. |
| `02-seed-equipment.sql.example` | **Template**, not real data — same pattern as `config/app-config.json.example`. Has a handful of placeholder Type/Brand/Model rows, S/N validation rules, and provider rows illustrating the format. Copy it to `02-seed-equipment.sql` (gitignored — see repo root `.gitignore`) and replace the example rows with your organization's actual equipment catalog, S/N patterns, and provider list before running it. Never commit the real, filled-in file. |
| `provisioning/` | The same schema as `01-schema.sql`, split into 6 small feature-scoped scripts (Sede, Equipment, Users, Permissions, Notes, Audit) plus matching starting-data templates — meant for handing to a DB team reviewing/provisioning a brand-new database feature-by-feature, rather than one large file. See `provisioning/README.md` for the full breakdown, execution order, and an explicit answer to "is there anything beyond plain CREATE TABLE / data" (constraints/indexes: yes; triggers/stored procedures/views: none exist anywhere in this schema). |

Both scripts are idempotent — safe to re-run; they only insert rows that don't already exist. (The scripts under `provisioning/` are the one exception — see that folder's own README for why.)

## Setting up SQL Server Express

**The one thing that trips people up coming from other databases**: SQL Server Express typically installs as a **named instance** (commonly `SQLEXPRESS`) using a **dynamic port** discovered via the SQL Server Browser service (UDP 1434) — not a fixed, predictable port the way PostgreSQL's 5432 or MySQL's 3306 are by default. This app connects with a plain `host:port`, with no support for named-instance discovery, so step 3 below (assigning a static port) is **not optional** — skip it and the app will never be able to connect no matter how correct the host/credentials are.

1. **Install SQL Server Express** on the server machine (free download from microsoft.com — choose the "Basic" or "Custom" install type, either works). During setup, note the instance name (default `SQLEXPRESS`) and switch authentication mode to **"Mixed Mode" (SQL Server and Windows Authentication)** — the app connects with a SQL login (username/password), not Windows Authentication.
2. **Enable TCP/IP and assign a static port**, via **SQL Server Configuration Manager**:
   - SQL Server Network Configuration → Protocols for `<instance name>` → enable **TCP/IP**.
   - Right-click TCP/IP → Properties → **IP Addresses** tab → scroll to the **IPAll** section at the bottom → clear **TCP Dynamic Ports** (leave it blank) → set **TCP Port** to a fixed value (e.g. `1433`, the SQL Server default — use a different value if something else on that machine already uses 1433).
   - Restart the **SQL Server (`<instance name>`)** service (and the SQL Server Browser service, if you'd rather leave dynamic discovery available for other tools — not needed by this app) for the change to take effect.
   - Open that port in the server's firewall.
3. **Create a database and a SQL login** for the app, e.g. via SQL Server Management Studio (SSMS) or `sqlcmd`:
   ```sql
   CREATE DATABASE notas_it;
   GO
   CREATE LOGIN notas_it_app WITH PASSWORD = '<a strong password>';
   GO
   USE notas_it;
   GO
   CREATE USER notas_it_app FOR LOGIN notas_it_app;
   ALTER ROLE db_owner ADD MEMBER notas_it_app;
   GO
   ```
4. **Copy the seed template and fill in your real data**:
   ```bash
   cp 02-seed-equipment.sql.example 02-seed-equipment.sql
   # edit 02-seed-equipment.sql: replace the example rows with your organization's actual
   # Type/Brand/Model catalog and provider list
   ```
5. **Run the scripts** against the new database:
   ```bash
   sqlcmd -S <server-host>,1433 -U notas_it_app -P <password> -d notas_it -i 01-schema.sql
   sqlcmd -S <server-host>,1433 -U notas_it_app -P <password> -d notas_it -i 02-seed-equipment.sql
   ```
   (Skip `01-schema.sql` if you'd rather let the app create the schema itself on first connect — see the table above. Adjust the port in `-S host,port` if you chose something other than 1433 in step 2.)
6. **Point the app at it**: launch the app, open **Configuración → Base de Datos → ✏ Editar** (admin mode required), and enter the host, the static port from step 2 (default `1433`), database name, username, and password from step 3. Saving tests the connection before persisting. From then on, the app runs `ensureSchema()` on every startup (a no-op if the tables already exist) and reads/writes through `RemoteDatabaseService`, falling back to local SQLite automatically if the remote database becomes unreachable.

## Already have a real catalog in the local SQLite database?

If you've been building up the real equipment catalog (and S/N validation rules) locally through the app's own "Base de Datos" UI — e.g. because a remote SQL Server wasn't available yet — you don't need to re-type everything into `02-seed-equipment.sql`. `CatalogMigrationTool` (`src/main/java/.../utils/CatalogMigrationTool.java`) copies Type, Brand, Brand-Type links, Model, S/N validation rules, and Provider straight from `data/noteapp.db` into a configured SQL Server database, remapping autoincrement ids correctly along the way (SQL Server's `IDENTITY` columns assign their own ids independent of whatever SQLite happened to use locally). It creates the schema itself (same `ensureSchema()` call the app makes), so an empty database is all you need on the SQL Server side — you can skip `01-schema.sql` entirely if using this path. Run it from `desktop-app/`:

```bash
mvn exec:java -Dexec.mainClass="com.bunshock.note_app_for_it_frontend.utils.CatalogMigrationTool"
```

It prompts interactively for the connection details and is safe to re-run (upserts by name, so running it again after adding more local data only inserts what's new). Note this only covers the equipment catalog — it does not migrate History (`NOTE_REPORT` and related tables).

## S/N validation rules

`02-seed-equipment.sql.example` covers `SN_VALIDATION` too — a rule per model, resolved by type+brand+model name to the model the schema actually assigned once it exists, delete-then-inserted so editing a rule and re-running the script updates it rather than duplicating it. Fill in your organization's real regex patterns in the same file, same as the equipment catalog rows above.

You don't have to use the script, though — the app's own "Validación de S/N por modelo" screen (**Configuración → Validación de S/N por modelo → Ver tabla**, admin mode) shows *every* asset-type model via a `LEFT JOIN` against `SN_VALIDATION`, whether or not a rule exists yet for that model, and lets an admin set the regex and toggle a rule active per model directly (`upsertSnValidation()`) — fine for a handful of rules, more tedious for a large catalog. If you've already set up rules locally this way (through the SQLite database), `CatalogMigrationTool` (see above) copies them over along with the rest of the catalog, so you don't need to also fill in this SQL Server template by hand in that case.
