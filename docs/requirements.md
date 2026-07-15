### Functional Requirements (FR)

##### 1. Core System & Logic

- **FR-01**: The system shall filter Type, Brand, and Model options dynamically based on the current selection in any of those three fields.

- **FR-02**: The system shall convert all Serial Numbers to uppercase by default, unless a specific override is toggled in the item configuration.

- **FR-03**: The system shall enforce S/N length validation based on a configurable Type/Brand mapping table.

- **FR-04**: The system shall automatically format A/F numbers based on four global parameters: Prefix, Separator, Length, and Filler character.

- **FR-05**: The system shall calculate the item quantity logic: allow 'Quantity' input only if 'S/N' is disabled.

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

- **FR-13**: The system shall require an Admin Password or Environment Key to modify critical settings (like Server IP). All general configuration fields in Settings (A/F format, SMTP, GLPI API URL/Key, AD API URL/Token) and the "Guardar Configuración" button are disabled unless an admin session is active. Saving a new database or AD API connection tests it first and requires confirmation before persisting if the test fails.

- **FR-14**: The system shall protect the "Generic" brand entry from deletion.

- **FR-15**: The system shall allow administrators to activate a timed admin session (15-minute inactivity expiry) via a password-protected toggle in Settings.

- **FR-16**: The system shall allow administrators to manage per-model S/N regex validation rules (enable/disable, view pattern) from an admin-protected table in Settings.

##### 5. History & Export

- **FR-17**: The history view shall provide multi-select filters for note type, GLPI status, equipment type, brand, and model, as well as date range and recipient text search.

- **FR-18**: Equipment filter values (type, brand, model) in the history view shall be populated from historical NOTE_ITEM data, not from the current live catalog.

- **FR-19**: The system shall support exporting the current filtered history to CSV (UTF-8 with BOM) and Excel (.xlsx) formats via a file-save dialog.

- **FR-20**: In admin mode, the note detail popup shall display Sync and Reject action buttons for each PENDING asset item, allowing per-item GLPI status management without leaving the history view.

### Non-Functional Requirements (NFR)

- **NFR-01 (UX)**: The application shall run as a windowed JavaFX desktop application with a sidebar-based dashboard layout.

- **NFR-02 (Reliability)**: The application must hide the terminal/console window during standard execution.

- **NFR-03 (Performance)**: Runtime database updates (new items) must be reflected in the UI within 5 seconds of the change (via polling or similar).

- **NFR-04 (Usability)**: The system shall provide a warning if a note contains more items than will fit on a single A4 page.

### User Stories

##### 1. Document Creation & Lifecycle

- **US 1.1** - **Per-Item Technical Detail Toggle**: As a user, I want a pop-up to show for each item I add to a note so that I can specifically select Type/Brand/Model, enable or disable S/N and A/F fields, or add a detailed note for that item only.
    - ***Acceptance Criteria***:
        - Both S/N and A/F fields must be disabled by default when adding an item.
        - The user must be able to toggle these fields via a modal or pop-up before the item is finalized in the list.
        - The printed/generated note must only display the enabled fields for that specific row.
        - Serial Numbers are automatically converted to uppercase by default. This can be toggled off with a checkbox (enabled by default).

- **US 1.2** - **Dynamic Asset Selection**: As a user, I want the Type, Brand, and Model selectors to filter each other dynamically so that I can only select valid equipment combinations (e.g., I shouldn't be able to select a "Samsung" Brand if I already selected "Laptop" and we only have Dell/HP laptops).
    - ***Acceptance Criteria***:
        - Selecting a value in any of the three fields must immediately update the available options in the remaining two.
        - The system must allow clearing a selection to "reset" the filters.
        - If the server is offline, the selectors must use the last known data stored in the local cache.

- **US 1.3** - **Advanced A/F Auto-Formatting**: As a user, I want to input only the numeric part of an A/F, so that the system automatically pads and prefixes it based on global settings (e.g., "512" becomes "IT-00000512").
    - ***Acceptance Criteria***:
        - Settings must allow configuration of Prefix, Separator, Length, and Filler character.
        - The formatting must be applied automatically to the output report.

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
