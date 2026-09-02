import { Component, inject } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';

import { AuthSessionStore } from './auth/auth-session.store';
import { TokenRefreshService } from './auth/token-refresh.service';

@Component({
  selector: 'app-root',
  // RouterOutlet hosts the matched feature component; RouterLink/RouterLinkActive
  // power the top-bar navigation and highlight the active route.
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent {
  title = 'EntraUi';

  // ── Dependencies (Angular 19 inject() DI convention) ──────────────────────

  /**
   * Session store — the single source of truth for the browser-side session.
   * The shell reads it (via the exposed signals below) to decide whether to show
   * the "Sign in" or "Sign out" control and to display the current roles.
   */
  private readonly store = inject(AuthSessionStore);

  /**
   * Owns the MSAL session-lifecycle operations. The shell calls
   * `beginInteractiveLogin()` for an explicit "Sign in" and `logout()` for
   * "Sign out"; both perform a full-page redirect to Entra ID.
   */
  private readonly refresher = inject(TokenRefreshService);

  // ── Reactive session facts for the template ───────────────────────────────

  /** Whether a session is currently authenticated (drives which control shows). */
  readonly isAuthenticated = this.store.isAuthenticated;

  /** The current identity's roles, shown in the top bar (informational). */
  roles(): string[] {
    return this.store.state().roles;
  }

  /**
   * Start interactive login (Authorization Code + PKCE) on demand. Lets a user
   * sign in from the shell without first hitting a guarded route.
   */
  signIn(): void {
    this.refresher.beginInteractiveLogin();
  }

  /**
   * Full sign-out: clears the local session and ends the Entra ID session via
   * MSAL `logoutRedirect` (see TokenRefreshService.logout).
   */
  signOut(): void {
    this.refresher.logout();
  }
}
