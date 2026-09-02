# Configuration

Every setting that controls the running system, where it lives, and how to change
it. The identity constants are the ones you must align with your Entra tenant/app.

## Identity constants (the important ones)

These are hard-wired in two places and MUST agree with each other and with your
Entra app registration.

| Constant | Value | Where |
| --- | --- | --- |
| Tenant ID | `76325907-a5db-46b1-9d5a-cbcca2e63e66` | frontend `msal.config.ts`; backend issuer-uri |
| Client ID | `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c` | frontend `msal.config.ts`; backend audiences |
| API scope | `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c/access_as_user` | frontend `msal.config.ts` |
| Authority / issuer | `https://login.microsoftonline.com/76325907-a5db-46b1-9d5a-cbcca2e63e66/v2.0` | both |
| Accepted audiences | `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c` and `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c` | backend `application.yml` |
| SPA redirect URI | `http://localhost:4200` | frontend `msal.config.ts`; Entra app registration |
| Frontend origin | `http://localhost:4200` | backend CORS (`JwtConfig`) |
| Backend origin | `http://localhost:8080` (API base `http://localhost:8080/entra-backend`) | frontend interceptor + protected-resource map |

## Backend configuration (application.yml)

File: `entra-backend/src/main/resources/application.yml`

| Setting | Value | Purpose |
| --- | --- | --- |
| `server.port` | `8080` | API listen port |
| `server.servlet.context-path` | `/entra-backend` | mounts every endpoint under `/entra-backend` (e.g. `/entra-backend/items`, `/entra-backend/actuator/health`) |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | Entra `/v2.0` authority | OIDC discovery -> JWKS; asserts `iss` |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/my_workspace` | database |
| `spring.datasource.username` / `password` | `postgres` / `postgres` | database creds |
| `spring.datasource.hikari.connection-timeout` | `10000` | 10s connect budget (fail fast) |
| `spring.jpa.hibernate.ddl-auto` | `validate` | schema owned by Flyway; ORM only validates |
| `spring.jpa.open-in-view` | `false` | close persistence context promptly |
| `spring.flyway.enabled` | `true` | run migrations on startup |
| `spring.flyway.locations` | `classpath:db/migration` | where migrations live |
| `spring.flyway.baseline-on-migrate` | `false` | do not silently baseline a non-empty schema |
| `spring.flyway.validate-on-migrate` | `true` | checksum drift halts startup |
| `app.security.audiences` | list of the two accepted `aud` values | custom audience validation |

The `jwk-set-uri` override is present but commented out - OIDC discovery from
`issuer-uri` derives the JWKS URI automatically; uncomment only if discovery is
unavailable in your network.

## Frontend configuration (msal.config.ts)

File: `EntraUi/src/app/auth/msal.config.ts`

| Export / setting | Value | Purpose |
| --- | --- | --- |
| `TENANT_ID` | tenant guid | Authority tenant segment |
| `CLIENT_ID` | app (client) id | `client_id` on auth/token requests; token `aud` |
| `API_SCOPE` | `api://<CLIENT_ID>/access_as_user` | scope that mints an API-audienced token |
| `AUTHORITY` | `https://login.microsoftonline.com/<TENANT_ID>/v2.0` | OIDC authority |
| `auth.redirectUri` | `http://localhost:4200` | must match a registered SPA redirect URI |
| `cache.cacheLocation` | `localStorage` | origin-scoped token cache (persists refresh token) |
| requested scopes | `API_SCOPE`, `openid`, `profile`, `offline_access` | `offline_access` yields a refresh token |
| protected resource map | `http://localhost:8080/entra-backend/*` -> `[API_SCOPE]` | which calls get a token |

`API_SCOPE` and `AUTHORITY` are derived from `CLIENT_ID`/`TENANT_ID`, so changing
the two ids updates everything consistently.

## Behavioral constants worth knowing

| Constant | Value | Where | Meaning |
| --- | --- | --- | --- |
| Proactive refresh window | 300000 ms (5 min) | `auth-session.store.ts` | refresh before a request if within this of expiry |
| Clock skew allowance | 60 s | `JwtConfig` (`JwtTimestampValidator`) | tolerance on `exp` |
| Max transient retries | 3 | `token-refresh.service.ts` | plus the initial attempt = 4 total |
| Backoff | 500 / 1000 / 2000 ms | `token-refresh.service.ts` | exponential per retry |
| API URL prefix | `http://localhost:8080/entra-backend` | `auth-token.interceptor.ts` | only these requests get a bearer token |

## Editor configuration (.vscode/settings.json)

The repo's `.vscode/settings.json` points the VS Code Java language server at a
**Windows** JDK path (`C:\\Program Files\\java\\jdk-corretto-21`). This affects
only the VS Code Java tooling, not the build. On macOS/Linux, either use IntelliJ
for the backend (and ignore this file) or update the paths to your local JDK 21
home (`/usr/libexec/java_home -v 21` on macOS). See `GUIDE/RUNNING-MAC-GUIDE.md`.

## Changing tenant / app (using your own Entra registration)

To point the app at a different Entra tenant/app:

1. In `EntraUi/src/app/auth/msal.config.ts`, set `TENANT_ID` and `CLIENT_ID`
   (`API_SCOPE` and `AUTHORITY` derive from them).
2. In `entra-backend/src/main/resources/application.yml`, set
   `spring.security.oauth2.resourceserver.jwt.issuer-uri` to your tenant's `/v2.0`
   authority, and set `app.security.audiences` to your client id and its `api://`
   form.
3. Ensure the Entra app registration is a **Single-page application** with
   redirect URI `http://localhost:4200`, exposes the `access_as_user` scope, and
   defines app roles `Admin` and `Viewer` assigned to your test users.
4. Restart the backend (config change) and let the frontend rebuild.

Full app-registration steps are in `GUIDE/RUNNING-MAC-GUIDE.md` Section 8.

## Ports and origins summary

- Backend API: `http://localhost:8080/entra-backend` (host/port change via
  `server.port`, path via `server.servlet.context-path`; if you change either,
  update the frontend interceptor prefix and protected-resource map).
- Frontend SPA: `http://localhost:4200` (change via `ng serve --port`; if you do,
  update the backend CORS allowed origin in `JwtConfig` and the Entra redirect
  URI).

## No secrets anywhere

There is no client secret in this system. The SPA is a public client using PKCE,
and the backend only validates tokens - it never calls Entra with a secret. The
only "credentials" in config are the local PostgreSQL `postgres`/`postgres`, which
are for local development only.
