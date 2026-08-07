# Auto-update release folder — templates

This folder is **not read by the app at runtime**. It exists only as a copy-paste starting point
for the real release folder, which lives on a network share every technician machine can reach —
not in this repository. See the main [`README.md`](../../README.md#auto-updates) for the full
setup walkthrough and `CLAUDE.md`'s "Auto-update system" section for the design.

## Files

| File | Purpose |
|------|---------|
| `latest.json.example` | Template for the manifest every installed app checks — copy to `latest.json` on the network share and fill in the real version/installer/size/notes for each release. |
| `changelog.json.example` | Template for the full release-note history shown by "Historial de cambios" — copy to `changelog.json` on the network share, same folder as `latest.json`. Newest entry first. |

Both `.example` files use placeholder version numbers and notes — replace them with real values
before copying to the share. Same convention as `app-config.json.example` and the
`database/sqlserver/02-seed-equipment.sql.example` templates elsewhere in this repo: the `.example`
suffix is committed, the real filled-in file is not (it never lives in this repo at all here,
since it belongs on the network share, not on a developer's machine).

## Publishing a release

1. Bump `pom.xml`'s `<version>` (plain `X.Y.Z`, no `-SNAPSHOT`) and build/package the app.
2. Copy the resulting installer into the network share folder.
3. Update `latest.json` on the share with the new version, installer file name, exact file size
   in bytes, and release notes.
4. Prepend a new entry to `changelog.json` on the share with the same version.

Every already-installed app picks this up the next time someone clicks "Buscar actualizaciones" —
nothing needs to change on any individual technician's machine.

## Configuring an installation to find this folder

Each installation's own `config/app-config.json` needs `updates.manifestPath` pointing at the
`latest.json` **file** on the share (not the folder), with JSON's backslash-escaping applied:

```json
"updates": {
  "manifestPath": "\\\\servidor\\notas-it\\releases\\latest.json"
}
```
