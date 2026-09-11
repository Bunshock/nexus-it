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

`mvn spring-boot:run` boots the `dev` Spring profile — an embedded, in-memory H2 database, no
Docker/Keycloak/SQL Server needed. **Unlike `backend-api-v1-no-adapter`, this branch's dev
profile has no seed data or dev-login route yet** — it exists only to exercise the auth/security
wiring against an empty schema (Flyway doesn't run in dev; the real T-SQL migrations are
SQL-Server-only). `GET /api/v1/auth/config` will report `503 IDP_NOT_CONFIGURED` with nothing to
fall back to until that groundwork is ported over.

**Browsing the H2 database** — the H2 web console is enabled under the `dev` profile and
allow-listed in `SecurityConfig` (dev-only, same precedent as Swagger). While the app is running:

1. Open `http://localhost:8080/h2-console` in a browser.
2. Connect with:
   - **JDBC URL**: `jdbc:h2:mem:notesit;MODE=MSSQLServer;DATABASE_TO_LOWER=FALSE`
   - **User Name**: `SA`
   - **Password**: *(blank)*