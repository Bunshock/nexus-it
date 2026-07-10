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

> **Never commit `desktop-app/data/noteapp.db`.** It's a runtime artifact created fresh on first launch and is already listed in `.gitignore`. If your local clone fails on startup with an error like `SQLITE_ERROR ... table NOTE_ITEM has no column named ...`, you likely have a stale database file left over from an old checkout — delete `desktop-app/data/noteapp.db` and re-run `mvn clean javafx:run` to regenerate it.

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
| `noteItemLimit` | Max items per note before showing a warning |

SMTP password and GLPI API key are stored encrypted in the local SQLite database (`AppKeyEncryptionService`, AES-256/GCM) — never in this file.

### Pre-configuring default secrets (zero-touch first run)

`app-config.json`'s `defaults` object lets a fresh install ship with `smtpPassword`, `glpiApiKey`, `dbPassword`, and `adApiToken` already configured — no technician or admin has to type anything in Settings on first launch. Each value must be **pre-encrypted**, never plaintext:

```bash
cd desktop-app
mvn exec:java -Dexec.mainClass="com.bunshock.note_app_for_it_frontend.utils.AppKeyEncryptionGenerator"
# Enter the secret when prompted, paste the printed ciphertext into app-config.json
```

```json
"defaults": {
  "smtpPassword": "<ciphertext from the generator>",
  "glpiApiKey": "",
  "dbPassword": "",
  "adApiToken": "<ciphertext from the generator>"
}
```

On first startup, any of these four whose `APP_SETTINGS` key isn't already set gets copied in from `defaults` — once an admin edits a value via Settings, that always takes precedence and the shipped default is never consulted again for that key. Leave a field as `""` if you don't want to pre-configure it.

**Security note**: all four secrets share one fixed encryption key embedded in the app (`AppKeyEncryptionService`) — this trades some security for zero-touch deployability across many machines (see `CLAUDE.md`'s Known issues/gotchas for the full trade-off discussion). Anyone with the installed application can, in principle, extract this key and decrypt these values from any copy of `data/noteapp.db` — this is materially weaker than the previous per-account Windows DPAPI approach, and was an explicit, discussed decision, not an oversight.

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

### Remote database (PostgreSQL)

The app runs fully on local SQLite by default. To point it at a shared PostgreSQL instance instead, go to **Base de Datos** → **✏ Editar** (admin mode required) and enter host, port, database name, username, and password — stored in `data/noteapp.db`'s `APP_SETTINGS` table (host/port/name in plaintext, username/password encrypted via `AppKeyEncryptionService`). Saving tests the connection before persisting, same confirm-on-failure flow as the AD API above. The **Probar conexión** button re-checks connectivity on demand without opening the edit dialog. If the remote database is unreachable, the app automatically falls back to local SQLite (write-through cache: writes go to remote first, then local; reads try remote first, fall back to local).

### `config/mock-equipment.json`

Contains placeholder equipment types, brands, models, and S/N validation rules used by `MockEquipmentService`. Replace with real data once connected to the PostgreSQL backend. Schema mirrors the production database structure.

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

140 unit tests covering: template engine, A/F formatting, AD search (mock and real REST client), equipment cascade logic, caching service fallback behavior, remote DB connection handling, and more — see `CLAUDE.md`'s Testing requirements section for the full class list.

---

## Features

### Note Generation

- **5 note profiles**: Entrega, Devolución, Fin de Contrato, Préstamo, Entrega - Proveedor
- Motivo dropdown (configurable per profile type) — mandatory for Entrega, Devolución, Fin de Contrato, and Provider notes
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

- Search by DNI, name, and/or username against a real REST API (see [Active Directory (AD) API](#active-directory-ad-api) above) — multiple fields narrow the search (AND)
- DNI dot format normalized automatically — queried both as `"35123456"` and `"35.123.456"`
- Name queried both as typed and reordered as `"Apellido, Nombre"` to match the stored display-name format
- Multi-result picker dialog when search returns more than one user, showing DNI/email/OU on hover
- Current Windows user looked up in AD on startup (background thread) to populate the technician profile
- Sidebar status dot reflects real AD API reachability (see [Configure Application Settings](docs/use-cases.md) / `CLAUDE.md` for the status-check design)

### History

- Every generated note saved to local SQLite
- History table with date, profile type, recipient, author, equipment count, and GLPI sync status
- **Advanced multi-select filters**: date range, note type, GLPI status (Pendiente/Sincronizado/Rechazado/Sin GLPI), recipient/provider text, and cascading equipment type → brand → model; partial/hybrid GLPI states are included in filtered results
- **Export**: filtered results exportable to CSV (UTF-8 with BOM) or Excel (.xlsx) with bold headers and auto-sized columns
- **Note detail popup**: double-click any row to open a floating popup with the rendered note preview (left) and a scrollable item card list (right) showing per-item GLPI status badges
- **Admin GLPI actions**: in admin mode, PENDING item cards in the popup show Sync and Reject buttons; rejection requires entering a reason; the history table refreshes after each action

### Settings

- A/F format configuration with live preview
- SMTP credentials (password encrypted via `AppKeyEncryptionService` — never stored in plaintext)
- GLPI API URL and API Key (key encrypted via `AppKeyEncryptionService` — never stored in plaintext)
- Active Directory API URL and Token (token encrypted via `AppKeyEncryptionService`, write-only field — never redisplayed once saved); saving tests the connection first and asks for confirmation if it fails
- Settings content scrolls independently in a fixed-height panel — the "Guardar Configuración" button always stays visible in its own footer row, not pushed off-screen as cards are added
- All configuration fields and the "Guardar Configuración" button are read-only/disabled unless admin mode is active
- **S/N Validation table** (admin-protected): view all asset-type models with their regex pattern and active toggle; active rules sort to the top; filterable by type, brand, or model
- **Admin mode**: password-protected session (SHA-256 hash in SQLite); unlocks general configuration editing, S/N validation edits, GLPI sync actions, and DB connection changes; auto-expires after 15 minutes of inactivity

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
| [`docs/database.md`](docs/database.md) | SQLite schema and planned PostgreSQL migration path |
