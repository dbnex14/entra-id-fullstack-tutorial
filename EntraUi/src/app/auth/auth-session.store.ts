// auth/auth-session.store.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// SIGNAL-BASED SESSION STORE
// ─────────────────────────────────────────────────────────────────────────────
//
// This service is the single source of truth for the browser-side view of the
// authenticated session. It holds *derived* facts about the tokens that MSAL
// manages under the hood — specifically the current Access_Token, its absolute
// expiry, and the roles claim — so that the rest of the SPA (HTTP interceptor,
// route guards, components) can react to authentication state through Angular
// signals rather than by poking at MSAL's opaque cache directly.
//
// WHY A CUSTOM STORE ON TOP OF MSAL?
// MSAL already performs PKCE, refresh-token rotation, and same-origin token
// storage. But Requirements 3 and 4 demand *specific, observable* behaviors
// (a single reactive refresh on 401, a proactive refresh within a fixed
// threshold, explicit request queuing). To make those behaviors explicit and
// testable, this thin store exposes exactly the lifecycle-relevant fields and
// nothing more.
//
// TOKEN LIFECYCLE — HOW STATE MOVES THROUGH THIS STORE:
//   1. Unauthenticated  -> initial state; no token, no expiry, no roles.
//   2. Login / refresh   -> setSession(...) flips authenticated=true and records
//                           the freshly minted Access_Token, its absolute expiry,
//                           and the roles decoded from the id/access token claims.
//   3. Nearing expiry    -> needsProactiveRefresh(now) becomes true within the
//                           300s window, prompting the interceptor to refresh
//                           BEFORE the next protected request goes out.
//   4. Logout / failure  -> clear() returns the store to the unauthenticated
//                           empty state, discarding any partial session.
//
// INVARIANT: State is ONLY ever mutated through the methods on this store
// (setSession / clear). The backing signal (`_state`) is private, and consumers
// receive a readonly view (`state`) plus a `computed` flag (`isAuthenticated`).
// No component or service writes to session state directly — this keeps the
// token lifecycle transitions in one auditable place.

import { Injectable, computed, signal } from '@angular/core';

/**
 * The complete, immutable snapshot of the browser-side session.
 *
 * Every field mirrors a fact about the currently held Access_Token:
 *  - `authenticated`: true only while a usable token is present in the store.
 *  - `accessToken`:   the raw encoded JWT bearer string attached to API calls,
 *                     or null when unauthenticated.
 *  - `expiresAt`:     ABSOLUTE epoch-millisecond timestamp of token expiry
 *                     (NOT a relative "expires_in" duration). Storing an
 *                     absolute instant lets us compare against `Date.now()`
 *                     without tracking when the token was issued (R4.3).
 *  - `roles`:         the case-sensitive role values from the token's `roles`
 *                     claim, used by the client-side role guard (the server
 *                     remains authoritative).
 */
export interface SessionState {
  authenticated: boolean;
  accessToken: string | null;
  expiresAt: number | null; // absolute epoch ms (R4.3)
  roles: string[];
}

/**
 * Number of milliseconds before expiry at which we consider the token "stale
 * enough" to proactively refresh. 300_000 ms = 300 s = 5 minutes (R3.4, R4.1).
 * A generous window ensures the interceptor swaps in a fresh token well before
 * the Resource_Server would reject the current one.
 */
const PROACTIVE_REFRESH_WINDOW_MS = 300_000;

/**
 * The canonical "logged out" snapshot. Both the initial state and `clear()`
 * reset to this exact shape so there is one definition of "unauthenticated."
 */
const UNAUTHENTICATED_STATE: SessionState = {
  authenticated: false,
  accessToken: null,
  expiresAt: null,
  roles: [],
};

@Injectable({ providedIn: 'root' })
export class AuthSessionStore {
  /**
   * The private, writable signal that backs the store. It is deliberately NOT
   * exported: mutations must flow through setSession()/clear() so that the
   * token lifecycle stays centralized and observable.
   */
  private readonly _state = signal<SessionState>({ ...UNAUTHENTICATED_STATE });

  /**
   * Public, read-only view of the session for any consumer that needs to read
   * the raw fields (e.g. the interceptor reading `state().accessToken`).
   * Because it is `asReadonly()`, callers cannot mutate the session out-of-band.
   */
  readonly state = this._state.asReadonly();

  /**
   * Convenience computed signal for route guards / templates (R1.5). Recomputes
   * automatically whenever the underlying session state changes, so guarded
   * routes and role-driven UI react without manual subscriptions.
   */
  readonly isAuthenticated = computed(() => this._state().authenticated);

  /**
   * Record a freshly obtained session. Called after a successful login token
   * exchange (R1.5) and after every successful background refresh (R4.3).
   *
   * Flips the store into the authenticated state and captures the new token,
   * its ABSOLUTE expiry instant, and the roles claim in a single atomic set.
   *
   * @param token     the raw encoded Access_Token (bearer string) to attach to
   *                  subsequent protected requests.
   * @param expiresAt ABSOLUTE epoch-millisecond timestamp at which the token
   *                  expires (e.g. `result.expiresOn.getTime()` from MSAL).
   * @param roles     case-sensitive role values from the token's `roles` claim.
   */
  setSession(token: string, expiresAt: number, roles: string[]): void {
    // A single `set` keeps the transition atomic: authenticated flag, token,
    // expiry, and roles all update together, never leaving a half-populated
    // session visible to signal consumers.
    this._state.set({
      authenticated: true,
      accessToken: token,
      expiresAt,
      roles,
    });
  }

  /**
   * Return the store to the unauthenticated empty state (R3.5, R4.5).
   *
   * Invoked when a background refresh fails, when there is no valid
   * Refresh_Token, or on explicit logout. Discarding the token here guarantees
   * stale credentials are never attached to a subsequent request; the
   * interceptor/guards will then drive the user back through interactive login.
   */
  clear(): void {
    // Reset to a fresh copy of the canonical unauthenticated snapshot so no
    // residual token, expiry, or role data lingers in the store.
    this._state.set({ ...UNAUTHENTICATED_STATE });
  }

  /**
   * Decide whether the currently held token is close enough to expiry that we
   * should refresh it BEFORE issuing the next protected request (R3.4, R4.1).
   *
   * Returns true only when:
   *   - a session is authenticated, AND
   *   - an absolute expiry is recorded, AND
   *   - that expiry is within PROACTIVE_REFRESH_WINDOW_MS (300 s) of `now`.
   *
   * The interceptor consults this on every outbound request so the proactive
   * refresh path fires exactly within the 5-minute window and not before.
   *
   * @param now the current client time as epoch milliseconds (passed in
   *            explicitly so callers and tests control the clock deterministically).
   */
  needsProactiveRefresh(now: number): boolean {
    const snapshot = this._state();
    const exp = snapshot.expiresAt;

    return (
      snapshot.authenticated &&
      exp != null &&
      exp - now <= PROACTIVE_REFRESH_WINDOW_MS // within 300s of expiry
    );
  }
}
