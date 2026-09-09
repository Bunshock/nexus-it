#!/usr/bin/env bash
# Smoke-checks the middleware running on the `sqlserver` profile against local-sqlserver's
# container. Run AFTER `docker compose up -d` and after the app has started on that profile.
#
#   ./smoke.sh            # assumes app at http://localhost:8080
#   APP=http://host:port ./smoke.sh
set -u

APP="${APP:-http://localhost:8080}"
SA_PASS="${SA_PASS:-Local_Dev_Pass123}"
SQLCMD='/opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P '"$SA_PASS"' -h -1 -W -b'
fail=0

say()  { printf '\n=== %s ===\n' "$1"; }
ok()   { printf '  OK   %s\n' "$1"; }
bad()  { printf '  FAIL %s\n' "$1"; fail=1; }

say "1. App is up (GET /api/v1/health)"
code=$(curl -s -o /dev/null -w '%{http_code}' "$APP/api/v1/health")
[ "$code" = "200" ] && ok "health 200" || bad "health returned $code"

say "2. Flyway applied V1 + V2 (flyway_schema_history)"
rows=$(docker compose exec -T mssql $SQLCMD -d notesit \
  -Q "SET NOCOUNT ON; SELECT CONCAT(version,'|',success) FROM dbo.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank;" 2>/dev/null | tr -d '\r')
echo "$rows" | sed 's/^/     /'
echo "$rows" | grep -q '^1|1$' && ok "V1 applied, success" || bad "V1 not applied / not successful"
echo "$rows" | grep -q '^2|1$' && ok "V2 applied, success" || bad "V2 not applied / not successful"

say "3. Expected tables exist"
for t in TYPE BRAND MODEL SEDE APP_USER ROLE ROLE_PERMISSION NOTE_REPORT NOTE_ITEM \
         NOTE_ITEM_STATUS_TRACKING AUDIT_LOGIN APP_CONFIG APP_CONFIG_MOTIVO_OPTION; do
  n=$(docker compose exec -T mssql $SQLCMD -d notesit \
    -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM information_schema.tables WHERE table_name='$t';" 2>/dev/null | tr -d '[:space:]')
  [ "$n" = "1" ] && ok "$t" || bad "$t missing (count=$n)"
done

say "4. V2 seed data present"
mv=$(docker compose exec -T mssql $SQLCMD -d notesit \
  -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM dbo.APP_CONFIG_MOTIVO_OPTION;" 2>/dev/null | tr -d '[:space:]')
[ "${mv:-0}" -ge 16 ] 2>/dev/null && ok "APP_CONFIG_MOTIVO_OPTION seeded ($mv rows)" || bad "motivo options not seeded (rows=$mv)"
rl=$(docker compose exec -T mssql $SQLCMD -d notesit \
  -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM dbo.ROLE;" 2>/dev/null | tr -d '[:space:]')
[ "${rl:-0}" = "3" ] && ok "ROLE seeded (3)" || bad "ROLE not seeded (rows=$rl)"

say "5. Optional — authenticated read (needs local-keycloak running + app pointed at it)"
if [ -n "${TOKEN:-}" ]; then
  code=$(curl -s -o /tmp/smoke-cfg.json -w '%{http_code}' "$APP/api/v1/config" -H "Authorization: Bearer $TOKEN")
  [ "$code" = "200" ] && ok "GET /config 200 ($(tr -d '\n' </tmp/smoke-cfg.json | cut -c1-80)...)" || bad "GET /config returned $code"
else
  echo "     skipped — set TOKEN=<middleware sessionToken> to run (see local-keycloak/README.md step 4)"
fi

say "Result"
[ "$fail" = "0" ] && { echo "  ALL CHECKS PASSED"; exit 0; } || { echo "  SOME CHECKS FAILED"; exit 1; }
