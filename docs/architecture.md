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
│   ├── NotePreviewController.java    — Preview popup: rendered HTML, print/email/GLPI options
│   ├── HistoryController.java        — History table view
│   ├── DatabaseSectionController.java — Equipment catalog CRUD, DB IP config
│   ├── SettingsController.java       — A/F format, SMTP, GLPI URL settings
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
│   ├── NoteReport.java               — Saved note report (history entry)
│   ├── NoteReportItem.java           — Individual item row in a saved report
│   ├── SnValidation.java             — S/N length rule for a specific model
│   └── TechnicianProfile.java        — Current user's personal profile
├── services/
│   ├── ConfigService.java            — Singleton: loads/saves app-config.json
│   ├── DatabaseService.java          — SQLite connection pool and schema initialization
│   ├── ServiceLocator.java           — Single wiring point for all service implementations
│   ├── IADService.java               — AD lookup interface
│   ├── MockADService.java            — In-memory AD mock (used until REST API is ready)
│   ├── ADService.java                — JAAS/GSSAPI Kerberos implementation (not active)
│   ├── IEquipmentService.java        — Equipment catalog interface (types, brands, models)
│   ├── MockEquipmentService.java     — JSON-backed equipment catalog (reads mock-equipment.json)
│   ├── SqliteEquipmentService.java   — SQLite-backed equipment catalog
│   ├── IHistoryService.java          — Note history persistence interface
│   ├── SqliteHistoryService.java     — SQLite-backed history implementation
│   ├── IGLPIService.java             — GLPI API interface
│   ├── GLPIServiceStub.java          — No-op stub (GLPI out of scope for v1)
│   ├── IEmailService.java            — Email sending interface
│   ├── GmailEmailService.java        — Jakarta Mail + Gmail SMTP implementation
│   ├── WindowsDPAPIService.java      — Credential encryption via Windows DPAPI (JNA)
│   ├── TemplateEngine.java           — {{TOKEN}} and {{#LOOP}} HTML template renderer
│   └── NoteGenerationService.java    — Builds rendered HTML notes from form data + templates
└── utils/
    └── ViewFactory.java              — View cache: loads each FXML once, reuses across nav switches
```

---

## Key Patterns

### Service Abstraction (Strategy Pattern)
Every external dependency has an interface (`IADService`, `IEquipmentService`, `IGLPIService`, `IEmailService`, `IHistoryService`). Concrete implementations are wired in `ServiceLocator.initialize()`. Swapping from mock to real is a one-line change in `ServiceLocator`.

### ViewFactory — State Persistence Across Navigation
`ViewFactory` loads each section FXML exactly once and caches the result. When `MainController` switches sections via sidebar, it calls `viewFactory.getXxxView()` which returns the cached node. Controller instances — and their bound data — remain alive in memory for the session. This implements FR-08 (in-session data persistence).

### Template Engine
`TemplateEngine.render()` processes HTML templates using:
- `{{TOKEN}}` → replaced with a value from the token map
- `{{#LOOP_KEY}}...{{/LOOP_KEY}}` → expanded once per item in the items list, each item having its own token map

Templates live in `src/main/resources/.../templates/`. `NoteGenerationService` selects the correct template based on note profile type and builds the token maps from form data.

### Windows DPAPI Credential Storage
`WindowsDPAPIService` wraps JNA's `Crypt32Util` to encrypt/decrypt sensitive strings (SMTP password) using the Windows Data Protection API. Encrypted bytes are stored as Base64 in the `APP_SETTINGS` SQLite table. Plaintext never touches disk.

### SQLite Local Database
`DatabaseService` initializes a local `data/noteapp.db` on first run. The schema mirrors the planned PostgreSQL structure exactly (same table names and column types), so migration will require only a JDBC driver swap and connection string change. The `Generic` brand is inserted as protected default data on initialization.

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
  → User selects options (Print / Email / GLPI)
      → NotePreviewController.handleGenerate()
          → if Print: WebView.print()
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
ItemDialogController    → ServiceLocator → IEquipmentService
DatabaseSectionController → ServiceLocator → IEquipmentService
SettingsController      → ConfigService
                        → WindowsDPAPIService
                        → DatabaseService
ProfileController       → DatabaseService
UserNoteController      → ServiceLocator → IADService
                        → ConfigService  (Motivo options)
ProviderNoteController  → ConfigService  (Motivo options)
```
