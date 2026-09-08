# Backend API Contract — Behaviors (the testable invariants)

Status: **DRAFT** — started 2026-08-31.
Branch: `backend-api-middleware-contract` (off `backend-api`).
Companions: `backend-contract.md` (the "what"), `auth-flow.md` (auth
sequence), `new-middleware-app.txt` on OneDrive (the "why").

This file is the **"must pass"**. Every item is a black-box check against
the API. Any implementation that serves `backend-contract.md` and passes
every behavior here is a valid backend — that's what keeps "swap the
backend later" honest.

Conventions: **G**iven / **W**hen / **T**hen. "the client" = an
authenticated caller holding a valid middleware `sessionToken` unless
stated otherwise. Naming is backend-neutral per `backend-contract.md` §1 —
"the external system" is GLPI today.

The `A.` / `H.` groups below were first drafted in `backend-contract.md`
§7.5 (adopted from a parallel contract effort) and are restated here so the
whole suite lives in one place.

---

## A. Auth & identity  (→ contract §2, §7.5)

- **A1** — G no token, or an invalid / expired one · W any call · T `401`, and **no state change**.
- **A2** — G a valid token · W `GET /me` · T returns the caller's `username`, `role`, `sedeId`, `permissions[]`. Identity is **never** read from a request body, on this or any other endpoint.
- **A3** — G two different users' sessions · W each acts · T each resulting audit row (§I) names the **real acting user**, never a shared account.
- **A4** — G an expired `sessionToken` · W any call · T `401`; the client silently re-runs the OIDC flow and retries once (contract §2.2). Only a failed re-auth surfaces a "must sign in" state.
- **A5** — G a valid external-IdP token, but the account is not registered / not in the allowed group and has no bypass flag · W `POST /auth/login` · T `403`, no session issued.
- **A6** — `POST /auth/login` validates the IdP token's signature (JWKS), `iss`, `exp`, **and `aud`** (must equal the configured client id) — a realm-issued token for a *different* client is rejected. (Currently unimplemented — `IdpTokenValidator` checks only `iss`/`exp`/signature; tracked as a build task.)
- **A7 (F1)** — the middleware makes every GLPI **write** as the acting technician's own `user_token`; reads use the shared `App-Token`. No `user_token` → `409 GLPI_TOKEN_NOT_SET` on any write step (see H9).

## B. Note lifecycle  (→ contract §5)

- **B1** — W `POST /notes` (any `profileType`) · T `201`, `approvalStatus = PENDING`, and `technicianName` / `technicianDni` are set from the **token**, not the body (which does not carry them — §2.5).
- **B2** — G a note with zero items · W `POST /notes` · T `400`, nothing created.
- **B3** — G a PENDING note · W `reject` with no `reason` · T `400`, note stays PENDING.
- **B4** — G a PENDING note · W `reject` with a reason · T `approvalStatus = REJECTED`; **no** item-level step ever becomes actionable for that note afterward.
- **B5** — G an APPROVED or REJECTED note · W `approve` or `reject` · T `409` (only a PENDING note is actionable).
- **B6** — W `GET /notes` with no `approvalStatuses` filter · T returns PENDING + APPROVED, **excludes** REJECTED.
- **B7** — G `approve` / `reject` on a note whose Sede the caller (plain ADMIN) isn't scoped to · T `403`; `GET /notes/{id}` on the same note still succeeds (H2).
- **B8** — a stored note item's `typeId`/`typeName`/`brandId`/`brandName`/`modelId`/`modelName` (and `serialNumber`/`af`) are captured at creation and **immutable** — a later external rename / reclassification / deletion of that catalog entry never alters an existing note. `GET /notes/{id}` returns the values as of creation (decision D5).
- **B9** — W `POST /notes` with `profileType = "REMITO DE ENVÍO"` and no `destinationSedeId`, or one whose Sede has no GLPI `Location` mapping (E2b) · T `422 SEDE_LOCATION_NOT_MAPPED`, nothing created. There is no free-text / custom destination and no `destinationRecipients` field in v2.0.

## C. Item flow steps  (→ contract §4, §4.0)

- **C1** — G a PENDING or REJECTED note · W any item step action (`sync`, `return`, …) · T `409` — steps are unreachable until the note is APPROVED.
- **C2** — G an APPROVED note; an **asset** item at its `sync` step · W `sync` · T `200`, step advances, **and the external system is actually updated** (holder / status / location per `profileType`), with an external-side history entry.
- **C3** — G an APPROVED note; a **countable** item at its `sync` step · W `sync` · T `200` and N external records are allocated (contract §4.1). *(Deliberate divergence from the parallel contract, which rejects countable sync — under this contract countables sync like assets; companion §4.6.)*
- **C4** — W `reject-sync` with no `reason` · T `400`.
- **C5** — G an item whose `sync` step is already advanced · W `sync` again · T `409` — never a second external write. *(Idempotency via the flow's own state guard; §K2.)*
- **C6** — G an APPROVED note; item A rejected at its `sync` step · T item A's flow ends; sibling items proceed through their own flows independently; the note's `approvalStatus` is unchanged.
- **C7** — per-item step actions are Sede-scoped for a plain ADMIN exactly as note approval is (H2 / H3).
- **C8** — a completed `sync` step sets the external record's `states_id` per the note's `profileType` (E1a `syncTargets`): ENTREGA / ENTREGA PERMANENTE → `31`, PRÉSTAMO → `34`, DEVOLUCIÓN → `33`. Same target for asset and countable. The write also stamps GLPI's movement-reason field with the note reference.
- **C8b** — a `sync` step on an `ENTREGA - PROVEEDOR` note sets `states_id` from `syncTargets.proveedor[motivo]`: `Garantía → 43`, `Reparación → 42`, `Devolución de préstamo → 45`, `Otro → 42`; it also clears `users_id` and writes the provider name + CUIT into `contact`. A returnable-Motivo (`Garantía` / `Reparación`) `return` step → `states_id 33`, `contact` cleared.
- **C8c** — a `syncTargets.proveedor` value of the sentinel `NONE` → the `sync` step completes (step → `SYNCED`) with **no external write**. No current Motivo maps to `NONE`.
- **C8d** — `POST /notes` for an `ENTREGA - PROVEEDOR` note whose Motivo has **no** `syncTargets.proveedor` entry (neither a state nor `NONE`) · T `422 PROVIDER_MOTIVO_NOT_CONFIGURED`; the note is not created.

## D. Return tracking  (→ contract §4.3, §4.4)

- **D1** — G an APPROVED PRÉSTAMO note; an item at its `return` step · W `return` (whole item) · T `200`, step → RETURNED, holder cleared and `states_id → 33` (En stock) — unless the record was `UNAVAILABLE`, where the holder clears but `states_id` is left as-is (D6a / F1b).
- **D2** — W `lost` with no `reason` · T `400`.
- **D3** — G a countable item, synced quantity 5 · W partial returns of 3 then 3 · T the second → `400`; cumulative (returned + lost) may never exceed the synced quantity.
- **D4** — G a note whose flow has **no** `return` step (ENTREGA / ENTREGA PERMANENTE / DEVOLUCIÓN, or a Provider note whose Motivo isn't on the returnable list) · W `return` / `lost` any item · T `409`.
- **D5** — a `sync`-step status and a `return`-step status are **independent**: advancing one never changes the other.
- **D6** — `lost` sets the external record's `states_id → 45` (En baja) — **not** `48` (Scrap), and **not** back to `33` / AVAILABLE — and clears the holder.

## E. Stock — derived ("Model Y")  (→ contract §3.4)

- **E1** — there is **no** endpoint that reads or writes a stored stock number. "Stock" is only ever a live derived count of AVAILABLE records (`GET /catalog/countables/availability`, and the `state` on `GET /catalog/assets/search`).
- **E2** — W `approve` any note · T **no** availability count changes anywhere. Approval moves no stock.
- **E3** — W a `sync` step completes for an ENTREGA/PERMANENTE/Provider item · T the affected records flip to IN_USE → the derived AVAILABLE count for that model+Sede drops by exactly that many. W a `sync` step completes for a DEVOLUCIÓN item · T records flip to AVAILABLE → the count rises.
- **E4** — W a `sync` step completes for a REMITO item · T the affected record's **`locations_id`** moves from the note's Sede's Location to the `destinationSedeId`'s Location (E2a); `states_id` and holder are unchanged. The derived AVAILABLE count drops at the source Sede and rises at the destination. (v2.0 has no custom/non-catalog destination — E2b.)
- **E5** — a shortage (fewer AVAILABLE than the note's quantity, at sync time) is **not** a synchronous error — it is the §L4 queued-job `FAILED` outcome.
- **E6** — the item dialog's pre-generation shortage warning reads live derived availability and is **advisory only**; it never blocks note creation.
- **E7** — there is no "modifies stock" item flag (removed, D4). Every item's `sync` updates the external record's holder/state; if that record is already in the intended state (a pure formalization), `sync` is a harmless no-op and the derived count is unchanged.
- **E8 (N1)** — for v2.0 every countable is backed by GLPI `Peripheral`; there is no `Consumable` path and no per-Type backing flag. Availability = count of `Peripheral` records for the model with `states_id` in the AVAILABLE bucket, `locations_id` under the Sede's Location subtree.

## F. Asset state — warnings and blocks  (→ contract §3.2, §5.1, §4.1)

- **F1 (hard block — D6a)** — G a DEVOLUCIÓN note; an asset item whose **current external state is `AVAILABLE`** (in stock, held by no one) · W `POST /notes` · T `409 ASSET_NOT_IN_USE`; the note is not created. **This is the only create-time hard block.** Enforced server-side; the client also blocks it at item-add time using the `state` from §3.2 search.
- **F1b (soft warning — D6a)** — G a DEVOLUCIÓN note; an asset item whose current external state is **`UNAVAILABLE`** (broken / lost / scrapped) · W `POST /notes` · T `201` (allowed) with a `warnings[]` entry. The subsequent `return` sync clears the holder but leaves the state `UNAVAILABLE` — it never forces it back to `AVAILABLE`.
- **F2 (soft warning — D6)** — G an asset item whose **current external holder contradicts the action** (DEVOLUCIÓN/PRÉSTAMO: holder ≠ recipient; ENTREGA/PERMANENTE: already `IN_USE` by someone) · W `POST /notes` · T `201` (allowed) with a `warnings[]` entry. The client confirms at item-add time; the middleware does not block.
- **F3 (sync-time gate — D6b; the parallel contract's G5)** — G an APPROVED asset item whose current external holder still contradicts the action · W `sync` / `return` · T a **synchronous `409 HOLDER_MISMATCH`** (`details`: `expectedRecipient`, `actualHolder`), **nothing enqueued**. Re-calling with `{ "acknowledgeHolderMismatch": true }` enqueues the write normally. Never silently overwrites.
- **F4 (soft warning — D6c)** — G an ENTREGA note; the recipient **already holds an asset of the same type** · W `POST /notes` · T `201` (allowed) with a `warnings[]` entry. Computed client-side from `GET /assets?holder={username}` (§3.5) at recipient-selection time.
- **F5** — every asset item on `POST /notes` sends only `serialNumber`; the middleware resolves the external id, current holder, current state, and the printed inventory number (`af`) from the external system. The client never inputs or derives an inventory number.
- **F6** — `af` on any response is whatever the external system holds for that asset (may be `null`); it is never computed client-side.
- **F7 (generic — D5b)** — the "Genérico / Otro" Brand/Model option is offered for **every** Type. For an **asset** it acts as an optional search filter (narrows to generic-manufacturer assets); the item's brand/model always come from the picked record. `brandId` / `modelId` are optional on `GET /catalog/assets/search`.
- **F8 (generic — D5b; shape E1b)** — picking "Genérico / Otro" for a **countable** stores `brandId` = `config.generic.manufacturerId` and `modelId` = `config.generic.modelIds[itemtype]` (either may be `null`) and `brandName` / `modelName` = `config.generic.label`. Its availability / `sync` / reconciliation resolve against backend records under the generic manufacturer (Model-Y, §3.4) — no middleware counter.
- **F9 (bucket mapping — E1a)** — `GET /catalog/assets/search`'s `state` maps GLPI `states_id`: `33` → `AVAILABLE`; `31` / `34` / `36` / `40` → `IN_USE`; everything else, including `0` / `null`, → `UNAVAILABLE`. A record whose `states_id` bucket and `users_id` disagree still resolves deterministically (availability ← `states_id`, holder ← `users_id`) and raises a `GLPI_CONTRADICTION` drift row (§M / §14).


## G. Filtering & pagination  (→ contract §5.2)

- **G1** — `GET /notes` honors every `HistoryFilter` field (profileTypes, `approvalStatuses`, `syncStatuses`, `returnStatuses` — D7, one param per step kind — sedes, date range, author, recipient, item type / brand / model) with any-of within a field, and-across fields. A note matches a `syncStatuses` / `returnStatuses` value if **any** of its items has a step of that kind in one of the listed statuses.
- **G2** — results are paginated; the reported total reflects the full filtered set, not the returned page.
- **G3** — equipment filter **values** offered for the History filters come from values seen in historical notes, not the live catalog. *(Sede is the documented exception — sourced from the live Sede catalog; CLAUDE.md 2026-07-30.)*

## H. RBAC / Sede-scoping / deny-by-default  (→ contract §7.3, §7.5)

- **H1** — G a permission **not** granted to the caller's role · W the guarded action · T `403`. A newly added action with no grant row is denied to **everyone** until a row is added.
- **H2** — G a plain ADMIN whose Sede ≠ the target note's Sede · W `approve` / `reject` / `sync` / `return` / `lost` · T `403`; the note stays **readable**.
- **H3** — G a SUPERADMIN · W the same cross-Sede action · T allowed.
- **H4** — there is no SUPERADMIN-only permission (D3). SUPERADMIN = ADMIN without the Sede fence: a plain ADMIN's actions and list reads (`approve` / `sync` / `return`, `GET /sync-queue`, `GET /audit/*`) are always scoped to their own `sedeId`; a SUPERADMIN's are not. Concrete cases: H2, H3.
- **H5** — every check above is **server-side**: calling the API directly, bypassing the client, still enforces H1–H4.
- **H6 (Decision 6)** — `PUT /config` is gated by `role == SUPERADMIN` checked **directly**, not by a permission. There is no `EDIT_CONFIG` in the enum (still exactly `APPROVE_NOTES` / `SYNC_EXTERNAL` / `VALIDATE_RETURNS`). A plain `ADMIN` → `403`.
- **H7 (Decision 6)** — there is no `/me/preferences` or any other per-user settings endpoint. Client-side UI toggles (clear-form-on-submit, close-tab-on-submit) never reach the middleware.
- **H8 (F1)** — `PUT /me/glpi-token` sets **only the caller's own** token (write-only; never returned by any endpoint). No path exists to set another user's token.
- **H9 (F1)** — G a technician with no personal GLPI token · W `sync` / `return` / `lost` · T `409 GLPI_TOKEN_NOT_SET`, nothing enqueued. `reject-sync` and every **read** endpoint (catalog / search / availability / `GET /notes` / `GET /me`) still work — reads use the shared `App-Token`.

## I. Audit  (→ contract §6)

- **I1** — every state-changing call (note create / approve / reject, each item step action, stock move, config change, login) writes **exactly one** audit row: acting user (from the token), timestamp, before / after where applicable.
- **I2** — no audit row ever contains a secret value (SMTP password, external API key, IdP / service credentials) in its before / after fields.
- **I3** — a denied or failed call (`400` / `403` / `409`) leaves **no partial state** — it either wrote a clean audit row or changed nothing.

## K. Cross-cutting

- **K1** — every non-2xx response carries a stable machine-readable `error.code` (contract §11); clients branch on the code, never the message.
- **K2 (idempotency)** — every mutating endpoint is safe to retry:
  - `approve` / `reject` / `sync` / `reject-sync` / `return` / `lost` — guarded by the flow model's own state. A transition that already happened is not a valid transition again → `409` (or a no-op). No client-supplied key needed.
  - `POST /notes` (create) — accepts an optional `Idempotency-Key` header; a retry with the same key returns the original `201` result without creating a second note. The one place a state guard can't help — a dropped create response.
- **K3 (D8)** — `GET /api/v1/directory/users` (§7.6, replaces `IADService.search()`): `name`/`dni`/`username` AND-combined; at least one required else `400`; no matches → `200` with `results: []` (not `404`); directory unreachable → `5xx` (distinct from empty); any authenticated session, no special permission, not Sede-scoped.
- **K4 (pagination — §1.1)** — every middleware→backend read of a collection is **fully paginated**: the adapter loops until the backend reports no more pages; a partial page is never treated as the whole result. Applies to asset search, `/catalog/countables/availability`, an asset's history/`Log` (§14.4), the `Location` list, the reconciliation asset walk, directory search, `listSearchOptions`. A pure **count** (e.g. availability, a drift aggregate's `N`) uses the backend's reported `totalcount`, not a fetched-and-counted subset. A response that would be wrong because a page was missed is a K4 violation, not an edge case.

## L. External write queue  (→ contract §10)

- **L0 (Decision 7)** — every enqueued write is a row in `EXTERNAL_WRITE_QUEUE` (payload + status + `attempt_count` / `last_error` / `next_retry_at` / `lease_until`). A `@Scheduled` poller claims rows serialised **per `model_key`**, under a lease; a crashed job's lease expires and is reclaimed (restart-safe, no double-run). The worker reads + precondition-checks the live external record before writing — on drift it raises a §14 reconciliation row, never blind-writes.
- **L0b (Decision 7)** — `glpiStatus` is **not** flipped optimistically: it stays `PENDING` until the worker confirms the write; the app never renders `SYNCED` before `GET /notes/{id}` reports it. Note **approval** enqueues nothing (D4) — only a manual step action or a `repush` produces a queue row.
- **L1** — G an APPROVED note · W any item step action (`sync` / `return` / `lost`) · T `202`, the step's status becomes `QUEUED`, and no external write has happened yet in the response.
- **L2** — G a `QUEUED` step · W the same action again · T `409` (can't re-enqueue; §K2).
- **L3** — G a queued job; the external system returns `5xx` / is unreachable · T the job is retried automatically with backoff, the step stays `QUEUED`, no admin alert.
- **L4** — G a queued `sync` job for quantity N; external availability is now < N · T the step → `FAILED` with **nothing allocated** (all-or-nothing), plus an in-app queue entry, a nav-badge increment, and an email to the note's Sede admins.
- **L5** — G a `FAILED` step · W an admin (Sede-scoped) calls `retry` · T the job is re-enqueued, step → `QUEUED`. There is **no** timed auto-retry that fires on stock recovery.
- **L6** — G a `FAILED` step · W `abandon` with no `reason` · T `400`. W `abandon` with a reason · T step → `SYNC_ABANDONED` / `RETURN_ABANDONED` (terminal), one audit row; the note's `approvalStatus` and its sibling items are unaffected.
- **L7** — `REJECTED` is reachable only from `PENDING`: W `reject-sync` on a `QUEUED` or `FAILED` step · T `409`.
- **L8** — two concurrent `sync` jobs for the same model can never both allocate the same unit — the queue serialises per model, so the second job sees the first's effect.
- **L9** — on the desktop item card, `FAILED` and `<STEP>_ABANDONED` each render as a state visibly distinct from `SYNCED` and from `REJECTED`.
- **L10** — `GET /sync-queue?status=FAILED` returns only jobs the caller's role/Sede authorises (plain `ADMIN` → own Sede, `SUPERADMIN` → all).

## M. Reconciliation  (→ contract §14)

_Redesigned for v2.0 (Decision 8) — scope (b), one `GET /reconciliation/drift`
feed, new drift taxonomy. Full mechanics in `reconciliation-endpoint.md`._

- **M1** — G a serialized asset the app has a claim on (`externalItemId` on a note item) whose external holder ≠ the latest SYNCED note-item action's recipient · W `GET /reconciliation/drift` · T a `target.kind = ASSET_UNIT` row, `driftKind = HOLDER_MISMATCH`, `expected` (holder/stateBucket/sedeLocationId/`fromNoteId`) and `actual` populated.
- **M1b** — same for an **assigned non-serialized countable unit** (external id recorded on `NOTE_ITEM_RETURN_ALLOCATION`) — a returned unit still showing a holder in GLPI · T `HOLDER_MISMATCH`, same row shape. (There is no `USER_COUNT_MISMATCH` — assigned units are checked individually.)
- **M2** — G an external asset with a holder set and **no open note** explaining it · T `driftKind = ORPHAN_ASSIGNMENT`, `expected = null`, `assignmentDate` / `assignmentDateSource` / `preCutoff` populated. An asset the app has a matching open note for does **not** appear.
- **M3** — G a unit whose Devolución sync completed but the external `states_id` bucket is still IN_USE · T `driftKind = STATE_MISMATCH` (not a separate "stale return" kind).
- **M4** — G a (category, Sede) whose `expected AVAILABLE = N − K` ≠ `actual AVAILABLE` for unassigned non-serialized countables · T a `target.kind = CATEGORY_SEDE` row, `driftKind = COUNT_MISMATCH`, `expectedAvailable` / `actualAvailable` / `delta` / `limbo` breakdown.
- **M4b** — G a unit whose GLPI `states_id` says AVAILABLE but `users_id` is set (or vice versa) · T `driftKind = GLPI_CONTRADICTION`; availability is taken from `states_id`, holder from `users_id`, the row is flagged not auto-resolved.
- **M5** — W `POST /reconciliation/{id}/repush` on a `STATE_MISMATCH` / `HOLDER_MISMATCH` / `LOCATION_MISMATCH` row · T `expected` is re-asserted via the §10 queue (`QUEUED` → `SYNCED` / `FAILED`); needs `SYNC_EXTERNAL`. On `COUNT_MISMATCH` / `ORPHAN_ASSIGNMENT` / `GLPI_CONTRADICTION` · T `400`.
- **M6** — W `acknowledge` with no `reason` · T `400`. With a reason · T the row is marked acknowledged (`by` / `at` / `reason`), drops out of the default feed, is audited. If `expected` / `actual` (or an aggregate row's `delta` / `limbo`) later changes, the row re-surfaces **un-acknowledged**.
- **M7** — a scan that finds `expected == actual` for a previously-drifted target removes its row automatically. Scans `upsert` by `(driftKind, target)` — a re-run never duplicates an unresolved row.
- **M8** — `GET /reconciliation/drift`, `repush`, `acknowledge` are Sede-scoped: a plain `ADMIN` only sees / acts on their own Sede's rows; `SUPERADMIN` all. The nav-badge count excludes `preCutoff` rows.
- **M9** — every response carries `scannedAt`; `POST /reconciliation/run` triggers a fresh scan. There is **no** per-sync re-check (the §10 queue precondition-check covers same-day drift).
- **M10** — G legacy `ORPHAN_ASSIGNMENT` rows with `assignmentDate < reconciliation.orphanCutoff` · T `preCutoff = true`; `GET /reconciliation/drift` and the nav badge exclude them by default; `?includePreCutoff=true` returns them. `run` still stores them.
- **M11** — G an orphan whose holder-change history entry survives (`id_search_option` = holder option, `linked_action = 0`, `new_id` = current holder) · T `assignmentDateSource = LOG` and `assignmentDate` = that entry's date. G no such entry · T `assignmentDateSource = DATE_MOD` and `assignmentDate` = the asset's `date_mod`. With `reconciliation.suppressApproximateOrphans = false`, a `DATE_MOD` orphan is shown in the default feed regardless of `preCutoff`.

---

## Still-open items that block specific behaviors

- ~~**C3** — over-allocation race~~ RESOLVED 2026-08-31 (D2): all-or-nothing → queued job `FAILED` + admin alert, never partial. See §L4, contract §10.
- ~~**F1** — UNAVAILABLE also block DEVOLUCIÓN?~~ RESOLVED 2026-08-31 (D6a): no — only AVAILABLE blocks; UNAVAILABLE is a soft warning, state stays UNAVAILABLE on return.
- ~~**F3** — advisory vs. gated~~ RESOLVED 2026-08-31 (D6b): gated (`409 HOLDER_MISMATCH` + `acknowledgeHolderMismatch`).
- ~~**F4** — "assets held by user X" read~~ RESOLVED 2026-08-31 (D6c): `GET /assets?holder={username}` (contract §3.5).
- ~~**E7** — "does not modify stock" flag~~ RESOLVED 2026-08-31 (D4): flag removed; stock is derived (Model Y), contract §3.4.
- ~~**G1** — per-step filter param names~~ RESOLVED 2026-08-31 (D7): `syncStatuses` + `returnStatuses`, one per step kind, contract §5.2.
- ~~**K3** — directory/recipient user-search endpoint~~ RESOLVED 2026-08-31 (D8): `GET /api/v1/directory/users`, contract §7.6.
- ~~**§M / §14** — Reconciliation~~ RESOLVED 2026-08-31 (D9), **redesigned for v2.0 2026-09-07 (Decision 8)**: in the v2.0 first release; scope (b) = per-unit direct-claim check + per-(category, Sede) aggregate; one `GET /reconciliation/drift` feed; drift kinds `STATE_MISMATCH` / `HOLDER_MISMATCH` / `LOCATION_MISMATCH` / `COUNT_MISMATCH` / `ORPHAN_ASSIGNMENT` / `GLPI_CONTRADICTION`; nightly-only, no per-sync re-check; `repush` = the three MISMATCH kinds; pre-app `ORPHAN_ASSIGNMENT` handled by `reconciliation.orphanCutoff` + `preCutoff` + `?includePreCutoff`; `assignmentDate` log-first with `date_mod` fallback. See contract §14, §M, and `reconciliation-endpoint.md`.
