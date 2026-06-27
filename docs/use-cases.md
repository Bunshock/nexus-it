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
7. Preview popup shows rendered note; technician selects Print / Email / GLPI
8. Clicks "Generar" — note is printed/sent; report saved to history

**Alternate Flow A — AD unavailable:** Fields are filled manually  
**Alternate Flow B — Item limit exceeded:** Warning shown; second note recommended

---

## UC-02 — Generate a User Note (Devolución)

Same as UC-01 but with profile "DEVOLUCIÓN". No Motivo field. Used when equipment is returned.

---

## UC-03 — Generate a User Note (Fin de Contrato)

Same as UC-01 but with profile "FIN DE CONTRATO". Used for employees leaving the organization.

---

## UC-04 — Generate a Recambio Note

**Actor:** IT Technician  
**Trigger:** Equipment replacement scenario (old out, new in)

**Main Flow:**
1. Technician selects profile "RECAMBIO"
2. Selects Motivo (e.g., Falla, Obsolescencia)
3. Fills recipient data
4. Adds equipment items
5. System generates two notes: one Entrega (new equipment) and one Devolución (returned equipment)
6. Preview shows both; technician prints/sends

---

## UC-05 — Generate a Provider Note (Entrega - Proveedor)

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

## UC-06 — Add Equipment Item

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

## UC-07 — Edit Equipment Item

**Actor:** IT Technician  
**Trigger:** Clicks "Editar" on an existing item row

**Main Flow:**
1. Item dialog opens pre-filled with existing item data
2. Technician modifies fields and saves
3. Table row updates in place

---

## UC-08 — Search User in Active Directory

**Actor:** IT Technician  
**Trigger:** Clicks "Buscar en AD" with at least one search field filled

**Main Flow:**
1. App calls IADService.search() with DNI, name, and/or username inputs
2. If one result: fields are auto-filled
3. If multiple results: AD user selection dialog shows a list; technician picks one
4. If no results: status label shows error feedback

**Variants:**
- DNI with dots (e.g., "35.123.456") is normalized before search
- Username with "." vs "-" separator is tried both ways

---

## UC-09 — View History

**Actor:** IT Technician  
**Trigger:** Clicks "Historial" in the sidebar

**Main Flow:**
1. History section loads; table shows all past reports (date, profile type, recipient, GLPI sync status)
2. Technician can click "Actualizar" to refresh from DB

---

## UC-10 — Manage Equipment Catalog (Database Section)

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

## UC-11 — Configure Application Settings

**Actor:** IT Technician  
**Trigger:** Clicks "Configuración" in the sidebar

**Main Flow:**
1. Technician configures A/F format (Prefix, Separator, Length, Filler); live preview shown
2. Configures SMTP sender address and password (stored encrypted via DPAPI)
3. Configures GLPI API URL
4. Clicks "Guardar Configuración" → saved to app-config.json and DB

---

## UC-12 — Send Note via Email

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

## UC-13 — Manage Technician Profile

**Actor:** IT Technician  
**Trigger:** Clicks "Mi Perfil" in the sidebar

**Main Flow:**
1. Profile section loads with data from SQLite (pre-filled from Windows user + AD lookup at startup)
2. Technician edits name, DNI, email and saves
3. Data persists in `TECHNICIAN_PROFILE` table; used to auto-fill technician fields in notes
