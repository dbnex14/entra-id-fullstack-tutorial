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
| SPA redirect URI | `http://localhost:4200` | frontend `msal.config.ts`; Entra app registration (**Single-page application** platform) |
| Postman native redirect URI | `https://oauth.pstmn.io/v1/callback` | Entra app registration (**Mobile and desktop applications** platform); used only for Postman OAuth login - see `DOC/API.md` Method B |
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

## Access token version (v2) - REQUIRED

The backend is configured for the Entra **v2.0** issuer
(`https://login.microsoftonline.com/<tenant>/v2.0`). For token validation to
succeed, the Entra app registration MUST issue **v2** access tokens; otherwise
Entra issues **v1** tokens whose issuer is `https://sts.windows.net/<tenant>/`
(note: no `/v2.0`) and the backend rejects them with **401** even though the user
signed in successfully and the token carries the right audience and roles.

Set this on the app registration:

1. Entra admin center -> App registrations -> your app -> **Manage -> Manifest**.
2. Set `"requestedAccessTokenVersion": 2` (it defaults to `null`, which means v1
   for the access token). Save.
3. Sign out and sign in again so a fresh v2 token is minted (cached v1 tokens are
   not upgraded automatically).

How to confirm: decode the access token at https://jwt.ms and check
`"ver": "2.0"` and `"iss": "https://login.microsoftonline.com/<tenant>/v2.0"`.
A `"ver": "1.0"` / `sts.windows.net` issuer is the classic cause of a
"signed in but every API call returns 401" symptom.

(Alternative, not recommended: point the backend `issuer-uri` at the v1 issuer
`https://sts.windows.net/<tenant>/` to match v1 tokens. The whole app, MSAL
config, and docs assume v2, so fixing the manifest to v2 keeps everything
consistent.)

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

### MSAL initialization (app initializer)

`@azure/msal-browser` v5 requires an asynchronous `initialize()` call before any
auth API (login/logout/redirect handling); calling one first throws
`uninitialized_public_client_application`. `app.config.ts` registers a
`provideAppInitializer` that awaits `instance.initialize()` and then
`handleRedirectPromise()` during bootstrap, populating the session store from a
successful redirect result. This runs before the router/guards, so auth calls are
always made against an initialized MSAL instance.

### Frontend routes and app shell

| Route | Guard | Purpose |
| --- | --- | --- |
| `/home` | none (public) | Landing page; default target of `''` and unknown URLs |
| `/login` | none | Redirect/callback handler |
| `/dashboard` | `authGuard` | Item list (Viewer or Admin) |
| `/admin` | `authGuard` + `roleGuard(['Admin'])` | Create / edit / delete items (Admin) |

The app is titled **"Item Manager"** (header and `index.html` title). The header
shows a **Sign in** control when signed out and **Sign out** + the current roles
when signed in; nav links appear only when authenticated, and the **Admin** link
only for users holding the `Admin` role (UX only - route guards and the backend
remain authoritative). The public `/home` route is what prevents opening the app
root (or returning after logout) from forcing an immediate login redirect.

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
4. Set `"requestedAccessTokenVersion": 2` in the app manifest (see "Access token
   version" above) so the backend accepts the tokens.
5. Restart the backend (config change) and let the frontend rebuild.
6. *(Optional, for Postman testing only)* If you want Postman to perform the OAuth
   login itself (Method B in `DOC/API.md`), also add a **Mobile and desktop
   applications** platform with redirect URI `https://oauth.pstmn.io/v1/callback`.
   This is required because a SPA-only registration rejects Postman's server-side
   token exchange with **AADSTS9002327**; a native redirect URI does not. The SPA
   platform (`http://localhost:4200`) stays as-is for the Angular app.

Full app-registration steps are in `GUIDE/RUNNING-MAC-GUIDE.md` Section 8.

## Sign-out (post-logout redirect)

Sign-out clears the local session and calls MSAL `logoutRedirect` with
`postLogoutRedirectUri: window.location.origin` (`http://localhost:4200`), which
ends the Entra session and returns the browser to the app. For a SPA, Entra
validates that post-logout URI against the **Redirect URIs** already listed under
the app registration's **Single-page application** platform - there is no separate
"post logout URI" box. So having `http://localhost:4200` in that SPA redirect-URI
list (which login needs anyway) is what makes sign-out land back on the app. The
separate **Front-channel logout URL** field is only for single-sign-out
notifications and is not needed here.

## Testing the Viewer role (creating a second user)

The role split (Viewer can read; Admin can read + write/delete) is driven purely
by the token's `roles` claim, which comes from Entra **app role assignments**. To
exercise the Viewer path you need an identity assigned the `Viewer` role only.

1. **Create a user** (if the tenant has only your Admin account): Entra admin
   center -> **Identity -> Users -> All users -> + New user -> Create new user**.
   Set a user principal name (e.g. `viewer@<tenant>.onmicrosoft.com`), a display
   name, and a password you control. A newly created user is prompted to change
   the password on first sign-in.
2. **Assign the Viewer role** (done on the Enterprise application, not the
   registration): **Identity -> Applications -> Enterprise applications ->** your
   app **-> Manage -> Users and groups -> + Add user/group**. Pick the user, set
   the role to **Viewer**, Assign.
3. **Sign in as the Viewer.** Sign out of the current session first and use an
   incognito window (so a cached Admin token is not reused), then sign in as the
   Viewer. App role changes only appear in a newly minted token, so a fresh
   sign-in is required.
4. **Expected result:** header shows `roles: Viewer`; the Dashboard lists items
   (200); the **Admin** nav link is hidden; a direct write/delete would return 403.

Alternatively, invite an external email as a guest
(**Users -> + New user -> Invite external user**) and assign it the Viewer role.

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
