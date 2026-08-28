// auth/token-refresh.service.spec.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// PROPERTY 5 — REFRESH IS SINGLE-FLIGHT AND ROTATES THE STORED TOKEN
// ─────────────────────────────────────────────────────────────────────────────
//
// Feature: entra-oauth-fullstack
// Property 5: Refresh is single-flight and rotates the stored token
// Validates: Requirements 4.2, 4.4
// Design: Correctness Properties > Property 5; Testing Strategy > Frontend
//
// WHAT THIS FILE PROVES
// ---------------------
// `TokenRefreshService.refresh()` coalesces any number `N >= 1` of *concurrent*
// callers into a SINGLE token-endpoint round-trip (the single-flight latch,
// R4.4), and every one of those N callers observes the SAME resulting access
// token (R4.2/R4.4). Because Entra ID rotates the refresh token on every
// `grant_type=refresh_token` exchange, we also assert that a refresh produces a
// freshly-rotated refresh-token value — modeled here through the mock's internal
// state, since our production code never sees or stores the raw refresh token
// (MSAL owns it in its localStorage cache — see msal.config.ts / R4.7).
//
// WHY A PROPERTY TEST (fast-check) AND NOT JUST EXAMPLES
// ------------------------------------------------------
// "Single-flight" is a UNIVERSAL claim: it must hold for EVERY N, not just for
// N = 2 or N = 5. A property test drives the same invariant across 100 generated
// values of N (and generated token strings), which is exactly what the design's
// Property 5 requires ("For any number N >= 1 ... exactly one token-endpoint
// call ... all N callers observe the same resulting access token").
//
// HOW THE SINGLE-FLIGHT LATCH IS EXERCISED (the crucial timing detail)
// --------------------------------------------------------------------
// `refresh()` calls `this.msal.instance.acquireTokenSilent(...)`, which returns
// a Promise. `from(promise)` does NOT resolve synchronously — it resolves on a
// later microtask. Therefore, when we call `service.refresh()` N times in a
// tight synchronous loop, all N subscriptions happen BEFORE the first
// acquireTokenSilent Promise resolves. The first call installs the shared
// `inFlight$` observable (shareReplay(1)); the remaining N-1 calls hit the
// `if (this.inFlight$) return this.inFlight$;` short-circuit and join the same
// in-flight observable. Net result: `acquireTokenSilent` is invoked exactly
// once. We assert precisely that.
//
// TEST WIRING (Angular 19 + Karma/Jasmine + fast-check)
// -----------------------------------------------------
// - A MOCK `MsalService` whose `.instance.acquireTokenSilent` is a Jasmine spy
//   that (a) counts calls and (b) returns a Promise resolving to a partial
//   `AuthenticationResult` carrying the generated access token, a future
//   `expiresOn` Date, and `idTokenClaims.roles`.
// - The mock models MSAL's INTERNAL refresh-token rotation: it holds a private
//   "stored refresh token" value and, on each `acquireTokenSilent` call, rotates
//   it to a brand-new value. Our production code doesn't store the refresh token
//   (MSAL does), so we assert against the mock's modeled store — proving that a
//   token call rotates the stored refresh token exactly as Entra ID would.
// - A REAL `AuthSessionStore` is used (no mock) so we verify the true store
//   mutation: after refresh, `store.state().accessToken` equals the new token.
// - A fresh service + mocks + store are constructed INSIDE the fast-check
//   predicate for every run (via `TestBed.resetTestingModule()` + reconfigure),
//   so each run starts with a clean, un-triggered single-flight latch. This is
//   essential: `inFlight$` is per-instance state, and reusing an instance across
//   runs would leak a settled latch into the next run.
//
// ASYNC HANDLING
// --------------
// We use `fc.asyncProperty` and `await` the N concurrent subscriptions. Each
// `refresh()` Observable is converted to a Promise via `firstValueFrom`, and all
// N promises are awaited together with `Promise.all`. Because all N `refresh()`
// calls are made synchronously (before any microtask resolves), they genuinely
// race against the same in-flight latch. `await fc.assert(fc.asyncProperty(...),
// { numRuns: 100 })` runs the whole scenario 100 times per the design.

import { TestBed } from '@angular/core/testing';
import { MsalService } from '@azure/msal-angular';
import { AuthenticationResult } from '@azure/msal-browser';
import { firstValueFrom } from 'rxjs';
import * as fc from 'fast-check';

import { TokenRefreshService } from './token-refresh.service';
import { AuthSessionStore } from './auth-session.store';
import { API_SCOPE } from './msal.config';

/**
 * A tiny mock of MSAL that models the two behaviors Property 5 cares about:
 *   1. Counting how many times the token endpoint (acquireTokenSilent) is hit.
 *   2. Rotating an internal "stored refresh token" on each token call, mirroring
 *      Entra ID's refresh-token rotation (which MSAL persists internally).
 *
 * We expose the `acquireTokenSilent` spy and a `storedRefreshToken` getter so the
 * property can assert the single-call and rotation invariants.
 */
interface RefreshHarness {
  /** The service under test, freshly constructed for this run. */
  service: TokenRefreshService;
  /** The REAL session store, so we verify the true post-refresh state. */
  store: AuthSessionStore;
  /** Jasmine spy standing in for MSAL's token-endpoint call. */
  acquireTokenSilentSpy: jasmine.Spy;
  /** Reads the mock's modeled "stored refresh token" (rotated by MSAL). */
  storedRefreshToken: () => string;
  /** How many times the mock rotated its stored refresh token. */
  rotationCount: () => number;
}

/**
 * Build a fresh harness (service + real store + mock MSAL) for a single property
 * run. Reconfiguring TestBed per run guarantees a clean single-flight latch and
 * an empty session store every time.
 *
 * @param newAccessToken the access token value the mock's token endpoint returns.
 * @returns the wired-up {@link RefreshHarness}.
 */
function makeRefreshHarness(newAccessToken: string): RefreshHarness {
  // Reset any prior TestBed configuration so each run is fully isolated: a new
  // injector means a new TokenRefreshService instance with a null inFlight$ latch.
  TestBed.resetTestingModule();

  // ── Mock MSAL internal state ──────────────────────────────────────────────
  // `modeledStoredRefreshToken` stands in for the refresh token MSAL keeps in its
  // localStorage cache. Entra ID rotates it on every refresh_token exchange, so
  // the mock generates a NEW value on each acquireTokenSilent call. We seed it
  // with an initial value so we can prove it changes after a refresh.
  let rotations = 0;
  let modeledStoredRefreshToken = 'initial-refresh-token';

  // The spy that stands in for the real token-endpoint call. Returning a Promise
  // (not a synchronous value) is what makes the single-flight latch observable:
  // all N synchronous subscribers queue behind this pending microtask.
  const acquireTokenSilentSpy = jasmine
    .createSpy('acquireTokenSilent')
    .and.callFake((request: { scopes: string[] }) => {
      // Sanity: the service must request exactly our backend API scope.
      expect(request.scopes).toEqual([API_SCOPE]);

      // Model MSAL/Entra ID refresh-token ROTATION: every token call replaces
      // the stored refresh token with a brand-new value and invalidates the old
      // one. Because single-flight guarantees ONE call, this rotation must occur
      // exactly once per refresh regardless of how many callers queued.
      rotations += 1;
      modeledStoredRefreshToken = `rotated-refresh-token-${rotations}`;

      // Build a partial AuthenticationResult carrying the outputs our service
      // reads: accessToken, a FUTURE expiresOn Date, and idTokenClaims.roles.
      const result = {
        accessToken: newAccessToken,
        // One hour in the future so the resulting session is not already stale.
        expiresOn: new Date(Date.now() + 3_600_000),
        // Roles live on idTokenClaims per the Microsoft identity platform v2.
        idTokenClaims: { roles: ['Viewer'] },
      } as unknown as AuthenticationResult;

      // Resolve asynchronously (Promise), so all synchronous subscribers share
      // the single in-flight observable before this settles.
      return Promise.resolve(result);
    });

  // A minimal MsalService stand-in: the service only touches `.instance`, and on
  // the instance only `.acquireTokenSilent` (and `.acquireTokenRedirect`, which
  // must never be called on the happy path — spy so we could detect misuse).
  const msalMock = {
    instance: {
      acquireTokenSilent: acquireTokenSilentSpy,
      acquireTokenRedirect: jasmine.createSpy('acquireTokenRedirect'),
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
    acquireTokenSilentSpy,
    storedRefreshToken: () => modeledStoredRefreshToken,
    rotationCount: () => rotations,
  };
}

describe('TokenRefreshService — Property 5: single-flight refresh + refresh-token rotation', () => {
  // Property 5 (fast-check, numRuns: 100). See file header for the full rationale.
  //
  // Validates: Requirements 4.2, 4.4
  it('coalesces N concurrent refreshes into one token call, shares one token, and rotates the stored refresh token', async () => {
    await fc.assert(
      fc.asyncProperty(
        // N concurrent callers. The design allows N >= 1; we use 2..20 to keep
        // runs fast while still exercising genuine concurrency across many N.
        fc.integer({ min: 2, max: 20 }),
        // The new access token the token endpoint returns this run. minLength
        // avoids empty-string ambiguity and mirrors the design's generator.
        fc.string({ minLength: 10 }),
        async (n, newAccessToken) => {
          // Fresh service/store/mock for THIS run: clean single-flight latch.
          const {
            service,
            store,
            acquireTokenSilentSpy,
            storedRefreshToken,
            rotationCount,
          } = makeRefreshHarness(newAccessToken);

          // Capture the pre-refresh stored refresh token so we can prove rotation.
          const refreshTokenBefore = storedRefreshToken();

          // ── FIRE N CONCURRENT refresh() CALLS ────────────────────────────
          // All N subscriptions happen synchronously here, BEFORE the first
          // acquireTokenSilent Promise resolves on a later microtask. The first
          // call installs the shared inFlight$ latch; the rest join it. Converting
          // each Observable to a Promise via firstValueFrom lets us await them.
          const callerPromises = Array.from({ length: n }, () =>
            firstValueFrom(service.refresh()),
          );

          // Await all callers. They all resolve from the single shared emission.
          const observedTokens = await Promise.all(callerPromises);

          // ── ASSERT: EXACTLY ONE TOKEN-ENDPOINT CALL (single-flight, R4.4) ─
          // Regardless of N, the latch must coalesce every caller into one call.
          expect(acquireTokenSilentSpy).toHaveBeenCalledTimes(1);

          // ── ASSERT: ALL CALLERS OBSERVE THE SAME ACCESS TOKEN (R4.2/R4.4) ─
          observedTokens.forEach((token) =>
            expect(token).toBe(newAccessToken),
          );

          // ── ASSERT: THE STORE HOLDS THAT SAME ACCESS TOKEN (R4.3 effect) ──
          // The single successful refresh must have updated the real session
          // store to the freshly acquired token — proving all callers and the
          // store converge on one value.
          expect(store.state().accessToken).toBe(newAccessToken);

          // ── ASSERT: THE STORED REFRESH TOKEN ROTATED EXACTLY ONCE (R4.2) ──
          // The single token call rotates MSAL's stored refresh token to a NEW
          // value, replacing the previous one. Because single-flight guarantees
          // one call, rotation happens exactly once for N concurrent callers.
          expect(rotationCount()).toBe(1);
          expect(storedRefreshToken()).not.toBe(refreshTokenBefore);
          expect(storedRefreshToken()).toBe('rotated-refresh-token-1');

          // Returning true keeps fast-check's predicate contract explicit; any
          // failed expectation above already throws and fails the run.
          return true;
        },
      ),
      // Minimum 100 generated cases per the design's property-testing mandate.
      { numRuns: 100 },
    );
  });
});
