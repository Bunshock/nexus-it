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

### Progress snapshot — **Tier 3/4 all DECIDED as of 2026-09-07**

| Item | State |
|---|---|
| E1a status buckets Q1–Q5 | ✅ confirmed |
| E1a Q6 (tie-break) | ✅ **raise a D9 drift row** when `states_id` and `users_id` contradict — extends D9 to intra-GLPI inconsistency |
| E1b generic-id config shape | ✅ per-itemtype maps: `genericManufacturerId` (one, global), `genericTypeIds{itemtype→id}`, `genericModelIds{itemtype→id}`. Replaces D5b's flat pair (§8 revision). |
| E2a Sede = GLPI `Location` | ✅ confirmed |
| E2b address + recipients | ✅ address in GLPI `Location` (middleware reads it), no middleware `SEDE_SHIPPING_INFO`; recipients = deferred desktop-app feature |
| N1 backing type | ✅ adapter supports BOTH `Peripheral` and `Consumable`; Consumable return semantics TBD in the adapter build |
| F1 write identity | ✅ per-user: one shared `App-Token` (admin config), per-technician `user_token`; `PUT /me/glpi-token` + `APP_USER.glpi_token_encrypted` to design |

**What's left is data-gathering only (GLPI admin), not decisions** — see "GLPI-admin
data still owed" at the bottom.

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
| `34` En préstamo | **IN_USE** (held) | a loan = held; valid for Peripheral too | ✅ (Q4, user 2026-09-07) |
| `35` En tránsito | **UNAVAILABLE** | Remito in-flight state | ✅ (Q3) |
| `36` Instalado | **IN_USE** (in service) | "installed / deployed", not handoutable | ✅ (Q1) |
| `42` En reparación | **UNAVAILABLE** | | ✅ |
| `43` En garantía | **UNAVAILABLE** | "at the vendor on a warranty claim"; warranty *status* is the separate plugin field `findegarantafield` | ✅ (Q2) |
| `44`/`45`/`46`/`47`/`48` (Obsoleto/En baja/En reciclaje/En donación/Scrap) | **UNAVAILABLE** | | ✅ |
| `0` / null | **UNAVAILABLE** | verified: the 303 Multimedia items are all `states_id=0`. "Unknown" is not handoutable | ✅ (Q5, user 2026-09-07) |

**Per-`profileType` sync map** (middleware → GLPI on a `sync` step):
ENTREGA / ENTREGA PERMANENTE → `31`; PRÉSTAMO → `34`; DEVOLUCIÓN / any return
→ `33`; `lost` → `45` (En baja) or `48` (Scrap); Remito relocation → keep
state, `PUT /<itemtype>/{id} {"locations_id": <dest>}` (E2a).
Every write also stamps `motivodemovimientofield` (plugin Fields container id 8)
with the note ref + acting technician + reason.

**Q6 (tie-break) — ✅ DECIDED 2026-09-07.** GLPI stores availability (`states_id`)
and holder (`users_id`) separately and they can contradict (`states_id=33` En
stock but `users_id` set, or `states_id=31` En uso but `users_id` empty). Rule:
availability from `states_id`, holder from `users_id`, and on a contradiction
**raise a D9 reconciliation drift row** (`HOLDER_MISMATCH` / `STATE_MISMATCH`) —
never silently pick one. This extends D9 beyond its original app-expected-vs-GLPI
scope to also cover "GLPI's own two fields disagree." Fold into
`contract-behaviors.md` §M + `backend-contract.md` §14.

### E1b · generic Type / Manufacturer / Model ids  ✅ (config shape decided 2026-09-07; rows TBD in GLPI)

**Corrected model (user 2026-09-07):** a generic value is needed for **Type,
Manufacturer, and Model** — and Type & Model are **per GLPI itemtype**, they do
NOT share a global one. GLPI's `Manufacturer` is a single global dropdown, so one
generic manufacturer. So the adapter config is **per-itemtype maps**:

```
genericManufacturerId: <id>                                  # one, global
genericTypeIds:  { Computer: <id>, Peripheral: <id>, Phone: <id>, Monitor: <id>, … }
genericModelIds: { Computer: <id>, Peripheral: <id>, Phone: <id>, Monitor: <id>, … }
```

These live in `adapters/glpi/` config (D1 — field/status maps are adapter config).
`GET /config` surfaces to the desktop app whatever it needs for the "Genérico /
Otro" picker option per Type (the label + the ids, or a per-Type flag).

**Contract impact:** this replaces D5b's flat `genericBrandId` / `genericModelId`
in §8 with the per-itemtype map above — a real §8 / D5b revision to write.

**Manufacturer-is-global CONFIRMED 2026-09-07** — one "Genérico" manufacturer, no
per-category distinction.

**Rows TBD in GLPI:** the generic Type/Manufacturer/Model rows do not exist yet —
the org will create them ("Genérico" per category for Type/Model, one for
Manufacturer). Until then those config entries are `null` (D5b's degrade path:
item takes the picked GLPI record's real type/brand/model; for a not-in-catalog
countable, names = `genericLabel`, ids = null). GLPI-admin lookups owed once the
rows exist — see bottom.

### E2a · Sede = GLPI `Location`  ✅ CONFIRMED 2026-09-07

`Location` (`GET|POST|PUT|DELETE /api.php/v1/Location`) carries
`name(14)/address(15)/postcode(17)/town(18)/state(104)/country(105)/building(11)/
room(12)/alias(107)/lat-long(20-21)`, and is the `locations_id`(**search field 3**)
FK on **every** asset itemtype. Consequences:
- `?sedeId=` → `criteria[field]=3`.
- Locations are a **tree** → "at a Sede or below" = `searchtype=under`.
- Remito relocation = `PUT /<itemtype>/{id} {"locations_id": <dest>}`.
- Address for a Remito destination is read from the Sede's `Location` record (E2b).
- The middleware's Sede→GLPI-id map is a Location-id map (adapter config), built
  from `GET /Location?range=0-200`.
- **Owed:** run `GET /Location?range=0-200` to confirm the org's real sedes are
  already loaded as Location records (else they need creating).

### E2b · address + recipients  ✅ (decided 2026-09-07) — full design deferred

- **Address**: lives in **GLPI `Location`**. The middleware reads a Sede's address
  from its `Location` record — there is **no middleware `SEDE_SHIPPING_INFO`
  table**. (This reverses Claude's earlier "middleware store owns it" lean.)
- **Recipient person(s)**: a **new desktop-app-side feature**, deferred ("save for
  later"). Intended behaviour: selecting a Sede on a Remito de Envío auto-fills
  the address (from GLPI Location, via the middleware) and enables recipient input
  fields. Where recipients are stored (on the note vs. a per-Sede store) is part
  of that later design.
- **Strip impact**: on the v2 branch, `SEDE_SHIPPING_INFO` + the current
  `NOTE_REMITO_SEDE` / `NOTE_REMITO_OTHER` shape get reworked — Remito destination
  becomes a GLPI Location id (address derived) or free text; recipients captured
  on the note by the future feature. Don't design the full thing now.

### New items surfaced by the investigation (not in the original Tier 3)

- **N1** ✅ (decided 2026-09-07) — **the adapter handles BOTH backing types:
  GLPI `Peripheral` AND `Consumable`.** A catalog "type" carries which one it's
  backed by (adapter config / itemtype metadata). Peripheral = the verified
  per-unit `states_id`/`users_id` workflow (return/loss tracked). Consumable =
  the one-way `date_out` model — **open sub-question**: a Consumable-backed
  countable is *consumed*, not returned, so its note items likely have no RETURN
  tracking dimension at all (or only a "delivered" state); to be pinned down when
  the adapter's countable path is designed. Two code paths for
  countable availability / sync / return.
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

### F1 · middleware → GLPI identity for writes  ✅ (decided 2026-09-07) — **per-user**

GLPI auth = `App-Token` + per-user `user_token` → `Session-Token` (not OAuth).
**Decision:**
- **`App-Token`** — ONE value, configured middleware-side by an admin, shared by
  every technician. (F2 territory: how it's stored/rotated.)
- **`user_token`** — **each technician configures their own.** On a `sync` /
  `return` / `lost` write, the middleware opens a GLPI session with the shared
  `App-Token` + that technician's `user_token`, so the write is attributed to the
  real person in GLPI's native history. The middleware still ALSO stamps the note
  ref + reason into `motivodemovimientofield` / `comentariosdemovimientofield`,
  and the recipient into `contact`.

**Open mechanics (design when the adapter's auth is built):**
- **How does a technician supply their `user_token`?** There is no in-app config
  UI (D3). Proposal: a write-only `PUT /api/v1/me/glpi-token {token}` — the
  middleware stores it AES-encrypted keyed to the `APP_USER` row (F2 storage).
  Never returned by `GET`.
- **A technician with no `user_token` set** cannot perform `SYNC_EXTERNAL` /
  `VALIDATE_RETURNS` writes — the action returns a clear "configure your GLPI API
  token first" error. Onboarding step, surfaced by the desktop app.

### F2 · middleware secret storage/rotation  ⚪ (partly pulled in by F1)

Holds/rotates: the shared GLPI `App-Token` (middleware config), **each
technician's `user_token` (AES-encrypted per `APP_USER`)**, the AD API service
token, SMTP password. Mostly deployment/ops, but the per-user GLPI token store
is a real schema addition (`APP_USER.glpi_token_encrypted` or a sibling table) +
the `PUT /me/glpi-token` endpoint above.

---

## GLPI-admin data still owed (not decisions — lookups / row creation)

1. Create the generic rows in GLPI: one "Genérico" `Manufacturer`; one "Genérico"
   in each `<X>Type` and `<X>Model` list (Computer/Peripheral/Phone/Monitor/
   Printer + the GLPI-11 custom-asset Type/Model lists). Then look up their ids:
   `GET /Manufacturer`, `GET /ComputerType`/`ComputerModel`, `GET /PeripheralType`/
   `PeripheralModel`, … → fills `genericManufacturerId` / `genericTypeIds` /
   `genericModelIds`.
2. `GET /Location?range=0-200` — confirm the org's sedes exist as Location
   records; build the Sede→Location-id adapter-config map from the result (create
   any missing sedes).
3. (adapter build) confirm the exact `states_id` sync targets for `lost`
   (`45 En baja` vs `48 Scrap`) and whether Peripheral loans really use
   `34 En préstamo`.

## Tier 3/4 decisions — fold into

- `backend-contract.md`: **§8 / D5b** (flat `genericBrandId`/`genericModelId` →
  per-itemtype maps + `genericManufacturerId`); **§3.3 / §7.4** (Sede = Location,
  address read from Location, no middleware `SEDE_SHIPPING_INFO`); **§3.1 / §3.2 /
  §3.4** (both Peripheral and Consumable backing); **§2.6 / §7** (per-user GLPI
  identity, `PUT /me/glpi-token`, `APP_USER.glpi_token_encrypted`).
- `contract-behaviors.md`: status buckets, the Q6 tie-break, "no `user_token` →
  can't sync".
- This appendix: field-id maps, per-instance `listSearchOptions` resolution, the
  two-backing-type countable path.

Then: the strip (per `decisions-pending.md` D3/D4/D5, plus the E2b Remito rework)
→ `port/` + `adapters/glpi/` → §10 write queue → §14 reconciliation.

## Deferred (noted, not now)

- **Remito recipients feature** (desktop-app-side) — Sede select auto-fills
  address from GLPI Location + enables recipient input fields. E2b, "save for
  later".
- **Consumable-backed countable return semantics** — likely no RETURN dimension
  (consumed, not returned); pin down when the countable adapter path is designed.
- **F2** secret storage/rotation details (deploy phase), beyond the per-user GLPI
  token store F1 forces.
