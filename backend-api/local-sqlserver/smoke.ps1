# Smoke-checks the middleware running on the `sqlserver` profile against local-sqlserver's
# container. Run AFTER `docker compose up -d` and after the app has started on that profile.
#
#   .\smoke.ps1
#   $env:APP = 'http://host:port'; .\smoke.ps1

$ErrorActionPreference = 'Continue'
$App    = if ($env:APP)     { $env:APP }     else { 'http://localhost:8080' }
$SaPass = if ($env:SA_PASS) { $env:SA_PASS } else { 'Local_Dev_Pass123' }
$script:fail = 0

function Say($m) { Write-Host "`n=== $m ===" }
function Ok($m)  { Write-Host "  OK   $m" }
function Bad($m) { Write-Host "  FAIL $m" -ForegroundColor Red; $script:fail = 1 }

function Sql([string]$query) {
  docker compose exec -T mssql /opt/mssql-tools18/bin/sqlcmd `
    -C -S localhost -U sa -P $SaPass -d notesit -h -1 -W -b `
    -Q "SET NOCOUNT ON; $query" 2>$null | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' }
}

Say '1. App is up (GET /api/v1/health)'
try {
  $r = Invoke-WebRequest -UseBasicParsing "$App/api/v1/health"
  if ($r.StatusCode -eq 200) { Ok 'health 200' } else { Bad "health returned $($r.StatusCode)" }
} catch { Bad "health unreachable: $($_.Exception.Message)" }

Say '2. Flyway applied V1 + V2 (flyway_schema_history)'
$rows = Sql "SELECT CONCAT(version,'|',success) FROM dbo.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank;"
$rows | ForEach-Object { Write-Host "     $_" }
if ($rows -contains '1|1') { Ok 'V1 applied, success' } else { Bad 'V1 not applied / not successful' }
if ($rows -contains '2|1') { Ok 'V2 applied, success' } else { Bad 'V2 not applied / not successful' }

Say '3. Expected tables exist'
foreach ($t in 'TYPE','BRAND','MODEL','SEDE','APP_USER','ROLE','ROLE_PERMISSION','NOTE_REPORT',
                'NOTE_ITEM','NOTE_ITEM_STATUS_TRACKING','AUDIT_LOGIN','APP_CONFIG','APP_CONFIG_MOTIVO_OPTION') {
  $n = Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_name='$t';"
  if ("$n" -eq '1') { Ok $t } else { Bad "$t missing (count=$n)" }
}

Say '4. V2 seed data present'
$mv = [int](Sql "SELECT COUNT(*) FROM dbo.APP_CONFIG_MOTIVO_OPTION;")
if ($mv -ge 16) { Ok "APP_CONFIG_MOTIVO_OPTION seeded ($mv rows)" } else { Bad "motivo options not seeded (rows=$mv)" }
$rl = Sql "SELECT COUNT(*) FROM dbo.ROLE;"
if ("$rl" -eq '3') { Ok 'ROLE seeded (3)' } else { Bad "ROLE not seeded (rows=$rl)" }

Say '5. Optional - authenticated read (needs local-keycloak running + app pointed at it)'
if ($env:TOKEN) {
  try {
    $r = Invoke-WebRequest -UseBasicParsing "$App/api/v1/config" -Headers @{ Authorization = "Bearer $($env:TOKEN)" }
    if ($r.StatusCode -eq 200) { Ok "GET /config 200 ($($r.Content.Substring(0,[Math]::Min(80,$r.Content.Length)))...)" }
    else { Bad "GET /config returned $($r.StatusCode)" }
  } catch { Bad "GET /config: $($_.Exception.Message)" }
} else {
  Write-Host '     skipped - set $env:TOKEN=<middleware sessionToken> to run (see local-keycloak/README.md step 4)'
}

Say 'Result'
if ($script:fail -eq 0) { Write-Host '  ALL CHECKS PASSED'; exit 0 } else { Write-Host '  SOME CHECKS FAILED'; exit 1 }
