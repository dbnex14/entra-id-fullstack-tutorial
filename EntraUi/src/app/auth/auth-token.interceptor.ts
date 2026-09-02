// auth/auth-token.interceptor.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// HTTP AUTH INTERCEPTOR — BEARER ATTACH + PROACTIVE/REACTIVE SILENT REFRESH
// ─────────────────────────────────────────────────────────────────────────────
//
// This functional `HttpInterceptorFn` is the seam where the browser-side token
// lifecycle meets every outbound API call. It has three jobs, all driven off the
// `AuthSessionStore` (current token + expiry) and the `TokenRefreshService`
// (single-flight silent renewal):
//
//   1. ATTACH — clone the outbound request adding `Authorization: Bearer <token>`
//      so the stateless Resource_Server can validate the JWT and derive roles.
//
//   2. PROACTIVE REFRESH (R3.4, R4.1) — BEFORE the request is sent, if the stored
//      token is within the 300s expiry window (`store.needsProactiveRefresh`),
//      refresh first, then send the request with the freshly-minted token. This
//      keeps a near-stale token from ever reaching the server in the common case.
//
//   3. REACTIVE REFRESH (R3.3, R3.6) — if the server nonetheless rejects the
//      request with a 401 whose `WWW-Authenticate` challenge indicates the token
//      is expired/invalid, perform AT MOST ONE background refresh and retry the
//      original request EXACTLY ONCE with the new token. On refresh failure the
//      TokenRefreshService already clears the session and starts interactive
//      login, so we simply propagate the error without a second retry.
//
// ── SCOPING: WHICH REQUESTS GET A TOKEN ─────────────────────────────────────
// This custom interceptor attaches tokens ONLY to our backend API calls
// (`http://localhost:8080/entra-backend/...`). Requests to any other host — most notably
// MSAL's own calls to the Entra ID token/authorize endpoints — must NOT carry
// our API bearer token (doing so could leak the token cross-origin and would
// break MSAL's own auth). Non-API requests pass straight through untouched. This
// mirrors the design's URL-scoped approach for the thin custom interceptor
// layered over MSAL's primitives.
//
// ── THE SINGLE-RETRY GUARANTEE (no infinite loops) ──────────────────────────
// The reactive path is entered from a `catchError` on the ORIGINAL request only.
// After a successful refresh we retry the request exactly once via a fresh
// `next(...)` call that is NOT wrapped in the same reactive `catchError`. So even
// if that retry also returns 401, it will not trigger another refresh+retry — it
// just surfaces to the caller. Combined with `TokenRefreshService` being
// single-flight (concurrent 401s coalesce into one token call), this guarantees
// at most one reactive refresh and one retry per request (R3.3, R3.6).

import {
  HttpErrorResponse,
  HttpHandlerFn,
  HttpInterceptorFn,
  HttpRequest,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, of, switchMap, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { AuthSessionStore } from './auth-session.store';
import { TokenRefreshService } from './token-refresh.service';

/**
 * URL prefix identifying calls to our own Resource_Server API. Only requests
 * whose URL starts with this prefix get a bearer token attached and participate
 * in the proactive/reactive refresh dance. Everything else (MSAL's token/
 * authorize calls, static assets, third-party URLs) is passed through as-is so
 * our API token never leaks to another origin.
 */
const API_URL_PREFIX = 'http://localhost:8080/entra-backend';

/**
 * Functional HTTP interceptor (Angular 19). Registered via
 * `provideHttpClient(withInterceptors([authTokenInterceptor]))` in app.config
 * (task 7.4). Uses `inject()` to grab the session store and refresh service —
 * the modern standalone DI convention for functional interceptors.
 */
export const authTokenInterceptor: HttpInterceptorFn = (
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<any> => {
  // ── PASS-THROUGH FOR NON-API REQUESTS ──────────────────────────────────────
  // Only decorate calls to our backend API. This prevents our API bearer token
  // from being attached to MSAL's own /token and /authorize requests (which live
  // on a different origin) and to any non-API traffic.
  if (!isApiRequest(req)) {
    return next(req);
  }

  const store = inject(AuthSessionStore);
  const refresher = inject(TokenRefreshService);

  // ── PROACTIVE PATH (R3.4, R4.1) ────────────────────────────────────────────
  // Consult the store BEFORE sending. If the current token is within the 300s
  // expiry window, refresh first so we send the request with a fresh token. The
  // refresh is single-flight, so concurrent proactive refreshes coalesce into
  // one token-endpoint call. If a refresh is NOT needed, `of(void 0)` is a no-op
  // that lets the request proceed immediately with the current token.
  const preflight$: Observable<unknown> = store.needsProactiveRefresh(Date.now())
    ? refresher.refresh() // shared, single-flight; queues concurrent callers (R4.4)
    : of(void 0);

  return preflight$.pipe(
    // After any proactive refresh settles, read the (possibly refreshed) token
    // straight from the store and attach it, then dispatch the request.
    switchMap(() => next(withBearer(req, store.state().accessToken))),

    // ── REACTIVE PATH (R3.3, R3.6) ────────────────────────────────────────────
    catchError((err: HttpErrorResponse) => {
      // Only react to a 401 whose challenge specifically indicates an expired /
      // invalid token. Other errors (403 authorization failures, 5xx, network
      // errors, or 401s without an expired challenge) are surfaced unchanged.
      if (err.status === 401 && isExpiredChallenge(err)) {
        // Perform AT MOST ONE background refresh (R3.3). Because refresh() is
        // single-flight, multiple concurrent 401s share one token call.
        return refresher.refresh().pipe(
          // Retry the ORIGINAL request EXACTLY ONCE with the new token (R3.6).
          // NOTE: this retry is intentionally NOT wrapped in the reactive
          // catchError above — so a second 401 on the retry will NOT trigger
          // another refresh+retry, guaranteeing the single-retry invariant and
          // preventing infinite loops.
          switchMap(() => next(withBearer(req, store.state().accessToken))),

          // If the refresh itself fails, TokenRefreshService has ALREADY cleared
          // the session and started interactive login (clear() +
          // beginInteractiveLogin(), R3.5/R4.5). We must not retry again — just
          // propagate the refresh error so the caller sees the failure.
          catchError((refreshErr) => throwError(() => refreshErr)),
        );
      }

      // Not an expired-token 401: propagate the original error untouched.
      return throwError(() => err);
    }),
  );
};

/**
 * Return true when the request targets our own Resource_Server API and is
 * therefore eligible for bearer attachment + refresh handling. Matching on the
 * absolute backend URL prefix keeps the token scoped to the one origin that
 * should ever receive it.
 */
function isApiRequest(req: HttpRequest<unknown>): boolean {
  return req.url.startsWith(API_URL_PREFIX);
}

/**
 * Clone a request, attaching (or replacing) the `Authorization: Bearer <token>`
 * header. Cloning is required because `HttpRequest` instances are immutable — we
 * never mutate the incoming request, we derive a new one carrying the header.
 *
 * When no token is available (unauthenticated, or the store was just cleared),
 * the request is sent WITHOUT an Authorization header; the Resource_Server will
 * then answer 401 for a protected endpoint, which the guards/refresh flow handle
 * elsewhere.
 *
 * @param req   the outbound request to decorate.
 * @param token the raw encoded Access_Token from the session store, or null.
 * @returns a cloned request with the bearer header, or the original request when
 *          there is no token to attach.
 */
export function withBearer(
  req: HttpRequest<unknown>,
  token: string | null,
): HttpRequest<unknown> {
  if (!token) {
    // Nothing to attach — forward the request unchanged rather than sending an
    // empty/`Bearer null` header.
    return req;
  }
  return req.clone({
    setHeaders: {
      Authorization: `Bearer ${token}`,
    },
  });
}

/**
 * Inspect a 401 error's `WWW-Authenticate` challenge to decide whether it
 * signals an EXPIRED / INVALID token (the reactive-refresh trigger) versus some
 * other authentication failure that a refresh cannot fix.
 *
 * Spring's `BearerTokenAuthenticationEntryPoint` emits challenges of the form:
 *   WWW-Authenticate: Bearer error="invalid_token", error_description="... expired ..."
 * so we treat the presence of `invalid_token` OR an `expired` hint as a
 * refreshable condition (R3.2/R3.3). A bare `WWW-Authenticate: Bearer` with no
 * error (e.g. simply "missing token") is NOT treated as expired — refreshing a
 * token we do not have would be pointless.
 *
 * @param err the HttpErrorResponse to inspect.
 * @returns true when the challenge indicates an expired/invalid token.
 */
export function isExpiredChallenge(err: HttpErrorResponse): boolean {
  // Header names are case-insensitive; HttpHeaders.get handles that for us.
  const challenge = err.headers?.get('WWW-Authenticate') ?? '';
  const normalized = challenge.toLowerCase();
  return (
    normalized.includes('invalid_token') || normalized.includes('expired')
  );
}
