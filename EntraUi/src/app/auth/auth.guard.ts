// auth/auth.guard.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// ROUTE GUARDS — authGuard (authentication) + roleGuard (client-side role gate)
// ─────────────────────────────────────────────────────────────────────────────
//
// This file supplies the two functional route guards that stand between the
// browser's router and the protected feature routes (see app.routes.ts, task
// 7.3). Both are written in modern Angular 19 style: they are plain functions
// typed as `CanActivateFn` and resolve their dependencies with `inject()`
// rather than through constructor DI on a guard class. Angular runs them inside
// an injection context, so `inject()` is valid at call time.
//
// There are TWO distinct concerns here, deliberately kept separate:
//
//   1. authGuard   — "is there a session at all?" It admits authenticated users
//                    and, for everyone else, kicks off interactive login while
//                    remembering where the user was trying to go.
//
//   2. roleGuard   — "does the session carry a role this route requires?" It is
//                    a guard FACTORY: you call `roleGuard(['Admin'])` in the
//                    route config and it returns a `CanActivateFn` closed over
//                    the required roles.
//
// ── SECURITY BOUNDARY DISCLAIMER (read before trusting roleGuard) ────────────
// The role check performed by `roleGuard` is a CLIENT-SIDE UX GATE ONLY. It
// exists so the SPA can avoid routing a user into, say, the Admin screen when
// their token plainly lacks the `Admin` role — a nicer experience than letting
// them load a page whose every API call will 403. It is *defense in depth*, not
// *the* security boundary. The authoritative authorization decision is made by
// the backend Resource Server, which re-validates the JWT and enforces
// `@PreAuthorize("hasRole('Admin')")` on the write endpoints (see
// ItemController / SecurityConfig). A user can trivially edit client memory or
// craft requests by hand; none of that matters, because the server independently
// checks the `roles` claim on every protected call. NEVER treat this guard as
// sufficient protection for a resource.

import { inject } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  RouterStateSnapshot,
} from '@angular/router';

import { AuthSessionStore } from './auth-session.store';
import { TokenRefreshService } from './token-refresh.service';

/**
 * sessionStorage key under which we stash the URL the user originally tried to
 * reach before we bounced them to interactive login.
 *
 * WHY THIS EXISTS (R1.7 — "return to originally requested route"):
 * MSAL historically offered `navigateToLoginRequestUrl: true`, which asked the
 * library to natively navigate the user back to the pre-login URL after the
 * redirect round-trip completed. That option was removed from msal-browser v5
 * (the pinned dependency — see the DESIGN-vs-LIBRARY note in msal.config.ts), so
 * this reference OWNS that behavior explicitly instead of relying on the flag.
 * The contract is a two-part handshake:
 *   - HERE (authGuard): before starting login, persist `state.url` under this
 *     key so the intended destination survives the full-page redirect to Entra
 *     ID and back.
 *   - LATER (login/redirect component, task 7.1): after the token exchange
 *     succeeds, read this key and navigate to the saved route (falling back to
 *     the default landing route if absent), then clear it.
 * sessionStorage is the right store: it is scoped to this tab/origin and is
 * automatically discarded when the tab closes, so a stale redirect target never
 * lingers into a future browsing session.
 */
const POST_LOGIN_REDIRECT_KEY = 'postLoginRedirect';

/**
 * authGuard — admits authenticated users; otherwise starts interactive login.
 *
 * Wired into protected routes (e.g. `dashboard`, `admin`) via
 * `canActivate: [authGuard]`. Angular invokes it with the target route snapshot
 * and the router state snapshot; we only need `state.url` (the fully resolved
 * URL the user was navigating to) to implement the return-to-route behavior.
 *
 * @param _route the activated route snapshot (unused — we authorize on session
 *               presence, not on any route data, so it is intentionally ignored).
 * @param state  the router state snapshot; `state.url` is the absolute URL the
 *               user attempted to reach and is what we persist for R1.7.
 * @returns `true` to allow activation when a session exists; otherwise `false`
 *          after kicking off the login redirect (the navigation is cancelled and
 *          the browser is redirected to Entra ID).
 */
export const authGuard: CanActivateFn = (
  _route: ActivatedRouteSnapshot,
  state: RouterStateSnapshot,
): boolean => {
  // Resolve dependencies from Angular's injection context. `inject()` is valid
  // here because the router runs guards within an injection context.
  const store = inject(AuthSessionStore);
  const refresher = inject(TokenRefreshService);

  // BRANCH 1 — authenticated: a usable session exists in the signal store, so
  // let the navigation proceed unchanged. `isAuthenticated()` is a computed
  // signal driven purely by the store's session state (R1.5), so this reflects
  // the live token lifecycle without any manual subscription.
  if (store.isAuthenticated()) {
    return true;
  }

  // BRANCH 2 — unauthenticated: we must send the user through interactive login.
  //
  // 2a. Persist the originally requested route so we can return the user there
  //     after login completes (R1.1, R1.7). This MUST happen BEFORE we trigger
  //     the redirect, because `beginInteractiveLogin()` navigates the whole page
  //     away to Entra ID — any state not already written to storage would be
  //     lost across that full-page redirect.
  sessionStorage.setItem(POST_LOGIN_REDIRECT_KEY, state.url);

  // 2b. Initiate the OAuth2 Authorization Code Flow with PKCE via MSAL (R1.1).
  //     This performs a full-page redirect to the Authority's /authorize
  //     endpoint; MSAL generates the PKCE code_verifier/code_challenge and the
  //     state/nonce under the hood (see msal.config.ts). Control leaves the SPA
  //     at this point and returns later via the redirect callback.
  refresher.beginInteractiveLogin();

  // 2c. Deny activation of the requested route for THIS navigation. The current
  //     in-app navigation is cancelled; the user is now mid-redirect to Entra
  //     ID and will re-enter the app through the login/redirect handler.
  return false;
};

/**
 * roleGuard — a guard FACTORY producing a client-side role gate.
 *
 * Usage in the route config:
 *   { path: 'admin', canActivate: [authGuard, roleGuard(['Admin'])], ... }
 *
 * Because this returns a `CanActivateFn` closed over `required`, you can create
 * differently-scoped guards for different routes (e.g. `roleGuard(['Admin'])`,
 * `roleGuard(['Viewer', 'Admin'])`) without duplicating logic. It is typically
 * paired AFTER `authGuard`, so by the time it runs a session is expected to
 * exist; if somehow it does not, `roles` is simply the empty array and the gate
 * denies access.
 *
 * REMINDER: this is only a UX gate (defense in depth). The server is the real
 * authorization boundary — see the security disclaimer at the top of this file.
 *
 * @param required the list of role names, ANY ONE of which grants access. The
 *                 gate opens if the session's roles intersect this list.
 * @returns a `CanActivateFn` that returns `true` when the session's `roles`
 *          include at least one of the `required` roles, otherwise `false`.
 */
export function roleGuard(required: string[]): CanActivateFn {
  // The returned function is what Angular actually invokes per navigation. It is
  // itself a `CanActivateFn`, so it may inject its own dependencies at call time.
  return (): boolean => {
    const store = inject(AuthSessionStore);

    // Read the case-sensitive role values captured from the token's `roles`
    // claim at login/refresh time (mutated only through the store, R1.5). We
    // compare against the raw values exactly as Entra ID issued them.
    const roles = store.state().roles;

    // Open the gate if the session carries AT LEAST ONE of the required roles.
    // `some(...)` implements the "any of" semantics: a route requiring
    // ['Admin'] admits an Admin; a route requiring ['Viewer', 'Admin'] admits
    // either. Returning false cancels the navigation (the router will not
    // activate the route). NOTE: even when this returns true, every backend call
    // the route makes is still independently authorized server-side.
    return required.some((role) => roles.includes(role));
  };
}
