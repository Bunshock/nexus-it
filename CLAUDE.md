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
- Current test count: 140 tests, all passing.
- Test classes: `TemplateEngineTest`, `AfFormatterTest`, `MockADServiceTest`, `AdApiServiceTest`, `MockEquipmentServiceTest`, `CachingServiceTest`, `GlpiStatusTest`, `NoteGenerationServiceTest`, `AdminSessionTest`, `RemoteDatabaseServiceTest`, `SqliteHistoryServiceTest`, `HistoryControllerTest`, `InputValidationTest`, `TechnicianSessionServiceTest`, `SettingsControllerTest`

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
`TemplateEngine.render(template, tokens, loops)` processes `{{TOKEN}}` replacements and expands every `{{#LOOP_KEY}}...{{/LOOP_KEY}}` block found against a `Map<String, List<Map<String,String>>> loops`. A loop key with no entry (or an empty list) expands to nothing — this doubles as a conditional block (used by `devolucion.html`'s `{{#FAILURE}}` section, populated only when Motivo = Falla). `NoteGenerationService` selects the correct HTML template and builds the token/loop maps from form data.

### Windows DPAPI
`WindowsDPAPIService` wraps JNA `Crypt32Util.cryptProtectData(byte[])` / `cryptUnprotectData(byte[])`. Returns Base64 string for SQLite storage. Decryption is tied to the Windows user account — never portable as plaintext.

### Admin dialog pattern
`requireAdmin(Runnable)` in `SettingsController` and `DatabaseSectionController` handles the full admin flow: check `AdminAuthService.isConfigured()`, prompt password, verify hash, run action. Each controller also duplicates `buildDialogStage / buildDialogRoot / buildDialogScene / centerOnContent` — this duplication is intentional (no shared utility class, per the no-abstraction rule). Do not extract a base class or helper unless explicitly requested. `MainController` and `NoteGeneratorController` duplicate the same pattern again for `showWarningNotice`/`showDialogNotice` (orange-accent warning popups) — `NoteGeneratorController` centers on `rootContainer` instead of a `contentArea`/`panelSettings`-style field, since it's a section-level controller, not the shell.

`AdminSession` is a singleton with `addOnActivateListener` / `addOnDeactivateListener` hooks. `NoteDetailController.open()` takes a boolean `adminMode` parameter — callers pass `AdminSession.getInstance().isActive()` at open time. Inside the popup, `case PENDING` in `buildGlpiStatusRow()` shows Sync/Reject buttons only when `adminMode && AdminSession.getInstance().isActive()`. This means admin actions are embedded in the note detail popup, not in a separate tab.

`SettingsController`'s general configuration fields (A/F format, SMTP, GLPI API) follow the same `setDisable(!adminActive)` gating as `ProfileController`'s technician fields: all inputs and the "Guardar Configuración" button are disabled by default and only become editable while `AdminSession.getInstance().isActive()`, wired via the same `addOnActivateListener`/`addOnDeactivateListener` pair (`SettingsController.onAdminStateChanged()`). Unlike `ProfileController`, the Save button is disabled rather than hidden when admin mode is off, since the user explicitly asked for a visible-but-disabled affordance here.

### Remote PostgreSQL (write-through cache)
`RemoteDatabaseService` manages the PostgreSQL connection. When `db_host` is set in `APP_SETTINGS`, `ServiceLocator.initialize()` calls `RemoteDatabaseService.configure(...)`, runs `ensureSchema()` (PostgreSQL DDL), then wraps both remote and local `SqliteEquipmentService`/`SqliteHistoryService` instances in `CachingEquipmentService`/`CachingHistoryService`. Reads try remote first, fall back to local SQLite on error. Writes go to remote first (fail loudly), then local SQLite best-effort. If remote is unreachable at startup, the app runs fully local. `APP_SETTINGS`, `TECHNICIAN_PROFILE`, and `SMTP` config are always local SQLite regardless of remote config.

### SQLite schema mirrors PostgreSQL
`DatabaseService` creates `data/noteapp.db` with the same table names and column types as the planned PostgreSQL schema. Migration = JDBC driver swap + connection string change.

---

## Note types and profiles

| Profile | UI `profileType` string | Note type | Template | Motivo | Notes |
|---------|--------------------------|-----------|----------|--------|-------|
| Entrega | `ENTREGA` | NOTE_ENTREGA_DEVOLUCION | `entrega.html` | Mandatory | |
| Devolución | `DEVOLUCIÓN` | NOTE_ENTREGA_DEVOLUCION | `devolucion.html` | Mandatory | |
| Fin de Contrato | `ENTREGA PERMANENTE` | NOTE_ENTREGA_DEVOLUCION | `entrega - fin de contrato.html` | Mandatory | |
| Préstamo | `PRÉSTAMO` | NOTE_ENTREGA_DEVOLUCION | `prestamo.html` | — | Captures an expected return date instead of Motivo (stored in the same `motivo` column) |
| Entrega - Proveedor | `Entrega - Proveedor` | NOTE_PROVEEDOR | `proveedor.html` | Mandatory | Has CUIT field |

"Recambio" is **not a note type** — it was removed entirely (2026-07-03) from `HistoryController.PROFILE_TYPE_OPTIONS` and every `toDisplayName()` switch (`NoteGenerationService`, `HistoryController`, `NoteDetailController`). It never had a live generation flow (no UI button ever produced it) and was only ever a filter-dropdown/display-name leftover. Do not reintroduce it.

### `NOTE_REPORT.profile_type` is stored raw — display it via `toDisplayName()`, never directly
`profile_type` is persisted exactly as `UserNoteController.getSelectedNoteType()` returns it — the literal, ALL-CAPS `ToggleButton` text ("ENTREGA", "DEVOLUCIÓN", "PRÉSTAMO", "ENTREGA PERMANENTE") or the hardcoded "Entrega - Proveedor" for providers. Anything that displays this value to a user must run it through a `toDisplayName(String)` mapper first (private, `NoteGenerationService.java` for note rendering; duplicated in `HistoryController.java` and `NoteDetailController.java` for History's table/CSV/XLSX/popup-title — same no-shared-abstraction convention as the dialog-builder methods). `HistoryController`'s table column, CSV export, and XLSX export, plus `NoteDetailController`'s popup title, were fixed to use this on 2026-07-03 — before that they showed the raw stored string.
**Filter bug fixed 2026-07-03:** `HistoryController.PROFILE_TYPE_OPTIONS` (the filter dropdown) uses nice-cased labels ("Entrega", "Devolución", "Fin de Contrato") that get passed into `SqliteHistoryService.getFiltered()`'s SQL `WHERE r.profile_type IN (...)`. Since `profile_type` is stored in two different casings depending on how the row was created (`DatabaseService.seedHistoryData()`'s demo rows use nice-cased literals; the live "Generar Nota" flow stores raw ALL-CAPS `ToggleButton` text), a filter for one label needs to match *both* forms. Fixed via `HistoryController.PROFILE_TYPE_LABEL_TO_RAW` (`Map<String, List<String>>`, mirroring `GLPI_LABEL_TO_CODE`'s pattern but one-to-many) and `expandProfileTypeLabels(Set<String>)`, called from `buildFilter()`. Verified against the real `data/noteapp.db`, not just test fixtures: filtering by "Devolución" now returns all 4 matching rows instead of 2.

All template placeholders (`{{TOKEN}}`) are named in English (`NAME`, `DNI`, `REASON`, `DATE`, `OBSERVATIONS`, `ASSET_TAG`, `COMPANY_NAME`, `CUIT`, `RESPONSIBLE_NAME`, `RESPONSIBLE_DNI`, `EXPECTED_RETURN_DATE`, `TECHNICIAN_NAME`, `TECHNICIAN_DNI`, plus the `{{#ITEMS}}` loop's `TYPE`/`BRAND`/`MODEL`/`SERIAL`/`ASSET_TAG`/`DETAILS` and Devolución's `{{#FAILURE}}` loop's `FAILURE_CAUSE`/`FAILURE_DETAILS`). `DNI` and `CUIT` are kept as-is (Argentine document types with no natural English equivalent). Visible prose inside templates stays in Spanish per the UI language convention — only the token identifiers are English. `NoteGenerationService` still populates an `EMAIL` token internally (from AD/user-note data, for potential future SMTP use), but **no template renders it** — email is deliberately not printed on any note.

The provider note's "Recibe por parte del proveedor" name/DNI (`ProviderNoteController.txtProviderResponsibleName`/`txtProviderResponsibleDni`, optional — filled only when `chkEnableResponsible` is checked) is fully persisted in `NOTE_PROVEEDOR.responsible_name`/`responsible_dni`, so it renders correctly both on freshly-generated notes and when re-rendering a stored report from History.

### Two-signature layout
Every template renders a `.signature-row` with two `.signature-section` boxes (48% width each, from `devolucion.html`/`entrega.html`/`entrega - fin de contrato.html`/`prestamo.html`; `proveedor.html` had this row already). Left box = the recipient (`NAME`/`DNI`) or, for provider notes, "Recibe por parte del proveedor" (`RESPONSIBLE_NAME`/`RESPONSIBLE_DNI`, shown but blank if `ProviderNoteController`'s checkbox is unchecked — this represents the provider's own person picking up the equipment, not an internal role). Right box is always the technician (`TECHNICIAN_NAME`/`TECHNICIAN_DNI`).

### Technician identity — session-only, sourced from Windows/AD
The technician's own identity (name, username, email, DNI) is **not persisted to disk**. `WindowsIdentityService` (JNA `Secur32Util.getUserNameEx(NameUserPrincipal)`) reads the Windows session's UPN (e.g. `user@domain.com`) — returns `null` on a non-domain machine (local/workgroup account), which is an expected, not-an-error case. `TechnicianSessionService` (singleton, in-memory only) derives the AD username by stripping everything after `@` (`TechnicianSessionService.deriveUsernameFromEmail`) and calls the existing `IADService.search(null, null, derivedUsername)` — no new AD interface method needed, since `ADUser` already carries `fullName`/`username`/`email`/`dni` in one record.

- **Refreshed at app startup** (`MainController.initialize()` → `refreshTechnicianSessionAsync()`, background thread) and **on demand** via the "Actualizar Perfil desde AD" button in `ProfileController` — never cached across restarts. `TechnicianSessionService.getLastError()` distinguishes three failure cases, all shown in Profile's status label and all mentioning that an admin can complete the fields manually: (1) Windows session UPN unresolvable (non-domain machine) — "No se pudo obtener el usuario de dominio de Windows"; (2) AD service reachable but no matching record — "Usuario no encontrado en Active Directory"; (3) AD `search()` threw (service unreachable) — "No se pudo conectar con Active Directory". The `search()` call is wrapped in try/catch specifically to distinguish case 3 from case 2.
- **If the startup refresh fails**, `MainController` shows a one-time **orange warning popup** (`showWarningNotice(title, message)`, `#f59e0b` accent + "⚠" icon — same warning color as `UserNoteController`'s inline AD-search feedback), centered on `contentArea` (`centerOnContent(Stage)`, mirroring `SettingsController`/`DatabaseSectionController`'s existing pattern) rather than the physical screen. `showNotice`/`showWarningNotice` both delegate to `showDialogNotice(title, message, accentColor, icon)`, which parameterizes `buildDialogRoot`'s border color (default black `#1a1a1a` for neutral notices like `AdminSession` expiry, orange for warnings).
- **Clicking "Actualizar Perfil desde AD" is self-evidently responsive even on a repeat identical failure** — `ProfileController.handleRefreshFromAd()` disables the button and changes its text to "Actualizando..." for the duration of the call (independent of whether the resulting message differs from before), and `flashStatus(message, color)` plays a quick opacity pulse (`FadeTransition`, 0.2→1.0 over 200ms) every time the status label is set — including on success, which previously showed no feedback at all on a successful refresh.
- **Success message depends on the update's source**, not just whether the session is resolved — `TechnicianSessionService.UpdateSource` (`AD` / `MANUAL`) is set alongside `name`/`username`/`email`/`dni` on every successful `refreshFromWindowsSession()` or `applyManualOverride()` call, and read back via `getLastUpdateSource()`. `ProfileController.populateFieldsFromSession()` (the single shared listener callback for *both* outcomes) branches on it: "Perfil actualizado desde Active Directory" for `AD`, "Perfil guardado correctamente" for `MANUAL`. Without this, an admin's manual save incorrectly displayed the AD-refresh message, since both paths funnel through the same `notifyListeners()` → `populateFieldsFromSession()` callback.
- **Disabled (not just non-editable) in the UI** — `ProfileController`'s 4 fields (`txtProfileName`/`txtProfileUsername`/`txtProfileDni`/`txtProfileEmail`) are `disable=true` by default (`ProfileView.fxml`), giving the standard native grayed-out look, not just a blocked-typing `editable=false` state. They become enabled, and the "Guardar" button appears, **only when `AdminSession.getInstance().isActive()`** — `ProfileController.updateEditability()` subscribes to `AdminSession`'s activate/deactivate listeners exactly like `MainController`/`SettingsController` already do. Admin edits go through `TechnicianSessionService.applyManualOverride(...)` — session-only, lost on next AD refresh or app restart. Fields are laid out one-below-the-other, capped at 360px wide (`ProfileView.fxml`), with a `Separator` between the description text and the field list.
- **Sidebar welcome message** (`MainController.lblWelcome`/`lblUsername`) subscribes to `TechnicianSessionService.addOnChangeListener(...)` and updates immediately on any change (startup resolution, manual refresh, or admin override) — no restart needed. This replaced the old one-shot, Profile-independent AD lookup that used to live directly in `MainController`. Format: `lblWelcome` always shows "Hola, {nombre}!" (or plain "Hola!" if unresolved); `lblUsername` (directly below) shows "Usuario: {username}" when resolved, or "Perfil no configurado" when not — never left blank, so an unconfigured profile is visible at a glance without opening Mi Perfil.
- **Note generation is blocked** (`NoteGeneratorController.handleGenerateNote()`, checked first, before any other validation) if `TechnicianSessionService.getName()`/`getDni()` are blank — every `NOTE_REPORT` must be traceable to the technician who created it. `showMissingTechnicianProfileError()` shows this via the same orange-accent `showWarningNotice()` dialog pattern (see [Admin dialog pattern](#admin-dialog-pattern)), directing the user to Mi Perfil's "Actualizar Perfil desde AD" button (or an admin to manually complete the fields).
- **`TECHNICIAN_PROFILE` SQLite/Postgres table still exists in the schema but is no longer written to** — kept only so old historical notes (linked via the now-legacy `NOTE_REPORT.technician_id` FK) still resolve an author name/DNI. New notes store the technician's name/DNI as plain text directly on `NOTE_REPORT` (`technician_name`/`technician_dni` columns — see [SQLite tables](#sqlite-tables)), the same "snapshot, don't reference" pattern `NOTE_ITEM` already uses for equipment type/brand/model.
- **Deferred, not yet built**: AD read-permission gating (disable AD-dependent buttons if the app/user lacks AD read access) — tracked in memory, not implemented.

### Falla (failure) detail capture — Devolución only
When Motivo = "Falla" is selected in the Devolución flow, `UserNoteController` opens `FailureDetailView.fxml` (`FailureDetailController`) modally. Only the cause combobox (from `AppConfig.fallaOptions`, configurable in `app-config.json`) is mandatory — the free-text details field is optional. Cancelling the popup clears the Motivo selection back to its placeholder (`UserNoteController.onFailureDialogCancelled()`), so the technician must re-select "Falla" to reopen it. The result is stored as `NOTE_ENTREGA_DEVOLUCION.failure_cause`/`failure_details` and rendered in `devolucion.html`'s `{{#FAILURE}}` conditional block as "Detalles de Falla". This only applies to Devolución — other note types never deliver broken equipment, so they have no Falla flow.

### Input validation
- **Name fields** (recipient name in `UserNoteController`, technician name in `ProfileController`, provider's responsible-person name in `ProviderNoteController`): a `TextFormatter` blocks non-letter/non-space characters as typed; a submit-time regex (`^\p{L}+( \p{L}+)*$`) additionally rejects leading/trailing/double spaces. The provider's own **company** name field is intentionally excluded (business names legitimately contain numbers/abbreviations).
- **DNI fields** (same three people): `TextFormatter` restricts to digits, max 8 chars; submit-time regex (`^\d{7,8}$`) requires 7 or 8 digits, no dots.
- **Email** (`ProfileController.txtProfileEmail` only — the user-note email is AD-populated, not typed): submit-time regex validated as a well-formed address.
- These three controllers each duplicate their own `NAME_PATTERN`/`DNI_PATTERN` (and `ProfileController` also `EMAIL_PATTERN`) — intentional duplication, no shared validator class, per the no-abstraction rule.
- `ProviderNoteController` gained a `validateAndShowErrors()` method (previously provider notes had no validation at all); `NoteGeneratorController.handleGenerateNote()` now calls it for the provider branch, mirroring the existing user-note validation call.

---

## Services — current implementations

| Interface | Active implementation | Future |
|-----------|----------------------|--------|
| `IADService` | `AdApiService` (REST client, `adApi.baseUrl` + DPAPI-encrypted `ad_api_token`) | — |
| `IEquipmentService` | `SqliteEquipmentService` (SQLite, seeded on first run) | PostgreSQL (JDBC driver swap) |
| `IHistoryService` | `SqliteHistoryService` | — |
| `IGLPIService` | `GLPIServiceStub` (no-op) | GLPI REST API (out of scope v1) |
| `IEmailService` | `GmailEmailService` (Jakarta Mail, STARTTLS port 587) | — |

---

## Config files

### `config/app-config.json`
- `afFormat`: `prefix`, `separator`, `length`, `filler` — controls A/F number formatting
- `motivoOptions`: per-profile Motivo dropdown values (`entrega`, `proveedor`, `devolucion`)
- `fallaOptions`: failure-cause combobox values shown by the Falla detail popup (Devolución only)
- `smtp`: `host`, `port`, `senderAddress` (password stored encrypted in DB, never here)
- `adApi.baseUrl`, `glpiApi.baseUrl`, `database.baseUrl`: external service URLs
- GLPI API key and AD API token are **not** in this file — they're credentials, stored DPAPI-encrypted in `APP_SETTINGS` (`glpi_api_key`, `ad_api_token`), same as `smtp_password`. Both are configured via a write-only `PasswordField` in `SettingsController` (never re-displayed once saved) and verified with a live test-before-save call before persisting (see `docs/architecture.md`'s "Active Directory Integration" section).
- `noteItemLimit`: max items before warning

**Jackson config**: `AppConfig` and all inner classes are annotated `@JsonIgnoreProperties(ignoreUnknown = true)` — unknown keys in the JSON file do not crash the app.

### `config/mock-equipment.json`
Types, brands, `typeBrands` junction entries, models, `snValidations`. Loaded by `MockEquipmentService` — **used only in tests**, not in production.

---

## SQLite tables

`TYPE`, `BRAND`, `BRAND_TYPE_LINK`, `MODEL`, `SN_VALIDATION`, `NOTE_REPORT`, `NOTE_ENTREGA_DEVOLUCION`, `NOTE_PROVEEDOR`, `NOTE_ITEM`, `TECHNICIAN_PROFILE`, `APP_SETTINGS`

`NOTE_ENTREGA_DEVOLUCION` has `failure_cause`/`failure_details` columns (Devolución's Falla flow — always `NULL` for other note types). `NOTE_PROVEEDOR` has `responsible_name`/`responsible_dni` columns (the provider's own receiving person — see [Two-signature layout](#two-signature-layout)). `NOTE_REPORT` has `technician_name`/`technician_dni` columns (see [Technician identity](#technician-identity--session-only-sourced-from-windowsad)) alongside the older, now-legacy `technician_id` FK to `TECHNICIAN_PROFILE`. `SqliteHistoryService`'s history queries `COALESCE(r.technician_name, tp.name) AS author_name` (and same for `dni`) — new notes read straight off `NOTE_REPORT`, old notes fall back to the `TECHNICIAN_PROFILE` join. Both the SQLite (`DatabaseService`) and PostgreSQL (`RemoteDatabaseService.ensureSchema()`) DDL must stay in sync — see [SQLite schema mirrors PostgreSQL](#sqlite-schema-mirrors-postgresql).

**Schema migration pattern**: `CREATE TABLE IF NOT EXISTS` silently no-ops on a database that already has the table from an older schema version — a column added after initial release will **never** land on an existing `data/noteapp.db` unless explicitly migrated. `DatabaseService.migrateSchema()` (called from `initialize()`, after all `CREATE TABLE` calls) runs idempotent `ALTER TABLE ... ADD COLUMN` statements via `addColumnIfMissing()`, which swallows the "column already exists" `SQLException` (SQLite has no `ADD COLUMN IF NOT EXISTS`). `RemoteDatabaseService.ensureSchema()` uses Postgres's native `ADD COLUMN IF NOT EXISTS` instead. **Any new column on an existing table must be added to `migrateSchema()`/`ensureSchema()`, not just the `CREATE TABLE` block** — forgetting this was the exact cause of a `SQLException` when previewing History notes after the Falla/responsible-person columns were added but not migrated.

See `docs/database.md` for full ERD.

### `APP_SETTINGS` keys in use

| Key | Encrypted | Purpose |
|-----|-----------|---------|
| `smtp_password` | DPAPI | SMTP sender password |
| `glpi_api_key` | DPAPI | GLPI REST API key |
| `ad_api_token` | DPAPI | AD API bearer token |
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
- The sidebar's AD status dot (`MainController.circleAD`) now reflects real `AdApiService` reachability: `checkAdReachable()` queries by the current technician's already-resolved username (`TechnicianSessionService.getUsername()`), and a thrown exception (401/5xx/network failure) means genuinely "down," not a mock artifact. If the technician's username isn't resolved yet, the check is skipped for that cycle (shown as unreachable) rather than sending an unbounded query. A third gray "No configurado" state shows via `IADService.isConfigured()` when the AD URL/token aren't set.
- `.modern-table` (`styles.css`) is shared by History's `tblGlobal`, Settings' S/N Validation `tblSnValidation`, and the note-generation item tables `tblAssets`/`tblCountables` (`NoteGeneratorView.fxml`) — a selected-row text-color change intended only for one silently affects all four. Only History's `tblGlobal` intentionally kept the darker selected-row text from commit `349c9e0`; the other three carry an extra `equipment-table` class that overrides `.modern-table .table-row-cell:selected .table-cell`'s text-fill back to the original light color. If a future change needs to diverge these tables' styling further, extend `.equipment-table`'s rule rather than editing `.modern-table` directly — and check whether it should apply to `tblAssets`/`tblCountables` too, not just `tblSnValidation`.
- **`App.java` creates the Scene with a fixed size** (`visualBounds.getWidth() * 0.85`, `* 0.95`) — it does not grow to fit content. `MainView.fxml`'s `BorderPane` gives `left` (sidebar) and `center` (`contentArea`) the same actual height, so if any section's root content isn't height-bounded and its min-height exceeds the fixed window height, the whole row is forced taller than the visible viewport — the sidebar's bottom-anchored nav buttons and status dots get pushed down and off-screen, even though the sidebar's own content never changed. This is exactly what happened when the AD API card was added to `SettingsController`'s config list without a `ScrollPane`. Fixed by wrapping `panelSettings`'s card list in a `ScrollPane` (`styleClass="settings-scroll"`, styled transparent in `styles.css`) with `VBox.vgrow="ALWAYS"`, and moving the "Guardar Configuración" button to a fixed footer `HBox` outside the `ScrollPane`. **Any future card added to Settings must go inside that `ScrollPane`'s content `VBox`, not appended directly to `panelSettings`**, or this bug recurs. The same risk applies to any other section root that isn't already scrollable if its content can grow unboundedly.
