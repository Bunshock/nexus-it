# SQLite starting data

The local database (`data/noteapp.db`) is created empty — schema only, no rows beyond the single baseline "Generic" brand the app's fallback logic expects. There is no automatic seeding: a fresh install, or a deleted-and-recreated `data/noteapp.db`, starts with a genuinely clean equipment catalog and history, ready to load real data through the app's own UI without any pre-existing rows mixed in.

## Files

| File | Purpose |
|------|---------|
| `demo-seed.sql` | **Optional, on-demand, illustrative demo data.** A realistic example equipment catalog (132 type/brand/model combinations) and 8 fictional history notes covering every GLPI status and note type. Not run automatically — load it yourself when you actually want demo data (e.g. before a demo, or as a starting example dataset). Not something you'd edit — the rows are fixed, fictional data. |
| `starter-template.sql.example` | **Template, not real data** — same pattern as `config/app-config.json.example` and `database/sqlserver/02-seed-equipment.sql.example`. Covers the equipment catalog, **S/N validation rules**, and providers, with a few example rows illustrating the syntax. Copy it to `starter-template.sql` (gitignored) and replace the example rows with your organization's real data before running it. |
| `provisioning/` | **Feature-by-feature version of the same starting-data idea**, covering Sede, Users, and Permissions in addition to the equipment catalog — a SQLite mirror of `database/sqlserver/provisioning/`, useful while seeding real organizational data locally before a remote SQL Server database exists. See `provisioning/README.md`. |

## Running either one

The database must already exist (start the app once, or let `DatabaseService` create it) before loading either script:

```bash
sqlite3 data/noteapp.db < database/sqlite/demo-seed.sql
# or, after copying and filling in your real data:
sqlite3 data/noteapp.db < database/sqlite/starter-template.sql
```

Both are safe to re-run. Equipment catalog inserts are `INSERT OR IGNORE`; `demo-seed.sql`'s history notes are skipped entirely as a group if `NOTE_REPORT` already has any rows; `starter-template.sql`'s S/N validation rules are replaced (delete-then-insert) per model, so editing a rule and re-running updates it rather than duplicating it.

## What about other persisted settings (SMTP, GLPI, AD, DB credentials)?

Not covered by either script here, deliberately. A/F number format, SMTP host/port, GLPI API URL, and AD API URL aren't database-persisted at all — they live directly in `config/app-config.json`. The actual secrets (SMTP password, GLPI API key, AD token, DB username/password) *are* persisted (encrypted, in `data/noteapp.db`'s `APP_SETTINGS` table), but there's already a dedicated mechanism for pre-configuring those before first run — `app-config.json`'s `defaults` section, with values pre-encrypted via `utils.AppKeyEncryptionGenerator` — see the root `README.md`'s "Pre-configuring default secrets" section. A parallel SQL-based way to set the same values would just be a second way to do the same thing.

## Starting completely fresh

To wipe the local database and start over with zero rows:

1. Close the app.
2. Delete `data/noteapp.db`.
3. Start the app — `DatabaseService.initialize()` recreates the schema (tables + the baseline "Generic" brand) but does not seed any equipment or history data.
4. Load your own real data through **Base de Datos** (admin mode), copy `starter-template.sql.example` and run it once filled in, or run `demo-seed.sql` if you want the example/demo dataset instead.

If you're preparing this local database to eventually migrate to a remote SQL Server, see `../sqlserver/README.md`'s "Already have a real catalog in the local SQLite database?" section for `CatalogMigrationTool` — it copies whatever's in `data/noteapp.db` (real data, demo data, or a mix) over once a SQL Server is available, so there's no need to enter the catalog twice.
