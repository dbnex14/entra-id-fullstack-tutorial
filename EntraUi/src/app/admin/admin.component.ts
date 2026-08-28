// app/admin/admin.component.ts
//
// ─────────────────────────────────────────────────────────────────────────────
// ADMIN COMPONENT — the write path (Admin only)
// ─────────────────────────────────────────────────────────────────────────────
//
// This standalone Angular 19 component demonstrates the "write" half of the
// role-protected REST contract. It performs a create against the backend write
// endpoint:
//
//     POST http://localhost:8080/api/items   body: { name, description }
//
// which the backend guards with `@PreAuthorize("hasRole('Admin')")`. This is the
// canonical Admin-only path used to show the role split end to end.
//
// ── HOW THE BEARER TOKEN AND ROLES DRIVE THIS COMPONENT ─────────────────────
// 1. BEARER TOKEN (transparent to this component):
//    As with the dashboard, this component does not attach or refresh tokens.
//    The global authTokenInterceptor (task 6.4) stamps the current Access_Token
//    onto the outgoing POST as `Authorization: Bearer <token>` and handles
//    proactive/reactive refresh. This component just submits the form and reacts.
//
// 2. ROLES — TWO LAYERS, SERVER IS AUTHORITATIVE:
//    a) CLIENT-SIDE (routing only): the `/admin` route is guarded by
//       `roleGuard(['Admin'])` (task 6.5 / 7.3). That guard reads the roles from
//       AuthSessionStore purely to decide whether to *route* to this component.
//       It is a UX convenience — it hides a page the user cannot use — and is NOT
//       a security control. A determined user could bypass client routing.
//    b) SERVER-SIDE (authoritative): the backend independently re-checks the
//       token's `roles` claim on every request. A token carrying `ROLE_Admin`
//       -> 201 Created with the new item (R8.2). A token WITHOUT `ROLE_Admin`
//       (e.g. a Viewer) -> 403 Forbidden with NO data mutation (R8.3). This
//       component explicitly renders that 403 case so a learner can observe that
//       the server, not the client, is the real gate.
//
//    The upshot: even though the client route guard normally prevents a
//    non-Admin from reaching this screen, the 403 handling below is what actually
//    protects the write — and is the behavior the design requires us to surface.

import { Component, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
// FormsModule provides `[(ngModel)]` two-way binding for the simple create form.
// It must be imported into this standalone component's `imports` array to use
// ngModel in the template.
import { FormsModule } from '@angular/forms';

import { AuthSessionStore } from '../auth/auth-session.store';
import { ItemDto } from '../dashboard/dashboard.component';

/**
 * The backend base URL for the Resource_Server (Spring Boot, port 8080). Matches
 * the dashboard constant and the design's stated backend origin.
 */
const API_BASE_URL = 'http://localhost:8080';

/**
 * The request body accepted by the backend write endpoint. Mirrors the Java
 * `CreateItemRequest` record (`@NotBlank String name`, `String description`), so
 * `name` is required and `description` is optional.
 */
interface CreateItemRequest {
  name: string;
  description?: string;
}

@Component({
  selector: 'app-admin',
  // Standalone component. FormsModule is imported so the template can use
  // `[(ngModel)]` for the create form's two-way binding.
  imports: [FormsModule],
  template: `
    <!--
      The template projects this component's signals:
        - submitting()  : the POST is in flight (disable the submit button),
        - created()     : the item returned by a successful 201 response,
        - errorMessage() : a failure message, including the Admin-required 403.
      The form fields are bound to the name / description signals through small
      change handlers so the state stays signal-based (Angular 19 convention).
    -->
    <section class="admin">
      <header class="admin__header">
        <h1>Create item (Admin)</h1>

        <!-- INFORMATIONAL role display: shows what the token claims so the 403
             vs 201 outcome below can be correlated with the roles. Not a gate. -->
        <p class="admin__identity" role="status">
          @if (isAuthenticated()) {
            Signed in — roles:
            @if (roles().length > 0) {
              <span class="admin__roles">{{ roles().join(', ') }}</span>
            } @else {
              <span class="admin__roles admin__roles--none">(none)</span>
            }
          } @else {
            Not signed in.
          }
        </p>
      </header>

      <!--
        Simple create form. (ngSubmit) calls submit(); the button is disabled
        while a request is in flight or when the required name is blank. The
        inputs are two-way bound to local signals via [ngModel]/(ngModelChange).
      -->
      <form class="admin__form" (ngSubmit)="submit()">
        <label class="admin__field">
          <span>Name</span>
          <input
            name="name"
            type="text"
            [ngModel]="name()"
            (ngModelChange)="name.set($event)"
            required
          />
        </label>

        <label class="admin__field">
          <span>Description</span>
          <input
            name="description"
            type="text"
            [ngModel]="description()"
            (ngModelChange)="description.set($event)"
          />
        </label>

        <button type="submit" [disabled]="submitting() || name().trim().length === 0">
          @if (submitting()) { Creating… } @else { Create }
        </button>
      </form>

      <!-- SUCCESS: the 201 response body (the created item). Confirms the write
           was accepted for an Admin token (R8.2). -->
      @if (created(); as item) {
        <div class="admin__status admin__status--success" role="status">
          Created item #{{ item.id }} — "{{ item.name }}" (created by {{ item.createdBy }}).
        </div>
      }

      <!-- ERROR: any failure, including the Admin-required 403 for non-admins. -->
      @if (errorMessage()) {
        <div class="admin__status admin__status--error" role="alert">
          {{ errorMessage() }}
        </div>
      }
    </section>
  `,
})
export class AdminComponent {
  // ── Dependencies (Angular 19 inject() DI convention) ──────────────────────

  /**
   * HttpClient with the global auth interceptor applied. The POST it issues
   * carries the bearer token automatically; this component reacts only to the
   * success/failure outcome.
   */
  private readonly http = inject(HttpClient);

  /**
   * Session store, used only to display the current identity's roles
   * (informational). The server, not this store, authorizes the write.
   */
  private readonly store = inject(AuthSessionStore);

  // ── Form state (signals) ──────────────────────────────────────────────────

  /** Bound to the "name" input; the only required field (backend `@NotBlank`). */
  readonly name = signal<string>('');

  /** Bound to the optional "description" input. */
  readonly description = signal<string>('');

  // ── Request state (signals) ────────────────────────────────────────────────

  /** True while the POST /api/items request is in flight. */
  readonly submitting = signal<boolean>(false);

  /** The item returned by a successful 201 response, or null before/failure. */
  readonly created = signal<ItemDto | null>(null);

  /** Human-readable error message, including the Admin-required 403 case. */
  readonly errorMessage = signal<string>('');

  // ── Convenience read-throughs to the session store (for the template) ─────

  /** Whether a session is currently authenticated (drives the identity banner). */
  readonly isAuthenticated = this.store.isAuthenticated;

  /**
   * Submit the create form: POST the new item to the Admin-only write endpoint.
   *
   * The bearer token is attached by the interceptor. We react to the outcome:
   *   - 201 Created -> render the returned item, reset the form, clear errors.
   *   - 403        -> render the "not authorized (Admin required)" message; the
   *                   server refused and performed NO mutation (R8.3).
   *   - other      -> render a generic failure message.
   */
  submit(): void {
    // Guard against empty submissions (mirrors the backend `@NotBlank name`).
    const trimmedName = this.name().trim();
    if (trimmedName.length === 0) {
      this.errorMessage.set('Name is required.');
      return;
    }

    // Enter the submitting state; clear any previous success/error so the UI
    // reflects only the current attempt.
    this.submitting.set(true);
    this.created.set(null);
    this.errorMessage.set('');

    // Build the request body matching the backend CreateItemRequest record.
    const body: CreateItemRequest = {
      name: trimmedName,
      // Send description only when the user typed something; otherwise omit it
      // so the backend stores null for the nullable column.
      description: this.description().trim() || undefined,
    };

    // POST to the Admin-only endpoint. `hasRole('Admin')` on the server decides
    // whether this becomes a 201 or a 403 — the client cannot self-authorize.
    this.http.post<ItemDto>(`${API_BASE_URL}/api/items`, body).subscribe({
      next: (item) => {
        // 201 path (Admin token accepted, R8.2): show the created item and reset
        // the form for the next entry.
        this.created.set(item);
        this.name.set('');
        this.description.set('');
        this.submitting.set(false);
      },
      error: (err: HttpErrorResponse) => {
        // Map the failure; the 403 branch is the headline non-admin case (R8.3).
        this.errorMessage.set(this.toWriteErrorMessage(err));
        this.submitting.set(false);
      },
    });
  }

  /**
   * Read the current identity's roles from the session store (informational).
   */
  roles(): string[] {
    return this.store.state().roles;
  }

  /**
   * Map a failed write request to a concise, user-facing message.
   *
   * The 403 case is the instructional centerpiece: it is what the SERVER returns
   * when an authenticated but non-Admin token attempts the write, proving that
   * authorization is claim-driven and server-authoritative — not a client
   * decision (R8.3, R2.7). Other statuses are handled gracefully too.
   */
  private toWriteErrorMessage(err: HttpErrorResponse): string {
    if (err.status === 403) {
      // Authenticated token lacked ROLE_Admin -> server refused, no mutation.
      return 'You are not authorized (Admin required).';
    }
    if (err.status === 401) {
      // Authentication failed / expired and could not be recovered by a refresh.
      return 'Your session has expired. Please sign in again.';
    }
    if (err.status === 400) {
      // Bean-validation failure on the backend (e.g. blank name).
      return 'The item was rejected. Please check the fields and try again.';
    }
    // Any other failure (network down, server error, CORS, etc.).
    return 'Could not create the item. Please try again.';
  }
}
