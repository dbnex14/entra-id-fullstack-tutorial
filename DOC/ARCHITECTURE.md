# Architecture

This document describes the components, the request/token flows, and the key
design decisions behind the Entra ID OAuth2 full-stack reference.

## The three actors

1. **Client_App** - the Angular SPA (`http://localhost:4200`). A *public client*:
   it cannot safely hold a secret, so it uses PKCE. It authenticates the user via
   Entra ID and calls the API with a bearer token.
2. **Authority** - Microsoft Entra ID
   (`https://login.microsoftonline.com/<tenant>/v2.0`). Authenticates the user,
   obtains consent, and issues tokens: an Access_Token (JWT) for the API, an
   id_token, and a rotating refresh token.
3. **Resource_Server** - the Spring Boot API (`http://localhost:8080`). Stateless:
   it trusts no session, only the JWT presented on each request. It validates the
   token and derives authorization from the token's `roles` claim.

## Guiding principle: claim-driven authorization

Authorization decisions are made **only** from the validated token's `roles`
claim. The database's `app_user` and `access_audit` tables exist for
profile/traceability and are **never** consulted to decide access. This keeps the
security model simple and auditable: the token is the single source of truth for
"who may do what."

## Backend architecture (entra-backend)

A layered Spring Boot resource server:

- **Security layer** (`security/`)
  - `JwtConfig` builds the `JwtDecoder` (JWKS via OIDC discovery) and composes the
    validator chain: issuer + timestamp(60s skew) + audience. It also defines the
    CORS policy and the `roles` -> authorities converter wiring.
  - `AudienceValidator` enforces the `aud` claim (Spring does not by default).
  - `RolesClaimConverter` maps the `roles` claim to `ROLE_*` authorities.
  - `SecurityConfig` assembles the stateless filter chain, enables method
    security, and wires the audit filter.
  - `AccessAuditFilter` records each request's outcome (audit only).
- **Domain layer** (`item/`)
  - `Item` (JPA entity), `ItemDto`, `CreateItemRequest`, `UpdateItemRequest`.
  - `ItemRepository` (Spring Data JPA), `ItemService` (business logic + stamps the
    token subject onto rows), `ItemController` (role-protected REST endpoints).
- **Audit layer** (`audit/`)
  - `AccessAudit` entity + `AccessAuditRepository`.
- **Persistence**
  - PostgreSQL, with the schema owned by **Flyway** migrations. Hibernate runs in
    `validate` mode - it never creates or alters tables.

### Backend startup sequence

1. Spring Boot boots; HikariCP opens the datasource (10s connection budget).
2. Flyway applies pending migrations (creates `item`, `app_user`,
   `access_audit`, `flyway_schema_history` on a fresh DB).
3. Hibernate validates entity mappings against the migrated schema.
4. The OAuth2 resource server performs OIDC discovery to load Entra's JWKS keys.
5. Tomcat starts on port 8080.

If the database is unreachable, startup fails fast (~10s). If the JWKS/issuer is
unreachable, startup fails - the server refuses to run without the keys it needs
to validate tokens.

## Frontend architecture (EntraUi)

An Angular 19 standalone SPA (no NgModules; `inject()` DI and signals throughout):

- **Auth infrastructure** (`app/auth/`)
  - `msal.config.ts` - identity constants and MSAL instance/guard/interceptor
    factories (localStorage cache; redirect flow).
  - `auth-session.store.ts` - signal-based session state (token/expiry/roles),
    `isAuthenticated`, and `needsProactiveRefresh` (300s window).
  - `token-refresh.service.ts` - single-flight silent refresh with bounded retry.
  - `auth-token.interceptor.ts` - attaches the bearer token, drives proactive and
    reactive refresh.
  - `auth.guard.ts` - `authGuard` (session gate) and `roleGuard` (client-side UX
    gate; the server remains authoritative).
- **Feature components** (`app/{login,dashboard,admin}/`)
  - `login` handles the redirect callback and session establishment.
  - `dashboard` reads `/api/items`; `admin` performs the Admin-only write.
- **Composition** (`app/app.config.ts`, `app/app.routes.ts`)
  - Providers wire the router, HttpClient + custom interceptor, and MSAL.

## Request flows

### Login (interactive)

1. User navigates to a guarded route while unauthenticated.
2. `authGuard` saves the intended URL under `sessionStorage['postLoginRedirect']`
   and calls `beginInteractiveLogin()`.
3. MSAL redirects to Entra ID (with PKCE code_challenge, state, nonce).
4. User authenticates/consents; Entra redirects back to `http://localhost:4200`.
5. The login component's `handleRedirectPromise()` validates `state`, detects
   `error` params, and (on success) exchanges the code for tokens.
6. The session store is populated; the app navigates to the saved route.

### Authenticated API call

1. Component issues an HTTP request to `http://localhost:8080/api/...`.
2. The interceptor, if the token is within the 300s window, refreshes first
   (proactive), then attaches `Authorization: Bearer <token>`.
3. The backend validates the token and evaluates `@PreAuthorize`.
4. On a 401 `invalid_token` challenge, the interceptor performs one reactive
   refresh and retries the request exactly once.

### Token refresh (single-flight)

Concurrent callers that all need a refresh coalesce into **one** call to the token
endpoint (the `inFlight$` latch + `shareReplay(1)`). Transient failures retry up
to 3 times with exponential backoff; interaction-required errors bypass retry and
trigger interactive login.

## Key design decisions

- **Stateless backend.** No HTTP session; every request is authorized from its
  bearer token. Enables horizontal scaling and matches OAuth2 resource-server
  norms.
- **Flyway owns the schema; Hibernate validates.** Schema is code, versioned and
  reproducible; `ddl-auto: validate` catches drift at startup instead of silently
  mutating tables.
- **Custom token layer over MSAL.** MSAL handles PKCE/rotation, but the specific,
  testable behaviors (proactive/reactive refresh, single-flight, bounded retry)
  live in a thin custom layer so they are explicit and property-tested. The custom
  functional interceptor is used *instead of* MSAL's class-based interceptor to
  avoid double token attachment.
- **Client role guard is UX only.** `roleGuard` improves the experience but is not
  a security boundary; the server independently enforces `hasRole('Admin')`.
- **Audit is observation, not enforcement.** `AccessAuditFilter` records outcomes
  after the fact; it never makes an authorization decision.

## Correctness properties (validated by tests)

The design defines universal properties, each checked by a property-based test:

1. Roles claim maps to exactly the `ROLE_`-prefixed distinct set.
2. Invalid tokens (expired / bad aud / bad iss / bad signature) are rejected 401.
3. CORS headers honor only the allowed origin.
4. Write endpoints require `ROLE_Admin` (403 causes no mutation).
5. Refresh is single-flight and rotates the stored token.
6. Transient refresh failures are retried at most three times, then re-auth.

See `DOC/SECURITY.md` and the test files referenced in `GUIDE/LEARNING-GUIDE.md`.
