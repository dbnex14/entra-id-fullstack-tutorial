// app/login/login.component.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// LOGIN / REDIRECT-HANDLING COMPONENT
// ─────────────────────────────────────────────────────────────────────────────
//
// This standalone Angular 19 component is the landing point of the OAuth2
// Authorization Code Flow with PKCE. It is the single place in the SPA that
// PROCESSES the redirect that Entra ID performs back to `redirectUri`
// (http://localhost:4200) after the user authenticates at the Authority's
// /authorize endpoint. In other words, it is the "return leg" of the login
// sequence diagrammed in the design (Identity & Token Flow > Login sequence).
//
// ── WHERE THIS SITS IN THE LOGIN SEQUENCE ───────────────────────────────────
//   1. (elsewhere) authGuard sees an unauthenticated user hit a protected route,
//      persists the requested URL under sessionStorage['postLoginRedirect'], and
//      calls TokenRefreshService.beginInteractiveLogin() -> full-page redirect to
//      Entra ID. MSAL generates the PKCE code_verifier/code_challenge and the
//      `state` value under the hood.
//   2. The user authenticates + consents at Entra ID.
//   3. Entra ID redirects the browser back to http://localhost:4200 with either
//      `code` + `state` (success) OR `error` + `error_description` (failure).
//   4. >>> THIS COMPONENT RUNS <<<. In ngOnInit we call
//      MsalService.instance.handleRedirectPromise(), which:
//        - validates the returned `state` against the value MSAL originally sent
//          (R1.9 — a mismatch is rejected internally and surfaces as an error),
//        - detects an authorization `error` param in the redirect (R1.10),
//        - on success completes the code -> token exchange with the stored PKCE
//          code_verifier and returns an AuthenticationResult.
//   5. On SUCCESS we populate AuthSessionStore.setSession(...) from the result
//      (R1.4, R1.5), then redirect to the persisted route or the default landing
//      route (R1.7, R1.8).
//   6. On FAILURE (token exchange error, state mismatch, or authorization error)
//      we show an error message and stay unauthenticated (R1.6, R1.9, R1.10).
//
// ── WHY handleRedirectPromise() IS THE RIGHT ENTRY POINT ─────────────────────
// MSAL owns the sensitive parts of the flow (PKCE verifier, `state`, `nonce`).
// `handleRedirectPromise()` is the library call that inspects the current URL's
// hash/query for the redirect response, performs `state` validation, redeems the
// authorization `code` for tokens, and resolves with an AuthenticationResult
// (or `null` when there is no redirect response to process). We never touch the
// raw `code`, `code_verifier`, or `state` ourselves — we only react to the
// success/failure outcome. This keeps the security-critical validation (R1.9)
// inside MSAL while this component owns the observable state machine and routing
// (R1.6, R1.7, R1.8, R1.10) that the design requires to be explicit and testable.

import {
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { MsalService } from '@azure/msal-angular';
import { AuthenticationResult } from '@azure/msal-browser';

import { AuthSessionStore } from '../auth/auth-session.store';

/**
 * sessionStorage key under which `authGuard` persists the URL the user
 * originally requested before being bounced to interactive login (R1.1, R1.7).
 *
 * This MUST match the literal used in auth.guard.ts (`POST_LOGIN_REDIRECT_KEY`).
 * It is duplicated here as a local constant (rather than imported) to keep the
 * guard's constant private to that module; both sides agree on the exact string
 * `'postLoginRedirect'`, which is the contract between the "persist" side
 * (authGuard) and the "restore" side (this component).
 */
const POST_LOGIN_REDIRECT_KEY = 'postLoginRedirect';

/**
 * The default authenticated landing route to use when no originally requested
 * route was persisted (R1.8). This matches the default redirect target defined
 * in app.routes.ts (task 7.3).
 */
const DEFAULT_LANDING_ROUTE = '/dashboard';

/**
 * The finite set of states the login state machine can occupy. Rendered in the
 * template so a learner can *see* the redirect being processed:
 *  - 'processing' : handleRedirectPromise() is in flight; we are validating
 *                   `state`, checking for an `error` param, and (on success)
 *                   exchanging the code for tokens. This is the initial state.
 *  - 'error'      : the redirect represented a failure (authorization error,
 *                   state mismatch, or token-exchange failure). We stay
 *                   unauthenticated and show `errorMessage`.
 *  - 'done'       : a session was established and we are navigating away to the
 *                   persisted/default route. Transient — the component is about
 *                   to be torn down by the router navigation.
 */
type LoginStatus = 'processing' | 'error' | 'done';

@Component({
  selector: 'app-login',
  // Angular 19 standalone component (standalone is the default in v19). No extra
  // imports are needed: the template renders only signal state with control flow.
  template: `
    <!--
      The template is a direct projection of the login state machine's signals.
      Each @if branch corresponds to exactly one LoginStatus value, so the UI
      always reflects the current phase of the redirect handling.
    -->
    <section class="login-callback" aria-live="polite">
      <!-- PROCESSING: the redirect is being handled (state check, error check,
           token exchange). This is what the user briefly sees on return from
           Entra ID before we route them onward. -->
      @if (status() === 'processing') {
        <p class="login-status login-status--processing" role="status">
          Completing sign-in…
        </p>
      }

      <!-- ERROR: authorization error (R1.10), state mismatch (R1.9), or token
           exchange failure (R1.6). We remain unauthenticated and surface the
           message; the user can retry by navigating to a protected route, which
           re-triggers interactive login via authGuard. -->
      @if (status() === 'error') {
        <div class="login-status login-status--error" role="alert">
          <p>Sign-in was not completed.</p>
          <p class="login-error-detail">{{ errorMessage() }}</p>
        </div>
      }

      <!-- DONE: session established; navigation to the target route is underway.
           Usually invisible because the router immediately swaps this component
           out, but rendered for completeness/observability. -->
      @if (status() === 'done') {
        <p class="login-status login-status--done" role="status">
          Signed in. Redirecting…
        </p>
      }
    </section>
  `,
})
export class LoginComponent implements OnInit {
  // ── Dependencies (Angular 19 inject() DI convention) ──────────────────────

  /**
   * MSAL facade. We use `msal.instance` (the underlying
   * IPublicClientApplication) to call `handleRedirectPromise()`, the primitive
   * that processes the authorization response, validates `state` (R1.9), and
   * performs the code -> token exchange (R1.4).
   */
  private readonly msal = inject(MsalService);

  /**
   * The signal-based session store — the single source of truth for the
   * browser-side session. On a successful redirect we call `setSession(...)`
   * here to flip the SPA into the authenticated state (R1.5).
   */
  private readonly store = inject(AuthSessionStore);

  /**
   * Angular router used to navigate to the persisted originally-requested route
   * (R1.7) or the default landing route (R1.8) once a session is established.
   */
  private readonly router = inject(Router);

  // ── Component state (signals, per Angular 19 conventions) ─────────────────

  /**
   * The current phase of the login state machine, rendered by the template.
   * Starts in 'processing' because ngOnInit immediately begins handling the
   * redirect response.
   */
  readonly status = signal<LoginStatus>('processing');

  /**
   * Human-readable error message shown when `status() === 'error'`. Empty while
   * processing or on success. Populated on any failure branch (R1.6, R1.9,
   * R1.10) with a message describing what went wrong.
   */
  readonly errorMessage = signal<string>('');

  /**
   * Handle the redirect callback as soon as the component initializes.
   *
   * ngOnInit is the correct lifecycle hook: by the time it runs the component is
   * on the page (having been routed to at `redirectUri`) and the URL still
   * carries the authorization response that MSAL needs to inspect. We delegate
   * to the async handler and intentionally do not await it here — ngOnInit stays
   * synchronous while the promise-driven state machine advances the signals.
   */
  ngOnInit(): void {
    // Kick off the redirect-handling state machine. Any rejection is caught
    // inside handleRedirect(), so there is no unhandled promise here.
    void this.handleRedirect();
  }

  /**
   * The core login state machine. Processes the Entra ID redirect response and
   * transitions the session accordingly.
   *
   * SESSION TRANSITIONS (each branch is annotated with its requirement):
   *   processing --(success)--> setSession -> done -> navigate away (R1.4,R1.5,R1.7,R1.8)
   *   processing --(auth error param)--> error, stay unauthenticated           (R1.10)
   *   processing --(state mismatch / exchange failure)--> error, stay unauth    (R1.6,R1.9)
   *   processing --(no redirect response)--> route to default landing/leave login
   */
  private async handleRedirect(): Promise<void> {
    try {
      // ── STEP 1: PROCESS THE REDIRECT RESPONSE ───────────────────────────
      // handleRedirectPromise() parses the current URL for an authorization
      // response and, if present:
      //   - validates the returned `state` against the value MSAL sent; a
      //     mismatch REJECTS the promise (R1.9) and lands us in the catch block,
      //   - detects an authorization `error` param (e.g. access_denied) and also
      //     REJECTS with that error (R1.10) -> catch block,
      //   - on a valid `code`, redeems it for tokens using the stored PKCE
      //     code_verifier (R1.4) and RESOLVES with an AuthenticationResult.
      // When there is NO redirect response to process (e.g. the user navigated
      // to /login directly), it RESOLVES with `null`.
      const result: AuthenticationResult | null =
        await this.msal.instance.handleRedirectPromise();

      // ── BRANCH A: NO REDIRECT RESPONSE (result === null) ────────────────
      // There was nothing to process — this was not a genuine login callback.
      // We do not have (and cannot establish) a session from this navigation.
      // Rather than stranding the user on a blank /login page, send them to the
      // default landing route; authGuard on that route will (re)initiate
      // interactive login if they are still unauthenticated.
      if (result === null) {
        this.status.set('done');
        await this.router.navigateByUrl(DEFAULT_LANDING_ROUTE);
        return;
      }

      // ── BRANCH B: SUCCESS — a session can be established (R1.4, R1.5) ────
      // MSAL has already completed the code -> token exchange and validated the
      // `state`/`nonce`. Populate the signal session store from the result:
      //   - accessToken: the bearer JWT for the backend API,
      //   - expiresOn.getTime(): the ABSOLUTE epoch-ms expiry (R1.5 lifecycle),
      //   - roles: extracted from idTokenClaims.roles (the Entra app roles).
      // MSAL always populates `expiresOn` on a successful acquisition, so the
      // non-null assertion is safe on the success path.
      this.store.setSession(
        result.accessToken,
        result.expiresOn!.getTime(),
        this.extractRoles(result),
      );

      // Mark the machine done and route the user onward (R1.7 / R1.8 below).
      this.status.set('done');
      await this.redirectAfterLogin();
    } catch (error: unknown) {
      // ── BRANCH C: FAILURE — stay unauthenticated (R1.6, R1.9, R1.10) ────
      // Reaching here means one of:
      //   - the authorization response carried an `error` param (R1.10),
      //   - the returned `state` did not match the sent `state` (R1.9), or
      //   - the code -> token exchange failed for some other reason (R1.6).
      // In every case we MUST NOT leave a partial session behind: clear the
      // store so no half-populated/stale token lingers, then surface an error
      // and remain in the unauthenticated 'error' state. The user re-enters the
      // flow by navigating to a protected route (authGuard restarts login).
      this.store.clear();
      this.errorMessage.set(this.toErrorMessage(error));
      this.status.set('error');
    }
  }

  /**
   * Navigate to the originally requested route if one was persisted, else to the
   * default landing route (R1.7, R1.8).
   *
   * Reads (and then removes) the sessionStorage key that `authGuard` wrote
   * before starting login. Clearing it after reading (R1.7) ensures a stale
   * redirect target from a previous login attempt can never hijack a future
   * navigation.
   */
  private async redirectAfterLogin(): Promise<void> {
    // R1.7: read the persisted originally-requested route (may be absent).
    const persisted = sessionStorage.getItem(POST_LOGIN_REDIRECT_KEY);

    // Clear the key immediately after reading so it is single-use and cannot
    // leak into a subsequent login round-trip.
    sessionStorage.removeItem(POST_LOGIN_REDIRECT_KEY);

    // R1.7 (persisted route exists) vs R1.8 (fall back to default landing).
    const target =
      persisted && persisted.length > 0 ? persisted : DEFAULT_LANDING_ROUTE;

    await this.router.navigateByUrl(target);
  }

  /**
   * Extract the case-sensitive role values from a successful token result.
   *
   * The Microsoft identity platform v2 endpoint places app roles in the `roles`
   * claim, which MSAL surfaces on `result.idTokenClaims`. We read `roles` from
   * there (matching the design and TokenRefreshService.extractRoles) and default
   * to an empty array when the claim is absent, so the store always receives a
   * well-formed `string[]` (R1.5).
   */
  private extractRoles(result: AuthenticationResult): string[] {
    // `idTokenClaims` is loosely typed by MSAL; cast to read the optional
    // `roles` array without over-constraining the claim shape.
    const claims = result.idTokenClaims as { roles?: string[] } | undefined;
    return claims?.roles ?? [];
  }

  /**
   * Convert an unknown thrown value into a user-facing error message.
   *
   * MSAL rejects with error objects that expose `errorCode` /
   * `errorMessage`/`message` (e.g. `state_mismatch` for R1.9, or an
   * authorization `error` such as `access_denied` for R1.10). We surface a
   * concise, non-sensitive description; the raw error is not logged with token
   * material to avoid leaking credentials.
   */
  private toErrorMessage(error: unknown): string {
    // MSAL BrowserAuthError / ServerError shape: prefer a specific message when
    // available, otherwise fall back to a generic authentication-failed notice.
    if (error && typeof error === 'object') {
      const e = error as { errorCode?: string; errorMessage?: string; message?: string };
      const detail = e.errorMessage ?? e.message ?? e.errorCode;
      if (detail && detail.length > 0) {
        return `Authentication failed: ${detail}`;
      }
    }
    return 'Authentication failed. Please try signing in again.';
  }
}
