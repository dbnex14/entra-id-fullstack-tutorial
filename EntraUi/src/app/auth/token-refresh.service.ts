// auth/token-refresh.service.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// TOKEN REFRESH SERVICE — SINGLE-FLIGHT SILENT RENEWAL WITH BOUNDED RETRY
// ─────────────────────────────────────────────────────────────────────────────
//
// This service owns the *silent renewal* of the Access_Token. It is the one
// place in the SPA that turns MSAL's `acquireTokenSilent` primitive into an
// observable, single-flight refresh that the HTTP interceptor and route guards
// can lean on. Every consumer that needs a fresh token calls `refresh()`; the
// service guarantees that no matter how many callers arrive concurrently, at
// most ONE token-endpoint call is made (R4.4).
//
// ── HOW MSAL POWERS THE REFRESH (what happens under the hood) ───────────────
// `acquireTokenSilent({ scopes: [API_SCOPE] })` asks MSAL for a valid access
// token for our backend scope. MSAL will:
//   1. Return a cached, still-valid access token if one exists, OR
//   2. Use the stored REFRESH token to perform a `grant_type=refresh_token`
//      call against the Authority's /token endpoint.
// Entra ID ROTATES the refresh token on every such call: it returns a brand new
// refresh token and invalidates the previous one. MSAL persists that rotated
// refresh token internally in its configured cache (localStorage, scoped to our
// origin — see msal.config.ts). Our code NEVER sees, stores, or forwards a raw
// refresh token; we only observe the resulting access token + expiry (R4.2,
// R4.3, R4.7). If the refresh token itself is expired/revoked, MSAL rejects the
// silent call with an interaction-required error and we fall back to interactive
// login (R4.5).
//
// ── THE SINGLE-FLIGHT LATCH — WHY EXACTLY ONE TOKEN CALL ────────────────────
// `inFlight$` is a private, nullable Observable that acts as a latch:
//   - When it is `null`, no refresh is running. The first caller BUILDS the
//     refresh pipeline, stores it in `inFlight$`, and returns it.
//   - While it is non-null, a refresh is already running. Every subsequent
//     caller short-circuits and returns the SAME `inFlight$` observable instead
//     of starting another `acquireTokenSilent` call.
//   - `shareReplay(1)` multicasts the single underlying subscription and replays
//     the one emission to every queued caller, so they all observe the identical
//     access token from the identical token-endpoint call (rather than each
//     triggering its own network request).
//   - `finalize(() => this.inFlight$ = null)` RELEASES the latch when the shared
//     observable completes or errors, so the *next* refresh (after this one
//     settles) is allowed to start a fresh token call. Because `finalize` runs on
//     both success and error, the latch can never get stuck.
// Net effect: concurrent callers coalesce into one token-endpoint round-trip
// (R4.4), and the rotated refresh token from that single round-trip is the one
// MSAL persists.
//
// ── BOUNDED TRANSIENT RETRY (R4.6) ──────────────────────────────────────────
// Transient failures (e.g. a flaky network hiccup while reaching /token) are
// retried at most 3 times with an increasing delay before giving up. Retrying is
// deliberately restricted to *transient* errors: an interaction-required error
// (expired/revoked refresh token, consent needed, MFA) is NOT transient — no
// amount of retrying will fix it — so those errors bypass retry and go straight
// to the failure path (clear session + interactive login, R4.5).

import { Injectable, inject } from '@angular/core';
import { MsalService } from '@azure/msal-angular';
import {
  AuthenticationResult,
  InteractionRequiredAuthError,
  SilentRequest,
} from '@azure/msal-browser';
import {
  Observable,
  defer,
  finalize,
  from,
  map,
  retry,
  shareReplay,
  tap,
  throwError,
  timer,
} from 'rxjs';
import { catchError } from 'rxjs/operators';

import { API_SCOPE } from './msal.config';
import { AuthSessionStore } from './auth-session.store';

/**
 * Maximum number of RETRY attempts (in addition to the initial attempt) for a
 * transient silent-refresh failure (R4.6). With `count: 3` the token endpoint is
 * contacted at most 4 times total (1 initial + 3 retries) before we conclude the
 * refresh has failed and fall back to interactive login.
 */
const MAX_TRANSIENT_RETRIES = 3;

/**
 * Base backoff delay in milliseconds. The Nth retry waits `BASE_DELAY_MS * 2^N`
 * (500ms, 1000ms, 2000ms), giving an increasing/exponential backoff so we do not
 * hammer the token endpoint during a transient outage (R4.6).
 */
const BASE_DELAY_MS = 500;

@Injectable({ providedIn: 'root' })
export class TokenRefreshService {
  /**
   * MSAL facade (Angular 19 `inject()` convention). We use `msal.instance`
   * (the underlying IPublicClientApplication) to call the low-level
   * `acquireTokenSilent` / `acquireTokenRedirect` primitives directly, which is
   * what performs the refresh_token grant and PKCE redirect respectively.
   */
  private readonly msal = inject(MsalService);

  /**
   * The signal-based session store — the single source of truth for the current
   * access token, its absolute expiry, and roles. We update it on a successful
   * refresh and clear it on failure.
   */
  private readonly store = inject(AuthSessionStore);

  /**
   * The single-flight latch. `null` means "no refresh in progress". When a
   * refresh is running it holds the SHARED (shareReplay'd) observable so every
   * concurrent caller returns the same in-flight refresh instead of starting a
   * new token-endpoint call (R4.4). Emits the new raw access token string.
   */
  private inFlight$: Observable<string> | null = null;

  /**
   * Perform (or join) a single-flight silent refresh and emit the NEW access
   * token (R4.2, R4.3, R4.4, R4.6, R4.7).
   *
   * Behavior:
   *  - If a refresh is already running, return the SAME shared observable so the
   *    caller queues behind the one in-flight token call (R4.4).
   *  - Otherwise build the refresh pipeline: call `acquireTokenSilent` for
   *    API_SCOPE, retry transient failures up to 3 times with increasing delay
   *    (R4.6), update the store with the fresh token/expiry/roles on success
   *    (R4.3), clear the session and begin interactive login on unrecoverable
   *    failure (R4.5), release the latch via `finalize`, and `shareReplay(1)` so
   *    all queued callers observe the identical emission.
   *
   * @returns an `Observable<string>` emitting the freshly acquired access token.
   */
  refresh(): Observable<string> {
    // ── SINGLE-FLIGHT SHORT-CIRCUIT (R4.4) ─────────────────────────────────
    // A refresh is already running: hand back the shared observable. Every
    // queued caller shares the one underlying `acquireTokenSilent` subscription
    // (via shareReplay below) — no second token-endpoint call is made.
    if (this.inFlight$) {
      return this.inFlight$;
    }

    // The silent-token request. Requesting only API_SCOPE keeps this a targeted
    // renewal of the backend access token; MSAL uses the stored (rotating)
    // refresh token behind the scenes to satisfy it (R4.2, R4.7).
    const silentRequest: SilentRequest = { scopes: [API_SCOPE] };

    // ── BUILD THE SINGLE, SHARED REFRESH PIPELINE ──────────────────────────
    this.inFlight$ = defer(() =>
      // `defer` builds a FRESH observable on every (re)subscription. This is
      // essential for `retry` below: rxjs `retry` works by resubscribing, and
      // without `defer` we would be resubscribing to a single already-settled
      // Promise (which just replays its one outcome and never re-attempts). By
      // wrapping the call in `defer`, each retry actually re-invokes
      // `acquireTokenSilent`, producing a NEW token-endpoint attempt — which is
      // what makes the bounded transient retry (R4.6) real rather than a no-op.
      from(
        // `acquireTokenSilent` returns a Promise<AuthenticationResult>; `from`
        // lifts it into an Observable so we can compose retry/backoff/error paths.
        this.msal.instance.acquireTokenSilent(silentRequest),
      ),
    ).pipe(
      // ── BOUNDED, TRANSIENT-ONLY RETRY (R4.6) ─────────────────────────────
      // Retry at most 3 times with an increasing delay. `resetOnSuccess: true`
      // means the retry counter resets after any success, which matters for the
      // reset semantics of a long-lived shared pipeline. Crucially, the delay
      // callback re-throws interaction-required errors so they are NOT retried:
      // only genuinely transient failures get the backoff treatment.
      retry({
        count: MAX_TRANSIENT_RETRIES,
        resetOnSuccess: true,
        delay: (error, retryCount) => {
          // Interaction-required (expired/revoked refresh token, consent, MFA)
          // is not transient — surface it immediately so the catchError below
          // drives interactive re-authentication instead of pointless retries.
          if (this.isInteractionRequired(error)) {
            return throwError(() => error);
          }
          // Transient failure: back off with increasing delay
          // (500ms, 1000ms, 2000ms for retryCount 1, 2, 3).
          return timer(BASE_DELAY_MS * Math.pow(2, retryCount - 1));
        },
      }),

      // ── SUCCESS: PERSIST THE FRESH SESSION (R4.2, R4.3, R4.7) ─────────────
      tap((result: AuthenticationResult) => {
        // MSAL has already persisted the ROTATED refresh token internally in its
        // cache (localStorage, origin-scoped) as part of this call — we neither
        // see nor store it (R4.2, R4.7). We only record the OBSERVABLE outputs:
        // the new access token, its absolute expiry, and the roles claim.
        this.store.setSession(
          result.accessToken,
          // `expiresOn` is a Date; convert to an ABSOLUTE epoch-ms timestamp so
          // the store/interceptor can compare against Date.now() (R4.3). MSAL
          // always populates expiresOn for a successful token acquisition.
          result.expiresOn!.getTime(),
          // Extract roles from the ID token claims' `roles` array (matching the
          // design). Defaults to an empty array when the claim is absent.
          this.extractRoles(result),
        );
      }),

      // Emit the raw access token string to callers (the interceptor ignores the
      // value and re-reads from the store, but returning the token keeps this
      // service directly usable/testable).
      map((result: AuthenticationResult) => result.accessToken),

      // ── FAILURE: EXHAUSTED RETRIES OR INTERACTION REQUIRED (R4.5) ─────────
      catchError((err) => {
        // Either the transient retry budget was exhausted, or an
        // interaction-required error short-circuited the retry above. In both
        // cases silent renewal is impossible: discard the (now unusable) session
        // so no stale token is ever attached to a request, then kick off the
        // interactive Authorization Code + PKCE login to re-establish identity.
        this.store.clear();
        this.beginInteractiveLogin();
        return throwError(() => err);
      }),

      // ── RELEASE THE SINGLE-FLIGHT LATCH ──────────────────────────────────
      // Runs on completion AND error, so the latch never sticks. Once this
      // shared refresh settles, the next `refresh()` call is free to start a new
      // token-endpoint round-trip.
      finalize(() => {
        this.inFlight$ = null;
      }),

      // ── MULTICAST + REPLAY TO ALL QUEUED CALLERS (R4.4) ──────────────────
      // `shareReplay(1)` ensures the underlying `acquireTokenSilent` runs ONCE
      // and its single emission is replayed to every caller that queued behind
      // the latch, so they all observe the identical token from the identical
      // token call.
      shareReplay(1),
    );

    return this.inFlight$;
  }

  /**
   * Begin an interactive (full-page redirect) login to re-establish the session
   * (R4.5). Called when silent refresh is impossible (exhausted retries or an
   * interaction-required error). Requests the same scope set used at first login
   * so the resulting session includes an API access token and a refresh token
   * (via `offline_access`).
   *
   * `acquireTokenRedirect` navigates the browser to the Authority's /authorize
   * endpoint; MSAL handles the PKCE code_challenge/verifier and, on return,
   * exchanges the code for tokens. `redirectStartPage` records where to send the
   * user back to after the round-trip.
   */
  beginInteractiveLogin(): void {
    this.msal.instance.acquireTokenRedirect({
      // Full scope set so the interactive login yields an API access token
      // (API_SCOPE), user identity (openid/profile), and a refresh token for
      // future silent renewal (offline_access).
      scopes: [API_SCOPE, 'openid', 'profile', 'offline_access'],
      // Return the user to the current location after the redirect completes.
      redirectStartPage: window.location.href,
    });
  }

  /**
   * Extract the case-sensitive role values from a successful token result.
   *
   * The Microsoft identity platform v2 endpoint places app roles in the `roles`
   * claim. MSAL surfaces the decoded ID-token claims on `result.idTokenClaims`;
   * we read `roles` from there (matching the design). When the claim is absent
   * or not present, we default to an empty array so the store always holds a
   * well-formed `string[]`.
   */
  private extractRoles(result: AuthenticationResult): string[] {
    // `idTokenClaims` is typed loosely by MSAL; cast to read the optional
    // `roles` array without over-constraining the claim shape.
    const claims = result.idTokenClaims as { roles?: string[] } | undefined;
    return claims?.roles ?? [];
  }

  /**
   * Classify an error as "interaction required" (non-transient). MSAL throws
   * `InteractionRequiredAuthError` when silent acquisition cannot proceed
   * without user interaction — e.g. an expired/revoked refresh token
   * (`invalid_grant`), a consent prompt, or an MFA challenge. These must NOT be
   * retried; they go straight to the interactive-login failure path (R4.5).
   */
  private isInteractionRequired(error: unknown): boolean {
    return error instanceof InteractionRequiredAuthError;
  }
}
