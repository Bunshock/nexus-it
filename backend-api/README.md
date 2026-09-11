### Backend API


##### Installation

- Requirements: Java 21, Maven

- Steps:
    - Clone this repository:
    - Move to `backend-api` folder, and build the project:
    `mvn clean install`
    - Run with:
    `mvn spring-boot:run`

##### Local development (dev profile)

`mvn spring-boot:run` boots the `dev` Spring profile by default (pinned in `pom.xml`) — an
embedded, in-memory H2 database, auto-seeded on every startup (`dev-schema.sql` +
`dev-seed.sql`). No Docker, no real Keycloak, no real SQL Server needed. Data does **not**
persist between restarts.

**Logging in without Keycloak** — `GET /api/v1/auth/config` returns `503 IDP_NOT_CONFIGURED`
in dev (no `middleware.idp.issuer-uri` set), which any client should treat as "use
`POST /api/v1/auth/dev-login` instead" (the desktop client does this automatically). Body:
`{"username": "<one of the seeded users below>"}` — no password, mints a real session.

Seeded accounts (`dev-seed.sql`), all assigned to Sede id 1:

| Username | Role |
|---|---|
| `dev.superadmin` | SUPERADMIN |
| `dev.admin` | ADMIN |
| `dev.user` | USER |

**Browsing the H2 database** — the H2 web console is enabled under the `dev` profile
(`spring.h2.console.enabled: true`) and its route is allow-listed in `SecurityConfig`
(dev-only — `/h2-console/**` doesn't exist in a non-`dev` build, same as `/auth/dev-login`).
While the app is running:

1. Open `http://localhost:8080/h2-console` in a browser.
2. Connect with:
   - **JDBC URL**: `jdbc:h2:mem:notesit;MODE=MSSQLServer;DATABASE_TO_LOWER=FALSE`
   - **User Name**: `SA`
   - **Password**: *(blank)*