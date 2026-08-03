# Generador de Notas IT

Desktop application for IT support teams at Universidad Siglo 21 to generate equipment handover notes (Notas IT). Built with JavaFX 21 for Windows.

---

## Requirements

- Java 21 (JDK) — verify with `java -version`
- Maven 3.8+ — verify with `mvn -version`
- Windows recommended — the technician-identity lookup (`WindowsIdentityService`, resolves the current Windows session's UPN) only works on Windows. Credential encryption (`AppKeyEncryptionService`) is cross-platform (pure `javax.crypto`, no native dependency) and works on Linux/macOS too.
- Git (to clone the repository)

---

## Setup

```bash
git clone <repository-url>
cd notes-app-for-it/desktop-app

# First run — copy the config template, then edit it with your real values
cp config/app-config.json.example config/app-config.json

mvn clean javafx:run
```

> **Always use `mvn clean javafx:run`**, not just `mvn javafx:run`. The IDE (VS Code / Eclipse) can leave stale `.class` files in `target/` that Maven reuses without recompiling, causing runtime errors.

> **Never commit `desktop-app/data/noteapp.db`.** It's a runtime artifact created fresh on first launch and is already listed in `.gitignore`. If your local clone fails on startup with an error like `SQLITE_ERROR ... table NOTE_ITEM has no column named ...`, you likely have a stale database file left over from an old checkout — delete `desktop-app/data/noteapp.db` and re-run `mvn clean javafx:run` to regenerate it. The regenerated database starts genuinely empty (schema only, no demo data) — see `desktop-app/database/sqlite/README.md` if you want the optional example catalog/history dataset loaded instead of starting from a blank slate.

> **`desktop-app/config/app-config.json` is gitignored** — it holds real per-deployment values (internal AD API URL, SMTP sender address) that must never reach git history. `config/app-config.json.example` is the committed template with placeholder values; copy it once per machine and edit the copy. If you pull changes to `app-config.json.example` (new keys), diff it against your local `app-config.json` and merge the new keys in manually.

> **Want AD/SMTP/GLPI/DB already configured on first launch, with nothing to type in Settings?** Fill in `app-config.json`'s `defaults` section before running the app for the first time — see [Pre-configuring default secrets](#pre-configuring-default-secrets-zero-touch-first-run) below.

---

## Configuration

Two JSON files in `desktop-app/config/` control runtime behavior. **Do not commit sensitive data to these files.**

### `config/app-config.json`

| Key | Description |
|-----|-------------|
| `afFormat.prefix` | A/F number prefix (e.g. `"IT"`) |
| `afFormat.separator` | Separator character (e.g. `"-"`) |
| `afFormat.length` | Significant-part length in characters (e.g. `8`) |
| `afFormat.filler` | Left-padding character (e.g. `"0"`) |
| `afFormat.inputPattern` | Single-character regex restricting what the user can type in the A/F field (e.g. `"\\d"` for digits only, `"[A-Z0-9]"` for alphanumeric). Defaults to `"\\d"` if omitted. |
| `motivoOptions.entrega` | List of Motivo values for Entrega notes |
| `motivoOptions.devolucion` | List of Motivo values for Devolución notes |
| `motivoOptions.proveedor` | List of Motivo values for Provider notes |
| `fallaOptions` | Failure-cause combobox values shown by the Falla detail popup (Devolución only) |
| `smtp.host` | SMTP server host |
| `smtp.port` | SMTP port (587 for Gmail STARTTLS) |
| `smtp.senderAddress` | Sender email address |
| `adApi.baseUrl` | REST API URL for AD lookups |
| `glpiApi.baseUrl` | GLPI REST API URL |
| `remoteDatabase.host` | Remote SQL Server host — not a secret; leave `""` to keep the app on local SQLite |
| `remoteDatabase.port` | Remote SQL Server port (defaults to `1433`) |
| `remoteDatabase.dbName` | Remote SQL Server database name |
| `noteItemLimit` | Max items per note before showing a warning |

SMTP password and GLPI API key are stored encrypted in the local SQLite database (`AppKeyEncryptionService`, AES-256/GCM) — never in this file. Same for the remote database's username/password — see below.

### Pre-configuring default secrets (zero-touch first run)

`app-config.json`'s `defaults` object lets a fresh install ship with `smtpPassword`, `glpiApiKey`, `dbUsername`, `dbPassword`, and `adApiToken` already configured — no technician or admin has to type anything in Settings on first launch. Each value must be **pre-encrypted**, never plaintext:

```bash
cd desktop-app
mvn exec:java -Dexec.mainClass="com.bunshock.note_app_for_it_frontend.utils.AppKeyEncryptionGenerator"
# Enter the secret when prompted, paste the printed ciphertext into app-config.json
```

```json
"remoteDatabase": {
  "host": "db.example.org",
  "port": 1433,
  "dbName": "notas_it"
},
"defaults": {
  "smtpPassword": "<ciphertext from the generator>",
  "glpiApiKey": "",
  "dbUsername": "<ciphertext from the generator>",
  "dbPassword": "<ciphertext from the generator>",
  "adApiToken": "<ciphertext from the generator>"
}
```

On first startup, any of these whose `APP_SETTINGS` key isn't already set gets copied in — `remoteDatabase.host`/`port`/`dbName` (plaintext, only if `host` is non-blank) and the five `defaults` entries (pre-encrypted). Once an admin edits a value via Settings or Base de Datos, that always takes precedence and the shipped default is never consulted again for that key. Leave a field as `""` if you don't want to pre-configure it. This is the only way to have the app connect to a shared remote database with zero manual setup on a fresh machine — see [Remote database (SQL Server)](#remote-database-sql-server) below for the equivalent one-time manual setup via the UI.

**Security note**: all five secrets share one fixed encryption key embedded in the app (`AppKeyEncryptionService`) — this trades some security for zero-touch deployability across many machines (see `CLAUDE.md`'s Known issues/gotchas for the full trade-off discussion). Anyone with the installed application can, in principle, extract this key and decrypt these values from any copy of `data/noteapp.db` — this is materially weaker than the previous per-account Windows DPAPI approach, and was an explicit, discussed decision, not an oversight.

### Active Directory (AD) API

The app looks up users (recipient name/DNI/email autofill, technician profile) against a real REST API — there is no built-in directory server integration. Configure it from **Configuración → ACTIVE DIRECTORY API** (admin mode required, see [Settings](#settings) below):

| Field | Where it's stored | Notes |
|-------|-------------------|-------|
| API URL | `config/app-config.json` → `adApi.baseUrl` | Not a secret; e.g. `https://ad-api.example.org` |
| API Token | Encrypted (`AppKeyEncryptionService`) in `data/noteapp.db` (`APP_SETTINGS.ad_api_token`) | Write-only field — once saved it's never shown again in the UI; re-enter to replace it |

Saving tests the connection in the background (using the currently resolved technician's own username as a lightweight, real query) before persisting; if the test fails you're asked to confirm before saving anyway.

**Required API contract** — the app calls `GET <baseUrl>/api/v1/ad/users` with any combination of `dni`, `name`, `username` query parameters (server-side ANDs whatever is supplied) and header `Authorization: Bearer <token>`. Expected response, a JSON array (placeholders below — not real data):

```json
[
  {
    "samAccountName": "example.username",
    "displayName": "Apellido, Nombre",
    "dni": "00.000.000",
    "mail": "example.username@example.org",
    "ou": "OU=Example,OU=Users,DC=example,DC=org"
  }
]
```

| Field | Maps to | Notes |
|-------|---------|-------|
| `samAccountName` | Username | Matched by `username` query param (partial match) |
| `displayName` | Full name | Stored/matched as `"Apellido, Nombre(s)"`; matched by `name` query param (partial match) |
| `dni` | DNI | Matched by `dni` query param; queried both with and without dots (see below) |
| `mail` | Email | Not queried, only returned |
| `ou` | Organizational unit | Raw DN-style string, shown as-is in the AD multi-result picker popup |

- An empty array `[]` with HTTP `200` means "no matching user" (not an error).
- HTTP `401` means the token is missing/invalid — the app treats this (and any other non-200 response) as "AD unreachable", distinct from a genuine zero-match search.
- A `dni` search tries both the plain-digits and dotted forms (grouped by 3 from the right, e.g. `45933368` and `45.933.368`); a `name` search tries the text as typed and a `"<lastWord>, <rest>"` reordering — both are real, separate HTTP calls whose results get merged.
- **Any of the 5 fields above can come back as a JSON array instead of a plain string** for some accounts (a multi-valued directory attribute — confirmed on both `dni` and `mail` in practice). The app always uses the first value in that case (empty array → empty string).
- **`dni` and `displayName` are normalized before display**: dots are stripped from `dni` (`"00.000.000"` → `"00000000"`) and the comma is stripped from `displayName` (`"Apellido, Nombre"` → `"Apellido Nombre"`, order kept as-is), since the destination fields only accept digits and letters/spaces respectively.

### Remote database (SQL Server)

The app runs fully on local SQLite by default. To point it at a shared Microsoft SQL Server (Express or full edition) instance instead, go to **Base de Datos** → **✏ Editar** (admin mode required) and enter host, port, database name, username, and password — stored in `data/noteapp.db`'s `APP_SETTINGS` table (host/port/name in plaintext, username/password encrypted via `AppKeyEncryptionService`). Saving tests the connection before persisting, same confirm-on-failure flow as the AD API above. Alternatively, pre-configure all five in `app-config.json` before the very first launch (see [Pre-configuring default secrets](#pre-configuring-default-secrets-zero-touch-first-run) above) so a fresh install connects with zero manual setup. The **Probar conexión** button re-checks connectivity on demand without opening the edit dialog, and reports the remote and local databases independently: "ESTADO REMOTO" shows orange ("no configurada") if no remote database is set up at all, red if one is configured but unreachable, or green if it connects successfully; "ESTADO LOCAL" always tests and reports the local SQLite database separately. If the remote database is unreachable, the app automatically falls back to local SQLite (write-through cache: writes go to remote first, then local; reads try remote first, fall back to local).

**SQL Server Express note**: it commonly installs as a named instance (`SQLEXPRESS`) with a dynamic port, discovered via SQL Server Browser rather than a fixed port the way PostgreSQL/MySQL default to. This app connects with a plain host:port — no named-instance discovery — so assigning the instance a **static TCP port** via SQL Server Configuration Manager is a required one-time setup step, not optional. See `desktop-app/database/sqlserver/README.md` for the exact steps.

The app creates its own schema automatically on first connect (`RemoteDatabaseService.ensureSchema()`) — a fresh, empty SQL Server database is all that's required. **`desktop-app/database/sqlserver/`** has ready-to-run scripts for setting one up, including starting data for the equipment catalog (Type/Brand/Model) and the provider catalog (Nota de Proveedor's dropdown), and a full remote-server setup walkthrough (installing Express, enabling TCP/IP with a static port, creating the DB/login, running the scripts) — see that folder's `README.md`. The seed script is a **template** with placeholder rows only, not real data (same pattern as `app-config.json.example`) — copy it and fill in your organization's actual catalog before running it; never commit the real, filled-in file (already gitignored). S/N validation rules are configured through the app's own UI, not a SQL script — same README explains why.

**Already built up a real catalog locally before setting up a remote server?** `CatalogMigrationTool` (`mvn exec:java -Dexec.mainClass="com.bunshock.note_app_for_it_frontend.utils.CatalogMigrationTool"` from `desktop-app/`) copies the equipment catalog — Type, Brand, Brand-Type links, Model, S/N validation rules, Provider — from the local `data/noteapp.db` into a SQL Server database, correctly remapping autoincrement ids instead of copying them as-is. It creates the schema itself and prompts interactively for the connection details; safe to re-run as more local data is added. See `desktop-app/database/sqlserver/README.md` for details. History isn't migrated by this tool — only the equipment catalog.

### `config/mock-equipment.json`

Contains placeholder equipment types, brands, models, and S/N validation rules used by `MockEquipmentService`. Replace with real data once connected to the SQL Server backend. Schema mirrors the production database structure.

The `snValidations` array defines per-model serial number rules:

```json
{ "modelId": 12, "regexPattern": "^PW0[A-Z0-9]{5}$", "description": null, "isActive": true }
```

| Field | Description |
|-------|-------------|
| `modelId` | ID of the model this rule applies to |
| `regexPattern` | Full regex the S/N must match. Expected length is derived automatically from fixed-length quantifiers (e.g. `{8}`). |
| `description` | Optional human-readable override for the pattern hint. If `null`, the hint is auto-generated from the regex (e.g. `^PW0[A-Z0-9]{5}$` → `PW0XXXXX`). |
| `isActive` | Set to `false` to disable the rule without removing it. |

Models with no entry in `snValidations` accept any S/N.

---

## HTML Note Templates

Note templates live in:

```
desktop-app/src/main/resources/com/bunshock/note_app_for_it_frontend/templates/
```

Each template is a plain `.html` file. The template engine replaces `{{TOKEN}}` placeholders with form data and expands `{{#ITEMS}}...{{/ITEMS}}` loops for equipment rows.

| Template file | Used for |
|---------------|----------|
| `entrega.html` | Entrega notes |
| `devolucion.html` | Devolución notes |
| `entrega - fin de contrato.html` | Fin de Contrato notes |
| `prestamo.html` | Préstamo notes |
| `proveedor.html` | Entrega - Proveedor notes |

To customize the look of a generated note, edit the corresponding HTML file — no Java changes needed. The CSS inside the template controls print layout. Common tokens available in both templates:

| Token | Value |
|-------|-------|
| `{{FECHA}}` | Generation date |
| `{{MOTIVO}}` | Selected Motivo |
| `{{OBSERVACIONES}}` | Observations field |
| `{{#ITEMS}}` / `{{/ITEMS}}` | Equipment rows loop |

---

## Running Tests

```bash
cd desktop-app
mvn test
```

182 unit tests covering: template engine, A/F formatting, AD search (mock and real REST client), equipment cascade logic, caching service fallback behavior, remote DB connection handling, and more — see `CLAUDE.md`'s Testing requirements section for the full class list.

---

## Features

### Note Generation

- **5 note profiles**: Entrega, Devolución, Fin de Contrato, Préstamo, Entrega - Proveedor
- Motivo dropdown (configurable per profile type) — mandatory for Entrega, Devolución, Fin de Contrato, and Provider notes
- Provider notes select the provider from an admin-managed catalog (see [Database Section](#database-section-base-de-datos)) — not free text, to keep naming consistent across notes; generation is blocked with an inline message if none is selected
- HTML template rendering with `{{TOKEN}}` substitution and `{{#ITEMS}}` loops
- Preview popup: choose Print / Send Email / Sync GLPI before generating

### Equipment Management

- Cascading Type → Brand → Model dropdowns with "Generic" fallback (shown in italics)
- Asset items (S/N + A/F) and countable items (quantity) in separate tables
- Item dialog popup for adding and editing items, with mandatory field validation
- Item limit enforced with a visual warning when the note would exceed one A4 page

**Serial number (S/N)**
- Auto-uppercased by default (per-item toggle to disable)
- Per-model regex validation configured in `mock-equipment.json → snValidations`
- Expected length derived automatically from fixed-length regex quantifiers (e.g. `{11}`)
- Two live hints below the field while typing: length check and pattern check
- Pattern hint rendered with fixed characters in bold and variable placeholders in italic (e.g. **`PW0`***`XXXXX`*)
- Save blocked until both length and pattern pass; "Sin S/N" checkbox bypasses validation for exceptional cases (disabled for Notebooks)

**Activo Fijo (A/F)**
- User types only the significant part (e.g. `435`); field auto-formats to full A/F on focus-out (e.g. `IT-00000435`)
- Live preview of the formatted result shown while typing
- Input restricted by `afFormat.inputPattern` — invalid characters and overflow are silently rejected
- Pattern hint shows the expected format with prefix bold and digit/char positions in italic (e.g. **`IT-`***`00000000`*)
- Field is required when the A/F checkbox is enabled

### AD Integration

- Search by DNI, name, and/or username against a real REST API (see [Active Directory (AD) API](#active-directory-ad-api) above) — multiple fields narrow the search (AND). This is enforced on both ends: every request already sends the fields together so the server ANDs them, and the app additionally re-verifies every supplied field (dni, name, username) against each result's own data before showing it, unconditionally and with no exceptions — the server can't be trusted to actually AND them itself, confirmed in practice (adding a dni to an already-correct name search could return entirely different, dni-only matches)
- DNI dot format normalized automatically — queried both as `"35123456"` and `"35.123.456"`. Note: a *partial* DNI search against a record whose DNI is stored dotted in AD may not find it unless the typed length happens to land on a dot boundary — this is how Active Directory itself indexes that data, confirmed in the native Windows AD tool too, not something the app's query can work around
- Name accepts "Nombre Apellido", "Apellido Nombre", and incomplete words in either position (e.g. "Rodriguez Joa", "Joaquin Rodrig", or just "Rodrig" alone) — queried as typed plus both comma-insertion guesses; if that finds nothing, each word is automatically retried alone and the results are narrowed back down to only people matching every typed word
- Multi-result picker dialog when search returns more than one user, showing DNI/email/OU on hover
- Credential validation at login (`POST /api/v1/ad/validate-credentials` on the AD API — a bind-as-user password check, plus the caller's AD group memberships for the app-access gate) — see [Login](#login) above
- Technician profile populated by the same lookup used for searches, once login succeeds
- Sidebar status dot reflects real AD API reachability (a background check using the already-logged-in username, not a re-lookup) — see [Configure Application Settings](docs/use-cases.md) / `CLAUDE.md` for the status-check design

### History

- Every generated note saved to local SQLite
- History table with date, profile type, recipient, author, equipment count, and GLPI sync status
- **Advanced multi-select filters**: date range, note type, GLPI status (Pendiente/Sincronizado/Rechazado/Sin GLPI), author text, recipient/provider text, Sede, and cascading equipment type → brand → model; partial/hybrid GLPI states are included in filtered results
- **Export**: filtered results exportable to CSV (UTF-8 with BOM) or Excel (.xlsx) with bold headers and auto-sized columns
- **Note detail popup**: double-click any row to open a floating popup with the rendered note preview (left) and a scrollable item card list (right) showing per-item GLPI status badges
- **Admin GLPI actions**: in admin mode, PENDING item cards in the popup show Sync and Reject buttons; rejection requires entering a reason; the history table refreshes after each action
- **Auto-refreshes on open**: every time you navigate to Historial, the table reloads with whatever filters are currently set (not reset) — a note generated since your last visit shows up immediately, no manual "Buscar" needed

### Préstamos (Internal Equipment Loans)

- **Two ways to create a Préstamo**: the existing Generar Nota → Préstamo flow (prints a note), or the new "Cargar Nuevo Préstamo" tab, which saves a loan directly to history with no print/email/rendered note at all
- **Cargar Nuevo Préstamo**: recipient Name/DNI with AD search, an optional "Área / Evento" context field, a mandatory tentative return date (defaults to next working day, can't be in the past), and the same asset/countable item tables and item dialog as Generar Nota
- **Historial de Préstamos**: a Préstamo-scoped history table, colored by return status (green = devuelto, orange = pendiente, red = perdido, gradient for mixed) with an overdue "(Vencido)" flag for pending loans past their tentative return date
- **Filters**: date range, Estado de Devolución (multi-select), destinatario/autor text search, Sede (multi-select)
- **Return validation** (admin mode): double-click a loan to open its detail popup — pending items show "Validar devolución" / "Marcar como perdido" (mandatory reason) buttons; every item, asset or countable, is tracked independently of GLPI sync status

### Database Section (Base de Datos)

- Admin-managed catalogs: Tipos, Marcas, Modelos (cascading), and Proveedores (flat list) — add/rename/remove, all admin-gated
- "Generic" brand protected from deletion
- A provider added here is immediately selectable in Nota de Proveedor's dropdown, even in a tab already open earlier in the session
- Remote SQL Server connection config and connectivity test (see [Remote database (SQL Server)](#remote-database-sql-server) below)

### Login

- A standalone login screen is shown before anything else in the app — no main window, no startup connectivity checks — until it succeeds. Username (pre-filled from the Windows session, still editable) + password, validated against the AD API (a bind-as-user credential check, not a stored password).
- App access is gated by AD group membership (configurable, `adAccess.allowedGroupName` — blank skips the check while it's not yet configured).
- A per-account role (`USER`/`ADMIN`/`SUPERADMIN`, plus an assigned Sede — all set by a database administrator via direct SQL against the `APP_USER` table, no in-app screen) determines whether admin mode starts already active — an `ADMIN` or `SUPERADMIN` login skips the old password toggle entirely and never expires for that session; a `USER` login starts as a normal technician, still able to reach password-gated admin actions (catalog edits, S/N validation) per click. Every admin-tier action is denied by default and only granted per role via a `ROLE_PERMISSION` table (also SQL-managed) — see [Role-based permissions](#role-based-permissions) below.

### Settings

- **Sede**: a superadmin-assigned per-technician site value (no self-service field anymore — set via direct SQL against `APP_USER.sede_id`), shown read-only in the sidebar next to the welcome message, styled as a standing warning ("Sede no asignada") when unset. Mandatory to generate a note or register a Préstamo — blocked with a warning (and a one-time startup popup) if unset, same as an incomplete AD profile. Snapshotted onto every note at generation time and printed on it (replaces the previously hardcoded "Campus" text on all 5 templates)
- A/F format configuration with live preview
- SMTP credentials (password encrypted via `AppKeyEncryptionService` — never stored in plaintext; editable only by a `SUPERADMIN`-role login, never via the shared admin password)
- GLPI API URL and API Key (key encrypted via `AppKeyEncryptionService` — never stored in plaintext)
- Active Directory API URL and Token (token encrypted via `AppKeyEncryptionService`, write-only field — never redisplayed once saved); saving tests the connection first and asks for confirmation if it fails
- Settings content scrolls independently in a fixed-height panel — the "CONFIGURACIÓN" header and the "Guardar Configuración" footer both stay fixed in place; only the card list in between scrolls
- Each configuration field group (A/F format, SMTP, GLPI, AD) is independently read-only/disabled unless the active session holds that specific permission; the "Guardar Configuración" button itself is always enabled and only persists the field groups the session is actually permitted to change
- **S/N Validation table** (admin-protected): view all asset-type models with their regex pattern and active toggle; active rules sort to the top; multi-select Tipo/Marca/Modelo/Activo dropdown filters (same pattern as History's filters, Tipo→Marca→Modelo cascading) with a "Limpiar filtros" reset button
- **Admin mode**: activated automatically at login for an account with the `ADMIN` or `SUPERADMIN` role (see [Login](#login) above; role is set via direct SQL against `APP_USER`, no in-app screen) — never expires for that session. A non-admin-role technician has no self-service toggle at all, but can still unlock individual ADMIN-level actions (catalog edits, S/N validation, GLPI sync) with the shared password, per click — that fallback can never reach a `SUPERADMIN`-only permission.

### Role-based permissions

- Every admin-tier action (catalog management, S/N validation, note approval, GLPI sync, return validation, profile overrides, and each Settings field group) is **denied by default** and only enabled per role — a new sensitive feature can't be silently reachable by everyone if a grant is forgotten when it's built.
- Grants live in a `ROLE_PERMISSION` table, edited directly via SQL by a superadmin, same as `APP_USER`'s role/Sede assignment — there is no in-app screen for managing permissions.
- A plain `ADMIN` is further restricted to acting (approve/reject, GLPI sync, return validation) only on notes generated at their own assigned Sede; opening a note from a different Sede shows its contents but hides the action buttons, with a warning explaining why. `SUPERADMIN` bypasses this Sede restriction entirely.
- `SUPERADMIN` sessions show a distinct magenta title-bar badge ("MODO SUPERADMINISTRADOR"), vs. the existing teal badge for a plain `ADMIN`.

### Mi Perfil (technician identity)

- Name, Username, DNI, and Email resolved from Active Directory at login time (see [Login](#login) above), refreshable on demand via "Actualizar Perfil desde AD" (a lookup, not a re-login) — read-only, editable only for a session holding the profile-override permission, never persisted to disk
- **Nombre para mostrar**: a separate, always-editable field (no admin mode required, max 20 characters) controlling only the sidebar welcome greeting ("Hola, ...!"). Pre-filled with a suggested default (the last word of the AD full name); persisted locally per technician username so it survives restarts and AD refreshes. Clearing it and saving reverts to the suggested default — or use the square ↺ reset button next to the field to do both in one click
- Sidebar welcome message updates immediately on any change (AD refresh, admin override, or a saved display-name preference) — no restart needed

### Security

- SMTP password, GLPI API key, DB password, and AD API token encrypted at rest using `AppKeyEncryptionService` (AES-256/GCM); see [Pre-configuring default secrets](#pre-configuring-default-secrets-zero-touch-first-run) above for the accepted security trade-off of this scheme
- No plaintext secrets in config files or source code
- Input validated at every system boundary

---

## Project Structure

```
notes-app-for-it/
├── desktop-app/           — JavaFX desktop application
│   ├── config/            — Runtime config files (not compiled into JAR)
│   ├── data/              — SQLite database (created at first run, gitignored)
│   ├── database/sqlserver/ — Remote DB setup: schema + starting equipment/provider catalog SQL scripts (example template, not real data)
│   └── src/
│       ├── main/java/     — Application source
│       ├── main/resources/— FXML views, CSS, HTML note templates
│       └── test/java/     — JUnit 5 unit tests
├── backend-api/           — Spring Boot REST API (planned)
└── docs/                  — Architecture, requirements, database schema, use cases
```

---

## Documentation

| File | Contents |
|------|----------|
| [`docs/architecture.md`](docs/architecture.md) | Package structure, design patterns, startup sequence, data flow |
| [`docs/use-cases.md`](docs/use-cases.md) | User-facing use cases (UC-01 through UC-15) |
| [`docs/requirements.md`](docs/requirements.md) | Functional and non-functional requirements |
| [`docs/database.md`](docs/database.md) | SQLite schema and planned SQL Server migration path |
