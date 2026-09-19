# Provisioning — one-time starting data for a fresh SQL Server database

The middleware's **schema** is owned by Flyway (`src/main/resources/db/migration/V1__init.sql`,
`V2__config.sql`) and is applied automatically the first time the app boots on the `sqlserver`
profile. The **data** a real installation needs before anyone can do anything is not — nothing
seeds it outside the `dev` profile (`dev-seed.sql`). This folder holds templates for that data.

Do **not** move these files into `db/migration/` — Flyway would try to execute them.

## Why this is required before go-live

On an empty production database the middleware boots and login can succeed against Keycloak, but:

- with no `APP_USER` row for the technician, login fails with `403 USER_NOT_REGISTERED`;
- with no `ROLE_PERMISSION` rows, every permission check denies (deny-by-default), so even a
  SUPERADMIN can do nothing after logging in;
- with no `SEDE` rows, no technician can be assigned a Sede, and notes cannot be created.

## Run order

1. Point the middleware at the (empty) database and boot it once with
   `SPRING_PROFILES_ACTIVE=sqlserver` — Flyway creates the schema and seeds `ROLE` and the default
   `APP_CONFIG`. See `../local-sqlserver/README.md` for a local walkthrough.
2. For each template below: copy `NN-name.sql.example` to `NN-name.sql`, replace the placeholder
   rows with real data, and run it against the database, **in numeric order**.

| # | Data | Template | Notes |
|---|------|----------|-------|
| 1 | Sedes (+ optional Remito shipping info) | `01-sede-data.sql.example` | Direct-SQL only in v1 |
| 2 | Equipment catalog, S/N rules, providers, per-Sede stock | `02-equipment-data.sql.example` | Type/Brand/Model also editable later in the app; Provider is not |
| 3 | Users (role, Sede, group-check bypass) | `03-users-data.sql.example` | Provision at least one SUPERADMIN |
| 4 | Role permission grants | `04-permissions-data.sql.example` | Real security config — review before running |

The filled-in copies (`NN-name.sql`) are gitignored — never commit real usernames, site names, or
inventory. Each script is meant to run **once**; re-running fails on a duplicate key, on purpose.

Notes and the audit trail have no template: those tables are written only by the middleware at
runtime. `APP_CONFIG` (A/F format, motivo lists, etc.) is seeded by `V2__config.sql` and edited
through `PUT /api/v1/config`, gated per field group by the `EDIT_AF_FORMAT_CONFIG` /
`EDIT_SMTP_CONFIG` grants in `ROLE_PERMISSION`.

## Ongoing changes are direct SQL

There is no in-app management for `APP_USER`, `ROLE_PERMISSION`, `SEDE`, `SEDE_SHIPPING_INFO`, or
`PROVIDER` — by design. Promote/demote a technician with an `UPDATE APP_USER SET role_id = ...`,
retire a Sede or Provider with `deprecated = 1` (never `DELETE` — old notes reference them), and
change a Sede's shipping info by deprecating the old row and inserting a new one. A technician's
role and Sede are snapshotted into their session at login, so a change to those takes effect at
their next login; `ROLE_PERMISSION` grants are read live on every request, so a grant change
applies immediately to everyone holding that role.

## Related configuration (not SQL)

- `middleware.idp.allowed-group-name` (`MIDDLEWARE_IDP_ALLOWED_GROUP_NAME`): the group a token must
  carry to log in. Blank means the check is skipped. `APP_USER.bypass_group_check = 1` overrides it
  per account.
- The Keycloak `preferred_username` must equal `APP_USER.username`.

## How these differ from the old desktop-app provisioning scripts

They replace the desktop repo's local-only `database/sqlserver/provisioning/`. The six per-feature
**schema** scripts were dropped: they were the source `V1__init.sql` was derived from, differing
only in that V1's `AUDIT_ITEM_STATUS.status_kind` also allows `GLPI_RETURN`, and keeping a second
copy would only let it drift. The two empty data stubs (notes, audit) were dropped too. The four
data templates were kept and their headers updated for the middleware (Keycloak usernames, Flyway,
`APP_CONFIG.generic_label`).
