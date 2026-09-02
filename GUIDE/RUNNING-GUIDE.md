# Running Guide - Entra ID OAuth2 Full-Stack Reference

This guide walks you through running the whole stack on your local machine as a
developer, and - just as importantly - tells you **what to expect to see** at
each step so you can validate that things are working (or spot exactly where they
are not).

There are three moving parts:

1. **PostgreSQL** - the database (native install, port 5432).
2. **entra-backend** - the Spring Boot resource server (JDK 21, port 8080).
3. **EntraUi** - the Angular 19 SPA (Node, port 4200).

Read the sections in order the first time. Once set up, the day-to-day loop is
just "start Postgres -> run backend -> run frontend".

> Related documentation: the repository `README.md` is the overview and entry
> point; the `DOC/` folder holds reference docs (ARCHITECTURE, API, SECURITY,
> DATABASE, CONFIGURATION); `LEARNING-GUIDE.md` is a file-by-file reading path;
> `RUNNING-MAC-GUIDE.md` covers macOS setup.

> A note on scope: the backend and the automated tests run fully locally with no
> cloud dependency. **Interactive browser login requires a real Microsoft Entra
> ID app registration** matching the constants in this project. See
> [Section 6](#6-what-actually-needs-entra-id-and-what-does-not) for exactly what
> works without it and what does not. You can validate a great deal of the system
> without ever logging in.

---

## 0. Prerequisites

Check each of these before starting. Commands to verify are in code blocks; the
expected shape of the output follows each.

### JDK 21

```bash
java -version
```

Expect a line reporting version **21** (e.g. `openjdk version "21..."` or
`Corretto-21...`). The backend targets Java 21; an older JDK will fail to build.

> On this machine the backend was built with `JAVA_HOME` pointed at
> `C:\Program Files\java\jdk-corretto-21`. If your default `java` is not 21, set
> `JAVA_HOME` to a 21 JDK for the terminal you run Maven in.

### Maven

The backend uses Maven. Verify:

```bash
mvn -version
```

Expect Maven 3.9+ and - critically - the "Java version" line it prints should be
**21**. If Maven reports a different Java version, fix `JAVA_HOME` first.

### Node.js + npm

```bash
node --version
npm --version
```

Expect Node 18+ (the project was exercised on Node 20/22-class runtimes) and a
matching npm. Angular 19 needs a modern Node.

### PostgreSQL

You need a running PostgreSQL server reachable at `localhost:5432` with the
`postgres` / `postgres` credentials, and a database named `my_workspace`.

```bash
psql -h localhost -p 5432 -U postgres -c "SELECT version();"
```

Expect a `PostgreSQL 1x.x ...` banner. If it prompts for a password, use
`postgres`.

### A browser with DevTools

Chrome/Edge is ideal (the automated frontend tests use ChromeHeadless, and the
verification steps use the Network tab).

---

## 1. One-time database setup

Create the database the backend expects (skip if `my_workspace` already exists):

```bash
psql -h localhost -p 5432 -U postgres -c "CREATE DATABASE my_workspace;"
```

Expect: `CREATE DATABASE`.

You do **not** need to create any tables - Flyway does that automatically when
the backend starts (see the next section). To confirm the database exists:

```bash
psql -h localhost -p 5432 -U postgres -lqt | cut -d '|' -f1 | grep -qw my_workspace && echo "my_workspace present"
```

Expect: `my_workspace present`.

---

## 2. Run the backend (entra-backend)

From the repository root:

```bash
cd entra-backend
mvn spring-boot:run
```

(If your default JDK is not 21, prefix with the right home, e.g. on Git Bash:
`JAVA_HOME="/c/Program Files/java/jdk-corretto-21" mvn spring-boot:run`.)

### What to expect during startup

Watch the log for these milestones, roughly in order:

1. **Spring Boot banner** and `Starting EntraOauthApplication using Java 21`.
2. **HikariCP** initializing the datasource:
   `HikariPool-1 - Starting...` then `... Start completed.`
   - If Postgres is **not** reachable, startup fails within ~10 seconds with a
     connection error (the deliberate 10s connection budget). That fast failure
     is expected behavior, not a hang.
3. **Flyway** running migrations. On a fresh database you will see something like:
   ```
   Flyway Community Edition ... by Redgate
   Database: jdbc:postgresql://localhost:5432/my_workspace (PostgreSQL 1x.x)
   Successfully validated 1 migration ...
   Creating Schema History table "public"."flyway_schema_history" ...
   Migrating schema "public" to version "1 - initial schema"
   Successfully applied 1 migration ...
   ```
   On subsequent starts it instead reports the schema is **up to date** and
   applies nothing (migrations are idempotent).
4. **Hibernate** initializing and **validating** the schema (it does not create
   tables - `ddl-auto: validate`). No "altering table" lines should appear; if
   the entity and schema disagreed, startup would fail here with a validation
   error.
5. **OAuth2 resource server**: Spring performs OIDC discovery against the Entra
   authority to load the JWKS keys used to verify token signatures.
   - With internet access this succeeds silently.
   - If the authority is unreachable, startup fails with a JWKS/issuer error -
     also expected, because the server refuses to run without the keys it needs
     to validate tokens.
6. **Tomcat started on port(s): 8080** and
   `Started EntraOauthApplication in N seconds`.

### Validate the backend is up

The backend is stateless and every `/entra-backend/**` route requires a valid token, so
the quickest health check is to confirm it **rejects** an unauthenticated call
with **401** (this proves the security filter chain is active):

```bash
curl -i http://localhost:8080/entra-backend/items
```

Expect:
- Status line `HTTP/1.1 401`
- A `WWW-Authenticate: Bearer` header on the response.

That 401 is the **success signal** here - it means the server is up and
correctly refusing anonymous access. A connection-refused error instead means
the backend is not running.

### Confirm Flyway created the tables

```bash
psql -h localhost -p 5432 -U postgres -d my_workspace -c "\dt"
```

Expect to see four tables: `item`, `app_user`, `access_audit`, and
`flyway_schema_history`.

```bash
psql -h localhost -p 5432 -U postgres -d my_workspace -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Expect one row: version `1`, description `initial schema`, success `t`.

---

## 3. Run the frontend (EntraUi)

In a **second terminal**, from the repository root:

```bash
cd EntraUi
npm install      # first time only
npm start
```

`npm start` runs `ng serve`.

### What to expect during startup

- Angular builds the app and prints:
  ```
  Application bundle generation complete. ...
  Local:   http://localhost:4200/
  ```
- You may see a **bundle-size budget warning** (initial bundle ~589 kB vs a
  500 kB budget). This is a **warning, not an error** - the app still serves. It
  is expected and harmless for this reference.

### Validate the frontend is serving

Open **http://localhost:4200** in your browser.

**Honest heads-up about the initial screen:** the root `AppComponent` still uses
the default Angular CLI starter template (the "Hello, EntraUi / Congratulations!
Your app is running" splash) with a `<router-outlet />` appended at the bottom.
So you will see that **starter splash**, and the routed view (dashboard/login)
renders **beneath** it rather than replacing it. That is a cosmetic artifact of
the scaffold, not a bug. The routing, guards, and API calls all still work
through the outlet.

Navigation to validate:

- Visiting `/` redirects to `/dashboard` (per the route table).
- `/dashboard` is guarded by `authGuard`. If you are not authenticated, the guard
  saves the intended URL and kicks off an MSAL redirect to Entra ID.
- `/admin` is guarded by `authGuard` + `roleGuard(['Admin'])`.

Whether the login redirect **completes** depends on the Entra app registration -
see Section 6.

---

## 4. The happy-path walkthrough (with a real Entra app registration)

If the Entra app registration is in place (Section 6), here is the end-to-end
flow and what you should observe:

1. **Navigate to `/dashboard` while logged out.** The `authGuard` persists
   `postLoginRedirect` in `sessionStorage` and triggers a full-page redirect to
   `https://login.microsoftonline.com/...`. Expect the browser URL to change to
   the Microsoft sign-in page.
2. **Sign in** with a user that has an app role (`Admin` or `Viewer`) assigned.
   You may see a consent prompt on first login. Expect to be redirected back to
   `http://localhost:4200`.
3. **The login component processes the callback.** Briefly you may see
   "Completing sign-in...", then it navigates to the route you originally
   requested (`/dashboard`). The session store is now populated (token + expiry +
   roles).
4. **Dashboard loads items.** It issues `GET /entra-backend/items` with the bearer token
   attached automatically by the interceptor. Expect either a list of items or an
   empty list (the `item` table starts empty until an Admin creates one).
5. **Try `/admin`.**
   - As an **Admin**: the create form works; submitting issues `POST /entra-backend/items`
     and you should see the newly created item reflected (201 Created).
   - As a **Viewer** (or no Admin role): `roleGuard` keeps you off the route
     client-side; and even if you reached the API, the server returns **403** for
     the write. Both are expected - the server is the real boundary.

### Observe the token and refresh (browser DevTools)

- **Network tab -> your `api/items` request -> Request Headers:** confirm
  `Authorization: Bearer <token>`.
- **Copy that token into jwt.io** (test tokens only): confirm the payload has a
  `roles` array (`Admin`/`Viewer`), `aud` = the client id or `api://<client id>`,
  `iss` = the tenant `.../v2.0` issuer, and a future `exp`.
- **Filter the Network tab to the token endpoint** (`.../oauth2/v2.0/token`) and
  trigger a refresh (wait until within ~5 min of expiry, or force a 401): expect
  a single request with `grant_type=refresh_token`, a rotated `refresh_token`,
  and a new `access_token`. Fire several API calls at once and confirm only
  **one** token call happens (single-flight coalescing).

`entra-backend/VERIFICATION.md` documents these three procedures in full detail.

### Observe server-side authorization outcomes

After exercising the endpoints, inspect the audit trail:

```bash
psql -h localhost -p 5432 -U postgres -d my_workspace -c \
  "SELECT subject, authorities, http_method, path, status, occurred_at FROM access_audit ORDER BY occurred_at DESC LIMIT 20;"
```

Expect rows showing the caller's subject, the resolved `ROLE_*` authorities, the
method/path, and the status (e.g. `200` for a Viewer GET, `403` for a Viewer
POST, `201` for an Admin POST). Remember: this table is **audit-only** - it
records outcomes, it never makes authorization decisions.

---

## 5. Running the automated tests (no login required)

The test suites validate the security and token-lifecycle behavior without any
cloud dependency - a great way to prove the system is correct locally.

### Backend tests (JDK 21)

```bash
cd entra-backend
mvn -o clean test
```

Expect: `Tests run: 15, Failures: 0, Errors: 0` and `BUILD SUCCESS`. These
include the jqwik property tests (each running 100 generated cases) and the
`@WebMvcTest` security-slice status matrix. No database or network is needed -
the tests mint tokens locally and mock the decoder where appropriate.

> Tip: if you ever see a spurious `415` on the write tests, run with `clean`
> (as above). It forces recompilation with the `-parameters` flag Jackson needs
> for record binding.

### Frontend tests (headless Chrome)

```bash
cd EntraUi
npm test -- --watch=false --browsers=ChromeHeadless
```

Expect: `Executed 13 of 13 SUCCESS`, `TOTAL: 13 SUCCESS`. These include the
fast-check property tests (single-flight refresh, retry-then-reauth) and the
interceptor example tests. ChromeHeadless must be able to launch; if you omit
`--browsers=ChromeHeadless` it will try to open a real Chrome window.

### Frontend production build

```bash
cd EntraUi
npm run build
```

Expect `Application bundle generation complete.` and exit 0. The bundle-size
budget **warning** may appear again - still a success.

---

## 6. What actually needs Entra ID, and what does not

Be clear-eyed about the cloud dependency so you know what you can validate today:

**Works with NO Entra ID / no login:**
- Backend starts, Flyway migrates, tables are created.
- `GET /entra-backend/items` returns **401** unauthenticated (proves the filter chain).
- The entire backend test suite (15 tests) passes.
- The entire frontend test suite (13 tests) passes.
- The frontend serves at `:4200` and you can see the app shell and routing
  attempt the login redirect.

**Requires a real Entra ID app registration to fully exercise:**
- Completing interactive sign-in and returning with a real token.
- Loading the dashboard's items *as an authenticated user*.
- The Admin write path end-to-end and the `access_audit` rows for real users.
- Observing the real refresh-token rotation in the Network tab.

### What the Entra app registration must provide

The project is hard-wired (in `EntraUi/src/app/auth/msal.config.ts` and the
backend `application.yml`) to these identity constants:

- **Tenant ID:** `76325907-a5db-46b1-9d5a-cbcca2e63e66`
- **Client ID:** `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c`
- **API scope:** `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c/access_as_user`
- **Redirect URI (SPA):** `http://localhost:4200`

For real login to work, an Entra app registration matching these must exist in
that tenant, configured as a **Single-page application** with the redirect URI
above, exposing the `access_as_user` scope, and defining **app roles** named
`Admin` and `Viewer` that are assigned to your test users. If you are using a
different tenant/app, update those constants in `msal.config.ts` and
`application.yml` (issuer-uri and `app.security.audiences`) to match, then
rebuild/restart both apps.

> If you only want to validate the code and its behavior, the automated tests
> (Section 5) and the unauthenticated 401 check (Section 2) are sufficient - you
> can defer the Entra setup until you want to click through a live login.

---

## 7. Quick troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Backend exits ~10s after start with a connection error | PostgreSQL not running / wrong creds | Start Postgres; ensure `my_workspace` exists with `postgres`/`postgres`. |
| Backend fails at startup with a JWKS/issuer error | No internet to reach the Entra authority for OIDC discovery | Ensure network access; or point `issuer-uri` at a reachable authority. |
| Backend fails with a Hibernate schema validation error | Entity/schema mismatch | Ensure Flyway ran (check `flyway_schema_history`); do not hand-edit tables. |
| `mvn` builds with the wrong Java | `JAVA_HOME` not 21 | Point `JAVA_HOME` at a JDK 21 for that terminal. |
| `curl /entra-backend/items` returns connection refused | Backend not up | Start the backend; watch for "Tomcat started on port 8080". |
| Frontend build shows a bundle budget warning | Expected for this reference | Ignore - it is a warning, not an error. |
| Login redirect loops or errors on return | Entra app registration missing/mismatched | See Section 6; align the constants and app-registration config. |
| Frontend tests try to open a real browser | Missing `--browsers=ChromeHeadless` | Add the flag; ensure Chrome is installed. |

---

## 8. The minimal validation checklist

If you just want to confirm "it works" locally without Entra:

- [ ] `psql ... -c "\dt"` shows `item`, `app_user`, `access_audit`,
      `flyway_schema_history` after the backend starts.
- [ ] `curl -i http://localhost:8080/entra-backend/items` returns **401** with a
      `WWW-Authenticate: Bearer` header.
- [ ] `mvn -o clean test` (in `entra-backend`) reports **15 tests, BUILD SUCCESS**.
- [ ] `npm test -- --watch=false --browsers=ChromeHeadless` (in `EntraUi`)
      reports **13 SUCCESS**.
- [ ] `npm run build` (in `EntraUi`) completes with exit 0.
- [ ] `http://localhost:4200` serves the app and attempts the login redirect.

Tick all of those and the full stack is verified end to end except for the live
Entra sign-in, which needs the app registration from Section 6.
