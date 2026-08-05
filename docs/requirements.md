### Functional Requirements (FR)

##### 1. Core System & Logic

- **FR-01**: The system shall filter Type, Brand, and Model options dynamically based on the current selection in any of those three fields.

- **FR-02**: The system shall convert all Serial Numbers to uppercase by default, unless a specific override is toggled in the item configuration.

- **FR-03**: The system shall enforce S/N length validation based on a configurable Type/Brand mapping table.

- **FR-04**: The system shall derive the A/F value live from the item's Serial Number as `Prefix + Separator + SerialNumber` (both globally configurable), with no independent A/F entry — disabling S/N (via "Sin S/N") shall force-disable A/F inclusion too, since there is nothing to derive it from.

- **FR-05**: The system shall calculate the item quantity logic: allow 'Quantity' input only if 'S/N' is disabled.

- **FR-05a**: The system shall track a current stock quantity per Model, scoped per Type+Brand combination it is offered under (so the single shared "Genérico / Otro" model can carry an independent stock number for each Type+Brand it is used with), editable by an administrator from the Base de Datos section, with summed rollups shown at the Model, Brand, and Type level.

##### 2. Data & Persistence

- **FR-06**: The system shall maintain a local `.sqlite` cache of the inventory to allow read-only operation if the Spring Boot API is unreachable.

- **FR-07**: The system shall store a history log of every generated report in a local and/or remote database table.

- **FR-08**: The system shall persist form data in memory during a session to allow seamless switching between different note profiles.

- **FR-09**: The system shall allow exporting and importing note templates categorized by profile type.

##### 3. Integrations

- **FR-10**: The system shall perform AD lookups to retrieve user data (name, DNI, email, OU) based on a search query (DNI, name, and/or username), querying a configurable REST API endpoint.

- **FR-11**: The system shall interface with the GLPI API to update asset history and status variables upon report generation.

- **FR-12**: The system shall generate a temporary PDF and send it via SMTP if the "Send Email" option is selected.

##### 4. Security & Administration

- **FR-13**: The system shall require an Admin Password, Environment Key, or a sufficiently-privileged login role to modify critical settings (like Server IP). Every general configuration field in Settings (A/F prefix/separator, SMTP, GLPI API URL/Key, AD API URL/Token) is independently disabled unless the active session holds the specific permission for that field group — SMTP configuration additionally requires the SUPERADMIN role specifically, never satisfied by the shared admin password alone. Saving a new database or AD API connection tests it first and requires confirmation before persisting if the test fails.

- **FR-14**: The system shall protect the "Genérico / Otro" brand entry from deletion.

- **FR-15**: The system shall require every technician to log in (AD username + password) before showing the main application window, gate application access on AD group membership, and automatically activate a non-expiring admin session for accounts with the ADMIN or SUPERADMIN role — replacing the previous self-service, password-toggled admin session. A non-admin-role technician retains per-action password prompts for individual admin-gated actions (catalog edits, S/N validation edits), but that shared-password fallback can only ever grant ADMIN-level permissions, never SUPERADMIN-only ones.

- **FR-15a**: The system shall support a per-account role (USER/ADMIN/SUPERADMIN) and an assigned Sede, independent of AD group membership, resolved read-only at login; roles, Sede assignments, and permission grants are set by a database administrator via direct SQL against the APP_USER and ROLE_PERMISSION tables, not through an in-app screen.

- **FR-15b**: The system shall deny every admin-tier action by default and only permit it when the active session's role has been explicitly granted the matching permission — adding a new sensitive feature must never be silently reachable by every role unless a grant is added for it.

- **FR-15c**: The system shall restrict a plain ADMIN's approve/reject, GLPI sync, and return-validation actions to notes generated at that ADMIN's own assigned Sede, showing a warning when an ADMIN opens a note belonging to a different Sede; the SUPERADMIN role shall bypass this restriction and act on notes from any Sede.

- **FR-16**: The system shall allow administrators to manage per-model S/N regex validation rules (enable/disable, view pattern) from an admin-protected table in Settings.

##### 5. History & Export

- **FR-17**: The history view shall provide multi-select filters for note type, GLPI status, equipment type, brand, model, and Sede, as well as date range and recipient text search.

- **FR-18**: Equipment filter values (type, brand, model) in the history view shall be populated from historical NOTE_ITEM data, not from the current live catalog.

- **FR-19**: The system shall support exporting the current filtered history to CSV (UTF-8 with BOM) and Excel (.xlsx) formats via a file-save dialog.

- **FR-20**: In admin mode, the note detail popup shall display Sync and Reject action buttons for each PENDING asset item, allowing per-item GLPI status management without leaving the history view.

##### 6. Internal Equipment Loans (Préstamos)

- **FR-21**: The system shall allow a technician to log a Préstamo (internal equipment loan) directly, independent of the existing printed-note generation flow, capturing recipient Name/DNI, an optional Área/Evento context field, a mandatory tentative return date (not in the past), and one or more equipment items — without producing a printed note, email, or rendered HTML.

- **FR-22**: The system shall track a return status (Pendiente/Devuelto/Perdido) per item on every Préstamo note, for both serialized (asset) and countable items, independent of GLPI sync status.

- **FR-23**: The Préstamos Historial view shall color each row by aggregate return status (solid green/orange/red, or a proportional gradient for mixed statuses), visually flag any row with pending items past its tentative return date as overdue, and provide multi-select filters for return status and Sede alongside date range and recipient/author text search.

- **FR-24**: In admin mode, the Préstamo detail popup shall allow validating a pending item as returned or marking it as lost (with a mandatory reason), without affecting that item's separate GLPI sync status.

##### 7. Technician Identity & Traceability

- **FR-26**: The system shall require each technician to have a Sede (site) assigned by a database administrator (not a self-service preference), and shall block note generation (and direct Préstamo entry) with a warning until one is assigned — the same traceability requirement already enforced for the technician's AD-resolved Name/DNI. The assigned Sede shall always be visible in the sidebar (styled as a standing warning when unassigned) and snapshotted onto every generated note, printed on the note itself.

##### 8. Note Approval Workflow

- **FR-27**: The system shall require every newly-generated note to start in a PENDING approval state, and shall hide all item-level GLPI sync, Préstamo return, and Provider return action rows in the note detail popups until an administrator explicitly approves the note (APPROVED) — a rejected note (RECHAZADO, with a mandatory reason) is treated as void, with no item-level actions ever made available.

- **FR-28**: The history view shall show PENDING and APPROVED notes by default, with a checkbox to additionally reveal RECHAZADO notes; a sidebar badge on the Historial nav item shall show the count of notes currently awaiting approval.

##### 9. Provider Equipment Return Tracking

- **FR-29**: The system shall track a return status (Pendiente/Recibido/No recibido) per item on a Provider note whenever its Motivo is in an administrator-configured list of returnable motivos (e.g. Garantía, Reparación), using the same underlying mechanism as Préstamo return tracking (FR-22) but with Provider-appropriate wording, and leaving items on non-returnable Provider notes untracked (same as Entrega/Entrega Permanente).

##### 10. Inter-Sede Stock Transfer (Remito de Envío)

- **FR-30**: The system shall allow a technician to generate a Remito de Envío note, reachable from a dedicated "Envíos" sidebar group, shipping one or more equipment items from their own assigned Sede to either an administrator-curated destination Sede (destination label, address, and recipients auto-filled from that Sede's saved shipping info and disabled/read-only) or a freely-typed custom destination (the three fields cleared and made editable via a "Personalizar destino" checkbox), producing a printed note the same way as every other note type.

- **FR-31**: The system shall move stock (`MODEL_STOCK`) only when an administrator approves a Remito note — decrementing the source Sede's stock for every shipped item and, only when the destination is a real catalog Sede, incrementing that Sede's stock by the same amount — never at note generation time, and shall reject the approval outright (with no partial effect) if it would drive the source Sede's stock below zero for any item.

- **FR-32**: Remito note items shall not be tracked for GLPI sync or return status, since a Remito represents an inter-Sede stock movement rather than an assignment to a person or a loan expected to come back.

- **FR-33**: The system shall provide a dedicated "Historial de Envíos" view, reachable from the same "Envíos" sidebar group, scoped to Remito notes only, showing Destino/Dirección/Destinatarios and approval-status columns not present in the global Historial, with filtering by date, Estado, Destino, Autor, and Sede Origen (defaulting to the technician's own assigned Sede) — in addition to, not instead of, Remito notes' continued appearance in the global Historial.

### Non-Functional Requirements (NFR)

- **NFR-01 (UX)**: The application shall run as a windowed JavaFX desktop application with a sidebar-based dashboard layout.

- **NFR-02 (Reliability)**: The application must hide the terminal/console window during standard execution.

- **NFR-03 (Performance)**: Runtime database updates (new items) must be reflected in the UI within 5 seconds of the change (via polling or similar).

- **NFR-04 (Usability)**: The system shall provide a warning if a note contains more items than will fit on a single A4 page.

### User Stories

##### 1. Document Creation & Lifecycle

- **US 1.1** - **Per-Item Technical Detail Toggle**: As a user, I want a pop-up to show for each item I add to a note so that I can specifically select Type/Brand/Model, enable or disable S/N and A/F fields, or add a detailed note for that item only.
    - ***Acceptance Criteria***:
        - S/N is enabled by default; A/F is included by default (derived from S/N) — disabling S/N ("Sin S/N") forces A/F off too, since it has nothing to derive from.
        - The user must be able to toggle these fields via a modal or pop-up before the item is finalized in the list.
        - The printed/generated note must only display the enabled fields for that specific row.
        - Serial Numbers are automatically converted to uppercase by default. This can be toggled off with a checkbox (enabled by default).

- **US 1.2** - **Dynamic Asset Selection**: As a user, I want the Type, Brand, and Model selectors to filter each other dynamically so that I can only select valid equipment combinations (e.g., I shouldn't be able to select a "Samsung" Brand if I already selected "Laptop" and we only have Dell/HP laptops).
    - ***Acceptance Criteria***:
        - Selecting a value in any of the three fields must immediately update the available options in the remaining two.
        - The system must allow clearing a selection to "reset" the filters.
        - If the server is offline, the selectors must use the last known data stored in the local cache.

- **US 1.3** - **A/F Derived from Serial Number**: As a user, I want the A/F value to be computed automatically from the item's Serial Number, so that I never have to type it separately (e.g., S/N "AB12345678" with Prefix "IT" and Separator "-" becomes "IT-AB12345678").
    - ***Acceptance Criteria***:
        - Settings must allow configuration of Prefix and Separator.
        - The A/F field recomputes live as the Serial Number is typed and is not independently editable.

- **US 1.4** - **Item Quantity & S/N Logic**: As a user, I want to be able to specify a quantity for items that don't have a Serial Number, so that I can add multiple "Generic" items (like headsets) quickly.
    - ***Acceptance Criteria***:
        - The "Quantity" field is only enabled if "S/N" is disabled.
        - If Quantity = 1, the "Quantity" label should not appear in the final printed note.

- **US 1.5** - **Multi-Profile Data Persistence**: As a user, I want the application to persist all field data (user info, item lists, text areas) when switching between note profiles, so that I don't lose progress if I accidentally start a report in the wrong profile (e.g., switching from "Entrega" to "Entrega - Proveedor").
    - ***Acceptance Criteria***:
        - All shared fields must retain values during profile changes.
        - Item lists must persist across all profiles.
        - An alert must trigger if switching profiles would result in irreversible data loss.

- **US 1.6** - **Draft Templates (Export/Import)**: As a user, I want to save the current state of a note as a local template file, so that I can quickly reload frequently used configurations (e.g., a standard "Onboarding Kit").
    - ***Acceptance Criteria***:
        - Users can export the current form state (Fields + Item List) to a categorized local folder.
        - The import function must filter files based on the currently active profile (e.g., if in 'Entrega', don't show 'Devolución' templates).
        - Exported templates must be human-readable or structured (like JSON or XML) to ensure they can be recovered/imported correctly.

##### 2. Validation

- **US 2.1** - **S/N Length Enforcement**: As a user, I want the system to validate the length of a Serial Number based on the Type/Brand combination, so that I can prevent typos in critical equipment logs.
    - ***Acceptance Criteria***:
        - App looks up a validation table (Type + Brand = Expected Length).
        - Validation can be toggled off per-item in the configuration pop-up (accessed via the Settings section).

##### 3. Personal Information

- **US 3.1** - **Technician Personal Profile**: As a user, I want to save my own personal information (name, DNI, email) in a dedicated "Profile" section, so that the system can auto-fill the "recipient" fields in profiles like 'Devolución' without me re-typing it every time.
    - ***Acceptance Criteria***:
        - Personal data must be persisted locally on the client PC (e.g., in a local config file or the `.sqlite` DB).
        - The data must be editable at any time via the 'Profile' menu.
        - When switching to a profile that requires the technician's signature/data, these fields must auto-populate if the data is available.

##### 4. Data Integration (AD, GLPI & Email)

- **US 4.1** - **Active Directory (AD) Integration**: As a user, I want to search for a user by DNI, email, or name via AD, so that the "User Information" fields are auto-filled with official data.
    - ***Acceptance Criteria***:
        - "Search in AD" button must be available near the user data fields inside each profile.
        - The app must correctly map AD attributes to the local form fields.

- **US 4.2** - **Post-Generation Workflow**: As a user, I want a "Generation Dashboard" pop-up after clicking Generate, so that I can choose whether to Print, Email note to the user, or Sync to GLPI.
    - ***Acceptance Criteria**:
        - "Generate" button is disabled until at least one action (Print/Email/Sync) is selected.
        - Email option is only enabled if a valid recipient email is present.
        - The app must generate a temporary PDF for the email attachment and delete it after sending.

- **US 4.3** - **GLPI Synchronization**: As a user, I want the app to update the equipment's history and status in GLPI, so that our global inventory stays updated without manual double-entry.
    - ***Acceptance Criteria***:
        - App must update GLPI equipment variables (number of times an equipment was delivered, equipment in storage, ...) and history logs automatically.
        - Failure to sync with GLPI must be logged but not necessarily block the printing of the note.

##### 5. Inventory & Administration

- **US 5.1** - **Hybrid Relational Database Management**: As a user, I want a "Database" section to manage the catalog and manage the relationship between Types, Brands, and Models, so that the entire team uses the same equipment catalog.
    - ***Acceptance Criteria***:
        - Adding a new Type/Brand/Model checks for duplicates before committing.
        - Changes must update in real-time for all clients.
        - App must function in "Offline/Cache" mode if the DB server is down.
        - Security check must occur when changing the Database IP in settings.

- **US 5.2** - **Report History & Logging**: As a user, I want a local history ("History" section) of every generated report, so that I can review past handovers even if the GLPI sync was disabled.
    - ***Acceptance Criteria***:
        - The "History" view must show a table of past registries.
        - Each registry must include the timestamp, user, and items delivered/received.

- **US 5.3** - **History Advanced Filtering**: As a user, I want to filter the history by note type, GLPI status, equipment type/brand/model, date range, and recipient so that I can quickly locate specific past notes.
    - ***Acceptance Criteria***:
        - All filter dropdowns must support multi-select (selecting multiple values shows notes matching any of them).
        - Equipment brand and model dropdowns must cascade based on selected types.
        - Filter values for equipment must reflect what was historically recorded, not the current catalog.
        - Clicking "Limpiar" resets all filters and reloads the full history.

- **US 5.4** - **History Export**: As a user, I want to export the currently filtered history to CSV or Excel so that I can share reports or analyze them in a spreadsheet.
    - ***Acceptance Criteria***:
        - Export options appear in a dropdown on the History view.
        - CSV must use UTF-8 encoding with BOM for compatibility with Excel.
        - Excel export must include bold headers and auto-sized columns.

- **US 5.5** - **S/N Validation Management**: As an administrator, I want to view and toggle per-model S/N validation rules in Settings so that I can control which models enforce regex patterns without editing config files.
    - ***Acceptance Criteria***:
        - Table shows type, brand, model, regex pattern, and active toggle for all asset-type models.
        - Active rows sort to the top; multi-select Tipo/Marca/Modelo/Activo dropdown filters (same pattern as History's filters) narrow the list, with Tipo→Marca→Modelo cascading and a "Limpiar filtros" button to reset them.
        - Toggling active state is admin-protected.

- **US 5.6** - **Admin GLPI Sync via Note Detail**: As an administrator, I want to sync or reject individual PENDING items directly from the note detail popup so that I can manage GLPI status without a separate admin panel.
    - ***Acceptance Criteria***:
        - In admin mode, PENDING asset items in the note detail popup show Sync and Reject buttons.
        - Rejecting requires entering a rejection reason.
        - After a sync/reject action, the item card updates immediately and the history table refreshes.

##### 6. User Interface & Experience

- **US 6.1** - **Modern Dashboard Navigation**: As a user, I want a sidebar-based navigation system, so that I can easily switch between the Note Generator, History, Database, Settings and About sections without opening multiple windows.
    - ***Acceptance Criteria***:
        - The sidebar must be persistent on the left side of the app.
        - Clicking a menu item must swap the main content area without flickering or losing state.
        - The current organization logo must be visible at the top or bottom of the sidebar.

- **US 6.2** - **Adaptive Form Layout**: As a user, I want the form fields to be organized in a clean, modern grid that adapts to the window size, so that the app is comfortable to use on different monitors.
    - ***Acceptance Criteria**:
        - The UI must use custom CSS (modern flat design, specific organization colors).
        - Fields must be grouped logically (user info, item list, action buttons).

- **US 6.3** - **Visual Feedback & Status**: As a user, I want to see visual indicators for the server connection and system status, so that I know if I am working in "Offline/Cache" mode or connected to the API.
    - ***Acceptance Criteria***:
        - A status bar or header icon must show a green/red indicator for the Spring Boot API connection.
        - A warning message must appear if the app falls back to the local `.sqlite` cache.

##### 7. Internal Equipment Loans (Préstamos)

- **US 7.1** - **Direct Préstamo Entry**: As a technician, I want to log an internal equipment loan without going through the full note-generation/print flow, so that I can quickly record a loan that doesn't need (or already has) its own physical paperwork.
    - ***Acceptance Criteria***:
        - The "Cargar Nuevo Préstamo" form captures recipient Name/DNI (with AD search), an optional Área/Evento field, a mandatory tentative return date, and equipment items.
        - Saving does not open a print dialog, send an email, or render an HTML note — it saves directly to history.
        - The existing Generar Nota → Préstamo flow (which does print) is unaffected and remains available alongside this one.

- **US 7.2** - **Préstamo Return Tracking**: As a technician or administrator, I want to see which loaned items have been returned, are still pending, or were lost, so that I can follow up on overdue loans without cross-referencing a paper log.
    - ***Acceptance Criteria***:
        - Every item on a Préstamo note (asset or countable) has its own return status, independent of GLPI sync status.
        - The Préstamos Historial table color-codes rows by return status and flags overdue pending loans.
        - Only an active admin session can mark an item as returned or lost; marking an item lost requires a reason.

##### 8. Role-Based Access Control

- **US 8.1** - **Deny-by-Default Permissions**: As a superadmin, I want every admin-tier action to be denied by default and only enabled per role, so that a newly-added sensitive feature can never be silently accessible to everyone if I forget to protect it when I build it.
    - ***Acceptance Criteria***:
        - Every admin-tier action (catalog management, S/N validation edits, note approval, GLPI sync, return validation, profile overrides, and each Settings configuration field) is denied unless the active session's role has an explicit grant for it.
        - Permission grants are managed by editing a database table directly via SQL — there is no in-app screen for assigning permissions.
        - A brand-new admin-tier action added to the app in the future starts denied to every role until a grant row is added for it.

- **US 8.2** - **Superadmin-Only Sensitive Settings**: As a superadmin, I want SMTP configuration to be editable only by a real superadmin login, never by the shared admin password, so that a shared fallback credential can't reach the app's email-sending configuration.
    - ***Acceptance Criteria***:
        - The SMTP host/sender/password fields in Settings stay disabled for a plain ADMIN, including one who entered the shared admin password correctly.
        - The same fields become editable for a SUPERADMIN-role login.
        - Every other Settings field group (A/F format, GLPI, AD) remains reachable by a plain ADMIN, matching pre-existing behavior.

- **US 8.3** - **Sede-Scoped Admin Actions**: As an ADMIN assigned to one Sede, I want to only approve/reject notes, sync GLPI, or validate returns for notes generated at my own Sede, so that I can't accidentally act on another site's records.
    - ***Acceptance Criteria***:
        - Opening a note generated at a different Sede still shows its full contents, but the approve/reject, GLPI sync, and return-validation buttons are hidden.
        - A clear warning is shown explaining that the note belongs to another Sede and cannot be actioned by this admin.
        - A SUPERADMIN-role login can act on notes from any Sede, with no such restriction.

- **US 8.4** - **Superadmin-Assigned Sede**: As a technician, I want my Sede to be assigned by a superadmin rather than picked by me, so that the value used for both note traceability and admin-scoping is consistent and can't be self-reported incorrectly.
    - ***Acceptance Criteria***:
        - There is no Sede selector left anywhere in Settings or Mi Perfil — Sede is entirely read-only from the technician's perspective.
        - A technician with no Sede assigned sees a standing warning in the sidebar (not just a hidden/blank line) and a one-time popup at startup, and cannot generate a note or register a Préstamo until a superadmin assigns one.
        - A sidebar badge showing pending GLPI-sync/approval/return counts is scoped to a plain ADMIN's own Sede, and shows the global count for a SUPERADMIN or a non-admin technician.

##### 9. Inter-Sede Stock Transfer

- **US 9.1** - **Ship Equipment Between Sedes**: As a technician, I want to generate a note that ships equipment from my own Sede to another Sede (or a custom destination like a CAU), so that the transfer is documented and reflected in stock without a separate paper process.
    - ***Acceptance Criteria***:
        - "Remito de Envío" is a submenu under its own "Envíos" sidebar group, alongside "Historial de Envíos".
        - Source Sede is always the technician's own assigned Sede, shown read-only.
        - Destination is either a picked Sede (auto-filling saved destination/address/recipients as disabled/read-only fields) or a "Personalizar destino" custom entry with no catalog tie, which clears and enables the three fields for free typing.
        - The note previews, prints, and saves the same way as every other note type.

- **US 9.3** - **Dedicated Envíos History**: As a technician, I want a history view scoped to Remito notes with their own Destino/Dirección/Destinatarios columns, so that I don't have to read those details out of blank/generic global-history columns.
    - ***Acceptance Criteria***:
        - "Historial de Envíos" is reachable from the "Envíos" sidebar group, next to "Remito de Envío".
        - The table shows Fecha, Autor, Sede Origen, Destino, Dirección, Destinatarios, Ítems, and Estado, with a colored strip reflecting approval status (orange/red/green).
        - Filters: date range, Estado (multi-select), Destino text search, Autor text search, Sede Origen (multi-select, defaulting to the technician's own assigned Sede).
        - Double-clicking a row opens the same note detail popup used by the global Historial, including Aprobar/Rechazar when the session holds the permission.
        - Remito notes still also appear in the global Historial, unchanged — this view is additional, not a replacement.

- **US 9.2** - **Stock Moves Only on Approval**: As an administrator, I want a Remito's stock impact to happen only when I approve it, so that a mistakenly-generated transfer can be rejected without corrupting real stock counts.
    - ***Acceptance Criteria***:
        - A newly-generated Remito starts PENDING, same as every other note type, with no stock changed yet.
        - Approving it decrements the source Sede's stock for every item, and increments the destination Sede's stock by the same amount only if a real catalog Sede was chosen.
        - Rejecting it never touches stock.
        - Attempting to approve a Remito that would ship more than the source Sede currently has blocks the approval with an error, changing nothing.
        - Re-approving an already-approved Remito never applies the stock movement a second time.
