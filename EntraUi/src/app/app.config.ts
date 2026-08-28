// app.config.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// STANDALONE BOOTSTRAP & PROVIDER ASSEMBLY — the SPA's composition root
// ─────────────────────────────────────────────────────────────────────────────
//
// This is the single `ApplicationConfig` that `bootstrapApplication(AppComponent,
// appConfig)` (see main.ts) hands to Angular's standalone bootstrap. There are no
// NgModules in this app (Angular 19 standalone APIs), so THIS array of providers
// is the entire dependency-injection graph the running SPA is built from. Every
// piece of the identity story is wired here:
//
//   main.ts
//     └─ bootstrapApplication(AppComponent, appConfig)
//          └─ appConfig.providers  ◀── THIS FILE
//               ├─ provideRouter(routes) ......... navigation + route guards
//               ├─ provideHttpClient(withInterceptors([authTokenInterceptor]))
//               │        ................. bearer-attach + refresh/queue on every XHR
//               └─ MSAL providers ................ Authorization Code Flow + PKCE engine
//
// Because these providers pull in `routes` (which references the guards and every
// feature component) and the interceptor (which references the session store and
// token-refresh service, which in turn reference the MSAL factories), this file
// sits at the ROOT of the whole app's TypeScript compile graph. A type error
// anywhere downstream surfaces when the app graph is type-checked from here.
//
// ── HOW THE THREE LAYERS COOPERATE AT RUNTIME ───────────────────────────────
//  1. ROUTER + GUARDS: `provideRouter(routes)` installs the route table. The
//     `authGuard` / `roleGuard` attached to protected routes read the signal
//     session store and, when a user is unauthenticated, persist the intended
//     URL and kick off MSAL interactive login (Authorization Code Flow + PKCE).
//     (R1.1)
//  2. HTTP INTERCEPTOR: `provideHttpClient(withInterceptors([authTokenInterceptor]))`
//     registers our CUSTOM functional interceptor into the HttpClient pipeline.
//     On every outbound request it attaches the bearer access token, proactively
//     refreshes the token when it is within the expiry threshold (R3.4, R4.1),
//     and on a 401 expired-token challenge performs a single background refresh
//     and one retry (R3.3). (R3.3, R4.1)
//  3. MSAL: the three `MSAL_*` tokens + `MsalService`/`MsalGuard`/
//     `MsalBroadcastService` provide the MSAL engine that actually executes the
//     OAuth2 Authorization Code Flow with PKCE, token redemption, silent renewal,
//     and refresh-token rotation. The custom refresh layer (TokenRefreshService)
//     calls into `MsalService.acquireTokenSilent` / `acquireTokenRedirect`
//     under the hood. (R1.3)

import {
  ApplicationConfig,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  MSAL_INSTANCE,
  MSAL_GUARD_CONFIG,
  MSAL_INTERCEPTOR_CONFIG,
  MsalService,
  MsalGuard,
  MsalBroadcastService,
} from '@azure/msal-angular';

import { routes } from './app.routes';
import { authTokenInterceptor } from './auth/auth-token.interceptor';
import {
  msalInstanceFactory,
  msalGuardConfigFactory,
  msalInterceptorConfigFactory,
} from './auth/msal.config';

/**
 * The application-wide provider assembly consumed by `bootstrapApplication`.
 *
 * The ordering below groups providers by concern (change detection, routing,
 * HTTP, then MSAL) purely for readability; Angular resolves providers by token,
 * not by position, so the order does not affect behavior.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    // ── Change detection ─────────────────────────────────────────────────────
    // Coalesce multiple synchronous events into a single change-detection pass.
    // Left in place from the generated scaffold; unrelated to auth but part of
    // the app's baseline runtime config.
    provideZoneChangeDetection({ eventCoalescing: true }),

    // ── Router + guards ──────────────────────────────────────────────────────
    // Installs the route table from app.routes.ts. Those routes carry the
    // `authGuard` (session required) and `roleGuard(['Admin'])` (client-side UX
    // gate) that, together with MSAL below, drive the unauthenticated → login
    // redirect and the return-to-requested-route behavior (R1.1).
    provideRouter(routes),

    // ── HttpClient + our CUSTOM functional interceptor ───────────────────────
    // `withInterceptors([authTokenInterceptor])` registers the functional
    // interceptor from auth/auth-token.interceptor.ts into the HttpClient chain.
    // It is the ONE place bearer tokens are attached to outbound `/api/*` calls,
    // where proactive refresh happens before a request (R3.4, R4.1), and where a
    // single reactive refresh + retry happens on a 401 expired-token challenge
    // (R3.3).
    //
    // IMPORTANT — why we do NOT also register MSAL's class-based MsalInterceptor:
    // @azure/msal-angular ships a default `MsalInterceptor` (registered via the
    // `HTTP_INTERCEPTORS` multi-provider) that would ALSO attach a bearer token
    // to requests matching the `protectedResourceMap`. Registering both would
    // DOUBLE-ATTACH / double-manage tokens on the same requests, producing
    // conflicting behavior and defeating the explicit, testable single-flight
    // refresh + single-retry guarantees that tasks 6.3/6.4 built into our custom
    // layer. So we deliberately register ONLY `authTokenInterceptor` here and
    // rely on it exclusively for HTTP token handling. (See the design's
    // "Design note on refresh control".)
    provideHttpClient(withInterceptors([authTokenInterceptor])),

    // ── MSAL engine (OAuth2 Authorization Code Flow + PKCE) ──────────────────
    // These providers stand up the MSAL runtime. The three factory-backed tokens
    // supply configuration built in auth/msal.config.ts; the three services are
    // the Angular-facing MSAL API that our guards and TokenRefreshService inject.

    // The singleton PublicClientApplication that performs PKCE, code exchange,
    // silent renewal, and refresh-token rotation. Consumed by MsalService.
    { provide: MSAL_INSTANCE, useFactory: msalInstanceFactory },

    // How/with-what-scopes to start interactive login (redirect flow) when a
    // guard triggers sign-in for an unauthenticated user (R1.3).
    { provide: MSAL_GUARD_CONFIG, useFactory: msalGuardConfigFactory },

    // Which outgoing endpoints are "protected" and which scopes their token
    // needs. Provided so the MSAL guard/consent plumbing has its config
    // available. NOTE: even though this config exists, the ACTUAL per-request
    // HTTP token interception in this app is done by our custom
    // `authTokenInterceptor` above — not by MSAL's default MsalInterceptor,
    // which we intentionally do not register (see the comment on
    // provideHttpClient). Providing MSAL_INTERCEPTOR_CONFIG here is harmless and
    // keeps the standard MSAL wiring complete/available.
    { provide: MSAL_INTERCEPTOR_CONFIG, useFactory: msalInterceptorConfigFactory },

    // MsalService     — the injectable MSAL API (loginRedirect, acquireTokenSilent,
    //                   acquireTokenRedirect) that TokenRefreshService and the
    //                   guards call to drive the Authorization Code Flow.
    // MsalGuard       — MSAL's own CanActivate guard; provided so it is available
    //                   for DI (our custom authGuard reuses MSAL's login config).
    // MsalBroadcastService — emits MSAL lifecycle/interaction events; provided so
    //                   MSAL's redirect-callback handling and event stream work.
    MsalService,
    MsalGuard,
    MsalBroadcastService,
  ],
};
