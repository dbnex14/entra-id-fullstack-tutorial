# Security Model

This document explains how the system establishes identity and enforces
authorization, and where the real trust boundary lies.

## Trust boundary in one sentence

The **backend resource server is the only authority on access**: it validates
every JWT and enforces role checks on every protected call. Everything the
browser does (route guards, hiding buttons) is convenience, not security.

## Token validation pipeline (backend)

Every request to a protected endpoint carries `Authorization: Bearer <JWT>`. The
resource server validates it in this order (see `JwtConfig`,
`AudienceValidator`):

1. **Signature.** The `JwtDecoder` is built via
   `NimbusJwtDecoder.withIssuerLocation(issuer)`, which performs OIDC discovery
   against the Entra authority and loads its JWKS (public signing keys) at
   startup. Each token's RS256 signature is verified against those keys. A bad
   signature is rejected.
2. **Issuer (`iss`).** `JwtValidators.createDefaultWithIssuer(issuer)` asserts the
   token was minted by the configured tenant issuer (the `/v2.0` authority). A
   token from any other issuer is rejected.
3. **Expiry (`exp`) with clock skew.** A `JwtTimestampValidator` with a 60-second
   allowance rejects tokens whose `exp` is in the past beyond the skew window.
4. **Audience (`aud`).** The custom `AudienceValidator` accepts the token only if
   its `aud` claim contains one of the configured audiences (the Client_ID or its
   `api://` form). Spring does not validate `aud` by default; this closes the
   "confused deputy" gap where a validly-signed token minted for a different API
   would otherwise be accepted.

Any failure produces an `invalid_token` result that Spring's
`BearerTokenAuthenticationEntryPoint` turns into **401 Unauthorized** with a
`WWW-Authenticate: Bearer error="invalid_token"` challenge.

## From claims to authorities

`RolesClaimConverter` turns the token's `roles` claim into Spring authorities:

- Reads the `roles` claim. If absent or not an array, yields **no** authorities.
- Filters to string values (ignores non-strings), de-duplicates, and preserves
  case.
- Maps each raw role `r` to `ROLE_<r>` (e.g. `Admin` -> `ROLE_Admin`).

The `ROLE_` prefix matters: Spring's `hasRole('Admin')` is shorthand for checking
the authority `ROLE_Admin`. Entra emits raw role names without the prefix, so this
converter is the single place that applies it.

## Endpoint authorization

Method security is enabled (`@EnableMethodSecurity`), and endpoints are gated with
`@PreAuthorize`:

- `GET /entra-backend/items` - `hasAnyRole('Viewer','Admin')`.
- `GET /entra-backend/items/{id}/history` - `hasAnyRole('Viewer','Admin')`.
- `GET /entra-backend/history` - `hasAnyRole('Viewer','Admin')`.
- `POST` / `PUT` / `DELETE /entra-backend/items` - `hasRole('Admin')`.

Reads (items and change history) are open to Viewer and Admin; only Admin can
write items, and therefore only Admin can generate change-history entries. The
change history is observational data - both roles may read it, but it is never
consulted to make an authorization decision (authorization is claim-driven, R2).

Because `@PreAuthorize` runs before the controller body, a caller lacking the
required role gets **403** and the method never executes - so a forbidden write or
delete performs no mutation. This "no mutation on 403" property is directly tested
(see `WriteAuthorizationPropertyTest`).

## Stateless sessions

The filter chain uses `SessionCreationPolicy.STATELESS`, disables CSRF (there is
no session/cookie to protect - the bearer token is the credential), permits
`OPTIONS` preflight and `/actuator/health`, and authenticates everything else.

## CORS

`JwtConfig.corsConfigurationSource()` allows exactly one origin,
`http://localhost:4200`:

- Allowed methods: GET, POST, PUT, DELETE, OPTIONS.
- Allowed headers: Authorization, Content-Type.
- Allow credentials: true.

A request from any other origin receives no `Access-Control-Allow-Origin` header,
so the browser blocks it. A single explicit origin (not `*`) is required for
`allow-credentials: true` and prevents other sites from reading the API on a
user's behalf. This is validated by `CorsPropertyTest`.

## Frontend token lifecycle

The SPA manages tokens through a thin, testable layer over MSAL:

- **Storage.** MSAL caches tokens (including the rotating refresh token) in
  `localStorage`, scoped to the SPA origin.
- **Proactive refresh.** Before a request within 300s of expiry, the interceptor
  refreshes first, so a near-stale token rarely reaches the server.
- **Reactive refresh.** On a 401 `invalid_token` challenge, the interceptor
  performs exactly one refresh and retries the original request once.
- **Single-flight.** Concurrent refreshes coalesce into one token-endpoint call.
- **Bounded retry.** Transient refresh failures retry at most 3 times with
  exponential backoff; interaction-required errors (expired/revoked refresh
  token, consent, MFA) are not retried and trigger interactive login.
- **Refresh-token rotation.** Entra rotates the refresh token on each use; MSAL
  persists the new one. The app never handles raw refresh tokens.

## The client role guard is NOT a security control

`roleGuard(['Admin'])` on the `/admin` route is a **UX gate**: it avoids routing a
user into a screen whose every call would 403. It reads roles from client memory,
which a user can trivially alter. It is defense in depth, not the boundary - the
backend independently re-validates the token and enforces `hasRole('Admin')` on
every call. Never rely on the client guard for protection.

The same "UX only" caveat applies to **hiding the Admin nav link** for non-Admins
in the header: it is a convenience so Viewers are not shown a page they cannot
use, but it hides nothing security-relevant. The `/admin` route guard and the
backend role checks are the actual gate.

## Sign-out

Sign-out is a *full* logout: the app clears the local signal session first (so no
stale token can be attached during the redirect) and then calls MSAL
`logoutRedirect`, which ends the Entra session, clears MSAL's origin-scoped cache
(including the rotating refresh token), and returns to the app origin. Clearing
only the local session would leave the Entra SSO session active and let the next
sign-in silently re-authenticate the same user without a prompt.

## PKCE and the public client

The SPA is a public client and cannot hold a secret. It uses the Authorization
Code Flow with **PKCE**: MSAL generates a `code_verifier`/`code_challenge`,
sends only the challenge on the authorize request, and redeems the code with the
verifier at the token endpoint. MSAL also validates `state` (login CSRF) and the
OIDC `nonce`. No client secret exists anywhere in this system - the backend only
*validates* tokens; it never calls Entra with a secret.

## Auditing (observation only)

`AccessAuditFilter` records, after each request, the subject, the resolved
`ROLE_*` authorities, the method, path, and response status into the
`access_audit` table. This is strictly for traceability and the verification
story - it records the **outcome** of a claim-driven decision and never makes one.
A failure to write an audit row never affects the user-facing response.

## What is validated by automated tests

- Roles conversion (Property 1), invalid-token 401 rejection across four mutation
  dimensions (Property 2), CORS single-origin (Property 3), write-authorization +
  no-mutation-on-403 (Property 4) - backend, jqwik.
- Single-flight refresh + rotation (Property 5), bounded retry then re-auth
  (Property 6), interceptor behavior - frontend, fast-check.
- Concrete status matrix (Viewer/Admin/anonymous) - backend security slice.

## Operational notes and hardening ideas

This is a reference/tutorial. For production you would additionally consider:
rate limiting, security headers (HSTS, CSP) at the edge, restricting the JWKS
refresh cadence, structured audit log shipping, TLS everywhere (the localhost
setup is HTTP), and per-environment identity constants rather than in-code values
(see `DOC/CONFIGURATION.md`).

## Guarding security-critical changes

A Kiro Agent Hook (`Backend: security-change guard`) flags edits to
`SecurityConfig.java`, `application.yml`, and the backend `security/` package so
that changes to auth, CORS, audience, issuer, or token validation get a
deliberate second look. The frontend code-review hook similarly watches for token
mishandling and XSS. These run only inside Kiro IDE and are advisory; the
authoritative checks are the security property tests plus CI. See
`DOC/AGENT-HOOKS.md`.
