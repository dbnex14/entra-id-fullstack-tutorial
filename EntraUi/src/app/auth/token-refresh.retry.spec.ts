// auth/token-refresh.retry.spec.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// PROPERTY 6 — TRANSIENT REFRESH FAILURES ARE RETRIED AT MOST THREE TIMES,
//              THEN RE-AUTH
// ─────────────────────────────────────────────────────────────────────────────
//
// Feature: entra-oauth-fullstack
// Property 6: Transient refresh failures are retried at most three times, then re-auth
// Validates: Requirements 4.6
// Design: Correctness Properties > Property 6; Testing Strategy > Frontend
//
// WHAT THIS FILE PROVES
// ---------------------
// `TokenRefreshService.refresh()` treats a transient silent-refresh failure
// (network hiccup, a generic ServerError-like error reaching the /token
// endpoint) as retryable, but only up to a BOUNDED budget: the initial attempt
// plus AT MOST 3 retries => AT MOST 4 total `acquireTokenSilent` calls (R4.6).
// From that single invariant three concrete behaviors follow, all asserted here:
//
//   1. Bound (always):        total attempts <= 4, for ANY number of leading
//                             transient failures.
//   2. Recovery within budget: if the number of leading transient failures
//                             `k <= 3`, the (k+1)-th attempt succeeds, the
//                             session is set (store.setSession), and NO re-auth
//                             is triggered (acquireTokenRedirect never called).
//   3. Exhausted budget:      if `k >= 4`, every one of the 4 attempts fails,
//                             the refresh ERRORS, the session is cleared
//                             (store.clear), and interactive login is started
//                             EXACTLY ONCE (acquireTokenRedirect called once).
//
// Plus a companion example test proves the "interaction-required is NOT
// transient" clause of R4.6: an `InteractionRequiredAuthError` on the FIRST
// attempt is not retried (attempts == 1) and drives immediate re-auth.
//
// WHY A PROPERTY TEST (fast-check) AND NOT JUST EXAMPLES
// ------------------------------------------------------
// "At most 3 retries" is a UNIVERSAL claim over the number of leading transient
// failures. The design's Property 6 generates `k` in `[0, 6]` — a range that
// straddles BOTH sides of the retry budget (k in 0..3 recovers; k in 4..6
// exhausts) — and asserts the invariant for every generated `k`. We reproduce
// that generator exactly (`fc.integer({ min: 0, max: 6 })`) and run 100 cases.
//
// ── THE HARD PART: VIRTUALIZING THE BACKOFF TIMERS ──────────────────────────
// `refresh()`'s retry uses `delay: (err, retryCount) => timer(BASE * 2^(n-1))`,
// i.e. rxjs `timer` waits REAL wall-clock time: 500ms, then 1000ms, then 2000ms
// across the three retries. If we let that run in real time the suite would
// sleep ~3.5s PER generated case × 100 cases. Unacceptable.
//
// SOLUTION (per the task's preferred approach): run each generated case inside
// its own Angular `fakeAsync` zone and drive the virtual clock with `tick()`.
// `fakeAsync` patches `setTimeout`/`setInterval` (which rxjs `timer` uses) so we
// can advance time instantly. Because composing fast-check's ASYNC mode with
// `fakeAsync` is fragile (both want to own the microtask/macrotask queue), we
// deliberately use fast-check in SYNCHRONOUS mode: `fc.property(...)` with a
// synchronous predicate, and inside that predicate we invoke a self-executing
// `fakeAsync(() => { ... })()`. The predicate returns synchronously once the
// fakeAsync zone has fully drained, so `fc.assert(..., { numRuns: 100 })` stays
// synchronous and deterministic.
//
// ── WHY A SINGLE `tick(3500)` IS FRAGILE, AND WHAT WE DO INSTEAD ─────────────
// The retry chain has a strict ordering dependency between MICROTASKS (the
// `acquireTokenSilent` Promise settling) and MACROTASKS (the backoff `timer`):
//
//   attempt N runs -> its Promise REJECTS (microtask) -> rxjs `retry` sees the
//   error and only THEN schedules the backoff `timer` for retry N (macrotask) ->
//   that timer fires -> `defer` re-invokes `acquireTokenSilent` -> attempt N+1's
//   Promise settles (microtask) -> ... and so on.
//
// So the timer for retry N does not even EXIST in the fake scheduler until
// attempt N's rejection microtask has been drained. A single bulk `tick(3500)`
// happens to work only because Zone flushes microtasks between timer callbacks,
// but it couples all four attempts into one opaque advance and is easy to break
// if the backoff shape changes. The robust, self-documenting pattern is to
// interleave the flush and the tick EXPLICITLY, one retry at a time:
//
//   flushMicrotasks();          // settle attempt 1's promise (schedules backoff 1 on failure)
//   tick(500);  flushMicrotasks(); // backoff 1 fires -> attempt 2 runs -> its promise settles
//   tick(1000); flushMicrotasks(); // backoff 2 fires -> attempt 3 runs -> its promise settles
//   tick(2000); flushMicrotasks(); // backoff 3 fires -> attempt 4 runs -> its promise settles
//
// After the initial flush settles attempt 1, each `tick(BACKOFF_MS[i])` advances
// EXACTLY that retry's specific delay so its timer fires, and the trailing
// `flushMicrotasks()` drains the resulting attempt's promise (and, on failure,
// lets `retry` schedule the NEXT backoff before we tick again). On the recovery
// path the later timers are simply never scheduled — the corresponding tick is a
// harmless no-op — so the same fixed drive sequence reliably handles k = 0..6.
//
// ── HOW THE MOCK MODELS TRANSIENT vs SUCCESS ────────────────────────────────
// The mock `acquireTokenSilent` is a Jasmine spy with a call counter. For a run
// parameterized by `k` (leading transient failures):
//   - calls 1..k          -> reject with a generic transient Error (NOT an
//                            InteractionRequiredAuthError, so the service's
//                            delay callback DOES retry them),
//   - call  k+1 (if made) -> resolve a partial AuthenticationResult (success).
// With `k <= 3` the success arrives within budget; with `k >= 4` the 4th attempt
// (the cap) is still a transient failure, so the whole refresh errors.
//
// ── ISOLATION PER RUN ───────────────────────────────────────────────────────
// A fresh TestBed (real `AuthSessionStore`, real `TokenRefreshService`, mock
// `MsalService`) is built per generated run — mirroring task 8.1 — so each run
// starts with a clean single-flight latch and an empty session store.

import { TestBed, fakeAsync, tick, flushMicrotasks } from '@angular/core/testing';
import { MsalService } from '@azure/msal-angular';
import {
  AuthenticationResult,
  InteractionRequiredAuthError,
} from '@azure/msal-browser';
import * as fc from 'fast-check';

import { TokenRefreshService } from './token-refresh.service';
import { AuthSessionStore } from './auth-session.store';
import { API_SCOPE } from './msal.config';

// ─────────────────────────────────────────────────────────────────────────────
// TIMING CONSTANTS — mirror token-refresh.service.ts
// ─────────────────────────────────────────────────────────────────────────────
//
// The service backs off `BASE_DELAY_MS * 2^(retryCount-1)` per retry, with a
// budget of 3 retries. We drive the fake clock by advancing EACH retry's
// specific delay in turn (see the header discussion of the interleaved
// flush/tick pattern), so we model the per-retry delays explicitly rather than
// collapsing them into a single bulk advance.
const BASE_DELAY_MS = 500;
const MAX_TRANSIENT_RETRIES = 3;

/**
 * The per-retry backoff delays, in the order the service schedules them:
 *   retry 1 -> BASE * 2^0 =  500ms
 *   retry 2 -> BASE * 2^1 = 1000ms
 *   retry 3 -> BASE * 2^2 = 2000ms
 * We `tick()` these one at a time so each retry's timer fires in isolation.
 */
const BACKOFF_DELAYS_MS: readonly number[] = Array.from(
  { length: MAX_TRANSIENT_RETRIES },
  (_unused, retryIndex) => BASE_DELAY_MS * Math.pow(2, retryIndex),
); // [500, 1000, 2000]

/** The hard cap on total token-endpoint calls: 1 initial + 3 retries. */
const MAX_TOTAL_ATTEMPTS = 1 + MAX_TRANSIENT_RETRIES; // 4

/**
 * Drive the virtual clock through the FULL retry chain from inside a `fakeAsync`
 * zone. This encapsulates the fragile microtask/macrotask interleaving so both
 * the property and the companion example share one correct, well-documented
 * time-advance strategy.
 *
 * Sequence:
 *   1. `flushMicrotasks()` settles the INITIAL attempt's `acquireTokenSilent`
 *      Promise. On failure, rxjs `retry` then schedules backoff timer #1.
 *   2. For each retry i, `tick(BACKOFF_DELAYS_MS[i])` fires backoff timer #i,
 *      which (via `defer`) re-invokes `acquireTokenSilent`; the trailing
 *      `flushMicrotasks()` settles that attempt's Promise and, on failure, lets
 *      `retry` schedule the NEXT backoff timer before we advance again.
 *
 * On the recovery path the later backoff timers are never scheduled, so ticking
 * their delays is a harmless no-op — which is exactly why one fixed drive
 * sequence works for every k in 0..6.
 *
 * IMPORTANT: this function MUST be called from within a `fakeAsync` zone (it uses
 * `tick`/`flushMicrotasks`), otherwise those helpers throw.
 */
function driveRetryChain(): void {
  // Settle attempt 1's promise (and schedule backoff 1 if it failed).
  flushMicrotasks();

  // Advance each retry's specific backoff, then drain the resulting attempt.
  for (const delayMs of BACKOFF_DELAYS_MS) {
    tick(delayMs); // fire this retry's backoff timer (no-op if never scheduled)
    flushMicrotasks(); // settle the retried attempt's promise (schedules next backoff on failure)
  }
}

/**
 * The wiring returned by {@link makeRetryHarness}, exposing the service under
 * test, the real session store, and the two spies whose call patterns encode
 * Property 6's invariants.
 */
interface RetryHarness {
  /** The service under test, freshly constructed for this run. */
  service: TokenRefreshService;
  /** The REAL session store, so we verify true clear()/setSession() effects. */
  store: AuthSessionStore;
  /**
   * Jasmine spy standing in for MSAL's token-endpoint call. Its call count IS
   * the "number of attempts" the property bounds at <= 4.
   */
  attemptSpy: jasmine.Spy;
  /**
   * Spy on `acquireTokenRedirect` — the low-level primitive
   * `beginInteractiveLogin()` calls. Being called means "re-auth started".
   */
  beginLoginSpy: jasmine.Spy;
}

/**
 * Options controlling how the mock token endpoint behaves for one run.
 */
interface RetryHarnessOptions {
  /**
   * Number of LEADING transient failures. Calls 1..k reject transiently; the
   * (k+1)-th call (if the service gets that far within budget) resolves.
   */
  failuresBeforeSuccess: number;
  /**
   * When true, the FIRST call rejects with an `InteractionRequiredAuthError`
   * instead of a transient error. Used by the companion example test to prove
   * interaction-required errors are NOT retried (R4.6 "not transient").
   */
  interactionRequiredFirst?: boolean;
}

/**
 * Build a fresh harness (service + real store + mock MSAL) for a single run.
 *
 * Reconfiguring TestBed per run guarantees a clean single-flight latch (a new
 * `TokenRefreshService` instance with `inFlight$ === null`) and an empty session
 * store every time — exactly the isolation strategy used by task 8.1.
 *
 * @param opts how the mock token endpoint should fail/succeed this run.
 * @returns the wired-up {@link RetryHarness}.
 */
function makeRetryHarness(opts: RetryHarnessOptions): RetryHarness {
  const { failuresBeforeSuccess, interactionRequiredFirst = false } = opts;

  // Fully isolate this run: a new injector => a new service with a null latch.
  TestBed.resetTestingModule();

  // Counts how many times the token endpoint was contacted. This is the value
  // Property 6 bounds at <= 4 and pins to exactly 4 when the budget is exhausted.
  let callCount = 0;

  // The spy standing in for MSAL's `acquireTokenSilent`. It returns a Promise on
  // every call (matching real MSAL), so the service's `from(promise)` pipeline
  // resolves on microtasks and the rxjs `retry` backoff timers are exercised.
  const attemptSpy = jasmine
    .createSpy('acquireTokenSilent')
    .and.callFake((request: { scopes: string[] }) => {
      // The service must always request exactly our backend API scope.
      expect(request.scopes).toEqual([API_SCOPE]);

      callCount += 1;
      const thisCall = callCount; // 1-based index of THIS attempt.

      // COMPANION-TEST BRANCH: an interaction-required error on the very first
      // attempt. The service's retry `delay` callback re-throws these (they are
      // NOT transient), so it must bypass retry entirely => exactly one attempt.
      if (interactionRequiredFirst && thisCall === 1) {
        return Promise.reject(
          new InteractionRequiredAuthError(
            'interaction_required',
            'The refresh token is expired or revoked; interactive sign-in required.',
          ),
        );
      }

      // TRANSIENT-FAILURE BRANCH: the first `failuresBeforeSuccess` calls reject
      // with a GENERIC error. Because it is NOT an InteractionRequiredAuthError,
      // the service classifies it as transient and schedules a backoff retry.
      if (thisCall <= failuresBeforeSuccess) {
        // A ServerError-like transient failure (e.g. HTTP 503 at /token). Using a
        // plain Error is sufficient: the service only special-cases
        // InteractionRequiredAuthError, treating everything else as transient.
        return Promise.reject(
          new Error(`transient_server_error (attempt ${thisCall})`),
        );
      }

      // SUCCESS BRANCH: reached only when the service still has budget after the
      // leading transient failures (i.e. failuresBeforeSuccess <= 3). Resolve a
      // partial AuthenticationResult carrying the outputs the service reads:
      // a fresh access token, a FUTURE absolute expiry, and roles claim.
      const result = {
        accessToken: `access-token-after-${failuresBeforeSuccess}-failures`,
        // One hour ahead so the resulting session is not already stale.
        expiresOn: new Date(Date.now() + 3_600_000),
        // Roles live on idTokenClaims per the Microsoft identity platform v2.
        idTokenClaims: { roles: ['Viewer'] },
      } as unknown as AuthenticationResult;

      return Promise.resolve(result);
    });

  // Spy on the interactive-login primitive. `beginInteractiveLogin()` calls
  // `this.msal.instance.acquireTokenRedirect(...)`, so a call here means the
  // service decided silent renewal was impossible and started re-auth.
  const beginLoginSpy = jasmine.createSpy('acquireTokenRedirect');

  // Minimal MsalService stand-in: the service only touches `.instance` and, on
  // it, `acquireTokenSilent` / `acquireTokenRedirect`.
  const msalMock = {
    instance: {
      acquireTokenSilent: attemptSpy,
      acquireTokenRedirect: beginLoginSpy,
    },
  };

  // Configure the injector: mock MsalService, REAL store + service under test.
  TestBed.configureTestingModule({
    providers: [
      TokenRefreshService,
      AuthSessionStore,
      { provide: MsalService, useValue: msalMock },
    ],
  });

  return {
    service: TestBed.inject(TokenRefreshService),
    store: TestBed.inject(AuthSessionStore),
    attemptSpy,
    beginLoginSpy,
  };
}

describe('TokenRefreshService — Property 6: bounded transient retry then re-auth', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // PROPERTY 6 (fast-check, numRuns: 100) — the universal invariant.
  //
  // Validates: Requirements 4.6
  // Design: Correctness Properties > Property 6
  // ───────────────────────────────────────────────────────────────────────────
  it('retries transient failures at most 3 times, recovers within budget without re-auth, and on exhaustion clears the session and re-authenticates exactly once', () => {
    fc.assert(
      fc.property(
        // Number of LEADING transient failures. The range [0, 6] straddles the
        // retry budget: 0..3 recover within budget; 4..6 exhaust it. This mirrors
        // the design's Property 6 generator exactly.
        fc.integer({ min: 0, max: 6 }),
        (failuresBeforeSuccess) => {
          // Each generated case runs inside its OWN fakeAsync zone so the rxjs
          // backoff `timer`s are virtualized and driven by `tick()` instead of
          // sleeping real seconds. The self-invoking `fakeAsync(() => {...})()`
          // returns synchronously once the zone has fully drained, keeping the
          // fast-check predicate synchronous.
          fakeAsync(() => {
            const { service, store, attemptSpy, beginLoginSpy } =
              makeRetryHarness({ failuresBeforeSuccess });

            // Track terminal outcome of the refresh observable.
            let succeeded = false;
            let errored = false;

            service.refresh().subscribe({
              next: () => (succeeded = true),
              error: () => (errored = true),
            });

            // Drive the full retry chain: settle the initial attempt, then fire
            // each retry's backoff and settle its attempt, one at a time. This
            // reliably advances up to all 4 attempts regardless of k (see
            // driveRetryChain's docs for the microtask/macrotask ordering).
            driveRetryChain();

            // ── INVARIANT (ALWAYS): AT MOST 4 TOTAL ATTEMPTS (R4.6) ──────────
            // 1 initial + at most 3 retries, no matter how many failures occur.
            expect(attemptSpy.calls.count()).toBeLessThanOrEqual(
              MAX_TOTAL_ATTEMPTS,
            );

            if (failuresBeforeSuccess <= MAX_TRANSIENT_RETRIES) {
              // ── RECOVERY WITHIN BUDGET ────────────────────────────────────
              // The (k+1)-th attempt succeeds, so exactly k+1 attempts were made
              // (still <= 4), the refresh SUCCEEDS, the session is set, and NO
              // re-auth is triggered.
              expect(attemptSpy.calls.count()).toBe(failuresBeforeSuccess + 1);
              expect(succeeded).toBe(true);
              expect(errored).toBe(false);

              // The successful refresh persisted the fresh session (R4.3 effect):
              // store now authenticated with the acquired token.
              expect(store.state().authenticated).toBe(true);
              expect(store.state().accessToken).toBe(
                `access-token-after-${failuresBeforeSuccess}-failures`,
              );

              // No re-authentication on the recovery path.
              expect(beginLoginSpy).not.toHaveBeenCalled();
            } else {
              // ── EXHAUSTED BUDGET (k >= 4) ─────────────────────────────────
              // All 4 attempts (1 initial + 3 retries) failed transiently: the
              // token endpoint is contacted EXACTLY 4 times (the cap), the
              // refresh ERRORS, the session is CLEARED, and interactive login is
              // started EXACTLY ONCE (R4.6).
              expect(attemptSpy.calls.count()).toBe(MAX_TOTAL_ATTEMPTS);
              expect(errored).toBe(true);
              expect(succeeded).toBe(false);

              // Session cleared so no stale token can be attached to a request.
              expect(store.state().authenticated).toBe(false);
              expect(store.state().accessToken).toBeNull();

              // Re-auth started exactly once (not zero, not twice).
              expect(beginLoginSpy).toHaveBeenCalledTimes(1);
            }
          })();

          // Predicate is truthy when no expectation above threw for this case.
          return true;
        },
      ),
      // Minimum 100 generated cases per the design's property-testing mandate.
      { numRuns: 100 },
    );
  });

  // ───────────────────────────────────────────────────────────────────────────
  // COMPANION EXAMPLE — interaction-required is NOT transient (R4.6).
  //
  // Proves the other half of R4.6: an InteractionRequiredAuthError on the FIRST
  // attempt is NOT retried (the service's retry `delay` callback re-throws it),
  // so the token endpoint is contacted EXACTLY ONCE, the session is cleared, and
  // interactive login is started EXACTLY ONCE — immediately, with no backoff.
  //
  // Validates: Requirements 4.6
  // ───────────────────────────────────────────────────────────────────────────
  it('does not retry an interaction-required error and re-authenticates immediately (attempts == 1)', fakeAsync(() => {
    const { service, store, attemptSpy, beginLoginSpy } = makeRetryHarness({
      // No transient failures modeled; the interaction-required branch fires on
      // the first call regardless of this value.
      failuresBeforeSuccess: 0,
      interactionRequiredFirst: true,
    });

    let errored = false;
    let succeeded = false;

    service.refresh().subscribe({
      next: () => (succeeded = true),
      error: () => (errored = true),
    });

    // Drive the full retry chain. Because the first attempt fails with an
    // interaction-required error (which the service re-throws rather than
    // retrying), NO backoff timer is ever scheduled — so every subsequent
    // tick(...) inside driveRetryChain is a no-op. Advancing the full budget
    // anyway PROVES no retry occurred: had the error been (incorrectly) treated
    // as transient, firing the backoff timers would have produced extra attempts.
    driveRetryChain();

    // EXACTLY ONE attempt: interaction-required bypassed the retry budget.
    expect(attemptSpy.calls.count()).toBe(1);

    // The refresh errored (surfaced to the caller) and did not succeed.
    expect(errored).toBe(true);
    expect(succeeded).toBe(false);

    // Session cleared and interactive login started exactly once.
    expect(store.state().authenticated).toBe(false);
    expect(beginLoginSpy).toHaveBeenCalledTimes(1);
  }));
});
