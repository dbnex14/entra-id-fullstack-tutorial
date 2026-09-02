// app/dashboard/dashboard.component.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD COMPONENT — the read path (Viewer or Admin)
// ─────────────────────────────────────────────────────────────────────────────
//
// This standalone Angular 19 component demonstrates the "read" half of the
// role-protected REST contract. It calls the backend read endpoint:
//
//     GET http://localhost:8080/entra-backend/items
//
// and renders the returned items using signals. It is deliberately thin: it does
// NOT attach the bearer token itself, and it does NOT know how tokens are
// refreshed. Those concerns live entirely in the functional HTTP interceptor
// (authTokenInterceptor, task 6.4), which is registered globally in app.config.ts
// via `provideHttpClient(withInterceptors([authTokenInterceptor]))` (task 7.4).
//
// ── HOW THE BEARER TOKEN AND ROLES DRIVE THIS COMPONENT ─────────────────────
// 1. BEARER TOKEN (transparent to this component):
//    When this component calls `http.get('/entra-backend/items')`, the request flows
//    through authTokenInterceptor, which reads the current Access_Token from the
//    AuthSessionStore and stamps it onto the outgoing request as
//    `Authorization: Bearer <token>`. If the token is near expiry the interceptor
//    proactively refreshes it first; if the server answers 401 (expired) it
//    performs a single background refresh and retries once. This component simply
//    calls the API and reacts to the result — the token machinery is invisible.
//
// 2. ROLES (authorization, enforced by the SERVER):
//    The backend read endpoint is annotated `@PreAuthorize("hasAnyRole('Viewer','Admin')")`,
//    so a token carrying `ROLE_Viewer` OR `ROLE_Admin` (derived from the `roles`
//    claim by the backend RolesClaimConverter) receives 200 + data (R8.1). A token
//    with neither role would receive 403. Because a Viewer can read, this
//    dashboard is reachable by any authenticated user with a role — that is why
//    the route is guarded only by `authGuard` (not a `roleGuard`) in app.routes.ts.
//
//    IMPORTANT: the client-side role display below is purely informational. The
//    SERVER is authoritative for every access decision — the client never grants
//    itself access, it only reflects what the token claims and lets the backend
//    accept or reject the actual call.

import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';

import { AuthSessionStore } from '../auth/auth-session.store';

/**
 * The backend base URL for the Resource_Server (Spring Boot, port 8080). Defined
 * as a constant so the read/write endpoints stay in one place and match the
 * design's stated origins (`Backend: http://localhost:8080`).
 */
const API_BASE_URL = 'http://localhost:8080';

/**
 * Client-side view of the backend `ItemDto` record. The Java side is:
 *
 *     record ItemDto(Long id, String name, String description, String createdBy) {}
 *
 * so the JSON shape is `{ id, name, description, createdBy }`. `description` is
 * marked optional here because the column is nullable in the schema (the backend
 * may serialize it as `null`/absent). `createdBy` holds the token subject
 * (oid/sub) that the backend persisted from the validated JWT — this is how a
 * rendered row ties back to the identity that created it.
 */
export interface ItemDto {
  id: number;
  name: string;
  description?: string;
  createdBy: string;
}

@Component({
  selector: 'app-dashboard',
  // Angular 19 standalone component (standalone is the default in v19). No module
  // imports are required: the template renders only signal state with control flow.
  template: `
    <!--
      The template is a direct projection of this component's signals:
        - loading()  : the GET /entra-backend/items request is in flight,
        - error()    : a request-level failure message (network, 401 after refresh
                       failure, 403, etc.),
        - items()    : the successfully loaded rows.
      Rendering straight from signals keeps the UI reactive without manual
      subscriptions or change-detection plumbing.
    -->
    <section class="dashboard">
      <header class="dashboard__header">
        <h1>Items</h1>

        <!--
          INFORMATIONAL role display. This shows what the current token claims so
          a learner can correlate the roles claim with what the server allows.
          It does NOT gate anything — the server is authoritative (R8).
        -->
        <p class="dashboard__identity" role="status">
          @if (isAuthenticated()) {
            Signed in — roles:
            @if (roles().length > 0) {
              <span class="dashboard__roles">{{ roles().join(', ') }}</span>
            } @else {
              <span class="dashboard__roles dashboard__roles--none">(none)</span>
            }
          } @else {
            Not signed in.
          }
        </p>
      </header>

      <!-- LOADING: the read request is in progress. -->
      @if (loading()) {
        <p class="dashboard__status dashboard__status--loading" role="status">
          Loading items…
        </p>
      }

      <!-- ERROR: surface a request-level failure. For a read this is typically a
           network error or a 401 that could not be recovered by a refresh. -->
      @if (error()) {
        <div class="dashboard__status dashboard__status--error" role="alert">
          {{ error() }}
        </div>
      }

      <!-- SUCCESS: render the loaded items. Empty result is a valid state. -->
      @if (!loading() && !error()) {
        @if (items().length > 0) {
          <ul class="dashboard__items">
            @for (item of items(); track item.id) {
              <li class="dashboard__item">
                <span class="dashboard__item-name">{{ item.name }}</span>
                @if (item.description) {
                  <span class="dashboard__item-desc">{{ item.description }}</span>
                }
                <!-- createdBy is the token subject the backend persisted, tying
                     the row back to the identity that created it. -->
                <span class="dashboard__item-owner">created by {{ item.createdBy }}</span>
              </li>
            }
          </ul>
        } @else {
          <p class="dashboard__status dashboard__status--empty" role="status">
            No items yet.
          </p>
        }
      }
    </section>
  `,
})
export class DashboardComponent implements OnInit {
  // ── Dependencies (Angular 19 inject() DI convention) ──────────────────────

  /**
   * Angular's HttpClient. Because app.config.ts registers the auth interceptor
   * globally, every call made through this client automatically carries the
   * bearer token and participates in the proactive/reactive refresh machinery.
   * This component therefore never touches tokens directly.
   */
  private readonly http = inject(HttpClient);

  /**
   * The signal session store, consulted ONLY to display the current identity's
   * roles (informational). Access decisions are made by the server, not here.
   */
  private readonly store = inject(AuthSessionStore);

  // ── Component state (signals, per Angular 19 conventions) ─────────────────

  /** The loaded items. Starts empty; populated on a successful GET. */
  readonly items = signal<ItemDto[]>([]);

  /** True while the GET /entra-backend/items request is in flight. */
  readonly loading = signal<boolean>(false);

  /** Human-readable error message when a request fails; empty otherwise. */
  readonly error = signal<string>('');

  // ── Convenience read-throughs to the session store (for the template) ─────

  /** Whether a session is currently authenticated (drives the identity banner). */
  readonly isAuthenticated = this.store.isAuthenticated;

  /**
   * Load the items as soon as the component initializes. The route that hosts
   * this component is guarded by authGuard, so by the time ngOnInit runs the
   * user is authenticated and the interceptor has a token to attach.
   */
  ngOnInit(): void {
    this.loadItems();
  }

  /**
   * Read the item list from the backend and project the result into signals.
   *
   * The bearer token is attached transparently by authTokenInterceptor, so this
   * method just issues the GET and reacts:
   *   - next  : replace `items` with the returned rows and clear any error.
   *   - error : map the HttpErrorResponse to a friendly message.
   *   - final : `loading` is toggled around the request in both paths.
   *
   * Exposed as a public method (not just called from ngOnInit) so a template
   * "retry" affordance could re-invoke it if desired.
   */
  loadItems(): void {
    // Enter the loading state and clear any previous error before firing.
    this.loading.set(true);
    this.error.set('');

    // The interceptor attaches `Authorization: Bearer <token>` to this request;
    // we only describe the response shape via the ItemDto generic.
    this.http.get<ItemDto[]>(`${API_BASE_URL}/entra-backend/items`).subscribe({
      next: (loaded) => {
        // Replace the backing signal with the freshly loaded rows.
        this.items.set(loaded);
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        // Any failure that reached us was NOT recovered by the interceptor's
        // refresh/retry (e.g. a hard network error, or a 401 whose refresh
        // failed and cleared the session). Surface a readable message.
        this.error.set(this.toReadErrorMessage(err));
        this.loading.set(false);
      },
    });
  }

  /**
   * Read the current identity's roles from the session store (informational).
   * Wrapped as a small accessor so the template can call `roles()` uniformly.
   */
  roles(): string[] {
    return this.store.state().roles;
  }

  /**
   * Map a failed read request to a concise, user-facing message. Reads are open
   * to Viewer and Admin, so a 403 here would be unusual (it would mean a token
   * carrying neither role), but we still handle it explicitly for clarity.
   */
  private toReadErrorMessage(err: HttpErrorResponse): string {
    if (err.status === 403) {
      // Token authenticated but lacked Viewer/Admin — server refused (R8, R2.7).
      return 'You are not authorized to view items (Viewer or Admin required).';
    }
    if (err.status === 401) {
      // Authentication failed and could not be recovered by a refresh; the
      // interceptor will have begun re-authentication.
      return 'Your session has expired. Please sign in again.';
    }
    // Any other failure (network down, server error, CORS, etc.).
    return 'Could not load items. Please try again.';
  }
}
