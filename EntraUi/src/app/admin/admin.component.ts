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
//     POST http://localhost:8080/entra-backend/items   body: { name, description }
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

import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
// FormsModule provides `[(ngModel)]` two-way binding for the create/edit forms.
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
 * `CreateItemRequest` record (`@NotBlank String name`, `String description`,
 * `String category`), so `name` is required and `description`/`category` are
 * optional.
 */
interface CreateItemRequest {
  name: string;
  description?: string;
  category?: string;
}

/**
 * The request body accepted by the backend update endpoint
 * ({@code PUT /entra-backend/items/{id}}). Mirrors the Java `UpdateItemRequest`
 * record (`@NotBlank String name`, `String description`, `String category`) —
 * same shape as the create body: `name` is required, `description`/`category`
 * are optional.
 */
interface UpdateItemRequest {
  name: string;
  description?: string;
  category?: string;
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
    <section class="page admin">
      <div class="card">
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

        <!-- Optional category. Admin-only to edit, matching the write-gating: the
             whole /admin screen is Admin-gated (route guard + server hasRole). -->
        <label class="admin__field">
          <span>Category</span>
          <input
            name="category"
            type="text"
            placeholder="e.g. hardware, software, service"
            [ngModel]="category()"
            (ngModelChange)="category.set($event)"
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
      </div>

      <div class="card">
      <!--
        ── EDIT EXISTING ITEM (PUT /entra-backend/items/{id}) ─────────────────
        Exercises the Admin-only UPDATE endpoint. We first GET the current items
        so the admin can pick one, load it into the edit form, change the fields,
        and PUT the update. As with create, the server independently enforces
        the Admin role — a non-Admin token gets 403 with no mutation (R8.3).
      -->
      <header class="admin__header">
        <h2>Edit item (Admin)</h2>
        <button type="button" (click)="loadItems()" [disabled]="loadingItems()">
          @if (loadingItems()) { Loading… } @else { Refresh list }
        </button>
      </header>

      @if (items().length > 0) {
        <ul class="admin__item-list">
          @for (item of items(); track item.id) {
            <li class="admin__item-row">
              <span>#{{ item.id }} — {{ item.name }}@if (item.category) { <em>({{ item.category }})</em> }</span>
              <span class="admin__item-actions">
                <button type="button" (click)="beginEdit(item)">Edit</button>
                <button
                  type="button"
                  class="btn--danger"
                  [disabled]="deletingId() === item.id"
                  (click)="deleteItem(item)"
                >
                  @if (deletingId() === item.id) { Deleting… } @else { Delete }
                </button>
              </span>
            </li>
          }
        </ul>
      } @else if (!loadingItems()) {
        <p class="admin__status admin__status--empty" role="status">
          No items to edit yet. Create one above, then Refresh list.
        </p>
      }

      <!-- The edit form only appears once an item is selected via "Edit". -->
      @if (editingId() !== null) {
        <form class="admin__form" (ngSubmit)="saveEdit()">
          <p>Editing item #{{ editingId() }}</p>

          <label class="admin__field">
            <span>Name</span>
            <input
              name="editName"
              type="text"
              [ngModel]="editName()"
              (ngModelChange)="editName.set($event)"
              required
            />
          </label>

          <label class="admin__field">
            <span>Description</span>
            <input
              name="editDescription"
              type="text"
              [ngModel]="editDescription()"
              (ngModelChange)="editDescription.set($event)"
            />
          </label>

          <label class="admin__field">
            <span>Category</span>
            <input
              name="editCategory"
              type="text"
              placeholder="e.g. hardware, software, service"
              [ngModel]="editCategory()"
              (ngModelChange)="editCategory.set($event)"
            />
          </label>

          <div class="admin__form-actions">
            <button type="submit" [disabled]="saving() || editName().trim().length === 0">
              @if (saving()) { Saving… } @else { Save changes }
            </button>
            <button type="button" (click)="cancelEdit()" [disabled]="saving()">Cancel</button>
          </div>
        </form>
      }

      <!-- SUCCESS: the 200 response body from a successful update (R8.2). -->
      @if (updated(); as item) {
        <div class="admin__status admin__status--success" role="status">
          Updated item #{{ item.id }} — "{{ item.name }}".
        </div>
      }

      <!-- ERROR: update-specific failure, including the Admin-required 403. -->
      @if (editErrorMessage()) {
        <div class="admin__status admin__status--error" role="alert">
          {{ editErrorMessage() }}
        </div>
      }
      </div>
    </section>
  `,
})
export class AdminComponent implements OnInit {
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

  /** Bound to the optional "category" input (Admin-only to edit). */
  readonly category = signal<string>('');

  // ── Request state (signals) ────────────────────────────────────────────────

  /** True while the POST /entra-backend/items request is in flight. */
  readonly submitting = signal<boolean>(false);

  /** The item returned by a successful 201 response, or null before/failure. */
  readonly created = signal<ItemDto | null>(null);

  /** Human-readable error message, including the Admin-required 403 case. */
  readonly errorMessage = signal<string>('');

  // ── Edit (update) state (signals) ──────────────────────────────────────────

  /** The current list of items to choose from, loaded via GET. */
  readonly items = signal<ItemDto[]>([]);

  /** True while the GET that populates the edit list is in flight. */
  readonly loadingItems = signal<boolean>(false);

  /** The id of the item currently being edited, or null when no edit is active. */
  readonly editingId = signal<number | null>(null);

  /** Bound to the edit form's "name" input (required, backend `@NotBlank`). */
  readonly editName = signal<string>('');

  /** Bound to the edit form's optional "description" input. */
  readonly editDescription = signal<string>('');

  /** Bound to the edit form's optional "category" input. */
  readonly editCategory = signal<string>('');

  /** True while the PUT /entra-backend/items/{id} request is in flight. */
  readonly saving = signal<boolean>(false);

  /** The item returned by a successful 200 update, or null before/failure. */
  readonly updated = signal<ItemDto | null>(null);

  /** Human-readable error message for the update flow, including 403. */
  readonly editErrorMessage = signal<string>('');

  /**
   * The id of the item currently being deleted, or null when no delete is in
   * flight. Used to disable and relabel the specific row's Delete button.
   */
  readonly deletingId = signal<number | null>(null);

  // ── Convenience read-throughs to the session store (for the template) ─────

  /** Whether a session is currently authenticated (drives the identity banner). */
  readonly isAuthenticated = this.store.isAuthenticated;

  /**
   * Load the current items once when the component initializes so the edit list
   * is populated. The route is guarded by authGuard + roleGuard(['Admin']), so a
   * token is present by the time this runs.
   */
  ngOnInit(): void {
    this.loadItems();
  }

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
      // Same treatment for the optional category label.
      category: this.category().trim() || undefined,
    };

    // POST to the Admin-only endpoint. `hasRole('Admin')` on the server decides
    // whether this becomes a 201 or a 403 — the client cannot self-authorize.
    this.http.post<ItemDto>(`${API_BASE_URL}/entra-backend/items`, body).subscribe({
      next: (item) => {
        // 201 path (Admin token accepted, R8.2): show the created item and reset
        // the form for the next entry.
        this.created.set(item);
        this.name.set('');
        this.description.set('');
        this.category.set('');
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
   * Load the item list from the backend read endpoint so the admin can pick one
   * to edit. Reads are open to Viewer and Admin, so an Admin token always sees
   * the list. The bearer token is attached by the interceptor.
   */
  loadItems(): void {
    this.loadingItems.set(true);
    this.http.get<ItemDto[]>(`${API_BASE_URL}/entra-backend/items`).subscribe({
      next: (loaded) => {
        this.items.set(loaded);
        this.loadingItems.set(false);
      },
      error: () => {
        // A failure to load the edit list is non-fatal to the create flow; show
        // it in the edit-error slot and leave the list empty.
        this.editErrorMessage.set('Could not load items to edit. Please try Refresh list.');
        this.loadingItems.set(false);
      },
    });
  }

  /**
   * Load a selected item into the edit form. Copies its current name/description
   * into the edit signals and records which id is being edited so `saveEdit`
   * knows the target of the PUT.
   */
  beginEdit(item: ItemDto): void {
    this.editingId.set(item.id);
    this.editName.set(item.name);
    this.editDescription.set(item.description ?? '');
    this.editCategory.set(item.category ?? '');
    // Clear any prior update result/error so the form starts clean.
    this.updated.set(null);
    this.editErrorMessage.set('');
  }

  /**
   * Abandon the in-progress edit and hide the edit form without contacting the
   * server.
   */
  cancelEdit(): void {
    this.editingId.set(null);
    this.editName.set('');
    this.editDescription.set('');
    this.editCategory.set('');
  }

  /**
   * Save the current edit: PUT the changed item to the Admin-only update
   * endpoint (`PUT /entra-backend/items/{id}`).
   *
   * The bearer token is attached by the interceptor. We react to the outcome:
   *   - 200 OK -> render the returned item, refresh the list, close the form.
   *   - 403    -> "not authorized (Admin required)"; server refused, no mutation
   *               (R8.3).
   *   - other  -> generic failure message.
   */
  saveEdit(): void {
    const id = this.editingId();
    if (id === null) {
      return;
    }

    // Guard against clearing the required name (mirrors backend `@NotBlank`).
    const trimmedName = this.editName().trim();
    if (trimmedName.length === 0) {
      this.editErrorMessage.set('Name is required.');
      return;
    }

    this.saving.set(true);
    this.updated.set(null);
    this.editErrorMessage.set('');

    const body: UpdateItemRequest = {
      name: trimmedName,
      description: this.editDescription().trim() || undefined,
      category: this.editCategory().trim() || undefined,
    };

    // PUT to the Admin-only endpoint. `hasRole('Admin')` on the server decides
    // whether this becomes a 200 or a 403 — the client cannot self-authorize.
    this.http
      .put<ItemDto>(`${API_BASE_URL}/entra-backend/items/${id}`, body)
      .subscribe({
        next: (item) => {
          // 200 path (Admin token accepted, R8.2): show it, refresh the list so
          // the change is visible, and close the edit form.
          this.updated.set(item);
          this.saving.set(false);
          this.cancelEdit();
          this.loadItems();
        },
        error: (err: HttpErrorResponse) => {
          this.editErrorMessage.set(this.toWriteErrorMessage(err));
          this.saving.set(false);
        },
      });
  }

  /**
   * Delete an item after an explicit confirmation prompt.
   *
   * DELETE /entra-backend/items/{id} is Admin-only and returns 204 No Content on
   * success. As with create/update the server is authoritative: a non-Admin
   * token would get 403 and remove nothing. We confirm first because a delete is
   * destructive and not undoable from the UI.
   *
   *   - 204 -> remove the row locally by refreshing the list; clear any error.
   *   - 403 -> "not authorized (Admin required)".
   *   - 404 -> the item was already gone; refresh so the list reflects reality.
   *   - other -> generic failure message.
   */
  deleteItem(item: ItemDto): void {
    // Guard the destructive action behind a native confirm dialog. If the user
    // cancels, do nothing.
    const confirmed = window.confirm(
      `Delete item #${item.id} "${item.name}"? This cannot be undone.`,
    );
    if (!confirmed) {
      return;
    }

    // If we were editing this same item, close the edit form — it is about to
    // cease to exist.
    if (this.editingId() === item.id) {
      this.cancelEdit();
    }

    this.deletingId.set(item.id);
    this.editErrorMessage.set('');

    this.http
      .delete<void>(`${API_BASE_URL}/entra-backend/items/${item.id}`)
      .subscribe({
        next: () => {
          // 204 No Content: the row is gone. Reload the list so the UI matches
          // the server, and clear the deleting indicator.
          this.deletingId.set(null);
          this.loadItems();
        },
        error: (err: HttpErrorResponse) => {
          this.deletingId.set(null);
          if (err.status === 404) {
            // Already deleted elsewhere — reconcile by refreshing the list.
            this.loadItems();
            return;
          }
          this.editErrorMessage.set(this.toWriteErrorMessage(err));
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
