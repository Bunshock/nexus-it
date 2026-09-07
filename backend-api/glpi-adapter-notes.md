# GLPI Adapter — Tier 3/4 progress + resolved adapter facts

Living tracker for the GLPI-adapter version of the middleware. Companion to
`decisions-pending.md` (D1–D9, resolved) and `backend-contract.md`. This is the
"GLPI adapter notes" appendix the contract calls for.

**Branch**: `backend-api-v2-glpi-adapter` (off `backend-api-v1-no-adapter`).
**Decision (2026-09-07)**: v2/adapter *replaces* the no-adapter version — it's the
single path forward, not a parallel track. The strip of the no-adapter
compensations happens *after* Tier 3/4 is closed and folded into the contract,
not before (avoids a long broken half-state + guessing on open items).

**Source docs** (in the user's `Downloads/`, not the repo):
`api-v1-endpoints.md`, `endpoints-inventario.md` — live introspection against
**dev `<dev-glpi-host>`, GLPI 11.0.7**, HL API `api.php/v1/`, auth =
`App-Token` + per-user `user_token` → `Session-Token` (NOT OAuth on this
instance). Plus `GLPI - normalization details.md` (OneDrive, ComputerModel
data-cleanup — informs E1b).

Status legend: ✅ resolved · 🟡 recommendation drafted, needs user confirm ·
🔴 blocked on a GLPI-admin lookup · ⚪ not started.

---

## Tier 3 — External facts

### E1a · status (`State`) → `AVAILABLE`/`IN_USE`/`UNAVAILABLE` map

**Verified `State` list** (`endpoints-inventario.md` §Estados):
`31` En uso · `32` ACTIVOS FÍSICOS · `33` En stock · `34` En préstamo ·
`35` En tránsito · `36` Instalado · `37` SOFTWARE/LICENCIAS · `38` Disponible ·
`39` Asignada · `40` En uso (dup) · `41` Expirada · `42` En reparación ·
`43` En garantía · `44` Obsoleto · `45` En baja · `46` En reciclaje ·
`47` En donación · `48` Scrap.

`32`/`37` are branch parents (not real states). `38`–`41` are the **license**
branch — irrelevant to asset/countable availability.

Verified Peripheral stock workflow (`endpoints-inventario.md` §2, defined
2026-08-27): alta `states_id=33` (En stock) → entrega `users_id` +
`states_id=31` (En uso) + `motivodemovimientofield` → devolución: clear
`users_id` + `states_id=33`. → **`33` = AVAILABLE, `31` = IN_USE, confirmed for
both Computer and Peripheral.**

| state | proposed bucket | basis | status |
|---|---|---|---|
| `33` En stock | **AVAILABLE** | verified stock workflow | ✅ |
| `31` En uso | **IN_USE** | verified stock workflow | ✅ |
| `40` En uso (dup) | **IN_USE** | duplicate of 31 | ✅ |
| `34` En préstamo | **IN_USE** (held) | a loan = held by someone | 🟡 Q4 |
| `35` En tránsito | **UNAVAILABLE** | this is the Remito in-flight state; not handoutable at either end | 🟡 Q3 |
| `36` Instalado | **IN_USE** (in service) | "installed / deployed" — proposed not available for hand-out | 🟡 Q1 |
| `42` En reparación | **UNAVAILABLE** | | ✅ |
| `43` En garantía | **UNAVAILABLE** | proposed: means "at the vendor on a warranty claim". Warranty *status* is tracked separately in plugin Fields `findegarantafield` (obsolescence container id 6), so the *state* 43 is the physical-return meaning | 🟡 Q2 |
| `44`/`45`/`46`/`47`/`48` (Obsoleto/En baja/En reciclaje/En donación/Scrap) | **UNAVAILABLE** | | ✅ |
| `0` / null | **UNAVAILABLE** | verified: the 303 Multimedia items are all `states_id=0` ("sin estado cargado"). "Unknown" must not be handoutable | 🟡 Q5 |

**Per-`profileType` sync map** (middleware → GLPI on a `sync` step):
ENTREGA / ENTREGA PERMANENTE → `31`; PRÉSTAMO → `34`; DEVOLUCIÓN / any return
→ `33`; `lost` → `45` (En baja) or `48` (Scrap); Remito relocation → keep
state, `PUT /<itemtype>/{id} {"locations_id": <dest>}` (E2a).
Every write also stamps `motivodemovimientofield` (plugin Fields container id 8)
with the note ref + acting technician + reason.

**6 residual questions for the user** (🟡 — need org confirm):
1. **Q1** `36 Instalado` → IN_USE (in service, not handoutable), AVAILABLE, or other?
2. **Q2** `43 En garantía` → physically returned to vendor (UNAVAILABLE), or just a
   flag on a still-usable asset (ignore for availability)?
3. **Q3** `35 En tránsito` → confirm UNAVAILABLE for hand-out / stock count.
4. **Q4** `34 En préstamo` — valid for **Peripheral** too (Préstamo carries
   countables), or Peripheral loans use a different state?
5. **Q5** `0`/null → UNAVAILABLE — agreed?
6. **Q6** Tie-break: when `states_id` and `users_id` disagree, the middleware
   derives *availability* from `states_id`, takes *holder* from `users_id`, and
   flags the disagreement as reconciliation drift (`HOLDER_MISMATCH` /
   `STATE_MISMATCH`). Agreed? — **recommended: adopt as stated** (states_id is
   the authoritative availability signal; this is exactly what D9 exists for).

### E1b · generic model / generic manufacturer  🔴

- `config.genericBrandId` — the generic **Manufacturer** id. D5b says it's
  "confirmed present"; the real id still needs a `GET /Manufacturer` lookup.
- `config.genericModelId` — `GLPI - normalization details.md` shows ComputerModel
  has a `-` placeholder row and an explicit "**Para agregar: Genérico**" TODO —
  i.e. no proper generic model exists yet, the org intends to create one.
  For **PeripheralModel** (what countables use): still needs `GET /PeripheralModel`
  to check for an existing generic row.
- **State**: `config.genericModelId = null` (D5b's documented fallback) until a
  real "Genérico" model is created in GLPI. Two lookups still owed by the GLPI
  admin: (a) `GET /Manufacturer` → generic id, (b) `GET /PeripheralModel` →
  generic row? .

### E2a · Sede = GLPI `Location`  ✅

Confirmed by `endpoints-inventario.md` §4 ("Sede (datos para envíos) → Location,
recomendado"). `Location` carries `name/address/postcode/town/state/country/
code/alias/building/room/comment/lat/long` and is the `locations_id`(3) FK on
**every** asset type → per-Sede reports for free. Consequences:
- `?sedeId=` filters map to `locations_id`.
- Locations are a **tree** → "at a Sede" = that node OR any descendant
  (`searchtype=under`).
- Remito relocation = `PUT /<itemtype>/{id} {"locations_id": <dest>}`.
- The middleware's Sede→GLPI-id map is a Location-id map (adapter config).

### E2b · where `SEDE_SHIPPING_INFO` lives  🟡 (recommendation: middleware store)

`Location` holds the **address** half natively. The **recipient person(s)** half
has no clean native home — GLPI `Contact` exists (0 rows today, clean start) but
links to Suppliers, not Locations/assets. **Recommendation, evidence-backed:**
the middleware's own store owns `SEDE_SHIPPING_INFO` in full (label + address +
recipients), seeded once, edited out-of-band; GLPI `Location` stays SSOT for
*where an asset is*, the middleware store for *how to address a shipment there*.
This is already how `backend-api-v1-no-adapter` has it. **Needs user's final
confirm.**

### New items surfaced by the investigation (not in the original Tier 3)

- **N1** 🟡 — some "countables" (chargers, paper, toner) fit GLPI **`Consumable`**
  better than `Peripheral` (`endpoints-inventario.md` §2 note, §10). Both
  Consumable catalogs are empty today.
  **Recommendation: the adapter models every countable as a GLPI `Peripheral`
  (1 record per unit), NOT `Consumable`.** Rationale: the app's countable model
  is "1 row per unit with return/loss tracking", which maps 1:1 to Peripheral's
  verified per-unit `states_id`/`users_id` workflow; `Consumable` is one-way
  (`date_out`, no structured return) and doesn't fit return tracking; the org's
  Consumable catalogs are unused anyway. Revisit only if a real
  paper/toner-consumption use case appears. **Needs org confirm.**
- **N2** ✅ (adapter note only) — the Type catalog spans 6+ GLPI itemtypes
  (Computer / Peripheral / Phone / Monitor / Printer + GLPI-11 custom assets
  Multimedia / AudioEquipment / Security / Misc — the last as URL-encoded FQCNs
  `Glpi%5CCustomAsset%5C…`). `GET /catalog/types` flattens them; catalog ids
  carry a composite id (itemtype + local id). Contract already says catalog ids
  are opaque strings → no contract change.
- **N3** ✅ (lock in) — `fieldID`s vary per instance (`4=serial` in an old skill
  vs `5=serial` / `6=otherserial` today, per `endpoints-inventario.md` §Nota).
  Field-name maps are adapter config, resolved per instance at startup via
  `GET /listSearchOptions/<Itemtype>`. Never cache field ids across instances.
- **N4** → folded into F1 below.

### Confirmed field mappings (adapter config, fold into the appendix)

- `otherserial` (field 6) = the A/F / "número de inventario", today `IT-XXXXX` →
  `af` = GLPI `otherserial`, server-side, read-only for the app.
- `serial` (field 5) = the S/N the app searches by partial
  (`criteria[field]=5&searchtype=contains`).
- `users_id` (field 70) = assigned user (the holder). `contact` (field 7) =
  free-text alternate/responsible user, not a GLPI account.
- Phone line numbers are `SoftwareLicense` associations ("Línea móvil" Software),
  not a direct field — the app doesn't model lines structurally, so this is a
  read-only detail at most.
- Movement stamp: plugin Fields container **id 8 "Motivos de Movimientos"** →
  `motivodemovimientofield` (dropdown), `comentariosdemovimientofield` (text),
  `nmeroitviejofield`. The middleware writes the note ref + reason + acting tech
  here on every sync.

---

## Tier 4 — Middleware-internal

### F1 · middleware → GLPI identity for writes  🟡 (recommendation: service account)

GLPI auth on this instance = `App-Token` + per-user `user_token` → `Session-Token`
(not OAuth). So "act as the real technician" vs "one service account" is a real
fork.

- **(a) Per-user** — the middleware holds each technician's `user_token`, acts as
  them in GLPI. GLPI's own "modified by" then shows the real actor. Cost: every
  technician must be provisioned in GLPI with an API token + a Keycloak→GLPI
  token path. Heavy.
- **(b) Service account** — the middleware holds ONE `user_token` for a dedicated
  GLPI service user. All GLPI writes attributed to that account; the real
  technician is in the middleware's own `AUDIT_*` tables AND stamped into
  `motivodemovimientofield` / `comentariosdemovimientofield` on every write, and
  the recipient goes in `contact`.

**Recommendation: (b) service account.** The middleware already has a complete
per-user audit trail; the movement-reason field carries the acting tech + note
ref so GLPI history isn't blind; no per-tech GLPI provisioning or token-exchange
path needed. Only real loss: GLPI's native "modified by" shows the service
account. **Needs user confirm.**

### F2 · middleware secret storage/rotation  ⚪

How the middleware holds/rotates its GLPI `App-Token` + service-account
`user_token`, AD bind account, SMTP password. Deployment/ops — not a contract
item. Defer to the deploy phase.

---

## Once Tier 3/4 is confirmed — fold into

`backend-contract.md` §3.1 / §3.2 / §3.4 / §7.4 / §13 · `contract-behaviors.md`
(status buckets, tie-break) · this appendix (field maps, per-instance config).
Then: the strip (per `decisions-pending.md` D3/D4/D5) + `port/` + `adapters/glpi/`
+ the §10 write queue + §14 reconciliation.
