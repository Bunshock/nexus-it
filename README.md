# Generador de Notas IT

Desktop application for IT support teams at Universidad Siglo 21 to generate equipment handover notes (Notas IT). Built with JavaFX 21 for Windows.

---

## Requirements

- Java 21 (JDK)
- Maven 3.8+
- Windows (DPAPI used for credential encryption)

---

## Setup

```bash
cd desktop-app

# First run — creates data/noteapp.db automatically
mvn clean javafx:run
```

> **Always use `mvn clean javafx:run`**, not just `mvn javafx:run`. The IDE (VS Code / Eclipse) can leave stale `.class` files in `target/` that Maven reuses without recompiling, causing runtime errors.

To run with Kerberos/AD support (for real ADService):

```bash
# Use the Launcher config in .vscode/launch.json
# or pass these VM args manually:
# --add-exports=java.security.jgss/sun.security.jgss=ALL-UNNAMED
# --add-exports=java.security.jgss/sun.security.jgss.spi=ALL-UNNAMED
# -Djavax.security.auth.useSubjectCredsOnly=false
```

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
| `motivoOptions.recambio` | List of Motivo values for Recambio notes |
| `smtp.host` | SMTP server host |
| `smtp.port` | SMTP port (587 for Gmail STARTTLS) |
| `smtp.senderAddress` | Sender email address |
| `adApi.baseUrl` | REST API URL for AD lookups |
| `noteItemLimit` | Max items per note before showing a warning |

SMTP password is stored encrypted in the local SQLite database (Windows DPAPI) — never in this file.

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
| `entrega.html` | Entrega, Devolución, Fin de Contrato, Recambio notes |
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

22 unit tests covering: template engine, A/F formatting, AD search, and equipment cascade logic.

---

## Features

### Note Generation

- **5 note profiles**: Entrega, Devolución, Fin de Contrato, Recambio, Entrega - Proveedor
- Recambio generates two notes (Entrega + Devolución) from a single form
- Motivo dropdown (configurable per profile type) — mandatory for Entrega and Provider notes
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

- Search by DNI, name, or username
- DNI dot format normalized automatically (`"35.123.456"` = `"35123456"`)
- Username `.` vs `-` separator variants tried automatically
- Multi-result picker dialog when search returns more than one user
- Current Windows user looked up in AD on startup (background thread)

### History

- Every generated note saved to local SQLite
- History view with date, profile type, recipient, GLPI sync status
- Refresh button to reload from database

### Settings

- A/F format configuration with live preview
- SMTP credentials (password encrypted via Windows DPAPI — never stored in plaintext)
- GLPI API URL

### Security

- SMTP password encrypted at rest using Windows DPAPI (tied to the current Windows user account)
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
| [`docs/use-cases.md`](docs/use-cases.md) | User-facing use cases (UC-01 through UC-13) |
| [`docs/requirements.md`](docs/requirements.md) | Functional and non-functional requirements |
| [`docs/database.md`](docs/database.md) | SQLite schema and planned PostgreSQL migration path |
