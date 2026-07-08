# Use Cases

## UC-01 — Generate a User Note (Entrega)

**Actor:** IT Technician  
**Trigger:** User selects "Entrega" profile and fills in user and equipment data  
**Preconditions:** App is running; equipment catalog is loaded (mock or DB)

**Main Flow:**
1. Technician selects "NOTA PARA USUARIO" → profile defaults to "ENTREGA"
2. Selects Motivo from dropdown
3. Searches for the recipient in AD (by DNI, name, or username); selects from results if multiple
4. Adds equipment items via the item dialog (Type → Brand → Model cascade; S/N and A/F if applicable)
5. Optionally fills Observaciones
6. Clicks "Generar PDF y Registrar"
7. Preview popup shows rendered note; technician selects Print / Email
8. Clicks "Generar" — note is printed/sent; report saved to history

**Alternate Flow A — AD unavailable:** Fields are filled manually  
**Alternate Flow B — Item limit exceeded:** Warning shown; second note recommended  
**Alternate Flow C — Print cancelled:** If "Imprimir" is checked and the technician cancels the OS print dialog, the whole generation is aborted — no email is sent and no History entry is created; status shows "Impresión cancelada — nota no generada" and the preview popup stays open so the technician can retry

---

## UC-02 — Generate a User Note (Devolución)

Same as UC-01 but with profile "DEVOLUCIÓN". No Motivo field. Used when equipment is returned.

---

## UC-03 — Generate a User Note (Fin de Contrato)

Same as UC-01 but with profile "FIN DE CONTRATO". Used for employees leaving the organization.

---

## UC-04 — Generate a Provider Note (Entrega - Proveedor)

**Actor:** IT Technician  
**Trigger:** Equipment received from or delivered to a supplier

**Main Flow:**
1. Technician selects "NOTA PARA PROVEEDOR"
2. Selects provider name and enters CUIT
3. Selects Motivo (Compra, Garantía, Reparación, Otro)
4. Optionally enables "Recibe en representación" and fills responsible person data
5. Adds equipment items
6. Generates note

---

## UC-05 — Add Equipment Item

**Actor:** IT Technician  
**Trigger:** Clicks "+ Agregar" in the equipment section

**Main Flow:**
1. Item dialog opens
2. Technician selects Type → Brand list filters → Model list filters
3. If Type is asset: S/N and A/F checkboxes appear (both unchecked by default)
   - Checking S/N enables the field; input is auto-uppercased; length is validated against config
   - Checking A/F enables the field; input is auto-formatted (e.g., "512" → "IT-00000512")
4. If Type is countable: Quantity spinner appears
5. Technician fills Observaciones (optional) and clicks "Agregar a la Nota"
6. Item appears in the appropriate table (Assets or Countables)

---

## UC-06 — Edit Equipment Item

**Actor:** IT Technician  
**Trigger:** Clicks "Editar" on an existing item row

**Main Flow:**
1. Item dialog opens pre-filled with existing item data
2. Technician modifies fields and saves
3. Table row updates in place

---

## UC-07 — Search User in Active Directory

**Actor:** IT Technician  
**Trigger:** Clicks "Buscar en AD" with at least one search field filled

**Main Flow:**
1. App calls IADService.search() with DNI, name, and/or username inputs — the real AD API ANDs whatever fields are supplied together (filling more fields narrows the result, it doesn't broaden it)
2. If one result: fields are auto-filled
3. If multiple results: AD user selection dialog shows a list with a hover popup showing DNI/email/OU; technician picks one
4. If no results: status label shows error feedback
5. If the AD API itself is unreachable or misconfigured (invalid/missing token): status label shows a distinct "could not connect" error rather than "not found"

**Variants:**
- DNI is queried both as plain digits and dotted (grouped by 3 from the right, e.g. "35123456" and "35.123.456") — both are real HTTP calls, results merged
- Name is queried both as typed and reordered as "Apellido, Nombre(s)" to match the stored display-name format — both are real HTTP calls, results merged
- Username is queried as typed (lowercased), partial match — no automatic "." vs "-" variant handling today (unlike DNI/name, this hasn't been implemented for the real API yet)

---

## UC-08 — View and Filter History

**Actor:** IT Technician  
**Trigger:** Clicks "Historial" in the sidebar

**Main Flow:**
1. History section loads; table shows all past reports (date, profile type, recipient, author, equipment count, GLPI sync status)
2. Technician applies optional filters: date range (DESDE/HASTA), note type (multi-select), GLPI status (multi-select), recipient/provider text, equipment type/brand/model (cascading multi-select)
3. Clicking "Buscar" or changing any filter reloads the table with matching results
4. Double-clicking a row opens the note detail popup (UC-12)
5. Technician can click "Limpiar" to reset all filters
6. Technician can export the current results via "Exportar" → CSV or Excel

**Alternate Flow A — Admin mode active:** GLPI Sync/Reject buttons appear in the detail popup for PENDING items (see UC-12)  
**Alternate Flow B — No matching records:** Table shows empty placeholder

---

## UC-09 — Manage Equipment Catalog (Database Section)

**Actor:** IT Technician  
**Trigger:** Clicks "Base de Datos" in the sidebar

**Main Flow:**
1. Three lists shown: Types, Brands, Models
2. Selecting a Type filters the Brands list
3. Selecting a Brand filters the Models list
4. Technician can add items (duplicate check on save) or remove items
5. "Generic" brand is protected from deletion

**Alternate Flow — Remote DB:** Technician enters DB URL; app verifies connectivity before accepting

---

## UC-10 — Configure Application Settings

**Actor:** IT Administrator (edit); IT Technician (read-only)  
**Trigger:** Clicks "Configuración" in the sidebar

**Preconditions:** Admin mode is active (see UC-13) — all fields and the save button are disabled otherwise

**Main Flow:**
1. Administrator configures A/F format (Prefix, Separator, Length, Filler); live preview shown
2. Configures SMTP sender address and password (stored encrypted via DPAPI)
3. Configures GLPI API URL and API Key (key stored encrypted via DPAPI, never in app-config.json)
4. Configures Active Directory API URL and Token (token stored encrypted via DPAPI, write-only — never redisplayed once saved)
5. Clicks "Guardar Configuración" → URL/format fields saved to app-config.json, credentials saved encrypted to DB; a status message appears next to the save button
6. Configuration content (A/F, SMTP, GLPI, AD, S/N validation entry point, Admin mode) scrolls independently within its own panel — the save button always stays visible in a fixed footer below it

**Alternate Flow A — Admin mode inactive:** all input fields and the save button are disabled (greyed out); technician can view current values but not change them

**Alternate Flow B — AD API connection test fails on save:** if an AD API URL is entered, saving first tests the connection in the background (using the current technician's own resolved username as the query) — the Save button shows "Probando..." and is disabled during the test. If the test fails, a confirmation dialog asks whether to save anyway; declining leaves the dialog open without saving.

---

## UC-11 — Send Note via Email

**Actor:** IT Technician  
**Trigger:** Selects "Enviar por correo" in the note generation popup

**Preconditions:** SMTP credentials are configured in Settings

**Main Flow:**
1. Technician enters recipient email address
2. Clicks "Generar"
3. App writes rendered HTML to a temp file, sends it as email attachment via Gmail SMTP
4. Temp file is deleted after send

**Failure Flow:** SMTP error is shown in status label; report is still saved to history

---

## UC-12 — View Note Detail / Admin GLPI Sync

**Actor:** IT Technician (read-only); IT Administrator (sync/reject actions)  
**Trigger:** Double-click on a row in the History table

**Main Flow:**
1. Note detail popup opens centered on the content area
2. Left panel shows the rendered HTML note (WebView)
3. Right panel shows item cards with type, brand, model, S/N/A/F, and current GLPI status badge
4. Technician reviews the note and closes the popup

**Alternate Flow — Admin mode active:**
1. Admin opens the popup from history; PENDING items show "Sincronizar" and "Rechazar" buttons
2. Admin clicks "Sincronizar" → item status updates to SYNCED; card refreshes
3. Admin clicks "Rechazar" → rejection reason dialog appears; admin enters reason; status updates to REJECTED
4. After any action, the history table in the background refreshes to reflect updated GLPI counts

---

## UC-13 — Activate Admin Mode

**Actor:** IT Administrator  
**Trigger:** Clicks "Activar modo administrador" in Configuración

**Main Flow:**
1. If no admin password is configured: prompt to set one; hash is stored in APP_SETTINGS (SHA-256)
2. If password is configured: prompt for password; verify against stored hash
3. On success: AdminSession activates; Settings label shows "Activo"; status label in sidebar updates
4. Admin actions (GLPI sync/reject, S/N validation edits, DB connection changes) are now accessible
5. Session expires automatically after 15 minutes of inactivity

---

## UC-14 — Manage S/N Validation Rules

**Actor:** IT Administrator  
**Trigger:** Clicks "Ver tabla" under "Validación de S/N por modelo" in Configuración

**Main Flow:**
1. Admin activates admin mode (UC-13) if not already active
2. Clicks "Ver tabla" — admin panel slides in showing all asset-type models with their regex and active toggle
3. Admin enables or disables a rule via the toggle; change is persisted immediately to SQLite
4. Admin uses the text filter to search by type, brand, or model
5. Clicks "← Volver" to return to Configuración

---

## UC-15 — View Technician Profile

**Actor:** IT Technician (view/refresh); IT Administrator (manual override)  
**Trigger:** Clicks "Mi Perfil" in the sidebar

**Preconditions:** None for viewing — identity resolution happens automatically at app startup, before this section is ever opened

**Main Flow:**
1. At app startup, the app reads the Windows session's UPN (domain email), derives the AD username from it, and looks up that user in Active Directory; Name, Username, Email, and DNI are held in memory for the current session only — never persisted to disk
2. Technician opens "Mi Perfil"; the four fields show the current session values, read-only (grayed out)
3. Technician clicks "Actualizar Perfil desde AD" to re-run the lookup on demand (e.g. if it failed at startup, or AD data changed)
4. Sidebar welcome message ("Hola, {nombre}!" / "Usuario: {username}") updates immediately to reflect any change

**Alternate Flow A — AD lookup fails:** A warning popup (and Profile's status label) distinguishes "user not found in AD" from "could not connect to AD" from "no Windows domain session available"; fields stay empty and read-only until a successful refresh or an admin override. Sidebar shows "Perfil no configurado" under the welcome message.  
**Alternate Flow B — Admin mode active:** Fields become editable; Administrator can manually enter or correct Name, Username, DNI, and Email; "Guardar" commits the override for the current session only — lost on the next AD refresh or app restart, not written to any database table.

**Note:** Technician identity is session-only and is never stored in a database table. When a note is generated, the current session's Name and DNI are copied as plain text directly onto that note's history record (not a reference to a shared profile row), so historical notes stay accurate even if the technician's AD identity changes later.
