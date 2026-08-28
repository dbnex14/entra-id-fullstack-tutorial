// auth/msal.config.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// MSAL + IDENTITY CONFIGURATION
// ─────────────────────────────────────────────────────────────────────────────
//
// This file is the single, declarative place where the SPA is told *who it is*
// (its Client_ID / registered application), *where to authenticate* (the Entra
// ID Authority for our tenant), and *what it is allowed to ask for* (the API
// scope plus the OIDC scopes). Everything downstream — the route guards, the
// HTTP interceptor, the token-refresh service — depends on the three factories
// exported here being wired into `app.config.ts` via the MSAL injection tokens
// (MSAL_INSTANCE, MSAL_GUARD_CONFIG, MSAL_INTERCEPTOR_CONFIG).
//
// ── HOW MSAL DRIVES THE OAUTH2 AUTHORIZATION CODE FLOW WITH PKCE ─────────────
// The `PublicClientApplication` created below implements the OAuth2/OIDC
// Authorization Code Flow with PKCE end-to-end. A browser-based SPA is a
// "public client": it cannot safely hold a client secret, so PKCE (Proof Key
// for Code Exchange, RFC 7636) is used instead of a secret to bind the
// authorization request to the token redemption. MSAL performs ALL of the
// following automatically — the application code never handles these values:
//
//   1. PKCE code_verifier / code_challenge:
//      Before redirecting to the Authority's /authorize endpoint, MSAL
//      generates a high-entropy random `code_verifier`, hashes it with SHA-256
//      to produce the `code_challenge`, and sends only the challenge (plus
//      `code_challenge_method=S256`) on the authorize request. The raw verifier
//      never leaves the browser until the token exchange. This is what makes it
//      safe to run the Authorization Code Flow without a client secret.
//
//   2. state / nonce (CSRF + replay protection):
//      MSAL generates and validates the `state` parameter (login CSRF defense,
//      surfaced to our login component per R1.9) and the OIDC `nonce` (binds the
//      returned id_token to this specific request), rejecting mismatches.
//
//   3. Authorization code -> token exchange:
//      On return to the redirectUri, MSAL POSTs the received `code` together
//      with the original `code_verifier` to the Authority's /token endpoint.
//      The Authority recomputes the challenge from the verifier and only then
//      issues the tokens (access_token, id_token, refresh_token).
//
//   4. Refresh-token rotation:
//      When `acquireTokenSilent` needs a fresh access token, MSAL uses the
//      stored refresh token with a `grant_type=refresh_token` call. Entra ID
//      rotates the refresh token on each use — it returns a NEW refresh token
//      and invalidates the old one. MSAL persists the rotated token internally
//      (in the cache location configured below); our code never sees or stores
//      raw refresh tokens.
//
// ── WHY A THIN CUSTOM LAYER ON TOP OF MSAL? ─────────────────────────────────
// MSAL already ships a default `MsalInterceptor` and `acquireTokenSilent` that
// implement PKCE, rotation, and same-origin token storage. However, Requirements
// 3 and 4 demand SPECIFIC, OBSERVABLE behaviors that the opaque default
// interceptor does not make explicit or testable:
//   - a single reactive refresh on a 401 expired-token challenge (R3.3),
//   - a proactive refresh within a fixed 300s window BEFORE issuing a request
//     (R3.4, R4.1),
//   - explicit queuing so concurrent requests trigger exactly one token call
//     (R4.4),
//   - bounded transient retry (at most 3 attempts) before re-authentication
//     (R4.6).
// To make these behaviors explicit, auditable, and property-testable — and to
// serve the instructional/verification goals of this reference — we layer a thin
// custom `AuthTokenInterceptor` + `AuthSessionStore` + `TokenRefreshService`
// over MSAL's `acquireTokenSilent` / `acquireTokenRedirect` primitives rather
// than relying solely on the default MsalInterceptor. This config file still
// provides the standard MSAL factories (the guard + interceptor configs are
// consumed by MsalGuard and, where used, the default interceptor plumbing), but
// the observable token lifecycle lives in the custom layer.

import {
  Configuration,
  IPublicClientApplication,
  InteractionType,
  PublicClientApplication,
} from '@azure/msal-browser';
import {
  MsalGuardConfiguration,
  MsalInterceptorConfiguration,
} from '@azure/msal-angular';

// ─────────────────────────────────────────────────────────────────────────────
// IDENTITY CONSTANTS (R1.3)
// ─────────────────────────────────────────────────────────────────────────────
//
// These are the authoritative identity coordinates shared across the whole
// front end. They intentionally mirror the backend's accepted issuer/audience
// so that a token minted for this SPA validates cleanly against the Resource
// Server. Deriving API_SCOPE and AUTHORITY from CLIENT_ID / TENANT_ID keeps the
// four values consistent — change the tenant or app registration in ONE place.

/**
 * The Entra ID (Azure AD) directory/tenant that owns the app registration and
 * issues tokens. It is the `{tenant}` segment of the Authority URL and appears
 * inside the token's issuer (`iss`) claim.
 */
export const TENANT_ID = '76325907-a5db-46b1-9d5a-cbcca2e63e66';

/**
 * The Application (client) ID of THIS SPA's Entra ID app registration. MSAL
 * sends it as `client_id` on the /authorize and /token requests, and it is the
 * value the backend expects in the token's audience (`aud`) claim.
 */
export const CLIENT_ID = '4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c';

/**
 * The delegated API permission (scope) that grants this SPA access to the
 * protected backend on behalf of the signed-in user. Requesting this scope is
 * what causes Entra ID to mint an access token whose `aud` targets our API
 * (`api://<CLIENT_ID>`) rather than only Microsoft Graph. The Resource Server
 * validates this audience before honoring the bearer token.
 */
export const API_SCOPE = `api://${CLIENT_ID}/access_as_user`;

/**
 * The OIDC Authority: the tenant-scoped v2.0 endpoint MSAL uses for discovery
 * (/.well-known/openid-configuration), authorization, and token redemption.
 * Its value flows into the token's `iss` claim, which the backend's issuer
 * validator checks. The `/v2.0` suffix selects the Microsoft identity platform
 * v2 endpoints (the ones that emit the `roles` claim we authorize on).
 */
export const AUTHORITY = `https://login.microsoftonline.com/${TENANT_ID}/v2.0`;

/**
 * The exact browser origin registered as a redirect URI on the app
 * registration. Entra ID will only redirect the authorization response back to
 * a URI that matches a registered value, so this must equal the SPA's dev origin.
 */
const REDIRECT_URI = 'http://localhost:4200';

/**
 * The backend API base that our bearer token is allowed to be attached to. The
 * `/*` suffix lets MSAL's protected-resource matching cover every path beneath
 * `/api` (e.g. `/api/items`, `/api/items/1`). Requests to any other origin will
 * NOT receive a token, preventing our access token from leaking to third
 * parties.
 */
const PROTECTED_API_BASE = 'http://localhost:8080/api/*';

/**
 * The full set of scopes requested at interactive login / silent acquisition:
 *  - API_SCOPE          -> access token targeting our backend (delegated access).
 *  - 'openid', 'profile'-> OIDC scopes that yield an id_token with user claims.
 *  - 'offline_access'   -> asks Entra ID to issue a REFRESH token, which is what
 *                          enables silent renewal + rotation (R4.7). Without it,
 *                          MSAL could not refresh tokens in the background.
 */
const REQUESTED_SCOPES = [API_SCOPE, 'openid', 'profile', 'offline_access'];

// ─────────────────────────────────────────────────────────────────────────────
// MSAL_INSTANCE FACTORY
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Build the singleton `PublicClientApplication` that backs all MSAL operations.
 *
 * Provided via `{ provide: MSAL_INSTANCE, useFactory: msalInstanceFactory }` in
 * app.config.ts. The application ("public client") uses the Authorization Code
 * Flow with PKCE described in the header comment — this factory only supplies
 * configuration; MSAL performs the flow.
 *
 * Note on initialization: with @azure/msal-browser v5, `PublicClientApplication`
 * has an async `initialize()` step. Following the @azure/msal-angular v6 factory
 * convention, we simply construct and return the instance here; msal-angular's
 * `MsalService`/providers drive `initialize()` at the right time in the Angular
 * lifecycle, so the factory itself stays synchronous.
 *
 * DESIGN-vs-LIBRARY note (R1.7): the design's `auth` block includes
 * `navigateToLoginRequestUrl: true`, which msal-browser removed from
 * `BrowserAuthOptions` in v5. The literal below therefore uses a `Configuration`
 * cast to keep the design's declared intent while remaining type-safe on the
 * pinned v5 dependency; the actual "return to requested route" behavior is owned
 * by our custom guard + login layer (tasks 6.5 / 7.1). See the inline comments.
 *
 * @returns a configured `IPublicClientApplication` for DI.
 */
export function msalInstanceFactory(): IPublicClientApplication {
  return new PublicClientApplication({
    auth: {
      // Identifies this SPA to Entra ID on /authorize and /token requests.
      clientId: CLIENT_ID,
      // Tenant-scoped v2.0 Authority used for discovery, authorize, and token
      // redemption; its value becomes the token issuer (`iss`).
      authority: AUTHORITY,
      // Where Entra ID returns the authorization response. Must be a registered
      // redirect URI on the app registration (R1 redirect target).
      redirectUri: REDIRECT_URI,
      // "Return to originally requested route" (R1.7):
      // The design specifies `navigateToLoginRequestUrl: true`. That flag asked
      // MSAL to natively navigate the user back to the URL they first requested
      // after an interactive redirect login. We keep the design's intent below,
      // but see the DESIGN-vs-LIBRARY note above the factory for why the R1.7
      // behavior is ultimately owned by our own auth layer rather than this flag.
      navigateToLoginRequestUrl: true,
    },
    cache: {
      // Persist MSAL's token cache — including the rotating REFRESH token — in
      // localStorage. localStorage is scoped to THIS origin (scheme + host +
      // port), so the refresh token is only ever readable by our SPA's origin
      // (R4.7). This also lets a returning user stay signed in across full page
      // reloads/tab restarts, since the refresh token survives in storage and
      // `acquireTokenSilent` can mint a new access token without re-prompting.
      cacheLocation: 'localStorage',
    },
    // The `auth` block is typed with a cast because `navigateToLoginRequestUrl`
    // was part of MSAL's BrowserAuthOptions through msal-browser v4 but was
    // REMOVED in msal-browser v5 (the version pinned in package.json). We retain
    // the property to honor the design verbatim (R1.7) and keep the option
    // available should the dependency be pinned back to v4; the cast prevents a
    // v5 excess-property type error. Regardless of whether MSAL acts on the flag,
    // the R1.7 behavior is implemented explicitly and testably in our custom
    // layer: `authGuard` persists the intended URL to sessionStorage under
    // `postLoginRedirect` (task 6.5) and the login/redirect component restores
    // that route after the token exchange (task 7.1) — the more observable and
    // verifiable approach this reference favors.
  } as Configuration);
}

// ─────────────────────────────────────────────────────────────────────────────
// MSAL_GUARD_CONFIG FACTORY
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Configuration consumed by MSAL's `MsalGuard` (and by our own guard's use of
 * MSAL's interactive login) when an unauthenticated user hits a protected route.
 *
 * Provided via `{ provide: MSAL_GUARD_CONFIG, useFactory: msalGuardConfigFactory }`.
 *
 * @returns the guard configuration describing HOW and WITH WHAT SCOPES to log in.
 */
export function msalGuardConfigFactory(): MsalGuardConfiguration {
  return {
    // Use full-page REDIRECT interaction (not popup). Redirect is the robust
    // choice for the Authorization Code Flow in this reference: it avoids
    // popup-blocker issues and keeps a single, inspectable navigation for the
    // manual verification steps.
    interactionType: InteractionType.Redirect,
    authRequest: {
      // The scopes to request when triggering interactive sign-in (R1.3). Asking
      // for API_SCOPE here means the very first login already yields an access
      // token usable against the backend, and `offline_access` guarantees a
      // refresh token for later silent renewal.
      scopes: REQUESTED_SCOPES,
    },
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// MSAL_INTERCEPTOR_CONFIG FACTORY
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Configuration describing which outgoing HTTP endpoints are "protected" and
 * therefore require a bearer token, and which scopes that token must carry.
 *
 * Provided via `{ provide: MSAL_INTERCEPTOR_CONFIG, useFactory: msalInterceptorConfigFactory }`.
 *
 * The `protectedResourceMap` is a `Map` from a URL pattern to the scopes needed
 * for that resource. Requests whose URL matches a key get a token acquired for
 * the mapped scopes attached as `Authorization: Bearer <token>`; requests that
 * match no key are sent WITHOUT a token, which prevents our access token from
 * being leaked to unrelated origins.
 *
 * @returns the interceptor configuration mapping the backend API to API_SCOPE.
 */
export function msalInterceptorConfigFactory(): MsalInterceptorConfiguration {
  // Map the backend API base to the single scope that mints a token audienced
  // for our Resource Server (R1.3). Using `/api/*` covers every sub-path.
  const protectedResourceMap = new Map<string, Array<string> | null>([
    [PROTECTED_API_BASE, [API_SCOPE]],
  ]);

  return {
    // Match the guard: interactive fallbacks (e.g. consent) also use redirect.
    interactionType: InteractionType.Redirect,
    protectedResourceMap,
  };
}
