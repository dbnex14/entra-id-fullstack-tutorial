// app/home/home.component.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// HOME COMPONENT — the PUBLIC landing page (NO GUARD)
// ─────────────────────────────────────────────────────────────────────────────
//
// This is the app's default, unauthenticated landing route. It exists so that
// visiting the app root (or returning here after logout) does NOT immediately
// force an interactive login. Instead the visitor sees a welcome page.
//
// WHY THIS ROUTE IS UNGUARDED (and why that fixes the "instant redirect" bug):
// Previously the empty path redirected to /dashboard, which is guarded by
// authGuard. An unauthenticated visitor therefore tripped the guard and was
// bounced straight to Entra ID on page load and after logout. Making the root a
// PUBLIC page breaks that chain: login now happens only when the user clicks the
// "Sign in" control in the header or deliberately navigates to a protected route
// (/dashboard, /admin), which still trigger login on demand as designed (R1.1).
//
// NOTE: sign-in is initiated from the HEADER control (app.component), not from
// here, so this page intentionally has NO Sign in button — that avoids showing
// two sign-in affordances at once.

import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthSessionStore } from '../auth/auth-session.store';

@Component({
  selector: 'app-home',
  // Standalone component. RouterLink powers the "Go to dashboard" link shown to
  // signed-in visitors.
  imports: [RouterLink],
  template: `
    <section class="page home">
      <div class="card home__card">
        <h1 class="home__title">Item Manager</h1>
        <p class="home__lead">
          Create, view, and manage items — secured by your Microsoft Entra ID
          organizational account.
        </p>

        @if (isAuthenticated()) {
          <!-- Already signed in: offer a way into the protected area. -->
          <p class="home__hint">You are signed in.</p>
          <a class="btn btn--primary" routerLink="/dashboard">Go to dashboard</a>
        } @else {
          <!-- Public visitor: explain how to get in. The actual Sign in control
               lives in the header, so we point to it rather than duplicate it. -->
          <p class="home__hint">
            Use the <strong>Sign in</strong> button in the top-right corner to
            get started.
          </p>
          <ul class="home__features">
            <li>Browse the item catalog (Viewer or Admin role)</li>
            <li>Create and edit items (Admin role)</li>
          </ul>
        }
      </div>
    </section>
  `,
})
export class HomeComponent {
  private readonly store = inject(AuthSessionStore);

  /** Drives whether we show the signed-in view or the welcome guidance. */
  readonly isAuthenticated = this.store.isAuthenticated;
}
