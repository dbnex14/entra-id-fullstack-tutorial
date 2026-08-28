// auth/auth-token.interceptor.spec.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// TASK 8.3 — HTTP AUTH INTERCEPTOR EXAMPLE TESTS (Jasmine + HttpTestingController)
// ─────────────────────────────────────────────────────────────────────────────
//
// Feature: entra-oauth-fullstack
// Task 8.3: Interceptor unit tests
// Validates: Requirements 3.3, 3.4, 3.5, 3.6, 4.1, 4.4, 4.6
// Design: Testing Strategy > Automated tests > Frontend interceptor tests
//
// WHAT THIS FILE PROVES
// ---------------------
// `authTokenInterceptor` is the seam between the browser token lifecycle and
// every outbound HTTP call. These are EXAMPLE-based (not property-based) tests —
// concrete, hand-authored scenarios driven through Angular's real HTTP stack via
// `provideHttpClient(withInterceptors([authTokenInterceptor]))` +
// `provideHttpClientTesting`. `HttpTestingController` lets us assert exactly
// which requests were dispatched, inspect their headers, and script server
// responses (200s, 401 challenges) deterministically.
//
// The scenarios mirror the task's checklist:
//   1. ATTACH (R-attach)   — a bearer header is added to /api requests.
//   2. SCOPING             — non-/api requests get NO bearer header.
//   3. PROACTIVE (R3.4/4.1)— when the stored token is within the 300s expiry
//                            window, the interceptor refreshes BEFORE sending and
//                            dispatches the request with the fresh token.
//   4. REACTIVE (R3.3/3.6) — a 401 whose WWW-Authenticate indicates
//                            invalid_token/expired triggers EXACTLY ONE refresh
//                            and EXACTLY ONE retry of the original request.
//   5. SINGLE-FLIGHT (R4.4)— N concurrent requests that all need a refresh queue
//                            behind ONE in-flight refresh (one token call).
//   6. FAILURE (R3.5/4.5)  — when refresh ultimately fails (its internal bounded
//                            retry, R4.6, is exhausted), the interceptor does NOT
//                            retry again; the session is cleared and the error is
//                            surfaced to the caller.
//
// ── TEST STRATEGY: REAL STORE, MOCK REFRESH SERVICE ─────────────────────────
// The interceptor `inject()`s two collaborators:
//   • AuthSessionStore      — the signal source of truth (token/expiry/roles).
//   • TokenRefreshService   — the single-flight silent renewal.
//
// We use the REAL `AuthSessionStore` so we exercise the true
// `needsProactiveRefresh()` math and observe real `setSession()`/`clear()`
// effects on `state()`. We MOCK `TokenRefreshService` because its production
// behavior (MSAL calls, the internal 3-retry backoff of R4.6, interactive
// redirect) is validated exhaustively in token-refresh.service.spec.ts (8.1) and
// token-refresh.retry.spec.ts (8.2). Here we only need to control WHAT `refresh()`
// does (succeed / fail, and how many underlying token calls it makes) and COUNT
// how the interceptor drives it — which a lightweight mock does cleanly and
// deterministically, without dragging MSAL or fakeAsync timers into the picture.
//
// The mock faithfully models the two production side effects the interceptor
// relies on:
//   • On SUCCESS  -> it calls `store.setSession(...)` with the fresh token (so a
//     subsequent request reads the NEW token from the store, exactly as prod).
//   • On FAILURE  -> it calls `store.clear()` and would begin interactive login
//     (prod: clear() + beginInteractiveLogin()); we assert the store is cleared.
// It also models single-flight: all subscribers within one "in-flight" window
// share ONE underlying token call (via shareReplay), so we can assert R4.4.

import { TestBed } from '@angular/core/testing';
import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import {
  Observable,
  Subject,
  defer,
  map,
  of,
  shareReplay,
  throwError,
} from 'rxjs';

import { authTokenInterceptor } from './auth-token.interceptor';
import { AuthSessionStore } from './auth-session.store';
import { TokenRefreshService } from './token-refresh.service';

// ─────────────────────────────────────────────────────────────────────────────
// CONSTANTS — mirror the interceptor + store under test.
// ─────────────────────────────────────────────────────────────────────────────

/** Absolute API prefix that the interceptor scopes bearer attachment to. */
const API_URL = 'http://localhost:8080/api/items';

/** A non-API URL (e.g. a third-party call) that must NOT receive a bearer. */
const NON_API_URL = 'https://login.microsoftonline.com/common/oauth2/v2.0/token';

/** The proactive-refresh window from AuthSessionStore (300_000 ms = 5 min). */
const PROACTIVE_WINDOW_MS = 300_000;

/**
 * Build a FUTURE absolute expiry that sits OUTSIDE the proactive window, so
 * `needsProactiveRefresh()` returns false and no proactive refresh fires. One
 * hour ahead is comfortably beyond the 5-minute threshold.
 */
function farFutureExpiry(): number {
  return Date.now() + 3_600_000;
}

/**
 * Build a FUTURE absolute expiry that sits INSIDE the proactive window (token
 * still valid, but "stale enough"), so `needsProactiveRefresh()` returns true.
 * We use half the window (150s) so it is unambiguously within threshold.
 */
function nearExpiry(): number {
  return Date.now() + PROACTIVE_WINDOW_MS / 2;
}

/**
 * A configurable test double for `TokenRefreshService`. It records how many
 * times a caller subscribes to `refresh()` and how many UNDERLYING token calls
 * actually happen (single-flight collapses many subscribers into one), and it
 * applies the same store side effects the real service would.
 */
class MockTokenRefreshService {
  /** How many times `refresh()` was invoked (one per interceptor call). */
  refreshInvocations = 0;

  /**
   * How many UNDERLYING token endpoint calls happened. With single-flight, many
   * concurrent `refresh()` subscriptions share ONE underlying call — this counter
   * increments only when the shared source is actually subscribed/executed.
   */
  underlyingTokenCalls = 0;

  /** Set true to make `refresh()` fail (models exhausted R4.6 retry budget). */
  shouldFail = false;

  /** The access token a successful refresh installs into the store. */
  refreshedToken = 'refreshed-access-token';

  /**
   * When true, `refresh()` does NOT settle synchronously; instead it waits for a
   * manual {@link releaseRefresh} call. This lets a test keep the single-flight
   * window OPEN while it fires several concurrent requests, so they genuinely
   * queue behind ONE in-flight refresh (mirroring the real async token call).
   */
  gated = false;

  /** The gate a gated `refresh()` awaits; `releaseRefresh()` completes it. */
  private readonly gate$ = new Subject<void>();

  /** The currently shared in-flight observable (single-flight latch). */
  private inFlight$: Observable<string> | null = null;

  constructor(private readonly store: AuthSessionStore) {}

  /**
   * Release a gated refresh: apply the success side effect (persist the fresh
   * session) and let all queued callers emit the refreshed token. Only relevant
   * when {@link gated} is true.
   */
  releaseRefresh(): void {
    // Persist the fresh session on release, exactly as the underlying body would
    // on the synchronous (ungated) path.
    this.store.setSession(this.refreshedToken, farFutureExpiry(), ['Viewer']);
    this.gate$.next();
    this.gate$.complete();
  }

  /**
   * Model the production single-flight `refresh()`:
   *  - the FIRST caller builds a shared observable that performs ONE underlying
   *    token call and applies the store side effect on success/failure,
   *  - concurrent callers within the same in-flight window join that SAME
   *    observable (so only one underlying call happens, R4.4),
   *  - the latch is released when the shared observable settles.
   *
   * `defer` ensures the underlying work runs lazily on first subscription; the
   * interceptor subscribes synchronously so concurrent callers coalesce.
   */
  refresh(): Observable<string> {
    this.refreshInvocations += 1;

    // Single-flight short-circuit: hand back the shared in-flight observable.
    if (this.inFlight$) {
      return this.inFlight$;
    }

    this.inFlight$ = defer(() => {
      // This body runs ONCE per in-flight window — it is the "underlying token
      // call". Counting it here proves single-flight coalescing (R4.4).
      this.underlyingTokenCalls += 1;

      if (this.shouldFail) {
        // Model the real failure side effect: the service clears the session
        // (and would begin interactive login) before surfacing the error
        // (R3.5/R4.5). Ticking through R4.6's internal retries is out of scope
        // here — the mock represents the TERMINAL outcome only.
        this.store.clear();
        return throwError(() => new Error('silent refresh failed (retries exhausted)'));
      }

      // GATED MODE: keep the in-flight window open until releaseRefresh() is
      // called. The store side effect is applied by releaseRefresh(), so the
      // near-expiry token stays in place while concurrent callers queue.
      if (this.gated) {
        return this.gate$.pipe(map(() => this.refreshedToken));
      }

      // SYNCHRONOUS SUCCESS: persist the fresh session so a subsequent request
      // reads the NEW token from the store (R4.3), and emit immediately.
      this.store.setSession(this.refreshedToken, farFutureExpiry(), ['Viewer']);
      return of(this.refreshedToken);
    }).pipe(
      // Release the latch once settled so a later refresh can start fresh, and
      // multicast the single underlying emission to all queued callers.
      shareReplay(1),
    );

    return this.inFlight$;
  }
}

/**
 * Configure a fresh TestBed with the REAL store, the mock refresh service, and
 * the interceptor wired into Angular's HTTP testing stack. Returns the handles
 * the tests need.
 */
function setup(): {
  http: HttpClient;
  httpMock: HttpTestingController;
  store: AuthSessionStore;
  refresher: MockTokenRefreshService;
} {
  TestBed.resetTestingModule();

  // The mock refresh service needs the SAME store instance the interceptor will
  // read, so we build the store first and hand it to the mock via a factory.
  const store = new AuthSessionStore();
  const refresher = new MockTokenRefreshService(store);

  TestBed.configureTestingModule({
    providers: [
      // Register the functional interceptor exactly as app.config.ts does.
      provideHttpClient(withInterceptors([authTokenInterceptor])),
      provideHttpClientTesting(),
      // Supply our controlled collaborators for the interceptor's inject() calls.
      { provide: AuthSessionStore, useValue: store },
      { provide: TokenRefreshService, useValue: refresher },
    ],
  });

  return {
    http: TestBed.inject(HttpClient),
    httpMock: TestBed.inject(HttpTestingController),
    store,
    refresher,
  };
}

describe('authTokenInterceptor (task 8.3)', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let store: AuthSessionStore;
  let refresher: MockTokenRefreshService;

  beforeEach(() => {
    ({ http, httpMock, store, refresher } = setup());
  });

  afterEach(() => {
    // Fail the test if any request was dispatched but left unanswered — this
    // catches accidental extra retries (e.g. a second reactive refresh/retry).
    httpMock.verify();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // 1. ATTACH — bearer header on /api requests.
  // ───────────────────────────────────────────────────────────────────────────
  it('attaches Authorization: Bearer <token> to /api requests', () => {
    // Seed a valid, non-stale session so no proactive refresh fires.
    store.setSession('current-access-token', farFutureExpiry(), ['Viewer']);

    http.get(API_URL).subscribe();

    const req = httpMock.expectOne(API_URL);
    // The interceptor must clone the request with the store's current token.
    expect(req.request.headers.get('Authorization')).toBe(
      'Bearer current-access-token',
    );
    // No refresh should have happened on the happy, non-stale path.
    expect(refresher.refreshInvocations).toBe(0);

    req.flush({ ok: true });
  });

  // ───────────────────────────────────────────────────────────────────────────
  // 2. SCOPING — non-/api requests get NO bearer header.
  // ───────────────────────────────────────────────────────────────────────────
  it('does NOT attach a bearer header to non-/api requests', () => {
    // Even with an authenticated session, foreign origins must not receive our
    // API token (it would leak cross-origin and could break MSAL's own calls).
    store.setSession('current-access-token', farFutureExpiry(), ['Viewer']);

    http.get(NON_API_URL).subscribe();

    const req = httpMock.expectOne(NON_API_URL);
    expect(req.request.headers.has('Authorization')).toBe(false);
    // Non-API requests bypass the whole store/refresh machinery.
    expect(refresher.refreshInvocations).toBe(0);

    req.flush({ ok: true });
  });

  // ───────────────────────────────────────────────────────────────────────────
  // 3. PROACTIVE REFRESH (R3.4, R4.1) — refresh fires BEFORE the request when the
  //    stored token is within the 300s expiry window; the request then carries
  //    the freshly-minted token.
  // ───────────────────────────────────────────────────────────────────────────
  it('proactively refreshes before sending when the token is within the expiry window, and sends the fresh token', () => {
    // Seed a near-expiry (but still valid) session -> needsProactiveRefresh true.
    store.setSession('stale-but-valid-token', nearExpiry(), ['Viewer']);
    expect(store.needsProactiveRefresh(Date.now())).toBe(true);

    http.get(API_URL).subscribe();

    // Exactly one proactive refresh (one underlying token call) fired first.
    expect(refresher.refreshInvocations).toBe(1);
    expect(refresher.underlyingTokenCalls).toBe(1);

    // The request that reaches the server carries the REFRESHED token, proving
    // the refresh completed BEFORE dispatch and the store was re-read after.
    const req = httpMock.expectOne(API_URL);
    expect(req.request.headers.get('Authorization')).toBe(
      `Bearer ${refresher.refreshedToken}`,
    );

    req.flush({ ok: true });
  });

  // ───────────────────────────────────────────────────────────────────────────
  // 4. REACTIVE REFRESH (R3.3, R3.6) — a 401 with an invalid_token/expired
  //    challenge triggers EXACTLY ONE refresh and EXACTLY ONE retry.
  // ───────────────────────────────────────────────────────────────────────────
  it('performs exactly one reactive refresh and one retry on a 401 with an invalid_token challenge', () => {
    // Non-stale session so no proactive refresh; the 401 is what drives refresh.
    store.setSession('expired-on-server-token', farFutureExpiry(), ['Viewer']);

    let succeeded = false;
    http.get(API_URL).subscribe({ next: () => (succeeded = true) });

    // First dispatch carries the original token and gets a 401 challenge.
    const first = httpMock.expectOne(API_URL);
    expect(first.request.headers.get('Authorization')).toBe(
      'Bearer expired-on-server-token',
    );
    first.flush(
      { error: 'invalid_token' },
      {
        status: 401,
        statusText: 'Unauthorized',
        // Spring's BearerTokenAuthenticationEntryPoint emits this challenge shape.
        headers: {
          'WWW-Authenticate':
            'Bearer error="invalid_token", error_description="The token expired"',
        },
      },
    );

    // The interceptor refreshed exactly once (R3.3) ...
    expect(refresher.refreshInvocations).toBe(1);
    expect(refresher.underlyingTokenCalls).toBe(1);

    // ... and retried the ORIGINAL request EXACTLY ONCE with the NEW token (R3.6).
    const retry = httpMock.expectOne(API_URL);
    expect(retry.request.headers.get('Authorization')).toBe(
      `Bearer ${refresher.refreshedToken}`,
    );
    retry.flush({ ok: true });

    expect(succeeded).toBe(true);
    // afterEach's httpMock.verify() proves there was no SECOND retry.
  });

  // ───────────────────────────────────────────────────────────────────────────
  // 4b. REACTIVE NON-TRIGGER — a 401 WITHOUT an expired/invalid_token challenge
  //     is surfaced unchanged (no refresh, no retry). Guards the isExpiredChallenge
  //     gate so we don't refresh on unrelated 401s.
  // ───────────────────────────────────────────────────────────────────────────
  it('does NOT refresh or retry on a 401 that lacks an expired/invalid_token challenge', () => {
    store.setSession('current-access-token', farFutureExpiry(), ['Viewer']);

    let errorStatus: number | undefined;
    http.get(API_URL).subscribe({
      error: (e: HttpErrorResponse) => (errorStatus = e.status),
    });

    const req = httpMock.expectOne(API_URL);
    // A bare "Bearer" challenge (e.g. missing token) is not refreshable.
    req.flush(
      { error: 'unauthorized' },
      {
        status: 401,
        statusText: 'Unauthorized',
        headers: { 'WWW-Authenticate': 'Bearer' },
      },
    );

    // No refresh, no retry — the original 401 propagates to the caller.
    expect(refresher.refreshInvocations).toBe(0);
    expect(errorStatus).toBe(401);
  });

  // ───────────────────────────────────────────────────────────────────────────
  // 5. SINGLE-FLIGHT (R4.4) — N concurrent requests that all need a proactive
  //    refresh queue behind ONE in-flight refresh (one underlying token call).
  // ───────────────────────────────────────────────────────────────────────────
  it('coalesces concurrent proactive refreshes into a single in-flight token call', () => {
    // Near-expiry session -> every request wants a proactive refresh.
    store.setSession('stale-but-valid-token', nearExpiry(), ['Viewer']);

    // GATE the refresh so the in-flight window stays OPEN while we fire all N
    // requests. Without the gate a synchronous refresh would complete after the
    // first request (updating the store to a far-future expiry), so requests 2
    // and 3 would no longer see needsProactiveRefresh() true — masking the very
    // coalescing we want to prove. The gate mirrors the real async token call,
    // which is still pending when the later requests arrive.
    refresher.gated = true;

    // Fire three concurrent requests synchronously. Each interceptor call invokes
    // refresh(), but they must all join ONE in-flight refresh (single-flight).
    const N = 3;
    for (let i = 0; i < N; i++) {
      http.get(API_URL).subscribe();
    }

    // refresh() was invoked once per request (three interceptor calls) ...
    expect(refresher.refreshInvocations).toBe(N);
    // ... but only ONE underlying token call actually happened (R4.4): the two
    // later callers hit the single-flight short-circuit and joined the first.
    expect(refresher.underlyingTokenCalls).toBe(1);

    // While the gate is closed, NO request has been dispatched yet — all three
    // are still waiting on the one in-flight refresh to settle.
    httpMock.expectNone(API_URL);

    // Release the shared refresh: the store gets the fresh token and all three
    // queued callers proceed together.
    refresher.releaseRefresh();

    // All N requests are now dispatched, each carrying the single fresh token
    // from the ONE token call they shared.
    const requests = httpMock.match(API_URL);
    expect(requests).toHaveSize(N);
    for (const req of requests) {
      expect(req.request.headers.get('Authorization')).toBe(
        `Bearer ${refresher.refreshedToken}`,
      );
      req.flush({ ok: true });
    }
  });

  // ───────────────────────────────────────────────────────────────────────────
  // 6. REFRESH FAILURE (R3.5, R4.5, R4.6) — when refresh ultimately fails (its
  //    internal bounded retry budget is exhausted), the interceptor does NOT
  //    retry again; the session is cleared and the error surfaces to the caller.
  // ───────────────────────────────────────────────────────────────────────────
  it('clears the session and surfaces the error (no further retry) when a reactive refresh fails', () => {
    refresher.shouldFail = true; // model exhausted R4.6 retries / interaction-required
    store.setSession('expired-on-server-token', farFutureExpiry(), ['Viewer']);

    let errored = false;
    http.get(API_URL).subscribe({
      next: () => fail('request should not have succeeded'),
      error: () => (errored = true),
    });

    // First dispatch -> 401 expired challenge triggers the (failing) refresh.
    const first = httpMock.expectOne(API_URL);
    first.flush(
      { error: 'invalid_token' },
      {
        status: 401,
        statusText: 'Unauthorized',
        headers: {
          'WWW-Authenticate':
            'Bearer error="invalid_token", error_description="expired"',
        },
      },
    );

    // Refresh was attempted once and failed; the error propagated to the caller.
    expect(refresher.refreshInvocations).toBe(1);
    expect(errored).toBe(true);

    // The failing refresh cleared the session (R3.5/R4.5) so no stale token
    // lingers, and — crucially — NO retry request was dispatched (verify() in
    // afterEach would fail if one had been left outstanding).
    expect(store.state().authenticated).toBe(false);
    expect(store.state().accessToken).toBeNull();
  });
});
