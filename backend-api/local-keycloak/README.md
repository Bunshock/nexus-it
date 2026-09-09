# Local Keycloak — auth-flow validation harness

The middleware's login code (`GET /auth/config`, `POST /auth/login` JWKS validation, the
`APP_USER` registration gate, the allowed-group gate, `bypass_group_check`, and now the
`aud` claim check) has **never run against a real Keycloak**. This harness lets it — against
a throwaway local Keycloak container shaped like the org realm — without needing the org IdP.

It validates the exact same code path the production `sqlserver` profile will use; only
`middleware.idp.issuer-uri` / `middleware.idp.client-id` change between here and prod.

> **Prerequisite:** Docker (Desktop or Engine) with Compose v2. Nothing else — no local
> Keycloak install, no SQL Server. Everything runs on the `dev` (H2 in-memory) profile.

Nothing in this directory holds a real secret (`admin`/`admin` console creds, `test` user
passwords, `example.test` emails, `localhost` URLs). Safe to commit.

---

## What the container gives you

| | |
|---|---|
| Issuer | `http://localhost:8081/realms/siglo21` |
| Public client (PKCE `S256`) | `nexus-it` — stamps `groups` (bare names) + `aud: nexus-it` into the access token; direct-access-grants enabled so you can get tokens from `curl` with no browser |
| Second client | `other-app` — unrelated realm client, stamps `aud: other-app`; **only** used to prove the `aud` check rejects a valid same-realm token issued for a different client |
| Realm group | `nexus-it-users` |
| Admin console | `http://localhost:8081/` — `admin` / `admin` (not needed for the runbook) |

### Realm users ↔ `APP_USER` seed

| Keycloak user (pwd `test`) | in `nexus-it-users`? | in `APP_USER` (`dev-auth-seed.sql`)? | expected login outcome |
|---|---|---|---|
| `emanuel.arias` | yes | yes — `ADMIN` | **200** — session minted, `role: ADMIN`, `permissions[]` populated |
| `no.role` | yes | **no** | **403 `USER_NOT_REGISTERED`** |
| `outsider` | **no** | yes — `USER` | 200 while the group gate is off; **403 `NOT_IN_ALLOWED_GROUP`** once it's on (unless `bypass_group_check`) |

---

## 1. Start Keycloak

```powershell
cd backend-api\local-keycloak
docker compose up -d
docker compose ps            # wait until STATUS shows "healthy" (~30-60s on first pull)
```

The `siglo21` realm is imported automatically from `realm/siglo21-realm.json` on startup.
Sanity check:

```powershell
curl.exe http://localhost:8081/realms/siglo21/.well-known/openid-configuration
```

## 2. Run the middleware pointed at it

From `backend-api/` (so the `file:` paths below resolve), on the **`dev`** profile, with the
minimal `APP_USER`/`ROLE`/`ROLE_PERMISSION`/`AUDIT_LOGIN` schema loaded via `spring.sql.init`:

**PowerShell**

```powershell
cd ..
$env:SPRING_PROFILES_ACTIVE           = "dev"
$env:MIDDLEWARE_IDP_ISSUER_URI        = "http://localhost:8081/realms/siglo21"
$env:MIDDLEWARE_IDP_CLIENT_ID         = "nexus-it"
$env:SPRING_SQL_INIT_MODE             = "always"
$env:SPRING_SQL_INIT_SCHEMA_LOCATIONS = "file:./local-keycloak/dev-auth-schema.sql"
$env:SPRING_SQL_INIT_DATA_LOCATIONS   = "file:./local-keycloak/dev-auth-seed.sql"
.\mvnw.cmd spring-boot:run
```

**bash**

```bash
cd ..
SPRING_PROFILES_ACTIVE=dev \
MIDDLEWARE_IDP_ISSUER_URI=http://localhost:8081/realms/siglo21 \
MIDDLEWARE_IDP_CLIENT_ID=nexus-it \
SPRING_SQL_INIT_MODE=always \
SPRING_SQL_INIT_SCHEMA_LOCATIONS=file:./local-keycloak/dev-auth-schema.sql \
SPRING_SQL_INIT_DATA_LOCATIONS=file:./local-keycloak/dev-auth-seed.sql \
./mvnw spring-boot:run
```

App comes up on `http://localhost:8080`. (Flyway stays disabled on `dev`; only the
four harness tables above exist — enough for the login path, nothing else.)

## 3. `GET /auth/config` — serves real discovery values

```bash
curl -s http://localhost:8080/api/v1/auth/config
# {"issuer":"http://localhost:8081/realms/siglo21","clientId":"nexus-it",
#  "scopes":["openid","profile"],"authBackendDisplayName":"Keycloak"}
```

Was `503 IDP_NOT_CONFIGURED` before the env vars in step 2.

## 4. Happy path — `emanuel.arias`

Get a real Keycloak access token (direct access grant — stands in for the desktop app's
Auth-Code+PKCE round trip; the token the middleware receives is identical either way):

```bash
TOKEN=$(curl -s \
  -d grant_type=password -d client_id=nexus-it \
  -d username=emanuel.arias -d password=test \
  http://localhost:8081/realms/siglo21/protocol/openid-connect/token | jq -r .access_token)
```

<details><summary>PowerShell equivalent</summary>

```powershell
$TOKEN = (Invoke-RestMethod -Method Post `
  -Uri http://localhost:8081/realms/siglo21/protocol/openid-connect/token `
  -Body @{ grant_type='password'; client_id='nexus-it'; username='emanuel.arias'; password='test' }).access_token
```
</details>

Exchange it for a middleware session:

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login -H "Authorization: Bearer $TOKEN"
```

Expect **200**:

```json
{
  "sessionToken": "…",
  "expiresAt": "…",
  "role": "ADMIN",
  "sedeId": null,
  "displayName": "emanuel.arias",
  "permissions": ["APPROVE_NOTES","MANAGE_MODELS","MANAGE_STOCK","SYNC_GLPI","VALIDATE_RETURNS"]
}
```

- `displayName` is the **username**, not "Emanuel Arias" — the directory (`middleware.directory.*`)
  is unset on `dev`, so `AuthController` catches the `503` and degrades (by design). To also
  exercise the login-time directory snapshot, additionally set `MIDDLEWARE_DIRECTORY_BASEURL` /
  `MIDDLEWARE_DIRECTORY_TOKEN` at a reachable AD API — out of scope for this harness.
- Then hit an authenticated endpoint with the session token to confirm the filter chain:
  `curl -s http://localhost:8080/api/v1/me -H "Authorization: Bearer <sessionToken>"`.

## 5. `403 USER_NOT_REGISTERED` — `no.role`

Same as step 4 with `username=no.role`. The token is perfectly valid (signature, `iss`,
`exp`, `aud: nexus-it` all pass) — it's rejected purely because there's no `APP_USER` row:

```
HTTP/1.1 403
{"error":{"code":"USER_NOT_REGISTERED","message":"Usuario no registrado en la aplicación. …"}}
```

## 6. `aud` check — reject a valid token for another client

Get a token from `other-app` (valid `siglo21` token, same signing key, `aud: other-app`):

```bash
BADAUD=$(curl -s \
  -d grant_type=password -d client_id=other-app \
  -d username=emanuel.arias -d password=test \
  http://localhost:8081/realms/siglo21/protocol/openid-connect/token | jq -r .access_token)

curl -s -X POST http://localhost:8080/api/v1/auth/login -H "Authorization: Bearer $BADAUD"
```

Expect **401** — the signature and issuer are fine, but `aud` doesn't contain `nexus-it`:

```
{"error":{"code":"INVALID_IDP_TOKEN","message":"El token de identidad no es válido."}}
```

Before the `aud` change (`IdpTokenValidator`, this session) this returned **200** — any
`siglo21` token for a registered user was accepted regardless of which client minted it.

> Sanity-decode the two tokens if you want to see it: paste each `access_token` into
> `jwt.io` (or `jq -R 'split(".")[1] | @base64d | fromjson' <<<"$TOKEN"`) and compare the
> `aud` claim — `nexus-it` vs `other-app`.

## 7. Group gate + `bypass_group_check`

Stop the app (`Ctrl+C`), restart step 2 with the allowed group set:

```powershell
$env:MIDDLEWARE_IDP_ALLOWED_GROUP_NAME = "nexus-it-users"   # (bash: prepend MIDDLEWARE_IDP_ALLOWED_GROUP_NAME=nexus-it-users)
.\mvnw.cmd spring-boot:run
```

- `emanuel.arias` (in `nexus-it-users`) → still **200**.
- `outsider` (not in the group) → **403 `NOT_IN_ALLOWED_GROUP`**.
- Now flip the bypass flag and retry `outsider` — **200**:
  ```sql
  -- H2 console at http://localhost:8080/h2-console  (JDBC URL: jdbc:h2:mem:notesit , user: sa , no password)
  UPDATE APP_USER SET bypass_group_check = 1 WHERE username = 'outsider';
  ```
  (Or edit `dev-auth-seed.sql` to seed `outsider` with `bypass_group_check = 1` and restart.)
- **Fail-closed check:** with `MIDDLEWARE_IDP_ALLOWED_GROUP_NAME` set, temporarily remove the
  `groups` mapper from the `nexus-it` client in the admin console → every login (even
  `emanuel.arias`, no bypass) → **403**. Restore the mapper afterwards. This confirms the
  gate denies when the claim is *absent*, not just when the group is *missing from the list*.

## 8. Tear down

```bash
docker compose down -v      # -v also wipes the imported realm; next `up` re-imports clean
```

---

## What this does NOT cover (still owed before prod)

- **`SessionStore` eviction** (`keycloak-integration.md` §4b) — expired sessions only drop on
  next access; add a `@Scheduled` sweep / size cap before running a 2nd replica.
- **HTTPS enforcement** for `middleware.baseUrl` (§4c).
- **Real SQL Server** — this harness runs entirely on H2 with a hand-cut 4-table schema.
  The `sqlserver` profile + real `V1__init.sql`/`V2__config.sql` are validated separately
  (v1 `NEXT-SESSION.md` gap #6).
- **The org realm's own policies** — token lifespans, MFA, LDAP federation, real group
  structure. This realm is a functional stand-in for the *protocol shape*, not those.
- **Kerberos/SPNEGO silent login** — out of scope, later (AD-admin work); the flow falls
  back to the Keycloak login page until then, no middleware change when it lands.

## Pointing at the org Keycloak instead

Everything above is identical; only these change (real values in the local, uncommitted
`../keycloak-integration.md` on the `backend-api-v2-glpi-adapter` worktree):

```
MIDDLEWARE_IDP_ISSUER_URI = https://auth.21.ar/realms/siglo21
MIDDLEWARE_IDP_CLIENT_ID  = nexus-it
```

Plus, for a real run: `SPRING_PROFILES_ACTIVE=sqlserver`, the `DB_*` vars,
`MIDDLEWARE_SECURITY_ENCRYPTION_KEY`, and a provisioned `APP_USER` table (migrated user
list + role→permission seed) — without that last one every login is `403 USER_NOT_REGISTERED`.
