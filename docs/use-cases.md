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
**Alternate Flow D — Missing technician profile or Sede:** Generation is blocked with a warning popup if the technician's AD profile (Name/DNI, resolved via Mi Perfil) or Sede (set in Configuración) isn't resolved/configured yet — every note must be traceable to both the technician and the site it was generated at

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
2. Selects a provider from the "PROVEEDOR" dropdown (populated from the provider catalog — see UC-09; not free text) and enters CUIT
3. Selects Motivo (Compra, Garantía, Reparación, Otro)
4. Optionally enables "Recibe en representación" and fills responsible person data
5. Adds equipment items
6. Generates note

**Alternate Flow — No provider selected:** "Generar Nota" is blocked with an inline "Debe seleccionar un proveedor" message until a provider is chosen. **Fixed 2026-07-10**: the provider dropdown used to be entirely non-functional (unpopulated, non-editable `ComboBox`) — every provider note generated through the live UI silently saved with a blank provider name. It's now backed by the provider catalog (UC-09) with this validation added since selection is meaningful.

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
1. "Buscar en AD" disables and shows a "Buscando" state (small spinner beside the button) while the search runs on a background thread — the search itself is a real HTTP call and no longer blocks the UI
2. App calls IADService.search() with DNI, name, and/or username inputs — the real AD API ANDs whatever fields are supplied together (filling more fields narrows the result, it doesn't broaden it)
3. If one result: fields are auto-filled, button returns to normal, and the DNI/name/username fields briefly highlight (fading back to their default border over ~2.65s)
4. If multiple results: AD user selection dialog shows a list with a hover popup showing DNI/email/OU; the button stays in its "Buscando" state (disabled, spinner visible) for as long as this dialog is open — it only returns to normal once the technician picks a user or clicks Cancelar
5. If no results: status label and field borders show red error feedback, same fade-back behavior as a successful match
6. If the AD API itself is unreachable or misconfigured (invalid/missing token): status label shows a distinct "could not connect" error rather than "not found"

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
2. Technician applies optional filters: date range (DESDE/HASTA), note type (multi-select), GLPI status (multi-select), author text, recipient/provider text, equipment type/brand/model (cascading multi-select)
3. Clicking "Buscar" or changing any filter reloads the table with matching results
4. Double-clicking a row opens the note detail popup (UC-12)
5. Technician can click "Limpiar" to reset all filters
6. Technician can export the current results via "Exportar" → CSV or Excel
7. Every time the technician navigates to Historial (including returning to it after visiting another section), the table automatically reloads with whatever filters are currently set — a note generated since the last visit appears without needing to click "Buscar" manually

**Alternate Flow A — Admin mode active:** GLPI Sync/Reject buttons appear in the detail popup for PENDING items (see UC-12)  
**Alternate Flow B — No matching records:** Table shows empty placeholder

**Note:** Step 7 was added 2026-07-10 — before this fix, the History section (like every section) is loaded once and cached for the session by `ViewFactory`, so revisiting it after generating a new note showed stale results until the technician manually clicked "Buscar".

---

## UC-09 — Manage Equipment Catalog and Provider List (Database Section)

**Actor:** IT Administrator (add/rename/remove); IT Technician (view only)  
**Trigger:** Clicks "Base de Datos" in the sidebar

**Main Flow:**
1. Four lists shown: Types, Brands, Models, Proveedores
2. Selecting a Type filters the Brands list; selecting a Brand filters the Models list (Proveedores is a flat, independent list — no cascade)
3. Add/Renombrar/Eliminar on any list prompts for the admin password if admin mode isn't already active (same `requireAdmin` gate as Configuración's fields)
4. "Genérico / Otro" brand is protected from deletion
5. A provider added here immediately becomes selectable in Nota de Proveedor's "PROVEEDOR" dropdown (UC-04) — including for that tab if it was already open earlier in the session, which re-fetches the provider list every time it's shown

**Alternate Flow — Remote DB:** Technician enters DB URL; app verifies connectivity before accepting

---

## UC-10 — Configure Application Settings

**Actor:** IT Administrator (edit); IT Technician (read-only)  
**Trigger:** Clicks "Configuración" in the sidebar

**Preconditions:** Admin mode is active (see UC-13) — all fields and the save button are disabled otherwise

**Main Flow:**
1. Administrator configures A/F format (Prefix, Separator, Length, Filler); live preview shown
2. Configures SMTP sender address and password (stored encrypted via AppKeyEncryptionService)
3. Configures GLPI API URL and API Key (key stored encrypted via AppKeyEncryptionService, never in app-config.json)
4. Configures Active Directory API URL and Token (token stored encrypted via AppKeyEncryptionService, write-only — never redisplayed once saved)
5. Clicks "Guardar Configuración" → URL/format fields saved to app-config.json, credentials saved encrypted to DB; a status message appears next to the save button
6. Configuration content (A/F, SMTP, GLPI, AD, S/N validation entry point, Admin mode) scrolls independently within its own panel — the "CONFIGURACIÓN" header and the save button both stay fixed in place; only the card list between them scrolls

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
4. Admin narrows the list using the Tipo/Marca/Modelo/Activo multi-select filter dropdowns (same checkbox-list "Todas" pattern as Historial's filters, UC-12) — Marca options are scoped to the selected Tipo(s), and Modelo options to the selected Tipo(s) and Marca(s); "Activo" filters by whether the row's validation is currently enabled (Sí/No, either or both)
5. "Limpiar filtros" resets all four dropdowns back to "Todas"
6. Clicks "← Volver" to return to Configuración

---

## UC-15 — View Technician Profile

**Actor:** IT Technician (view/refresh); IT Administrator (manual override)  
**Trigger:** Clicks "Mi Perfil" in the sidebar

**Preconditions:** None for viewing — identity resolution happens automatically at app startup, before this section is ever opened

**Main Flow:**
1. At app startup, the app reads the Windows session's UPN (domain email), derives the AD username from it, and looks up that user in Active Directory; Name, Username, Email, and DNI are held in memory for the current session only — never persisted to disk
2. Technician opens "Mi Perfil"; the four identity fields show the current session values, read-only (grayed out)
3. Technician clicks "Actualizar Perfil desde AD" to re-run the lookup on demand (e.g. if it failed at startup, or AD data changed)
4. Sidebar welcome message ("Hola, {nombre}!" / "Usuario: {username}") updates immediately to reflect any change
5. A separate "Nombre para mostrar" field (max 20 characters), always editable (no admin mode required), lets the technician set a personal greeting-name preference used only for the sidebar welcome message. It's pre-filled with a suggested default (the last word of the AD full name — the given name, since AD's stored order is "Apellido Nombre"). Clicking its own "Guardar" persists the preference locally, keyed by the technician's username, so it survives app restarts and AD refreshes
6. Clearing the field to blank and saving reverts the welcome message to the suggested default. A square reset button (↺) next to the field does this in one click — it clears the field and saves, equivalent to step 6 without manually emptying the field first
7. A separate "SEDE" field, in Configuración (not Mi Perfil), always editable (no admin mode required), lets the technician set their own site. It's shown in the sidebar next to the welcome message and printed on every note the technician generates — it's mandatory: generating a note or registering a Préstamo is blocked with a warning until it's set

**Alternate Flow A — AD lookup fails:** A warning popup (and Profile's status label) distinguishes "user not found in AD" from "could not connect to AD" from "no Windows domain session available"; the four identity fields stay empty and read-only until a successful refresh or an admin override. Sidebar shows "Perfil no configurado" under the welcome message. The "Nombre para mostrar" field is unaffected by this failure (it's independent of AD identity), but can't be saved without a resolved username to key it by.
**Alternate Flow B — Admin mode active:** The four identity fields become editable; Administrator can manually enter or correct Name, Username, DNI, and Email; "Guardar" commits the override for the current session only — lost on the next AD refresh or app restart, not written to any database table.

**Note:** The four AD identity fields (Name, Username, Email, DNI) are session-only and never stored in a database table. When a note is generated, the current session's Name and DNI are copied as plain text directly onto that note's history record (not a reference to a shared profile row), so historical notes stay accurate even if the technician's AD identity changes later. The "Nombre para mostrar" preference is the one exception — it's a personal display preference independent of AD identity, so it's persisted locally (`APP_SETTINGS`, keyed by username) and deliberately survives both AD refreshes and app restarts. Sede is a second such exception (set in Configuración, not Mi Perfil) — also persisted locally per technician username, and, unlike every other identity field, also snapshotted onto the note itself (`NOTE_REPORT.sede`) and printed on it, since it must stay mandatory and traceable per note.

---

## UC-16 — Log a Préstamo Directly (Cargar Nuevo Préstamo)

**Actor:** IT Technician
**Trigger:** Clicks "Préstamos" in the sidebar, then the "CARGAR NUEVO PRÉSTAMO" toggle
**Preconditions:** Technician profile is resolved (Name + DNI), same requirement as any other note generation

**Main Flow:**
1. Technician fills recipient Name/DNI (manually or via "Buscar en AD", same search as UC-07)
2. Optionally fills "Área / Evento" (e.g. a department or event the loan is for — the signer is still the person in step 1, this is just extra context)
3. Sets "Fecha tentativa de devolución" (defaults to the next working day; cannot be a past date)
4. Adds equipment items via the same item dialog as UC-05 (asset and/or countable)
5. Clicks "Guardar Préstamo"
6. Loan is saved directly to history — **no print dialog, no email, no rendered note is shown** — and a success message appears; the form clears

**Alternate Flow A — Missing technician profile or Sede:** Same warning as UC-01's Alternate Flow D
**Alternate Flow B — Validation failure:** Same inline red feedback as UC-01 (Name/DNI pattern, missing fecha tentativa, no items added)

**Note:** This is a second, deliberately separate way to create a Préstamo alongside the existing Generar Nota → Préstamo flow (UC-01), which still prints a physical note for the paper-folder process. Neither replaced the other — a loan logged here has no accompanying printed note, so it's meant for cases where the physical paperwork is handled separately (or not needed).

---

## UC-17 — View and Validate Préstamo Returns (Historial de Préstamos)

**Actor:** IT Technician (view only); IT Administrator (validate/reject returns)
**Trigger:** Clicks "Préstamos" in the sidebar, then the "HISTORIAL DE PRÉSTAMOS" toggle

**Main Flow:**
1. Table shows every Préstamo note (from either UC-01 or UC-16), with Fecha, Autor, Destinatario, Ítems, and Estado de Devolución columns
2. Each row is colored by return status: green (all items returned), orange (all pending), red (all lost), or a proportional gradient when mixed — same visual language as History's GLPI status coloring
3. A row with pending items whose tentative return date has passed shows an additional red outline and "(Vencido)" in the status text
4. Technician applies optional filters: date range, Estado de Devolución (multi-select: Pendiente/Devuelto/Perdido/Mixto/Vencido), destinatario/autor text search
5. Double-clicking a row opens the Préstamo detail popup

**Alternate Flow — Admin mode active:**
1. Admin opens the detail popup; PENDING items show "Validar devolución" and "Marcar como perdido" buttons
2. Admin clicks "Validar devolución" → item status updates to RETURNED; card refreshes
3. Admin clicks "Marcar como perdido" → a mandatory reason dialog appears; on confirm, status updates to LOST
4. After any action, the Préstamos table in the background refreshes to reflect the updated return counts

**Note:** This popup is separate from the regular History detail popup (UC-12) — reopening a Préstamo note from the regular Historial section shows only its GLPI sync status, not return status; return validation is only available from this Préstamos section.

---

## UC-18 — Generate a Remito de Envío Note

**Actor:** IT Technician
**Trigger:** Selects "REMITO DE ENVÍO" in Generar Nota, when equipment is being physically shipped to another sede
**Preconditions:** Technician profile is resolved (Name + DNI), same requirement as any other note generation

**Main Flow:**
1. Technician selects "REMITO DE ENVÍO" (third toggle, alongside "NOTA PARA USUARIO"/"NOTA PARA PROVEEDOR")
2. Fills Destinatario Nombre, Área, and Sede (all free text — Sede is a stand-in until a dedicated Sede catalog exists)
3. Fills Remitente Nombre (the IT Support coordinator sending the shipment — not necessarily the technician generating the note; always starts blank, no default) and, if needed, adjusts Remitente Área/Sede (both pre-filled from `app-config.json`'s `remito.remitenteArea`/`remitenteSede`, but editable per note)
4. Adds equipment items via the same item dialog as UC-05 (asset and/or countable) — the shared equipment tables and the note is subject to the same item-limit warning as any other note type
5. Clicks "Generar Reporte y Registrar"
6. Preview popup shows the rendered note; technician selects Print / Email as usual
7. Note is printed/sent; report saved to history

**Alternate Flow A — Missing technician profile or Sede:** Same warning as UC-01's Alternate Flow D
**Alternate Flow B — Validation failure:** Inline red feedback if any Destinatario or Remitente field is blank, or a Nombre field contains anything other than letters and single spaces

**Note:** Deliberately different from every other note type, matching its physical source document exactly: **no Motivo field**, **no DNI printed for either party**, **no signatures at all**, and **no "Observaciones Generales" field** — the shared footer used by the other two profile types is hidden specifically when this toggle is selected. The rendered note (`remito.html`) is also the only one of the app's templates printed in **landscape**, not portrait, matching its source spreadsheet. Equipment assets on a Remito still go through the normal GLPI Pendiente → Sincronizar/Rechazar workflow (unlike Préstamo, whose assets are marked N/A) — a Remito is treated as a real custody-affecting relocation. Fully integrated into Historial (UC-08, UC-12) like every other note type — filterable, exportable, reopenable/reprintable.
