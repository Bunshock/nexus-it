# CLAUDE.md — Generador de Notas IT

Project-level instructions for Claude Code. These override defaults and apply to every task in this repo.

---

## Language conventions

- **UI text**: Spanish (labels, buttons, messages shown to the user)
- **Code**: English (variable names, method names, class names, comments)
- **Documentation**: English (README, docs/*.md)

---

## Code style rules

- Write **no comments** unless the WHY is non-obvious (hidden constraint, subtle invariant, workaround for a specific bug). Never explain what the code does.
- **Never make design changes** (UI layout, colors, spacing, view sizing, structural changes to FXML or CSS) without asking the user first. Always present options and wait for explicit approval before touching anything visual or structural.
- **Never add unrequested functionality.** If you think something would improve UX or DX, ask first and wait for explicit approval before implementing it.
- No dead code, no backwards-compatibility shims, no feature flags.

---

## Security requirements (mandatory on every feature)

- **No plaintext secrets** anywhere — not in source code, not in config files, not in git history.
- SMTP password and any other credentials must be encrypted at rest using **Windows DPAPI** via `WindowsDPAPIService` (JNA `Crypt32Util`). Store encrypted Base64 in the `APP_SETTINGS` SQLite table.
- **Validate all user inputs** at every system boundary (form fields, config values, FXML bindings).
- **Fail safely**: if AD, GLPI, or SMTP is unreachable, degrade gracefully — show a status message, do not crash and do not expose internal errors to the UI.
- The "Generic" brand is protected from deletion (`SqliteEquipmentService` enforces this).
- DB IP changes must trigger a security verification check against the remote database before accepting.

---

## Testing requirements (mandatory on every feature)

- Every implemented feature must have tests. Write test files **and run them** before marking a task done.
- Desktop app: **JUnit 5 + TestFX** (`src/test/java/...`)
- Run: `mvn test` from `desktop-app/`
- Current test count: 35 tests, all passing.
- Test classes: `TemplateEngineTest`, `AfFormatterTest`, `MockADServiceTest`, `MockEquipmentServiceTest`, `CachingServiceTest`

---

## Documentation requirements (keep updated with every feature)

After every feature implementation, update all of these before closing the task:

| File | Purpose |
|------|---------|
| `README.md` | Installation, usage, features |
| `docs/architecture.md` | Package structure, patterns, startup sequence, data flow |
| `docs/use-cases.md` | User-facing use cases (UC-XX format) |
| `docs/database.md` | SQLite schema ERD + notes |
| `docs/requirements.md` | Functional/non-functional requirements and user stories |

All documentation in English.

---

## Build and run

```bash
cd desktop-app

# Always use clean run — prevents stale IDE-compiled .class files from breaking the run
mvn clean javafx:run

# Tests
mvn test
```

The `data/noteapp.db` SQLite database is created automatically on first run.

---

## Project structure

```
notes-app-for-it/
├── desktop-app/              — JavaFX 21 app (active — branch: desktop-app)
│   ├── config/
│   │   ├── app-config.json   — Runtime config (A/F format, Motivo options, SMTP, API URLs)
│   │   └── mock-equipment.json — Equipment catalog mock data (types, brands, models)
│   ├── data/                 — SQLite DB (gitignored, created at runtime)
│   └── src/
│       ├── main/java/.../
│       │   ├── controllers/  — FXML controllers
│       │   ├── models/       — Data models (AppConfig, NoteReport, EquipmentType, ...)
│       │   ├── services/     — Service interfaces + implementations
│       │   └── utils/        — ViewFactory (session-level view cache)
│       └── main/resources/.../
│           ├── views/        — FXML files
│           └── templates/    — HTML note templates (entrega.html, etc.)
└── docs/                     — Architecture, requirements, schema, use cases
```

---

## Architecture — key patterns

### Service abstraction (Strategy)
Every external dependency has an interface (`IADService`, `IEquipmentService`, `IGLPIService`, `IEmailService`, `IHistoryService`). Concrete implementations are wired in `ServiceLocator.initialize()`. Mock → real is a one-line swap.

### ViewFactory — session state persistence
`ViewFactory` loads each section FXML exactly once and caches it. Navigation reuses the cached node so controller state (form data, item tables) survives tab switches within a session (FR-08).

### Template engine
`TemplateEngine.render()` processes `{{TOKEN}}` replacements and `{{#LOOP_KEY}}...{{/LOOP_KEY}}` expansions. `NoteGenerationService` selects the correct HTML template and builds token maps from form data.

### Windows DPAPI
`WindowsDPAPIService` wraps JNA `Crypt32Util.cryptProtectData(byte[])` / `cryptUnprotectData(byte[])`. Returns Base64 string for SQLite storage. Decryption is tied to the Windows user account — never portable as plaintext.

### Admin dialog pattern
`requireAdmin(Runnable)` in `SettingsController` and `DatabaseSectionController` handles the full admin flow: check `AdminAuthService.isConfigured()`, prompt password, verify hash, run action. Each controller also duplicates `buildDialogStage / buildDialogRoot / buildDialogScene / centerOnContent` — this duplication is intentional (no shared utility class, per the no-abstraction rule). Do not extract a base class or helper unless explicitly requested.

### Remote PostgreSQL (write-through cache)
`RemoteDatabaseService` manages the PostgreSQL connection. When `db_host` is set in `APP_SETTINGS`, `ServiceLocator.initialize()` calls `RemoteDatabaseService.configure(...)`, runs `ensureSchema()` (PostgreSQL DDL), then wraps both remote and local `SqliteEquipmentService`/`SqliteHistoryService` instances in `CachingEquipmentService`/`CachingHistoryService`. Reads try remote first, fall back to local SQLite on error. Writes go to remote first (fail loudly), then local SQLite best-effort. If remote is unreachable at startup, the app runs fully local. `APP_SETTINGS`, `TECHNICIAN_PROFILE`, and `SMTP` config are always local SQLite regardless of remote config.

### SQLite schema mirrors PostgreSQL
`DatabaseService` creates `data/noteapp.db` with the same table names and column types as the planned PostgreSQL schema. Migration = JDBC driver swap + connection string change.

---

## Note types and profiles

| Profile | Note type | Motivo | Notes |
|---------|-----------|--------|-------|
| Entrega | NOTE_ENTREGA_DEVOLUCION | Mandatory | |
| Devolución | NOTE_ENTREGA_DEVOLUCION | — | |
| Fin de Contrato | NOTE_ENTREGA_DEVOLUCION | — | |
| Recambio | NOTE_ENTREGA_DEVOLUCION | Mandatory | Generates TWO notes: Entrega + Devolución |
| Entrega - Proveedor | NOTE_PROVEEDOR | Mandatory | Has CUIT field |

---

## Services — current implementations

| Interface | Active implementation | Future |
|-----------|----------------------|--------|
| `IADService` | `MockADService` (in-memory) | REST API (Spring Boot, URL configurable via `adApi.baseUrl`) |
| `IEquipmentService` | `SqliteEquipmentService` (SQLite, seeded on first run) | PostgreSQL (JDBC driver swap) |
| `IHistoryService` | `SqliteHistoryService` | — |
| `IGLPIService` | `GLPIServiceStub` (no-op) | GLPI REST API (out of scope v1) |
| `IEmailService` | `GmailEmailService` (Jakarta Mail, STARTTLS port 587) | — |

---

## Config files

### `config/app-config.json`
- `afFormat`: `prefix`, `separator`, `length`, `filler` — controls A/F number formatting
- `motivoOptions`: per-profile Motivo dropdown values (`entrega`, `proveedor`, `recambio`)
- `smtp`: `host`, `port`, `senderAddress` (password stored encrypted in DB, never here)
- `adApi.baseUrl`, `glpiApi.baseUrl`, `database.baseUrl`: external service URLs
- `noteItemLimit`: max items before warning

**Jackson config**: `AppConfig` and all inner classes are annotated `@JsonIgnoreProperties(ignoreUnknown = true)` — unknown keys in the JSON file do not crash the app.

### `config/mock-equipment.json`
Types, brands, `typeBrands` junction entries, models, `snValidations`. Loaded by `MockEquipmentService` — **used only in tests**, not in production.

---

## SQLite tables

`TYPE`, `BRAND`, `BRAND_TYPE_LINK`, `MODEL`, `SN_VALIDATION`, `NOTE_REPORT`, `NOTE_ENTREGA_DEVOLUCION`, `NOTE_PROVEEDOR`, `NOTE_ITEM`, `TECHNICIAN_PROFILE`, `APP_SETTINGS`

See `docs/database.md` for full ERD.

### `APP_SETTINGS` keys in use

| Key | Encrypted | Purpose |
|-----|-----------|---------|
| `smtp_password` | DPAPI | SMTP sender password |
| `db_host` | No | Remote DB hostname |
| `db_port` | No | Remote DB port (default `5432`) |
| `db_name` | No | Remote DB database name |
| `db_username` | DPAPI | Remote DB username |
| `db_password` | DPAPI | Remote DB password |

### Equipment seed data

`DatabaseService.seedEquipmentData()` runs automatically on first startup when `TYPE` is empty. It inserts all types, brands, type-brand links, and models from the organization's catalog. Every type+brand combination also gets an `Otro / Genérico` model added. Re-running the app on an existing DB skips seeding entirely.

---

## Known issues / gotchas

- Always run `mvn clean javafx:run` — never just `mvn javafx:run`. The IDE (VS Code / Eclipse) can write broken `.class` files that Maven reuses without recompiling.
- `config/app-config.json` must use `"baseUrl"` (not `"remoteUrl"`) in all API endpoint objects to match `AppConfig.ApiEndpoint`.
- `WindowsDPAPIService` only works on Windows. Tests that invoke it will fail on Linux/macOS CI.
- SLF4J "Failed to load class StaticLoggerBinder" at runtime is harmless — ControlsFX logs via SLF4J but the app functions normally without a binding.
