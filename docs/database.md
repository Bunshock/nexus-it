## Database Schema (ERD)

The local SQLite database lives at `data/noteapp.db` (created at first run). The schema mirrors the SQL Server structure `RemoteDatabaseService.ensureSchema()` creates on a remote database — same table names and column types, differing only where each dialect requires it. See `desktop-app/database/sqlserver/` for ready-to-run SQL Server schema and starting-data scripts, and that folder's `README.md` for a full remote-server setup walkthrough.

#### Equipment catalog tables

```mermaid

erDiagram
    direction TB

    TYPE ||--o{ BRAND_TYPE_LINK : ""
    BRAND ||--o{ BRAND_TYPE_LINK : ""
    BRAND_TYPE_LINK |o--o{ MODEL : "optional — NULL for the single global generic model"
    
    MODEL ||--o| SN_VALIDATION : ""
    
    TYPE {
        int id PK
        string name "UNIQUE regardless of deprecated status — see Notes below"
        int is_asset "1 = asset (S/N + A/F); 0 = countable (quantity)"
        int requires_serial "1 = S/N mandatory, 'Sin S/N' checkbox disabled in ItemDialogView"
        int deprecated "0 = active/selectable; 1 = renamed-away or removed, kept for historical NOTE_ITEM FKs"
    }

    BRAND {
        int id PK
        string name "UNIQUE regardless of deprecated status; 'Genérico / Otro' is protected from deletion"
        int deprecated "0 = active/selectable; 1 = renamed-away or removed"
    }

    BRAND_TYPE_LINK {
        int id PK
        int type_id FK
        int brand_id FK
        "UNIQUE(type_id, brand_id)"
    }

    MODEL {
        int id PK
        int brand_type_id FK "nullable — NULL marks the single global 'Genérico / Otro' model, not scoped to any link"
        string name "UNIQUE(brand_type_id, name) regardless of deprecated status — same name OK under a different brand/type; UNIQUE(name) WHERE brand_type_id IS NULL for the global row"
        int deprecated "0 = active/selectable; 1 = renamed-away or removed"
    }

    SN_VALIDATION {
        int id PK
        int model_id FK
        string regex_pattern "optional; expected length and the 'Patrón esperado' hint shown in ItemDialogView are always derived from this at display time, never stored separately"
        int is_active "1 = enforce; 0 = skip"
    }
```

#### Provider table

Independent of the equipment catalog above — no FK relationship, just a flat, admin-managed list backing Nota de Proveedor's "PROVEEDOR" dropdown (`IEquipmentService.getAllProviders()`/`addProvider()`/`renameProvider()`/`removeProvider()`). Added 2026-07-10 to fix the dropdown, which was previously unpopulated and non-functional.

```mermaid

erDiagram
    PROVIDER {
        int id PK
        string name "UNIQUE regardless of deprecated status"
        int deprecated "0 = active/selectable; 1 = renamed-away or removed"
    }

    SEDE {
        int id PK
        string name "UNIQUE regardless of deprecated status"
        int deprecated "0 = active/selectable; 1 = renamed-away or removed"
    }
```

---

#### User role table

Added 2026-07-24 alongside the login screen. Flat username→role mapping, unrelated to AD group
membership (which gates app access at login, checked live against the AD API, never stored
locally) — this table only distinguishes `ADMIN` vs `USER` among accounts that already passed
that gate. `IUserRoleService.getRole()` defaults to `"USER"` when a username has no row. No
in-app UI writes to this table — an admin promotes/demotes an account with a plain SQL
`INSERT`/`UPDATE`/`DELETE` against `USER_ROLE` directly.

```mermaid

erDiagram
    USER_ROLE {
        string username PK "AD account name, not a catalog id"
        string role "ADMIN or USER"
    }
```

---

#### Note history tables

```mermaid

erDiagram
    direction LR

    NOTE_REPORT ||--|{ NOTE_ITEM : ""

    NOTE_REPORT ||--o| NOTE_ENTREGA_DEVOLUCION : ""
    NOTE_REPORT ||--o| NOTE_PROVEEDOR : ""

    NOTE_ITEM ||--o| NOTE_ITEM_ASSET : ""
    NOTE_ITEM ||--o| NOTE_ITEM_COUNTABLE : ""
    NOTE_ITEM ||--o| NOTE_ITEM_GLPI_TRACKING : ""
    NOTE_ITEM ||--o| NOTE_ITEM_RETURN_TRACKING : ""

    TYPE ||--o{ NOTE_ITEM : ""
    BRAND ||--o{ NOTE_ITEM : ""
    MODEL ||--o{ NOTE_ITEM : ""
    PROVIDER ||--o{ NOTE_PROVEEDOR : ""
    SEDE |o--o{ NOTE_REPORT : "optional — NULL only for notes predating this feature"
    
    NOTE_REPORT {
        int id PK
        string created_at "ISO-8601 timestamp"
        string profile_type "ENTREGA / DEVOLUCION / FIN_DE_CONTRATO / PROVEEDOR"
        int glpi_synced "0 = not synced"
        string technician_name "plain text, snapshot at generation time"
        string technician_dni "plain text, snapshot at generation time"
        string sede "DEPRECATED 2026-07-24 — old free-text snapshot, left in place unused, never written/read anymore"
        int sede_id FK "nullable — resolves to SEDE.name at read time via JOIN, same pattern as provider_id"
    }

    NOTE_ENTREGA_DEVOLUCION {
        int note_report_id PK_FK
        string user_name
        string user_dni
        string user_email
        string motivo "mandatory for ENTREGA; tentative return date (dd/MM/yyyy) for PRESTAMO"
        string area_evento "optional, PRESTAMO-only context field (e.g. department/event) — null otherwise"
    }

    NOTE_PROVEEDOR {
        int note_report_id PK_FK
        int provider_id FK "references PROVIDER(id), possibly a deprecated/renamed-away row — see Notes below"
        string cuit
        string motivo "mandatory"
    }

    NOTE_ITEM {
        int id PK
        int note_id FK
        int type_id FK "references TYPE(id), possibly a deprecated/renamed-away row — see Notes below"
        int brand_id FK "references BRAND(id), possibly a deprecated/renamed-away row"
        int model_id FK "references MODEL(id), possibly a deprecated/renamed-away row"
        string observations
    }

    NOTE_ITEM_ASSET {
        int item_id PK_FK "row exists only for asset items"
        string serial_number
        string a_f
    }

    NOTE_ITEM_COUNTABLE {
        int item_id PK_FK "row exists only for countable items"
        int quantity "default 1"
    }

    NOTE_ITEM_GLPI_TRACKING {
        int item_id PK_FK "row exists only when GLPI-tracked (asset AND not Prestamo) — absence IS 'N_A' now"
        string status "PENDING | SYNCED | REJECTED"
        string rejection_reason "null unless REJECTED"
        string status_updated_at "ISO-8601 timestamp; null until first sync attempt"
    }

    NOTE_ITEM_RETURN_TRACKING {
        int item_id PK_FK "row exists only when the note is a Prestamo — absence IS 'N_A' now"
        string status "PENDING | RETURNED | LOST"
        string rejection_reason "null unless LOST"
        string status_updated_at "ISO-8601 timestamp; null until first return action"
    }
```

**`NOTE_ITEM` normalized into 5 tables (2026-07-22)** — the previous single wide table (`is_asset`, `serial_number`/`a_f` vs `quantity`, the `glpi_*`/`return_*` tracking triples) always had one whole tracking dimension and one of asset-vs-countable sitting at a dead `NULL`/`'N_A'` value depending on the item's type and the note's profile. Split above so a subtype row only ever exists when that dimension actually applies. `NoteReportItem.java`, the `GlpiStatus`/`ReturnStatus` enums, and every controller/template are unaffected — only `SqliteHistoryService`'s SQL changed (see `CLAUDE.md`'s "SQLite tables" section for the full migration mechanics, on both SQLite and SQL Server).

**Catalog-FK redesign (2026-07-22, same day)** — `NOTE_ITEM.type_name`/`brand_name`/`model_name` and `NOTE_PROVEEDOR.provider_name` (plain text snapshots) were replaced with real `type_id`/`brand_id`/`model_id`/`provider_id` foreign keys into the catalog. `TYPE`, `BRAND`, `MODEL`, and `PROVIDER` each gained a `deprecated` column: renaming or removing a row never mutates or deletes it — it's marked `deprecated = 1` and a new (or reactivated, if a matching deprecated row already exists) `deprecated = 0` row takes over the name, so a historical note's FK still resolves to a row carrying its exact original name. Uniqueness on `name` (and, for `MODEL`, `(brand_type_id, name)`) is enforced regardless of `deprecated` status — a given name lives on exactly one row at a time. Renaming a `TYPE`/`BRAND` cascades: every `BRAND_TYPE_LINK` that used the old id gets an equivalent link under the new id, with every active `MODEL` under it cloned forward, so the active catalog never silently loses children. See `CLAUDE.md`'s catalog-FK redesign section for the full backfill/migration mechanics (both SQLite and SQL Server) and `SqliteEquipmentService.java` for the rename/cascade logic.

---

#### Settings table

| Key | Value | Description |
|-----|-------|-------------|
| `smtp_password` | Base64-encoded AES-256/GCM ciphertext | SMTP password, encrypted via `AppKeyEncryptionService` |
| `glpi_api_key` | Base64-encoded AES-256/GCM ciphertext | GLPI REST API key, encrypted via `AppKeyEncryptionService` |
| `ad_api_token` | Base64-encoded AES-256/GCM ciphertext | AD API bearer token, encrypted via `AppKeyEncryptionService` |
| `db_username` | Base64-encoded AES-256/GCM ciphertext | Remote DB username, encrypted via `AppKeyEncryptionService` |
| `db_password` | Base64-encoded AES-256/GCM ciphertext | Remote DB password, encrypted via `AppKeyEncryptionService` |
| `display_name_pref:<username>` | Plaintext | Technician's personal greeting-name preference for the sidebar welcome message, one row per technician username. Set via `TechnicianSessionService.setDisplayNamePreference()`, `ProfileController`'s "NOMBRE PARA MOSTRAR" field. Not a secret — stored unencrypted, unlike the rows above. |
| `sede_pref:<username>` | Plaintext | Technician's Sede (site) value, one row per technician username. Set via `TechnicianSessionService.setSedePreference()`, `SettingsController`'s "SEDE" field (not admin-gated — any technician sets their own). Snapshotted onto `NOTE_REPORT.sede` at note-generation time and printed on every note; mandatory to generate a note, same as the technician's AD profile. Not a secret. |

`smtp_password`, `glpi_api_key`, `db_password`, and `ad_api_token` can ship pre-configured: `app-config.json`'s `defaults` object holds pre-encrypted values that `ServiceLocator.provisionDefaultSecrets()` copies into this table on first startup, only if that key isn't already set (see README.md's "Pre-configuring default secrets").

```mermaid

erDiagram
    APP_SETTINGS {
        string key PK "e.g. smtp_password, db_host, display_name_pref:jperez"
        string value "plaintext or AES-256/GCM ciphertext, depending on key — see table above"
    }
```

Schema-less key-value table — see the table above for the actual keys in use; this diagram shows only its two columns, not its rows.

---

#### Notes

- `'Genérico / Otro'` brand row (renamed from `'Generic'` 2026-07-22) is inserted on first run and is protected from deletion in `SqliteEquipmentService`. `MODEL` has its own single global `'Genérico / Otro'` row too (`brand_type_id IS NULL`, added 2026-07-23) — offered for every brand+type combination regardless of whether a real `BRAND_TYPE_LINK` exists, via a `UNION` in `getModelsForBrandAndType()`. This replaced an earlier per-`BRAND_TYPE_LINK` duplicate (one "Genérico / Otro" `MODEL` row per link, forced by `brand_type_id` being `NOT NULL`) — a real redundancy, since the label never actually varied by scope; renaming it meant updating N rows to stay in sync. `removeModel()` refuses to delete this row (it can only be renamed); `idx_model_global_generic_name` (`UNIQUE(name) WHERE brand_type_id IS NULL`) and `idx_model_single_active_generic` (`UNIQUE(deprecated) WHERE brand_type_id IS NULL AND deprecated = 0`) back it at the DB level — a plain `UNIQUE(brand_type_id, name)` can't, since every SQL engine treats NULL as never-equal-to-NULL even inside a unique index. The seed label is configurable via `app-config.json`'s `catalog.genericLabel` (one-shot: only used the first time the row is created — renaming it afterward is a normal admin catalog rename, same as any Brand/Model, so historical notes keep showing whatever label was active when they were generated).
- `NOTE_ENTREGA_DEVOLUCION` and `NOTE_PROVEEDOR` are optional one-to-one extensions of `NOTE_REPORT`, populated based on `profile_type`.
- `NOTE_REPORT.technician_name`/`technician_dni` store the generating technician's identity as a plain-text snapshot at generation time — the technician's identity is session-only, sourced from Windows/AD, and never persisted elsewhere (see `TechnicianSessionService` in `docs/architecture.md`). The `TECHNICIAN_PROFILE` table and `NOTE_REPORT.technician_id` FK that predated this snapshot approach were removed entirely (2026-07-16); any note created before that change that only had `technician_id` set now shows a blank author in History.
- All encrypted values use `AppKeyEncryptionService` (AES-256/GCM, replaced Windows DPAPI on 2026-07-08) — a single fixed key shared across every installation, chosen specifically so these organization-wide shared credentials can be pre-configured across many machines without per-account/per-machine setup. See `CLAUDE.md`'s Known issues/gotchas for the accepted security trade-off.
- `NOTE_ITEM.glpi_status` tracks GLPI sync state per asset item. Only rows where `is_asset = 1` are eligible for GLPI sync; countable items always remain `N_A`. `NoteReport` aggregates these counts into `getPendingItemCount()`, `getSyncedItemCount()`, `getRejectedItemCount()` for display in the history table.
- `GlpiStatus` enum values: `N_A` (no GLPI tracking), `PENDING` (queued for sync), `SYNCED` (successfully pushed to GLPI), `REJECTED` (sync attempted but rejected, reason stored in `glpi_rejection_reason`).
- `NOTE_ITEM.return_status` (added 2026-07-17, Préstamos section) is a separate dimension from `glpi_status` — it tracks whether a *loaned* item has come back, for both asset and countable items (unlike GLPI, which is asset-only). Only set to `PENDING` at insert time for `NOTE_REPORT.profile_type = 'PRÉSTAMO'` notes; every other note type's items stay `N_A`. `ReturnStatus` enum values: `N_A`, `PENDING` (awaiting return), `RETURNED` (confirmed back), `LOST` (reason stored in `return_rejection_reason`). `NoteReport` aggregates these into `getReturnPendingItemCount()`/`getReturnedItemCount()`/`getLostItemCount()` for the Préstamos Historial table (`PrestamoHistoryController`) — see `CLAUDE.md`'s "Préstamos section" for the full flow.
