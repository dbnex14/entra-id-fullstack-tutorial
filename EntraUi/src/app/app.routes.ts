// app.routes.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// ROUTE TABLE — the SPA's navigation map and its authorization wiring
// ─────────────────────────────────────────────────────────────────────────────
//
// This is the single source of truth for which URLs the Angular router will
// activate and, critically, WHICH GUARDS stand in front of each protected
// feature. It is consumed by `provideRouter(routes)` in app.config.ts (task
// 7.4), which is in turn assembled during `bootstrapApplication` in main.ts.
// That chain — main.ts -> app.config.ts -> routes -> components + guards — is
// also what pulls every referenced component and guard into the TypeScript
// compile graph, so a type error anywhere downstream surfaces when this file is
// type-checked.
//
// ── HOW IDENTITY DRIVES NAVIGATION (the story this table tells) ──────────────
// Every route below is a standalone route definition (Angular 19 standalone
// APIs — no NgModules). We use DIRECT `component:` references rather than lazy
// `loadComponent:` imports: the app is small, the components are eagerly needed
// right after login, and direct references keep the wiring obvious for this
// instructional reference. (Lazy loading would be a drop-in swap later:
// `loadComponent: () => import('./dashboard/dashboard.component').then(m => m.DashboardComponent)`.)
//
// The guards do the heavy lifting:
//   • authGuard        — "is there a session?" Gates any route that needs a
//                        logged-in user. If the user is unauthenticated it
//                        persists the requested URL and kicks off the interactive
//                        Authorization Code Flow (PKCE) via MSAL, so the user
//                        lands back where they intended after login (R1.1, R1.7).
//   • roleGuard([...]) — "does the session carry a required role?" A CLIENT-SIDE
//                        UX gate only; the backend Resource Server remains the
//                        authoritative authorization boundary (it re-validates
//                        the JWT and enforces @PreAuthorize on every call). See
//                        the security disclaimer in auth/auth.guard.ts.

import { Routes } from '@angular/router';

import { authGuard, roleGuard } from './auth/auth.guard';
import { LoginComponent } from './login/login.component';
import { DashboardComponent } from './dashboard/dashboard.component';
import { AdminComponent } from './admin/admin.component';

/**
 * The application's route table.
 *
 * Ordering matters: Angular matches routes top-to-bottom and activates the
 * FIRST match, so the concrete feature routes come first, the empty-path
 * default redirect next, and the catch-all wildcard last.
 */
export const routes: Routes = [
  // ── /login — the redirect/callback handler (NO GUARD) ─────────────────────
  // This route is intentionally UNGUARDED. It is where the browser lands after
  // the full-page redirect back from Entra ID, and the LoginComponent itself
  // owns the login state machine: it verifies the returned `state`, detects
  // authorization `error` params, exchanges the code for tokens, populates the
  // session store, and finally redirects to the persisted `postLoginRedirect`
  // route (or the default landing route). Guarding this route with `authGuard`
  // would be self-defeating: the user is BY DEFINITION not yet authenticated
  // when they arrive here, so the guard would bounce them straight back into
  // another login redirect — an infinite loop. Hence: no guard.
  {
    path: 'login',
    component: LoginComponent,
  },

  // ── /dashboard — the default authenticated landing area ───────────────────
  // Requires a session. `authGuard` admits authenticated users and, for anyone
  // else, persists the attempted URL under `postLoginRedirect` and begins
  // interactive login (R1.1). Because navigating here while unauthenticated
  // triggers the login redirect, this is effectively the entry point of the
  // "protected" half of the app. The interceptor attaches the bearer token to
  // the component's `/api/items` calls.
  {
    path: 'dashboard',
    component: DashboardComponent,
    canActivate: [authGuard], // unauthenticated access -> triggers login (R1.1)
  },

  // ── /admin — the Admin-only area ──────────────────────────────────────────
  // TWO guards run in order for this route:
  //   1. authGuard          — first ensures a session exists at all (R1.1). If
  //                           the user is not logged in, login is triggered and
  //                           the second guard never runs.
  //   2. roleGuard(['Admin']) — then checks that the session's `roles` claim
  //                           includes 'Admin'. This is a CLIENT-SIDE UX GATE:
  //                           it keeps non-admins out of a screen whose every
  //                           write would 403 anyway, giving a cleaner UX. It is
  //                           NOT the security boundary — the backend independently
  //                           enforces `hasRole('Admin')` on the write endpoints
  //                           (ItemController / SecurityConfig), so the server is
  //                           authoritative even if a client bypasses this gate.
  {
    path: 'admin',
    component: AdminComponent,
    canActivate: [authGuard, roleGuard(['Admin'])], // session + Admin role (server authoritative)
  },

  // ── '' (empty path) — default redirect to the dashboard ───────────────────
  // With `pathMatch: 'full'` this matches ONLY the exact empty URL (the app
  // root). Landing on '/' sends the user to /dashboard, whose `authGuard` then
  // decides whether to admit them or start login. This gives every visitor a
  // sensible default landing route (R1.8).
  {
    path: '',
    redirectTo: 'dashboard',
    pathMatch: 'full',
  },

  // ── '**' (wildcard) — catch-all redirect to the dashboard ─────────────────
  // Any unrecognized URL falls through to here and is redirected to the
  // dashboard rather than showing a broken/blank route. As with the empty-path
  // redirect, `authGuard` on /dashboard then handles the authenticated-vs-login
  // decision, so unknown deep links still funnel through the normal auth flow.
  {
    path: '**',
    redirectTo: 'dashboard',
  },
];
