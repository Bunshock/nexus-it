# Architecture

## Overview

**Generador de Notas IT** is a JavaFX desktop application for IT support teams at Universidad Siglo 21. It generates equipment handover notes (Notas IT) and maintains a local history of all generated reports.

The application follows a layered MVC architecture with a service abstraction layer that allows external dependencies (AD, GLPI, database) to be swapped without touching UI code.

---

## Package Structure

```
desktop-app/src/main/java/com/bunshock/note_app_for_it_frontend/
├── App.java                          — JavaFX entry point; wires up config, DB, services
├── Launcher.java                     — Alternate main with Kerberos VM args (IDE use)
├── controllers/
│   ├── MainController.java           — Root layout: sidebar nav, status polling, AD startup lookup
│   ├── NoteGeneratorController.java  — Equipment tables, note profile switching, generate trigger
│   ├── UserNoteController.java       — User note fields: type toggle, Motivo, AD search
│   ├── ProviderNoteController.java   — Provider note fields: name, CUIT, Motivo, responsible
│   ├── ItemDialogController.java     — Add/edit item: cascading dropdowns, S/N, A/F, quantity
│   ├── NotePreviewController.java    — Preview popup: rendered HTML, print/email options
│   ├── NoteDetailController.java     — History detail popup: HTML preview + item list with admin GLPI actions
│   ├── HistoryController.java        — History table with multi-select filters and CSV/xlsx export
│   ├── DatabaseSectionController.java — Equipment catalog CRUD, DB IP config
│   ├── SettingsController.java       — A/F format, SMTP, GLPI URL, S/N validation table, admin toggle
│   ├── ProfileController.java        — Technician personal profile (read from DB)
│   ├── AboutController.java          — App info
│   └── ADUserSelectionController.java — Multi-result AD user picker dialog
├── models/
│   ├── ADUser.java                   — AD lookup result
│   ├── AppConfig.java                — Deserialized app-config.json structure
│   ├── AssetItem.java                — Equipment item with S/N and A/F (JavaFX properties)
│   ├── CountableItem.java            — Equipment item with quantity (JavaFX properties)
│   ├── EquipmentBrand.java           — Brand entity
│   ├── EquipmentModel.java           — Model entity (belongs to a BRAND_TYPE_LINK)
│   ├── EquipmentType.java            — Type entity with isAsset flag
│   ├── EquipmentItem.java            — Base class for AssetItem and CountableItem
│   ├── GlpiStatus.java               — Enum: N_A, PENDING, SYNCED, REJECTED
│   ├── HistoryFilter.java            — Filter params for history queries (dates, multi-select lists)
│   ├── NoteReport.java               — Saved note report (history entry) with aggregated GLPI counts
│   ├── NoteReportItem.java           — Individual item row in a saved report with GlpiStatus
│   ├── SnValidation.java             — S/N regex rule for a specific model
│   ├── SnValidationRow.java          — Display row for S/N validation admin table
│   └── TechnicianProfile.java        — Current user's personal profile
├── services/
│   ├── AdminAuthService.java         — Admin password verification (SHA-256 hash stored in APP_SETTINGS)
│   ├── AdminSession.java             — Singleton: tracks active admin session; fires activate/deactivate listeners
│   ├── ConfigService.java            — Singleton: loads/saves app-config.json
│   ├── DatabaseService.java          — SQLite connection pool and schema initialization
│   ├── RemoteDatabaseService.java    — PostgreSQL connection and DDL; used by Caching* wrappers
│   ├── ServiceLocator.java           — Single wiring point for all service implementations
│   ├── IADService.java               — AD lookup interface (search + isConfigured)
│   ├── AdApiService.java             — Active implementation: REST client for the AD API (see below)
│   ├── MockADService.java            — In-memory AD mock (tests only)
│   ├── IEquipmentService.java        — Equipment catalog interface (types, brands, models)
│   ├── MockEquipmentService.java     — JSON-backed equipment catalog (reads mock-equipment.json)
│   ├── SqliteEquipmentService.java   — SQLite-backed equipment catalog
│   ├── CachingEquipmentService.java  — Remote-first wrapper: tries PostgreSQL, falls back to SQLite
│   ├── IHistoryService.java          — Note history persistence interface + distinct-value query methods
│   ├── SqliteHistoryService.java     — SQLite-backed history: filtered queries, GLPI status updates, distinct catalog values
│   ├── CachingHistoryService.java    — Remote-first wrapper: tries PostgreSQL, falls back to SQLite
│   ├── IGLPIService.java             — GLPI API interface
│   ├── GLPIServiceStub.java          — No-op stub (GLPI out of scope for v1)
│   ├── IEmailService.java            — Email sending interface
│   ├── GmailEmailService.java        — Jakarta Mail + Gmail SMTP implementation
│   ├── AppKeyEncryptionService.java  — Credential encryption via AES-256/GCM (fixed shared key)
│   ├── TemplateEngine.java           — {{TOKEN}} and {{#LOOP}} HTML template renderer
│   └── NoteGenerationService.java    — Builds rendered HTML notes from form data + templates
└── utils/
    ├── AdminPasswordHashGenerator.java   — CLI utility to generate admin password SHA-256 hash
    ├── AppKeyEncryptionGenerator.java    — CLI utility to pre-encrypt default secrets for app-config.json
    └── ViewFactory.java              — View cache: loads each FXML once, reuses across nav switches
```

---

## Key Patterns

### Service Abstraction (Strategy Pattern)
Every external dependency has an interface (`IADService`, `IEquipmentService`, `IGLPIService`, `IEmailService`, `IHistoryService`). Concrete implementations are wired in `ServiceLocator.initialize()`. Swapping from mock to real is a one-line change in `ServiceLocator`.

### Active Directory Integration (AdApiService)
`AdApiService` (singleton, `configure(baseUrl, apiToken)` / `isConfigured()`) is the active `IADService` implementation, calling `<baseUrl>/api/v1/ad/users` with `dni`/`name`/`username` query params (server-side ANDs whatever is supplied) and an `Authorization: Bearer <token>` header. `MockADService` is test-only now — `ServiceLocator` always wires `AdApiService`.

- **Config storage**: the URL (`adApi.baseUrl`) lives in `app-config.json` like `glpiApi.baseUrl`; the token is a secret, encrypted (`AppKeyEncryptionService`) into `APP_SETTINGS` (`ad_api_token`), never re-displayed once saved (write-only `PasswordField`, same pattern as `glpi_api_key`). Both fields live in `SettingsController`'s "ACTIVE DIRECTORY API" card, admin-gated like every other config field. Can ship pre-configured via `app-config.json`'s `defaults.adApiToken` — see "Credential Encryption" pattern below.
- **Verify-before-save**: saving a new URL/token in Settings tests the connection on a background thread (using the current technician's already-resolved username as a cheap, real query) before persisting; a failed test prompts for confirmation rather than silently accepting bad config — mirrors `DatabaseSectionController`'s DB connection flow.
- **DNI query variants**: the API matches literally, so a technician-typed DNI (digits only) is queried in both its plain and dotted forms (grouped by 3 from the right, e.g. `45933368` → `45.933.368`); results from both are merged and deduped by `samAccountName`.
- **Name query variants**: `name` is a partial/contains match against the stored `"Apellido, Nombre(s)"` display format, so a typed name is queried both as-typed (matches when given names are stored in the typed order, regardless of what precedes the comma) and reordered as `"<lastWord>, <restOfWords>"` (matches the common "Nombre Apellido" input shape). Both variants run as separate HTTP calls and are merged.
- **Error handling**: any non-200 response (401 `invalid_api_key`/`missing_api_key`, 5xx, network failure) throws, so existing callers (e.g. `TechnicianSessionService`) can keep distinguishing "AD reachable, zero matches" from "AD unreachable" without change. A `200` with an empty array is a normal zero-match result.
- **Reachability check**: `MainController`'s periodic 60s AD status poll queries by the current technician's own already-resolved username (`TechnicianSessionService.getUsername()`) instead of an unbounded/empty query, to avoid pulling the full directory just to check liveness; the sidebar dot shows a third gray "No configurado" state via `IADService.isConfigured()` when the URL/token aren't set, distinct from red "Desconectado".
- **`ADUser` no longer carries group memberships** — the real API's DTO has no such field. It now carries the raw `ou` string (e.g. `OU=2025,OU=Bajas,OU=Cau2018,OU=SEDES CAU`) in the existing `distinguishedName` field, shown as-is in `ADUserSelectionController`'s multi-result popup; the "GRUPOS" line was removed.

### ViewFactory — State Persistence Across Navigation
`ViewFactory` loads each section FXML exactly once and caches the result. When `MainController` switches sections via sidebar, it calls `viewFactory.getXxxView()` which returns the cached node. Controller instances — and their bound data — remain alive in memory for the session. This implements FR-08 (in-session data persistence).

### Template Engine
`TemplateEngine.render()` processes HTML templates using:
- `{{TOKEN}}` → replaced with a value from the token map
- `{{#LOOP_KEY}}...{{/LOOP_KEY}}` → expanded once per item in the items list, each item having its own token map

Templates live in `src/main/resources/.../templates/`. `NoteGenerationService` selects the correct template based on note profile type and builds the token maps from form data.

### Credential Encryption (AppKeyEncryptionService)
`AppKeyEncryptionService` (AES-256/GCM, `javax.crypto`) encrypts/decrypts the four shared organizational secrets — `smtp_password`, `glpi_api_key`, `db_password`/`db_username`, `ad_api_token` — stored as Base64 in the `APP_SETTINGS` SQLite table. Replaced `WindowsDPAPIService` on 2026-07-08: DPAPI ties every encrypted value to the specific Windows account that encrypted it, which made pre-configuring these fleet-wide shared credentials across many technician machines impractical without visiting each one (none of these four are actually *personal* per-technician secrets — they're all IT-department-owned service credentials identical across every installation). `AppKeyEncryptionService` uses one fixed key embedded in the app, shared across every installation.

**Explicit accepted trade-off** (discussed with and decided by the user, not a default/oversight): this is materially weaker than DPAPI against a determined local attacker — the key can be extracted from the installed app by decompiling it, and the same key decrypts every installation's secrets, not just one machine's. It stops casual plaintext exposure (opening `noteapp.db` in a browser/viewer) but not a deliberate extraction attempt. If a proper installer is built later, generate a unique key per deployment at packaging time instead of reusing one static key indefinitely (tracked as a known follow-up).

**Zero-touch pre-configuration**: `app-config.json`'s `defaults` object (`smtpPassword`, `glpiApiKey`, `dbPassword`, `adApiToken`) holds pre-encrypted values, generated via `utils.AppKeyEncryptionGenerator` (mirrors `AdminPasswordHashGenerator`'s existing CLI pattern). `ServiceLocator.provisionDefaultSecrets()` copies any of these into `APP_SETTINGS` on first startup, only for keys not already set — an admin's later edit via Settings always takes precedence and is never overwritten by a shipped default.

### SQLite Local Database
`DatabaseService` initializes a local `data/noteapp.db` on first run. The schema mirrors the planned PostgreSQL structure exactly (same table names and column types), so migration will require only a JDBC driver swap and connection string change. The `Generic` brand is inserted as protected default data on initialization.

### Admin Mode (AdminSession)
`AdminSession` is a singleton that tracks whether an admin session is currently active. Controllers register listeners via `addOnActivateListener` / `addOnDeactivateListener`. Activation is done via `SettingsController.handleToggleAdmin()` which calls `requireAdmin(Runnable)` — this checks if a password is configured (`AdminAuthService.isConfigured()`), prompts for it, verifies against the stored SHA-256 hash, then fires the callback. Sessions auto-expire after 15 minutes of inactivity. `SettingsController`'s own configuration fields (A/F format, SMTP, GLPI API) and its "Guardar Configuración" button are disabled unless admin mode is active, via the same listener pair (`SettingsController.updateFieldEditability()`).

### Note Detail Popup (admin-integrated GLPI actions)
`NoteDetailController.open(NoteReport, boolean adminMode, Window owner, Runnable onUpdate)` opens a floating stage showing the rendered HTML note alongside a scrollable item card list. When `adminMode` is true and `AdminSession.getInstance().isActive()`, each PENDING item's card includes Sync and Reject buttons. The `adminMode` flag is set from the caller by reading `AdminSession.getInstance().isActive()` at open time. No separate admin tab or view — actions are embedded directly in the popup.

### History Filtering
`HistoryController` uses `MenuButton` with `CustomMenuItem(CheckBox, false)` to build non-closing multi-select dropdowns for note type, GLPI status, and equipment type/brand/model. Equipment dropdowns cascade: tipo → refreshBrandMenu() → refreshModelMenu() each time a selection changes. Distinct values for type/brand/model are read from `NOTE_ITEM` historical data (not the current catalog) via `IHistoryService.getDistinctItemTypes/Brands/Models()`. Filter state is assembled into a `HistoryFilter` and passed to `IHistoryService.getFiltered()`.

---

## Startup Sequence

```
App.init()
  → ConfigService.load()       reads config/app-config.json
  → DatabaseService.initialize() creates data/noteapp.db and schema if needed
  → ServiceLocator.initialize() wires service implementations

App.start()
  → Loads MainView.fxml
  → MainController.initialize()
      → reads Windows username (System.getProperty("user.name"))
      → background thread: AD lookup for current user → updates welcome label
      → background thread: status check → updates AD/GLPI indicators
      → loads NoteGeneratorView into content area (default section)
```

---

## Data Flow — Note Generation

```
User fills form fields
  → clicks "Generar PDF y Registrar"
  → NoteGeneratorController.handleGenerateNote()
      → NoteGenerationService.generateUserNote() / generateProviderNote()
          → loads HTML template from resources
          → TemplateEngine.render(template, tokens, items)
          → returns rendered HTML string
      → opens NotePreviewView (modal) with rendered HTML
  → User selects options (Print / Email)
      → NotePreviewController.handleGenerate()
          → if Print: WebView.print(), via PrinterJob.showPrintDialog()
              → if the technician cancels the print dialog, generation aborts here:
                no email sent, no NoteReport saved, preview popup stays open
          → if Email: GmailEmailService.sendNote()
          → saves NoteReport to SQLite via IHistoryService
          → closes preview
```

---

## Configuration Files

| File | Purpose |
|------|---------|
| `config/app-config.json` | A/F format, Motivo options, SMTP host/port, API URLs, item limit |
| `config/mock-equipment.json` | Equipment type/brand/model data used by MockEquipmentService |
| `data/noteapp.db` | SQLite database (created at runtime) |

---

## Service Dependency Map

```
MainController          → ServiceLocator → IADService
                                         → IGLPIService
NoteGeneratorController → NoteGenerationService → TemplateEngine
NotePreviewController   → ServiceLocator → IEmailService
                                         → IHistoryService
                                         → IGLPIService
NoteDetailController    → ServiceLocator → IHistoryService (GLPI status updates)
                        → AdminSession   (gates Sync/Reject buttons per item)
ItemDialogController    → ServiceLocator → IEquipmentService
HistoryController       → ServiceLocator → IHistoryService (filtered queries + distinct values)
                        → AdminSession   (adminMode flag passed to NoteDetailController)
DatabaseSectionController → ServiceLocator → IEquipmentService
                          → RemoteDatabaseService
SettingsController      → ConfigService
                        → AppKeyEncryptionService
                        → DatabaseService
                        → AdminSession / AdminAuthService
ProfileController       → DatabaseService
UserNoteController      → ServiceLocator → IADService
                        → ConfigService  (Motivo options)
ProviderNoteController  → ConfigService  (Motivo options)
```
