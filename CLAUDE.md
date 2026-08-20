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
- SMTP password, GLPI API key, DB password, and AD API token must be encrypted at rest using **`AppKeyEncryptionService`** (AES-256/GCM, `javax.crypto`, no native dependency). Store encrypted Base64 in the `APP_SETTINGS` SQLite table. **This replaced Windows DPAPI on 2026-07-08** — see "AppKeyEncryptionService replaced WindowsDPAPIService" in Known issues/gotchas below for why, and the accepted security trade-off.
- **Validate all user inputs** at every system boundary (form fields, config values, FXML bindings).
- **Fail safely**: if AD, GLPI, or SMTP is unreachable, degrade gracefully — show a status message, do not crash and do not expose internal errors to the UI.
- The "Generic" brand is protected from deletion (`SqliteEquipmentService` enforces this).
- DB IP changes must trigger a security verification check against the remote database before accepting. **This is the `testConnection()`/`confirmSaveDespiteFailedTest()` flow in `DatabaseSectionController.openEditConnectionDialog()`, independent of who can open that dialog** — removing its admin-password gate on 2026-07-24 (see "Admin dialog pattern" below) didn't touch this requirement, since it was never what enforced it.

---

## Testing requirements (mandatory on every feature)

- Every implemented feature must have tests. Write test files **and run them** before marking a task done.
- Desktop app: **JUnit 5 + TestFX** (`src/test/java/...`)
- Run: `mvn test` from `desktop-app/`
- Current test count: 524 tests, all passing.
- Test classes: `TemplateEngineTest`, `PrestamoHistoryControllerTest`, `ProviderNoteViewFxmlTest`, `MockADServiceTest`, `AdApiServiceTest`, `AppKeyEncryptionServiceTest`, `MockEquipmentServiceTest`, `CachingServiceTest`, `GlpiStatusTest`, `ReturnStatusTest`, `NoteGenerationServiceTest`, `AdminSessionTest`, `RemoteDatabaseServiceTest`, `SqliteHistoryServiceTest`, `DatabaseServiceMigrationTest`, `HistoryControllerTest`, `InputValidationTest`, `TechnicianSessionServiceTest`, `SettingsControllerTest`, `SqliteEquipmentServiceTest`, `ServiceLocatorProvisionTest`, `UserNoteFallaPersistenceTest`, `UserNoteViewFxmlTest`, `SettingsControllerSnFilterTest`, `SettingsViewFxmlTest`, `ProfileControllerDisplayNameTest`, `ProfileViewFxmlTest`, `CatalogMigrationToolTest`, `DemoSeedSqlTest`, `StarterTemplateSqlTest`, `SqlServerSeedSqlSnValidationTest`, `NotePreviewViewFxmlTest`, `NoteDetailViewFxmlTest`, `PrestamosViewFxmlTest`, `PrestamoNewLoanViewFxmlTest`, `PrestamoDetailViewFxmlTest`, `NotePreviewControllerGlpiStatusTest`, `PendingCountsServiceTest`, `NoteGeneratorViewFxmlTest`, `SqliteUserRoleServiceTest`, `LoginControllerTest`, `LoginViewFxmlTest`, `DatabaseSectionViewFxmlTest`

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

**Mermaid diagrams are part of "keep updated" too** (added 2026-07-10) — `docs/database.md`'s ER diagrams and `docs/architecture.md`'s service-layer class diagram + Service Dependency Map flowchart. A new table needs a new/updated ER diagram; a new service interface/implementation or a controller starting/stopping a service call needs the class diagram / dependency flowchart updated in the same commit. Both files have their own maintenance-reminder blockquote near the top — read it before editing either file.

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
Every external dependency has an interface (`IADService`, `IEquipmentService`, `IGLPIService`, `IEmailService`, `IHistoryService`, `IUserRoleService`). Concrete implementations are wired in `ServiceLocator.initialize()`. Mock → real is a one-line swap.

### ViewFactory — session state persistence
`ViewFactory` loads each section FXML exactly once and caches it. Navigation reuses the cached node so controller state (form data, item tables) survives tab switches within a session (FR-08).

### Template engine
`TemplateEngine.render(template, tokens, loops)` processes `{{TOKEN}}` replacements and expands every `{{#LOOP_KEY}}...{{/LOOP_KEY}}` block found against a `Map<String, List<Map<String,String>>> loops`. A loop key with no entry (or an empty list) expands to nothing — this doubles as a conditional block (used by `devolucion.html`'s `{{#FAILURE}}` section, populated only when Motivo = Falla). `NoteGenerationService` selects the correct HTML template and builds the token/loop maps from form data.

**Nested loops, added 2026-07-16** — `expandLoops()` now recurses into each matched block (against the same full `loops` map) before replacing that block's own-scope tokens, so a *different* loop key can be nested inside another loop's block. This lets a "presence flag" loop (a single dummy entry when a list is non-empty, or empty when it's not — see `NoteGenerationService.presenceFlag(boolean)`) wrap an entire section, header included, while a separate per-item loop repeats just the rows inside it — used by the item tables below to make a whole table (not just its rows) disappear when that item list is empty. Existing single-level usages (`FAILURE`, and previously `ITEMS`) are unaffected — recursing into a block with no nested loop tags is a no-op.

### Credential encryption (AppKeyEncryptionService)
`AppKeyEncryptionService` (AES-256/GCM via `javax.crypto`) replaced `WindowsDPAPIService` on 2026-07-08. Returns Base64 (random IV + ciphertext) for SQLite storage. The key is a fixed constant shared across every installation — see "AppKeyEncryptionService replaced WindowsDPAPIService" in Known issues/gotchas for the full rationale and accepted trade-off.

### Admin dialog pattern
`requireAdmin(Runnable)` in `SettingsController` and `DatabaseSectionController` handles the full admin flow: check `AdminAuthService.isConfigured()`, prompt password, verify hash, run action. `MainController` and `NoteGeneratorController` duplicate a related pattern for `showWarningNotice`/`showDialogNotice` (orange-accent warning popups) — `NoteGeneratorController` centers on `rootContainer` instead of a `contentArea`/`panelSettings`-style field, since it's a section-level controller, not the shell.

**Superseded 2026-07-30**: `DatabaseSectionController`'s `requireAdmin(Runnable)` was renamed
`requirePermission(Permission, Runnable)` — see
[Role-based permissions (RBAC)](#role-based-permissions-rbac-a-superadmin-tier-and-sede-scoped-admin-actions-2026-07-30)
below. `SettingsController` never had its own `requireAdmin()`/dialog-based password prompt (its
fields were always gated by a plain `setDisable(!adminActive)` check, now `hasPermission(...)`
per field group).

**Superseded 2026-08-20 — `buildDialogStage`/`buildDialogRoot`/`buildDialogScene`/`centerOnContent`
extracted to `utils.core.DialogChrome`, direct user request, overriding the "no shared utility
class" rule this section used to state.** These 4 methods used to be duplicated verbatim (or
near-verbatim) across all 9 controllers that open a dialog — `DatabaseSectionController`,
`SettingsController`, `AboutController`, `MainController`, `RemitoNoteController`,
`NoteGeneratorController`, `PrestamoNewLoanController`, `NoteDetailController`,
`PrestamoDetailController`. A pre-extraction survey found real per-screen variance, not just
copy-paste noise — the extraction had to be parameterized, not a blind merge:
- **`centerOnContent`'s anchor node differs per controller** — 6 controllers anchor on their own
  `rootContainer`; `MainController` anchors on `contentArea`; `AboutController` anchors on
  `panelAbout`; `SettingsController` anchors on a **dynamic** target
  (`panelSettings.isVisible() ? panelSettings : panelSnValidation`). `DialogChrome.centerOnContent(Stage,
  Region anchor)` takes the anchor as a parameter — each call site passes its own anchor
  expression (including the ternary, inline, for `SettingsController`).
- **`NoteDetailController`/`PrestamoDetailController` never had a separate `centerOnContent`
  method at all** — the centering logic was baked directly into their own `buildDialogStage()`.
  Normalized to call `DialogChrome.buildDialogStage()` then `DialogChrome.centerOnContent(stage,
  rootContainer)` explicitly, same as every other controller — a structural change (how the code
  is expressed) with no behavioral change (same opacity/`setOnShown`/bounds math, same timing
  relative to `setScene()`).
- **`buildDialogRoot` had 2 signatures** — 5 controllers took `(double prefWidth)` with a
  hardcoded `#1a1a1a` background; 4 took `(double prefWidth, String accentColor)`. Unified to the
  latter; the 5 fixed-color controllers now pass `"#1a1a1a"` explicitly.
- **`buildDialogScene` was byte-for-byte identical across all 9 controllers** — zero behavioral
  variance, extracted as-is.
- Every controller's own copy of these 4 methods was deleted (replaced with a one-line comment
  pointing at `DialogChrome`), and `getClass().getResource(...)` calls (which need to stay
  resolvable regardless of caller) were verified to still work via `DialogChrome.class.getResource(...)`
  with an absolute resource path.
- Full suite verified green before and after (531 tests, 1 pre-existing unrelated failure in
  `NoteGenerationServiceTest` present on both the pre- and post-extraction commit — confirmed via
  `git stash` before attributing it) — this extraction did not change that count.
- **`requireAdmin`/`requirePermission` and `showWarningNotice`/`showDialogNotice` remain
  independently duplicated per controller, unaffected by this change** — only the 4 dialog-chrome
  builder methods were extracted; the rest of this codebase's no-shared-abstraction convention
  (documented throughout this file) still applies everywhere else, including the admin-flow logic
  these dialogs are used inside of.

**Exception, 2026-07-24**: `DatabaseSectionController.handleEditConnection()` (the remote DB host/port/name/username/password dialog) no longer calls `requireAdmin()` — explicit user decision, since `db_*` settings are per-machine (local `APP_SETTINGS` only, never synced) and the existing test-connection-before-accepting flow (see Security requirements above) already guards against silently saving a bad config, regardless of who opens the dialog. This surfaced a real, separate issue while reviewing it: `openEditConnectionDialog()`'s password field used to pre-fill with the *decrypted* current `db_password` — harmless while only an admin could reach it, but a real plaintext-disclosure risk once anyone can. Fixed in the same change: the password field is now write-only, like every other secret field in this app (SMTP/GLPI/AD) — blank by default (`promptText="Dejar en blanco para no cambiarla"`), and `db_password` is only re-saved when the field is actually non-blank; a blank save resolves to the existing decrypted password for `configure()`/`testConnection()` (so leaving it blank doesn't break the live connection), but never writes it back to `APP_SETTINGS` again. The username field was deliberately left as-is (still pre-filled, plain `TextField`) — out of scope for this change, and a username alone isn't a credential.

**A second, more serious gap, caught by direct user question the same day**: reusing the stored password on a blank field is only safe when the *destination* hasn't changed. The blank-password resolution originally applied unconditionally — meaning anyone could retype `host` to a server of their own choosing, leave the password blank, and the app would submit the real, live `db_password` to that new host via the test-connection call, before anything was even saved. This doesn't require ever reading the password back (the write-only fix above didn't address it) — it lets the app itself relay the live credential to an arbitrary destination on request, which is the more dangerous vector. Fixed by comparing the typed `host`/`portStr` against the currently-stored values: a blank password is only accepted when both are unchanged from what's already configured (changing just the database name or username against the *same* already-trusted host is still fine blank); changing host or port with a blank password now shows an inline error ("Ingrese la contraseña al cambiar de servidor o puerto") and the save/test never runs at all.

`AdminSession` is a singleton with `addOnActivateListener` / `addOnDeactivateListener` hooks. `NoteDetailController.open()` takes a boolean `adminMode` parameter — callers pass `AdminSession.getInstance().isActive()` at open time. Inside the popup, `case PENDING` in `buildGlpiStatusRow()` shows Sync/Reject buttons only when `adminMode && AdminSession.getInstance().isActive()`. This means admin actions are embedded in the note detail popup, not in a separate tab.

`SettingsController`'s general configuration fields (A/F format, SMTP, GLPI API) follow the same `setDisable(!adminActive)` gating as `ProfileController`'s technician fields: all inputs and the "Guardar Configuración" button are disabled by default and only become editable while `AdminSession.getInstance().isActive()`, wired via the same `addOnActivateListener`/`addOnDeactivateListener` pair (`SettingsController.onAdminStateChanged()`). Unlike `ProfileController`, the Save button is disabled rather than hidden when admin mode is off, since the user explicitly asked for a visible-but-disabled affordance here.

**Superseded 2026-07-24 by login-based role activation, see [Login screen and role-based admin mode](#login-screen-and-role-based-admin-mode) below** — the self-service "Activar modo administrador" toggle this paragraph originally described no longer exists; `AdminSession` itself, `requireAdmin()`, and the `setDisable(!adminActive)` field-gating pattern are otherwise unchanged.

### Login screen and role-based admin mode

Added 2026-07-24. Replaces the previous zero-friction identity model (`TechnicianSessionService.refreshFromWindowsSession()`, silently resolving identity from the Windows session's UPN at every startup, no password ever typed anywhere) with a real login screen shown *before* `MainView` — and before its startup connectivity overlay — is even constructed. Also replaces the shared-password "Activar modo administrador" self-toggle in Configuración with role-based auto-activation at login.

**Fixed same day**: `LoginView.fxml`'s root had no `stylesheets` attribute, unlike `MainView.fxml` (which sets `styles.css` directly on its own root node) — so every `styleClass` reference on the login screen (`button-primary-large`, `button-secondary-large`, `section-label`, `input-label-small`, `form-input-main`) resolved to nothing, and the whole screen rendered in plain default JavaFX styling instead of the app's actual look. Fixed by adding `stylesheets="/com/bunshock/note_app_for_it_frontend/css/styles.css"` to `LoginView.fxml`'s root `StackPane`, matching `MainView.fxml`'s own convention.

- **`App.java` shows a login `Scene` first**, small and fixed-size (420×560, same transparent/rounded-card chrome every dialog in this app already uses), built from a new `views/LoginView.fxml` + `LoginController`. Only on a successful login does `App.showMainApp(Stage)` (the entire previous `start()` body, now a separate method) build `MainView.fxml` and run the existing startup sequence — `MainController`'s connectivity overlay, sidebar status checks, etc. are never constructed at all until login succeeds, so there is genuinely nothing else on screen beforehand. `LoginController` never touches the `Stage` directly; it reports success via a plain `Runnable` callback (`setOnLoginSuccess`), keeping `App.java` the only place that owns Stage/Scene swapping.
- **Login flow**: username (pre-filled from `WindowsIdentityService.getSessionEmail()` via the now-`public` `TechnicianSessionService.deriveUsernameFromEmail()` — a convenience only, never trusted for authentication) + password, submitted to a new `IADService.validateCredentials(username, password)` method (same file as `search()` — no separate interface needed). Returns a new `AdCredentialResult(valid, groups)` model. `AdApiService` calls a **new AD API endpoint that doesn't exist yet** — `POST /api/v1/ad/validate-credentials`, body `{username, password}`, response `{"valid": true/false, "groups": [...]}`, same Bearer service-account token as the existing lookup calls. This is the one piece of this feature genuinely blocked on external work; everything else was built and tested against `MockADService`'s own stub (fixed password `"password123"`, any of its 3 mock users, returns a mock `"AllowedAppUsers"` group) so the app-side flow didn't have to wait for it.
- **App access is gated by AD group membership, not a local whitelist** — a new `AppConfig.AdAccessConfig.adAccess.allowedGroupName` (`app-config.json`'s `adAccess.allowedGroupName`) is checked against `AdCredentialResult.getGroups()` after a successful password check; failing it shows "No tiene permisos para usar esta aplicación" and the technician stays on the login screen. **The real group name is still unknown — deliberately left blank** (blank = check skipped entirely, same degrade-gracefully convention as GLPI/AD/SMTP being unconfigured elsewhere in this app) pending the user's own investigation into what it's actually called in their AD. Do not guess a value here.
- **Role source: a new local `USER_ROLE` table** (`username` PK, `role` — `"ADMIN"`/`"USER"`), not AD groups — a deliberately separate concern from the access gate above. New `IUserRoleService`/`SqliteUserRoleService`/`CachingUserRoleService`/`MockUserRoleService`, mirroring `IEquipmentService`'s remote-first/local-fallback pattern (remote-first read, fall back to local), wired into `ServiceLocator` alongside the others. `getRole(username)` defaults to `"USER"` when no row exists — most technicians are never promoted. **Read-only by design, no in-app UI at all** — `IUserRoleService` only exposes `getRole()`; an admin promotes/demotes an account by running SQL directly against `USER_ROLE` (`INSERT`/`UPDATE`/`DELETE`), not through the app. A first version of this added a 6th flat catalog list ("USUARIOS") in Base de Datos for this, admin-gated via `requireAdmin()` — **removed same day, explicit user decision**: direct SQL was preferred over an in-app CRUD screen for something this infrequent. `getAllUsers()`/`setRole()`/`removeUser()` and the `UserRole` model were deleted along with it, not left as unused code. `MockUserRoleService` alone keeps a test-only `setRole()` (not part of the interface) purely so tests can arrange a role without touching a real database.
  **Superseded 2026-07-30**: `USER_ROLE` was renamed `APP_USER` (surrogate PK, `sede_id` added,
  `SUPERADMIN` added to the role `CHECK`) and `IUserRoleService` grew `getSedeId()`/
  `getPermissionsForRole()` — see
  [Role-based permissions (RBAC)](#role-based-permissions-rbac-a-superadmin-tier-and-sede-scoped-admin-actions-2026-07-30)
  below. The read-only-by-direct-SQL-only design principle itself is unchanged and now also
  governs the new `ROLE_PERMISSION` table.
- **TEMPORARY: mock credential validation** (`AppConfig.AdAccessConfig.mockCredentialValidation`, `app-config.json`'s `adAccess.mockCredentialValidation`, default `false`) — added the same day, since the real AD API genuinely cannot be extended with the `validate-credentials` endpoint yet and the user still needed to log in and use the app. When `true`, `AdApiService.validateCredentials()` skips the HTTP call entirely (`AdApiService.mockValidateCredentials()`): it accepts **any password**, but still confirms the typed username is a real AD account via the already-working `search()` lookup, and still supplies the configured `allowedGroupName` (if set) in its returned groups so the access gate isn't accidentally defeated too. Prints an unmissable `[MOCK]` line to stdout on every use. **Must be set back to `false`** once the real endpoint exists — this is a real, accepted-for-now security bypass (any correct username logs in with any password), not a permanent config knob.
- **`TechnicianSessionService.loginResolved(ADUser, role)`** replaces `refreshFromWindowsSession()` as the session-populating entry point (called once, by `LoginController`, after every check above already passed — this method does no AD I/O of its own). An `ADMIN` role calls `AdminSession.getInstance().activatePermanently()` — a new activation mode (alongside the existing `activate()`) that skips the 15-minute inactivity timeout entirely, since the technician's own login already proved their identity and the only UI that used to let anyone get back in after that timeout (the removed toggle) no longer exists for anyone to click. `activatePermanently()`/`activate()`/`deactivate()` all remain on one `AdminSession` singleton; `isActive()` just short-circuits `true` for a permanently-activated session instead of ever checking `lastActivity`.
  **Superseded 2026-07-30**: `activatePermanently()` now takes a `String role` parameter (so a
  `SUPERADMIN` login activates as `SUPERADMIN`, not `ADMIN`) — see
  [Role-based permissions (RBAC)](#role-based-permissions-rbac-a-superadmin-tier-and-sede-scoped-admin-actions-2026-07-30)
  below for the full redesign; `activate()`/`deactivate()` and the timeout mechanics themselves are
  unchanged.
- **"Actualizar Perfil desde AD" no longer re-authenticates** — `ProfileController.handleRefreshFromAd()` used to call the now-removed `refreshFromWindowsSession()` (which, on failure, wiped the whole session via a `clear(String)` method that no longer exists). It now calls a plain `search(null, null, username)` lookup and either `TechnicianSessionService.refreshProfileFromAd(ADUser)` (updates name/email/dni only, leaves username/role untouched) or `reportProfileRefreshError(message)` (sets `lastError` for the existing `lblProfileStatus` display, **without** touching the rest of the session) — an AD hiccup while refreshing a profile must not force a technician to log in again.
- **Real, accepted feature loss for non-admin-role technicians, flagged rather than silently absorbed**: every UI element gated purely on `AdminSession.isActive()` (not wrapped in `requireAdmin()`'s own password-prompt fallback) — `NoteDetailController`'s GLPI Sync/Reject buttons, `PrestamoDetailController`'s Devuelto/No devuelto buttons, and `ProfileController`'s own manual-edit fields — become reachable only by an `ADMIN`-role login now, since there is no longer any UI path for a non-admin-role technician to make `AdminSession.isActive()` true at all. `requireAdmin()`-wrapped actions (Base de Datos catalog CRUD, S/N validation edit) are **unaffected** — that method already had its own independent per-click password fallback, untouched by any of this. Deliberately not rewiring the three admin-session-gated cases above onto `requireAdmin()` too — that would be adding functionality nobody asked for, not fixing a bug.
  **Superseded 2026-07-30**: every one of these `isActive()`-only gates (including `requireAdmin()`
  itself, renamed `requirePermission()`) was converted to a specific `Permission` check — see
  [Role-based permissions (RBAC)](#role-based-permissions-rbac-a-superadmin-tier-and-sede-scoped-admin-actions-2026-07-30)
  below.
- **`AboutView.fxml`'s manual (section 11)** was rewritten to match: no more "Se activa desde Ajustes con el botón..." instructions, since that button is gone; now describes automatic role-based activation, the never-expiring session, and that a non-admin-role technician can still reach the `requireAdmin()`-gated actions via the shared password.
- **Tests**: `SqliteUserRoleServiceTest` (real SQLite-backed, rows seeded with plain `INSERT` statements — matching how a real admin edits this table — not a `setRole()` call), `MockADServiceTest` additions for `validateCredentials()`, `TechnicianSessionServiceTest` additions for `loginResolved()`/`refreshProfileFromAd()`/`reportProfileRefreshError()`, `AdminSessionTest` additions for `activatePermanently()`, `LoginControllerTest` (reflection-injected fields, `MockADService`/`MockUserRoleService` via `ServiceLocator`, no real `Stage.show()` — same standing rule as `UserNoteFallaPersistenceTest`), `LoginViewFxmlTest`.

### Audit logging and SUPERADMIN role — built, then descoped for the first release

An audit-logging feature (`LOGIN_AUDIT`/`ACTION_AUDIT` append-only tables, a third `SUPERADMIN`
role tier, a read-only "Auditoría" section) was built and tested on 2026-07-24, but was **removed
entirely** before the first release — explicit user decision, to ship a smaller v1 faster. All of
it — `IAuditService`/`SqliteAuditService`/`CachingAuditService`/`MockAuditService`,
`ActionAuditEntry`/`LoginAuditEntry`, `AuditController`/`AuditView.fxml`, the 5 audit write points
(`LoginController`, `NoteDetailController`, `PrestamoDetailController`,
`DatabaseSectionController`), the `LOGIN_AUDIT`/`ACTION_AUDIT` schema (both SQLite and SQL Server),
and `IUserRoleService.ROLE_SUPERADMIN` — was deleted, not just hidden behind a flag. `USER_ROLE`
(`ADMIN`/`USER` only now) and the rest of [Login screen and role-based admin mode](#login-screen-and-role-based-admin-mode)
above are unaffected. This may be reintroduced in a future release; if so, re-derive the design
rather than assuming the deleted code is still relevant.

### Audit trail — schema-first pass (2026-08-06)

Reintroduced per direct user request, ahead of an external DBA schema review deadline — a
from-scratch redesign, per this file's own instruction above, not a revival of the deleted
2026-07-24 `LOGIN_AUDIT`/`ACTION_AUDIT` design. Built in two passes the same day: a schema-first
pass (tables + login rate-limiting only), then a same-day follow-up that wired every remaining
write point and renamed the tables to an `AUDIT_*` prefix (see below) — both passes are described
together here rather than as separate sections.

- **Four tables, not one generic table** — `AUDIT_LOGIN`, `AUDIT_STOCK`, `AUDIT_ITEM_STATUS`,
  `AUDIT_ADMIN_ACTION`. Three of the four have one clear, single FK target worth keeping typed
  (`AUDIT_STOCK` → `BRAND_TYPE_LINK`/`MODEL`/`SEDE` with real `INTEGER`/`INT` `old_stock`/
  `new_stock` columns; `AUDIT_ITEM_STATUS` → `NOTE_ITEM`, with a `status_kind` discriminator
  covering both GLPI and Préstamo/Provider return-status transitions in one table, since both are
  the exact same "item's status moved from A to B" shape — `NOTE_ITEM_GLPI_TRACKING`/
  `NOTE_ITEM_RETURN_TRACKING` only ever keep the *latest* status, never prior transitions, so this
  is genuinely new information, not a duplicate). `AUDIT_ADMIN_ACTION` is the deliberate
  exception — a generic `action`/`target_type`/`target_id`/`old_value`/`new_value` shape (`target_id`
  is `TEXT`/`NVARCHAR`, not a typed FK, since it sometimes holds a settings key like
  `"glpi_api_key"` rather than a numeric row id), meant to cover catalog CRUD, note
  approval/rejection, S/N validation edits, and config changes — a genuinely heterogeneous set of
  admin actions with no single shared FK target. (Admin-editable "profile override" fields were
  considered too, but confirmed removed from `ProfileController` in an earlier session — there's
  nothing left there to audit.) This is the same
  `object_id`-as-text shape most real-world audit logs use for exactly this reason (Django's
  `LogEntry`, Rails' PaperTrail) — flagged explicitly here since it's a deliberate exception to
  this schema's usual typed-FK-over-generic-text preference, not an inconsistency.
- **Secrets are never written to `old_value`/`new_value`, full stop.** SMTP password, GLPI API
  key, AD token, and DB credentials must never appear in `AUDIT_ADMIN_ACTION`, even encrypted —
  that would just create a second place for secrets to leak from. Only "this config was changed"
  gets recorded for those fields, never the value itself. This is enforced by whoever calls
  `IAuditService.recordAdminAction()` once `EDIT_SMTP_CONFIG`/`EDIT_GLPI_CONFIG`/`EDIT_AD_CONFIG`
  get wired in — not something the schema itself can enforce — so it's called out here to not be
  missed later.
- **A real, accepted gap**: `APP_USER`/`ROLE_PERMISSION` changes (role, Sede, and permission
  grants) are made via direct SQL, not through the app (see
  [Role-based permissions (RBAC)](#role-based-permissions-rbac-a-superadmin-tier-and-sede-scoped-admin-actions-2026-07-30)) —
  an app-level audit table structurally cannot see those writes. Auditing that would need a DB
  trigger, not an application-level insert; not built here.
- **`AUDIT_STOCK.reason` is `NOT NULL`** — both Base de Datos stock dialogs ("Stock" and "Editar
  modelo") now require a reason before saving *whenever the stock value actually changes* (a
  no-op save that leaves stock unchanged needs no reason and writes no audit row at all). A new
  "MOTIVO DEL CAMBIO" field, capped at 500 chars matching the column bound
  (`DatabaseSectionController.stockReasonFormatter()`), blocks the save with an inline error via
  the same `triggerFieldError()` pattern already used for every other validation in that dialog.
- **`IAuditService.recordStockChange()` takes `brandId`/`typeId`, not `brandTypeId` directly** —
  every real caller already has those two on hand (same as `IEquipmentService.setModelStock()`
  itself), so `SqliteAuditService` resolves the `BRAND_TYPE_LINK` row internally
  (`findBrandTypeLinkId()`) rather than pushing that lookup onto every call site.
- **All four write methods are now wired to real call sites, not just designed.**
  `recordStockChange()` — the two Base de Datos stock dialogs above. `recordItemStatusChange()` —
  `NoteDetailController.handleSync()`/`handleReject()` (GLPI), `handleProviderReceived()`/
  `handleProviderNotReceived()` (Provider return), and `PrestamoDetailController.handleReturn()`/
  `handleLost()` (Préstamo return) — six call sites total, each capturing the item's status
  *before* it's overwritten as `old_status`.
  **A second, separate write path was missed in the first pass, caught by direct user report the
  same day**: a *countable* (quantity-based) item's return/loss goes through a completely
  different `IHistoryService` method, `allocateCountableReturn()` (supports partial batches —
  e.g. 5 loaned cables, 3 returned now, 1 lost later, 1 still pending — see
  [Per-item return status](#per-item-return-status-returnstatus)), not `updateItemReturnStatus()`.
  Fixed in 4 more call sites: `PrestamoDetailController.handleCountableReturn()`/
  `handleCountableLost()`, `NoteDetailController.handleProviderCountableReceived()`/
  `handleProviderCountableNotReceived()`. Since a partial allocation has no single clean
  before/after status the way a whole-item transition does, `old_status` is always
  `ReturnStatus.PENDING.toDbString()` (the only state a pending quantity can be allocated from).
  **`AUDIT_ITEM_STATUS.quantity`** (`INTEGER`/`INT NOT NULL DEFAULT 1`, added same day, direct
  user follow-up): how many units this row concerns — `1` for every whole-item transition (GLPI
  sync/reject, whole-asset Préstamo/Provider return — a single serialized asset, or a GLPI sync,
  is always exactly one unit; never `NULL`, since the fact is genuinely always "exactly one," not
  "not applicable"), or the real allocated quantity for the 4 partial-batch call sites above.
  Deliberately not left as embedded text inside `reason` (an earlier draft did exactly that —
  `"Cantidad: 3"` — before this column existed) specifically so a plain `SUM(quantity)` works
  uniformly across every row in the table, whole-item or partial, without text-parsing.
  **`reason` semantics, made consistent by the same fix**: `NULL` whenever no admin-typed
  justification applies (a plain sync, a normal whole-item or partial return) — populated only
  for the actions that actually require one (GLPI reject, Préstamo/Provider "lost"/"not received",
  whole-item or partial alike). `old_status`/`new_status` stay real `ReturnStatus`/`GlpiStatus`
  values on every row, whole-item or partial. `recordAdminAction()` — Type/Brand/Model add/rename/
  remove in `DatabaseSectionController` (9 call sites; Provider/Sede have no in-app CRUD at all
  anymore, so nothing to wire there), `SettingsController.handleSave()`'s four config groups (AF
  format/SMTP/GLPI/AD — old values captured before mutation, secrets never logged, only whether
  they changed), `SettingsController`'s S/N validation edit dialog, and
  `NoteDetailController`/`PrestamoDetailController`'s `handleApprove()`/`handleRejectNote()` (4
  call sites). `IAuditService` is local-only for now (writes go straight to the local SQLite
  database, same as `APP_SETTINGS`) — no remote-first `CachingAuditService` wrapper yet; revisit
  once a real usage pattern (an admin reviewing audit history across a shared remote database)
  actually needs one.
- **Login attempt rate-limiting.**
  `LoginController.handleLogin()` now checks `IAuditService.countRecentFailedLoginAttempts(username,
  15)` (a 15-minute rolling window) *before* calling AD at all; at 5 or more recent failures for
  that username, the attempt is rejected immediately ("Demasiados intentos fallidos...") with
  **no AD call and no new `AUDIT_LOGIN` row** — that's what actually bounds the table's growth
  (the whole point of adding this), not just a UI-level annoyance. Every real, non-blocked outcome
  (wrong credentials, denied by AD group, profile not found, not registered, success) writes one
  `AUDIT_LOGIN` row. **One deliberate exception**: an AD-unreachable exception is not recorded and
  does not count toward the rate limit — that's a connectivity failure, not evidence of the
  technician guessing credentials, and penalizing them for it would be unfair.
- **Tests**: `DatabaseServiceMigrationTest.createAuditTablesCreatesAllFourAuditTables()` (table
  existence only — no live SQL Server instance exists anywhere in this project's test
  infrastructure to validate `RemoteDatabaseService`'s mirror against, same limitation already
  documented repeatedly elsewhere in this file). New `MockAuditService` (test-only, mirrors
  `MockADService`/`MockUserRoleService`'s precedent of being a genuinely functional stand-in, not
  an empty stub — its `countRecentFailedLoginAttempts()` does real time-window filtering, not just
  a recorded-call count) wired into `LoginControllerTest`, plus
  `tooManyFailedAttemptsBlocksFurtherLoginsWithoutHittingAdOrLoggingMore()` (seeds 5 failed
  attempts, then asserts a 6th attempt — even with *correct* credentials — is blocked, that AD is
  never reached, and that no new audit row is inserted). Full suite: 491 tests passing.
- **`RemoteDatabaseService.ensureSchema()`/`01-schema.sql` ordering note**: `AUDIT_ITEM_STATUS`
  (references `NOTE_ITEM`) had to be created later in the script than the other three audit
  tables, right after `NOTE_ITEM_STOCK_EXCEPTION` — SQL Server validates FK targets at `CREATE
  TABLE` time (unlike SQLite, which doesn't), so it can't be declared before `NOTE_ITEM` exists.
  `DatabaseService.java`'s SQLite version has no such constraint and keeps all four together in
  one `createAuditTables()` method, called after `createHistoryTables()` (which already creates
  `NOTE_ITEM`) in `initialize()`.
- **Renamed to an `AUDIT_*` prefix same day** — `LOGIN_AUDIT`/`STOCK_AUDIT`/`ITEM_STATUS_AUDIT`/
  `ADMIN_ACTION_AUDIT` → `AUDIT_LOGIN`/`AUDIT_STOCK`/`AUDIT_ITEM_STATUS`/`AUDIT_ADMIN_ACTION`,
  explicit user request so they group together in a DB browser next to each other and follow the
  same `NOTE_*`-style prefix convention already used elsewhere in this schema. Safe as a plain
  rename with no migration path — these tables were created and renamed within the same,
  never-shipped session, so nothing had written to the old names yet.
- **Not built here, and not currently planned**: `APP_USER`/`ROLE_PERMISSION` changes still can't
  be audited (see above — direct-SQL-only by design). No dedicated tests were added for the new
  `AUDIT_ADMIN_ACTION`/`AUDIT_ITEM_STATUS`/`AUDIT_STOCK` call sites themselves — same
  "`Stage.show()`-driven dialogs aren't left in this suite's permanent run" and "`initialize()`
  reaches `ServiceLocator` directly" gaps already accepted elsewhere in this file for
  `DatabaseSectionController`'s other dialog-driven behavior; verified via the full suite staying
  green and direct code review instead.
- **A real crash caught by the user, same day**: `AUDIT_ITEM_STATUS.quantity` (added alongside the
  design discussion above) was only added to the `CREATE TABLE` block at first — missing the
  `addColumnIfMissing()`/`columnExists()`-guarded migration path this file's own "Schema migration
  pattern" note (under [SQLite tables](#sqlite-tables)) explicitly warns about. `CREATE TABLE IF
  NOT EXISTS` silently no-ops on a database that already created the table earlier the same
  session (before `quantity` existed), so `PrestamoDetailController.handleLost()` crashed with
  `SQLITE_ERROR: table AUDIT_ITEM_STATUS has no column named quantity` the moment a real return
  was rejected. Fixed in all three places: `DatabaseService.migrateSchema()` gained
  `addColumnIfMissing(stmt, "AUDIT_ITEM_STATUS", "quantity", "INTEGER NOT NULL DEFAULT 1")`,
  `RemoteDatabaseService.ensureSchema()` gained the matching `columnExists()`-guarded `ALTER
  TABLE`, and `01-schema.sql` gained the matching `information_schema.columns`-guarded block.
  `DEFAULT 1` is load-bearing, not cosmetic — SQLite rejects `ADD COLUMN ... NOT NULL` on a table
  with existing rows unless a default is supplied. Test:
  `DatabaseServiceMigrationTest.migrateSchemaAddsQuantityColumnToAPreExistingAuditItemStatusTable()`
  reproduces the old (pre-`quantity`) table shape directly, runs the real migration, and does a
  real `INSERT` omitting `quantity` to confirm the column doesn't just exist but actually accepts
  a normal insert via its default. Full suite: 492 tests passing (491 + 1 new).

### Remote SQL Server (write-through cache)
`RemoteDatabaseService` manages the SQL Server connection (`com.microsoft.sqlserver:mssql-jdbc`). When `db_host` is set in `APP_SETTINGS`, `ServiceLocator.initialize()` calls `RemoteDatabaseService.configure(...)`, runs `ensureSchema()` (T-SQL DDL), then wraps both remote and local `SqliteEquipmentService`/`SqliteHistoryService` instances in `CachingEquipmentService`/`CachingHistoryService`. Reads try remote first, fall back to local SQLite on error. Writes go to remote first (fail loudly), then local SQLite best-effort. If remote is unreachable at startup, the app runs fully local. `APP_SETTINGS` and `SMTP` config are always local SQLite regardless of remote config.

**Replaced PostgreSQL entirely on 2026-07-17** — explicit user decision after PostgreSQL proved hard to get installed/approved in their organization, whereas SQL Server Express is realistic there. Not a dual-backend choice; PostgreSQL support was removed, not kept alongside. Connection string: `jdbc:sqlserver://<host>:<port>;databaseName=<dbName>;encrypt=true;trustServerCertificate=true` — `trustServerCertificate=true` is unconditional, since a SQL Server Express instance essentially never has a real TLS certificate and recent `mssql-jdbc` versions default to requiring encryption (omitting this reliably breaks the connection on a fresh Express install). Default port changed `5432` → `1433` everywhere (`AppConfig.RemoteDatabaseConfig`, `ServiceLocator`, `DatabaseSectionController`, `CatalogMigrationTool`, `app-config.json`/`.example`). **SQL Server Express commonly installs as a named instance (`SQLEXPRESS`) with a dynamic port** discovered via SQL Server Browser (UDP 1434) — this app's `host:port` connection model has no named-instance support, so `database/sqlserver/README.md` walks through assigning a static port to the instance via SQL Server Configuration Manager as a required one-time setup step, not optional.

**Upserts rewritten as plain check-then-insert/update, not `MERGE`** — SQL Server has no `ON CONFLICT`/`RETURNING`. Only 3 spots in the whole codebase actually needed this: `SqliteEquipmentService.addBrand()`/`addBrandForType()` (now a `SELECT`-then-`INSERT`, same shape `ensureBrandTypeLink()` already used) and `CatalogMigrationTool`'s 5 `migrate*()` methods (now `SELECT` to check existence, `UPDATE` if found or `INSERT ... RETURN_GENERATED_KEYS` if not). `SqliteHistoryService` needed **no changes at all** — it had zero dialect-specific SQL to begin with. Decomposing away from `MERGE` was a deliberate choice: no live SQL Server exists anywhere in this project's test infrastructure to validate trickier syntax against (same reasoning already documented below for why PostgreSQL itself was never live-tested), so keeping every statement as plain, portable `SELECT`/`INSERT`/`UPDATE` means the exact same SQL also runs correctly against SQLite — both in production (`SqliteEquipmentService`/`SqliteHistoryService` are reused unchanged for local and remote, see below) and in tests (`CatalogMigrationToolTest` stands a second SQLite database in for "remote").

### SQLite schema mirrors the remote SQL Server schema
`DatabaseService` creates `data/noteapp.db` with the same table names and column types as the SQL Server schema `RemoteDatabaseService.ensureSchema()` creates. Column types differ only where the dialect requires it (`TEXT` in SQLite ↔ `NVARCHAR`/`NVARCHAR(MAX)` in T-SQL, `INTEGER PRIMARY KEY AUTOINCREMENT` ↔ `INT IDENTITY(1,1) PRIMARY KEY`) — booleans stay `INTEGER`/`INT` (0/1) in both, no `BIT` type introduced, to keep one convention across both databases.

### `CatalogMigrationTool` — local-to-remote catalog migration

Added 2026-07-14, in response to a direct user need: they wanted to build up the real equipment catalog locally through the app's own UI before a remote database was available, then move that data over once one was set up, rather than re-entering everything by hand or hand-writing SQL. `utils/CatalogMigrationTool.java` (same one-off-CLI-utility convention as `AdminPasswordHashGenerator`/`AppKeyEncryptionGenerator` — `mvn exec:java -Dexec.mainClass=...`, interactive prompts, no pom.xml changes needed since `exec:java` resolves via Maven's default plugin-prefix mechanism) copies `TYPE`/`BRAND`/`BRAND_TYPE_LINK`/`MODEL`/`SN_VALIDATION`/`PROVIDER` from local SQLite into a configured remote database.

- **Reuses `RemoteDatabaseService.ensureSchema()` directly** rather than duplicating DDL — guarantees the created schema never drifts from what the app itself would create, and means an empty, freshly-provisioned remote database is the only prerequisite (no need to run `01-schema.sql` by hand first, though that still works too).
- **Id remapping, not a straight copy**: the remote database's `IDENTITY` columns assign their own ids on insert, independent of whatever ids SQLite happened to use locally — copying SQLite's original ids verbatim risks colliding with rows the remote database already has (e.g. a pre-existing "Generic" brand) and would leave its identity counters stale for future inserts. Each `migrate*()` method (`TYPE`→`BRAND`→`BRAND_TYPE_LINK`→`MODEL`→`SN_VALIDATION`→`PROVIDER`, in that FK dependency order) inserts, captures the *new* generated id, and builds an old-id→new-id `Map` that later methods use to translate foreign keys (e.g. `MODEL.brand_type_id` must reference the *migrated* `BRAND_TYPE_LINK` id, not the original SQLite one). Rows whose parent wasn't migrated (a data-integrity gap, not something the tool itself would cause) are skipped with a warning rather than aborting the whole run.
- **Safe to re-run**: every table is migrated with a plain check-then-insert-or-update (`SELECT` to find an existing row by its unique key, `UPDATE` the other columns if found, otherwise `INSERT ... RETURN_GENERATED_KEYS`) — no `MERGE`/`ON CONFLICT`/`RETURNING`, see "Remote SQL Server" above for why this shape was chosen over SQL Server's native `MERGE`. `TYPE`'s upsert additionally refreshes `is_asset`/`requires_serial` when the row already exists, so re-running after changing those flags locally propagates the change. `SN_VALIDATION` has no unique constraint of its own to upsert against (one row per `model_id` is only enforced app-side, in `SqliteEquipmentService.upsertSnValidation`), so it's handled as delete-then-insert per model instead.
- **Catalog only — History is out of scope on purpose**: `NOTE_REPORT`/`NOTE_ITEM`/etc. store type/brand/model as denormalized text, not catalog foreign keys (same "snapshot, don't reference" pattern noted elsewhere in this file), so they don't depend on catalog ids and migrating them wasn't the actual need here.
- **`database/sqlserver/README.md` updated to reference this** — it previously stated flatly that "there's no bulk-import path" for S/N validation rules; that's no longer true now that this tool exists, and the doc was corrected in the same change.
- **Tests**: `CatalogMigrationToolTest.java` — no real SQL Server instance exists anywhere in this test suite's infrastructure (confirmed via `RemoteDatabaseServiceTest`, which only ever exercises unreachable-host failure paths). Since the tool's SQL is plain `SELECT`/`INSERT`/`UPDATE` with no SQL Server-specific syntax, the tests use a *second*, separate SQLite database as the "remote" stand-in, exercising the actual `migrate*()` methods and their real SQL, not a mock. The migrate methods were changed from `private static` to package-private specifically for this (same precedent as `ServiceLocator.provisionDefaultSecrets`). Covers: full-catalog migration with id remapping verified via referential-integrity queries on the target, idempotent re-run (same source, no duplicate rows, identical id map), and orphaned-row skipping. One gotcha hit while writing these: SQLite's JDBC driver holds the `.db` file open until `Connection.close()` — omitting that in `@AfterEach` made `@TempDir` cleanup fail on Windows with a file-in-use error, even though the tests' own assertions had already passed.

---

## Note types and profiles

| Profile | UI `profileType` string | Note type | Template | Motivo | Notes |
|---------|--------------------------|-----------|----------|--------|-------|
| Entrega | `ENTREGA` | NOTE_ENTREGA_DEVOLUCION | `entrega.html` | Mandatory | |
| Devolución | `DEVOLUCIÓN` | NOTE_ENTREGA_DEVOLUCION | `devolucion.html` | Mandatory | |
| Fin de Contrato | `ENTREGA PERMANENTE` | NOTE_ENTREGA_DEVOLUCION | `entrega - fin de contrato.html` | Mandatory | |
| Préstamo | `PRÉSTAMO` | NOTE_ENTREGA_DEVOLUCION | `prestamo.html` | — | Captures an expected return date instead of Motivo (stored in the same `motivo` column) |
| Entrega - Proveedor | `Entrega - Proveedor` | NOTE_PROVEEDOR | `proveedor.html` | Mandatory | Has CUIT field; provider name selected from the `PROVIDER` catalog, not free text — see [Provider catalog](#provider-catalog-equipmentprovider) |

"Recambio" is **not a note type** — it was removed entirely (2026-07-03) from `HistoryController.PROFILE_TYPE_OPTIONS` and every `toDisplayName()` switch (`NoteGenerationService`, `HistoryController`, `NoteDetailController`). It never had a live generation flow (no UI button ever produced it) and was only ever a filter-dropdown/display-name leftover. Do not reintroduce it.

### `NOTE_REPORT.profile_type` is stored raw — display it via `toDisplayName()`, never directly
`profile_type` is persisted exactly as `UserNoteController.getSelectedNoteType()` returns it — the literal, ALL-CAPS `ToggleButton` text ("ENTREGA", "DEVOLUCIÓN", "PRÉSTAMO", "ENTREGA PERMANENTE") or the hardcoded "Entrega - Proveedor" literal for the other top-level toggle, which (like Provider) doesn't branch into further sub-Motivos the way Usuario's toggle does. Anything that displays this value to a user must run it through a `toDisplayName(String)` mapper first (private, `NoteGenerationService.java` for note rendering; duplicated in `HistoryController.java` and `NoteDetailController.java` for History's table/CSV/XLSX/popup-title — same no-shared-abstraction convention as the dialog-builder methods). `HistoryController`'s table column, CSV export, and XLSX export, plus `NoteDetailController`'s popup title, were fixed to use this on 2026-07-03 — before that they showed the raw stored string.
**Filter bug fixed 2026-07-03:** `HistoryController.PROFILE_TYPE_OPTIONS` (the filter dropdown) uses nice-cased labels ("Entrega", "Devolución", "Fin de Contrato") that get passed into `SqliteHistoryService.getFiltered()`'s SQL `WHERE r.profile_type IN (...)`. Since `profile_type` is stored in two different casings depending on how the row was created (`DatabaseService.seedHistoryData()`'s demo rows use nice-cased literals; the live "Generar Nota" flow stores raw ALL-CAPS `ToggleButton` text), a filter for one label needs to match *both* forms. Fixed via `HistoryController.PROFILE_TYPE_LABEL_TO_RAW` (`Map<String, List<String>>`, mirroring `GLPI_LABEL_TO_CODE`'s pattern but one-to-many) and `expandProfileTypeLabels(Set<String>)`, called from `buildFilter()`. Verified against the real `data/noteapp.db`, not just test fixtures: filtering by "Devolución" now returns all 4 matching rows instead of 2.

**Autor filter added, History stopped auto-refreshing on open (both fixed 2026-07-10):** `HistoryFilter.authorSearch` + a `txtAuthorSearch` field alongside the recipient search, matching `COALESCE(r.technician_name, tp.name, '')` in `SqliteHistoryService.getFiltered()` — repeated inline rather than referencing the `author_name` SELECT alias, since SQL Server rejects that in a WHERE clause even though SQLite tolerates it (this query runs against both, see [Remote SQL Server](#remote-sql-server-write-through-cache)). Separately: `ViewFactory` caching every section for the session (see below) meant `HistoryController.initialize()`'s one-time `loadGlobal()` never re-ran on later visits, so a note generated after the first visit to Historial didn't appear until "Buscar" was clicked manually. `HistoryController.refresh()` (public, re-runs `loadGlobal(buildFilter())` — reuses current filters, doesn't reset them) is now called by `MainController.handleShowHistory()` on every navigation to History, via the newly-exposed `ViewFactory.getHistoryController()`.

**Sede filter added to Historial and Préstamos Historial (first release).** `HistoryFilter.sedes` (`List<String>`, null = all) mirrors the existing item-type/brand/model filter shape — matched via `appendInViaCatalog(sql, params, "r.sede_id", "SEDE", filter.getSedes())` (`SqliteHistoryService.getFiltered()`), so filtering by a Sede name a technician later renamed away from still finds the notes generated under that name, same as the equipment-catalog filters. A new `IHistoryService.getDistinctSedes()` (default `emptyList()`, real implementation in `SqliteHistoryService`, remote-first/local-fallback in `CachingHistoryService`) populates the menu from Sede names actually used in History, not the full `SEDE` catalog — same "distinct values actually seen" convention as `getDistinctItemTypes()`. `HistoryController` (`mnuSede`, `selSedes`, wired in `initEquipmentMenus()`) and `PrestamoHistoryController` (`mnuSede`, `selSedes`, its own `initSedeMenu()` since that controller has no equivalent equipment-menu grouping) both got the same `MenuButton`/`CustomMenuItem` checkbox pattern already used for every other multi-select filter in this app, added to `HistoryView.fxml`/`PrestamoHistoryView.fxml`'s existing filter rows (next to Autor).

**Superseded 2026-07-30, per direct user report and request**: the Sede filter's own available
options no longer come from `getDistinctSedes()` at all — the user pointed out that a technician's
own assigned Sede (see [Role-based permissions (RBAC)](#role-based-permissions-rbac-a-superadmin-tier-and-sede-scoped-admin-actions-2026-07-30))
might have zero historical notes yet (a brand-new site), in which case "distinct values actually
seen" would never surface it as a selectable option at all — a real mismatch between the filter
dropdown and the live `SEDE` catalog. Both controllers now source their Sede menu options from
`ServiceLocator.getInstance().getEquipmentService().getAllSedes()` instead — `HistoryController`'s
new `sedeCatalogNames()` helper, `PrestamoHistoryController`'s `initSedeMenu()` updated in place —
the same active-Sede-catalog source `SettingsController`/`DatabaseSectionController` already read
from, so the filter dropdown can never drift out of sync with what's actually configured.
`IHistoryService.getDistinctSedes()` (interface default, `SqliteHistoryService`,
`CachingHistoryService`) was deleted outright once this left it with zero callers — no dead code,
per this codebase's own convention.

**Sede filter now defaults to the technician's own assigned Sede, not "Todas."** Same user report,
same underlying motivation as the RBAC feature's Sede-scoping: a technician mostly cares about
their own site's history. Both controllers gained a `resetSedeFilterToDefault()` (pre-populates
`selSedes` with `TechnicianSessionService.getInstance().getSede()` if assigned, otherwise leaves it
empty), called once at `initialize()` and again from `handleClearFilters()` — mirroring the exact
"Limpiar filtros restores the default view, not a blank one" precedent this file already documents
for `chkShowRechazado` above, rather than introducing a second, inconsistent notion of "default."
This is a starting point only, not a restriction — the technician can still clear or change the
Sede filter freely, unlike the RBAC feature's actual Sede-scoped permission checks, which are a
hard block on specific actions, not a UI default. A technician with no Sede assigned at all simply
sees the pre-existing "Todas" behavior, unchanged.

**Tests**: `HistoryControllerTest` gained `resetSedeFilterToDefaultAddsTechniciansOwnSedeWhenAssigned`/
`...LeavesEmptyWhenNoSedeAssigned` (reflection-setting `TechnicianSessionService`'s private
`sedeName` field directly, bypassing the full login/`APP_USER` round trip since
`resetSedeFilterToDefault()` only ever reads the public `getSede()` getter) and
`sedeCatalogNamesReturnsActiveSedesFromEquipmentService` (a real `MockEquipmentService` wired via
`ServiceLocator`). `PrestamoHistoryControllerTest` gained the mirrored set, plus
`initSedeMenuOptionsComeFromEquipmentServiceCatalogNotHistory` (asserts the actual `MenuButton`'s
built checkbox items, not just the underlying `List<String>`, to also catch a regression in
`initSedeMenu()`'s own `populateMenu()` wiring). Full suite: 389 tests passing (383 pre-existing +
6 new).

**Sede and Motivo columns added to both history tables (2026-07-30), plus a full-detail export
overhaul.** Direct user request. `SqliteHistoryService.LIST_BASE_SQL` gained a `LEFT JOIN SEDE sd
ON sd.id = r.sede_id` and `COALESCE(sd.name, '') AS sede`, and `mapSummary()` now sets it — Sede
was already filterable in History, but never actually shown as a column on a summary row (only
`getById()`'s single-report query resolved it before this). `motivo` was already selected in
`LIST_BASE_SQL` (added earlier for the Préstamo "Vencido" overdue check), so no query change was
needed there — just wiring it up as a visible column.

- **`HistoryController`** (`tblGlobal`, every profile type mixed together): new `colGSede`/
  `colGMotivo` columns, positioned SEDE right after AUTOR and MOTIVO right after TIPO. Shows the
  raw `motivo` value for every profile type, Préstamo included — direct user instruction ("for
  Préstamo notes on the global History section, put Motivo of course").
- **`PrestamoHistoryController`** (`tblPrestamos`, Préstamo notes only): new `colPSede`, and a new
  `colPFechaTentativa` column reading the same `NoteReport.getMotivo()` field — but labeled "FECHA
  TENTATIVA," not "Motivo," per explicit user direction, since `motivo` is overloaded for Préstamo
  to store the tentative return date, not a real reason (see [Note types and profiles](#note-types-and-profiles)),
  and every row in this table is a Préstamo.
- **Export overhaul, per explicit user direction ("CSV/Excel exports should contain every possible
  note value, NULLs allowed")**: `handleExportCsv()`/`handleExportXlsx()` went from 5 hardcoded
  columns (Fecha/Tipo/Destinatario/Autor/Estado GLPI) to 25 (`HistoryController.EXPORT_HEADERS`):
  every summary-row field (Fecha, Autor, Autor DNI, Sede, Tipo, Motivo, Destinatario, Estado,
  Razón Rechazo, Estado GLPI, and all 8 item-count/status columns) plus 7 profile-specific detail
  fields that only exist on a full report (Causa Falla, Detalle Falla, CUIT, Responsable,
  Responsable DNI, Área/Evento, Observaciones) — blank for whichever profile type they don't apply
  to, by design (accepted NULLs). A new `exportRowValues(NoteReport)` shared helper does a
  `getById(summary.getId())` fetch per exported row to reach those 7 detail-only fields — a real,
  explicitly-accepted performance trade-off: a large export is now noticeably slower (one extra
  query per row) in exchange for actually containing every field. `approvalStatusDisplay(String)`
  maps the raw `approval_status` value (`PENDING`/`APPROVED`/`RECHAZADO`) to a clean Spanish label
  for the "Estado" export column (falls back to the raw value for anything unrecognized).
  `PrestamoHistoryController` has no export feature at all (never did), so it needed no equivalent
  change.
- **Tests**: `SqliteHistoryServiceTest` gained `getAllIncludesSedeOnSummaryRows`/
  `getAllSedeIsBlankWhenNoSedeAssigned`. `HistoryControllerTest` gained
  `approvalStatusDisplayMapsKnownValues`/`...FallsBackToRawValueForUnknownStatus`.
  `exportRowValues()`/the CSV/XLSX writers themselves have no dedicated test — same
  "`ServiceLocator`-dependent, impractical to exercise in isolation" gap already accepted elsewhere
  in this file for `HistoryController`'s other `initialize()`-adjacent methods; verified instead via
  `mvn compile`/`mvn test` (full suite green) and direct code review. Full suite: 393 tests passing
  (389 pre-existing + 4 new).

**Login screen: Enter key submits, from either field (2026-07-30).** Direct user request.
`pfPassword` already had `onAction="#handleLogin"` (pressing Enter while the password field was
focused already worked); `txtUsername` was missing the same wiring — a one-line `LoginView.fxml`
fix, `onAction="#handleLogin"` added to `txtUsername` too, so Enter submits regardless of which of
the two fields currently has focus. No controller change needed — `handleLogin()` already reads
both fields' current values, same as clicking "Iniciar sesión" does.

All template placeholders (`{{TOKEN}}`) are named in English (`NAME`, `DNI`, `REASON`, `DATE`, `OBSERVATIONS`, `ASSET_TAG`, `COMPANY_NAME`, `CUIT`, `RESPONSIBLE_NAME`, `RESPONSIBLE_DNI`, `EXPECTED_RETURN_DATE`, `TECHNICIAN_NAME`, `TECHNICIAN_DNI`, `SEDE`, plus the item tables' `{{#ASSET_ITEMS}}` loop's `TYPE`/`BRAND`/`MODEL`/`SERIAL`/`ASSET_TAG`/`DETAILS`, `{{#COUNTABLE_ITEMS}}` loop's `TYPE`/`BRAND`/`MODEL`/`QUANTITY`/`DETAILS`, their `HAS_ASSET_ITEMS`/`HAS_COUNTABLE_ITEMS` presence-flag wrappers (see [Item list redesign](#item-list-redesign-stacked-blocks--tables)), and Devolución's `{{#FAILURE}}` loop's `FAILURE_CAUSE`/`FAILURE_DETAILS`). `DNI` and `CUIT` are kept as-is (Argentine document types with no natural English equivalent). Visible prose inside templates stays in Spanish per the UI language convention — only the token identifiers are English. `NoteGenerationService` still populates an `EMAIL` token internally (from AD/user-note data, for potential future SMTP use), but **no template renders it** — email is deliberately not printed on any note.

The provider note's "Recibe por parte del proveedor" name/DNI (`ProviderNoteController.txtProviderResponsibleName`/`txtProviderResponsibleDni`, optional — filled only when `chkEnableResponsible` is checked) is fully persisted in `NOTE_PROVEEDOR.responsible_name`/`responsible_dni`, so it renders correctly both on freshly-generated notes and when re-rendering a stored report from History.

### Two-signature layout
Every template renders a `.signature-row` with two `.signature-section` boxes (48% width each, from `devolucion.html`/`entrega.html`/`entrega - fin de contrato.html`/`prestamo.html`; `proveedor.html` had this row already). Left box = the recipient (`NAME`/`DNI`) or, for provider notes, "Recibe por parte del proveedor" (`RESPONSIBLE_NAME`/`RESPONSIBLE_DNI`, shown but blank if `ProviderNoteController`'s checkbox is unchecked — this represents the provider's own person picking up the equipment, not an internal role). Right box is always the technician (`TECHNICIAN_NAME`/`TECHNICIAN_DNI`).

**Signature box redesign, 2026-07-16** — the old layout put the role as a bold `<p><b>Recibe:</b></p>` heading floating above a plain `.sign-data` text block with no actual line to sign on (`.sign-line-graphic` was defined in every template's CSS but never referenced in any body markup — dead CSS). User found the heading-above-box look unconvincing; three redesigns were mocked up as an HTML artifact (rendered at the real print font/size) and the "ficha con etiqueta" direction was picked: `.signature-box` is now a bordered card (`border: 1.4pt solid black; border-radius: 6px`) with the role as a small bold tab (`.signature-tab`, absolutely positioned, white background, overlapping the box's top border like a form legend) instead of a heading above it, a real signature line (`.sign-line-graphic`, now actually used), then `Aclaración`/`DNI` below the line — "Aclaración" replacing the old "Nombre" label (matches the picked mockup) and the old "Firma: __________" text line dropped entirely, since the bordered line now stands in for it visually. Applied identically to all 5 templates. The old `.signature-section .outro-text { margin-bottom: 5px; }` rule (styling the now-removed heading paragraph) was removed as dead CSS alongside it. **Follow-up, same day**: `.sign-line-graphic`'s top margin bumped `2px` → `10px` across all 5 templates — the line sat too close to the box's top border for comfortable signing room, direct user feedback after the redesign landed. **Second follow-up, same day**: still too tight — bumped again, `10px` → `26px`, for genuinely comfortable room to sign above the printed name/DNI.

**Footer "Fecha" removed, 2026-07-16** — every template used to repeat `{{DATE}}` a second time in a footer div below the signature row (`<strong>Fecha:</strong> {{DATE}}`, or `.footer-date-section` in `devolucion.html`), redundant with the same `{{DATE}}` already shown in the header's `.header-right`. Removed from all 5 templates' body markup; `devolucion.html`'s now-unused `.footer-date-section` CSS rule was deleted alongside it as dead code.

**Template title now states its own field name, 2026-07-16** — `.template-name` (e.g. "Entrega", "Devolución") was easy to misread as just a subtitle under "Oficina de Soporte IT" rather than a labeled field. All 5 templates now render `Tipo de nota: {{TEMPLATE_NAME}}` instead of the bare token — text-only change, `NoteGenerationService`'s `TEMPLATE_NAME` token values (`toDisplayName(profileType)`, or the literal `"Entrega - Proveedor"` for provider notes) are unchanged.

**`.signature-tab` role labels renamed, 2026-07-16** — "Recibe"/"Técnico responsable" replaced across all 5 templates, from 3 proposed naming schemes (action-based, descriptive-tied-to-office, minimal) mocked up and compared before picking. Now: "Quien recibe" (was "Recibe" — Entrega, Fin de Contrato, Préstamo's Entrega row) and "Soporte IT" (was "Técnico responsable", and `prestamo.html`'s Devolución row's "Técnico que recibe") everywhere the technician signs. "Quien devuelve" (Devolución, Préstamo's Devolución row) and "Recibe por parte del proveedor" (`proveedor.html`) were left unchanged — the two other candidate schemes would have renamed these too, but the picked scheme only touches the two labels the user explicitly asked about.

### Préstamo: Entrega + Devolución signature sections
Added 2026-07-16, per direct user request — `prestamo.html` used to only capture a signature for the delivery, with no way to record the equipment actually coming back. Now renders **two** full `.signature-row` blocks, each preceded by a `.signature-group-label` (a small stripe + uppercase label — "Entrega" / "Devolución" — the only template with more than one signature row, so the only one that needs this extra grouping heading):

- **Entrega** row: unchanged from before (Recibe = `NAME`/`DNI`, Técnico responsable = `TECHNICIAN_NAME`/`TECHNICIAN_DNI`).
- **Devolución** row (new): both signature boxes are blank fill-by-hand fields — explicit user decisions:
  - **"Técnico que recibe" starts blank**, not pre-filled with the delivering technician's name — any technician in the area could end up processing the actual return, not necessarily the one who handled delivery.
  - **"Fecha real de devolución" is a blank field** for the actual return date — the app only ever knows the *tentative* one (`UserNoteController.getFechaTentativa()` → `{{EXPECTED_RETURN_DATE}}`), never the real one. Initially also reprinted `{{EXPECTED_RETURN_DATE}}` as "Fecha tentativa" directly in this signature block for reference, but removed same day — it's already shown once elsewhere on the note (see below), and repeating it here was redundant. **Moved outside the signature box, same day**: was initially a third line inside "Quien devuelve"'s `.sign-data` (alongside Aclaración/DNI), making that box visually asymmetric against "Soporte IT"'s two-line box. Moved to a plain text line below the Devolución `.signature-row` — the same styling the old (now-removed) global footer "Fecha" line used — so both Devolución signature boxes render identically.
- **Blank fields are label-only, no underscore line — fixed same day, in every template, not just here.** Every manually-completed field (`Aclaración`/`DNI` in both Devolución boxes, plus "Fecha real de devolución") originally printed a literal run of underscores after the label (`Aclaración: __________________________`), matching the pre-table-redesign convention. User found the underscores didn't look good, especially now sitting directly under the signature boxes' own `.sign-line-graphic` line — removed entirely, leaving just the bare label (`<strong>Aclaración:</strong>`) with nothing after it; the technician writes directly on/after the label line instead of a dedicated ruled blank.
- **"Fecha tentativa de devolución" moved below the item list, above Observaciones — same day.** Was originally in the intro paragraph, above the items section; moved below `.items-section` into its own `.outro-text` block, with a `.section-separator` between it and Observaciones (matching the separator already used between Observaciones and the closing legal paragraph). Direct user request, no stated reason beyond preferred placement.
- **Page-fit**: adding a second signature row makes the note taller; per explicit user direction this was not pre-emptively compensated for (no trimming of body copy/spacing elsewhere) — if it overflows the single A4 page in practice, that's a follow-up, not something guessed at now.

### Motivo moved below the item list; Devolución gained the closing legal paragraph

Added 2026-07-16, same day as Préstamo's Fecha Tentativa relocation above — user asked for the same treatment applied to Motivo, everywhere it appears. `entrega.html`, `entrega - fin de contrato.html`, `devolucion.html`, and `proveedor.html` each had `<strong>Motivo:</strong> {{REASON}}` in the intro paragraph, above the item list; all four now render it in its own `.outro-text` block below `.items-section`, followed by a `.section-separator`, mirroring exactly where Préstamo's Fecha Tentativa now sits. `proveedor.html` has no Observaciones section, so its Motivo block sits directly above the closing legal paragraph instead of above an Observaciones block like the other three.

- **`devolucion.html`'s Falla details moved along with Motivo**, since `{{#FAILURE}}` conceptually belongs right after it (only shown when Motivo = Falla) — both now live in the same relocated `.outro-text` block, immediately after the items section.
- **`devolucion.html` was also missing the closing legal paragraph** ("No siendo para más se da por finalizada la nota...") that the other 4 templates already had — noticed while relocating Motivo. Added using the same wording currently used by `entrega.html`/`prestamo.html`/`proveedor.html` (a shorter version without the "Se confeccionan dos copias..." sentence still present in `entrega - fin de contrato.html` — an existing inconsistency across templates, predating this change and left as-is since fixing it wasn't asked for here).
- **Tests**: `NoteGenerationServiceTest.generatesProviderNoteWithAllCapturedFieldsAndTechnician`'s ordering assertion flipped (Motivo now expected *below* the item table, not above).

### Devolución's leftover flex-spacer layout removed, "Detalles de Falla" no longer prints a dangling dash

Both found and fixed 2026-07-16, from a direct user question ("is the extra spacing around the table section correct?").

- **`devolucion.html` had its own unique `.content-wrapper` layout** — `display: flex; flex-direction: column;` with `.spacer-top` (`flex: 0.5`) before the intro paragraph and two `.spacer-bottom` instances (`flex: 1.5` each) after the Motivo/Falla block and at the very end — a vertical-centering mechanism from when item blocks were tall and variable-height, distributing whatever vertical room was left over between them. None of the other 4 templates have (or ever had) this — they just stack content top-to-bottom and let `.main-frame`'s `justify-content: space-between` push the signature row to the bottom. With items now rendered as compact tables, there's much more leftover room in that flex column, and these same spacers absorbed nearly all of it — visibly oversized gaps around `.items-section` compared to every other template. Removed entirely (`.spacer-top`/`.spacer-bottom` CSS rules and all 3 body instances); `.content-wrapper` simplified to `flex: 1; overflow: hidden;`, identical to the other 4 templates now that nothing needs the flex-column/spacer setup.
- **"Detalles de Falla" no longer shows a dangling "—" when only the cause is given.** `devolucion.html` rendered `{{FAILURE_CAUSE}} — {{FAILURE_DETAILS}}` unconditionally, so a Falla note without details (the details field is optional — see [Falla detail capture](#falla-failure-detail-capture--devolución-only)) printed as "No enciende — " with nothing after the dash. `NoteGenerationService.failureLoop()` now builds a single combined `FAILURE_TEXT` token instead of separate `FAILURE_CAUSE`/`FAILURE_DETAILS` ones — only appending `" — " + failureDetails` when details are actually non-blank, otherwise just the cause alone. `devolucion.html` (the only template with a `{{#FAILURE}}` block) updated to render `{{FAILURE_TEXT}}` in place of the old two-token expression.
- **Tests**: `NoteGenerationServiceTest.generatesDevolucionNoteWithFailureCauseOnlyShowsNoDanglingSeparator` added (cause given, details `null`) asserting the rendered text never contains `"No enciende —"`.

### Technician identity — session-only, sourced from Windows/AD
The technician's own identity (name, username, email, DNI) is **not persisted to disk**. `WindowsIdentityService` (JNA `Secur32Util.getUserNameEx(NameUserPrincipal)`) reads the Windows session's UPN (e.g. `user@domain.com`) — returns `null` on a non-domain machine (local/workgroup account), which is an expected, not-an-error case. `TechnicianSessionService` (singleton, in-memory only) derives the AD username by stripping everything after `@` (`TechnicianSessionService.deriveUsernameFromEmail`) and calls the existing `IADService.search(null, null, derivedUsername)` — no new AD interface method needed, since `ADUser` already carries `fullName`/`username`/`email`/`dni` in one record.

- **Refreshed at app startup** (`MainController.initialize()` → `refreshTechnicianSessionAsync()`, background thread) and **on demand** via the "Actualizar Perfil desde AD" button in `ProfileController` — never cached across restarts. `TechnicianSessionService.getLastError()` distinguishes three failure cases, all shown in Profile's status label and all mentioning that an admin can complete the fields manually: (1) Windows session UPN unresolvable (non-domain machine) — "No se pudo obtener el usuario de dominio de Windows"; (2) AD service reachable but no matching record — "Usuario no encontrado en Active Directory"; (3) AD `search()` threw (service unreachable) — "No se pudo conectar con Active Directory". The `search()` call is wrapped in try/catch specifically to distinguish case 3 from case 2.
- **If the startup refresh fails**, `MainController` shows a one-time **orange warning popup** (`showWarningNotice(title, message)`, `#f59e0b` accent + "⚠" icon — same warning color as `UserNoteController`'s inline AD-search feedback), centered on `contentArea` (`centerOnContent(Stage)`, mirroring `SettingsController`/`DatabaseSectionController`'s existing pattern) rather than the physical screen. `showNotice`/`showWarningNotice` both delegate to `showDialogNotice(title, message, accentColor, icon)`, which parameterizes `buildDialogRoot`'s border color (default black `#1a1a1a` for neutral notices like `AdminSession` expiry, orange for warnings).
- **Clicking "Actualizar Perfil desde AD" is self-evidently responsive even on a repeat identical failure** — `ProfileController.handleRefreshFromAd()` disables the button and changes its text to "Actualizando..." for the duration of the call (independent of whether the resulting message differs from before), and `flashStatus(message, color)` plays a quick opacity pulse (`FadeTransition`, 0.2→1.0 over 200ms) every time the status label is set — including on success, which previously showed no feedback at all on a successful refresh.
- **Success message depends on the update's source**, not just whether the session is resolved — `TechnicianSessionService.UpdateSource` (`AD` / `MANUAL`) is set alongside `name`/`username`/`email`/`dni` on every successful `refreshFromWindowsSession()` or `applyManualOverride()` call, and read back via `getLastUpdateSource()`. `ProfileController.populateFieldsFromSession()` (the single shared listener callback for *both* outcomes) branches on it: "Perfil actualizado desde Active Directory" for `AD`, "Perfil guardado correctamente" for `MANUAL`. Without this, an admin's manual save incorrectly displayed the AD-refresh message, since both paths funnel through the same `notifyListeners()` → `populateFieldsFromSession()` callback.
- **Disabled (not just non-editable) in the UI** — `ProfileController`'s 4 fields (`txtProfileName`/`txtProfileUsername`/`txtProfileDni`/`txtProfileEmail`) are `disable=true` by default (`ProfileView.fxml`), giving the standard native grayed-out look, not just a blocked-typing `editable=false` state. They become enabled, and the "Guardar" button appears, **only when `AdminSession.getInstance().isActive()`** — `ProfileController.updateEditability()` subscribes to `AdminSession`'s activate/deactivate listeners exactly like `MainController`/`SettingsController` already do. Admin edits go through `TechnicianSessionService.applyManualOverride(...)` — session-only, lost on next AD refresh or app restart. Fields are laid out one-below-the-other, capped at 360px wide (`ProfileView.fxml`), with a `Separator` between the description text and the field list.
- **Sidebar welcome message** (`MainController.lblWelcome`/`lblUsername`) subscribes to `TechnicianSessionService.addOnChangeListener(...)` and updates immediately on any change (startup resolution, manual refresh, or admin override) — no restart needed. This replaced the old one-shot, Profile-independent AD lookup that used to live directly in `MainController`. Format: `lblWelcome` always shows "Hola, {nombre}!" (or plain "Hola!" if unresolved); `lblUsername` (directly below) shows "Usuario: {username}" when resolved, or "Perfil no configurado" when not — never left blank, so an unconfigured profile is visible at a glance without opening Mi Perfil. `{nombre}` is `TechnicianSessionService.getDisplayName()` (see next bullet) — **fixed 2026-07-10**: it used to be `extractFirstName()` grabbing the *first* word of the full name, which greeted technicians by their surname once `AdApiService.normalizeName()` started keeping AD's actual "Apellido Nombre" order; `getDisplayName()` now uses the *last* word as the fallback instead (or the technician's own preference, see below).
- **"Nombre para mostrar" — a technician-editable greeting-name preference** (added 2026-07-10, `TechnicianSessionService.getDisplayName()`/`setDisplayNamePreference()`): unlike the four identity fields above, this is a personal cosmetic preference independent of AD data, so it's the one thing in `TechnicianSessionService` that **is** persisted — in `APP_SETTINGS`, keyed by `"display_name_pref:" + username` (plaintext, not a secret; local SQLite only, never mirrored to the remote database, same as `ad_api_token`/`db_host`). This keeps it correctly scoped per technician even when several people share one installed app on one machine, and lets it survive both app restarts and AD refreshes — `refreshFromWindowsSession()`/`applyManualOverride()` both reload it from disk after updating identity rather than clearing it. `ProfileController`'s "NOMBRE PARA MOSTRAR" field (own card in `ProfileView.fxml`, below the AD identity card) is **not** admin-gated — always editable, with its own always-visible "Guardar" button (`handleSaveDisplayName()`) — and is pre-filled with `getDisplayName()`'s current effective value, so a technician who never touches it just sees the suggested default (last word of the full name). Saving a blank value clears the preference back to that suggested default rather than storing an empty override.
- **Reset button + 20-char limit** (added 2026-07-14): a square `btnResetDisplayName` ("↺", `.button-square-reset` in `styles.css`) sits to the right of the field in `ProfileView.fxml`. `ProfileController.handleResetDisplayName()` just clears the field and calls `handleSaveDisplayName()` — the existing blank-save-reverts-to-suggested-default behavior above, one click instead of manually emptying the field first. `txtDisplayName`'s `TextFormatter` now also rejects any input beyond 20 characters (`DISPLAY_NAME_MAX_LENGTH`), consistent with it being a short greeting name, not a full legal name.
- **Note generation is blocked** (`NoteGeneratorController.handleGenerateNote()`, checked first, before any other validation) if `TechnicianSessionService.getName()`/`getDni()` are blank — every `NOTE_REPORT` must be traceable to the technician who created it. `showMissingTechnicianProfileError()` shows this via the same orange-accent `showWarningNotice()` dialog pattern (see [Admin dialog pattern](#admin-dialog-pattern)), directing the user to Mi Perfil's "Actualizar Perfil desde AD" button (or an admin to manually complete the fields).
- **`TECHNICIAN_PROFILE` table and `NOTE_REPORT.technician_id` FK removed entirely (2026-07-16)**, per explicit user direction — the table had been kept only so old historical notes (linked via that FK) could still resolve an author name/DNI, but new notes had stopped writing to it since the switch to session-only technician identity. Notes store the technician's name/DNI as plain text directly on `NOTE_REPORT` (`technician_name`/`technician_dni` columns — see [SQLite tables](#sqlite-tables)), the same "snapshot, don't reference" pattern `NOTE_ITEM` already uses for equipment type/brand/model. **Accepted trade-off**: any note created before this removal that only had `technician_id` set (never migrated to `technician_name`/`technician_dni`) now shows a blank author in History — deliberately not backfilled or given a migration, since existing installations' `TECHNICIAN_PROFILE` table/column are simply left in place unused (no `DROP TABLE`/`DROP COLUMN` migration was added; only the `CREATE TABLE`/column declarations were removed, so this only affects brand-new databases).
- **Deferred, not yet built**: AD read-permission gating (disable AD-dependent buttons if the app/user lacks AD read access) — tracked in memory, not implemented.

### Technician Sede — per-note, mandatory (2026-07-22)

A second `TechnicianSessionService` persistence exception, same shape as "Nombre para mostrar" above but a fully independent concept — Sede has no AD-sourced field to derive a default from, unlike the display name's "last word of full name" fallback.

- **Storage**: `getSede()`/`setSedePreference()`, persisted in `APP_SETTINGS` keyed by `"sede_pref:" + username` (plaintext, local SQLite only). Its own listener list (`addOnSedeChangeListener`/`notifySedeListeners()`) keeps a Sede save from re-triggering the AD-identity or display-name status messages — same crosstalk fix already applied to the display-name preference (see "Nombre para mostrar" save/reset was also flashing the AD identity status message" under Known issues/gotchas).
- **UI lives in Configuración, not Mi Perfil** — explicit user choice, even though the value is per-technician like the display name. `SettingsController`'s "SEDE" card (own `txtSede`, own "Guardar" button `handleSaveSede()`, own status label `lblSedeStatus`) is the one field in that entire panel **not** gated by `AdminSession` — every other Settings field follows `setDisable(!adminActive)`, but Sede is always editable by any technician. `TextFormatter` caps input at 255 chars, matching the DB bound below.
  **Superseded 2026-07-30 — self-service Sede removed entirely.** `setSedePreference()`, the
  `sede_pref:<username>` `APP_SETTINGS` key, `addOnSedeChangeListener`/`notifySedeListeners()`,
  and the Settings "SEDE" card described in the next bullet were all deleted, not deprecated —
  Sede is now read from the superadmin-assigned `APP_USER.sede_id`, the same value driving the
  Sede-scoped admin permissions below. See
  [Self-service Sede removed entirely](#self-service-sede-removed-entirely--sede-is-now-superadmin-assigned-for-every-technician)
  under [Role-based permissions (RBAC)](#role-based-permissions-rbac-a-superadmin-tier-and-sede-scoped-admin-actions-2026-07-30).
- **Mandatory to generate a note or register a Préstamo** — `NoteGeneratorController.handleGenerateNote()` and `PrestamoNewLoanController.handleGuardarPrestamo()` both check `getSede()` blank (right after the existing AD-profile-incomplete check) and block with `showMissingSedeError()` (same orange `showWarningNotice()` pattern as `showMissingTechnicianProfileError()`), directing the technician to Configuración.
- **Sidebar display**: a third label (`lblSede`, `MainView.fxml`, styled like `lblUsername`) below the welcome/username block, hidden entirely (`visible/managed=false`) when unset rather than showing an empty line. `MainController.updateSedeLabel()` is called both from `updateWelcomeLabels()` (identity changes) and directly from `TechnicianSessionService.addOnSedeChangeListener(...)` (a pure Sede save, which doesn't fire the identity listener).
- **Snapshotted onto every note, printed on every template** — `NOTE_REPORT.sede` (`TEXT`/`NVARCHAR(255)`, added to both `DatabaseService`/`RemoteDatabaseService`'s `CREATE TABLE`/migration blocks, same "any new column needs both" rule as every other column addition in this file) stores the value at generation time, same "snapshot, don't reference" pattern as `technician_name`/`technician_dni`/`observations`. `NoteGenerationService`'s three `generate*Note()` methods each gained a `sede` parameter (sourced from the live session at generation time, passed by `NoteGeneratorController`); `generateFromStoredReport()` reads `report.getSede()` for reprints. Rendered via a new `{{SEDE}}` token.
  - On all 5 templates (`entrega.html`, `devolucion.html`, `entrega - fin de contrato.html`, `proveedor.html`, `prestamo.html`), `{{SEDE}}` **replaces a previously hardcoded "Campus"** in the intro sentence ("En la sede {{SEDE}} de la Universidad Siglo 21, en el día de la fecha...") — every note printed before this change claimed "Campus" regardless of where it was actually generated; explicit user decision to make this sentence accurate per note rather than add Sede as a separate, redundant line. A note generated before this feature existed (or any grandfathered gap) renders `orEmpty(report.getSede())`, i.e. a blank in that sentence — accepted, same as every other reprint-of-an-old-note gap already documented in this file, since Sede is enforced mandatory going forward.
- **Tests**: `TechnicianSessionServiceTest` (save/load/blank-clears/no-op-when-unresolved/listener-isolation, mirroring the existing display-name test set), `DatabaseServiceMigrationTest` (column lands via migration on a pre-existing database), `SqliteHistoryServiceTest.getByIdPersistsAndReturnsSede()`, `NoteGenerationServiceTest` (token renders at generation time and on reprint).

### Sede became catalog-backed — SEDE table, ComboBox, deprecated-flag rename (2026-07-24)

Direct follow-up to the section above, per explicit user request: Sede was free text (typed into `SettingsController.txtSede`, stored as plain text on both the technician's preference and `NOTE_REPORT.sede`) with no admin curation and no rename-without-corrupting-history mechanism. Now catalog-backed, following the exact same `deprecated`-flag pattern already established for `TYPE`/`BRAND`/`MODEL`/`PROVIDER` (see "Catalog-FK redesign" above) — chosen specifically so a rename shows correctly on old notes' previews, which a plain text-swap could never do.

- **`SEDE` table** (`id`, `name UNIQUE`, `deprecated`) — flat, no scoping/junction table, identical shape to `PROVIDER`. `IEquipmentService` gained `getAllSedes()`/`addSede()`/`renameSede()`/`removeSede()`, implemented in `SqliteEquipmentService` (reusing the same generic `findActiveIdExcluding`/`findDeprecatedId`/`setDeprecated` helpers already parameterized by table name — near-zero new logic), `CachingEquipmentService` (remote-first, local-fallback), and `MockEquipmentService` (in-memory, empty by default, same as `PROVIDER` — no `mock-equipment.json` seeding since Sede is org-specific).
- **`NOTE_REPORT.sede_id`** (nullable FK into `SEDE`) added alongside the existing `sede` TEXT column — the old column is deliberately **left in place, unused**, same "leave the old column, migrate forward" precedent as `TECHNICIAN_PROFILE`/`NOTE_REPORT.technician_id` (no `DROP COLUMN`). `SqliteHistoryService.insertReport()` now writes `sede_id`; `getById()` resolves the display name via `LEFT JOIN SEDE`, same "SQL gains JOINs, Java row-mapping code doesn't change" pattern as `provider_name`/`provider_id` — `NoteReport.getSede()`'s callers (templates, `NoteGenerationService`) needed zero changes.
- **Historical backfill, and why newly-created rows are active, not deprecated — a deliberate deviation from the NOTE_ITEM/NOTE_PROVEEDOR precedent.** `DatabaseService.migrateSedeIdSchema()` / `RemoteDatabaseService`'s mirror (both reuse `resolveOrCreateCatalogRow()`, naturally idempotent — only ever selects `NOTE_REPORT` rows still missing `sede_id`) resolve every existing row's free-text `sede` into a matching or newly-created `SEDE` row. Unlike the catalog-FK backfill for Type/Brand/Model/Provider (which creates unmatched historical values as `deprecated=1`, since a real admin-curated active catalog already existed independently), a backfilled Sede row here is left **active** (`deprecated=0`) — `SEDE` is a brand-new table with no pre-existing catalog to fall back on, so marking every backfilled row deprecated would leave the Settings combobox with zero selectable options, blocking every technician from generating a note (Sede is mandatory) until an admin manually reactivated each one. Confirmed against the real `data/noteapp.db` this was built against: 0 existing `NOTE_REPORT` rows at the time, so the backfill was a no-op there, but the logic still needed to be correct for installations with real history.
- **Settings: `ComboBox<Sede>` instead of a free-text field** — `cmbSede` replaces `txtSede`, non-editable strict selection from the admin-curated list, same pattern as `ProviderNoteController.cmbProviderSearch`. Populated at `initialize()` from `equipmentService.getAllSedes()`; no refresh-on-tab-show wiring was added (a deliberate scope call, not an oversight — this exact controller's own S/N Validation table already has the same "load once at init, no refresh-on-show" precedent, and Sede catalog changes are expected to be rare/admin-driven).
  **Superseded 2026-07-30**: `cmbSede` itself was removed from `SettingsView.fxml` — self-service
  Sede selection no longer exists at all, see the RBAC section referenced above.
- **`TechnicianSessionService` now persists a Sede *id*, not a name** — `setSedePreference(Integer sedeId, String sedeName)` (was `setSedePreference(String value)`) stores only the id in `APP_SETTINGS` (`sede_pref:<username>`, unchanged key, just an id-as-string now instead of free text) and caches the resolved display name in memory. `getSedeId()` is new (feeds `NoteReport.sedeId`); `getSede()` keeps its existing signature/meaning (display name) for every existing caller (sidebar label, templates via `NoteGeneratorController`/`PrestamoNewLoanController`).
  - **Pre-2026-07-24 installs' stored preference (free text, not an id) is treated as unset, not crashed on** — `loadSedePreference()` tries `Integer.parseInt` on the stored value; on `NumberFormatException`, clears both fields rather than propagating. Every technician who'd already set a free-text Sede preference needs to re-select it once via the new combobox after upgrading — an accepted, explicit one-time reset, not a silent data-loss bug (there's no safe way to fuzzy-match old free text onto a specific new catalog row).
  - **Resolves the display name including deprecated rows, deliberately** — `resolveSedeName(id)` has no `deprecated = 0` filter, unlike every other "current" catalog lookup in this app. If a technician's saved Sede gets renamed by an admin (deprecating the old row), their preference should still show *something* rather than silently going blank — which, since Sede is mandatory, would otherwise block them from generating notes until they noticed and re-picked one. This is a deliberate, narrow exception to the "always filter deprecated" convention, not an inconsistency.
- **`NoteGeneratorController`/`PrestamoNewLoanController`** — the mandatory-Sede check now also requires `getSedeId() != null` (previously just checked the name string wasn't blank); both `report.setSede(...)` call sites gained a matching `report.setSedeId(technician.getSedeId())`.
- **Base de Datos layout — a real, asked-for structural change, confirmed with the user before implementing** (a 5th catalog list made the existing single-row-of-4 layout "too small," per direct user feedback). Chosen from 3 presented options (two-row split / toggle-between-groups / horizontally-scrollable single row): a `ToggleButton` pair — "CATÁLOGO DE EQUIPOS" (Tipos/Marcas/Modelos, unchanged, still cascading) / "OTROS CATÁLOGOS" (Proveedores + the new Sedes, flat/independent) — same `.type-button-left`/`.type-button-right` CSS and `ToggleGroup`-listener wiring pattern already used by `NoteGeneratorView`'s Usuario/Proveedor toggle. Only one row is `visible`/`managed` at a time. "SEDES" is a plain 4th/5th list, identical shape to "PROVEEDORES" (own `listSedes`, `handleAddSede`/`handleEditSede`/`handleRemoveSede`, `openAddSedeDialog()` — near-verbatim copies of the Provider equivalents).
- **Tests**: `SqliteEquipmentServiceTest` gained `addSedeRejectsCaseInsensitiveDuplicate`/`renameSedeRejectsCaseInsensitiveDuplicate`/`renamingSedeDeprecatesOldRowAndReactivatesOrInsertsNew`/`removeSedeDeprecatesRatherThanDeletes` (mirrors the Provider test set). `TechnicianSessionServiceTest`'s Sede tests rewritten for the id-based API; `sedePreferencePersistsAcrossRefresh()` is the one test needing a real `SEDE` row to exercise `resolveSedeName()`'s DB round-trip — inserted and hard-deleted within that single test (never left lingering in the shared local catalog), and it's also the only test in this file (or, it turns out, the whole suite) that calls the real `DatabaseService.getInstance().initialize()` — nothing else does, so this test can't assume `SEDE` already exists in `data/noteapp.db` the way this file's other real-DB-touching tests could already assume `APP_SETTINGS` existed. `SqliteHistoryServiceTest.getByIdPersistsAndReturnsSede()` updated to create a real `SEDE` row and set `sedeId` instead of just `sede` text (its own duplicated schema copy also gained `SEDE` + `NOTE_REPORT.sede_id`, same "every new column/table needs test schema copies updated too" precedent). New `DatabaseSectionViewFxmlTest.java` (no prior test loaded `DatabaseSectionView.fxml`) catches wiring typos in the new toggle buttons/rows/Sede list; `SettingsViewFxmlTest` needed `ServiceLocator.setEquipmentService(new MockEquipmentService())` added to its setup, since `SettingsController.initialize()` now eagerly calls `equipmentService.getAllSedes()` (previously nothing in that controller's `initialize()` dereferenced `equipmentService` synchronously, so the test never needed one configured).

### Falla (failure) detail capture — Devolución only
When Motivo = "Falla" is selected in the Devolución flow, `UserNoteController` opens `FailureDetailView.fxml` (`FailureDetailController`) modally. Only the cause combobox (from `AppConfig.fallaOptions`, configurable in `app-config.json`) is mandatory — the free-text details field is optional. The result is stored as `NOTE_ENTREGA_DEVOLUCION.failure_cause`/`failure_details` and rendered in `devolucion.html`'s `{{#FAILURE}}` conditional block as "Detalles de Falla". This only applies to Devolución — other note types never deliver broken equipment, so they have no Falla flow.

**Reworked 2026-07-13** — three separate fixes/generalizations, all from direct user questions while reviewing this flow:

- **Popup now styled and positioned like every other dialog in the app.** Previously `UserNoteController.openFailureDetailDialog()` used a plain default `Stage` (OS window chrome, no transparency, no positioning — user pointed out it didn't match the rest of the app). `FailureDetailView.fxml`'s root is now the same transparent `StackPane` + dual dark/white border + dropshadow wrapper `ItemDialogView.fxml`/`ADUserSelectionView.fxml` use (kept the existing `.ad-header-gradient` teal header unchanged, just overrode its corner radius inline to `10 10 0 0` to match the wrapper instead of the class's own `7 7 0 0`). The Stage is now `StageStyle.TRANSPARENT` with the stylesheet attached to the `Scene` (previously via the FXML root's `stylesheets` attribute, now removed since the root changed), and uses the same `setOnShown`-based positioning as `showUserSelectionDialog()` — anchored to `cmbMotivo`, offset to its right, clamped to screen bounds.
- **The hardcoded `"Falla".equals(motivo)` check is gone.** Same bug class as the earlier `"Notebook".equals(type.getName())` fix (see [Per-type mandatory-S/N flag](#per-type-mandatory-s-n-flag-equipmenttyperequiresserial)): renaming the "Falla" option in `app-config.json`'s `motivoOptions.devolucion` list would have silently stopped the popup from triggering. `AppConfig` gained `failureTriggerMotivo` (default `"Falla"`, in both `app-config.json` and `.json.example`), checked via `UserNoteController.isFailureTriggerMotivo(String)` everywhere the literal used to appear (the popup-trigger listener, `validateAndShowErrors()`). Renaming which Motivo value requires the Falla popup is now a config edit, not a code change.
- **Falla cause/details — and now every type's Motivo selection — persist across note-type switches within a session.** User explicitly asked for the bigger of two options here (not just Falla-scoped memory): `UserNoteController.lastMotivoByType` (`Map<ToggleButton, String>`) remembers each note type's last-selected Motivo independently, restored via `cmbMotivo.setValue(...)` in `loadMotivoOptions()` instead of always calling `clearSelection()`. Before this, `initialize()`'s toggle listener force-cleared `failureCause`/`failureDetails` on *every* switch away from Devolución, and `loadMotivoOptions()` force-cleared the Motivo selection on *every* type switch (including switching back to the same type) — nothing persisted. Scoped to only run failure-detail side effects (the auto-open, `clearFailureDetails()`) when `btnTypeDevolucion.isSelected()`, so restoring a *different* type's remembered Motivo (e.g. switching to Entrega) can never touch Devolución's stored failure state. `handleClearUserFields()` (the "Limpiar" button) wipes `lastMotivoByType` entirely, for a genuine full reset.
- **Popup-reopens-on-type-switch bug — three fix attempts, same day.** First report: confirming Falla with only the cause filled (details are optional) and then switching types away and back reopened the popup, but leaving both fields filled didn't. **Attempt 1**: replaced the `failureCause == null` popup-open guard with an explicit `UserNoteController.failureConfirmed` boolean (`true` only via `setFailureDetails()`, `false` only via `clearFailureDetails()`). **Didn't fix it — user reported the popup then reopened in *both* cases**, a regression from the partially-working state before attempt 1. **Attempt 2**: added `UserNoteController.restoringMotivo`, `true` only for the duration of `loadMotivoOptions()`'s `cmbMotivo.setValue(remembered)` call, so the listener could recognize a restore directly instead of inferring it from `failureConfirmed`. Automated tests (pre-seeding state via reflection, no real `Scene`/`Stage`) passed — **but the user reported it was *still* broken in the real app.** That mismatch was the actual clue: **attempt 3 (the real fix)** — the automated tests never attached a `Scene`, so `cmbMotivo` never got a real `Skin`; on a fully-realized `ComboBox`, `cmbMotivo.setItems(...)` (called *before* attempt 2's `restoringMotivo = true`, at the top of `loadMotivoOptions()`) apparently fires the value listener with an intermediate `null` on its own — Skin-mediated, invisible in an unskinned test. That unguarded intermediate firing overwrote `lastMotivoByType`'s "Falla" entry with `null` *before* `loadMotivoOptions()` ever read it back, so the restore silently failed, Motivo went blank, and the user re-picking "Falla" themselves was — correctly, from the code's now-confused perspective — treated as a brand-new pick. Fixed by widening `restoringMotivo` to guard `loadMotivoOptions()`'s *entire* body, `setItems()` included, and moving the `lastMotivoByType.get(forType)` read to *before* touching `cmbMotivo` at all: `if (restoringMotivo) return;` is now the listener's very first line, ahead of even the `lastMotivoByType.put(...)` bookkeeping. Validated against a real, shown, `applyCss()`/`layout()`-forced `Stage` (see Tests below) before being confirmed fixed. `onFailureDialogCancelled()`'s edit-vs-new-pick distinction still uses `failureConfirmed` (unrelated code path, not implicated in this bug).
- **The Falla cause now shows directly in the Motivo combobox itself** (`"Falla - No enciende"`) instead of requiring a hover — user's follow-up idea after finding the "⚙ Editar" tooltip-only indicator (below) not visible enough. `cmbMotivo.setCellFactory(...)` and `cmbMotivo.setButtonCell(...)` both use a shared inner `MotivoCell` class (`UserNoteController.MotivoCell extends ListCell<String>`) whose `updateItem()` calls `motivoDisplayText(motivo)` — returns `"{motivo} - {failureCause}"` only when Devolución's Motivo is the failure-trigger value and `failureConfirmed`, otherwise just `motivo` unchanged. The underlying `cmbMotivo.getValue()`/`getMotivo()` stays the pure `"Falla"` string always — only the *rendered* cell text is augmented, so the actual Motivo value used by note generation/templates is never affected. **Gotcha worth remembering**: `ComboBox` does not re-invoke its button cell just because external state (`failureCause`) changed — only a genuine value change does that — so `MotivoCell` exposes a package-visible `refresh()` (`updateItem()` is `protected` on `Cell`, not callable from outside a subclass) that `refreshFailureIndicators()` calls explicitly after every `setFailureDetails()`/`clearFailureDetails()`/type-switch.
- **"⚙ Editar" indicator kept, but simplified** — still the only way to reopen the popup for an already-confirmed Falla (clicking an already-selected ComboBox value doesn't fire a change event, so the combobox itself can't trigger reopening). Its dynamic per-instance `Tooltip` (built fresh from `failureCause`/`failureDetails` on every visibility change) was removed since it's now redundant with the combobox showing the cause directly — replaced with one static `Tooltip("Editar detalles de Falla")` set once in `initialize()`.
- **Cancelling an edit vs. cancelling a new pick, made to behave differently** — a real gap surfaced by adding the "Editar" reopen: `onFailureDialogCancelled()` used to unconditionally clear the Motivo selection on Cancel, which was correct for abandoning a brand-new, unconfirmed "Falla" pick but would have wiped an already-saved Falla's cause/details just because the technician reopened it to look and clicked Cancel. Now only clears Motivo when `!failureConfirmed` at cancel time — cancelling out of an edit leaves the existing data untouched.
- **Tests**: `UserNoteViewFxmlTest.java` — loads `UserNoteView.fxml` through a real `FXMLLoader` on the FX thread and asserts it succeeds; no prior test in this suite actually loaded this FXML (existing tests construct controllers directly and inject `@FXML` fields via reflection), so a typo in `fx:id`/`onAction`/`onMouseClicked` would only have surfaced at runtime. `UserNoteFallaPersistenceTest.java` (two cases: cause-only and cause+details) reproduces the reported scenario against the real FXML/controller/listeners, pre-seeding `failureConfirmed`/`failureCause`/`failureDetails` via reflection *before* ever setting `cmbMotivo` to the trigger value so `openFailureDetailDialog()` never fires and no window is created for the "must NOT reopen" assertions — still exercises the real listener/`loadMotivoOptions()`-restore code path (including `restoringMotivo`), just without window churn.
  - **This unskinned-`ComboBox` blind spot is exactly what let attempt 2 pass its own tests while still being broken in the real app** — worth remembering for any future `ComboBox`/`Cell` state bug in this codebase: a bare `FXMLLoader.load()` with no attached `Scene` cannot catch Skin-mediated behavior, full stop.
  - **Real `Stage` tests are flaky in this suite — confirmed three times, same day, modal or not.** Two different tests were written and removed after confirming what they set out to prove: (1) an earlier, heavier version of the persistence test drove a real `APPLICATION_MODAL` `FailureDetailView` popup end to end (button clicks included) — passed alone, hung the *next* test class's `FXMLLoader.load()` for 5+ seconds in the full suite. (2) A `freshFallaSelectionStillOpensPopup` test opened one real modal `Stage` as its only window interaction — same hang. (3) A `fallaPersistsAcrossTypeSwitchWithARealSkinnedComboBox` test — the one that validated attempt 3 above — opened a real **non-modal** `Stage` (just to force a `Skin`) — same hang again, proving modality was never the actual trigger. All three passed reliably in isolation (including by exact `-Dtest=Class#method` name) before removal, so each still served its purpose as a one-time validation; none are safe to leave in the permanent suite. **Standing rule for this suite**: never leave a test that calls `Stage.show()` (modal or not) in the permanent run — if a real Skin/Window is genuinely needed to validate something, run it once by exact test name, confirm, then delete or comment it out like the two precedents in `UserNoteFallaPersistenceTest.java`.

### Input validation
- **Name fields** (recipient name in `UserNoteController`, technician name in `ProfileController`, provider's responsible-person name in `ProviderNoteController`): a `TextFormatter` blocks non-letter/non-space characters as typed; a submit-time regex (`^\p{L}+( \p{L}+)*$`) additionally rejects leading/trailing/double spaces. The provider's own **company** name field is intentionally excluded (business names legitimately contain numbers/abbreviations).
- **DNI fields** (same three people): `TextFormatter` restricts to digits, max 8 chars; submit-time regex (`^\d{7,8}$`) requires 7 or 8 digits, no dots.
- **Email** (`ProfileController.txtProfileEmail` only — the user-note email is AD-populated, not typed): submit-time regex validated as a well-formed address.
- These three controllers each duplicate their own `NAME_PATTERN`/`DNI_PATTERN` (and `ProfileController` also `EMAIL_PATTERN`) — intentional duplication, no shared validator class, per the no-abstraction rule.
- `ProviderNoteController` gained a `validateAndShowErrors()` method (previously provider notes had no validation at all); `NoteGeneratorController.handleGenerateNote()` now calls it for the provider branch, mirroring the existing user-note validation call.

---

## Préstamos section (internal equipment loans)

Added 2026-07-17, per direct user request. Closes a real gap in the existing Préstamo flow: generating a Préstamo note (see [Note types and profiles](#note-types-and-profiles)) captured a tentative return date but gave no way to *track* the loan afterward — no list of open loans, no per-item return confirmation, no way to flag a lost/stolen item. This reuses the GLPI-sync pattern (`GlpiStatus`, `NoteDetailController.buildGlpiStatusRow()`, `HistoryController`'s row-gradient coloring) almost verbatim for a new, orthogonal dimension: whether a loaned item has come back.

### Two entry points, kept deliberately separate
- **Generar Nota → Nota de Usuario → Préstamo** (unchanged) still prints a physical note — this is the flow the physical-folder process (paper note travels between a "pending" and a "finished" folder) depends on.
- **Préstamos → "Cargar Nuevo Préstamo"** (new) logs a loan straight into history with **no HTML render, no print, no email** — `PrestamoNewLoanController.handleGuardarPrestamo()` builds a `NoteReport`/`NoteReportItem` list directly and calls `IHistoryService.save()`, skipping `NoteGenerationService`/`NotePreviewController` entirely. Both stay as separate, equally-valid ways to create a Préstamo; neither was removed in favor of the other.

### Per-item return status (`ReturnStatus`)
`models/ReturnStatus.java` mirrors `GlpiStatus` exactly in shape: `PENDING` (orange), `RETURNED` (green), `LOST` (red), `N_A` (default, gray — every non-Préstamo note's items). `NOTE_ITEM` gained 3 columns paralleling the existing GLPI ones: `return_status`, `return_rejection_reason`, `return_status_updated_at`. Unlike GLPI (asset-only), **return tracking applies to both asset and countable items** — a Préstamo's countables (e.g. loaned cables) need return confirmation too, per explicit user direction. `SqliteHistoryService.insertItems()` is the single hook point: every item on a Préstamo note (`isPrestamo(profileType)`, case/accent-tolerant like `HistoryController.PROFILE_TYPE_LABEL_TO_RAW`) gets `PENDING` regardless of asset/countable; every other note type's items stay `N_A`. Tracking granularity is **whole-item, not partial-quantity** — a countable line item is marked fully Returned/Lost/Pending as one unit, not "3 of 5 returned."

`updateItemReturnStatus(itemId, ReturnStatus, reason)` (`IHistoryService`/`SqliteHistoryService`/`CachingHistoryService`) is a byte-for-byte copy of `updateItemGlpiStatus()` targeting the new columns.

**Préstamo assets are deliberately excluded from GLPI sync** (`GlpiStatus.N_A`, not `PENDING` — added 2026-07-17, explicit user decision after asking whether loaned assets should be GLPI-pending): GLPI sync in this app is a one-way, manual, no-revert action (`buildGlpiStatusRow()`'s `PENDING`→`SYNCED`/`REJECTED` never reverts), and nothing in the Préstamo return flow (`PrestamoDetailController.handleReturn()`) touches `glpi_status` — only `return_status`. Marking a loaned asset `PENDING` for GLPI would let an admin sync it, after which GLPI would have no way to learn the item came back — a permanently stale "still assigned to the borrower" state, since this app never un-syncs anything. Both entry points set `GlpiStatus.N_A` for Préstamo assets: `PrestamoNewLoanController` unconditionally (it only ever builds Préstamo notes), `NotePreviewController.buildReportWithItems()` conditionally (`"PRÉSTAMO".equals(profileType)`, since that method is shared by every note type — every other type keeps the existing unconditional `PENDING`). Countables were already `N_A` regardless of note type (GLPI sync is asset-only). Tests: `NotePreviewControllerGlpiStatusTest.java` (reflection-only — `buildReportWithItems()` touches no `@FXML` fields, so no `Scene`/toolkit setup is needed at all).

### Área / Evento (optional context field)
The signing recipient is always Name+DNI, same as every other note type — this is *additional* context, not a replacement. `NOTE_ENTREGA_DEVOLUCION.area_evento` (nullable, Préstamo-only, same pattern as `failure_cause`/`failure_details` being Devolución-only). Captured via `UserNoteController.txtAreaEvento` (inside the existing `vboxFechaTentativa` block, so it's shown/hidden together with the tentative-date picker) and `PrestamoNewLoanController`'s own copy of the same field. Rendered on `prestamo.html` conditionally, right after "Fecha tentativa de devolución":
```html
{{#HAS_AREA_EVENT}}<p><strong>Área / Evento:</strong> {{AREA_EVENT}}</p>{{/HAS_AREA_EVENT}}
```
**Gotcha that cost one failed test run**: `TemplateEngine.expandLoops()` calls `replaceTokens()` using *only* the loop's own per-entry token map, not falling back to the top-level `tokens` map — so a raw top-level token referenced directly inside a `{{#LOOP}}` block gets silently replaced with `""` during the loop's own expansion pass, before the final top-level `replaceTokens()` call ever runs on it. The first implementation attempt put `AREA_EVENT` in the top-level `tokens` map with `HAS_AREA_EVENT` as a bare `presenceFlag(boolean)` (empty-map) wrapper — this rendered as a permanently blank line. Fixed by folding `AREA_EVENT` into the presence-flag loop's own single-entry map instead (`areaEventLoop(String)`, returning `List.of(Map.of("AREA_EVENT", areaEvento))` or `List.of()`), exactly mirroring how `FAILURE`'s `FAILURE_TEXT` already worked. Any future optional-field-inside-a-conditional-block should follow this same shape, not the bare-presence-flag-plus-top-level-token shape.

### Reused dialog interfaces: `ItemDialogHost`, `AdSearchHost`
`ItemDialogController` (the shared Add/Edit Equipo popup) and `ADUserSelectionController` (the shared multi-result AD search popup) were both hard-typed to call back into one specific controller (`NoteGeneratorController`, `UserNoteController` respectively) via `setParentController(ConcreteType)`. Reusing either popup for `PrestamoNewLoanController` needed that coupling loosened. **Deliberate, scoped exception to the no-shared-abstraction convention**: rather than duplicate either popup wholesale (~700 lines for `ItemDialogController` — combobox "most used" pinning, "Genérico / Otro" fallback, requires-serial logic; ~150 for the AD search flow), two minimal callback interfaces were extracted — `controllers/ItemDialogHost.java` (`addAsset`/`addCountable`) and `controllers/AdSearchHost.java` (`fillUserData`/`onAdSelectionDialogClosed`/`highlightFields`/`triggerFeedback`). `ItemDialogController`/`ADUserSelectionController`'s `parentController` field and `setParentController()` param retyped to the interface; `NoteGeneratorController implements ItemDialogHost` and `UserNoteController implements AdSearchHost` (both already had every required method — just needed the `implements` and, for `UserNoteController.highlightFields()`, widening from package-private to `public`). `PrestamoNewLoanController` implements both, but its own AD-search/item-table *logic* is a full duplicate of `UserNoteController`'s/`NoteGeneratorController`'s — only the popup's callback *type* is shared, not the behavior behind it. If a third screen ever needs either popup, extend these same interfaces rather than reverting to a concrete-class coupling.

### Préstamos Historial (`PrestamoHistoryController`)
A Préstamo-scoped sibling of `HistoryController`, with no GLPI-related column/filter at all (return status is a fully separate concern). Listed via the *existing* `IHistoryService.getFiltered(HistoryFilter)` with `profileTypes` fixed to `List.of("PRÉSTAMO", "PRESTAMO", "Préstamo")` — no new listing query needed. Row coloring duplicates `HistoryController.computeRowStyle()`/`appendSlice()`'s exact solid/gradient math, keyed on `returnedItemCount`/`returnPendingItemCount`/`lostItemCount` instead of the GLPI counts (same 3 colors: green/orange/red).

- **"Vencido" (overdue) indicator**: a row with any `PENDING` items whose tentative return date (parsed from `NoteReport.getMotivo()`, `dd/MM/yyyy` — the same column Préstamo already overloads for this, see [Note types and profiles](#note-types-and-profiles)) is before today gets a 2px red border layered on top of whatever fill color already applies, plus a "(Vencido)" suffix in the Estado column text. Computed client-side in the row factory (`PrestamoHistoryController.isOverdue()`) — no new column or query, since `motivo` is now selected in `SqliteHistoryService.LIST_BASE_SQL` (previously absent from summary rows; added specifically for this).
- **Double-click → `PrestamoDetailController`** — a **separate, new** popup, not a branch inside `NoteDetailController`. Regular History's own detail popup stays GLPI-only and untouched, even when reopening a Préstamo note from there; return validation only ever happens from the Préstamos section. Structurally a copy of `NoteDetailController`'s chrome (transparent stage, border frame, `generateFromStoredReport()`-rendered `WebView`), but each item card shows return status instead of GLPI status, with "Validar devolución"/"Marcar como perdido" buttons shown only when `adminMode && AdminSession.getInstance().isActive()` — identical gating to `buildGlpiStatusRow()`'s PENDING case. "Marcar como perdido" prompts a mandatory reason, same as GLPI's reject flow.

### No new header-level table
`profile_type = 'PRÉSTAMO'` plus the existing `motivo` column (tentative date) fully identify a Préstamo note — Préstamos Historial is just `NOTE_REPORT` filtered to Préstamo, joined with the new per-item columns above. No note-level "actual return date" field exists; the most recent `return_status_updated_at` among a note's items is enough, per explicit user direction that note-level return-date tracking wasn't needed.

### Préstamo detail popup: stacked status/action rows, not one HBox
Added 2026-07-17, direct user report: `PrestamoDetailController`'s item card originally packed the "⏳ Pendiente" badge and both action buttons ("Validar devolución", "Marcar como perdido") into one `HBox`, same shape as `NoteDetailController.buildGlpiStatusRow()`. Those two button labels are much longer than GLPI's ("Sincronizar"/"Rechazar"), and at the item panel's ~270px usable width (310px panel minus scroll/card padding) all three elements shrank below readable size. Fixed by changing `buildReturnStatusRow()` to return a `VBox` instead of an `HBox` — badge, then each button, one per row — rather than trying to fit two buttons side-by-side (measured as razor-thin, ~270px available vs. ~270-280px needed, and worse once a vertical scrollbar appears alongside multiple items). No wording changes at the time; every element got its own full row so nothing competed for width regardless of label length. **Superseded same day** — see the next entry.

### GLPI/Préstamo pending rows redesigned: prefix label + renamed status, buttons on their own row
Added 2026-07-17, direct user request, applied to both `NoteDetailController.buildGlpiStatusRow()` and `PrestamoDetailController.buildReturnStatusRow()`'s `PENDING` case for visual consistency between the two detail popups:

- **Row 1**: a plain grey prefix label (`smallLabel()`, matching the same S/N:/A-F:/Cantidad:/Obs: prefix-label convention already used elsewhere in these item cards) + the orange status badge, renamed: `"GLPI: "` + `"⏳ Pendiente sincronización"` (History), `"Préstamo: "` + `"⏳ Pendiente devolución"` (Préstamos) — was just `"⏳ Pendiente"` in both, with no prefix, before this change.
- **Row 2**: the two action buttons, side-by-side (`HBox`) — for GLPI, unchanged text ("Sincronizar"/"Rechazar", short enough to always fit). For Préstamo, this reintroduces the exact side-by-side arrangement the previous entry above just moved away from because of width shrinkage — resolved this time by *shortening the button labels themselves* instead of stacking them: `"Validar devolución"` → `"Devuelto"`, `"Marcar como perdido"` → `"No devuelto"` (explicit user choice, confirmed after being warned the old labels wouldn't fit side-by-side at this width). The underlying actions/handlers (`handleReturn`/`handleLost`) are unchanged — "No devuelto" still opens the same mandatory-reason prompt and sets `ReturnStatus.LOST`, just under a shorter button label.
- `PrestamoDetailController`'s `buildReturnStatusRow()` stays a `VBox` (2 children now instead of 3: the combined prefix+badge row, then the button row) — same reasoning as before, just one row shorter now that the badge and prefix share a row instead of each having their own.

**Follow-up, same day** — 3 more direct user fixes to this same redesign:
- **The prefix label disappeared after clicking a button.** Both `buildGlpiStatusRow()`/`buildReturnStatusRow()` only added the `"GLPI: "`/`"Préstamo: "` prefix inside the `PENDING` switch branch — clicking Sincronizar/Rechazar/Devuelto/No devuelto re-renders the card with the item's new status (`SYNCED`/`REJECTED`/`RETURNED`/`LOST`), which had no prefix at all, so the label visibly vanished the moment an admin acted on it — reported as the label being "deleted." Fixed by restructuring both methods: the `switch` now only sets local `badgeText`/`badgeColor`/`showActions` variables instead of building UI directly, and the prefix-label + badge `HBox` is built once, unconditionally, after the switch — so the prefix renders identically in every state (`SYNCED`, `REJECTED`/`LOST`, `PENDING`, and the no-items `default`/`—` case), not just while pending.
- **Buttons sat too close to the label row above them** — both `VBox`s' spacing bumped `4` → `8`.
- **Prefix labels made bold.** Since the existing shared `smallLabel()` helper (non-bold, `-fx-font-size: 11px; -fx-text-fill: #475569;`) is also reused for other same-card prefixes (S/N:, A-F:, Cantidad:, Obs:) that must stay non-bold, a new `prefixLabel()` method was added instead of modifying `smallLabel()` — identical styling plus `-fx-font-weight: bold;` — duplicated in both `NoteDetailController.java` and `PrestamoDetailController.java` per this codebase's no-shared-abstraction convention (same precedent as `smallLabel()`/`statusBadge()`/the dialog-builder methods already being duplicated between these two controllers).
- No test changes — nothing in the existing suite asserts this row's internal node structure or exact styling; verified via `mvn compile`/`mvn test` (full suite still green) plus manual code review of the rebuilt switch/row logic.

### History's "Estado GLPI" column showed "Mixto" for Préstamo notes instead of "—"
Fixed 2026-07-17, direct user report: a Préstamo note with one asset showed "Mixto" in Historial's "Estado GLPI" column, instead of the expected "—" (nothing to sync). Root cause: `HistoryController.glpiStatusLabel()`/`computeRowStyle()` and `SqliteHistoryService.matchesGlpiStatus()` all used `r.getAssetItemCount()` (raw asset count) as "total" and compared it against `pending + synced + rejected` — an invariant that held for every note type *before* Préstamo introduced `GlpiStatus.N_A` on an actual asset item (previously N_A only ever applied to countables, which `getAssetItemCount()` already excludes, so `pending + synced + rejected` always summed back up to the asset total exactly). A Préstamo note's asset has `total = 1` but `pending = synced = rejected = 0`, so none of the three "all-X" equality checks matched, and both methods fell through to their "mixed" branch — `glpiStatusLabel()` returned "Mixto", and `computeRowStyle()`'s gradient builder produced a linear-gradient with **zero color stops** (each `appendSlice()` call no-ops when its count is 0), a broken/empty CSS value. The same bug independently broke `matchesGlpiStatus()`'s `"N_A"` case (`total == 0`) — a Préstamo note's assets, despite being genuinely N_A, would never match the "Sin GLPI" filter dropdown.

**Fix**: all three now derive "total" from `pending + synced + rejected` (the count of assets actually GLPI-tracked) instead of raw asset count — a note has "nothing to sync" whenever that sum is 0, whether because it has no assets at all (the original, still-correct case) or because every asset on it is N_A (Préstamo). Since a note is always wholly one profile type, an asset is never partially-N_A/partially-tracked within the same note, so this substitution is safe everywhere the old `total` was used for ratio math too. Tests: `HistoryControllerTest.glpiStatusLabelAllNaAssetsReturnsDash()`/`computeRowStyleAllNaAssetsReturnsNeutralColor()`, `SqliteHistoryServiceTest.filtersByGlpiStatusIncludesPrestamoNotesWhoseAssetsAreAllNA()`.

### Sidebar pending-count badges (Historial / Préstamos)
Added 2026-07-17, direct user request. Small orange "(N)" labels overlaid on the "Historial" and "Préstamos" sidebar nav buttons — GLPI-pending note count on Historial, return-pending note count on Préstamos. Both count **notes** (a note qualifies if it has at least one item in that pending state), not raw item count, per the user's own phrasing ("pending notes to sync with GLPI" / "pending Préstamos (notes)").

- **Visible to every technician, not admin-gated** — explicit user decision, reasoned from precedent: individual pending/orange rows are already visible to anyone who opens Historial or Préstamos without admin mode; only the actual Sincronizar/Rechazar/Validar-devolución/Marcar-como-perdido *actions* are admin-gated. Gating the summary badge itself would be inconsistent with that existing split.
- **Hidden entirely when count is 0** — same "a present badge always means something needs attention" reasoning as `lblAdminIndicator`'s own visible/managed toggling, rather than a permanent orange "(0)" that would read oddly.
- **`PendingCountsService`** (new singleton, `services/PendingCountsService.java`) — a pub/sub mirroring `AdminSession`'s `addOnActivateListener` shape but for a single "something changed" event (`addOnChangeListener`/`notifyChanged()`), not activate/deactivate/expire. Holds no cached counts itself; `MainController.refreshPendingCounts()` (background thread, `Platform.runLater` back to the UI, same shape as the existing `startAdCheck`/`startDbCheck`/`startGlpiCheck` startup checks) is the only thing that actually queries `IHistoryService.getPendingGlpiSync()` and `getFiltered()` (filtered to Préstamo profile types, then counting rows with `getReturnPendingItemCount() > 0` client-side, same predicate `PrestamoHistoryController` already uses for its own "Pendiente" filter option).
- **Event-driven refresh, not polling** — explicit user choice over a periodic background poll (like the sidebar's AD/GLPI/DB status dots use). `notifyChanged()` is called from the 4 places that can actually move either count: `NotePreviewController.handleGenerate()` and `PrestamoNewLoanController.handleGuardarPrestamo()` (right after `IHistoryService.save(...)` — either flow can create a Préstamo or a GLPI-pending asset), and `NoteDetailController.handleSync()`/`handleReject()` plus `PrestamoDetailController.handleReturn()`/`handleLost()` (right after `updateItemGlpiStatus()`/`updateItemReturnStatus()`). Trade-off accepted along with the choice: a change made by *another* technician on a shared remote database won't update this session's badge until this session itself does one of those 4 things — the periodic-poll alternative would catch that but wasn't worth the extra recurring query for a "nice to have" summary count.
- **UI structure**: each nav button is wrapped in a `StackPane` (`MainView.fxml`) with the badge `Label` (`fx:id="lblHistoryBadge"`/`"lblPrestamosBadge"`, `.nav-badge` CSS class, `mouseTransparent="true"`) as a second child, `StackPane.alignment="CENTER_RIGHT"`. Chosen over binding the button's own `graphic` to a width-filling `HBox` (the more "native" way to add composite content to a `Labeled`) specifically to avoid needing to bind the graphic's width to the button's own `widthProperty()` in code — the overlay approach gets true right-alignment for free from the `StackPane` itself, no width-binding needed.
- **Tests**: `PendingCountsServiceTest.java` covers the listener mechanics (add/notify/remove, multiple independent listeners) — `MainController` itself isn't unit-tested (same reasoning as `HistoryController`/`PrestamoHistoryController` never getting an FXML-load test: `initialize()` reaches `ServiceLocator`, which isn't configured in a bare unit test, and `MainController`'s `initialize()` additionally does far more — deferred window-chrome setup, the startup connectivity overlay — that isn't practical to exercise in isolation).

### "Cargar Nuevo Préstamo" polish: even toggle split, "(sin nota)" label, field reorder, Reimprimir Nota button, and Observaciones Generales persistence

Added 2026-07-17, direct user request, several independent fixes bundled in one pass:

- **Section-toggle divider now splits exactly 50/50**, both here and on Generar Nota's "NOTA PARA USUARIO"/"NOTA PARA PROVEEDOR" toggle. Both `ToggleButton`s in each pair already used `HBox.hgrow="ALWAYS"` + `maxWidth="Infinity"`, but with no explicit `prefWidth`/`minWidth` set, JavaFX's `HBox` computes each child's baseline width from its own text content *before* distributing leftover space evenly between `ALWAYS`-grow children — so a longer label (e.g. "HISTORIAL DE PRÉSTAMOS" vs. "CARGAR NUEVO PRÉSTAMO") ended up with a wider baseline, and the 50/50 *extra*-space split on top of that unequal baseline never fully corrected it, leaving the divider visibly off-center. Fixed by adding `minWidth="0" prefWidth="0"` to all 4 buttons (`NoteGeneratorView.fxml`, `PrestamosView.fxml`) — with both buttons' baseline forced to 0, 100% of the available width comes from the equal `ALWAYS`-grow distribution, giving an exact 50/50 split regardless of text length.
- **`btnNewLoan`'s label changed to "CARGAR NUEVO PRÉSTAMO (SIN NOTA)"** — explicit clarification that this entry point (unlike Generar Nota → Nota de Usuario → Préstamo) never renders or prints anything; see [Two entry points, kept deliberately separate](#two-entry-points-kept-deliberately-separate).
- **Left-column field order in `PrestamoNewLoanView.fxml` changed**: "Fecha Tentativa de Devolución" moved to the very top of the column (above "Datos de quien recibe"), and "Área / Evento" moved to the bottom (last field, after Username) — purely a reorder, no field gained or lost behavior.
- **"Observaciones Generales" footer added**, identical markup/styling to Generar Nota's own footer block (`txtObservations`, full-width `VBox` below the two-column `HBox`, wired to `PrestamoNewLoanController.handleGuardarPrestamo()`/`handleClearForm()`).
- **`Observaciones Generales` is now actually persisted — for every note type, not just Préstamo.** Investigating how to wire the new Préstamo field surfaced a real, pre-existing gap: `NoteGenerationService.generateFromStoredReport()` (used to reopen/reprint *any* note from History) hardcoded `tokens.put("OBSERVATIONS", "")` unconditionally — so Generar Nota's own "Observaciones Generales" field, despite being printed correctly at creation time, was silently lost the moment a note was saved; reopening it from History always rendered an empty Observaciones section, for every profile type, not just Préstamo. Explicit user direction once this was flagged: fix it properly, for every note type, not just cosmetically add the new field to Préstamo. Fix:
  - `NoteReport` gained an `observations` field (alongside `motivo`/`areaEvento`, the other note-level-not-per-item fields).
  - `NOTE_REPORT` gained an `observations TEXT`/`NVARCHAR(MAX)` column (SQLite `DatabaseService` + SQL Server `RemoteDatabaseService.ensureSchema()`, both the `CREATE TABLE` block for new installs and `migrateSchema()`/`addColumnIfMissing()` for existing ones — same "any new column needs both" rule as every other column addition in this file). Chosen over adding it to `NOTE_ENTREGA_DEVOLUCION`/`NOTE_PROVEEDOR` instead, since Observaciones Generales is common to every profile type (Entrega, Devolución, Fin de Contrato, Préstamo, Proveedor alike) — same reasoning `technician_name`/`technician_dni` already live directly on `NOTE_REPORT` rather than being duplicated into each sub-table.
  - `SqliteHistoryService.insertReport()` now writes it; `getById()` (the query both `NoteDetailController` and `PrestamoDetailController` use to load the full report before rendering the popup) now reads it back. `LIST_BASE_SQL` (the summary-row query behind the History table itself) deliberately was **not** touched — Observaciones Generales was never shown as a History table column, only inside the rendered note, so only the single-report `getById()` path needed it.
  - `NoteGenerationService.generateFromStoredReport()` now renders `orEmpty(report.getObservations())` instead of the hardcoded `""`.
  - `NoteGeneratorController.handleGenerateNote()` now calls `report.setObservations(getObservations())` before handing the report off to `openPreview()`/eventual `save()` — previously the footer's `TextField` value was passed *only* into the HTML-generation call, never onto the `NoteReport` object that actually gets persisted.
  - Every other field `generateFromStoredReport()` renders was already correctly re-derived from the stored `NoteReport` (`AREA_EVENT`, `FAILURE_CAUSE`/`FAILURE_DETAILS`, item details, etc.) — audited while investigating this bug, confirmed Observaciones Generales was the *only* field with this gap.
- **"Reimprimir Nota" button added to both `NoteDetailView.fxml` and `PrestamoDetailView.fxml`** — footer, immediately left of the existing "Cerrar" button, visible to every technician (not admin-gated: reprinting is a read-only action on already-saved data, the same risk level as opening the popup itself). `NoteDetailController.handleReprint()`/`PrestamoDetailController.handleReprint()` (duplicated per this codebase's no-shared-abstraction convention) reuse the exact `PrinterJob`/`PageLayout` pattern already established in `NotePreviewController.printNote()`, printing whatever HTML is already loaded in that popup's own `webPreview` — no re-fetch needed, since `getById()` already loaded the full report (including, now, its Observaciones) before the popup opened.
- **Tests**: `SqliteHistoryServiceTest.getByIdPersistsAndReturnsObservationsGenerales()`, `NoteGenerationServiceTest.generateFromStoredReportRendersPersistedObservationsGenerales()`, `DatabaseServiceMigrationTest`'s existing migration test extended with an `observations` column assertion. `SqliteHistoryServiceTest`'s own duplicated schema copy (see [SQLite schema mirrors the remote SQL Server schema](#sqlite-schema-mirrors-the-remote-sql-server-schema) for why it can't just reuse `DatabaseService`) needed the new column added too, or every `save()`-calling test in that file would fail with "table NOTE_REPORT has no column named observations" — caught immediately by the full suite, not a silent gap.

### Préstamo Historial selected-row text was invisible (white-on-white); free-text length caps added

Both fixed 2026-07-17, direct user report/request in the same session as the Observaciones persistence work above.

- **`tblPrestamos` (`PrestamoHistoryView.fxml`) selected-row text bug**: carried `styleClass="modern-table, equipment-table"`, copied from the note-generation item tables (`tblAssets`/`tblCountables`) rather than from `HistoryController`'s own `tblGlobal`. `.equipment-table .table-row-cell:selected .table-cell` overrides text-fill to light (`#f4f8f7`) against `.modern-table`'s own light teal selected-row background (`#6ebdb0`) — the exact combination the existing gotcha note under [Known issues/gotchas](#known-issues--gotchas) already warns about (`.equipment-table` is meant to *keep* light text light on tables that were already using it before `349c9e0` darkened `.modern-table`'s default, not to be added to a new colored-status history table). Fixed by dropping `equipment-table` from `tblPrestamos`, matching `tblGlobal` exactly (`modern-table` only) — same dark selected text (`#0c2620`) as regular Historial.
- **Free-text field length caps**: per-item "Detalles" (`ItemDialogView.fxml`'s `txtObs`, backing both asset and countable items' `NOTE_ITEM.observations`) capped at **200 characters**; "Observaciones Generales" (`NoteGeneratorView.fxml`/`PrestamoNewLoanView.fxml`'s `txtObservations`, backing `NOTE_REPORT.observations` — see above) capped at **300 characters**. Both via a `TextFormatter` rejecting any keystroke that would exceed the limit, same mechanism as `ProfileController`'s existing 20-char `txtDisplayName` cap and the DNI/A-F fields — added in `ItemDialogController.java`, `NoteGeneratorController.java`, and `PrestamoNewLoanController.java`'s `initialize()` methods respectively.
  - **App-layer only, no DB-level `CHECK` constraint** — explicit user decision after being shown the trade-off: SQLite doesn't actually enforce a declared column length (`VARCHAR(200)` behaves identically to `TEXT`), so a *real* DB-level limit needs a `CHECK(length(col) <= N)` constraint instead. That's straightforward for a brand-new column (`NOTE_REPORT.observations`, added the same day — see above), but `NOTE_ITEM.observations` (the per-item Detalles field) already existed before this session, and SQLite cannot add a `CHECK` constraint to an *existing* column without fully rebuilding that table (recreate + copy old rows + drop + rename) — a materially more invasive migration than anything else in this schema. Given neither of this app's other length-bounded fields (DNI, display name) has a matching DB constraint either, the `TextFormatter` cap alone was chosen as consistent with existing precedent, not a shortcut.
  - **Tests**: `NoteGeneratorViewFxmlTest.java` (new — no prior test loaded `NoteGeneratorView.fxml`; its controller's `initialize()` touches no `ServiceLocator`/DB state, making a full `FXMLLoader` load cheap and realistic, same reasoning as `PrestamoNewLoanViewFxmlTest`) and `PrestamoNewLoanViewFxmlTest.observationsFieldRejectsInputBeyond300Characters()` both exercise the *real* `TextFormatter` through a real FXML load + reflection-obtained field, not a re-implemented copy of the length-check logic. **`ItemDialogController`'s `txtObs` cap has no equivalent test** — unlike the other two, its `initialize()` calls `ServiceLocator.getInstance().getEquipmentService()`/`getHistoryService()` directly, and no test in this suite configures `ServiceLocator` against anything but the real local `data/noteapp.db` (confirmed: no existing test calls either method directly) — same class of "impractical to exercise in isolation" gap already accepted for `MainController`/`HistoryController`/`PrestamoHistoryController`'s `initialize()` methods. Flagged here rather than silently skipped.

### S/N Validation regex field length cap

Added 2026-07-20, direct user request after reviewing a schema-size report prepared for the org's DB admin and noticing `SN_VALIDATION.regex_pattern` had no length limit at all (`NVARCHAR(MAX)`/`TEXT`, both unbounded). `SettingsController.SN_REGEX_MAX_LENGTH` (500) + a `TextFormatter` on `openEditDialog()`'s `tfRegex` field caps input, same mechanism/precedent as the Detalles/Observaciones Generales caps above.

- **Initially app-layer only — superseded same day, see next entry.** `regex_pattern` now has a real `NVARCHAR(500)` bound on the SQL Server side too, not just the app-layer `TextFormatter` — the user explicitly wanted the DB, not the app, to be the source of truth for the limit.
- **500 was chosen deliberately generous** — the actual risk with an unbounded regex field is ReDoS (catastrophic backtracking from a pathological pattern like nested quantifiers), not storage size (this table is admin-curated, one row per model, negligible either way); a length cap doesn't address ReDoS at all, since a malicious pattern can be short. The cap here is purely a sanity guard against an accidental huge paste, sized well above any legitimate S/N regex's real length, not a security control.
- **No test added** — same "impractical to exercise in isolation" gap as `ItemDialogController.txtObs` immediately above: `SettingsController.initialize()` also calls `ServiceLocator.getInstance().getEquipmentService()` directly, and `tfRegex` is built dynamically inside `openEditDialog()`, not declared in `SettingsView.fxml`, so `SettingsViewFxmlTest`'s plain FXML load wouldn't exercise it either way.

### DB-side length bounds for every previously-unbounded free-text column

Added 2026-07-20, same day as the regex cap above — direct user follow-up after reviewing the schema-size report: several `NVARCHAR(MAX)`/`TEXT` free-text columns across `NOTE_ENTREGA_DEVOLUCION`, `NOTE_PROVEEDOR`, `NOTE_ITEM`, and `NOTE_REPORT` had no length bound anywhere, DB or app. Explicit user direction this time: **the DB should declare the real limit, and the app should be adapted to match it** — the reverse of the app-layer-only approach used for the earlier free-text caps (`Detalles`/`Observaciones Generales`/the regex field above).

- **SQL Server side (`RemoteDatabaseService.java`)** — every column below changed from `NVARCHAR(MAX)` to a bounded `NVARCHAR(n)`, in both the `CREATE TABLE` blocks (new installs) and the `addColumnIfMissing()` migration calls (installs that never had the column at all):
  - `SN_VALIDATION.regex_pattern` → 500 (see entry above)
  - `NOTE_REPORT.observations` → 300 (matches the existing app cap already in place)
  - `NOTE_ENTREGA_DEVOLUCION.motivo` → 100, `.failure_cause` → 100, `.failure_details` → 200, `.area_evento` → 200
  - `NOTE_PROVEEDOR.motivo` → 100
  - `NOTE_ITEM.observations` → 200 (matches the existing app cap already in place), `.glpi_rejection_reason` → 300, `.return_rejection_reason` → 300
- **New `narrowNvarcharIfNeeded()`/`currentMaxLength()` helpers** handle the case an already-running remote database created one of these columns as `NVARCHAR(MAX)` before this change — reads `information_schema.columns.CHARACTER_MAXIMUM_LENGTH` (SQL Server reports `-1` for `MAX`) and only issues `ALTER TABLE ... ALTER COLUMN` when the current length doesn't already match the target, so it's a no-op on both a brand-new install (already correct from `CREATE TABLE`) and a re-run against an already-narrowed column. Wrapped in a swallowed try/catch — an installation with existing rows already longer than the new bound would fail the `ALTER COLUMN` with a truncation error; rather than blocking startup, the column is simply left at its current (wider) size, same "fail safely" precedent as the `idx_model_brand_type_name` unique index creation above. An admin who wants the tighter bound enforced on such an installation would need to manually trim the offending row(s) first.
- **SQLite side is unchanged** — SQLite doesn't enforce declared column lengths at all (`VARCHAR(100)` and `TEXT` behave identically), so there is no DB-side equivalent to add there; the columns stay declared `TEXT`.
- **App-layer `TextFormatter` caps added to match the new DB bounds**, since SQLite has no enforcement of its own and the two must agree: `FailureDetailController.txtFailureDetails` (200, matching `failure_details`), `UserNoteController.txtAreaEvento` and `PrestamoNewLoanController.txtAreaEvento` (200 each, matching `area_evento` — each controller has its own field/cap per this codebase's existing duplication convention), `NoteDetailController.promptRejectionReason()`'s `TextArea` and `PrestamoDetailController.promptRejectionReason()`'s `TextArea` (300 each, matching `glpi_rejection_reason`/`return_rejection_reason`).
- **`motivo`/`failure_cause` got no `TextFormatter`** — neither has a free-text input widget anywhere in the app; both are populated from fixed, admin-curated `app-config.json` lists (`motivoOptions`, `fallaOptions`) via `ComboBox` selection, never typed by a technician. The 100-char DB bound on these is therefore a config-content constraint (don't add a `motivoOptions`/`fallaOptions` entry longer than 100 characters), not something enforceable via a `TextFormatter` — no current config value comes close (longest existing entry is ~38 characters).
- **`created_at`, `glpi_status_updated_at`, `return_status_updated_at` deliberately left untouched** — these are genuinely a different problem (a real date/time value stored as plain `NVARCHAR(MAX)` ISO-8601 text, not a free-text field that merely lacks a sensible bound) and the user wants to migrate them to a real `TIMESTAMP`-family type, but explicitly wants that deferred pending confirmation of which engine (SQL Server `DATETIME2` vs. a future PostgreSQL `TIMESTAMP`) the remote backend will ultimately use — see [Deferred: real timestamp type for date/time columns](#deferred-real-timestamp-type-for-datetime-columns) below. A `// TODO` comment was left directly above `NOTE_REPORT`'s `CREATE TABLE` block in `RemoteDatabaseService.java` pointing back to this decision.
- **No test added for the narrowing migration** — same "no live SQL Server instance anywhere in this project's test infrastructure" limitation noted repeatedly elsewhere in this file (`RemoteDatabaseServiceTest` only ever exercises unreachable-host paths); `narrowNvarcharIfNeeded()`/`currentMaxLength()` query `information_schema.columns`, a SQL-Server-specific view with no SQLite equivalent, so there's no dialect-neutral stand-in database this could be tested against the way `CatalogMigrationToolTest`/`SqlServerSeedSqlSnValidationTest` manage for other SQL-Server-only logic.

### Real timestamp type for date/time columns — resolved 2026-07-29 (SQL Server side), dead NOTE_REPORT columns dropped same day

Flagged as deferred 2026-07-20 (see the superseded discussion this replaces, preserved via git history), resolved 2026-07-29 alongside a database-cleanup pass the user explicitly requested ("drop the dead columns and resolve the timestamp-type question now") after being asked directly whether the schema was finished/normalized enough to hand to a DB administrator. The engine choice was confirmed as SQL Server (no PostgreSQL revert happened), so this no longer needed to wait.

- **`RemoteDatabaseService.java`**: `NOTE_REPORT.created_at`, `NOTE_ITEM_GLPI_TRACKING.status_updated_at`, and `NOTE_ITEM_RETURN_TRACKING.status_updated_at` are now real `DATETIME2` columns instead of `NVARCHAR(MAX)`, in both the `CREATE TABLE` blocks (new installs) and a new migration path for existing ones: `migrateTimestampColumnsToDatetime2()` → `alterToDatetime2IfNeeded()` (checks `information_schema.columns.DATA_TYPE`, no-ops if already `datetime2`, otherwise runs `ALTER TABLE ... ALTER COLUMN ... DATETIME2`) → `isAlreadyDatetime2()`. Wrapped in the same swallowed try/catch as `narrowNvarcharIfNeeded()` above — an installation with some non-ISO-8601 garbage already stored would fail the `ALTER COLUMN` and is left on the old `NVARCHAR(MAX)` shape rather than blocking startup.
- **No write-path change was needed.** `SqliteHistoryService`'s writes (`ps.setString(idx, LocalDateTime.now().toString())` / `report.getCreatedAt().toString()`) were already plain ISO-8601 `'T'`-separated strings — SQL Server implicitly (and losslessly) converts these to `DATETIME2` on `INSERT`/`UPDATE`, a documented, locale-independent conversion. Kept unchanged rather than switching to `setTimestamp()` specifically to avoid a second, riskier change with no live SQL Server instance to validate it against (same limitation noted repeatedly elsewhere in this file).
- **Read path made tolerant of both engines' string rendering, not switched to `getTimestamp()`.** `DATETIME2`'s own `getString()` representation (`"yyyy-MM-dd HH:mm:ss[.fffffff]"`, space-separated) differs from SQLite's stored ISO-8601 text (`'T'`-separated) — the two new helpers `parseStoredTimestamp(String)` (tries `LocalDateTime.parse()` first, falls back to swapping the first space for `'T'` on `DateTimeParseException`) and `normalizeTimestampString(String)` (same space→`'T'` swap, for the two tracking-table columns that are kept as raw display strings rather than parsed to `LocalDateTime` — see `NoteDetailController`/`PrestamoDetailController`'s `.substring(0, 16)` display trick) replace the previous bare `LocalDateTime.parse(rs.getString(...))`/raw-passthrough calls at all 4 read sites in `SqliteHistoryService.java`. SQLite rows always succeed on the first ISO branch and never touch the fallback, so local-only installations are unaffected.
- **`HistoryController`'s date-range filtering is actually improved, not just preserved**, once `created_at` is a real `DATETIME2`: `WHERE r.created_at >= ?` / `< ?` now compares as a native datetime comparison on the remote connection (SQL Server implicitly converts the bound ISO-8601 string parameter), not a lexical string comparison — the exact fragility the original deferred-timestamp entry flagged as a future risk no longer applies there. SQLite's own comparison is unchanged (still lexical over `TEXT`, still correct as long as all stored values share the same ISO-8601 format, which they do).
- **`NOTE_REPORT.glpi_synced` and `.sede` dropped as dead columns**, same session, same user request. Both were confirmed to have zero readers/writers anywhere in the app (`glpi_synced`: never wired to anything, SQL-Server-only — `DatabaseService.java`'s SQLite schema never declared it at all; `sede`: fully superseded by `sede_id` once Sede became catalog-backed, see [Sede became catalog-backed](#sede-became-catalog-backed--sede-table-combobox-deprecated-flag-rename-2026-07-24)). `DatabaseService.java` (SQLite): new `dropDeadNoteReportColumns(Connection, Statement)`, called from `migrateSchema()` right after `migrateSedeIdSchema()` (which now guards `if (!columnExists(..., "sede")) return;` at its very start, since a database that never had — or already lost — the `sede` column has nothing left to backfill from); the `addColumnIfMissing(..., "sede", ...)` call that used to unconditionally re-add the column every startup was removed, or the drop would just get silently undone on the next run. `RemoteDatabaseService.java` mirrors this exactly (`dropDeadNoteReportColumns(Statement, Connection)`, reusing the existing `dropDefaultConstraintIfAny()` helper for `glpi_synced`'s `DEFAULT 0` before dropping it — SQL Server rejects `DROP COLUMN` on a column with an attached default constraint). Both wrapped in the same swallowed-try/catch "fail safely" pattern as every other migration step in these two files. A brand-new install never creates either column in the first place (removed from both `CREATE TABLE` blocks), so this migration only ever fires on an already-running installation.
- **Tests**: `DatabaseServiceMigrationTest.migrateSchemaDropsDeadSedeColumnFromAPreExistingDatabase` (SQLite-side drop, verified end-to-end against a real temp database) — no SQL Server-side equivalent, same "no live SQL Server instance anywhere in this project's test infrastructure" limitation as `narrowNvarcharIfNeeded()` above; `migrateSchemaAddsPrestamoColumnsToAPreExistingDatabase`'s existing `sede`-column assertion flipped from `assertTrue` to `assertFalse` to match. Full suite: 338 tests passing (337 + 1 new).
- **A separate, pre-existing issue surfaced while reading this code — flagged the same day, fixed the next.** `SqliteHistoryService.updateItemGlpiStatus()`/`updateItemReturnStatus()` both used `INSERT ... ON CONFLICT(item_id) DO UPDATE` — SQLite/PostgreSQL upsert syntax that SQL Server does not support at all (no `ON CONFLICT` clause exists in T-SQL; the rest of this codebase's SQL Server upserts were deliberately rewritten as plain check-then-insert/update for exactly this reason, see [Remote SQL Server](#remote-sql-server-write-through-cache)'s "Upserts rewritten as plain check-then-insert/update" section). Since `SqliteHistoryService` is reused unchanged against the remote connection, these two methods would have failed outright against a real SQL Server database — a real correctness gap given the remote engine is confirmed SQL Server going forward, not a hypothetical. Fixed by rewriting both to the same `UPDATE ...; if (executeUpdate() == 0) INSERT ...` shape already established for every other upsert in this codebase (e.g. `SqliteEquipmentService.carryForwardModelStockAcrossLink()`) — plain, portable SQL that runs correctly against both engines. Behavior is unchanged for every real caller (an item transitioning from an already-existing `PENDING` tracking row hits the `UPDATE` branch exactly as before); the `INSERT`-if-missing branch only matters defensively, for an item with no tracking row yet (row absence being how `N_A` is represented — see the `NOTE_ITEM` normalization notes above). Tests: `SqliteHistoryServiceTest.updateItemGlpiStatusInsertsTrackingRowWhenNoneExistedYet()`/`updateItemReturnStatusInsertsTrackingRowWhenNoneExistedYet()`, exercising the `INSERT` branch directly (the pre-existing `...PersistsStatusAndReason()` tests only ever exercised the `UPDATE` branch, since their fixture items already start `PENDING`). Full suite: 340 tests passing (338 + 2 new).

---

## Note approval workflow (2026-07-29)

`NOTE_REPORT` gains `approval_status TEXT NOT NULL DEFAULT 'PENDING'` (`PENDING`/`APPROVED`/`RECHAZADO`)
and a nullable `rejection_reason` — every newly-generated note starts `PENDING`, so a technician
picking the wrong note type or making a mistake can't have it affect real history until an admin
reviews it. Explicit user motivation: "This tells if a note is valid or not... to prevent
mistakenly-created notes to have any impact on the real history." The stricter of two options was
picked deliberately (an extra admin step on every note, vs. zero-friction-but-retroactive
correction) — see `project_stock_approval_af_design.md` in memory for the full tradeoff discussion.

- **Gates every item-level action row, not just a display label.** While `PENDING` or `RECHAZADO`,
  `NoteDetailController.buildItemCard()`/`PrestamoDetailController.buildItemCard()` render **no**
  GLPI row, Préstamo return-status row, or Provider return-status row at all (see
  [Provider conditional return tracking](#provider-conditional-return-tracking-2026-07-29) below) —
  not even the inert "— Sin acción GLPI" badge. Only once `APPROVED` do these popups look exactly
  as they did before this feature.
- **Report-level Aprobar/Rechazar UI**: both detail popups gained a `vboxApproval` section
  (`NoteDetailView.fxml`/`PrestamoDetailView.fxml`, inserted before the existing "EQUIPOS" block)
  showing a status badge always (`⏳ Pendiente de aprobación` / `✓ Aprobada` /
  `✗ Rechazada: {reason}`), plus **Aprobar/Rechazar buttons only while `PENDING` and admin-active**
  — same `adminMode && AdminSession.getInstance().isActive()` gate every other admin action in
  these popups already uses. Rejecting requires a mandatory reason, same `promptRejectionReason()`-
  style `TextArea` pattern (300-char cap) as GLPI reject/Préstamo lost. `buildApprovalSection()`/
  `handleApprove()`/`handleRejectNote()`/`promptNoteRejectionReason()` are duplicated verbatim
  between `NoteDetailController` and `PrestamoDetailController` per this codebase's
  no-shared-abstraction convention (same precedent as `buildGlpiStatusRow()`/`buildReturnStatusRow()`
  already being independent between these two controllers).
- **`approved_by`/`approved_at` deliberately NOT added.** Explicit user decision after being asked:
  this is the same "who did what when" shape as the audit logging removed in
  [Audit logging and SUPERADMIN role](#audit-logging-and-superadmin-role--built-then-descoped-for-the-first-release) —
  add it back alongside a real audit-logging rebuild, not piecemeal under a different name now.
- **History's default view shows PENDING + APPROVED, RECHAZADO hidden behind a checkbox.**
  `HistoryView.fxml` gained `chkShowRechazado` ("Mostrar rechazadas", default unchecked) next to
  "Limpiar filtros". `HistoryController.buildFilter()` sets
  `f.setApprovalStatuses(List.of("PENDING", "APPROVED"))` when unchecked; when checked, no
  approval-status filter is applied at all (every status shown, RECHAZADO included) — a RECHAZADO
  note is void but still viewable on purpose, an admin didn't lose the record. `handleClearFilters()`
  resets the checkbox to unchecked and now calls `loadGlobal(buildFilter())` instead of a bare
  `new HistoryFilter()`, so "Limpiar filtros" restores the default view rather than momentarily
  revealing RECHAZADO notes. The PENDING+APPROVED default lives in the **controller**, not the
  service — `SqliteHistoryService.getFiltered()` with no explicit `approvalStatuses` still returns
  every status, same as any other unset filter field.
- **New sidebar badge, on the Historial nav button alongside the existing GLPI-pending badge** —
  not a new nav row. `MainView.fxml`'s Historial `StackPane` gained a second `Label`
  (`lblApprovalBadge`, same `.nav-badge` style). `MainController.refreshPendingCounts()` gained a
  third count (`historyService.getPendingApproval().size()`) inside the same background-thread/
  `Platform.runLater` structure the other counts already use.
  **Follow-up, same day — three fixes/additions after seeing it live**:
  - **Overlap bug**: the first attempt placed `lblApprovalBadge` at `StackPane.alignment="CENTER_LEFT"`,
    which sat directly on top of the "Historial" label text (`.nav-button` is `-fx-alignment:
    CENTER_LEFT`, confirmed by reading the CSS — the button's own text is left-aligned, not
    centered, so a "opposite corner" badge collided with it instead of avoiding it). Fixed by
    wrapping both badges in one `HBox` (`spacing="6"`, `StackPane.alignment="CENTER_RIGHT"`) so
    they lay out automatically side-by-side at the button's right edge, immune to either badge's
    text length changing — no more manual per-badge margin math.
  - **Distinct color**: `lblApprovalBadge` (and its Préstamos counterpart below) got a new
    `.nav-badge-approval` CSS class (`-fx-text-fill: #ef4444`, red) so it can't be confused with
    the orange `.nav-badge` GLPI count sitting right next to it.
  - **Also added to the Préstamos nav button** — explicit user reversal of the original "Historial
    only" decision from earlier the same day. `MainView.fxml`'s Préstamos `StackPane` gained the
    same `HBox`-wrapped pair (`lblPrestamosApprovalBadge` + the existing `lblPrestamosBadge`).
    `MainController` gained `isPrestamoProfileType(String)` and a `prestamoApprovalPending` count —
    derived from the *same* already-fetched `getPendingApproval()` list (filtered client-side by
    profile type), not a second query, since that list already has to be fetched for the Historial
    badge anyway.
- **Schema/service shape**: `HistoryFilter.approvalStatuses` (+ static factory `pendingApproval()`,
  mirroring `pendingGlpiSync()`); `IHistoryService.getPendingApproval()`/`updateNoteApprovalStatus()`
  (both `default` methods, matching the existing optional-capability convention);
  `SqliteHistoryService.updateNoteApprovalStatus()` is a plain `UPDATE NOTE_REPORT SET
  approval_status = ?, rejection_reason = ? WHERE id = ?` — **not** the per-item
  upsert-into-tracking-table pattern `updateItemGlpiStatus()`/`updateItemReturnStatus()` use, since
  the `NOTE_REPORT` row always already exists by the time approval happens. `NoteReport.approvalStatus`
  defaults to `"PENDING"` via a Java field initializer, so every `new NoteReport()` call site
  (`NoteGeneratorController`, `PrestamoNewLoanController`) needed **no explicit line added** — the
  default alone is enough for the column round-trip to be correct.
- **Row-level visual accent (Historial + Préstamos Historial), added same day after being asked
  for one**: three options were presented (a colored left-border, a dedicated "Aprobación" column,
  or dimming RECHAZADO rows) — the left-border was picked. `HistoryController.computeRowStyle()`/
  `PrestamoHistoryController.computeRowStyle()` both gained an `approvalBorderStyle()`/
  `approvalBorderColor()` helper (duplicated per convention): a 4px left border, orange for
  PENDING, red for RECHAZADO, layered on top of whatever GLPI/return-status background already
  applies, so it reads as a second, independent signal rather than replacing the existing coloring.
  - **A real composition bug caught while implementing the Préstamos side, not by inspection** —
    `-fx-border-color`/`-fx-border-width` are single CSS properties, not additive: naively
    concatenating the existing "Vencido" bottom-border (`0 0 2 0`) with a second, separate
    `-fx-border-color`/`-fx-border-width` declaration for the approval accent would have made the
    second silently win outright (last-write-wins), dropping the Vencido border on any row that's
    also PENDING/RECHAZADO. Fixed by composing both into one multi-layer, comma-separated
    declaration (`-fx-border-color: #ef4444, #f97316; -fx-border-width: 0 0 2 0, 0 0 0 4;`) — the
    same multi-layer model this app's dialogs already use for `-fx-background-color`.
    `HistoryController`'s own version has no second border source to compose with, so its simpler
    single-declaration form is correct as-is.
  - **Follow-up, same day — border-shift bug + APPROVED green accent, both from one fix.** User
    report: rows carrying a border (PENDING/RECHAZADO) visibly shifted their content rightward
    relative to borderless APPROVED rows — border insets consume layout space, so a border that
    only *sometimes* exists changes that row's effective content width. The user's own suggested
    fix and the actual root-cause fix were the same thing: make **every** row always render a
    left border (color-only difference), so the border's width never varies and rows never shift
    relative to each other. This simultaneously satisfied the user's second ask — a green accent
    for APPROVED, "so the app is explicit on the note status" — since APPROVED now needed a real
    color anyway rather than "no border." `approvalBorderColor()` in both controllers now returns
    `#22c55e` (green) for APPROVED instead of contributing nothing, and the border-width layer for
    that case is always appended, never conditionally omitted.
  - **Tests**: `HistoryControllerTest` gained 3 new `computeRowStyle` cases (PENDING/RECHAZADO/
    APPROVED); `reportWith()`'s test helper now explicitly sets `APPROVED` so the file's
    pre-existing GLPI-color assertions aren't also implicitly testing the new border (`NoteReport`
    defaults to PENDING). New `PrestamoHistoryControllerTest.java` (this controller had no test
    file before) — same reflection-on-a-bare-instance approach, with a dedicated
    `computeRowStyleCombinesOverdueBottomBorderAndApprovalLeftBorder` test specifically guarding
    against the last-write-wins bug above. After the border-shift/green-accent follow-up, 6
    pre-existing `HistoryControllerTest` background-color assertions were loosened from
    `assertEquals` (exact string) to `assertTrue(...startsWith(...))`/`contains(...)`, since every
    background now always has a trailing border suffix appended; `PrestamoHistoryControllerTest`'s
    two "no border" cases were renamed/rewritten to assert the green border instead.
  - **Superseded 2026-07-29 — the border approach itself was replaced with a dedicated column,
    per direct user report.** However thin, a left/right border *always* consumes layout space,
    which the row's own content must shrink to make room for — but the `TableView`'s column
    *headers* never got a matching inset, so every row's cells ended up shifted a few pixels
    relative to the header text above them ("looks kind of bad," the user's own words). Presented
    4 options (dedicated status column / move the border to the row's right edge / a
    background-gradient hard-stop with no layout shift at all / just a thinner border); the
    dedicated column was picked, since it's the only one where the header and cells are
    *structurally* guaranteed to reserve the same width, not just tuned to currently look right.
    `HistoryController`/`PrestamoHistoryController.computeRowStyle()` no longer append any
    approval-related border at all — `HistoryView.fxml`/`PrestamoHistoryView.fxml` each gained a
    new, narrow (`prefWidth`/`minWidth`/`maxWidth="8"`, `resizable="false"`, `sortable="false"`,
    blank header text), first `TableColumn` (`colGApproval`/`colPApproval`) whose cell factory
    paints `-fx-background-color` (and `-fx-padding: 0` so the color fills the cell edge-to-edge,
    overriding `.modern-table .table-cell`'s own `0 10` padding) from a NoteReport's approval
    status — inline style wins over the stylesheet regardless of selector specificity, so this
    still renders correctly on a selected (teal-background) row. `approvalBorderColor()` in both
    controllers was renamed `approvalStatusColor()` to match its new role (a cell color, not a
    border color) — same PENDING→orange/RECHAZADO→red/APPROVED→green mapping, unchanged.
    `PrestamoHistoryController.computeRowStyle()` keeps its "Vencido" bottom-border exactly as
    before (a *bottom* border doesn't shift horizontal content the way a left/right one does, so
    it never had this problem) — with the approval left-border layer gone, the multi-layer
    comma-separated composition this method used to need (to avoid the CSS last-write-wins bug
    documented above) is gone too; there's only ever at most one border source now.
    **Tests**: `HistoryControllerTest`'s 3 border-suffix tests replaced with
    `approvalStatusColor{Pending,Rechazado,Approved}Is{Orange,Red,Green}` (calling the renamed
    method directly) plus `computeRowStyleNoLongerAppendsAnyBorder`; `PrestamoHistoryControllerTest`
    similarly rewritten (its own combined-border test no longer applies, since there's nothing left
    to combine). Full suite: 342 tests passing (340 + 2 net new).
- **Tests**: `SqliteHistoryServiceTest` (default-PENDING on save, `getFiltered` with/without explicit
  `approvalStatuses`, `updateNoteApprovalStatus` persists, `getPendingApproval`),
  `HistoryControllerTest` (reflection-seeded `buildFilter()` tests for the checkbox-driven default —
  needed a `Platform.startup()` `@BeforeAll` this file didn't have before, since `DatePicker`'s
  static init throws `NoClassDefFoundError` without a running FX toolkit),
  `DatabaseServiceMigrationTest` (column-exists assertions), `NoteDetailViewFxmlTest`/
  `PrestamoDetailViewFxmlTest` (extended to assert the new `vboxApproval` `fx:id` loads).

### `approval_status`'s rejected value renamed RECHAZADO → REJECTED (2026-08-06)

Caught by direct user review of the schema diagram — `PENDING`/`APPROVED` are English (this
project's code-in-English convention), but the rejected value was left as the Spanish
`RECHAZADO`, stored and compared in code exactly like the other two. Renamed to `REJECTED`
everywhere it's used as a stored/compared value (`HistoryController`, `NoteDetailController`,
`PrestamoDetailController`, `PrestamoHistoryController`, `RemitoHistoryController`, and their
tests) — the Spanish **display** text ("Rechazada"/"Rechazar", already correctly Spanish per the
UI-text convention) is untouched; only the internal value changed.

- **A real, existing-data migration was required, not just a code rename** — unlike a purely
  cosmetic rename, `approval_status = 'RECHAZADO'` was already sitting in real rows. Without a
  migration, an old rejected note would stop matching every `switch`/`if` branch entirely, falling
  through to a "Pendiente" display — while still being stuck, since none of those branches ever
  re-show the Aprobar/Rechazar buttons for a status that isn't literally `"PENDING"`. Added
  `UPDATE NOTE_REPORT SET approval_status = 'REJECTED' WHERE approval_status = 'RECHAZADO'` to
  `DatabaseService.migrateSchema()`, `RemoteDatabaseService.ensureSchema()`, and `01-schema.sql`.
- **A real regression this caused, caught by the existing test suite, not shipped**: the SQLite
  migration statement first ran unconditionally, but several pre-existing
  `DatabaseServiceMigrationTest` cases hand-build a minimal `NOTE_REPORT` fixture from before
  `approval_status` existed at all — `UPDATE ... SET approval_status = ...` against a table with
  no such column threw immediately, breaking 6 unrelated tests. Fixed by guarding it behind
  `columnExists(conn, "NOTE_REPORT", "approval_status")`, same convention as every other
  column-dependent migration statement in this file. `RemoteDatabaseService`/`01-schema.sql` never
  had this problem — both already run their own `addColumnIfMissing()`/`ALTER TABLE ADD` for
  `approval_status` earlier in the same migration sequence, guaranteeing the column exists by the
  time the `UPDATE` runs.
- **While reviewing this, the same review also caught `profile_type`'s Provider-note value,
  `"Entrega - Proveedor"`, was mixed-case** — inconsistent with the other three literal values
  (`"ENTREGA"`, `"DEVOLUCIÓN"`, `"PRÉSTAMO"`, `"ENTREGA PERMANENTE"`), all ALL-CAPS. Renamed to
  `"ENTREGA - PROVEEDOR"` at its one real write site, `NoteGeneratorController.handleGenerateNote()`.
  Every `toDisplayName()` duplicate (`NoteGenerationService`/`HistoryController`/
  `NoteDetailController`) already had a dedicated switch case expecting exactly this value
  (`case "ENTREGA - PROVEEDOR" -> "Entrega - Proveedor"`) — it had simply never been reachable
  before, silently falling through to the `default -> profileType` branch instead (which
  happened to look correct anyway, since the raw mixed-case value already read fine as-is,
  masking the inconsistency). `HistoryController.PROFILE_TYPE_LABEL_TO_RAW` already tolerated both
  casings as legacy variants, and the two `isProviderReturnable()`/`isProviderReturnableNote()`
  checks already used `equalsIgnoreCase()` — so, unlike the `RECHAZADO` case, no existing row was
  ever actually at risk of breaking here; the matching `UPDATE NOTE_REPORT SET profile_type =
  'ENTREGA - PROVEEDOR' WHERE profile_type = 'Entrega - Proveedor'` migration (added in the same
  three places) is a data-consistency cleanup, not a regression fix.
- **`approval_status` can only ever reach `RECHAZADO`/`REJECTED` through the in-app UI in one
  direction** — confirmed by reading the actual gating code, not assumed: `NoteDetailController`/
  `PrestamoDetailController`'s Aprobar/Rechazar buttons are both gated behind
  `"PENDING".equals(status)`, and `updateNoteApprovalStatus()` has no other caller anywhere in the
  app. So `PENDING → APPROVED` and `PENDING → REJECTED` are each a one-shot, terminal transition
  through the UI — never reversible, and a `PENDING → APPROVED → REJECTED → APPROVED` cycle is
  **not reachable through the app at all**. The only way it could happen is a superadmin manually
  resetting `approval_status` back to `'PENDING'` via direct SQL (same "no in-app UI, direct SQL
  only" precedent as `APP_USER`/`ROLE_PERMISSION`), which would re-enable the Aprobar button and
  let a real second approval go through normally — exactly the scenario `NOTE_REPORT.stock_applied`
  guards against, so that guard is not dead code even though the in-app UI alone can never trigger
  the case it protects.
- **Tests**: `DatabaseServiceMigrationTest.migrateSchemaUpdatesLegacyApprovalStatusAndProfileTypeValues()`
  seeds a real `NOTE_REPORT` row with both old values, runs the real migration, and asserts both
  land on the new values. `HistoryControllerTest.toDisplayNameMapsRawStoredValuesToLabels()`'s
  Provider assertion was flipped to pass the ALL-CAPS raw value as input (matching every other
  assertion in the same test) instead of the mixed-case value, so it actually exercises the
  previously-dead switch case instead of the `default` fallback. Full suite: 493 tests passing
  (492 + 1 new).

## Per-item "Modifica stock" exception (2026-08-05)

Explicit user request: a mechanism for the rare case where a note is generated to *formalize*
equipment the recipient already physically has (no delivery actually happens at approval time),
so that specific item must not move stock even though its note type normally would.

- **`ItemDialogController`** gained `chkModifiesStock` ("Modifica stock", `ItemDialogView.fxml`,
  in the fixed-footer Observaciones block so it's visible for both asset and countable items),
  **selected by default**. Unchecking it opens a self-contained orange/warning confirmation
  popup (`confirmDisableStockModification()` — its own small `Stage`/`Scene`, not a reused
  dialog-builder helper, since no existing one fit a plain Yes/No confirm) explaining the
  exceptional cases this is for; cancelling reverts the checkbox back to checked. Re-checking
  needs no confirmation — only turning the exception *on* is the risky direction. Applies
  identically to every `ItemDialogHost` flow (`NoteGeneratorController` — both User and Provider
  notes, `PrestamoNewLoanController`, `RemitoNoteController`), since all three share this same
  popup.
- **DB modeling**: a plain `NOTE_ITEM.modifies_stock` (`INTEGER`/`INT NOT NULL DEFAULT 1`) — not a
  subtype table, since (unlike GLPI/return tracking) this is a genuinely always-applicable
  attribute of every item, not a "sometimes doesn't apply" dimension; a boolean column is the
  correct normalized shape here, not row-absence. Added to `DatabaseService.java` (`CREATE TABLE`
  + `migrateSchema()`'s `addColumnIfMissing`, `DEFAULT 1` preserving existing items' current
  behavior), `RemoteDatabaseService.java`, and `database/sqlserver/01-schema.sql` (both the
  `CREATE TABLE` and its own idempotent `information_schema.columns`-guarded `ALTER TABLE`).
- **Model layer**: `EquipmentItem`/`AssetItem`/`CountableItem`/`NoteReportItem` each gained a
  `modifiesStock` field defaulting to `true` — via a new constructor overload (the original
  9-arg constructors now delegate to a 10-arg one taking the flag), so every pre-existing call
  site across the app and test suite kept compiling unchanged.
- **Effect on approval (egress/ingress stock move)**: `SqliteHistoryService.aggregateItemQuantities()`
  skips any item with `modifiesStock == false` entirely before it ever reaches
  `applyDirectionalStock()`/`applyRemitoStock()` — the item is simply left out of the aggregated
  `Map`, so it neither decrements nor increments stock at approval, while every other item on the
  same note still moves normally.
- **Effect on return/receipt credit — a second, independent site, per explicit user confirmation
  this had to be blocked too.** `resolveItemStockInfo()`'s `ItemStockInfo` record gained a
  `modifiesStock` field (read straight off `NOTE_ITEM.modifies_stock`); both
  `updateItemReturnStatus()` (Préstamo/Provider whole-item RETURNED credit) and
  `allocateCountableReturn()` (partial-quantity credit) now check `info.modifiesStock()` before
  calling `adjustModelStock(...)` — an item that never decremented stock on the way out must never
  credit it back in on the way back.
- **Effect on the pre-generation stock-shortage warning** — per explicit user confirmation, a
  flagged item shouldn't count toward "you're requesting more than your Sede has." Each of the 3
  host controllers' own `computeStockShortages()` (duplicated per this codebase's
  no-shared-abstraction convention) now skips `!isModifiesStock()` items in both its asset and
  countable loops, before aggregating requested quantities.
- **Display: admin approval popup only, unconditionally — not the note preview or printed
  template**, per explicit user direction. `NoteDetailController`/`PrestamoDetailController`'s
  `buildItemCard()` (both, duplicated per convention) render an orange `statusBadge("⚠ No
  modifica stock", "#f97316")` for any item with the flag off — shown regardless of the note's
  approval status (not gated behind `"APPROVED".equals(...)` like the GLPI/return-status rows
  are), since this is exactly the information an admin needs *before* deciding whether to
  approve, not after.
- **Tests**: `SqliteHistoryServiceTest` — `getByIdPersistsAndReturnsModifiesStock`,
  `itemsDefaultToModifyingStockWhenNotExplicitlySet`,
  `approvingEntregaSkipsStockForItemsFlaggedAsNotModifyingStock` (mixed one flagged + one normal
  item on the same note, asserting only the normal one moves),
  `approvingDevolucionSkipsStockForItemFlaggedAsNotModifyingStock`,
  `returningItemFlaggedAsNotModifyingStockDoesNotCreditStockBack`,
  `allocatingCountableReturnForItemFlaggedAsNotModifyingStockDoesNotCreditStock`. This file's own
  duplicated `NOTE_ITEM` schema copy gained the column too, same "every new column needs its test
  schema copies updated" precedent already established elsewhere in this file. **No dedicated test
  for `ItemDialogController`'s checkbox/confirmation-dialog wiring itself** — same
  "`initialize()` calls `ServiceLocator` directly" gap already accepted elsewhere in this file for
  this exact controller (`txtObs`'s own length cap), and the confirmation popup opens a real
  `Stage`, which this suite has a standing rule against leaving in the permanent run (see the
  Falla-persistence investigation elsewhere in this file). Verified via `mvn compile`/`mvn test`
  (full suite green, 487 pre-existing + 6 new) and direct code review instead.

**Follow-up, same day — mandatory audit reason.** Explicit user request: capture *why* the
exception was invoked, not just that it was, so admins have a real audit trail.

- **`confirmDisableStockModification()` gained a required `TextArea`** ("MOTIVO *", 300-char
  `TextFormatter` cap matching the new DB column below) inside the same confirmation popup — the
  method's return type changed from `boolean` to `String` (the entered reason, or `null` if
  cancelled), so the caller (`handleModifiesStockToggle()`) can no longer distinguish "cancelled"
  from "confirmed" by a bare boolean alone. The "Confirmar" button starts disabled and only
  enables once the reason field is non-blank — mandatory by construction, not just by convention.
  Re-checking the checkbox clears `stockExceptionReason` (the controller's holding field for the
  as-yet-unsaved reason), since a discarded exception has no reason to keep.
- **DB modeling — a subtype table, not a nullable column, per direct user pushback.** A first
  version added `modifies_stock_reason` directly as a nullable column on `NOTE_ITEM` — flagged by
  the user before it shipped as inconsistent with this schema's own standing normalization rule
  (a reason column sitting `NULL` on the overwhelming majority of rows, since only a small
  minority of items ever use this exception). Corrected to the same shape `NOTE_REPORT_REJECTION`
  already established for the identical problem (`rejection_reason`, also only meaningful for a
  minority of notes): a new `NOTE_ITEM_STOCK_EXCEPTION` (`item_id` PK/FK → `NOTE_ITEM`, `reason`
  `NOT NULL`) — a row exists only for an item actually flagged `modifies_stock = 0`. `NOTE_ITEM`
  itself only ever carries the boolean `modifies_stock` column (always applicable to every item,
  correctly a plain column — unlike the reason, which isn't). Added to `DatabaseService.java`
  (`createHistoryTables()`), `RemoteDatabaseService.java` (`createTableIfMissing`), and
  `database/sqlserver/01-schema.sql`.
- **A real pre-existing gap caught while working on this, not introduced by it**: `modifies_stock`
  itself had only ever been declared on `RemoteDatabaseService`'s `createTableIfMissing()` path
  (the brand-new-install `CREATE TABLE`) — since that call is a no-op once `NOTE_ITEM` already
  exists, **an already-running remote SQL Server installation never actually got the
  `modifies_stock` column added at all**. Fixed by adding the missing
  `addColumnIfMissing(stmt, c, "NOTE_ITEM", "modifies_stock", ...)` migration call.
- **Migration for the brief nullable-column shape**: since the flawed design was caught before
  being committed but *after* this local dev database had already run through it once
  (`data/noteapp.db` genuinely had the column), both `DatabaseService.migrateStockExceptionReasonSchema()`
  and `RemoteDatabaseService`'s mirror check `columnExists(..., "modifies_stock_reason")`,
  backfill any non-blank values into `NOTE_ITEM_STOCK_EXCEPTION`, then `DROP COLUMN` — same
  backfill-then-drop shape as `migrateRejectionReasonSchema()`. A no-op on any install that never
  saw the old shape (i.e. every real deployment, since this was never released).
- **Model layer**: `EquipmentItem`/`NoteReportItem` gained a plain `modifiesStockReason` field +
  getter/setter (no constructor-overload ripple, unlike `modifiesStock` itself — the reason is
  always set via a setter, after construction, from `ItemDialogController`'s held
  `stockExceptionReason`, mirroring how every other optional reason field in this codebase — e.g.
  `NoteReportItem.glpiRejectionReason` — is populated). This part of the design needed **zero**
  changes when the DB shape was corrected — same "normalization is a DB-layer concern" outcome
  already observed repeatedly elsewhere in this file.
- **Persisted via a conditional insert, not a plain column write**: `SqliteHistoryService.insertItems()`
  gained a `stockExceptionPs` batch — one row into `NOTE_ITEM_STOCK_EXCEPTION` only when
  `!item.isModifiesStock()` and a non-blank reason is present, same "row absence means not
  applicable" pattern `glpiPs`/`returnPs` already use in that method. `loadItems()` gained a
  `LEFT JOIN NOTE_ITEM_STOCK_EXCEPTION se ON se.item_id = b.id`, reading `se.reason AS
  modifies_stock_reason` — the row-mapping Java code (`item.setModifiesStockReason(...)`) needed
  no change, since the joined column name is unchanged from the earlier (reverted) design.
  `NotePreviewController.buildReportWithItems()` and `PrestamoNewLoanController`'s equivalent both
  copy `getModifiesStockReason()` across the same way they already copy `isModifiesStock()`.
- **Display**: appended to the same unconditional orange badge from the original entry above —
  `"⚠ No modifica stock: {reason}"` instead of the bare label — in both
  `NoteDetailController`/`PrestamoDetailController`. `statusBadge()`'s existing `-fx-wrap-text:
  true` already handles a long reason wrapping within the card, no layout change needed.
- **Tests**: `getByIdPersistsAndReturnsModifiesStock` extended to also assert the reason round-trips;
  `getByIdReturnsNullModifiesStockReasonWhenItemModifiesStockNormally`;
  `DatabaseServiceMigrationTest.migrateSchemaBackfillsStockExceptionReasonIntoOwnTableAndDropsColumn`
  (seeds a pre-existing database with the brief nullable-column shape, including one item with a
  reason and one without, and asserts the backfill/drop is correct and doesn't false-positive on
  the second item). Full suite: 489 tests passing.

## Provider conditional return tracking (2026-07-29)

Reuses `ReturnStatus`/`NOTE_ITEM_RETURN_TRACKING` exactly as Préstamo already has it (see
[Per-item return status](#per-item-return-status-returnstatus)) for Provider (Entrega - Proveedor)
notes, gated by a new config list — some Proveedor motivos are round-trips (Garantía, Reparación),
others are permanent departures (Otro, Devolución de préstamo), and only the former should ever show
a return-tracking row.

- **`AppConfig.returnableMotivosProveedor`** (flat top-level field, matching `failureTriggerMotivo`'s
  style — not a nested config class) names which `motivoOptions.proveedor` values trigger this,
  same "rename the config value, not the code" precedent `failureTriggerMotivo` already set for
  Devolución's Falla popup. Shipped default: `["Garantía", "Reparación"]`.
- **No controller changes needed for note creation** — a real correction of the original design
  sketch, found by reading the actual code: `SqliteHistoryService.save()` already computes its
  `isPrestamo` boolean entirely internally (from `report.getProfileType()`) before calling
  `insertItems()`, never supplied by any caller. The same server-side pattern extends cleanly to
  also check `report.getMotivo()` (already populated by the time `save()` runs, for every note type)
  — so `NoteGeneratorController`/`NotePreviewController`/`PrestamoNewLoanController` needed **zero**
  changes. `insertItems()`'s boolean parameter was renamed `isPrestamo` → `needsReturnTracking`
  (`= isPrestamo(...) || isProviderReturnable(...)`) to reflect this.
- **Distinct wording from Préstamo's, per explicit design requirement** — same underlying state
  machine, different labels: prefix `"Proveedor: "` (was `"Préstamo: "`), badges
  `"⏳ Pendiente recepción"` / `"✓ Recibido"` / `"✗ No recibido"`, buttons `"Recibido"` /
  `"No recibido"`. New `NoteDetailController.buildProviderReturnStatusRow()`/
  `handleProviderReceived()`/`handleProviderNotReceived()`/`promptProviderNotReceivedReason()` —
  duplicated from `PrestamoDetailController`'s equivalents per the no-shared-abstraction convention,
  not parameterized.
- **Applies to both asset and countable items**, same "whole note, not per item-type" semantics
  Préstamo already has — a shipped countable (e.g. cables sent for warranty replacement) needs
  return confirmation too.
- **Gated by the approval workflow too** — a returnable Provider note's asset shows both a GLPI row
  and this row, but only once `APPROVED` (see [Note approval workflow](#note-approval-workflow-2026-07-29)
  above); the single `if ("APPROVED".equals(...))` check in `buildItemCard()` wraps both.
- **`isProviderReturnableNote()`/`isProviderReturnable()` duplicated between
  `SqliteHistoryService` (service-side, drives `insertItems()`) and `NoteDetailController`
  (UI-side, drives whether to render the row at all)** — same convention as `genericLabel()` being
  duplicated three times elsewhere in this codebase.
- **Tests**: `SqliteHistoryServiceTest` — provider items land `PENDING` when Motivo is in the
  returnable list, `N_A` otherwise (both loading the real `config/app-config.json` via
  `ConfigService.getInstance().load()`, same precedent several other tests in this suite already use).

## Role-based permissions (RBAC), a SUPERADMIN tier, and Sede-scoped admin actions (2026-07-30)

Explicit user motivation: a "prohibit-all" rule — every admin-tier action is denied by default,
and only allowed per role — specifically to prevent a newly-added sensitive feature from being
silently reachable by everyone if a permission check is forgotten when it's built. This replaces
the flat `ADMIN`/`USER` split from [Login screen and role-based admin mode](#login-screen-and-role-based-admin-mode)
with a third `SUPERADMIN` tier and a real, DB-backed permission grant system — a from-scratch
redesign, not the audit-logging-era `SUPERADMIN` role removed in
[Audit logging and SUPERADMIN role](#audit-logging-and-superadmin-role--built-then-descoped-for-the-first-release)
(that removal is otherwise unaffected; this is unrelated new work, not a revival of the deleted code).

### `Permission` enum + `APP_USER`/`ROLE_PERMISSION` — a hybrid, not a hardcoded map

A code-only permission map (`Map<Role, Set<Permission>>` baked into `AdminSession`) was rejected
once the user asked the obvious follow-up question: how would a superadmin's permission change
actually reach every installation sharing one remote database? The answer is the same
remote-first/local-fallback shape every other admin-curated catalog in this app already uses
(`SEDE`, `PROVIDER`, etc.) — `models/Permission.java` (a plain enum: `MANAGE_TYPES`,
`MANAGE_BRANDS`, `MANAGE_MODELS`, `MANAGE_STOCK`, `MANAGE_PROVIDERS`, `MANAGE_SEDES`,
`EDIT_SN_VALIDATION`, `EDIT_SMTP_CONFIG`, `EDIT_GLPI_CONFIG`, `EDIT_AD_CONFIG`,
`EDIT_AF_FORMAT_CONFIG`, `APPROVE_NOTES`, `SYNC_GLPI`, `VALIDATE_RETURNS`,
`OVERRIDE_PROFILE_FIELDS`) defines *what actions exist* at compile time, while a new
`ROLE_PERMISSION` table (`role`, `permission`, PK on both) defines *who currently has them* —
edited directly via SQL by a superadmin, same "no in-app CRUD, direct SQL only" precedent
`USER_ROLE` already established (see the removed-6th-catalog-list bullet above). **Absence of a
row is the only "denied" state** — there is no separate explicit-deny flag, matching this
codebase's standing "row exists only when applicable" convention (`NOTE_ITEM_GLPI_TRACKING`,
`NOTE_ITEM_RETURN_TRACKING`, `NOTE_REPORT_REJECTION`, etc.) — a stray/unrecognized permission
string from hand-edited SQL is silently ignored (`Permission.valueOf()`'s `IllegalArgumentException`
swallowed in `SqliteUserRoleService.getPermissionsForRole()`), not thrown, matching this app's
"fail safely" convention elsewhere.

- **`USER_ROLE` (`username` PK, `role`) renamed to `APP_USER`** (`id` surrogate PK, `username
  UNIQUE`, `role CHECK (role IN ('USER','ADMIN','SUPERADMIN'))`, `sede_id INTEGER REFERENCES
  SEDE(id)`) — not just a rename: `USER` on its own is a reserved keyword/niladic function in
  T-SQL, so the SQL Server side could never have created a table literally named `USER` at all.
  `sede_id` folds the previously-separate concept of "which Sede is this account tied to" (see
  below) into the same row, since both role and Sede assignment are superadmin-curated facts
  about one account.
- **Migration, not a breaking rename** — `DatabaseService.createUserRoleTable()` /
  `RemoteDatabaseService`'s mirror check whether `APP_USER` already exists; if not (a pre-2026-07-30
  install), `migrateUserRoleIntoAppUser()` copies every existing `USER_ROLE` row across (`sede_id`
  starts `NULL` for all of them — Sede assignment is a brand-new concept, nothing to carry
  forward) and drops the old table. `ROLE_PERMISSION` is seeded **only the first time it's
  created**, never on every startup — `seedDefaultRolePermissions()` grants `ADMIN` every
  `Permission` except `EDIT_SMTP_CONFIG`, and `SUPERADMIN` every `Permission` including it,
  matching pre-RBAC status quo (a plain `ADMIN` used to be able to edit everything in Settings
  except nothing was SUPERADMIN-only before now) plus the one new restriction the user asked for.
  Seeding only once means a superadmin's later revocation (a `DELETE` against `ROLE_PERMISSION`)
  is never silently re-granted on the next app launch.
- **`IUserRoleService`** gained `getSedeId(String username)` and
  `getPermissionsForRole(String role)` alongside the existing `getRole(username)` — implemented
  in `SqliteUserRoleService` (plain `SELECT`s against `APP_USER`/`ROLE_PERMISSION`),
  `CachingUserRoleService` (remote-first, local-fallback, same try/catch shape as every other
  method on that class), and `MockUserRoleService` (in-memory; its constructor pre-seeds the same
  ADMIN-minus-SMTP/SUPERADMIN-everything default described above, so a test that doesn't care
  about permissions doesn't have to arrange them — `setRole()`/`setSedeId()`/
  `setPermissionsForRole()` are test-only, not part of the interface, same convention as the
  original `setRole()`).

### `AdminSession.effectiveRole` — the shared-password fallback can never grant SUPERADMIN

The single riskiest requirement in this feature, stated explicitly by the user up front: the
existing shared-password fallback (`AdminSession.activate()`, used by `requirePermission()`'s
`promptPassword()` path below when a technician has no elevated role of their own) must only ever
unlock `ADMIN`-level actions, never `SUPERADMIN`-only ones like `EDIT_SMTP_CONFIG` — a shared
password is fundamentally weaker proof of identity than a real per-account login, so it can't be
allowed to reach the tier reserved for that stronger proof.

- **`activatePermanently(String role)`** (was `activatePermanently()`, no args, always implicitly
  `ADMIN`) now takes the role that actually logged in — `LoginController` passes
  `IUserRoleService.getRole(username)`'s result straight through for both `ADMIN` and
  `SUPERADMIN`. `AdminSession.effectiveRole` (new `volatile String` field) is set explicitly and
  independently at every activation path: `activate()` (the shared-password fallback) always sets
  it to `IUserRoleService.ROLE_ADMIN`, hardcoded, regardless of anything else — this is the one
  line that actually enforces the "fallback never grants SUPERADMIN" requirement.
  `activatePermanently(role)` sets it to whatever role was passed in. Both `deactivate()` and
  `expire()` reset it to `null`.
- **`hasPermission(Permission)`** — `false` immediately if `!isActive()` or `effectiveRole` is
  `null`; otherwise delegates to `ServiceLocator.getInstance().getUserRoleService()
  .getPermissionsForRole(effectiveRole).contains(permission)`. A second overload,
  **`hasPermission(Permission, Integer noteSedeId)`**, adds the Sede-scoping described below —
  it first re-checks the plain (non-scoped) overload, then short-circuits `true` for
  `SUPERADMIN` regardless of Sede, otherwise requires `noteSedeId` and the session's own
  `TechnicianSessionService.getSedeId()` to both be non-null and equal.
- **`getEffectiveRole()`** — new public getter (`null` when inactive), used by `MainController`
  for the superadmin title-bar badge color (see below) and by `NoteDetailController`/
  `PrestamoDetailController`'s Sede-mismatch check.

### Deny-by-default call sites — every admin-tier action converted

Every place in the app that used to gate an action purely on `AdminSession.isActive()` (a binary
"is *any* admin logged in") was converted to check a specific `Permission` instead — the actual
"prohibit-all, allow per role" change the user asked for. None of these call sites needed a new
UI element; each one already had a boolean gate to flip.

- **`DatabaseSectionController`'s `requireAdmin(Runnable)` renamed to `requirePermission(Permission,
  Runnable)`.** All 16 original call sites (`handleAdd/Edit/Remove` × Type/Brand/Model/Provider/
  Sede, `handleModifyStock`) now pass the matching `Permission.MANAGE_*` constant. The method's
  shape is otherwise unchanged: if `AdminSession.hasPermission(permission)` already holds (a real
  login already granted it), run the action immediately and just refresh the session's activity
  timer; otherwise fall through to the existing password-prompt fallback — but **the fallback
  path now double-checks that `ADMIN`'s own currently-granted permission set (queried live via
  `getPermissionsForRole(ROLE_ADMIN)`) actually contains the requested permission** before running
  the action, even after a correct password. This is what stops the shared password from ever
  reaching a SUPERADMIN-only permission through this path too — not just `effectiveRole` alone,
  since `requirePermission()`'s fallback branch never calls `activate()` at all (no session state
  changes), so `AdminSession.effectiveRole` being pinned to `ADMIN` wasn't by itself sufficient to
  block this specific code path; the explicit re-check is a second, independent enforcement of the
  same rule, one call site removed from `activate()` itself.
- **`SettingsController.updateFieldEditability()`** — was one `setDisable(!adminActive)` call
  covering every field. Now four independent checks (`EDIT_AF_FORMAT_CONFIG`, `EDIT_SMTP_CONFIG`,
  `EDIT_GLPI_CONFIG`, `EDIT_AD_CONFIG`), each gating its own field group. `handleSave()` matches:
  each field group is only read from its `TextField`/`PasswordField` and persisted if its own
  permission is currently granted; a group without permission falls back to the value already in
  `AppConfig`/`APP_SETTINGS` rather than silently blanking it out. `handleEditRow()` (S/N
  Validation) checks `EDIT_SN_VALIDATION`.
- **`ProfileController.updateEditability()`** — `OVERRIDE_PROFILE_FIELDS` replaces the old
  `AdminSession.isActive()` check gating the four manual-edit identity fields.
- **`NoteDetailController`/`PrestamoDetailController`** — `APPROVE_NOTES` (Aprobar/Rechazar),
  `SYNC_GLPI` (Sincronizar/Rechazar), `VALIDATE_RETURNS` (Devuelto/No devuelto in
  `PrestamoDetailController`, Recibido/No recibido in `NoteDetailController`'s Provider
  return-tracking row) — all three now sede-scoped, see below.

### Sede-scoped admin actions — a plain ADMIN only acts on their own Sede's notes

A genuinely new requirement, not a straightforward permission-per-action conversion: an `ADMIN`
account is now itself tied to one Sede (`APP_USER.sede_id`), and can only approve/reject notes,
sync GLPI, or validate returns for notes generated *at that Sede* — not because the permission
itself is withheld, but because the action's target doesn't match where this admin is allowed to
act. `SUPERADMIN` bypasses this scoping entirely and can act on any Sede's notes.

- **Enforced inside `hasPermission(Permission, Integer noteSedeId)`** (see above) — every one of
  the three sede-scoped call sites now calls this overload instead of the plain one, passing the
  note's own `sede_id` (via a small `noteSedeIdOrNull()` helper duplicated in both controllers,
  per the no-shared-abstraction convention — `report.getSedeId()` returns a primitive `int`, `0`
  meaning "unset", translated to `null` for the permission check). An unset note Sede or an unset
  admin Sede both resolve to "not a match," not a silent allow — mirrors the same "unset counts as
  no match" rule `MainController`'s own Sede-filtered badge counts use (see below).
- **A real bug caught before it could ship**: `SqliteHistoryService.getById()` — the query both
  detail popups use to load a report before rendering — resolved `sede_id`'s joined *name* into
  `NoteReport.setSede(...)` but never read the raw `sede_id` column back into
  `NoteReport.setSedeId(...)` at all. Every loaded note's `sedeId` would have silently been `0`
  (unset), which — combined with the "unset never matches" rule above — would have made *every*
  admin's Sede-scoped permission check fail for *every* note, regardless of Sede, defeating the
  feature outright rather than just being slightly wrong. Fixed by adding `COALESCE(r.sede_id, 0)
  AS sede_id` to the `SELECT` and `r.setSedeId(rs.getInt("sede_id"))` to the row mapping.
- **Buttons-only restriction, not the whole note hidden** — explicit user choice from two
  presented options. A Sede-mismatched admin still opens the note detail popup and sees its full
  contents (items, status, approval state); only the action buttons for the three sede-scoped
  permissions are withheld for that specific note.
- **A warning banner, not just silently-missing buttons** — per the user's own follow-up ("add a
  warning somewhere to tell the non-sede-admin that this note belongs to another Sede and cannot
  be audited by this admin"). `isSedeMismatchForAdmin()` (duplicated in both controllers) is
  `true` only when `adminMode && AdminSession.isActive() && effectiveRole != SUPERADMIN` and the
  admin's own Sede doesn't match the note's — `buildApprovalSection()` inserts a plain warning
  `Label` right after the approval-status row whenever this holds, so it's visible regardless of
  the note's approval state, not just alongside the withheld buttons.

### Self-service Sede removed entirely — Sede is now superadmin-assigned, for every technician

A deliberate, explicit expansion of scope the user confirmed directly: not just admins' Sede
tying them to an approval scope, but **every technician's own Sede** (the mandatory,
note-generation-blocking value from [Technician Sede](#technician-sede--per-note-mandatory-2026-07-22)
and [Sede became catalog-backed](#sede-became-catalog-backed--sede-table-combobox-deprecated-flag-rename-2026-07-24))
is no longer a self-service preference a technician picks themselves. It's read from the same
`APP_USER.sede_id` a superadmin assigns for the RBAC scoping above — one assignment serves both
purposes, since a technician's own Sede and "the Sede this account is tied to" are the same fact.

- **`SettingsController`'s "SEDE" card (`cmbSede`, `handleSaveSede()`, `lblSedeStatus`) was
  removed entirely from `SettingsView.fxml`** — there is no self-service Sede UI left anywhere in
  the app. A superadmin sets `APP_USER.sede_id` directly via SQL, same as role itself.
- **`TechnicianSessionService`** — `setSedePreference(Integer, String)`,
  `addOnSedeChangeListener`/`removeOnSedeChangeListener`/`notifySedeListeners()`, and
  `loadSedePreference()`'s `APP_SETTINGS`-backed persistence were all deleted outright, not
  deprecated in place — there is no more "preference" to persist, since Sede is now derived,
  read-only session state, same shape as `role`. `getSede()`/`getSedeId()` keep their existing
  method signatures and meaning (display name / catalog id) for every existing caller (sidebar
  label, templates via `NoteGeneratorController`/`PrestamoNewLoanController`), so those call sites
  needed zero changes. A new private `loadAssignedSede()` (called from both `loginResolved()` and
  `applyManualOverride()`, mirroring how `loadDisplayNamePreference()` already gets called from
  both) reads `IUserRoleService.getSedeId(username)` and resolves the display name via the
  existing `resolveSedeName(int)` helper — unchanged, including its deliberate "resolves even a
  deprecated row's name" exception (see the original Sede-catalog entry above for why).
- **Unset Sede is fail-safe, not silently permitted** — per the user's explicit answer ("the
  preference will be removed"): a technician with no `APP_USER.sede_id` assigned simply has
  `getSede()`/`getSedeId()` return `null`, same as before this change, so the existing mandatory-
  Sede block on note generation / Préstamo registration (`NoteGeneratorController`/
  `PrestamoNewLoanController`) continues to apply unmodified — nothing new needed there.
- **Sidebar Sede label reworked from "hidden when unset" to "always visible, warning-styled when
  unset"** — `MainController.updateSedeLabel()` used to hide `lblSede` entirely when Sede was
  blank (reasonable when a technician could just go set their own preference). Now that an unset
  Sede requires a superadmin's action to fix, silently hiding the indicator would leave a
  technician with no visible clue why note generation is blocked. `lblSede` is now always
  `visible`/`managed`; it reads `"Sede no asignada"` styled via a new bold, orange
  `.user-sede-warning` CSS class (vs. the existing muted `.user-sede` class) when unset, or
  `"Sede: {name}"` in the original styling otherwise.
- **A one-time startup warning popup** — `MainController.warnIfSedeUnassigned()`, called from the
  connectivity-overlay fade-out's `setOnFinished` (the same point that used to house the
  now-obsolete "profile unavailable" warning from the original login-screen rework — see that
  section's comment, now genuinely correct again since this is a real new case). Reuses the
  existing `showDialogNotice(title, message, accentColor, icon)` helper with the same orange/`⚠`
  styling as every other warning popup in this app, rather than introducing a new dialog pattern.

### Sidebar pending-count badges Sede-scoped for a plain `ADMIN`, global for `SUPERADMIN`

Per the user's explicit answer ("Filter to their own Sede. Superadmin will see all sedes number
here."). `MainController.refreshPendingCounts()` now branches on
`AdminSession.getEffectiveRole()`: a plain `ADMIN` filters all three counts (GLPI-pending,
approval-pending, Préstamo-return-pending) down to notes matching their own
`TechnicianSessionService.getSede()` (name-based, matching `HistoryFilter.sedes`' existing
`List<String>` shape — reused directly for the Préstamo count's `getFiltered()` call; the other
two counts, which read from `getPendingGlpiSync()`/`getPendingApproval()`'s plain `List<NoteReport>`
results with no filter parameter, are filtered client-side via a small `sameSede(NoteReport,
String)` helper instead). `SUPERADMIN` and a non-admin technician alike see the unfiltered, global
count — for `SUPERADMIN` because they can act on every Sede; for a non-admin technician because
the badge is purely informational either way (they can't act on any pending item regardless of
Sede, admin or not), so scoping it would just be inconsistent with why it's shown to non-admins at
all in the first place.

### Superadmin title-bar badge — distinct magenta, not the existing admin teal

Per explicit user request: `MainController.updateAdminIndicator()` now also sets
`lblAdminIndicator`'s text (`"MODO SUPERADMINISTRADOR"` vs. `"MODO ADMINISTRADOR"`) and CSS class
(`.title-bar-superadmin-badge`, new, `#c026d3` magenta, vs. the existing `.title-bar-admin-badge`,
`#0e9a82` teal) based on `IUserRoleService.ROLE_SUPERADMIN.equals(getEffectiveRole())` — same
sizing/padding/lettering as the existing badge, color and text only.

### Tests

`AdminSessionTest` gained `MockUserRoleService` wiring plus cases for `effectiveRole`/
`hasPermission`/`hasPermission(Permission, Integer)` across `activate()` vs.
`activatePermanently(role)`, including the specific "shared-password fallback never grants a
SUPERADMIN-only permission" requirement and the "SUPERADMIN bypasses Sede-scoping, a null note
Sede never matches for ADMIN" cases. `SqliteUserRoleServiceTest` rewritten for the
`APP_USER`/`ROLE_PERMISSION` schema (was hand-building the old `USER_ROLE` table), gained
`getSedeId`/`getPermissionsForRole` coverage including the stray-unknown-permission-string
tolerance. `TechnicianSessionServiceTest`'s Sede tests rewritten entirely around
`MockUserRoleService.setSedeId()` instead of the deleted `setSedePreference()`/listener API,
including a case simulating a superadmin assigning a Sede *between* two refreshes of the same
session. `SettingsControllerTest` rewritten to check SMTP fields separately from the other three
config groups (a plain `ADMIN` must never see them enabled, `SUPERADMIN` must). `LoginControllerTest`
gained a `SUPERADMIN`-login case asserting `AdminSession.getEffectiveRole()` directly.
`CachingServiceTest` gained `CachingUserRoleService` read/fallback coverage for all three
`IUserRoleService` methods (this class had no test coverage at all before this change).
`DatabaseServiceMigrationTest` gained three `createUserRoleTable()` cases: brand-new-database
seeding, migrating an existing `USER_ROLE` table's data forward, and idempotency (re-running must
never silently re-grant a permission a superadmin already revoked). Full suite: 378 tests passing.

## Login: unregistered-account and username-substring-match bugs fixed (2026-07-30)

Direct user report, caught by manually testing login right after the RBAC feature above shipped:
an account with no `APP_USER` row could still log in (silently defaulted to `USER` role, no
error), and typing only a few letters of a real AD username logged in *as that other, unrelated
account* with no error either. Two independent bugs, both in the authentication path itself, not
the RBAC/permission layer built earlier the same day:

- **Root cause 1 — no registration gate at all.** `LoginController` never checked whether the
  authenticated AD account had a row in `APP_USER`; `IUserRoleService.getRole()`'s existing
  `ROLE_USER` default (designed as a *permission* fallback — "most technicians are never
  promoted") was being relied on, unintentionally, as an implicit "anyone with valid AD credentials
  may use the app" gate. Fixed with a new `IUserRoleService.isRegistered(String username)` (real
  `APP_USER` existence check, implemented in `SqliteUserRoleService`/`CachingUserRoleService`/
  `MockUserRoleService` — `MockUserRoleService.isRegistered()` is just `roles.containsKey(username)`,
  so any test already calling `setRole()` registers implicitly, no test rewrites needed).
  `LoginController.handleLogin()` now rejects with **"Usuario no registrado en la aplicación.
  Solicite acceso a un administrador."** — a new, specific message, distinct from the existing
  generic "Ingrese usuario y contraseña." (empty fields) and "Usuario o contraseña incorrectos."
  (bad credentials) — right after resolving the profile, before ever calling `getRole()`.
- **Root cause 2 — substring username matching reused for authentication.** `search()`'s username
  matching is a deliberate substring `.contains()` (needed elsewhere for partial-username recipient
  lookups in note generation) — but two places reused it for identity resolution, where only an
  exact match should ever count:
  1. `AdApiService.mockValidateCredentials()` (the temporary stand-in for the AD API's
     not-yet-built `validate-credentials` endpoint — see [Login screen and role-based admin mode](#login-screen-and-role-based-admin-mode))
     treated "search() returned anything" as "known user," so a login attempt with only a few
     matching letters of someone else's real username passed the check entirely, with no password
     check at all (mock mode already skips password checking by design — this was strictly more
     permissive than even that). Fixed via a new `containsExactUsernameMatch(List<ADUser>,
     String)` static helper (mirrors this file's existing `matchesUsername`/`matchesDni` small-testable-helper
     pattern) requiring `u.getUsername().equalsIgnoreCase(username)`, not just a non-empty result.
  2. `LoginController.handleLogin()` itself took `profile.get(0)` — the first `search()` result —
     unconditionally, with the same latent flaw: a substring hit could resolve to the wrong
     account's `ADUser`, and thus the wrong session identity, even independent of bug 2.1 above.
     Fixed as a second, independent layer of defense: the profile is now resolved via
     `profile.stream().filter(u -> u.getUsername().equalsIgnoreCase(username)).findFirst()`,
     falling through to the existing "No se pudo obtener el perfil desde Active Directory."
     message if nothing matches exactly.
- **"Wrong password" vs. "wrong username" is still not distinguished** — deliberately left as-is,
  per explicit user direction: the real AD API's `validate-credentials` endpoint doesn't exist yet
  (see [Login screen and role-based admin mode](#login-screen-and-role-based-admin-mode)'s
  `mockCredentialValidation` bullet), so there's no way to know *which* part was wrong yet. The
  existing generic "Usuario o contraseña incorrectos." stays until that endpoint exists and can
  report the two cases separately.
- **Tests**: `LoginControllerTest.validAdCredentialsButNotRegisteredInAppUserIsRejected()` (a real
  `MockADService` account, deliberately never registered via `mockUserRoleService.setRole()`, must
  be rejected with the new message) and `.partialUsernameNeverResolvesToADifferentRealAccount()`
  (a custom `IADService` stub whose `search()` reproduces real substring matching and whose
  `validateCredentials()` naively returns valid — proving `LoginController`'s own exact-match
  filtering catches it even if some `IADService` implementation's credential check doesn't).
  `AdApiServiceTest` gained `containsExactUsernameMatchRejectsSubstringOnlyResult()`/
  `AcceptsExactMatchCaseInsensitive()`/`FalseForEmptyResults()`, same reflection-tested-static-helper
  convention as this file's other small `AdApiService` helpers. Full suite: 383 tests passing
  (378 pre-existing + 5 new).

## Real AD credential validation deployed; AD-group login gate + per-account bypass (2026-08-11)

The AD API's `POST /api/v1/ad/validate-credentials` endpoint (designed but not yet built as of the
[Login screen and role-based admin mode](#login-screen-and-role-based-admin-mode) section above)
was built, deployed to the org's AD API server, and verified end-to-end against real Active
Directory (both a wrong-password rejection and a correct-password success returning the account's
real AD group list, confirmed live via Postman) — `AppConfig.AdAccessConfig.mockCredentialValidation`
is now `false` in the real `config/app-config.json`; `AdApiService.mockValidateCredentials()` and
its `[MOCK]` stdout warning are unchanged in code (still there for local/offline dev) but no longer
exercised in production.

- **Deployed on a new port, not the existing production AD API instance** — the app hits the AD
  API's raw host:port directly with no IIS reverse proxy in front of it (confirmed by reading the
  live config, correcting an earlier assumption in this file's own history that IIS was involved).
  The new endpoint's code was deployed as a second Windows service instance on port 8099 (8086 was
  tried first but hit a Windows-administered excluded TCP port range —
  `netsh interface ipv4 show excludedportrange protocol=tcp` — not a permissions or code bug,
  diagnosed from `EACCES: permission denied` in the service's own error log) rather than folding
  the new route into the existing production instance — explicit choice to keep the
  already-working production path completely untouched while this endpoint was brought up and
  verified. `app-config.json`'s `adApi.baseUrl` now permanently points at the 8099 instance.
- **`adAccess.allowedGroupName` is now set to `"AdminLocalSoportes"`** — the org's real IT-support
  AD group, confirmed directly by the user. The access gate described in
  [Login screen and role-based admin mode](#login-screen-and-role-based-admin-mode) (blank =
  check skipped) is therefore now actually enforced: a technician must both pass the AD password
  check and be a member of this group to log in at all, on top of the pre-existing `APP_USER`
  registration requirement.
- **`APP_USER.bypass_group_check`** (`INTEGER`/`INT NOT NULL DEFAULT 0`, both engines + `01-schema.sql`,
  migrated via the standard `addColumnIfMissing()`/`columnExists()`-guarded-`ALTER TABLE` pattern
  this file documents repeatedly elsewhere) — added because intern technicians are real users who
  need app access but aren't members of `AdminLocalSoportes` and have no AD group of their own to
  be gated on instead. Same "deny-by-default, explicit per-account grant via direct SQL only"
  convention as `role`/`sede_id` on the same table (no in-app UI to set it) — a superadmin flips
  this flag for a specific intern's row to let them through the group check without weakening the
  check for anyone else. **Registration in `APP_USER` is still separately and unconditionally
  required either way** — this flag only overrides the group-membership gate specifically, not the
  registration gate from [Login: unregistered-account bugs fixed](#login-unregistered-account-and-username-substring-match-bugs-fixed-2026-07-30)
  directly above.
- **`IUserRoleService.hasGroupCheckBypass(username)`** — new interface method, `default false`-style
  semantics (no row, or a row with the flag unset, both read as `false`), implemented in
  `SqliteUserRoleService` (plain `SELECT`), `CachingUserRoleService` (remote-first, local-fallback,
  same try/catch shape as every other method on that class), and `MockUserRoleService` (in-memory
  `Map<String, Boolean>`, test-only `setGroupCheckBypass()` setter mirroring `setRole()`/
  `setSedeId()`'s existing precedent).
- **`LoginController.handleLogin()`**'s group-check block now reads: reject only when the account
  is *both* not in the allowed group *and* doesn't have the bypass flag set — an account with the
  bypass flag skips the group check entirely and falls through to the existing (unchanged)
  registration/role/session-assignment steps below it.
- **Considered and rejected**: auto-assigning `ROLE_USER` to any AD-authenticated account not yet
  present in `APP_USER` (raised directly by the user as an alternative to today's hard
  registration-required gate) — rejected in the same conversation, since it has no answer for Sede
  assignment (mandatory for note generation) and would reintroduce exactly the kind of
  implicit-access gap the [unregistered-account fix](#login-unregistered-account-and-username-substring-match-bugs-fixed-2026-07-30)
  deliberately closed. The existing explicit-registration-required model was kept as-is.
- **Tests**: `SqliteUserRoleServiceTest` gained `hasGroupCheckBypassFalseWhenNoRowExists`/
  `...FalseWhenRowExistsWithFlagUnset`/`...TrueWhenExplicitlySetViaSql`. `CachingServiceTest` gained
  `hasGroupCheckBypassReadsReturnPrimaryData`/`...FallsBackToLocalWhenPrimaryThrows`.
  `LoginControllerTest` gained `accountWithGroupCheckBypassLogsInDespiteNotBeingInTheAllowedGroup`
  (a different allowed group configured, account not a member, bypass flag set — login still
  succeeds). `DatabaseServiceMigrationTest` gained
  `migrateSchemaAddsBypassGroupCheckColumnToAPreExistingAppUserTable`. Full suite: 524 tests passing
  (517 pre-existing + 7 new).
- **Still open, flagged as a real pre-release blocker, not yet actionable**: the remote SQL Server
  database this app is meant to run against in production does not exist yet — every deployment
  today runs fully local (see [Remote SQL Server](#remote-sql-server-write-through-cache)'s
  degrade-to-local-on-unreachable behavior). Explicit user statement: the app must not ship without
  this properly configured. Until it exists, `APP_USER`/role/Sede/permission assignment is done by
  hand, per machine, via a local SQLite browser against each installation's own `data/noteapp.db` —
  a real, accepted interim gap, not a design decision.

## Remito de Envío — built, then removed before the first release

A third top-level note type in Generar Nota ("REMITO DE ENVÍO," documenting equipment shipped
between sedes) was built on 2026-07-20 — `RemitoNoteController`/`RemitoNoteView.fxml`,
`remito.html`, the `NOTE_REMITO` table, and full History integration. **Removed entirely** before
the first release — explicit user decision to ship a smaller v1 faster, not a quality problem
with the feature itself. `NoteGeneratorView.fxml`'s toggle bar reverted from a three-way split
back to the original 50/50 "NOTA PARA USUARIO"/"NOTA PARA PROVEEDOR" split (`.type-button-middle`
CSS removed, now unused); `NoteReport`'s `destinatario*`/`remitente*` fields, `NOTE_REMITO`'s
schema (both SQLite and SQL Server), and every Remito branch in `NoteGenerationService`/
`SqliteHistoryService`/`HistoryController`/`NoteDetailController` were deleted, not just hidden.
May be reintroduced in a future release; if so, re-derive the design (including the Destinatario
Sede handling, which should now be catalog-backed via [Sede became catalog-backed](#sede-became-catalog-backed--sede-table-combobox-deprecated-flag-rename-2026-07-24)
rather than free text) rather than assuming the deleted code is still relevant.

**Stale as of 2026-08-07 — this section describes a state that no longer holds.** Remito de Envío
was in fact reintroduced in a later session (its own "Envíos" sidebar section, per-Sede stock
transfer on approval) — this section was never updated to reflect that and is kept here only as a
historical record of the original removal decision. See
[NOTE_REMITO split into NOTE_REMITO_SEDE/NOTE_REMITO_OTHER](#note_remito-split-into-note_remito_sedenote_remito_other-2026-08-07)
below for the current schema; a fuller docs-currency pass on this section (and the `README.md`
Features list, `docs/*.md`) is still pending.

## NOTE_REMITO split into NOTE_REMITO_SEDE/NOTE_REMITO_OTHER (2026-08-07)

A schema-normalization follow-up, prompted by a direct user question ("prevent null FKs and NULL
values in NOTE_REMITO... redundancy with SEDE_SHIPPING_INFO?") — `NOTE_REMITO` mixed a nullable
`destination_sede_id` (meaningful only for a catalog-Sede destination) with always-populated
`destination_label`/`address`/`recipients` text. The initial framing ("just make
`destination_sede_id` an accepted nullable FK, same precedent as `MODEL.brand_type_id`") was
revised after checking the actual UI code: `RemitoNoteController` **locks (disables)** those 3
text fields the moment a real Sede is picked — `fillDestinationFields()` sets them from
`SEDE_SHIPPING_INFO` and the technician cannot edit them in that mode (only "Personalizar destino"
enables them, for a genuinely custom/free-text destination) — so a Sede-backed row's text is
*guaranteed* to exactly match that Sede's `SEDE_SHIPPING_INFO` at the moment it was picked, not a
possibly-diverging copy. That guarantee is what makes referencing the row by FK safe, instead of
duplicating its text.

- **`SEDE_SHIPPING_INFO` gained a surrogate `id` PK + `deprecated` flag** — same pattern as
  `TYPE`/`BRAND`/`MODEL`/`PROVIDER`/`SEDE` (was `sede_id` itself as the PK, one row per Sede, no
  history). A superadmin edit (still direct-SQL-only, no in-app CRUD) is now deprecate-old-row +
  insert-new-row, not an `UPDATE` — the same mechanism that lets `NOTE_ITEM`'s catalog FKs safely
  reference a row that might later be renamed. A partial unique index
  (`idx_sede_shipping_single_active`, `WHERE deprecated = 0`) enforces "at most one active row per
  Sede," mirroring `idx_model_single_active_generic`'s exact precedent.
- **`NOTE_REMITO` split into two mutually-exclusive subtype tables**, same shape as
  `NOTE_ITEM_ASSET`/`NOTE_ITEM_COUNTABLE` (exactly one exists per note, never neither, never
  both): `NOTE_REMITO_SEDE` (`shipping_info_id NOT NULL` FK → `SEDE_SHIPPING_INFO(id)`, no
  duplicated text at all) and `NOTE_REMITO_OTHER` (`destination_label`/`address`/`recipients`,
  genuinely owned free text for a destination with no catalog row to reference). The old
  `NOTE_REMITO` table is dropped entirely by the migration, not left in place unused.
- **A new gap this introduced, resolved before implementing, not silently accepted**: a catalog
  Sede can legitimately have no `SEDE_SHIPPING_INFO` configured yet (it's optional, superadmin-set)
  — with `shipping_info_id` now `NOT NULL`, such a Sede has nothing to reference. Explicit user
  decision: `RemitoNoteController.populateDestinationSedeCombo()` now filters the destination combo
  to only Sedes with an active `SEDE_SHIPPING_INFO` row (`IEquipmentService
  .getSedeIdsWithShippingInfo()`, one query for the whole set, not one `getSedeShippingInfo()` call
  per Sede) — an unconfigured Sede simply isn't offered as a catalog destination; "Personalizar
  destino" remains the escape hatch, same as it already was for a CAU not in the `SEDE` catalog at
  all. `SEDE_SHIPPING_INFO` stays superadmin-only/direct-SQL — the app never writes to it.
- **`NoteReport.shippingInfoId`** (new field) is captured at Sede-selection time
  (`RemitoNoteController.fillDestinationFields()`), not re-derived at save time — same "ids
  captured at ComboBox-selection time, not re-derived by name later" precedent already established
  for `NOTE_ITEM`'s catalog ids, since the active `SEDE_SHIPPING_INFO` row for a Sede could in
  theory change between selection and save on a shared remote database.
  `SqliteHistoryService.insertProfileDetail()`'s Remito branch is now discriminated by
  `shippingInfoId != null`, not `destinationSedeId` — in practice the two are always set together
  by `RemitoNoteController`, but `shippingInfoId` is the one that actually determines which
  subtype table a row lands in. `destinationSedeId` itself is unchanged (still written by
  `RemitoNoteController`, still read back by `getById()` via a join through `shipping_info_id` —
  `applyRemitoStock()`'s own stock-crediting check resolves it fresh from `NOTE_REMITO_SEDE ⋈
  SEDE_SHIPPING_INFO` directly rather than trusting a stored copy).
- **Migration** (both SQLite and SQL Server): `migrateSedeShippingInfoIdSchema()` must run before
  `migrateNoteRemitoSplitSchema()` — the latter needs `SEDE_SHIPPING_INFO.id`/`deprecated` to
  already exist. SQLite rebuilds `SEDE_SHIPPING_INFO` under a temp name (can't relax/change a PK
  via `ALTER TABLE`, same recipe as `migrateGenericModelSchema()`); SQL Server adds the `IDENTITY`
  column directly via `ALTER TABLE ADD` (supported on a non-empty table) after dynamically looking
  up and dropping the old inline PK constraint's auto-generated name (same technique
  `dropDefaultConstraintIfAny()` already uses for `DEFAULT` constraints). The `NOTE_REMITO` split
  itself is a per-row backfill (no rebuild needed — nothing referenced it by FK): each historical
  Sede-backed row resolves-or-creates a matching `SEDE_SHIPPING_INFO` row by exact
  (`sede_id`, `label`, `address`, `recipients`) tuple match (reusing a currently-active row if its
  values happen to match exactly, otherwise creating a new `deprecated=1` historical row — never
  touching whatever a superadmin has set as the Sede's actual current shipping info), each custom
  row moves straight into `NOTE_REMITO_OTHER`. `01-schema.sql` mirrors both migrations as T-SQL
  cursors, matching the Java's own per-row logic — no live SQL Server instance in this project's
  test infrastructure to validate either migration against, same limitation already accepted
  repeatedly elsewhere in this file.
- **A real bug caught by the test suite, not by inspection**: the new
  `idx_sede_shipping_single_active` index-creation statement, placed directly in
  `createEquipmentTables()` right after `SEDE_SHIPPING_INFO`'s `CREATE TABLE IF NOT EXISTS` (a
  no-op against an old-shape table), ran unconditionally and threw "no such column: deprecated" on
  every startup against a pre-migration database — that column doesn't exist until
  `migrateSedeShippingInfoIdSchema()` (called later, from `migrateSchema()`) adds it. Fixed by
  wrapping the statement in the same swallowed try/catch already used for
  `idx_model_single_active_generic` immediately above it in the same method — the exact same class
  of "fails safely against an unmigrated database" guard, not a new pattern.
- **Tests**: `SqliteEquipmentServiceTest` (`getSedeShippingInfo` ignores deprecated rows,
  `getSedeIdsWithShippingInfo` reflects only active rows), `MockEquipmentServiceTest`/
  `CachingServiceTest` (same, plus primary-fail/local-fallback for the new method),
  `DatabaseServiceMigrationTest` (`SEDE_SHIPPING_INFO` id/deprecated migration, the `NOTE_REMITO`
  split creating a historical `deprecated=1` row when nothing matches, and reusing an existing
  active row when values match exactly — the pre-existing
  `createHistoryTablesCreatesNoteRemitoTable` test renamed and updated for the new table names).
  `SqliteHistoryServiceTest`'s own duplicated schema copy and `remitoReport()` test helper updated
  to insert a real `SEDE_SHIPPING_INFO` row and set `shippingInfoId` for the Sede-backed case, same
  "every new column/table needs its test schema copies updated too" precedent already established
  elsewhere in this file — this is also what exercises `applyRemitoStock()`'s rewritten
  Sede-crediting query end to end (`approvingRemitoWithCatalogDestinationMovesStockBothWays` and
  friends). Full suite: 516 tests passing (509 pre-existing + 7 new).

---

## Auto-update system (2026-08-07)

Built per the design already worked out in the user's own OneDrive guide
(`Guia-Configuracion-Base-de-Datos-y-App.md`, section 10) — a network-share manifest, not a
database or API, per that design's own stated reasoning (a static `latest.json` file needs no
write-coordination or new auth/availability dependency; see that guide's section 10.7 for why an
API was explicitly deferred). Scoped to **manual, About-screen-only checks** for this pass — the
guide's automatic startup check (a background check at every app launch, with a "Más tarde"-able
notice) was **not built**, since it wasn't part of what was actually asked for; add it later as a
separate, explicitly-requested step if wanted, following the same `IUpdateService` the About
screen already uses.

- **`IUpdateService`** (`checkForUpdate(currentVersion)`, `getChangelog()`,
  `downloadAndInstall(UpdateInfo)`, default `isConfigured()`) — same Strategy-interface pattern as
  every other service in this app, specifically so a future `ApiUpdateService` can swap in later
  as a one-class change without touching `AboutController`.
- **`NetworkShareUpdateService`** — reads `latest.json` (`{version, installerFileName,
  fileSizeBytes, notes}`) from `AppConfig.UpdatesConfig.manifestPath` (new `app-config.json` key,
  `updates.manifestPath`, plain UNC path text, not a secret — blank means the feature is
  unconfigured, checked via `isConfigured()`, same degrade-gracefully convention as AD/GLPI/SMTP
  being unconfigured elsewhere in this app). `checkForUpdate()` never throws — an unreachable
  share, missing file, or malformed JSON all resolve to `Optional.empty()`, not an error surfaced
  to the technician.
- **Changelog is full history, not just the latest release's notes** — explicit design choice over
  the simpler alternative (reusing `latest.json`'s own `notes` field). A second sibling file,
  `changelog.json` (array of `{version, date, notes}`, newest first), lives next to `latest.json`
  in the same release folder and is read independently via `getChangelog()` — always available
  from the About screen's "Historial de cambios" button, whether or not an update is currently
  pending.
- **Version compare is numeric, dot-segment-wise** (`NetworkShareUpdateService.isNewer()`,
  package-private static, tested via reflection like `AdApiService`'s own small static helpers) —
  a plain string comparison would sort `"1.10.0"` before `"1.9.0"`, which is wrong.
- **Real build version, not a hardcoded string** — `AboutController.lblVersion` used to be a
  literal `"Versión 1.0.0"`. Replaced with `utils.AppVersion.getCurrentVersion()`, reading a
  `version.properties` resource Maven-filters from `pom.xml`'s `project.version` at build time
  (new `<resources>` block in `pom.xml`, scoped to just that one file so every other resource
  stays unfiltered). Chosen over `Package.getImplementationVersion()` (the guide's original
  suggestion) specifically because that only ever populates from a real packaged jar's manifest —
  it would read `null` under plain `mvn javafx:run`, which is still how this app runs today (no
  `jpackage` pipeline exists yet, see below). Falls back to `"0.0.0-dev"` if the resource is
  missing or wasn't actually filtered (e.g. copied some other way), rather than crashing.
- **UI**: `AboutView.fxml`'s existing version card gained just two buttons directly under the
  version label — "Buscar actualizaciones" and "Historial de cambios" — nothing else. **Every**
  check result (unconfigured / already up to date / a newer version found, with the
  confirm-before-close step and download/install progress folded into the same dialog) surfaces
  in a popup, never inline in the card — a first version showed the result as an inline
  `Label`/button pair instead, which visibly resized the card between states and, worse, had to
  permanently reserve empty space for that result even while idle (direct user report — "HUGE
  gap" — both problems are what's actually being avoided here, not a preference). A dialog can
  never affect this panel's layout at all, by construction, so this was a deliberate redesign
  down to zero inline update-related UI, not a tightened version of the original. All three
  dialogs (`showUpdateFoundDialog`, `showInfoDialog`, `showChangelogDialog`) are duplicated
  `Stage`/`buildDialogStage`/`buildDialogRoot`/`buildDialogScene`/`centerOnContent` helpers inside
  `AboutController` itself, per this codebase's no-shared-abstraction convention (same shape as
  `SettingsController`'s `confirmSaveDespiteFailedTest()`), since `AboutController` had no
  dialog-building code at all before this feature — it was previously a plain embedded panel
  controller, not a `Stage` owner.
- **Install flow** (`downloadAndInstall()`): copies the installer from the manifest's folder to
  `%TEMP%\notas-it-update\`, verifies the copied size against `fileSizeBytes` (deletes and throws
  on a mismatch — catches a truncated network copy, not a cryptographic guarantee), extracts a
  bundled `update-helper.bat` resource, and launches it detached
  (`ProcessBuilder`, not waited on). The controller then calls `Platform.exit()` once that returns
  without error. `update-helper.bat` runs the installer silently (`/quiet /norestart`) and
  relaunches the app — needed because Windows won't let an installer overwrite files this app's
  own still-running process has open, so something outside the Java process has to survive to
  relaunch it. Full content and reasoning: guide section 10.4-D.
- **A real, accepted gap: this cannot be tested end-to-end yet, and the user explicitly chose this
  sequencing.** No `jpackage`/installer pipeline exists in this project yet (guide section 9) —
  there is no real `.exe` to point a manifest at, and `ProcessHandle.current().info().command()`
  (used to find this app's own running executable path to relaunch) has **not been verified**
  against a real packaged build — under `mvn javafx:run` it resolves to a `java`/`javaw` launcher
  path, not the eventual packaged app's `.exe`. Flagged inline in
  `NetworkShareUpdateService.launchHelperAndRelaunch()`'s Javadoc. Confirm this, and the
  installer's actual silent-install flags (`/quiet /norestart` is the WiX/Burn-bundle standard
  `jpackage --type exe` produces, per the guide, but unverified against this project's own
  installer), once packaging is actually built — same "verify once against a real packaged build"
  caveat the guide's own section 10.6 already calls out.
- **Tests**: `NetworkShareUpdateServiceTest` (version-compare edge cases, manifest/changelog
  parsing against real temp files, the size-mismatch-deletes-the-partial-file case — the one
  deterministic, non-process-spawning part of the install flow, split into its own
  package-private `copyAndVerifyInstaller()` specifically so it's testable without spawning a
  real Windows process or needing a real installer), `AppVersionTest` (sanity-checks the filtered
  resource actually resolved), `AboutViewFxmlTest` (new — no prior test loaded this FXML). The
  process-launch step (`extractHelperScript()`/`launchHelperAndRelaunch()`) has no test — same
  "never leave a test that spawns a real process/window in the permanent suite" precedent already
  established for `Stage.show()`-driven dialogs elsewhere in this file; verified via code review
  and the guide's own documented real-build verification checklist instead. Full suite: 509 tests
  passing (493 pre-existing + 16 new).
- **UI redesign, same day, after real use**: the inline status label + conditional "Actualizar"
  button (shown right under the version label) made the About card resize between states and
  permanently reserve dead space while idle — direct user report. Replaced with popups for every
  outcome (found/not found/unconfigured), so the card's layout never changes at all; the
  confirm-before-close step and the download itself live in the same "found" dialog rather than
  two separate ones. See `AboutController.showUpdateFoundDialog()`/`showInfoDialog()`.
- **Error messages now explain *why*, not just show a path** (2026-08-07, direct user report) —
  `copyAndVerifyInstaller()`/`extractHelperScript()`/`launchHelperAndRelaunch()` used to let a raw
  `IOException` (e.g. `NoSuchFileException`, whose own message is often just the bare file path)
  propagate straight into the error dialog, with no indication of which failure mode occurred. Each
  now catches and rewrites into an explicit Spanish reason (installer not found on the share vs.
  network/copy failure vs. local temp-file preparation vs. process launch failure), still appending
  the original message after "Detalle:" for a technician who wants the raw error too. Test:
  `NetworkShareUpdateServiceTest.copyAndVerifyInstallerGivesAClearReasonWhenTheInstallerFileIsMissing()`.
  Full suite: 517 tests passing (516 pre-existing + 1 new).

---

## Mobile phones and corporate chips/lines — needed no schema changes at all (2026-08-13/14)

A same-day design detour, worth documenting precisely because of what it concluded: the first
attempt modeled phones and chips as two brand-new `TYPE.item_kind` values (`ASSET_IMEI`/
`NUMBERED`) with their own `NOTE_ITEM_PHONE`/`NOTE_ITEM_SIM` subtype tables, a 4-table item split,
a 4th/5th item table per host screen, an `ItemDialogHost.allowedItemKinds()` filtering mechanism,
and new template loops — fully built and tested (537 tests) before being **reverted the same day,
in full**, after showing it to the user. Direct feedback: no note type has ever restricted which
items can be added to it (every host screen reuses the same `ItemDialogController` against the
full catalog), so `allowedItemKinds()` was an invented restriction with no precedent; and visually,
"I HATE" a 4th/5th table appearing at all — the two-table Asset/Countable layout every screen
already had was correct and shouldn't grow a 3rd or 4th regardless of what's being modeled.

The corrected, final design: **phones and chips are not new kinds — they're just the two kinds
this schema already had, described differently to a technician.**

- **A mobile phone is an ordinary asset-table item.** Add a `TYPE` row for it (e.g. "Celular",
  `is_asset = 1`, `requires_serial = 1` recommended) exactly like Notebook or Monitor. The phone's
  IMEI is typed directly into the existing N° de Serie field, raw digits, no prefix (obtained via
  `*#06#` or Ajustes → Acerca del teléfono on the device) — A/F still derives from it normally
  (`prefijo + separador + IMEI`), since nothing about A/F's own formula changes. If the phone has
  an assigned company phone number, that goes in the item's own Observaciones/Detalles field —
  there's no dedicated field for it, and none was added.
- **A corporate chip/line is an ordinary countable-table item.** Add a `TYPE` row for it (e.g.
  "Línea Corporativa", `is_asset = 0`) exactly like Mouse or Cable. Because a countable's quantity
  has no per-unit identity, a batch of chips can't be logged as one row with quantity > 1 — each
  distinct line/number needs its own item row (quantity 1), with the number itself noted in that
  item's Detalles. This is a real, accepted gotcha (a technician's natural instinct is to bump the
  quantity instead), documented in the app's own manual (`AboutView.fxml`, section 5.2) rather than
  enforced in code — explicitly accepted as low-risk by the user, since delivering a chip on its
  own is rare in practice.
- **Zero new schema, zero new model classes, zero new controller branches.** `TYPE.is_asset`/
  `requires_serial`, `NOTE_ITEM_ASSET`/`NOTE_ITEM_COUNTABLE`, `ItemDialogController`, every host
  screen's existing two-table layout, and all 6 HTML templates are all completely unchanged — the
  entire "feature" is two new catalog rows plus a documented typing convention. The reverted
  attempt's `ItemKind` enum, `PhoneItem`/`SimItem` models, `NOTE_ITEM_PHONE`/`NOTE_ITEM_SIM`
  tables, `ItemDialogHost.allowedItemKinds()`, and the phone/chip template loops were all deleted
  outright, not left disabled behind a flag — full suite back to exactly 524 tests passing (the
  same count as before the detour began), confirming nothing about the prior shape survived.
- **The generic-model/A-F/stock/GLPI machinery all keeps working unmodified**, precisely because
  nothing new was introduced for it to special-case: a phone is GLPI-eligible and return-trackable
  the same way any other asset already was (no exclusion needed — `NUMBERED`'s GLPI exclusion was
  a reverted-attempt-only concept); a chip is display-bucketed as a countable in History's
  "Equipos" column the same way any other countable already was.
- **Manual updated** (`AboutView.fxml`, section 5.2 "Número de serie (S/N)") with two new bullets
  documenting the IMEI-goes-in-S/N convention and the one-chip-per-row rule — the only user-facing
  trace of this feature, since there is no new UI to describe.

---

## Services — current implementations

| Interface | Active implementation | Future |
|-----------|----------------------|--------|
| `IADService` | `AdApiService` (REST client, `adApi.baseUrl` + encrypted `ad_api_token`) | — |
| `IEquipmentService` | `SqliteEquipmentService` (SQLite, seeded on first run) | SQL Server (JDBC driver swap) |
| `IHistoryService` | `SqliteHistoryService` | — |
| `IGLPIService` | `GLPIServiceStub` (no-op) | GLPI REST API (out of scope v1) |
| `IEmailService` | `GmailEmailService` (Jakarta Mail, STARTTLS port 587) | — |
| `IUserRoleService` | `SqliteUserRoleService` (local + remote, via `CachingUserRoleService`) | — |
| `IAuditService` | `SqliteAuditService` (local-only, no remote write-through yet) | Remote write-through (`CachingAuditService`) if a real usage pattern needs it — see [Audit trail](#audit-trail-schema-first-pass-2026-08-06) |
| `IUpdateService` | `NetworkShareUpdateService` (reads a UNC network-share manifest, no DB/API) | `ApiUpdateService` once a real update-distribution API exists — see [Auto-update system](#auto-update-system-2026-08-07) |

---

## Config files

### `config/app-config.json`
- `afFormat`: `prefix`, `separator` — controls A/F derivation (`prefix + separator + serialNumber`). **`length`/`filler`/`inputPattern` were removed 2026-07-29** — see [A/F format simplification](#af-format-simplification-2026-07-29) below.
- `motivoOptions`: per-profile Motivo dropdown values (`entrega`, `finDeContrato`, `proveedor`, `devolucion`). `entrega` and `finDeContrato` are separate keys (each an independent list) — **added 2026-07-20**, replacing an earlier setup where Fin de Contrato silently reused `entrega`'s list (`UserNoteController.updateMotivoVisibility()` mapped `btnTypeFinContrato` to the `"entrega"` key). Explicit user request to give Fin de Contrato its own options; both new installs and the seeded config were pre-filled with a copy of `entrega`'s list as a starting point, meant to be edited independently going forward.
- `fallaOptions`: failure-cause combobox values shown by the Falla detail popup (Devolución only)
- `returnableMotivosProveedor` (added 2026-07-29, default `["Garantía", "Reparación"]`): names which `motivoOptions.proveedor` values expect the equipment to come back — see [Provider conditional return tracking](#provider-conditional-return-tracking-2026-07-29).
- `catalog.genericLabel` (default `"Genérico / Otro"`, added 2026-07-23): seeds the name of the single global "no specific brand/model" catalog row **the first time it's created only** — not live-synced, an admin renames it afterward through the ordinary catalog UI. See [Global "Genérico / Otro" MODEL row](#global-genérico--otro-model-row--collapsing-the-per-link-duplicate-2026-07-23).
- `smtp`: `host`, `port`, `senderAddress` (password stored encrypted in DB, never here)
- `adApi.baseUrl`, `glpiApi.baseUrl`: external service URLs
- `adAccess.allowedGroupName`: AD group required to log in at all (see [Login screen and role-based admin mode](#login-screen-and-role-based-admin-mode)) — blank means the check is skipped, not yet configured
- `remoteDatabase`: `host`, `port` (default `1433`), `dbName` — non-secret SQL Server connection fields for pre-configuring a shared remote DB before first startup. **Replaced the old, unused `database.baseUrl` field on 2026-07-13** — `database.baseUrl` was declared on `AppConfig` but never read anywhere in the code (confirmed via grep); it predates `RemoteDatabaseService`'s host/port/dbName/username/password JDBC connection model and was a dead leftover, not an alternate config path. `ServiceLocator.provisionDefaultSecrets()` copies `remoteDatabase.host`/`port`/`dbName` into `APP_SETTINGS` (`db_host`/`db_port`/`db_name`, plaintext) on first startup, only if `host` is non-blank and only for keys not already set — same one-shot, never-overwrite semantics as `defaults` below. **Username/password are NOT part of `remoteDatabase`** — they're secrets and go through `defaults.dbUsername`/`defaults.dbPassword` instead (see below), since `db_username`/`db_password` are stored AES-encrypted in `APP_SETTINGS`, not plaintext.
- GLPI API key and AD API token are **not** in this file — they're credentials, encrypted (`AppKeyEncryptionService`) in `APP_SETTINGS` (`glpi_api_key`, `ad_api_token`), same as `smtp_password`. Both are configured via a write-only `PasswordField` in `SettingsController` (never re-displayed once saved) and verified with a live test-before-save call before persisting (see `docs/architecture.md`'s "Active Directory Integration" section).
- `defaults` (optional): pre-encrypted (`AppKeyEncryptionService`) default values for `smtpPassword`, `glpiApiKey`, `dbUsername`, `dbPassword`, `adApiToken` — copied into `APP_SETTINGS` on first startup only if that key isn't already set, so a fresh install can ship pre-configured with zero technician/admin setup. Generate values via `utils.AppKeyEncryptionGenerator`, never paste plaintext here. `dbUsername` added 2026-07-13 alongside `remoteDatabase` — before that, there was no way to pre-provision a remote DB connection at all, since host/port/dbName had no config field and username (like password) needs encryption, not a plaintext one.
- **To fully pre-configure a remote DB before first startup**: set `remoteDatabase.host`/`port`/`dbName` (plaintext) plus `defaults.dbUsername`/`defaults.dbPassword` (both pre-encrypted via `utils.AppKeyEncryptionGenerator`). All five are one-shot — provisioned only on first run, only into currently-empty `APP_SETTINGS` keys; an admin's later edit via Base de Datos → Editar always takes precedence and is never overwritten by these.
- `noteItemLimit`: max items before warning
- `updates.manifestPath` (added 2026-08-07): UNC path to the `latest.json` auto-update manifest — plain text, not a secret. Blank means auto-update is unconfigured. See [Auto-update system](#auto-update-system-2026-08-07).

**Jackson config**: `AppConfig` and all inner classes are annotated `@JsonIgnoreProperties(ignoreUnknown = true)` — unknown keys in the JSON file do not crash the app.

### `config/mock-equipment.json`
Types, brands, `typeBrands` junction entries, models, `snValidations`. Loaded by `MockEquipmentService` — **used only in tests**, not in production.

---

## SQLite tables

`TYPE`, `BRAND`, `BRAND_TYPE_LINK`, `MODEL`, `MODEL_STOCK`, `SN_VALIDATION`, `PROVIDER`, `SEDE`, `SEDE_SHIPPING_INFO`, `APP_USER`, `ROLE_PERMISSION`, `NOTE_REPORT`, `NOTE_REPORT_REJECTION`, `NOTE_ENTREGA_DEVOLUCION`, `NOTE_DEVOLUCION_FALLA`, `NOTE_PRESTAMO_AREA_EVENTO`, `NOTE_PROVEEDOR`, `NOTE_REMITO_SEDE`, `NOTE_REMITO_OTHER`, `NOTE_ITEM`, `NOTE_ITEM_ASSET`, `NOTE_ITEM_COUNTABLE`, `NOTE_ITEM_GLPI_TRACKING`, `NOTE_ITEM_RETURN_TRACKING`, `NOTE_ITEM_GLPI_RETURN_TRACKING`, `NOTE_ITEM_STOCK_EXCEPTION`, `NOTE_ITEM_RETURN_ALLOCATION`, `AUDIT_LOGIN`, `AUDIT_STOCK`, `AUDIT_ITEM_STATUS`, `AUDIT_ADMIN_ACTION`, `APP_SETTINGS`

`NOTE_ITEM_GLPI_RETURN_TRACKING` tracks the separate "synced back into GLPI" event for a
returnable Provider note's asset once its return is validated — independent from
`NOTE_ITEM_GLPI_TRACKING`'s original sync-out, since GLPI sync is one-way/no-revert and can't be
"undone" to reflect the item coming back. `NOTE_ITEM_RETURN_ALLOCATION` is an append-only table of
partial-quantity return/loss actions on a countable item (e.g. 5 loaned headsets resolved as 3
returned now, 1 lost later, 1 still pending) — a single `NOTE_ITEM_RETURN_TRACKING.status` column
can't express that; remaining pending quantity is always `NOTE_ITEM_COUNTABLE.quantity` minus the
sum of that item's allocation rows, same row-absence-means-pending convention as every other
tracking table here. Both were missing from this list and from `database/sqlserver/01-schema.sql`
until caught by direct user review — added to both in the same pass that built
`database/sqlserver/provisioning/`.

`APP_USER` (`id` surrogate PK, `username UNIQUE`, `role` — `"ADMIN"`/`"USER"`/`"SUPERADMIN"`, `sede_id` FK→`SEDE`, nullable) is a username→role/Sede mapping, unrelated to AD group membership (which gates app access at login, checked live against the AD API, not stored here) — see [Login screen and role-based admin mode](#login-screen-and-role-based-admin-mode). Renamed from `USER_ROLE` (which had no `sede_id` and no `SUPERADMIN` tier) on 2026-07-30 — see [Role-based permissions (RBAC)](#role-based-permissions-rbac-a-superadmin-tier-and-sede-scoped-admin-actions-2026-07-30). `ROLE_PERMISSION` (`role`, `permission`, PK on both) is the deny-by-default permission grant table the same feature added — a permission is denied unless a matching row exists.

`NOTE_ENTREGA_DEVOLUCION` (Devolución's Falla flow and Préstamo's Área/Evento moved out into their own subtype tables 2026-07-30 — see [NOTE_ENTREGA_DEVOLUCION and NOTE_REPORT normalized further](#note_entrega_devolucion-and-note_report-normalized-further-2026-07-30) below): `NOTE_DEVOLUCION_FALLA` (`failure_cause`/`failure_details`, row exists only for Devolución+Falla) and `NOTE_PRESTAMO_AREA_EVENTO` (`area_evento`, row exists only when a Préstamo note actually captured one — itself optional). `NOTE_PROVEEDOR` has `responsible_name`/`responsible_dni` columns (the provider's own receiving person — see [Two-signature layout](#two-signature-layout)). `NOTE_REPORT` has `technician_name`/`technician_dni` columns (see [Technician identity](#technician-identity--session-only-sourced-from-windowsad)) — `SqliteHistoryService`'s history queries read these straight off `NOTE_REPORT`. `NOTE_REPORT` also has a `sede_id` FK (per-technician site, mandatory, printed on every note — see [Technician Sede](#technician-sede--per-note-mandatory-2026-07-22) and [Sede became catalog-backed](#sede-became-catalog-backed--sede-table-combobox-deprecated-flag-rename-2026-07-24)); the old free-text `sede` column was dropped 2026-07-29. `NOTE_REPORT.rejection_reason` moved into `NOTE_REPORT_REJECTION` 2026-07-30 (same section below) — a row exists only for a note that's actually been rejected. **`TECHNICIAN_PROFILE` and `NOTE_REPORT.technician_id` were removed 2026-07-16** (see [Technician identity](#technician-identity--session-only-sourced-from-windowsad)'s last bullet) — an already-running installation's existing table/column are simply left in place, unused, since no `DROP` migration was added. Both the SQLite (`DatabaseService`) and SQL Server (`RemoteDatabaseService.ensureSchema()`) DDL must stay in sync — see [SQLite schema mirrors the remote SQL Server schema](#sqlite-schema-mirrors-the-remote-sql-server-schema).

**`NOTE_ITEM` normalized into 5 tables (2026-07-22)** — explicit, standing user requirement: no nullable/sentinel-value columns representing "doesn't apply to this row," even when the practical cost is negligible. This was a direct correction of an initially-proposed cheaper "just make the columns nullable" fix — the user wants this same normalized shape favored by default in any future schema work with a similar all-nulls-for-some-rows pattern, not just this one table. The old wide `NOTE_ITEM` (16 columns: `is_asset`, `serial_number`/`a_f` vs `quantity`, the `glpi_*`/`return_*` tracking triples) had one whole tracking dimension and one of asset-vs-countable always sitting at a dead `NULL`/`'N_A'` value depending on the item's type and the note's profile. Split into:
- `NOTE_ITEM` (slim base): `id, note_id, type_name, brand_name, model_name, observations` — always fully populated. **`type_name`/`brand_name`/`model_name` were themselves replaced with `type_id`/`brand_id`/`model_id` real foreign keys the same day, in a follow-up phase — see "Catalog-FK redesign" below. Kept here as the historical starting shape this split began from.**
- `NOTE_ITEM_ASSET` (`item_id` PK/FK→`NOTE_ITEM`, `serial_number`, `a_f`) — row exists only for asset items.
- `NOTE_ITEM_COUNTABLE` (`item_id` PK/FK→`NOTE_ITEM`, `quantity`) — row exists only for countable items.
- `NOTE_ITEM_GLPI_TRACKING` (`item_id` PK/FK→`NOTE_ITEM`, `status`, `rejection_reason`, `status_updated_at`) — row exists only when the item is actually GLPI-tracked (asset AND not Préstamo). No `'N_A'` value anymore — row absence **is** N_A.
- `NOTE_ITEM_RETURN_TRACKING` — same shape, row exists only when the note is a Préstamo.

**Deliberately scoped to the DB layer only** — `NoteReportItem.java`, the `GlpiStatus`/`ReturnStatus` enums (both **keep** their `N_A` value; it's just represented by row-absence in SQL now, not a stored string), every controller, `NoteGenerationService`, and all 6 HTML templates needed **zero changes** — confirmed by grepping the whole `src` tree that only `SqliteHistoryService.java` and the two DDL files (plus their duplicated test/seed schema copies) ever touched these columns directly. Every rewritten `SELECT` in `SqliteHistoryService` (`loadItems()`'s item-loading query, `LIST_BASE_SQL`'s aggregate counts) reproduces the exact same result-column names (`is_asset`, `glpi_status`, `serial_number`, etc.) via `LEFT JOIN`/`COALESCE`/`CASE`, so the row-mapping Java code is unchanged. `insertItems()` can no longer batch the base insert (each subtype row needs its parent's generated id first), so it inserts one base row at a time, batching only the 4 subtype `PreparedStatement`s. `updateItemGlpiStatus()`/`updateItemReturnStatus()` switched from `UPDATE` to `INSERT ... ON CONFLICT(item_id) DO UPDATE` (defensive upsert — every real caller only ever transitions an already-existing row today).

**Migration mechanics differ by engine.** SQLite has no reliable in-place way to both drop 10 columns and add 4 new tables consistently, so `DatabaseService.migrateNoteItemSchema()` uses SQLite's own documented table-rebuild recipe: `PRAGMA foreign_keys = OFF` (must run **before** `setAutoCommit(false)` starts a transaction — this pragma is a no-op inside one), rename `NOTE_ITEM` → a timestamped temp name, create the slim table + 4 subtype tables, copy/backfill data across preserving ids, drop the renamed old table, commit, restore autocommit, `PRAGMA foreign_keys = ON`. SQL Server (`RemoteDatabaseService`) backfills the 4 new tables directly from the still-wide `NOTE_ITEM`, then drops the 10 old columns natively (`ALTER TABLE ... DROP COLUMN`, after dropping each column's default constraint first via `sys.default_constraints` — T-SQL fails `DROP COLUMN` if one's still attached). Both migrations guard on `columnExists(..., "NOTE_ITEM", "is_asset")` and are no-ops once already migrated, including on a brand-new install (which gets the slim shape straight from `createHistoryTables()`/`createTableIfMissing`).

**A real ordering bug was caught only by live-testing against the actual `data/noteapp.db`, not by the unit tests** — worth remembering as a general lesson for this codebase: `createHistoryTables()`'s `CREATE TABLE IF NOT EXISTS` for the 4 new subtype tables is **not** a no-op on an old wide-shape database (they never existed there at all), so those 4 tables already existed by the time `migrateNoteItemSchema()` ran its own (originally unconditional) `CREATE TABLE` for them — "table already exists." `DatabaseServiceMigrationTest`'s original fixtures never called the real `createHistoryTables()` first, only hand-built the "old" schema directly, so they missed this exact ordering interaction; fixed by adding `IF NOT EXISTS` to those 4 statements inside `migrateNoteItemSchema()` too, and by making the test invoke `createHistoryTables()` (via reflection) before `migrateSchema()`, matching `initialize()`'s real call order. **Verify any future migration against a real (or realistically-shaped) database, not just a hand-built unit-test fixture that skips the surrounding production call sequence.**

### Catalog-FK redesign: NOTE_ITEM/NOTE_PROVEEDOR reference the catalog by id, not text (2026-07-22, same day as the 5-table split above)

Direct continuation of the same standing normalization requirement — the 5-table split above eliminated nullable/sentinel *tracking* columns; this phase eliminates the last text-snapshot/no-FK pattern in `NOTE_ITEM`/`NOTE_PROVEEDOR` themselves. `NOTE_ITEM.type_name`/`brand_name`/`model_name` and `NOTE_PROVEEDOR.provider_name` (plain text, no relational link to the catalog at all) were replaced with `type_id`/`brand_id`/`model_id`/`provider_id` — real, `NOT NULL` foreign keys into `TYPE`/`BRAND`/`MODEL`/`PROVIDER` (every one of these selections is mandatory today, so `NOT NULL` is correct, not just convenient). Folded into the same pass as normalizing the "Genérico / Otro" fallback (below), since both were the same underlying gap.

- **`deprecated` flag, not delete-and-recreate.** `TYPE`, `BRAND`, `MODEL`, `PROVIDER` each gained a `deprecated INTEGER NOT NULL DEFAULT 0` column. Renaming or removing a catalog row **never** mutates or deletes it — `SqliteEquipmentService`'s `renameType`/`renameBrand`/`renameModel`/`renameProvider` now: (1) no-op update if the new name is just a case change of the row's own current name; (2) error if another **active** row already has that exact name; (3) else, if a **deprecated** row already has that name, reactivate it (`deprecated=0`) and deprecate the row being renamed; (4) else insert a new active row and deprecate the old one. `removeType`/`removeBrand`/`removeModel`/`removeProvider` now just set `deprecated=1` instead of `DELETE`. This means an old note's `type_id`/`brand_id`/`model_id`/`provider_id` always resolves to a row still carrying its exact original name (possibly now deprecated), so History/reprints render identically to before a rename — no text duplication needed to achieve that.
- **Uniqueness holds regardless of `deprecated` status — explicit user correction, not an implementation detail.** The first draft of this design used a *partial* unique index (`WHERE deprecated = 0`), which would have allowed two rows with the same name to coexist as long as at most one was active. The user explicitly rejected this: a name may only ever live on **one row at a time**, deprecated or not; the deprecate-then-reactivate-or-insert procedure above is exactly what keeps that true on every write, not a workaround excluded from the constraint. This actually **simplified** the schema work — `TYPE.name`/`BRAND.name`/`PROVIDER.name`'s existing plain `UNIQUE` and `MODEL`'s existing `(brand_type_id, name)` unique index already enforce precisely this, unchanged; no SQLite table-rebuild or SQL Server `DROP CONSTRAINT` was needed for this part at all.
- **Rename cascade** (`SqliteEquipmentService.cascadeAfterTypeOrBrandRename`) — the highest-risk new logic, with dedicated test coverage (`SqliteEquipmentServiceTest.renameTypeClonesEveryLinkedBrandsActiveModelsToTheNewTypeId`/`renameBrandClonesActiveModelsAcrossEveryTypeItWasLinkedTo`/`renamingBackToADeprecatedNameReactivatesTheOriginalRowInsteadOfDuplicating`). Renaming a Type or Brand deprecates the row every `BRAND_TYPE_LINK` using it points at — left alone, that would silently orphan that link's active models (the models would still exist, but be unreachable from any current dropdown). So for every affected link, an equivalent link is ensured under the new id (idempotent, same "select-or-insert" shape as `ensureBrandTypeLink`), and every currently-active `MODEL` under the old link is cloned forward onto the new one (insert-if-missing by name). A Brand rename cascades across **every** Type it was linked to, not just one.
- **"Genérico / Otro" fallback, finally backed by real rows.** `ItemDialogController`'s old `GENERIC_BRAND`/`GENERIC_MODEL` were UI-only sentinel objects with hardcoded fake ids (99 / -1) — never written to the DB, name-mismatched against the real (but unused/unlinked) seeded `'Generic'` `BRAND` row. Both problems fixed together: the seeded `BRAND` row is renamed `'Generic'` → `'Genérico / Otro'` (one-time `UPDATE`, idempotent) and is now the actual object shown/selected in the Brand dropdown's fallback — one single global row, never linked into `BRAND_TYPE_LINK` itself. `MODEL` can't have a single global fallback the same way (`MODEL.brand_type_id` is `NOT NULL` — a model is always scoped to a specific brand+type), so instead every `BRAND_TYPE_LINK` gets its **own** `'Genérico / Otro'` `MODEL` row, created lazily the moment the link itself is created or reactivated — `ensureBrandTypeLink()` (used by `addModel`/`addBrandForType`), the rename cascade above, and `ItemDialogController.onBrandSelected()` (via `addBrandForType(GENERIC_LABEL, typeId)`, when a technician picks the global Generic brand for a type that's never been linked to it before) are the 4 lazy-creation trigger points. A backfill (`INSERT INTO MODEL ... SELECT ... FROM BRAND_TYPE_LINK WHERE NOT EXISTS (...)`, both DDL files) covers every link that existed before this feature, run unconditionally on every startup (cheap, idempotent).
- **Historical backfill for pre-existing `NOTE_ITEM`/`NOTE_PROVEEDOR` rows** (`DatabaseService.migrateCatalogFkSchema()`/`RemoteDatabaseService`'s mirror): for every existing row's `type_name`/`brand_name`/`model_name`/`provider_name`, resolve to a catalog row matching **case-insensitively regardless of `deprecated` status** (reusing an already-deprecated row rather than risking a duplicate-name insert, consistent with the uniqueness rule above); if truly nothing matches, auto-create a new row with `deprecated=1` (a historical value must never silently become an active, selectable catalog entry). A blank/null historical `brand_name`/`model_name` resolves to `'Genérico / Otro'`; a blank `provider_name` resolves to a synthetic `'Desconocido'` row. SQLite: since `NOTE_ITEM` can't be safely renamed mid-migration without corrupting `NOTE_ITEM_ASSET`/`COUNTABLE`/`GLPI_TRACKING`/`RETURN_TRACKING`'s FK (the exact SQLite-rename-rewrites-other-tables'-schema gotcha already documented above for the 5-table split), the new FK-based table is built under a temp name, backfilled, and only then does the old `NOTE_ITEM` get `DROP`ped and the temp table `RENAME`d into place — the 4 subtype tables, never touched, keep resolving correctly throughout since their `REFERENCES NOTE_ITEM(id)` clause never had to survive a rename of the table it points at. `NOTE_PROVEEDOR` (nothing else references it by FK) uses the simpler rename-old/create-new/copy/drop-old shape. SQL Server: nullable FK columns added, backfilled row-by-row, then tightened to `NOT NULL` with a real `FOREIGN KEY` constraint added, old text columns dropped last.
- **Ids captured at ComboBox-selection time, not re-derived by name later.** `ItemDialogController.doSave()` already discarded `cmbType.getValue().getId()`/etc. (only `.getName()` was ever used) — now it captures them too. `AssetItem`/`CountableItem`/`EquipmentItem` gained `typeId`/`brandId`/`modelId` int fields threaded straight through; `NoteReportItem` gained matching fields; `NoteReport` gained `providerId` (from `ProviderNoteController.getProviderId()`). `NotePreviewController.buildReportWithItems()` and `PrestamoNewLoanController`'s equivalent (its own duplicate, per this codebase's no-shared-abstraction convention) both copy the ids across when building `NoteReportItem`s. Considered and rejected: resolving the id by name lookup inside `SqliteHistoryService.insertItems()` instead — rejected because the id is already sitting on the selected object at pick time, and a name-based lookup would be fragile against a mid-session rename (a stale cached ComboBox could offer a name that no longer resolves to an active row).
- **Reads: SQL gains JOINs, Java row-mapping code doesn't change.** `SqliteHistoryService.loadItems()`/`getById()`/`LIST_BASE_SQL` all now `JOIN`/`LEFT JOIN` back to `TYPE`/`BRAND`/`MODEL`/`PROVIDER` to reconstruct `type_name`/`brand_name`/`model_name`/`recipient` as SQL column aliases — the exact same result-column names as before, so `NoteReportItem.setTypeName(...)` etc. (and everything downstream: `NoteGenerationService`'s template tokens, `NoteDetailController`/`PrestamoDetailController`'s item-card titles) needed **zero changes**, same "reproduces the exact same result-column names" pattern already used for the 5-table split above. `getDistinctItemTypes/Brands/Models()` also switched to joining through (still text-based for the History filter dropdowns — no UI change), and `getFiltered()`'s item-type/brand/model filter now resolves the selected text to a set of catalog ids (`type_id IN (SELECT id FROM TYPE WHERE name IN (...))`, matching regardless of `deprecated`, so filtering by an old renamed-away name still finds the notes that used it). `getMostUsedTypeNames/BrandNames/ModelNames()` group by id and filter `deprecated = 0` (never suggest a renamed-away entry as "most used") but are otherwise behaviorally unchanged from the old text-based grouping — a rename already split the count into two buckets before this change, and still does.
- **`CatalogMigrationTool`'s `migrateTypes`/`migrateBrands`/`migrateModels`/`migrateProviders` now carry `deprecated` across** (previously nothing to carry — the column didn't exist), so a locally-deprecated catalog row ends up deprecated on the remote side too after migration. `migrateProviders` gained a genuine update-on-match branch for this (it previously only ever inserted, silently never refreshing an already-migrated provider).
- **`docs/database.md`/`docs/architecture.md`/`docs/requirements.md`/`docs/use-cases.md` updated to match** — ERD gained the `deprecated` columns and the `TYPE`/`BRAND`/`MODEL`/`PROVIDER` → `NOTE_ITEM`/`NOTE_PROVEEDOR` FK relationships; every "'Generic' brand" mention renamed to "'Genérico / Otro'".
- **Tests**: the 3 dedicated cascade/reactivate tests above, plus every existing test whose own duplicated schema copy (`SqliteHistoryServiceTest`, `SqliteEquipmentServiceTest`, `DatabaseServiceMigrationTest`, `CatalogMigrationToolTest`) needed the new `deprecated` columns and `NOTE_ITEM`/`NOTE_PROVEEDOR` FK shape added to stay in sync — same "every new column/table needs its test schema copies updated too" precedent already established elsewhere in this file. Full suite: 280 tests passing (277 pre-existing + 3 new).

### Global "Genérico / Otro" MODEL row — collapsing the per-link duplicate (2026-07-23)

Direct continuation of the Catalog-FK redesign above, prompted by a user question after reviewing the schema with a DB administrator's "normalize, eliminate redundancy" instruction in mind: is having a separate `'Genérico / Otro'` `MODEL` row per `BRAND_TYPE_LINK` actually redundant? Extended design discussion concluded **yes — not a classical normal-form violation (the table itself was already BCNF), but a real cross-row redundancy**: the label text itself is a single, scope-independent fact, physically duplicated into N rows purely because `MODEL.brand_type_id` was `NOT NULL`. Renaming the fallback meant updating N rows to stay in sync, with nothing enforcing they wouldn't drift apart — a genuine update anomaly, just not one classical 1NF–BCNF analysis (which operates on functional dependencies within one relation, not repeated literal values across rows) is built to catch.

- **Rejected alternative, considered first**: making `NOTE_ITEM.brand_id`/`model_id` nullable and treating "generic" as the *absence* of a value, rendered via a config string at display time. Rejected because it overloads NULL with a specific, known business meaning ("the technician deliberately chose Generic") rather than genuine "unknown/not applicable" — the classic NULL-as-sentinel anti-pattern, and a direct reversal of this codebase's own standing normalization rule (see [[feedback_db_normalization]] in memory) against exactly that shortcut. It would also have forced every `SqliteHistoryService` read path back from clean `JOIN`s to `LEFT JOIN`/`COALESCE`, undoing the whole point of the Catalog-FK redesign.
- **Also rejected**: an `is_generic` flag column on `BRAND`/`MODEL`. Turned out unnecessary once `MODEL.brand_type_id` itself was made nullable — a NULL `brand_type_id` is *already* a sufficient, structural, self-explaining marker (no other `MODEL` row can ever have one), so a flag would have been a redundant second signal for the exact same fact. `BRAND` never needed one either — `BRAND.name`'s existing plain `UNIQUE` constraint already makes "the" generic brand a DB-enforced singleton by name, with none of `MODEL`'s NULL-comparison problem.

**Final design**: `MODEL.brand_type_id` is now nullable; `NULL` is reserved for a single global row, offered for *every* brand+type combination (real or never-linked) via a `UNION` in `SqliteEquipmentService.getModelsForBrandAndType()`, rather than duplicated per link. `ItemDialogController.onBrandSelected()`'s old special-case (`addBrandForType(GENERIC_LABEL, typeId)` to force-create a link + its per-link generic model before querying) was removed entirely — no longer needed, since the global row needs no link to be found.

- **Singleton enforcement, the NULL-comparison trap**: a plain `UNIQUE(brand_type_id, name)` (the existing `idx_model_brand_type_name`) does **not** protect the global row at all — every SQL engine treats NULL as never-equal-to-NULL, even inside a unique index, so it would silently permit unlimited `brand_type_id IS NULL` rows. Two new partial indexes fill the gap by keying off columns that hold *real*, comparable values among the filtered rows instead of `brand_type_id` itself: `idx_model_global_generic_name` (`UNIQUE(name) WHERE brand_type_id IS NULL` — blocks an accidental duplicate-name insert) and `idx_model_single_active_generic` (`UNIQUE(deprecated) WHERE brand_type_id IS NULL AND deprecated = 0` — guarantees at most one *active* global row at a time, regardless of how many deprecated renamed-away copies pile up). SQL Server's filtered indexes support this natively, same syntax.
- **A real ordering bug this caught**: `SqliteEquipmentService.renameModel()` originally activated the new/reactivated row *before* deprecating the old one (matching `renameBrand`/`renameType`'s existing order, harmless there since their indexes key on `name`, which always differs post-rename). For the global row, this briefly left *two* active `brand_type_id IS NULL` rows in the same statement — an immediate `idx_model_single_active_generic` violation, caught by `SqliteEquipmentServiceTest.renameModelOnTheGlobalGenericModelStaysGlobalAndDeprecatesTheOldRow` on first run. Fixed by deprecating the old row *first*, then creating/reactivating the replacement — safe for the ordinary scoped-model case too, since name-keyed uniqueness never cared about ordering to begin with.
- **"At least one must always exist" is an app-layer guarantee, not a DB constraint** — no standard SQL constraint (`CHECK`/`UNIQUE`/FK) can express "this table must never have zero rows matching X," and a trigger was rejected as unnecessary complexity (two engines' worth of trigger code, no live SQL Server instance anywhere in this project's test infrastructure to validate the T-SQL version against) for a rule already satisfied by construction: the only code path that ever changes the row's active status (`renameModel`) always creates/reactivates a replacement as part of the same call that deprecates the old one. `removeModel()` closes the one gap that could bypass that — it refuses outright (`IllegalArgumentException`) when the target is the global row, mirroring the existing "'Genérico / Otro' brand can't be deleted" guard on `removeBrand()`.
- **Seed label is config-driven, one-shot only — not live-synced.** `AppConfig.CatalogConfig.genericLabel` (`app-config.json`'s new `catalog.genericLabel`, default `"Genérico / Otro"`) is read only the first time the row is created (`DatabaseService.insertDefaultData()`/`RemoteDatabaseService`'s migration, both via a duplicated `resolveGenericLabel()` per the no-shared-abstraction convention). Deliberately **not** re-read and reapplied on every startup — doing so would let editing a config file silently overwrite the row an admin may have already renamed through the normal catalog UI, the first place in this app config would have been able to clobber admin-made data. To actually rename it later, use the ordinary admin-gated "Editar" flow on the Model list, same as any other catalog row — and because identification is structural (not name-based), renaming it needs no special-casing at all. This also preserves the existing "old notes render what was true when they were made" guarantee: renaming deprecates the old row and creates/reactivates a new one (the standard `deprecated`-flag mechanism), so a historical `NOTE_ITEM.model_id` still resolves to the old label, while new items pick up the new one.
- **Migration for pre-existing databases** — `DatabaseService.migrateGenericModelSchema()` (SQLite) / `RemoteDatabaseService`'s mirror, both guarded by checking whether `brand_type_id` is still `NOT NULL` (no-op once already migrated, including on a brand-new install, which gets the nullable column straight from `CREATE TABLE`): promotes one existing per-link `'Genérico / Otro'` row into the new global row (preserving its id, so anything already pointing at exactly that row needs no repointing), re-points every other `NOTE_ITEM.model_id` that referenced a *different* per-link copy onto it, then deprecates (never deletes) those now-unused rows. SQLite can't relax a `NOT NULL` constraint via `ALTER TABLE`, so this uses the same "build the new table under a temp name, `DROP` the old one (never renamed), `RENAME` the temp table into place" recipe already established for the Catalog-FK redesign — renaming `MODEL` itself first would trigger the same SQLite auto-rewrites-other-tables'-schema gotcha documented there, since `SN_VALIDATION`/`NOTE_ITEM` both say `REFERENCES MODEL(id)`. SQL Server supports `ALTER TABLE ... ALTER COLUMN ... NULL` natively, so its version skips the rebuild entirely.
- **`CatalogMigrationTool.migrateModels()` updated** to read `brand_type_id` via `rs.getObject(...)` (not `getInt`, which silently returns `0` for SQL NULL) and, when null, match/insert on the remote via `brand_type_id IS NULL` directly instead of remapping through the `linkIds` map — previously this would have skipped the global row entirely as if its (nonexistent) link had failed to migrate.
- **Tests**: `SqliteEquipmentServiceTest` gained `globalGenericModelIsOfferedForEveryBrandAndTypeCombination`, `removeModelRefusesToDeleteTheGlobalGenericModel`, `renameModelOnTheGlobalGenericModelStaysGlobalAndDeprecatesTheOldRow` (the one that caught the ordering bug above); two pre-existing tests (`addModelRejectsDuplicateWithinSameBrandAndType`, `renameTypeClonesEveryLinkedBrandsActiveModelsToTheNewTypeId`) had assertions updated since they depended on the old per-link auto-creation that no longer happens. `DatabaseServiceMigrationTest.migrateGenericModelSchemaCollapsesPerLinkGenericModelsIntoASingleGlobalRow` exercises the real SQLite rebuild-and-consolidate migration end to end. `CatalogMigrationToolTest.migratesGlobalGenericModelWithoutABrandTypeLink` covers the null-`brand_type_id` migration path, including idempotent re-run.

**Follow-up, same day — stray pre-existing `BRAND_TYPE_LINK` and Base de Datos rendering.** Verified against the user's real `data/noteapp.db` after deploying this: the global generic *model* row was correctly consolidated, but a `BRAND_TYPE_LINK` from before 2026-07-23 (back when `ItemDialogController.onBrandSelected()` used to lazily link the generic brand the first time it was picked for a given type) was still sitting there, now empty of models. Left alone, that link made `getBrandsForType()` return the generic brand via its real `JOIN` for *that one type only* — sorted alphabetically among real brands — while every other type only saw it via `ItemDialogController`'s client-side synthesis (always appended last), a real per-type rendering inconsistency the user caught by noticing it in Base de Datos.
- `SqliteEquipmentService.addBrandForType()` now no-ops (skips `ensureBrandTypeLink`) when the brand being added is the generic one — closes the actual source, since Base de Datos' own "Nueva marca" dialog calls this same method and could have recreated the link.
- `DatabaseService.cleanupStrayGenericBrandLinks()` / `RemoteDatabaseService`'s mirror (new, runs unconditionally every startup, cheap and idempotent): deletes any `BRAND_TYPE_LINK` for the generic brand that has zero `MODEL` rows under it; a link that still has a genuinely different, deliberately-added model is left alone rather than risking an orphan.
- `DatabaseSectionController.refreshBrandsForType()` now synthesizes the generic brand into every type's list (same pattern as `ItemDialogController.onTypeSelected()`), instead of only showing it for whichever type(s) happened to have a real link.
- **Second follow-up, same day** — two more Base de Datos consistency gaps, both direct user reports after the fix above landed: (1) the generic *model* still sorted alphabetically among real models in `listModels` (`getModelsForBrandAndType()`'s SQL orders everything by name together) instead of sitting last like the generic *brand* already does — `refreshModelsForBrandType()` now pulls it out and re-appends it at the end, mirroring `refreshBrandsForType()`. (2) Neither `listBrands` nor `listModels` had any custom cell styling at all, unlike `ItemDialogController`'s combo boxes (italic, `#94a3b8` grey, via `GENERIC_STYLE`) — `DatabaseSectionController` gained its own `GENERIC_STYLE` constant (duplicated, per convention) and a shared `applyGenericCellStyle(ListView<T>)` helper wired onto both lists in `initialize()`, so the generic entry now reads visually the same way in Base de Datos as it does in the note-generation Item dialog. No divider/separator line was added — only the italic styling was requested.
- **Tests**: `SqliteEquipmentServiceTest.addBrandForTypeDoesNotLinkTheGenericBrand`, `DatabaseServiceMigrationTest.cleanupStrayGenericBrandLinksRemovesOnlyEmptyLinks` (covers both the empty-link-removed and model-still-attached-link-preserved cases, plus idempotent re-run). `DatabaseSectionController` itself has no dedicated test (same "`initialize()` reaches `ServiceLocator` directly" gap already accepted elsewhere in this file for `MainController`/`HistoryController`) — verified by `mvn clean compile`/`mvn test` (full suite green) and a dry-run query against the user's actual `data/noteapp.db` confirming the exact stray link it would remove. Full suite: 287 tests passing (280 pre-existing + 7 new).

### Model stock (`MODEL_STOCK`) — per-(Type,Brand) rollups in Base de Datos (2026-07-29)

New table `MODEL_STOCK(brand_type_id, model_id, stock)`, PK `(brand_type_id, model_id)` — **not**
a plain `MODEL.stock` column, specifically because the single shared global "Genérico / Otro"
`MODEL` row (`brand_type_id IS NULL`, see above) is reused across every Type+Brand it's offered
under, and stock needs to be tracked independently per (Type,Brand) usage of it, not as one shared
number. For a normal (non-generic) model, `brand_type_id` is redundantly the same value the
model's own row already carries — one natural row, functionally identical to a plain column, just
expressed as a join.

- **`cleanupStrayGenericBrandLinks()` updated (both engines)** — this method (runs every startup,
  see above) used to delete a generic-brand `BRAND_TYPE_LINK` whenever it had zero `MODEL` rows;
  it now also checks `MODEL_STOCK` and skips deletion if a stock row exists there too, or a
  legitimately-stocked generic-brand link would get silently deleted on next startup.
- **Rename-carry-forward (real gap found while writing tests, not part of the original design) —
  a direct model rename and the Type/Brand rename cascade need *different* carry-forward logic.**
  `SqliteEquipmentService.renameModel()`/`cascadeAfterTypeOrBrandRename()` deprecate an old `MODEL`
  row and create/reactivate a *different* row id on any real rename; since `MODEL_STOCK`'s PK
  includes `model_id`, both cascades would silently orphan existing stock unless carried forward.
  `carryForwardModelStock(oldModelId, newModelId)` (plain rename — the model's own `brand_type_id`
  never changes) and `carryForwardModelStockAcrossLink(oldLinkId, oldModelId, newLinkId, newModelId)`
  (Type/Brand cascade — the scope itself changes too) are **not interchangeable**: a first attempt
  used the plain-rename version for the cascade too, which left stock keyed to the now-stale
  `oldLinkId` — caught immediately by `renamingTypeCarriesStockForwardThroughCascade`, a new test
  written specifically because this exact interaction wasn't obvious from the design alone.
- **`IEquipmentService`** gained `getModelStock(modelId, brandId, typeId)`/`setModelStock(...)` plus
  three batched rollup reads — `getStockTotalsByType()`/`getStockTotalsByBrandForType(typeId)`/
  `getStockTotalsByModelForBrandAndType(brandId, typeId)`, each returning a `Map<Integer,Integer>`
  computed with **one query per list-refresh, not one per row** — implemented in
  `SqliteEquipmentService` (the Model-level rollup mirrors `getModelsForBrandAndType()`'s existing
  UNION-with-global-row shape), `CachingEquipmentService` (the file's two existing fixed templates),
  and `MockEquipmentService` (new in-memory `List<int[]>`, mirroring `typeBrands`'s existing shape).
  **Known, deliberately out-of-scope gap**: `MockEquipmentService.getModelsForBrandAndType()`
  doesn't union in the global generic model at all (a pre-existing gap, unrelated to this feature) —
  Mock's stock implementation follows that same narrower scoping as-is, not fixed here.
- **`DatabaseSectionController`**: the old `applyGenericCellStyle()`/`GENERIC_STYLE`-only cell
  factory (Brands/Models only) was replaced by a single `applyCatalogCellFactory(ListView<T>,
  Function<T,Integer> stockLookup)` applied to all three cascading lists (Types too, which
  previously had no custom cell factory at all — just `toString()`), from a `Map` fetched once per
  `refreshTypes()`/`refreshBrandsForType()`/`refreshModelsForBrandType()` call. `openAddModelDialog()`
  gained a "STOCK INICIAL" field (default `0`); a new dedicated
  `openEditModelDialog(EquipmentModel, brandId, typeId)` (name + stock together) replaces
  `handleEditModel()`'s previous routing through the shared `openRenameDialog()` — same "own
  dialog when an entity needs more than a name" precedent `openEditTypeDialog()` already set for
  the `requires_serial` flag. Brand/Provider/Sede keep using `openRenameDialog()` unaffected.
  **A rename resolves the target model id fresh from the catalog by name** before writing stock
  (not `model.getId()`, which may now point at a deprecated row after `renameModel()` swaps ids) —
  same pattern `openAddModelDialog()` already uses to resolve a newly-created model's id, since
  `addModel()`/`renameModel()` don't return one directly.
  **Redesigned into a real two-column layout, same day, after direct user feedback** — the
  original `item.toString() + " · Stock: N"` concatenated-text approach was replaced with a proper
  two-`Label` row (`lblName` left, `lblStock` right-aligned via `-fx-alignment: CENTER_RIGHT` +
  `minWidth="50"`, spaced apart by a `Region` with `HBox.setHgrow(ALWAYS)`) set as the cell's
  `graphic`. Each of the three `ListView`s gained a plain sibling `HBox` header row ("NOMBRE" /
  "STOCK", padded to roughly line up with `.modern-list .list-cell`'s own `7 12` padding) directly
  above it in FXML — since it's outside the `ListView`'s own scrollable viewport, it stays fixed
  while the list scrolls, with no extra wiring needed. **Selected-row text color is now handled
  manually** (a `selectedProperty()` listener calling `refreshTextStyle()`) since `.modern-list
  .list-cell:filled:selected`'s CSS `-fx-text-fill` only affects a `Cell`'s own text, not an
  arbitrary graphic's child `Label`s — mirrors the existing dark/light + generic-italic states the
  plain-text version used to get for free from CSS.
- **Second follow-up, same day — header right-alignment, value styling, and an in-app modify-stock
  action, all from one direct user feedback pass.**
  - **The "STOCK" header label itself wasn't right-aligned to match the value column below it** —
    the previous fix right-aligned `lblStock` (the cell's own value `Label`), but the sticky
    `HBox` header row above each `ListView` still had its "STOCK" `Label` left/default-aligned, so
    the column header visually sat out of line with the numbers under it. Fixed by adding
    `alignment="CENTER_RIGHT"` (alongside the existing `minWidth="50" prefWidth="50"
    maxWidth="50"`) to all three header rows' STOCK `Label`s in `DatabaseSectionView.fxml`, so the
    header's own box now matches the cell's `lblStock` box exactly, not just its width.
  - **Stock values are now always bold, green when `> 0`, red otherwise** — `refreshTextStyle()`'s
    `lblStock` styling no longer depends on selection/generic state (unlike `lblName`, which still
    needs to react to both): `"-fx-font-weight: bold; -fx-text-fill: " + (stock > 0 ? "#22c55e" :
    "#ef4444") + ";"`, applied unconditionally. Gives an at-a-glance signal for "needs restocking"
    across all three cascading lists without opening any dialog.
  - **A real ListCell layout gotcha, caught while making the alignment actually hold**: a
    `ListCell`'s `graphic` is **not** automatically stretched to the cell's rendered width the way
    a plain `HBox`'s direct children are (`VBox`/`HBox`'s own `fillWidth` default doesn't apply
    here) — so the invisible `Region` spacer (`HBox.setHgrow(ALWAYS)`) had nothing real to grow
    into, and `lblStock` never actually reached the list's true right edge despite its own
    right-alignment being correct in isolation. Fixed with
    `row.prefWidthProperty().bind(widthProperty().subtract(24))` on the cell's root `HBox` (24 =
    `.modern-list .list-cell`'s own `7 12` padding, both sides) — forces the row to actually claim
    the cell's full available width first, so the spacer has real space to distribute.
  - **In-app stock modification, added per direct user request** ("let's add a way to modify
    stock in-app... add a modify stock button") — a new "Stock" button in the Models list footer
    (`DatabaseSectionView.fxml`, between "Editar" and "Eliminar"), wired to
    `handleModifyStock()`/`openModifyStockDialog(EquipmentModel, brandId, typeId)` — a minimal
    stock-only dialog (no name field), separate from `openEditModelDialog()` (name + stock
    together, used by the Model list's own "Editar" button) since a technician adjusting stock day
    to day shouldn't have to go through a rename-capable dialog to do it.
- **Third follow-up — Type/Brand rollup counters went stale after a stock edit, and the STOCK
  header still didn't line up.** Two direct user reports, fixed together.
  - **Stale rollups**: `openModifyStockDialog()`/`openEditModelDialog()`'s save handlers only
    called `refreshModelsForBrandType(brandId, typeId)` after `setModelStock(...)` — the Type- and
    Brand-level totals (which sum every Model under their scope) never got refetched, so
    `listTypes`/`listBrands` kept showing the pre-edit numbers until the technician switched away
    from and back to the current Type (re-triggering the selection-change listeners that call
    `refreshTypes()`/`refreshBrandsForType()`), or restarted the app. Fixed with a new
    `refreshStockRollupsOnly(typeId)` — refetches `getStockTotalsByType()`/
    `getStockTotalsByBrandForType(typeId)` and re-applies `applyCatalogCellFactory(...)` to
    `listTypes`/`listBrands` with the fresh maps, **without** calling `setItems(...)` on either
    list. This matters: `refreshTypes()`/`refreshBrandsForType()` both reset `listBrands`'/
    `listModels`' items and selection outright — correct for actual Type/Brand *navigation*, but
    wrong here, since the technician is mid-browsing a Model, not switching Types. Re-applying just
    the cell factory forces JavaFX to redraw the existing (unchanged) items with the new numbers,
    leaving selection/scroll position untouched. Called from both save handlers, right after
    `refreshModelsForBrandType(...)`.
  - **Header still not aligned**: fixed with the scrollbar-spacer theory above — plausible, and
    genuinely a real (if minor) gap, but a user screenshot the same day proved it wasn't the actual
    bug. The screenshot showed "NOMBRE"/"STOCK" sitting right next to each other at the header's
    far *left*, nowhere near the right-aligned "0"/"6" values below — not a small few-pixel
    offset, a completely un-grown header. **Root cause**: `HBox.hgrow="ALWAYS"` was set directly
    on the "NOMBRE" `Label` (not on a separate spacer `Region`, unlike the proven-working
    title-row pattern right above it in the same FXML file — `TIPOS`/`+ Agregar` correctly stretch
    apart via `<Region HBox.hgrow="ALWAYS"/>`). `hgrow` only tells the parent `HBox` how to
    *allocate* leftover space to a child — it does **not** override that child's own `maxWidth`. A
    `Region`/`Pane` subclass has an effectively unbounded computed max width, so a spacer `Region`
    readily consumes whatever space `hgrow` hands it; a `Labeled` (`Label`, `Button`, ...) computes
    its `maxWidth` from its own *content* by default, so `HBox.hgrow="ALWAYS"` on a bare `Label`
    reserves space the `HBox` layout algorithm can't actually give it (capped by the label's own
    max width) — the leftover space is simply left empty *after* the label instead of being
    granted to it, so every following sibling (`STOCK`) renders immediately after the label's true
    (small) content width, exactly matching the screenshot. Fixed by replacing
    `HBox.hgrow="ALWAYS"` on the "NOMBRE" `Label` with a plain `<Region HBox.hgrow="ALWAYS"/>`
    between "NOMBRE" and "STOCK" in all three header rows — the same working pattern already used
    elsewhere in this exact file, not a new idiom.
  - **The scrollbar-spacer fix above is still correct and still in place** — it addresses a real,
    separate few-pixel gap that only shows up once a list has enough rows to display a scrollbar;
    it just wasn't the (much larger) bug the screenshot actually showed. Both fixes are needed for
    full pixel alignment: the `Region` spacer gets "STOCK" to the list's true right edge, and
    `bindHeaderScrollbarSpacer()` keeps it there once a scrollbar appears.
  - **No test added for either** — same "impractical to exercise in isolation" gap already
    accepted elsewhere in this file for `DatabaseSectionController`'s other UI-driven behavior
    (`initialize()` calls `ServiceLocator` directly; both dialogs build their `TextField`/`Region`
    nodes dynamically in Java, not declared in FXML). Verified via `mvn compile`/`mvn test` (full
    suite green) plus a user-provided screenshot confirming the fix visually.
- **Fourth follow-up — stock numbers blended into the selected-row background.** Direct user
  report with a screenshot: the green (`#22c55e`) in-stock color had low contrast against
  `.modern-list`'s selected-row background (`#6ebdb0`, a light teal-green), making the number hard
  to read the moment its row was selected. Changed to plain black (`#000000`) for `stock > 0`,
  per explicit user request over trying a different shade of green — black reads clearly against
  both the white unselected background and the teal selected one. Red (`#ef4444`, out of stock)
  already had enough contrast against both and was left unchanged.
- **Fifth follow-up — the "NOMBRE"/"STOCK" header row removed entirely**, explicit user decision
  ("I ended up not liking it") after seeing it fixed and live. This supersedes every header-row-specific
  fix in the three follow-ups above (the alignment fixes, the scrollbar spacer, all of it) — that
  code no longer exists, kept here as historical record only, not something to re-derive if a
  header is ever reintroduced. Removed: the three `HBox` header rows (`DatabaseSectionView.fxml`),
  their `spacerTypesScrollbar`/`spacerBrandsScrollbar`/`spacerModelsScrollbar` `fx:id`s, the
  corresponding `@FXML Region` fields, `bindHeaderScrollbarSpacer()` and its `LIST_SCROLLBAR_WIDTH`
  constant, and the `initialize()` calls wiring them up — all dead code once the header markup was
  gone, deleted rather than left unused per this codebase's no-dead-code convention (including the
  now-unused `ScrollBar`/`Bindings` imports). **The cell-level stock column itself is unaffected**
  — `applyCatalogCellFactory()`'s two-`Label` row (name left, stock right-aligned, bold,
  black/red-by-value) still renders exactly as before; only the sticky label row that used to sit
  above each `ListView` is gone. No test changes needed — no test asserted the header row's
  presence. Full suite: still 340 tests passing.
- **`CatalogMigrationTool.migrateModelStock()`** — added after `migrateModels()`, same
  check-then-insert-or-update shape as every other `migrate*()` method (otherwise moving a
  locally-built catalog to a remote database would silently drop all stock numbers).
- **Tests**: `SqliteEquipmentServiceTest` (round-trip get/set, each rollup level, the global-generic
  union case, both rename-carries-forward cases above, lazy `BRAND_TYPE_LINK` creation for the
  generic brand's first stock entry), `MockEquipmentServiceTest`, `CachingServiceTest`
  (primary-fail/local-fallback), `CatalogMigrationToolTest` (schema copy + new migrate test, plus
  an orphaned-row-skipped case), `DatabaseServiceMigrationTest` (table-exists assertion, plus a new
  `cleanupStrayGenericBrandLinksSkipsLinkWithOnlyStockNoModels` case). Built second in this
  session's 4-feature batch, right after [A/F format simplification](#af-format-simplification-2026-07-29)
  below — full suite: 320 tests passing after this feature.

**Schema migration pattern**: `CREATE TABLE IF NOT EXISTS` silently no-ops on a database that already has the table from an older schema version — a column added after initial release will **never** land on an existing `data/noteapp.db` unless explicitly migrated. `DatabaseService.migrateSchema()` (called from `initialize()`, after all `CREATE TABLE` calls) runs idempotent `ALTER TABLE ... ADD COLUMN` statements via `addColumnIfMissing()`, which swallows the "column already exists" `SQLException` (SQLite has no `ADD COLUMN IF NOT EXISTS`). `RemoteDatabaseService.ensureSchema()` has no such native clause in T-SQL, so it guards every `ALTER TABLE ... ADD` with a Java-side `columnExists()` check instead (queries `information_schema.columns`, which SQL Server supports natively). **Any new column on an existing table must be added to `migrateSchema()`/`ensureSchema()`, not just the `CREATE TABLE` block** — forgetting this was the exact cause of a `SQLException` when previewing History notes after the Falla/responsible-person columns were added but not migrated.

See `docs/database.md` for full ERD.

### NOTE_ENTREGA_DEVOLUCION and NOTE_REPORT normalized further (2026-07-30)

A fresh schema-normalization audit (explicit user request — "review the database schema for full
relational normalization," a standing preference, see [[feedback_db_normalization]] in memory) found
two more instances of the exact "doesn't apply to this row" pattern the `NOTE_ITEM` 5-table split
(above) already fixed, both missed at the time because they predate that split or were added after
it without being revisited:

- **`NOTE_ENTREGA_DEVOLUCION`** mixed all 4 profile types it's used by (Entrega, Devolución, Fin de
  Contrato, Préstamo) on one table — `failure_cause`/`failure_details` were only ever populated for
  Devolución+Falla, `area_evento` only for Préstamo (and even there, optional). Split into
  `NOTE_DEVOLUCION_FALLA` (`note_report_id` PK/FK → `NOTE_ENTREGA_DEVOLUCION`, `failure_cause`
  `NOT NULL`, `failure_details` nullable — same "row exists only when the dimension applies, but a
  genuinely optional attribute within that row can still be NULL" shape as `NOTE_ITEM_GLPI_TRACKING`'s
  own `rejection_reason`) and `NOTE_PRESTAMO_AREA_EVENTO` (`note_report_id` PK/FK, `area_evento`
  `NOT NULL` — the row itself simply doesn't exist when Área/Evento was left blank). The base
  `NOTE_ENTREGA_DEVOLUCION` table is left with just `note_report_id`/`user_name`/`user_dni`/
  `user_email`/`motivo` — `motivo` stays, since every profile type that uses this table has one.
- **`NOTE_REPORT.rejection_reason`** (added 2026-07-29 alongside the approval workflow, uncommitted
  at the time of this audit) is null unless `approval_status = 'RECHAZADO'` — the same pattern,
  caught immediately since it was brand new. Split into `NOTE_REPORT_REJECTION` (`note_report_id`
  PK/FK → `NOTE_REPORT`, `rejection_reason NOT NULL`) — a row exists only for a note that's
  actually been rejected. `approval_status` itself stays directly on `NOTE_REPORT` — every note has
  one, applicable uniformly, unlike `rejection_reason` which only ever means something for one of
  its three values.
- **Both decisions were explicitly confirmed with the user before implementing** (per this
  project's own "never make schema changes without asking first" rule) — presented as findings with
  options (split now / flag only / leave as-is) rather than assumed; the user chose the full split
  for both.
- **No Java-side ripple at all** — `NoteReport.java`'s plain `failureCause`/`failureDetails`/
  `areaEvento`/`rejectionReason` fields, every controller, `NoteGenerationService`, and all 5 HTML
  templates needed zero changes, exactly the same "normalization is a DB-layer concern" outcome
  already observed for the `NOTE_ITEM` split and the catalog-FK redesign — only `SqliteHistoryService.java`
  and the two DDL files (plus their duplicated test schema copies) ever touched these columns
  directly.
- **`SqliteHistoryService` changes**: `insertProfileDetail()`'s ENTREGA_DEVOLUCION branch now does
  a 5-column base insert, then a conditional insert into `NOTE_DEVOLUCION_FALLA` (only when
  `failureCause` is non-blank) and `NOTE_PRESTAMO_AREA_EVENTO` (only when `areaEvento` is
  non-blank). `updateNoteApprovalStatus()` now does a plain `UPDATE` for `approval_status`, then a
  check-then-insert-or-update-or-delete for `rejection_reason` — the same shape
  `updateItemGlpiStatus()`/`updateItemReturnStatus()` already use (delete the row when
  approving/re-approving with a blank reason, upsert it when rejecting with a real one). `getById()`
  gained 3 more `LEFT JOIN`s (`NOTE_REPORT_REJECTION`, `NOTE_DEVOLUCION_FALLA`,
  `NOTE_PRESTAMO_AREA_EVENTO`) — same "SQL gains JOINs, Java row-mapping code doesn't change"
  pattern as the catalog-FK redesign; result-column names (`rejection_reason`, `failure_cause`,
  `failure_details`, `area_evento`) are unchanged. `LIST_BASE_SQL` gained the
  `NOTE_REPORT_REJECTION` join too (History needs to show the rejection reason without a second
  query per row).
- **Migration mechanics — much simpler than the `NOTE_ITEM`/`MODEL` rebuilds above**: none of the 4
  retired columns are referenced by FK from any other table, so both engines use a plain
  backfill-then-`DROP COLUMN` (SQLite 3.35+ supports `DROP COLUMN` natively; SQL Server always
  has), no table-rename/rebuild dance needed. `migrateRejectionReasonSchema()`/
  `migrateEntregaDevolucionSplitSchema()` (duplicated per this codebase's no-shared-abstraction
  convention, in both `DatabaseService.java` and `RemoteDatabaseService.java`) each guard on
  `columnExists()` (no-op once migrated, including on a brand-new install, which never creates the
  retired columns at all) and use `INSERT OR IGNORE`/`NOT EXISTS` for the backfill so a retry after
  a partially-failed `DROP COLUMN` never double-inserts. `failure_cause`/`failure_details`/
  `area_evento`/`rejection_reason` are deliberately **not** re-added via `addColumnIfMissing()`
  anymore — same "stop re-adding a retired column" precedent already established for
  `NOTE_REPORT.sede`.
- **Tests**: `DatabaseServiceMigrationTest` gained
  `migrateSchemaBackfillsRejectionReasonIntoOwnTableAndDropsColumn()` and
  `migrateSchemaBackfillsFailureAndAreaEventoIntoOwnTablesAndDropsColumns()` — both seed a
  pre-existing database with real data in the old columns (not just empty tables) and assert it
  lands correctly in the new tables, the old columns are gone, and an unrelated row/column is
  untouched; the existing `migrateSchemaAddsPrestamoColumnsToAPreExistingDatabase` test's stale
  assertions (which expected `area_evento`/`rejection_reason` to land back on the old tables) were
  corrected to expect the new tables instead. `SqliteHistoryServiceTest`'s own duplicated schema
  copy updated to match (same "every new column/table needs its test schema copies updated too"
  precedent already established elsewhere in this file) — no behavioral test changes needed there,
  since `NoteReport`'s Java-side shape never changed. Full suite: 344 tests passing (342 pre-existing
  + 2 new).

### App-layer length-cap audit against SQL Server `NVARCHAR(n)` bounds (2026-07-30)

Direct user follow-up to the schema-normalization pass above, after being told SQL Server bounds
most free-text columns but SQLite never enforces a declared column length at all — meaning the
*only* real enforcement for a local-only installation is whichever `TextFormatter` cap the input
widget happens to have. Audited every typed free-text field in the app against its target SQL
Server column and found **7 fields with no cap matching their column's bound at all** — some had
no length restriction of any kind, one (`ProviderNoteController.txtCuit`) had no `TextFormatter`
whatsoever. All fixed with a plain `length() <= N ? change : null` cap, same mechanism already used
throughout this codebase (Detalles/Observaciones Generales/regex/etc.) — no new validation
machinery introduced.

- **`ItemDialogController.txtSerial`** → `NOTE_ITEM_ASSET.serial_number` `NVARCHAR(255)`. New
  `SERIAL_MAX_LENGTH = 255`. `txtAF` (the derived A/F display field, `editable="false"` since the
  2026-07-29 A/F simplification) is **not** separately capped — nothing is ever typed into it
  directly, and since it's just `prefix + separator + serial`, with prefix/separator both short
  config values, capping `serial` already keeps `a_f` within its own `NVARCHAR(255)` bound in every
  realistic case.
- **`UserNoteController.txtUserName`** and **`PrestamoNewLoanController.txtUserName`** (its own
  duplicate, per this codebase's no-shared-abstraction convention) → `NOTE_ENTREGA_DEVOLUCION.user_name`
  `NVARCHAR(255)`. Both gained `USER_NAME_MAX_LENGTH = 255`. `UserNoteController`'s copy already had
  a letter+space-only character restriction — the length check was folded into the same formatter,
  ahead of the character-set check. `PrestamoNewLoanController`'s copy had no restriction of any
  kind (it doubles as an AD search-by-name box), so only the length cap was added, deliberately not
  the letter-only restriction — adding that would be new, unrequested behavior on a field this
  codebase never constrained that way.
- **`ProfileController.txtProfileName`** → `NOTE_REPORT.technician_name` `NVARCHAR(255)`. New
  `PROFILE_NAME_MAX_LENGTH = 255`, folded into the existing letter+space formatter the same way.
- **`ProviderNoteController.txtProviderResponsibleName`** → `NOTE_PROVEEDOR.responsible_name`
  `NVARCHAR(255)`; **`txtCuit`** → `NOTE_PROVEEDOR.cuit` `NVARCHAR(255)` (this one had **no**
  `TextFormatter` at all before — not even a character restriction). New
  `RESPONSIBLE_NAME_MAX_LENGTH`/`CUIT_MAX_LENGTH = 255`.
- **`DatabaseSectionController`'s 8 catalog-name dialogs** (Add/Edit Type, Add Brand, Add/Edit
  Model, Add Provider, Add Sede, and the shared `openRenameDialog()` used by Brand/Model/Provider/Sede
  renames) → `TYPE`/`BRAND`/`MODEL`/`PROVIDER`/`SEDE.name`, all `NVARCHAR(255)`. None had any cap
  before. One shared `CATALOG_NAME_MAX_LENGTH = 255` constant + a `catalogNameFormatter()` factory
  method (returns a **new** `TextFormatter` instance per call — a `TextFormatter` can only ever be
  attached to one control at a time, so a single shared instance across 8 `TextField`s would have
  silently detached from all but the last) — one shared helper here, not duplicated 8 times, since
  every use is within this same class rather than across controllers.
- **Fields deliberately left unchanged, and why**: `ProfileController.txtProfileEmail` is never
  written to any DB column (technician identity is session-only — see
  [Technician identity](#technician-identity--session-only-sourced-from-windowsad) — only
  `display_name_pref` persists, in local-only `APP_SETTINGS`; `sede_pref` no longer exists at all
  as of 2026-07-30, see [Role-based permissions (RBAC)](#role-based-permissions-rbac-a-superadmin-tier-and-sede-scoped-admin-actions-2026-07-30)); `UserNoteController`
  has no email field at all (`user_email` is populated straight from the AD lookup result, never
  typed); DNI fields (`txtUserDni`, `txtProfileDni`, `txtProviderResponsibleDni`) were already
  capped at 8 digits, far under any column's bound; `motivo`/`failure_cause` are ComboBox
  selections from config lists, never typed (see the existing "DB-side length bounds" entry above
  for why those two specifically have no `TextFormatter`).
- **Tests**: `UserNoteViewFxmlTest`/`PrestamoNewLoanViewFxmlTest`/`ProfileViewFxmlTest` each gained
  a length-cap regression test (real FXML load + reflection-obtained field, same
  `observationsFieldRejectsInputBeyond300Characters()` precedent). New `ProviderNoteViewFxmlTest.java`
  (no prior test loaded `ProviderNoteView.fxml`) — `initialize()` eagerly calls
  `refreshProviders()` → `ServiceLocator.getInstance().getEquipmentService().getAllProviders()`, so
  this needed `ServiceLocator.getInstance().setEquipmentService(new MockEquipmentService())` in
  setup, same precedent as `SettingsViewFxmlTest`/`DatabaseSectionViewFxmlTest`; covers both new
  caps (`txtProviderResponsibleName`, `txtCuit`). **`ItemDialogController.txtSerial` and
  `DatabaseSectionController`'s 8 catalog-name fields have no dedicated test** — same two
  already-accepted gaps in this file: `ItemDialogController.initialize()` calls `ServiceLocator`
  directly (same reason `txtObs`'s own cap has no test), and every one of
  `DatabaseSectionController`'s catalog dialogs is a real, modally-shown `Stage`
  (`buildAndShow()` → `stage.showAndWait()`) — this suite has a standing rule against leaving a
  `Stage.show()`-driven test in the permanent run (proven flaky, see the Falla-persistence
  investigation elsewhere in this file). Verified via `mvn compile`/`mvn test` (full suite green)
  and direct code review instead. Full suite: 350 tests passing (344 pre-existing + 6 new).

### Edge-case / malformed-input audit (2026-07-30)

Direct user follow-up to the length-cap audit above — checked for input that could cause an
unhandled runtime exception rather than just an unbounded value, starting from a concrete example
the user gave (negative stock).

- **`SqliteEquipmentService.setModelStock()` now rejects `stock < 0`** (`IllegalArgumentException`,
  Spanish message, same "exception-for-user-facing-validation" pattern `addType`/`removeBrand`
  already use). The UI's own `tfStock` `TextFormatter` (`\d{0,9}`, no minus sign possible) already
  made this unreachable through the 3 Base de Datos dialogs today — this is a boundary check on the
  actual persistence method every caller goes through, not a UI-reachable bug fix. Deliberately
  **not** duplicated into `MockEquipmentService`, matching the established precedent that
  validation logic lives only in the real `SqliteEquipmentService` (confirmed by checking
  `MockEquipmentService.addType()`, which has no matching duplicate-name check either — Mock is a
  simpler in-memory stand-in, validation-specific behavior is tested against the real service).
  `CachingEquipmentService.setModelStock()` needed no change — it already calls
  `primary.setModelStock(...)` with no surrounding try/catch, so the exception propagates as a
  "fail loudly" write failure, same as every other primary-write error in that class.
- **A real crash risk found, not hypothetical**: `ItemDialogController.validateSnLength()`'s
  `sn.matches(regex)` had no protection against a syntactically invalid regex — `SettingsController`'s
  S/N Validation edit dialog let an admin save any string as `regex_pattern` with zero syntax
  checking. A bad pattern (unbalanced parens, an invalid quantifier, etc.) would compile fine as a
  *string* but throw `PatternSyntaxException` the moment any technician typed an S/N against that
  model — on every keystroke, since this method runs from `txtSerial`'s text-property listener.
  Fixed at both ends:
  - **`SettingsController.openEditDialog()`** (the S/N Validation "Editar" dialog) now calls
    `Pattern.compile(newRegex)` in a try/catch before saving — a new `lblRegexError` (red, inline,
    same "validate at the boundary" precedent as every other catalog dialog's error label) blocks
    the save and shows `PatternSyntaxException.getDescription()` if the pattern doesn't compile.
    This is the fix that actually matters — a bad pattern should never reach the database at all.
  - **`ItemDialogController.validateSnLength()`** now wraps `sn.matches(regex)` in its own
    try/catch, defensively — an installation that already had a bad pattern saved before the check
    above existed (or any other write path into `SN_VALIDATION`) must not crash S/N entry for
    every technician. Fails safe: treats a `PatternSyntaxException` as "pattern check skipped, not
    blocking" — same "fail safely, don't crash, don't block the user" precedent this project
    already applies to AD/GLPI/SMTP unreachability.
- **Tests**: `SqliteEquipmentServiceTest.setModelStockRejectsNegativeStock()` — the negative-stock
  guard is the only one of these three fixes with a feasible unit test. Neither regex fix has a
  dedicated test, both for reasons already established elsewhere in this file:
  `ItemDialogController`'s `initialize()` calls `ServiceLocator` directly (same reason `txtObs`'s/
  `txtSerial`'s own caps have no test) and reflection-seeding every field `validateSnLength()`
  touches (`cmbModel`, `containerAssetFields`, `chkSinSN`, `flowSnPattern`, `equipmentService`,
  ...) for one small catch block isn't a good size/value tradeoff; `SettingsController.openEditDialog()`
  builds `tfRegex`/`lblRegexError` dynamically and opens a real modal `Stage`
  (`showAndWait()`) — this suite's standing rule against `Stage.show()`-driven tests in the
  permanent run (see the Falla-persistence investigation elsewhere in this file) applies here too.
  Verified via `mvn compile`/`mvn test` (full suite green) and direct code review instead. Full
  suite: 351 tests passing (350 pre-existing + 1 new).

### `database/sqlserver/01-schema.sql` brought back in sync with `RemoteDatabaseService.ensureSchema()`

This hand-maintained script had drifted significantly — it had zero test coverage (no test loads
or runs it, unlike the seed scripts), so nothing caught it. It still reflected roughly the schema
as it stood right after the `NOTE_ITEM` 5-table split: missing `SEDE`, `USER_ROLE`, `MODEL_STOCK`,
`NOTE_REPORT_REJECTION`, `NOTE_DEVOLUCION_FALLA`, `NOTE_PRESTAMO_AREA_EVENTO`; missing every
`deprecated` column; `NOTE_ITEM`/`NOTE_PROVEEDOR` still on the pre-catalog-FK text-column shape;
`MODEL.brand_type_id` still `NOT NULL`; still creating the now-dead `NOTE_REPORT.glpi_synced` on
every fresh install; almost every free-text column still `NVARCHAR(MAX)` instead of its real
bound; `created_at`/`status_updated_at` still `NVARCHAR(MAX)` instead of `DATETIME2`.

Rewritten to mirror the current `ensureSchema()` line-by-line, in the same order: all 20
`CREATE TABLE` blocks (including the 3 `MODEL` indexes), then every migration step for an
already-running installation, translated faithfully — most as set-based `INSERT`/`UPDATE`
(idiomatic for a hand-run script), but the two truly row-by-row resolve-or-create migrations
(`NOTE_ITEM`/`NOTE_PROVEEDOR`'s text-to-FK conversion) kept as T-SQL cursors, matching the Java's
own per-row logic exactly rather than approximating it with a set-based query that might not
handle every edge case (e.g. the "Genérico / Otro" fallback resolution) the same way.

**Also fixed the same day, in `02-seed-equipment.sql.example`**: it still seeded a brand literally
named `'Generic'` — predating the rename to `'Genérico / Otro'`. Since `01-schema.sql`'s rename
only fires on a row still named `'Generic'`, running this seed script after `01-schema.sql` on a
fresh install would have created a stray duplicate `'Generic'` brand alongside the real fallback.
Fixed to seed `'Genérico / Otro'` directly.

**No test coverage added for `01-schema.sql` itself — a known, accepted gap, not an oversight.**
Unlike the seed scripts (`02-seed-equipment.sql.example`, `starter-template.sql.example`,
`demo-seed.sql`), which are plain portable `SELECT`/`INSERT`/`DELETE` and can run against a SQLite
stand-in for testing (see `SqlServerSeedSqlSnValidationTest`/`StarterTemplateSqlTest`/
`DemoSeedSqlTest`), `01-schema.sql` is now saturated with genuine T-SQL-only control flow
(`BEGIN`/`END`, cursors, `TRY`/`CATCH`, dynamic SQL via `sp_executesql`, `sys.indexes`/
`sys.tables`/`information_schema.columns`) that SQLite cannot parse at all — there is no
dialect-neutral stand-in this could run against, same "no live SQL Server instance anywhere in
this project's test infrastructure" limitation already documented repeatedly elsewhere in this
file. Verified instead by careful line-by-line transcription against the current, exact
`RemoteDatabaseService.ensureSchema()` Java source (re-read in full immediately before writing
this script) and a structural sanity check (`BEGIN`/`END`/`TRY`/`CATCH` block-count balance).
Existing test suite (351 tests) still green — nothing in `src/main/java` was touched by this pass.

### `APP_SETTINGS` keys in use

| Key | Encrypted | Purpose |
|-----|-----------|---------|
| `smtp_password` | AES (AppKeyEncryptionService) | SMTP sender password |
| `glpi_api_key` | AES (AppKeyEncryptionService) | GLPI REST API key |
| `ad_api_token` | AES (AppKeyEncryptionService) | AD API bearer token |
| `db_host` | No | Remote DB hostname |
| `db_port` | No | Remote DB port (default `5432`) |
| `db_name` | No | Remote DB database name |
| `db_username` | AES (AppKeyEncryptionService) | Remote DB username |
| `db_password` | AES (AppKeyEncryptionService) | Remote DB password |
| `display_name_pref:<username>` | No | Per-technician sidebar greeting-name preference (see [Technician identity](#technician-identity--session-only-sourced-from-windowsad)) — one row per technician username, not a secret |
| ~~`sede_pref:<username>`~~ | — | **Removed 2026-07-30** — Sede is no longer a self-service preference at all; it's read from the superadmin-assigned `APP_USER.sede_id` instead (see [Role-based permissions (RBAC)](#role-based-permissions-rbac-a-superadmin-tier-and-sede-scoped-admin-actions-2026-07-30)). Kept here, struck through, as a historical pointer — a pre-2026-07-30 install may still have stray rows under this key in `APP_SETTINGS`; nothing reads them anymore. |

### Equipment seed data — no longer auto-seeded (2026-07-14)

**Removed 2026-07-14**, per explicit user direction. `DatabaseService.seedEquipmentData()`/`seedHistoryData()` used to run automatically on first startup (when `TYPE`/`NOTE_REPORT` were empty, respectively), inserting a 132-row curated equipment catalog and 8 fictional demo history notes. A fresh install, or a deleted-and-recreated `data/noteapp.db`, now starts genuinely empty — schema only, plus the single always-present "Generic" brand (`insertDefaultData()`, unconditional, kept because it's infrastructural — the app's brand-less-item fallback logic expects it to exist — not sample/demo data). This means a technician can start loading real organizational data immediately with zero pre-existing "dirty" rows to work around or delete first.

The removed data wasn't deleted outright — it's preserved, unchanged, as `database/sqlite/demo-seed.sql` (see that file and `database/sqlite/README.md`), an optional script run on demand (`sqlite3 data/noteapp.db < database/sqlite/demo-seed.sql`) when actually wanted — e.g. before a demo. Both methods (and their private `insertReport`/`insertItem` helpers) were deleted from `DatabaseService.java` entirely, not just stopped from being called — no dead code, per this codebase's own convention, since the same data now lives in the SQL file.

**Rewriting the seed loop's Java logic as SQL wasn't a 1:1 transliteration** — two real bugs surfaced while writing and *testing* the translation (`DemoSeedSqlTest.java`, which actually executes the script against a real SQLite database rather than just eyeballing the SQL):
- The equipment catalog uses a `CREATE TEMP TABLE _seed_rows` staging table + `INSERT OR IGNORE ... SELECT DISTINCT ... FROM _seed_rows` per target table (`TYPE`/`BRAND`/`BRAND_TYPE_LINK`/`MODEL`), rather than 500+ individual `INSERT` statements — mirrors the original Java loop's per-row dedup behavior far more compactly and reliably.
- The 8 history notes' "skip if we've already seeded" guard originally checked, per note, whether *note 1's specific timestamp* already existed in `NOTE_REPORT` — which breaks if `NOTE_REPORT` had an unrelated pre-existing row that correctly blocked note 1 alone: notes 2-8 would then find note 1's timestamp genuinely absent and insert anyway. Fixed with a single `CREATE TEMP TABLE _should_seed_history AS SELECT (SELECT COUNT(*) FROM NOTE_REPORT) = 0 AS should_seed` computed once, up front, before any insert — every one of the 8 `NOTE_REPORT` inserts checks that same captured flag instead of re-deriving it. (The child-row guards — `NOT EXISTS (SELECT 1 FROM NOTE_ITEM WHERE note_id = NOTE_REPORT.id)` etc., needed for a genuine re-run where the notes already exist from a prior load — were correct as originally written and didn't need this fix.)
- **Tests**: `DemoSeedSqlTest.java` duplicates the schema DDL for an isolated temp database (same precedent as `SqliteHistoryServiceTest`'s own schema copy — `DatabaseService.DB_URL` is a hardcoded path, not swappable for a temp one) and actually runs the real `database/sqlite/demo-seed.sql` file against it, asserting row counts, referential correctness (`NOTEBOOK.requires_serial = 1`), idempotent re-runs, and the "don't mix fake notes into a database with real ones" guard. Nothing else in the build compiles or type-checks a `.sql` file, so this is the only thing that would catch drift between this script and the schema going forward.

For the remote database, the equivalent starting data lives in `desktop-app/database/sqlserver/02-seed-equipment.sql.example` — a **template only** (placeholder rows, same pattern as `app-config.json.example`), not the real catalog; see that folder's README for why real catalog/provider data must never be committed. See also [`CatalogMigrationTool`](#catalogmigrationtool--local-to-remote-catalog-migration) above for moving whatever ends up in the local SQLite catalog (real, demo, or a mix) to a remote database once one is available — no need to enter it twice.

**Historical note (2026-07-13, predates the above removal)**: the seed data used to also insert a real `"Otro / Genérico"` catch-all `MODEL` row for every brand-type link with no specific model, and 8 rows used `"Genérico"` as a placeholder `BRAND` — both removed at the time per the same "database should only ever contain real data" direction. `ItemDialogController`'s `"Genérico / Otro"` UI-layer fallback (see [below](#genérico--otro-fallback-footer-brandmodel-combos-in-itemdialogview)) already covers the same need without a fake DB row, and was unaffected by either that change or the later full removal.

**Follow-up, same day**: user asked for a genuine blank starting-point template (not filled with demo data, and initially asked for it as "a MySQL script" — the app doesn't use MySQL anywhere, turned out to just be loose terminology for "a SQL script"). Added `database/sqlite/starter-template.sql.example` — same `_seed_rows` staging-table technique as `demo-seed.sql`, but with a handful of clearly-marked example rows to replace rather than real/demo data, following the exact same committed-`.example`/gitignored-real-copy convention as `app-config.json.example` and `02-seed-equipment.sql.example` (`.gitignore` updated to match). Two things this template covers that neither existing seed script did:
- **S/N validation rules** — `database/sqlserver/02-seed-equipment.sql.example` explicitly defers these to the app's own UI; this template includes them (a second `_seed_sn_rules` staging table, delete-then-insert per resolved `MODEL.id` so re-running replaces a rule instead of duplicating it) since they're plain, non-secret, per-model data with no real reason to exclude from a starting-point script.
- **Explicitly scoped to exclude secrets/non-DB config** — user initially asked for "any other persisted configs" too, but investigating what's actually persisted where surfaced a genuine misunderstanding worth documenting: A/F format, SMTP host/port, GLPI/AD API URLs are **not** database-persisted at all (they're plain fields in `config/app-config.json`); the real secrets (SMTP password, GLPI API key, AD token, DB username/password) *are* persisted (encrypted, in `APP_SETTINGS`), but already have a dedicated, pre-existing mechanism for pre-configuring them before first run — `app-config.json`'s `defaults` section, via `utils.AppKeyEncryptionGenerator` (see "Pre-configuring default secrets" in the root `README.md`). Building a second, SQL-based way to set the same `APP_SETTINGS` rows would just be a competing path to the same result — deliberately left out of this template rather than duplicated.
- **A real bug caught by testing the translation, same as `demo-seed.sql`'s own history**: the first draft of the S/N validation section's `DELETE FROM SN_VALIDATION WHERE model_id IN (...)` resolved `BRAND_TYPE_LINK.id` instead of `MODEL.id` in its subquery (copy-paste leftover from the equipment-catalog section above it) — caught by `StarterTemplateSqlTest.java` actually loading the file and asserting the S/N rule resolves to the correct model via a join, not just checking a row count.
- **Tests**: `StarterTemplateSqlTest.java` — same approach as `DemoSeedSqlTest` (duplicated schema including `SN_VALIDATION`, which `demo-seed.sql`'s test schema doesn't need), run against the committed `.example` file directly (the real, gitignored `starter-template.sql` a user fills in doesn't exist in this repo, but the `.example` file's own placeholder rows are valid, loadable data, so testing it *is* exactly what a user copying and running it would experience).

**Second follow-up, same day**: user asked to reconcile two things noticed by directly comparing this new template against `02-seed-equipment.sql.example`.
- **`requires_serial` was auto-derived (`CASE WHEN type_name = 'NOTEBOOK'`) instead of settable per row**, unlike the Postgres-era template's explicit `requires_serial` staging-table column — meaning this template couldn't express "some other type should also always require S/N" without editing the `CASE` expression directly. Fixed by adding `requires_serial` as an explicit `_seed_rows` column (matching the other template exactly), set per example row (`1` for NOTEBOOK, `0` elsewhere) rather than derived. `StarterTemplateSqlTest.requiresSerialIsSetPerRowNotJustForNotebook()` added specifically to check MOUSE's `requires_serial=0` came through as an actual per-row value, not a coincidence of the old NOTEBOOK-only logic happening to also produce 0 for non-NOTEBOOK types.
- **`02-seed-equipment.sql.example`'s own comment claiming "MODEL has no unique constraint in the schema" was stale** — `RemoteDatabaseService.ensureSchema()` creates the same `idx_model_brand_type_name` unique index on the remote database that SQLite has (added 2026-07-13, see "Equipment catalog name validation" below — the remote seed template predates that and was never updated at the time). Comment corrected, and the MODEL insert switched from a `NOT EXISTS` subquery to an upsert-guard consistent with every other insert in that file (at the time, Postgres's `ON CONFLICT (brand_type_id, name) DO NOTHING`; since the 2026-07-17 SQL Server rewrite, a `NOT EXISTS`-guarded `INSERT` — see "Remote SQL Server" above for why `NOT EXISTS` guards replaced `ON CONFLICT` throughout). Deliberately still noted as a comment: the index *can* fail to be created on an installation with pre-existing duplicate `(brand_type_id, name)` rows (`ensureSchema()`'s own documented degradation path), in which case this insert would silently do nothing extra (`NOT EXISTS` just re-evaluates against whatever duplicates exist) rather than the enforcement being airtight — not a real concern for this script's actual use case (seeding a brand-new, empty database, where the index is always created successfully), but worth the reader knowing why.

**Third follow-up, same day**: `02-seed-equipment.sql.example` got S/N validation support too, for full parity with `starter-template.sql.example` — a `_seed_sn_rules` staging table + delete-then-insert per model, resolved via plain `JOIN`s (`TYPE`→`BRAND`→`BRAND_TYPE_LINK`→`MODEL`) rather than the SQLite template's correlated subqueries — functionally identical, just more idiomatic for a file that already used plain `JOIN`s in its own `MODEL` insert. The file's header comment (previously "Does NOT touch S/N validation — that's configured separately... through the app's own UI") and `database/sqlserver/README.md`'s dedicated "S/N validation rules — not a SQL script" section were both rewritten to match — the UI path still exists and is documented as the lower-effort option for a handful of rules, but is no longer the *only* option.

- **No live SQL Server instance exists anywhere in this project's test infrastructure** (confirmed via `RemoteDatabaseServiceTest`, same as noted for `CatalogMigrationToolTest` above), so this addition couldn't be run end-to-end against the real target database. The section's logic is plain `DELETE`/`INSERT`/`JOIN` — standard SQL SQLite executes identically once a handful of T-SQL-only lexical quirks are stripped. `SqlServerSeedSqlSnValidationTest.java` (renamed from `PostgresSeedSqlSnValidationTest.java` in the 2026-07-17 SQL Server rewrite) extracts just the S/N validation section (between its own start/end comment markers in the file) and runs it against a temp SQLite database as a dialect-neutral stand-in — same "same-dialect stand-in, not a mock" reasoning `CatalogMigrationToolTest` already established. Three dialect-only accommodations are needed purely for the test harness, none of which touch the actual file: `#_seed_sn_rules` (SQL Server's local-temp-table naming convention — `#` isn't a valid unquoted SQLite identifier character) is renamed to `_seed_sn_rules`; `N'...'` (T-SQL's Unicode string-literal prefix) is stripped, since SQLite strings are already Unicode; `NVARCHAR(MAX)` is replaced with `TEXT` (SQLite's parser errors on the `MAX` keyword-as-length-argument syntax). The real script's own `DROP TABLE #_seed_sn_rules` at the end of the section (needed since T-SQL has no `ON COMMIT DROP`-style auto-cleanup) makes the extracted section self-contained and re-runnable with no extra test-harness cleanup step, unlike the old Postgres-targeting test which had to manually drop the temp table between runs.

### Equipment catalog name validation (empty + duplicate names)

Added 2026-07-13. Before this, `DatabaseSectionController`'s Add/Edit dialogs for Types/Brands/Models/Providers silently did nothing on an empty name (`if (name.isEmpty()) return;`, dialog stayed open with zero feedback), and duplicate names had two different silent-failure modes: **Add** dialogs relied on SQL `ON CONFLICT (name) DO NOTHING`, so adding a name that already existed just no-opped with no row created and no error; **rename** dialogs had no `try/catch` at all, so a duplicate rename threw an uncaught `RuntimeException` mid-handler — the dialog would appear to hang, with `refreshTypes()`/`stage.close()` never reached.

- **Inline errors, not popups — and same-line, not below the field.** First attempt put the error `Label` below the `TextField` (`new VBox(2, lblN, tfName, lblError)`) with a red field border; user rejected it same-day: the label pushed the dialog's height up when it appeared, and the pattern didn't match `UserNoteView`'s existing `lblMotivoStatus` convention (header label + status label sharing one `HBox` row, status right-aligned via a `Region` spacer, so the field below never moves). Rebuilt as `DatabaseSectionController.buildFieldHeaderRow(headerLabel, errorLabel)` — every field's header (`NOMBRE`, `TIPO DE EQUIPO`, `MARCA`, `NUEVO NOMBRE`) now shares its row with an error label via that helper; the dialog's height is fixed regardless of whether an error is showing. The red field border was dropped entirely (Motivo's pattern doesn't use one either). Dialogs with multiple required fields (Add Brand: Tipo + Nombre; Add Model: Tipo + Marca + Nombre) got one error label per field, each triggered independently, instead of one shared label that couldn't indicate *which* field failed.
- **Fades out instead of persisting** — `DatabaseSectionController.triggerFieldError(Label, String)` mirrors `UserNoteController.triggerLabelFeedback()`'s exact timing (`FIELD_ERROR_HOLD` = 2000ms hold, `FIELD_ERROR_FADE` = 650ms `FadeTransition`) so every inline validation error in the app — note-generation forms and the Base de Datos catalog dialogs alike — holds and fades at the same speed. A `FadeTransition` in progress from a previous failed attempt is stopped (via `Label.getUserData()`, stashing the active transition) before starting a new one, so rapid re-clicks of Save can't leave two competing fades fighting over the same label's opacity.
- **Duplicate-name enforcement lives in `SqliteEquipmentService`, not the controller** — `addType`/`renameType` (global), `renameBrand` (global), `addModel`/`renameModel` (scoped to `brand_type_id`), `addProvider`/`renameProvider` (global) all pre-check for a case-insensitive existing match and throw `IllegalArgumentException` with a Spanish message (e.g. `"Ya existe un tipo con ese nombre"`) before touching the DB — same exception-for-user-facing-validation pattern `removeBrand()`'s Generic-protection check already used. The old `ON CONFLICT (name) DO NOTHING` clauses were removed from `addType`/`addProvider` accordingly.
- **`addBrandForType` deliberately did NOT get a duplicate-name guard** — reusing an existing brand name across multiple types (e.g. "DELL" linked to both NOTEBOOK and MONITOR) is intentional, existing behavior, not a mistake; the DB's `ON CONFLICT (name) DO NOTHING` + `ON CONFLICT (type_id, brand_id) DO NOTHING` on `BRAND`/`BRAND_TYPE_LINK` still silently reuses/idempotently links as before (see `MockEquipmentServiceTest.addBrandForTypeIsIdempotentForExistingBrand`, which this must keep passing). Only **renaming** a brand checks global uniqueness, since that changes the shared `BRAND.name` value for every type it's linked to.
- **`MODEL` had no uniqueness constraint at all before this** (confirmed by reading `DatabaseService`/`RemoteDatabaseService`'s `CREATE TABLE MODEL`, in response to a direct user question) — model names are only meaningful scoped to a Brand+Type (e.g. "Otro / Genérico" is intentionally reused across every brand+type combination), so uniqueness is enforced on `(brand_type_id, name)`, not `name` alone. `IEquipmentService` gained `getAllBrands()` (no prior method returned brands outside a single type's scope) so the controller/tests can inspect the full brand list.
- **DB-level backstop added too, not just app-layer** — explicit user choice over app-layer-only, despite the extra migration risk: SQLite can't `ALTER TABLE ADD CONSTRAINT` on an existing table (would require a full table rebuild), so this uses `CREATE UNIQUE INDEX IF NOT EXISTS idx_model_brand_type_name ON MODEL(brand_type_id, name)` instead — functionally equivalent to a `UNIQUE` constraint, but doesn't touch the table structure. Both `DatabaseService.createEquipmentTables()` (SQLite) and `RemoteDatabaseService.ensureSchema()` (SQL Server, guarded by a `sys.indexes` existence check since T-SQL has no `CREATE INDEX IF NOT EXISTS`) run this unconditionally on every startup (cheap and idempotent when the index already exists — unlike the `requires_serial` column migration, this one doesn't need a `hadColumn`-style one-time-backfill check, since there's no backfill involved) and wrap the statement in a local `try/catch` that swallows the failure if pre-existing duplicate `(brand_type_id, name)` rows already exist on that installation — same "fail safely, don't block startup" pattern as `addColumnIfMissing()`. On such an installation the index silently never gets created; enforcement degrades to app-layer-only (still fully effective for anything created through the UI going forward) until an admin manually cleans up the old duplicates.
- **Tests**: new `SqliteEquipmentServiceTest.java` (11 tests) — the first dedicated test file for `SqliteEquipmentService` (previously only exercised indirectly via `MockEquipmentServiceTest`, which tests the mock, not the real SQL-backed implementation). Covers case-insensitive duplicate rejection for all four entities, that renaming to your own existing name doesn't false-positive, and specifically that brand reuse across types and model reuse across different brand+type scopes are still allowed.

### Provider catalog (EquipmentProvider)

`PROVIDER` (`id`, `name UNIQUE`) is a flat table — no type/brand/model structure, unlike the equipment catalog — backing `ProviderNoteController`'s "PROVEEDOR" `ComboBox`. **Fixed 2026-07-10**: that field used to be a `ComboBox<String>` never populated with items and never made editable anywhere in the code, so `getProviderName()` always returned `""` and every provider note generated through the live UI silently saved with a blank provider name — nothing crashed, it just silently produced incomplete notes. `IEquipmentService` gained `getAllProviders()`/`addProvider()`/`renameProvider()`/`removeProvider()`, implemented identically to the Type/Brand/Model pattern in `SqliteEquipmentService` (`ON CONFLICT (name) DO NOTHING`), `CachingEquipmentService` (remote-first, local-fallback), and `MockEquipmentService` (in-memory, test-only, empty by default — no `mock-equipment.json` seeding since providers are inherently org-specific with no sensible mock default).

- **Managed like Types/Brands/Models**: a fourth "PROVEEDORES" list in `DatabaseSectionController`/`DatabaseSectionView.fxml`, admin-gated via the same `requireAdmin()` as the other three — but deliberately outside their cascade (`refreshProviders()` is independent of the Type/Brand selection listeners), since a provider isn't tied to a type or brand.
- **`cmbProviderSearch` is intentionally non-editable** — strict selection from the admin-curated list, not free text. Explicit user choice over a simpler free-text fix, specifically to avoid typos/inconsistent provider naming across notes.
- **Validated as mandatory**: `ProviderNoteController.validateAndShowErrors()` now blocks note generation with an inline "Debe seleccionar un proveedor" (`lblProviderStatus`) if `cmbProviderSearch.getValue()` is null — a check that was previously moot since the field could never hold a real value anyway.
- **Refreshed on tab show, not just once**: `NoteGeneratorController.showProviderNoteView()` calls `ProviderNoteController.refreshProviders()` every time the Nota de Proveedor tab is shown, not just at `initialize()` — `ViewFactory` caches this view for the session, so without this, a provider added via Base de Datos mid-session wouldn't appear in an already-open tab (same staleness fix pattern as History's `refresh()`).

### Per-type mandatory-S/N flag (`EquipmentType.requiresSerial`)

**Fixed 2026-07-13**: `ItemDialogController.updateSinSnForType()` used to force the "Sin S/N" checkbox off (and disable it) via a hardcoded `"Notebook".equals(type.getName())` check — exact-case string comparison against the live `TYPE.name` value. Renaming the Notebook type via Base de Datos (e.g. to "NOTEBOOK") silently broke the rule with no error; the checkbox just stopped being force-disabled. Generalized into a `requires_serial` column on `TYPE` (SQLite + SQL Server, migrated via `DatabaseService.migrateSchema()` / `RemoteDatabaseService.ensureSchema()` — see [SQLite schema mirrors the remote SQL Server schema](#sqlite-schema-mirrors-the-remote-sql-server-schema)), exposed on `EquipmentType.isRequiresSerial()`. `ItemDialogController` now checks `type.isRequiresSerial()` instead of matching a name.

- **Migration backfill, not a blind default**: both migration paths only run `UPDATE TYPE SET requires_serial = 1 WHERE LOWER(name) = 'notebook'` the first time the column is added (`DatabaseService`'s `addColumnIfMissing()` now returns whether it actually added the column; `RemoteDatabaseService.ensureSchema()` checks `information_schema.columns` first) — this preserves the old hardcoded behavior for existing installations instead of silently dropping the requirement to `false` for everyone's Notebook type. `DatabaseService.seedEquipmentData()` sets `requires_serial = 1` for `NOTEBOOK` directly when seeding a brand-new database; `config/mock-equipment.json`'s Notebook entry got `"requiresSerial": true` to match.
- **Not asset-specific in the schema, but only meaningful for asset types**: `IEquipmentService.setRequiresSerial(typeId, boolean)` (added alongside `addType`/`renameType`/etc., implemented identically across `SqliteEquipmentService`/`CachingEquipmentService`/`MockEquipmentService`) can technically be set on a non-asset type, but has no effect since `ItemDialogController` only shows the S/N field for `type.isAsset()` types in the first place.
- **Admin-managed via the type's "Editar" dialog, not an inline list checkbox.** First attempt (2026-07-13, same day) put an unlabeled checkbox directly in each `listTypes` row via a custom `ListCell` — user rejected it: no visible indication of what the checkbox did, and it stayed visible (just disabled) on countable types where it's meaningless. Replaced with `openEditTypeDialog(EquipmentType)`: a clearly labeled checkbox, shown **only when `type.isAsset()`** (hidden entirely for countables, not just disabled). `openAddTypeDialog()` got the same checkbox, wired to `chkAsset`'s selection (disabled + force-unchecked whenever "Es un activo" is off) so it's never offered for a type being created as a countable.
- **"Renombrar" renamed to "Editar" across all four catalog lists** (Types/Brands/Models/Providers) for label coherence, since Types' button now does more than rename — `handleRenameType/Brand/Model/Provider` renamed to `handleEditType/Brand/Model/Provider` to match. Brands/Models/Providers still only rename (their dialogs are unchanged, still routed through the shared `openRenameDialog()` helper) — only Types combines name + the requires-serial flag in one dialog.
- **Checkbox label reads "**Siempre** requerir número de serie"** with only the word "Siempre" bold — a plain `CheckBox.setText(...)` can't mix font weights within one label, so `DatabaseSectionController.buildRequiresSerialCheckbox(boolean)` builds a small `HBox` of two `Label`s (one bold) and sets it as the checkbox's `graphic` with `text` left empty. Shared by both `openAddTypeDialog()` and `openEditTypeDialog()` — the one case in this controller where a small private helper was used instead of duplicating markup, since both call sites needed byte-identical rendering, not independently-evolvable dialogs. **First attempt used a `TextFlow` (two `Text` runs) instead of `HBox`/`Label` — caused both dialogs' height to balloon** (fixed same-day, caught by the user): `TextFlow` is a wrapping layout node, and nested as a `Labeled`'s `graphic` with no explicit width, JavaFX's two-pass sizing measured it against a near-zero width, wrapping the text character-by-character into a tall stack of one-character lines. `HBox`/`Label` have no such wrapping-sizing hazard — same visual result, safe layout. If a future label ever needs mixed-style text again, avoid `TextFlow` as a `Labeled.graphic`; prefer `HBox` of plain `Label`s unless true inline wrapping is required.

### Most-used item pinning (Type/Brand/Model combos in `ItemDialogView`)

Added 2026-07-13. `cmbType`/`cmbBrand`/`cmbModel` in `ItemDialogController` now pin up to 3 "most used" entries at the top of each dropdown, separated from the rest by a thin top border on the first non-pinned cell — a lightweight alternative to a real `Separator` row, chosen deliberately (see below). If nothing qualifies as "most used" for a given combobox, it renders exactly as before (no pinning, no divider).

**How "most used" is computed — explicit user answers, not defaults:**
- **Source of truth**: aggregated `COUNT(*)` over `NOTE_ITEM` (joined to `NOTE_REPORT` for the date filter), grouped by `type_name` / `(type_name, brand_name)` / `(type_name, brand_name, model_name)`. Deliberately **not** a new tracking table — `NOTE_ITEM` rows only exist for notes that were actually generated and saved (see `SqliteHistoryService.insertItems()`), so this reuses durable, already-shared data (synced to the remote database via `CachingHistoryService` when a remote DB is configured) instead of counting every click of "Agregar a la Nota" in an abandoned, never-generated note.
- **Window**: last 30 days (`MOST_USED_WINDOW_DAYS` in `ItemDialogController`) — recent enough to track current equipment trends without a quiet week making the list sparse (the 7-day option) or a stale, discontinued model staying pinned indefinitely (the all-time option).
- **Threshold**: at least 2 uses in the window (`MOST_USED_MIN_USES`) before an item is considered "most used" — avoids pinning a single one-off use. A combobox with too little history in the window just falls back to its normal, unpinned rendering.
- **Count**: top 3 per combobox (`MOST_USED_LIMIT`) — short enough not to dominate the dropdown, especially Model lists which can already be long.

**Scoping respects the existing Type → Brand → Model cascade**: Brand pinning is computed per selected Type (`IHistoryService.getMostUsedBrandNames(typeName, ...)`), Model pinning per selected Type+Brand (`getMostUsedModelNames(typeName, brandName, ...)`) — a brand pinned for NOTEBOOK isn't assumed pinned for MONITOR. `IHistoryService` gained these three methods as `default` methods returning `Collections.emptyList()` (matching the existing pattern for `getDistinctItemTypes()`/etc.), implemented for real only in `SqliteHistoryService`; `CachingHistoryService` delegates to primary with local fallback, same remote-first pattern as every other read.

**UI implementation — no separate item added to the ComboBox's selection model.** The straightforward approach (insert a non-selectable "separator" entry into the `items` list, matching common JavaFX ComboBox-separator recipes) was rejected: `ListCell.setDisable(true)` blocks mouse clicks but **not** keyboard arrow-key navigation in `ComboBoxListViewSkin`'s internal `ListView`, so a user arrowing past a `null`-sentinel separator could land on it and commit a `null` value — silently clearing a mandatory field. Instead, `ItemDialogController.reorderWithPinned(...)` simply moves matching items (by name, case-insensitive) to the front of the real, fully-selectable list, and `applyGenericCellFactory(combo, pinnedCountSupplier)` (now takes an `IntSupplier` — was parameterless before this change) adds a thin top border (`-fx-border-width: 1 0 0 0`) to the cell whose `getIndex()` equals the pinned count, i.e. the first "rest" item. `pinnedTypeCount`/`pinnedBrandCount`/`pinnedModelCount` fields hold the current count per combobox, recomputed on every relevant selection change (type change → brand list; brand change → model list) so the divider position and pin set never go stale.

### "Genérico / Otro" fallback footer (Brand/Model combos in `ItemDialogView`)

Added 2026-07-13. `cmbBrand`/`cmbModel`'s synthetic fallback entry (shown when a Type/Brand has no catalog-configured brand/model — previously labeled plain "Generic", `ItemDialogController.GENERIC_BRAND`/`GENERIC_MODEL`, `id` 99/-1) is now labeled `GENERIC_LABEL = "Genérico / Otro"` and rendered as a footer, separated from the rest of the list by the same thin top-border divider style used for the most-used-items pinning above it (`DIVIDER_STYLE`, extracted as a shared constant since both dividers now use it) — but applied to whichever cell's text equals `GENERIC_LABEL`, not by index, since this entry is always appended last by `onTypeSelected`/`onBrandSelected` regardless of pinning. Keeps its pre-existing italic/grey styling (`GENERIC_STYLE`, `-fx-font-style: italic; -fx-text-fill: #94a3b8;`) unchanged. `cmbType` has no such fallback (no `GENERIC_TYPE`), so the shared cell factory's extra condition simply never triggers for it.

- **The only source of a "generic/other" option — the DB no longer seeds one.** `DatabaseService.seedEquipmentData()` used to also insert a real `"Otro / Genérico"` catch-all `MODEL` row for every brand-type link; removed 2026-07-13 (see [Equipment seed data](#equipment-seed-data)) per explicit user direction that the database should hold only real, curated catalog data — never a UI convenience row. This UI-only sentinel is now the sole place `"Genérico / Otro"` comes from for both Brand and Model, appearing whenever the real catalog has zero brands/models for the current selection (which, now, is every brand+type whose seed row had a `null` model).
- **Pre-existing items saved with the old "Generic" label won't re-match on edit.** `prefillAsset()`/`prefillCountable()` look up the combobox item whose name equals the stored `NOTE_ITEM.brand_name`/`model_name` text exactly. A note generated before this rename with brand/model literally `"Generic"` will fail to preselect anything when reopened for editing (the field shows blank) — same class of trade-off as every other denormalized "snapshot, don't reference" field in this schema (see `NOTE_ITEM` in [SQLite tables](#sqlite-tables)). Not fixed; flagged as a known cosmetic edge case for very old notes, not retroactively migrated.
- **Live-filter search attempted and reverted 2026-07-16**: `cmbType`/`cmbBrand`/`cmbModel` were briefly made `setEditable(true)` with a custom `StringConverter` + text-listener filtering (typing narrowed the dropdown, matching only committed a real catalog item, never free text — functionally worked). Reverted same day, purely on visual grounds — user found the editable-combobox look unconvincing (an editable ComboBox's internal editor `TextField` replaces the closed-box `ButtonCell` display these dialogs relied on for the "Genérico / Otro" italic styling). If searchable Type/Brand/Model combos are revisited, don't just reintroduce `setEditable(true)`; consider a non-editable approach (e.g. a small dedicated filter `TextField` above/beside the combo, or a `PopOver`-based custom picker) that doesn't change the ComboBox's own rendered chrome.

### A/F format simplification (2026-07-29)

The org's A/F format changed to `IT-<serial number>` — no longer an independently-typed, padded
number. A/F is now fully derived: `prefix + separator + serialNumber`, recomputed live as the
technician types S/N — no separate raw-number field, no padding logic.

- **`AppConfig.AfFormat` shrunk to just `prefix`/`separator`** — `length`/`filler`/`inputPattern`
  deleted entirely, both from the model and `app-config.json`/`.example`. `@JsonIgnoreProperties(ignoreUnknown = true)`
  was already present, so a stale config file with the old keys still deserializes fine — no
  migration step needed.
- **`ItemDialogController`**: `formatAF()`/`extractAfRaw()`/`refreshAfFlow()`/`afTypeDescription()`/
  `afPlaceholder()` and the `flowAfPattern` preview `TextFlow` (both controller field and FXML node)
  were deleted outright, replaced by a single `recomputeAf()` — `prefix+separator+S/N` when
  `chkEnableAF` is selected, blank otherwise — wired to `txtSerial`'s existing text-change listener
  (so A/F updates on every S/N keystroke) and called from `handleAfToggle()`/`handleSinSnToggle()`.
  `txtAF` is now `editable="false"` (FXML) — a derived display field, not typed input.
- **"Incluir A/F" (`chkEnableAF`) now defaults `selected="true"`** (was unchecked) and **force-disables/
  unchecks when "Sin S/N" (`chkSinSN`) is checked** — new interaction in `handleSinSnToggle()`,
  mirroring how Sin S/N already disables the S/N field itself: there's nothing to derive A/F from
  with no serial. `prefillAsset()` simplified to `chkEnableAF.setSelected(af != null && !af.isEmpty())`
  + `handleAfToggle()` (S/N is already prefilled by that point, so `recomputeAf()` reproduces the
  stored value) — no raw-suffix extraction needed since there's no raw suffix anymore.
- **`SettingsController`/`SettingsView.fxml`**: `txtAfLength`/`txtAfFiller` fields, their FXML
  `GridPane` columns, and `handleSave()`'s `Integer.parseInt` validation were all removed.
  `updateAfPreview()` simplified to plain `prefix + separator + "AB12345678"` concatenation.
- **`AfFormatterTest.java` deleted outright** — confirmed via grep that no `AfFormatter` class
  exists anywhere in `main/`; the file defined and tested a private, self-contained reimplementation
  of the old padding algorithm, exercising zero production code. `SettingsControllerTest`'s
  `txtAfLength`/`txtAfFiller` field-injection lines were removed to match.
- **No changes needed in `AssetItem`/`NoteReportItem`/`NoteGenerationService`** — they only ever
  passed through whatever string was already in `af`/`getAf()`, agnostic to how it was constructed.
- Built first in this session's 4-feature batch, as an intentional warm-up (fully resolved design,
  no open questions) — full suite: 302 tests passing (307 pre-existing − 5 deleted `AfFormatterTest`
  cases), all green.

### Close ("×") icon on note popups

Added 2026-07-16. `NotePreviewView.fxml` (shown right after "Generar Nota" succeeds) and `NoteDetailView.fxml` (shown when reopening a note from History — the same popup for both a regular technician and an admin, gated by `NoteDetailController.open()`'s `adminMode` parameter) each gained a small "✕" button in the top-right of their teal gradient header, via a `Region HBox.hgrow="ALWAYS"` spacer pushing a new `Button` (`styleClass="dialog-header-close-button"`) to the far right. Each wires to the popup's existing close handler (`onAction="#handleCancel"` for `NotePreviewController`, `onAction="#handleClose"` for `NoteDetailController`) rather than duplicating close logic — behaves identically to each popup's existing "Cancelar"/"Cerrar" footer button, just reachable without scrolling down to the footer.

- **New `.dialog-header-close-button` CSS class** (`styles.css`, next to `.title-bar-button`) — same transparent-background/hover-overlay visual language as the custom title bar's minimize/maximize/close buttons, but sized smaller (26×26, circular) for a compact dialog header instead of the 42×36 title bar. Not a reuse of `.title-bar-button` itself, since that class's fixed sizing is tuned to the actual title bar and would look oversized here.
- **Does not fade before closing** — unlike `NotePreviewController`'s post-generation-success auto-close (`fadeOutAndClose(Stage)`), this × is a manual dismiss action equivalent to the existing Cancelar/Cerrar buttons, so it reuses their existing (non-fading) close behavior rather than introducing new animation for a plain manual close.
- **Tests**: `NotePreviewViewFxmlTest.java`/`NoteDetailViewFxmlTest.java` — same FXML-load-through-a-real-`FXMLLoader` convention as `UserNoteViewFxmlTest`, added since neither view had one before and a typo in the new button's `onAction` would otherwise only surface at runtime.

### Item list redesign: stacked blocks → tables

Added 2026-07-16. Every template's item list used to render each item as 5 stacked "Label: value" lines (`.items-list-container` > `.item-entry` > `.item-property`/`.item-label`; `devolucion.html` had its own CSS-grid variant of the same idea) — spacious enough that a handful of items could take up more than half the page. User asked for a proper table layout, with an HTML mockup comparing both side by side (same 5 items: ~145mm tall in the old layout vs. ~38mm in a table) before committing.

- **Two separate tables, not one shared table** — Tipo/Marca/Modelo/N° Serie/N° Activo Fijo/Detalles for assets, Tipo/Marca/Modelo/Cantidad/Detalles for countables, mirroring `NoteGeneratorView.fxml`'s own `tblAssets`/`tblCountables` split rather than one table with columns blank depending on item type. Explicit user choice over a merged table.
- **No `<caption>` on either table, same day** — first version gave each table a title ("Equipos con S/N" / "Periféricos y otros"). User disliked "Equipos con S/N" specifically and, rather than iterate on wording, asked to drop per-table captions entirely — the two tables are now told apart only by which columns they have and the spacing between them. This also sidesteps a related complaint (the caption's small-caps styling read too similarly to the column header row directly below it).
- **Both tables now sit inside one bordered `.items-section` container** with a single tab label "Lista de equipamiento" (`.items-section-tab`, same overlapping-the-border idea as the signature boxes' `.signature-tab`, added same day right after the caption removal). Always rendered (not itself conditional) since a note can't be generated with zero items at all; the two tables inside it still independently vanish via `HAS_ASSET_ITEMS`/`HAS_COUNTABLE_ITEMS` as described below. **Follow-up, same day**: the border started at 2.5pt (meant to read as distinctly thicker than the signature boxes' 1.4pt) but was reported as too heavy — brought down to 1.5pt, just barely heavier than the signature boxes rather than dominating the page. Same follow-up added `.note-table + .note-table { margin-top: 22px; }` — with the per-table captions gone, the two tables had nothing but each other's own `margin-bottom: 12px` keeping them apart, and the second table's header row read as uncomfortably close to the first table's last data row.
- **Real differentiation, not the old `SERIAL` hack** — `NoteGenerationService` used to merge assets and countables into one `ITEMS` loop and repurpose the `SERIAL` field to print `"Cant: N"` text for countables (only when `N > 1`; nothing at all for `N == 1`) since there was no real quantity column. Replaced with two proper loops/builders (`buildAssetItemTokens`/`buildCountableItemTokens`) and a real `QUANTITY` token — the countable table's Cantidad column now always shows the actual number, including `1`, since a dedicated column has no reason to hide it the way inline prose text did.
- **A whole empty table (header included) disappears, not just its rows** — e.g. a note with only assets shows no "Periféricos y otros" table at all, not an empty one with just column headers. This needed a real capability the engine didn't have: **`TemplateEngine.expandLoops()` now supports nested loops** (see [Template engine](#template-engine)) — a `HAS_ASSET_ITEMS`/`HAS_COUNTABLE_ITEMS` "presence flag" loop (`NoteGenerationService.presenceFlag(boolean)`: one dummy entry or empty) wraps the whole `<table>` including its header, with the real per-row `ASSET_ITEMS`/`COUNTABLE_ITEMS` loop nested inside it. Flagged to the user as a real trade-off before implementing — the simpler alternative (skip the engine change, accept a header-only empty table) was explicitly turned down in favor of the correct result.
- **Long "Detalles" text wraps normally, row grows** — explicit user choice over truncating with an ellipsis or dropping the column entirely; simplest, and never silently loses information written in an item's observations.
- **Horizontal rules only, no vertical cell borders** — explicit user choice over a fully gridded table, to stay closer to the rest of the note's plain-document feel (`.note-table th`/`.note-table td` get `border-bottom` only; no left/right borders).
- **Column headers kept in full** ("N° Serie", "N° Activo Fijo", "Cantidad", "Detalles", not abbreviated) — explicit user choice, consistent with wording already used elsewhere on the note.
- **Applied identically to all 5 templates**, including `devolucion.html`, which previously had its own distinct CSS-grid-based item layout (2-column grid per item, not stacked "Label: value" lines like the other 4) — now unified onto the same `.note-table` markup/CSS as everywhere else. Its `@media print` rule that force-sharpened the old grid's borders (`.item-entry, .item-label, .item-value`) was retargeted to `.note-table th, .note-table td` rather than left dangling on now-nonexistent classes.
- **Tests**: `NoteGenerationServiceTest.java` updated throughout — assertions on the old `item-property`/`item-value`/`items-list-container` markup and `"Cant: N"` text replaced with `note-table`/exact `<td>...</td>` cell-sequence checks; `countableItemShowsQuantityOnlyWhenGreaterThanOne` renamed to `countableItemAlwaysShowsQuantityInDedicatedColumn` to match the new always-show-the-number behavior. `TemplateEngineTest.java` gained two new cases for the nested-loop behavior (`nestedLoopInsideOuterBlockExpandsAgainstTheSameLoopsMap`, `nestedLoopWrapperHidesWholeBlockWhenOuterLoopIsEmpty`).

### Item table redesign, round 2: merged "Equipo" column + zebra rows, no outer box — superseded same week

Direct user report with a screenshot: for a note with only 1-2 items, the 2026-07-16 table design above (outer bordered box + tab, 6 separate columns, a heavy 2px header rule, a 1px rule under every row) was "too many lines, for too little information" — a lot of visual chrome around very little actual content. Three redesigns (merged-column table with zebra rows / a no-table compact inline list / the same 6 columns with the box and per-row lines stripped) were mocked up as an HTML artifact against the user's own real data (rendered at real print font/size, same precedent as the signature-box and original table-vs-stacked-blocks redesigns) before picking one — **Option A** (merged column + zebra) was chosen 2026-07-29, with Option B (compact inline list) named up front as the fallback if A "fails" once seen for real.

Option A shipped (Tipo/Marca/Modelo merged into one "Equipo" column, outer box removed, zebra-striped rows) but was **replaced the very next day (2026-07-30) — see the round-3 entry directly below** — the fallback condition was hit for real, for two concrete reasons the mockup comparison never surfaced: the target printer is black & white, so the `#f4f4f4` zebra shading that was supposed to separate rows printed as no visible difference at all; and the table's own row height/padding still left more empty vertical space between items than the user wanted, even without the shading. This whole entry is kept as historical record of what was tried and specifically why it didn't hold up in the real print environment — not something to resurrect without addressing both points (any future table-based option needs a *lines-or-marks* row separator that survives B&W printing, not a background color).

- **Tipo/Marca/Modelo collapse into one "Equipo" column** — a bold primary line (`{{TYPE}}`) with a lighter, smaller secondary line below it (`{{BRAND}} {{MODEL}}`), via two stacked `<div class="equipo-name">`/`<div class="equipo-sub">` inside the cell. Asset tables go from 6 columns to 4 (Equipo, N° Serie, N° Activo Fijo, Detalles); countable tables go from 5 to 3 (Equipo, Cantidad, Detalles).
- **The outer `.items-section` bordered box + "Lista de equipamiento" tab (added 2026-07-16, see above) is removed entirely**, per Option A's design.
- **Header row**: single 1.4pt line, uppercase, smaller (9.5pt), `#555` gray.
- **Row separation**: alternating `#f4f4f4` background (`tbody tr:nth-child(odd)`) instead of a `border-bottom` on every `<td>` — **this specific mechanism is what failed on a B&W printer**, since a light gray fill and white are visually identical once color is stripped out.
- **Tests**: `NoteGenerationServiceTest`'s two exact-markup assertions updated to the merged-cell shape; a real, one-time visual check (generate a real note, dump + render the HTML, confirm, delete the test) was run before calling it done — same precedent used again for round 3 below.

### Item table redesign, round 3: Option B, compact inline list (2026-07-30)

Direct follow-up the next day: "the printer is black and white so the grey background is not showing, and there is so much empty space in between the items." Rather than patch Option A's row-separator mechanism, the user went straight to the fallback already agreed on the day before — **Option B**, the no-table compact list — since that option never depended on background color or a table's row chrome to begin with.

- **No table at all.** Each item is one flex row (`.item-row`: `display: flex; justify-content: space-between; align-items: baseline;`) — equipment name/brand/model on the left, technical details on the right in smaller gray text. Rows within one list are separated only by a thin `1px solid #ccc` bottom border (`.item-row:last-child` drops it, so the list's own last row doesn't end in a trailing rule) — a **line**, not a fill color, so it survives B&W printing exactly as well as every other rule already used elsewhere on these notes (signature lines, section separators). Row padding is `5px 2px` — deliberately tighter than round 2's table-cell padding, directly addressing "so much empty space."
- **A combined `{{META}}` token, built server-side, replaces separate `{{SERIAL}}`/`{{ASSET_TAG}}`/`{{QUANTITY}}`/`{{DETAILS}}` cells** — `NoteGenerationService` gained `assetMeta(serial, af, details)` / `countableMeta(quantity, details)` / `joinMeta(String...)`, mirroring the exact "combine conditionally in Java, not in the template" precedent `failureLoop()`'s `FAILURE_TEXT` already established (see [Template engine](#template-engine)'s gotcha entries): each non-blank piece is joined with `" · "`, so a blank S/N (Sin S/N checked) or empty Detalles never leaves a dangling separator. Assets render `"S/N: {serial} · A/F: {af} · {details}"` (any blank piece dropped entirely, not just its value); countables render `"Cantidad: {n} · {details}"` (Cantidad always shown, since quantity always has a value). Both the live-generation path (`buildAssetItemTokens()`/`buildCountableItemTokens()`) and the reprint path (`generateFromStoredReport()`'s inline item loop) call these same two helpers, so a reopened historical note computes `META` identically to a freshly-generated one.
- **`TYPE`/`BRAND`/`MODEL` needed no Java change** — same as round 2, these were already separate per-entry tokens; the left-hand line is just `<span class="item-equipo">{{TYPE}}</span> — {{BRAND}} {{MODEL}}` in the template.
- **`.item-list + .item-list { margin-top: 14px; }`** separates the asset list from the countable list when a note has both (mirrors round 2's `.note-table + .note-table` spacing rule) — each list still independently vanishes via the unchanged `HAS_ASSET_ITEMS`/`HAS_COUNTABLE_ITEMS` presence-flag wrapping.
- **`devolucion.html`'s print-media border-sharpening rule retargeted again** — round 2 had it forcing `.note-table th`'s crispness; now targets `.item-row` (`border-width: 1pt !important`), since that's the only remaining thin rule in this layout that print rendering could blur.
- **Applied identically to all 5 templates** — CSS block and markup block were confirmed byte-identical across all 5 files (checksummed) both before and after this edit.
- **Tests**: `NoteGenerationServiceTest`'s markup assertions rewritten again for the new `.item-row`/`.item-equipo`/`.item-meta` shape (`countableItemAlwaysShowsQuantityInDedicatedColumn` renamed `countableItemAlwaysShowsQuantityInMetaLine` to match); the two `indexOf("<table class=\"note-table\">")` ordering checks (Préstamo return-date-below-items, Provider motivo-below-items) updated to `indexOf("<div class=\"item-list\">")`. Same one-time real-render visual check run and removed before calling this done. Full suite: still 342 tests passing (no net count change — assertions rewritten in place, not added).

### S/N Validation panel — multi-select filters (Tipo/Marca/Modelo/Activo)

Added 2026-07-14, replacing the panel's previous single free-text filter (`txtSnFilter`, matched against type/brand/model/regex substrings). `SettingsController` now duplicates History's multi-select checkbox `MenuButton` filter pattern (`populateSnMenu`/`updateSnMenuLabel` — same shape as `HistoryController.populateMenu`/`updateMenuLabel`, duplicated rather than shared per the no-abstraction rule) for four filters: Tipo, Marca, Modelo, and Activo (`SN_ACTIVE_OPTIONS = List.of("Sí", "No")`, matching a row's `SnValidationRow.isActive()`). A "Limpiar filtros" button (`handleClearSnFilters()`) resets all four back to "Todas".

- **Tipo → Marca → Modelo cascades**, mirroring History's item-type/brand/model filters: selecting a Tipo narrows Marca's options to brands actually used with that Tipo in the table, and Marca narrows Modelo similarly. Unlike History (which re-queries `IHistoryService` for distinct values scoped by date), this cascade is computed locally from the already-loaded `allSnRows` list (`distinctSnValues(Predicate, nameFn)`) — no service round-trip needed, since the S/N validation table's full dataset is already in memory once the panel opens.
- **Filtering is instant/local, no "Buscar" button** — `applySnFilter()` just rebuilds `filteredSnRows`'s `Predicate` directly from the four selection `Set`s on every checkbox change, unlike History which re-queries the DB on `autoSearch()`.
- **Filter selections survive a same-session data reload** (e.g. after editing a row's regex/active toggle via `openEditDialog()`, which calls `loadSnValidationData()` again) — `refreshSnFilterMenus()` rebuilds all four menus' *options* from the fresh `allSnRows` without touching the selection `Set`s, so an admin's active filter isn't wiped out by editing the very row they filtered to find. Only `handleClearSnFilters()` and the cascade-triggered `onSnTypeChanged()`/`onSnBrandChanged()` (changing Tipo/Marca deliberately resets the narrower downstream selections) clear a `Set`.
- **Tests**: `SettingsControllerSnFilterTest.java` — exercises the cascade and Activo predicate directly against `MockEquipmentService`'s real `mock-equipment.json` data (Notebook/Monitor asset types) via reflection-injected fields, without needing a live `Skin` (same rationale as `UserNoteFallaPersistenceTest`'s reflection-seeding approach). `SettingsViewFxmlTest.java` loads `SettingsView.fxml` through a real `FXMLLoader` to catch `fx:id`/`onAction` typos in the new `mnuSnType`/`mnuSnBrand`/`mnuSnModel`/`mnuSnActive` MenuButtons.

### S/N Validation "Editar" button — disabled, not hidden, for non-admins; stale error dialog removed (2026-08-06)

Direct user report: `handleEditRow()`'s permission check showed "Activa el modo administrador
desde Configuración para editar la validación S/N." when clicked without `EDIT_SN_VALIDATION` —
stale, since the self-service "Activar modo administrador" toggle it pointed at was removed
in the RBAC redesign (see [Role-based permissions (RBAC)](#role-based-permissions-rbac-a-superadmin-tier-and-sede-scoped-admin-actions-2026-07-30)).
Explicit user direction: disable the button (visible but unusable), don't hide it — different
from `DatabaseSectionController`'s CRUD buttons, which hide entirely (`updateCrudButtonVisibility()`).

- `colSnEdit`'s cell factory now sets `btn.setDisable(!AdminSession.hasPermission(EDIT_SN_VALIDATION))`
  on every `updateItem()`; `onAdminStateChanged()` (already wired to `AdminSession`'s activate/
  deactivate listeners for `updateFieldEditability()`) now also calls `tblSnValidation.refresh()`,
  so the disabled state updates immediately on an admin login/logout instead of only on the next
  scroll or data reload.
- **The permission check inside `handleEditRow()` itself was deleted outright, not just
  re-worded** — caught by direct user question ("the button is disabled so it's unclickable,
  right? Shouldn't you just remove the whole popup?"). Confirmed via grep: `handleEditRow()` is
  `private` with exactly one caller (the button's own `onAction`), which cannot fire while the
  button is disabled — genuinely unreachable, not just redundant. This is a different situation
  from `DatabaseSectionController.requirePermission()`, which stays necessary even with its
  buttons hidden, because it's a *shared* gate with a password-fallback path reused across many
  call sites — a hidden button there doesn't guarantee every path into it is covered the way this
  method's single caller does here. Removing the check also left `showErrorDialog()` with zero
  remaining callers in this file, so it was deleted too rather than left unused.
- No test changes — no test exercised the removed error path or asserted the button's `disable`
  property; full suite: 493 tests passing, unchanged count.

### AD user-selection PopOver hover delay

**Fixed 2026-07-13**: scrolling `ADUserSelectionView`'s `lstResults` (the multi-match popup from `UserNoteController.handleADSearch()`) with the scrollbar felt laggy/stuttery — user asked directly whether it was the per-row `PopOver` (a real animated popup `Stage`, ControlsFX) fighting the scroll, and it was. `ADUserSelectionController.setupCellFactory()`'s `mouseEntered`/`mouseExited` handlers called `popOver.show(this)`/`popOver.hide()` immediately; in a virtualized `ListView`, dragging the scrollbar with the cursor stationary makes cells slide *under* the cursor, so every row boundary crossed fired a full animated show/hide cycle — that's what stuttered, not the list itself.

Fixed with a 400ms `PauseTransition` (`POPOVER_HOVER_DELAY`) between `mouseEntered` and the actual `popOver.show(this)` call, cancelled on `mouseExited` (so a quick pass-through during scrolling never triggers it) and also cancelled/hidden unconditionally at the top of every `updateItem()` call (covers the edge case where a recycled cell's content changes while the mouse never technically left the Node's bounds, which could otherwise leave a stale pending show or an already-open PopOver pointing at the wrong user). This is the second time this exact `lstResults`/`PopOver` block has been touched — see [[project_technician_session_architecture]]'s "Unrelated UI polish, same session" entry for the earlier dialog-chrome restyling pass, which explicitly left the *hover behavior itself* untouched at the time; this fix is scoped to timing only, not styling, so that earlier decision still stands for everything except when the popup fires.

### Custom title bar and window chrome

Added 2026-07-14. `App.java` uses `StageStyle.TRANSPARENT` (not the OS's native decorated window) — `MainView.fxml` has its own `<top>` title bar HBox (`titleBar`, teal `#0a6b5c` — intentionally darker than `.sidebar`'s `#0c8570` so the two don't visually merge into one block) with the app icon, full window title, and minimize/maximize/close buttons. `MainController.setupWindowChrome()` (deferred via `Platform.runLater`, same one-pulse-deferral reasoning already used for `runStartupChecks` — the Stage/Scene aren't attached yet during `initialize()`) hand-rolls everything the native chrome used to provide for free:

- **Drag-to-move**: `titleBar`'s own mouse press/drag, tracking the cursor's scene-space offset from the press point. Double-click toggles maximize/restore, matching native title bar convention.
- **Edge resize**: only the right, bottom, and bottom corners (`ResizeDirection.E/W/S/SE/SW`) are drag-resizable — the top edge is reserved for the title bar's drag-to-move gesture, so there's no top/top-corner resize zone to avoid the two fighting over the same 6px strip. `rootPane.setOnMouseMoved/Pressed/Dragged` detect proximity to its own edges (`RESIZE_MARGIN = 6`) and compute the new width/height/X from the drag delta, clamped to `stage.getMinWidth()/getMinHeight()` (900×600).
- **Shadow + rounded corners + subtle border** (added 2026-07-14, same day, in response to the window looking "too sharp" once the native chrome — which used to provide its own shadow — was gone): `Node.clip` and `Node.effect` don't combine cleanly on one node (a `DropShadow` needs to render outside the node's own bounds, but a clip cuts exactly at those bounds, clipping the shadow away too) — so they're split across two nodes. `App.java` wraps `rootPane` in a plain, transparent, padded `StackPane` (`windowWrapper`, padding = `WINDOW_SHADOW_MARGIN` = 20, same "shadow needs room outside the visible content" trick every dialog's `buildDialogScene` already uses) that carries the `DropShadow`; `rootPane` itself carries a `Rectangle` clip (`windowClip`, bound live to `rootPane`'s width/height, corner arc 20 ≈ same visual radius as `.app-window-frame`'s CSS `-fx-background-radius: 10`) plus the CSS border/radius. Both — and the wrapper's padding — are stripped via `applyWindowFrame(windowWrapper, maximized)` (a `stage.maximizedProperty()` listener) whenever the window is maximized, via a `.maximized` CSS modifier on `rootPane`, so a maximized window fills the screen edge-to-edge with square corners instead of a rounded shape or shadow gap cutting into the screen.
- **Window sizing must add, not subtract**: the title bar's 40px and the shadow wrapper's 2×20px padding are *added on top of* `App.java`'s existing `visualBounds * 0.85/0.95` budget, not carved out of it (clamped to `visualBounds` so this never asks for more height than the screen has) — see the next entry for why getting this backwards broke a specific view.
- **Known limitations, not implemented**: no Windows Aero Snap (drag-to-top/side to maximize/half-screen) — that's OS-chrome-provided behavior with no equivalent hook here; dragging a maximized window doesn't auto-restore-then-drag the way native title bars do (the drag handler just no-ops while maximized).
- **Maximize/restore icon fixed 2026-07-14, in two passes.** Originally rendered via Unicode glyphs (`"▢"`/`"❐"`), which the user reported "looks weird": uncommon symbols with patchy font coverage, rendering at an inconsistent weight/size/alignment next to the plain, well-supported `"─"`/`"✕"` glyphs used for minimize/close. **First attempt**: small bordered `Region`s (CSS `-fx-border-color`/`-fx-border-width` via `setStyle(...)`) as the button's `graphic`. This made it *worse* — reported as invisible with a stray white square around it, most likely some CSS-cascade rule winning over the inline style; never fully root-caused. **Second attempt**: `javafx.scene.shape.Rectangle` instead of a styled `Region`, with fill/stroke set via the direct Java API (`setFill()`/`setStroke()`/`setStrokeWidth()`) rather than a CSS string — no stylesheet cascade to fight, so the rendered shape is exactly what's set. `updateMaximizeGlyph()` builds a plain outlined square for maximize, two overlapping outlined squares (classic OS convention) for restore. `btnMaximizeRestoreWindow` has no FXML `text` attribute at all — fully graphic-driven.

**Third attempt (still not it)**: reported again as "malformed," this time only while maximized. The restore icon's front square had `setFill(Color.web("#0a6b5c"))` — a solid fill matching the title bar's flat background, the classic "punch a hole in the back square" trick for the two-overlapping-squares look. But `.title-bar-button`'s `:hover`/`:pressed` CSS states change the *button's* background to a lighter overlay (`rgba(255,255,255,0.18)`/`0.30`) while the graphic's fill stayed a static solid color — so hovering or pressing the button (exactly when a user is looking at/interacting with it) showed a visibly mismatched patch sitting on top of the lighter background. Fixed by making both squares `fill = TRANSPARENT` (outline-only) — with no background to match, they render identically regardless of the button's state. Still reported as wrong afterward — this attempt addressed a real bug, just not the one actually causing the visual problem.

**Fourth attempt (the actual fix)**: user's precise description ("a square in the bottom-left and a square in the top-right" of the button, not overlapping) pinned it down — the two `Rectangle`s' wrapping `StackPane` had `setPrefSize(11, 11)` but no `setMaxSize(...)`. `.title-bar-button` is 42×36, far bigger than 11×11, and `Region`'s default max size is unbounded — with nothing capping it, the button's layout pass stretched the `StackPane` well past its intended 11×11, so the two corner-aligned (`TOP_RIGHT`/`BOTTOM_LEFT`) squares ended up spread across the much larger actual area instead of overlapping. Explains why only the restore state (the `StackPane`-wrapped one) was affected and the plain maximize state (a single bare `Rectangle`, not `Resizable`, immune to parent-driven stretching) was always fine. Fixed with `icon.setMinSize(11, 11)` + `setMaxSize(11, 11)` alongside the existing `setPrefSize(11, 11)`.

### NoteGeneratorView's min-height forced the sidebar taller than the window

**Fixed 2026-07-14**, in three passes — the first two didn't fully work, and the second one was actively rejected. First symptom: after the title bar shipped, the sidebar's "ESTADO DE SERVICIOS" block sat noticeably closer to the bottom edge — but *only* while viewing Generar Nota, never on other sections. Root cause: `BorderPane` forces its `left` (sidebar) and `center` rows to share one height. `NoteGeneratorView` was already sized right at the edge of the old available height; losing the title bar's 40px (originally carved out of the existing budget rather than added on top) pushed its min-height just past what was left, forcing the whole row — sidebar included — taller than the visible window. Other sections have shorter content and never hit the edge, which is why it looked section-specific.

- **Attempt 1**: add the title bar's (and later the shadow wrapper's) height back to `App.java`'s window-height budget instead of subtracting from it. Reduced the effect but didn't eliminate it — user reported "better, but still shrinks a little."
- **Attempt 2 (reverted same day)**: wrapped `NoteGeneratorView.fxml`'s left column (`dynamicContentArea`, hosting `UserNoteView`/`ProviderNoteView` — a tall stack of fixed-size fields with no shrink capacity of its own, unlike the two equipment `TableView`s on the right) in a `ScrollPane`. This did eliminate the height-forcing problem, but introduced a worse one: a visible scrollbar on the User Note form itself, rejected outright by the user ("unacceptable"). Reverted back to the plain `VBox.vgrow="ALWAYS"` StackPane.
- **Attempt 3 (partially wrong, corrected same day)**: re-examining attempt 1's math found a real bug — `App.java` clamped the computed height to `Screen.getVisualBounds().getHeight()` (screen minus taskbar), which only leaves ~5% headroom above the existing `0.95` factor while the title bar + shadow-margin additions total *more* than that on almost any ordinary monitor (1080p included) — so the clamp was silently discarding the entire attempt-1 compensation on normal screens. Fixed by clamping to `Screen.getBounds()` (the *full* screen, taskbar included) instead. This over-corrected: the window could now grow tall enough to extend past the taskbar's top edge, genuinely hiding a sliver of the app behind it — reported immediately.
- **Attempt 4**: reverted the clamp ceiling back to `visualBounds.getHeight()` — the same ceiling the app used before the title bar ever existed, and the only one that actually guarantees no taskbar overlap — and dropped the extra `CONTENT_HEIGHT_BUFFER` (50px) attempt 3 had added on top, since that was the main contributor pushing past it. Framed at the time as an acknowledged trade-off (a small shortfall was assumed unavoidable), but user pushed back: "is it that hard to make the app just a tiny bit taller?" — worth re-deriving properly rather than accepting.
- **Attempt 5 (the actual fix)**: re-deriving the full chain exposed the real bug in attempt 4's own reasoning — it only subtracted the title bar's height when checking the deficit, forgetting `windowWrapper`'s own padding (`WINDOW_SHADOW_MARGIN`, both top *and* bottom) also comes out of the same clamped total. Full chain when clamped to `visualBounds` (VB): `rootPane` height = VB − 2×`WINDOW_SHADOW_MARGIN`; content-row = that − `TITLE_BAR_HEIGHT`. With the old constants (40 + 2×20 = 80px total overhead) against `visualBounds`'s ~5%-of-VB headroom (≈52px on a 1080p screen), the shortfall was a real, computable ≈28px — not a screen-dependent edge case, a near-certainty on any ordinary monitor. Padding the height *budget* further can't fix this: it either still gets clamped away (no-op, attempt 4) or, if the clamp is loosened enough to let it through, starts covering the taskbar again (attempt 3, already tried and reverted). The only screen-size-independent fix is shrinking the overhead itself: `TITLE_BAR_HEIGHT` 40→32, `WINDOW_SHADOW_MARGIN` 20→12 (total overhead 80→56px, shortfall ≈28px→≈4px — effectively gone on a typical screen, and proportionally smaller everywhere else too). `MainView.fxml`'s title bar `prefHeight` and `.title-bar-button`'s CSS min/max height/width shrunk to match (44×40 → 40×32); the title bar's icon shrunk 18px→16px to stay proportional. The `DropShadow`'s radius/offsetY (`MainController.applyWindowFrame()`) were also scaled down (24/6 → 14/4) to match the smaller padding — a shadow that bleeds further than the wrapper's padding gives it room for just gets clipped at the wrapper's own edge.

**Follow-up, same day**: user missed the taller original bar and asked for a middle ground, explicitly offering to roll back if it didn't work out. `TITLE_BAR_HEIGHT` nudged 32→36 (`MainView.fxml`'s `prefHeight`, `.title-bar-button`'s CSS min/max height 32→36 and width 40→42, icon 16px→17px) — costs an estimated few more px of shortfall (still far below the original 28px regression on a typical screen), asked and confirmed before changing. `WINDOW_SHADOW_MARGIN` (12) and the `DropShadow` sizing were left untouched.

### Admin-mode indicator moved from the sidebar to the title bar

**Fixed 2026-07-14**: `lblAdminIndicator` ("MODO ADMINISTRADOR") used to sit inside the sidebar's welcome-message `VBox` (`managed`/`visible` toggled by `MainController.updateAdminIndicator()`, unchanged — only its FXML location and styling moved). That `VBox` sits between two `VBox.vgrow="ALWAYS"` spacers for vertical centering within a fixed height budget (see the window-height entries above), so showing the label both shifted the welcome text's centered position and, on tight screens, could push the sidebar's required height past what was available — the exact same class of bug as `NoteGeneratorView`'s, just triggered by the sidebar's own content instead of `center`'s.

Moved to the title bar instead (`MainView.fxml`, right after the title label, new `.title-bar-admin-badge` CSS class — same `#0e9a82` color as before, shrunk to fit the title bar's height) — a fixed-height row that never grows, so a badge there can't have either side effect. No controller changes needed: `fx:id="lblAdminIndicator"` and `updateAdminIndicator()`'s `setVisible`/`setManaged` calls are unchanged, only the label's declared position and inline style (now a CSS class) moved. The in-app manual's "Modo administrador" section (`AboutView.fxml`) was updated to match — it used to say the state also shows "en la barra lateral."

### "Probar conexión" silently reported local DB status when no remote was configured

**Fixed 2026-07-14**: `DatabaseSectionController.handleTestConnection()` used to test *either* the remote database (if `RemoteDatabaseService.isConfigured()`) *or* the local one, never both — so clicking "Probar conexión" with no remote database configured at all just silently tested and reported the local database's status under the single "ESTADO" label, with no indication that the remote side had never been configured in the first place. A configured-but-unreachable remote and a never-configured remote were also indistinguishable (both just fell through to the local-only path or showed the same red message).

Fixed by always testing and reporting both, independently, in two separate labels (`DatabaseSectionView.fxml`'s connection `GridPane` gained a row — "ESTADO REMOTO" / `lblConnectionStatus`, "ESTADO LOCAL" / new `lblLocalConnectionStatus`): "ESTADO REMOTO" now has three distinct states — orange `"⚠ Base de datos remota no configurada"` when `!isConfigured()`, red `"✗ Servidor remoto configurado no disponible"` when configured but `testConnection()` fails, green `"✓ Conexión remota activa"` on success. "ESTADO LOCAL" always tests `DatabaseService.getInstance().getConnection()` directly and reports green/red, regardless of remote configuration state.

### "Nombre para mostrar" save/reset was also flashing the AD identity status message

**Fixed 2026-07-14**: saving or resetting "Nombre para mostrar" (`ProfileController.handleSaveDisplayName()`/`handleResetDisplayName()`) also re-flashed `lblProfileStatus` with "Perfil actualizado desde Active Directory" / "Perfil guardado correctamente" — the *identity* section's own confirmation message, unrelated to the display-name preference. Root cause: `setDisplayNamePreference()` and the three AD-identity-changing methods (`refreshFromWindowsSession`, `applyManualOverride`, `clear`) all called the same `notifyListeners()`, and `ProfileController.populateFieldsFromSession()` — the single shared callback subscribed to it — unconditionally flashed `lblProfileStatus` every time it ran, with no way to tell which of the two unrelated things had actually changed.

Fixed by giving `TechnicianSessionService` a second, independent listener list (`onDisplayNameChangeListeners` / `addOnDisplayNameChangeListener()`) that only `setDisplayNamePreference()` notifies. `ProfileController` now has two separate callbacks: `populateFieldsFromSession()` (identity fields + `lblProfileStatus`, subscribed to the original `addOnChangeListener`) and `populateDisplayNameField()` (just `txtDisplayName`, subscribed to the new listener, no status flash — `handleSaveDisplayName()`/`handleResetDisplayName()` already flash `lblDisplayNameStatus` directly themselves).

**Follow-up fix, same day**: writing `ProfileControllerDisplayNameTest` (a new `ProfileController` + `initialize()` per test method, singleton `TechnicianSessionService` listeners never unregistered between tests) surfaced a real, pre-existing race in `getDisplayName()`/`defaultDisplayName()` — both read a `volatile` field (`displayNamePreference`, `name`) twice (once in a null/blank guard, once to use it), a classic check-then-act race: another thread's `clear()`/`applyManualOverride(null, ...)` (both `notifyListeners()`-driven, delivered via `Platform.runLater` — so genuinely concurrent with whatever the FX thread is doing next, e.g. the next test's setup) can null the field out in the gap between the two reads, turning the second read into a `NullPointerException`. Silent under normal single-technician usage (nothing else mutates the session that fast), but reliably reproduced by the test suite's rapid setup/teardown. Fixed by snapshotting each `volatile` field into a local variable once and using that local throughout — a single consistent read no other thread can invalidate mid-method.

### History table: Autor column moved/widened, Equipos column silently never showed countables

**Fixed 2026-07-14**. Two independent changes:

- **Column order/width**: `AUTOR` moved to right after `FECHA` (was between `DESTINATARIO` and `EQUIPOS`) and widened (`prefWidth` 140→190, `maxWidth` 140→220) so a full name isn't clipped. Purely an `HistoryView.fxml` `<columns>` reorder — `TableColumn`s are bound by `fx:id`, so this has no code-side effect (export CSV/XLSX build their own column order independently, unaffected).
- **Equipos column bug**: the "Equipos" column (e.g. `"2A / 3C"`) is supposed to show both asset and countable item counts, but the countable half never rendered for any row in the History *table* — only in a note's detail popup. Root cause: `HistoryController`'s cell factory computed `countables` as `(r.getItems() != null ? r.getItems().size() : 0) - assets`, but `SqliteHistoryService.mapSummary()` (used by `getAll()`/`getFiltered()`, i.e. every row the History table itself renders) never populates `getItems()` — that full item list is only loaded separately, per-note, when opening the detail popup. So on every summary row this evaluated to `0 - assets` (always ≤ 0), silently hiding every countable-only or mixed note. Fixed by adding a proper SQL aggregate (`countable_count`, `SUM(CASE WHEN i.is_asset = 0 THEN 1 ELSE 0 END)`, alongside the existing `asset_count`) to `SqliteHistoryService.LIST_BASE_SQL`, a matching `NoteReport.countableItemCount` field, and reading it directly in the cell factory instead of deriving it from the (absent) items list.
- **Legend added**: a small `"A = Activo (con S/N) · C = Contable (por cantidad)"` label, bottom-right under the table (`HistoryView.fxml`) — added directly in response to this exact abbreviation causing repeated technician confusion.

---

## Known issues / gotchas

- Always run `mvn clean javafx:run` — never just `mvn javafx:run`. The IDE (VS Code / Eclipse) can write broken `.class` files that Maven reuses without recompiling.
- `config/app-config.json` must use `"baseUrl"` (not `"remoteUrl"`) in all API endpoint objects to match `AppConfig.ApiEndpoint`.
- **`AppKeyEncryptionService` replaced `WindowsDPAPIService`** (2026-07-08, explicit user decision after discussing trade-offs) — DPAPI ties every encrypted value to the specific Windows account that encrypted it, which made pre-configuring shared organizational credentials (AD token, GLPI key, SMTP/DB passwords — all four are org-wide shared credentials, not personal per-technician secrets) across many machines impractical without visiting each one. `AppKeyEncryptionService` uses a single AES-256/GCM key, shared across every installation, embedded in `AppKeyEncryptionService.KEY_BASE64` — no native/JNA dependency, works on any OS. **Accepted trade-off, stated explicitly to the user before implementing**: this is materially weaker than DPAPI against a determined local attacker — anyone with the installed app can decompile it, extract the key, and decrypt any copy of `data/noteapp.db`'s encrypted settings (not just their own). It stops casual plaintext exposure (e.g. opening the DB in a browser tool) but not a deliberate extraction attempt. If an installer is built later, generate a unique key per deployment at packaging time instead of reusing this one static key indefinitely — tracked as a known follow-up, not yet done. Rotating the key makes every previously-encrypted `APP_SETTINGS` value undecryptable (all four secrets would need re-entry) — `utils.AppKeyEncryptionGenerator` (mirrors `AdminPasswordHashGenerator`'s existing pattern) produces new ciphertext for `app-config.json`'s `defaults` section, which `ServiceLocator.provisionDefaultSecrets()` copies into `APP_SETTINGS` on first run only (never overwrites an admin's existing value).
- `WindowsIdentityService` (unrelated to the above — reads the Windows session UPN for AD username derivation, not encryption) still only works on Windows and still uses JNA.
- SLF4J "Failed to load class StaticLoggerBinder" at runtime is harmless — ControlsFX logs via SLF4J but the app functions normally without a binding.
- The sidebar's AD status dot (`MainController.circleAD`) now reflects real `AdApiService` reachability: `checkAdReachable()` queries by the current technician's already-resolved username (`TechnicianSessionService.getUsername()`), and a thrown exception (401/5xx/network failure) means genuinely "down," not a mock artifact. If the technician's username isn't resolved yet, the check is skipped for that cycle (shown as unreachable) rather than sending an unbounded query. A third gray "No configurado" state shows via `IADService.isConfigured()` when the AD URL/token aren't set.
- `.modern-table` (`styles.css`) is shared by History's `tblGlobal`, Settings' S/N Validation `tblSnValidation`, and the note-generation item tables `tblAssets`/`tblCountables` (`NoteGeneratorView.fxml`) — a selected-row text-color change intended only for one silently affects all four. Only History's `tblGlobal` intentionally kept the darker selected-row text from commit `349c9e0`; the other three carry an extra `equipment-table` class that overrides `.modern-table .table-row-cell:selected .table-cell`'s text-fill back to the original light color. If a future change needs to diverge these tables' styling further, extend `.equipment-table`'s rule rather than editing `.modern-table` directly — and check whether it should apply to `tblAssets`/`tblCountables` too, not just `tblSnValidation`.
- **`App.java` creates the Scene with a fixed size** (`visualBounds.getWidth() * 0.85`, `* 0.95`) — it does not grow to fit content. `MainView.fxml`'s `BorderPane` gives `left` (sidebar) and `center` (`contentArea`) the same actual height, so if any section's root content isn't height-bounded and its min-height exceeds the fixed window height, the whole row is forced taller than the visible viewport — the sidebar's bottom-anchored nav buttons and status dots get pushed down and off-screen, even though the sidebar's own content never changed. This is exactly what happened when the AD API card was added to `SettingsController`'s config list without a `ScrollPane`. Fixed by wrapping `panelSettings`'s card list in a `ScrollPane` (`styleClass="settings-scroll"`, styled transparent in `styles.css`) with `VBox.vgrow="ALWAYS"`, and moving the "Guardar Configuración" button to a fixed footer `HBox` outside the `ScrollPane`. **Any future card added to Settings must go inside that `ScrollPane`'s content `VBox`, not appended directly to `panelSettings`**, or this bug recurs. The same risk applies to any other section root that isn't already scrollable if its content can grow unboundedly.
- **The "CONFIGURACIÓN" title header must also live outside the `ScrollPane`, as a sibling before it** — fixed 2026-07-10: the header (`page-title-card` HBox) originally lived as the ScrollPane content `VBox`'s first child, so it scrolled away with the rest of the cards, unlike the already-fixed footer save button. Moved to be `panelSettings`'s first child, with its own `-fx-padding: 30 30 20 30` replacing the top/bottom portion of padding the content `VBox` used to own (now `0 30 20 30`), preserving the original visual spacing. **Any future top-level element meant to stay visible while scrolling (headers, footers, toolbars) must be a sibling of the `ScrollPane` inside `panelSettings`, not a child of its content `VBox`.**
