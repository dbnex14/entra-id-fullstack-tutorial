# Learning Guide — Entra ID OAuth2 Full-Stack Reference

This guide is a **guided reading path** through the whole project. Every source
file in this repository was written with extensive inline comments that explain
*why* each line exists and how the identity payload (tokens, claims, roles)
flows down the stack. The fastest way to learn the subjects covered here is to
open the files **in the order below** and read them top to bottom, comments and
all.

You do not need to run anything to learn from this path — reading the files in
order is enough. When you *do* want to see it working, the final stop
(`GUIDE/TOKEN-VERIFICATION-GUIDE.md`) tells you how.

> **Related documentation:** the repository `README.md` is the overview and
> entry point; the `DOC/` folder holds reference docs (ARCHITECTURE, API,
> SECURITY, DATABASE, CONFIGURATION); the sibling guides `RUNNING-GUIDE.md`
> and `RUNNING-MAC-GUIDE.md` cover running the apps.

---

## What you will learn

By reading the files in order you will understand, end to end:

- **OAuth2 / OpenID Connect** — the Authorization Code Flow with PKCE, and the
  roles of a Client (SPA), an Authority (Entra ID), and a Resource Server.
- **JWT validation** — signature verification via JWKS, and the `iss`, `aud`,
  and `exp` claims (with clock skew).
- **Claim-driven authorization** — turning an Entra `roles` claim into Spring
  Security authorities and gating endpoints with `@PreAuthorize`.
- **Spring Boot resource server** — security filter chain, CORS, stateless
  sessions, method security, JPA/Hibernate, and Flyway schema migrations.
- **Angular 19 (standalone)** — `inject()` dependency injection, signals for
  state, functional route guards, and functional HTTP interceptors.
- **MSAL** — how the browser library performs PKCE, token exchange, silent
  renewal, and refresh-token rotation, and why a thin custom layer sits on top.
- **Token lifecycle in the browser** — proactive vs reactive refresh,
  single-flight request coalescing, and bounded retry with re-authentication.
- **Testing** — property-based tests (jqwik on the backend, fast-check on the
  frontend) and example-based security-slice / interceptor tests.

---

## How to use this guide

1. Read the **Orientation** section once for the big picture.
2. Work through the numbered **Reading Path** stages in order. Each entry lists
   the file to open and what to focus on.
3. Read the comments — they are the lesson. The code demonstrates the comment.
4. When a file references another concept ("see X"), you will usually reach X in
   a later stage; trust the order.

Legend for each entry:

- **File** — the path to open.
- **Read for** — the concepts this file teaches.
- **Look for** — specific comments/lines worth pausing on.

---

## Print it and read it like a book

If you prefer to read on paper (or a tablet) away from the editor, you can turn
this reading path into a single ordered "book" and print it. The exact ordered
list of files is the **File Manifest** at the end of this guide, and this repo
ships that same list as a plain text file — `GUIDE/reading-order.txt` (one file
path per line, in reading order; blank lines and `#` comments are ignored). The
commands below consume it.

**Run these from the repository root** — the folder that directly contains the
`GUIDE/`, `entra-backend/`, and `EntraUi/` directories (for example
`C:\dev\entra-tutorial`). The commands create the book as **`learning-book.txt`
in that same repository-root folder** — right next to `GUIDE/` — and then print a
one-line confirmation so you can see it worked. (The file is a normal, tracked
file in the repo root; it will show up in `git status`.)

**Option A — one concatenated "book" file (recommended for printing).**
Produces `learning-book.txt` with a labelled banner before each file, in order,
so it reads front-to-back like chapters.

macOS / Linux (bash):

```bash
# Fails loudly if you're not in the repo root, so you never get a silent no-op:
[ -f GUIDE/reading-order.txt ] || { echo "Not in the repo root. cd to the folder that contains GUIDE/, then rerun."; }
: > learning-book.txt
while IFS= read -r f; do
  case "$f" in ''|\#*) continue;; esac        # skip blank lines and # comments
  { printf '\n\n===== FILE: %s =====\n\n' "$f"; cat "$f"; } >> learning-book.txt
done < GUIDE/reading-order.txt
echo "Wrote learning-book.txt ($(grep -c '===== FILE:' learning-book.txt) files, $(wc -l < learning-book.txt) lines)."
# Then open/print learning-book.txt (e.g. `lp learning-book.txt`, or open it and Print).
```

Windows (PowerShell):

```powershell
# Fails loudly if you're not in the repo root, so you never get a silent no-op:
if (-not (Test-Path GUIDE/reading-order.txt)) { Write-Host "Not in the repo root. cd to the folder that contains GUIDE\, then rerun." }
Remove-Item learning-book.txt -ErrorAction Ignore
Get-Content GUIDE/reading-order.txt | Where-Object { $_ -and $_ -notmatch '^\s*#' } | ForEach-Object {
  "`r`n`r`n===== FILE: $_ =====`r`n" | Add-Content learning-book.txt
  Get-Content $_ | Add-Content learning-book.txt
}
Write-Host "Wrote learning-book.txt ($((Select-String -Path learning-book.txt -Pattern '===== FILE:').Count) files, $((Get-Content learning-book.txt).Count) lines)."
# Then print: notepad learning-book.txt  (File > Print), or your editor's Print.
```

A successful run prints `Wrote learning-book.txt (50 files, ...)`. If you instead
see the "Not in the repo root" message, `cd` to the folder that contains `GUIDE/`
and run it again. (The command is otherwise silent by design — it only writes the
file — which is why the confirmation line is there.)

**Option B — print each file separately, in order** (a stack of per-file printouts).
Run from the repo root, same as above:

macOS / Linux:

```bash
while IFS= read -r f; do
  case "$f" in ''|\#*) continue;; esac
  lp "$f"        # or: enscript -p - "$f" | lp   for syntax-friendly output
done < GUIDE/reading-order.txt
```

Windows (PowerShell):

```powershell
Get-Content GUIDE/reading-order.txt | Where-Object { $_ -and $_ -notmatch '^\s*#' } | ForEach-Object {
  Start-Process -FilePath $_ -Verb Print       # prints via the file type's default handler
}
```

Tips for the paper read:
- Print with **line wrapping on** and a **monospace** font so long comment lines
  are not clipped. For syntax highlighting on paper, `enscript`, `a2ps`, or
  VS Code's Print work well.
- Read the printouts in manifest order — it matches the stages below exactly, so
  the concepts build on one another like chapters.
- You do not need the running app to read; save `TOKEN-VERIFICATION-GUIDE.md`
  (the last item) for when you are back at a machine and want to see it live.

---

## Orientation — the mental model before you read code

Three actors cooperate in this system:

1. **Client_App** — the Angular SPA at `http://localhost:4200`. It is a *public
   client* (cannot hold a secret), so it uses **PKCE**. It signs the user in via
   Entra ID and calls the API with a bearer token.
2. **Authority** — Microsoft Entra ID. It authenticates the user, asks for
   consent, and mints tokens (an **Access_Token** for the API and an
   **id_token**), plus a rotating **refresh token**.
3. **Resource_Server** — the Spring Boot API at `http://localhost:8080`. It is
   **stateless**: it trusts no session, only the JWT on each request. It
   validates the token's signature/issuer/audience/expiry and derives
   authorization purely from the token's `roles` claim.

The single most important idea in the whole project: **authorization is
claim-driven**. No database table decides who can do what — the `roles` claim in
the validated token does. Keep that in mind as you read.

Authoritative identity constants used throughout (you will see these repeatedly):

- Tenant ID: `76325907-a5db-46b1-9d5a-cbcca2e63e66`
- Client ID: `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c`
- API scope: `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c/access_as_user`
- Authority / issuer: `https://login.microsoftonline.com/76325907-a5db-46b1-9d5a-cbcca2e63e66/v2.0`

---

## Reading Path

### Stage 0 — The design (concepts first)

Start here so the code has context. These are not source files but they frame
everything that follows.

- **File:** `.kiro/specs/entra-oauth-fullstack/requirements.md`
  **Read for:** what the system must do, expressed as requirements (the `R#.#`
  tags you'll see referenced in code comments).
  **Look for:** the requirement numbers — code comments cite them (e.g. "R6.4"),
  so skimming these makes every later comment click into place.

- **File:** `.kiro/specs/entra-oauth-fullstack/design.md`
  **Read for:** the architecture, the token/login sequence, the data models, and
  the six "correctness properties" the tests later prove.
  **Look for:** the Identity & Token Flow section and the Correctness Properties
  list — these are the spine of the whole project.

> If you only have a few minutes, still skim `design.md`. Everything below is an
> implementation of it.

---

### Stage 1 — Backend foundation (build, boot, config, schema)

This stage teaches how a Spring Boot resource server is assembled and how its
schema is owned by migrations rather than the ORM.

1. **File:** `entra-backend/pom.xml`
   **Read for:** the dependency stack — web, security, OAuth2 resource server,
   JPA, PostgreSQL, Flyway, and the test libraries (jqwik).
   **Look for:** the XML comment on the **two Flyway dependencies** explaining
   the v10 modular split (why `flyway-database-postgresql` is mandatory), and the
   per-dependency comments describing each one's role in the pipeline.

2. **File:** `entra-backend/src/main/java/com/example/entraoauth/EntraOauthApplication.java`
   **Read for:** the application entry point and what happens at startup.
   **Look for:** the class Javadoc — it explains that **Flyway migrates on
   startup** and the **OAuth2 resource server is auto-configured** from
   `application.yml`. Two big behaviors, zero imperative wiring.

3. **File:** `entra-backend/src/main/resources/application.yml`
   **Read for:** how configuration alone turns this app into a token validator
   and a Flyway-managed persistence layer.
   **Look for:** the three commented concern-blocks — **token validation**
   (issuer-uri + OIDC discovery), **schema ownership** (`ddl-auto: validate`,
   Flyway), and **connection budgeting** (10s Hikari timeout). Also the custom
   `app.security.audiences` list (both accepted `aud` forms).

4. **File:** `entra-backend/src/main/resources/db/migration/V1__initial_schema.sql`
   **Read for:** the database schema and Flyway's naming/versioning convention.
   **Look for:** the file header on the `V<version>__<description>.sql`
   convention and transactional DDL, and the per-table comments stating that
   `app_user` / `access_audit` are **audit-only, never used for authorization**,
   and that `created_by` holds the token subject (oid/sub).

4a. **File:** `entra-backend/src/main/resources/db/migration/V2__item_category_and_history.sql`
   **Read for:** a **forward-only** schema change — adding the optional
   `category` column and the `item_history` change-log table on top of V1.
   **Look for:** the header explaining why migrations are **immutable** (editing
   an applied one breaks its Flyway checksum), and the `item_history` foreign key
   using **`ON DELETE SET NULL`** so a DELETE's history row survives the item's
   removal.

4b. **File:** `entra-backend/src/main/resources/db/migration/V3__public_ids.sql`
   **Read for:** the industry-standard pattern of **not exposing sequential
   primary keys over the API**. Adds an opaque `public_id` (UUIDv7) to `item` and
   `item_history`.
   **Look for:** the header's explanation of *why* (sequential ids leak row
   counts/ordering and are enumerable) and the emphasis that this is **defence in
   depth, not the primary control** — authorization stays claim-driven. Note it
   touches only the tables whose id crosses the wire (not `app_user` /
   `access_audit`), and that UUIDv7 is generated in the app because PG 17 has no
   native `uuidv7()`.

---

### Stage 2 — Token validation pipeline (the security core)

This is the heart of the backend: how an incoming bearer token is verified and
turned into authorities. Read these four in order — each builds on the previous.

5. **File:** `entra-backend/src/main/java/com/example/entraoauth/security/AudienceValidator.java`
   **Read for:** why the `aud` claim must be checked and how a failed check
   becomes a 401.
   **Look for:** the class Javadoc on the **"confused deputy"** problem (why
   Spring doesn't validate `aud` by default) and why two audience forms are
   accepted.

6. **File:** `entra-backend/src/main/java/com/example/entraoauth/security/RolesClaimConverter.java`
   **Read for:** the bridge from *identity* (the `roles` claim) to
   *authorization* (Spring authorities).
   **Look for:** the **`ROLE_` prefixing** explanation (why `hasRole('Admin')`
   needs `ROLE_Admin`), and each defensive branch (absent claim, non-array,
   non-string elements, distinct, case preserved).

7. **File:** `entra-backend/src/main/java/com/example/entraoauth/security/JwtConfig.java`
   **Read for:** how the decoder is built and how the validators are composed.
   **Look for:** `NimbusJwtDecoder.withIssuerLocation(...)` (JWKS via OIDC
   discovery at startup) and the `DelegatingOAuth2TokenValidator` chaining
   **issuer + timestamp(60s skew) + audience**. Also the **CORS** bean — read
   the comment on why a single explicit origin makes the browser block others.

8. **File:** `entra-backend/src/main/java/com/example/entraoauth/security/SecurityConfig.java`
   **Read for:** how all the pieces snap onto the HTTP filter chain.
   **Look for:** `@EnableMethodSecurity`, `STATELESS` sessions, CSRF disabled,
   `OPTIONS` preflight permitted, `oauth2ResourceServer().jwt(...)` wired to the
   converter, and the comments on the default **401 entry point** vs **403
   access-denied handler**. (Also note the `ObjectProvider<AccessAuditRepository>`
   — you'll understand why in Stage 6.)

---

### Stage 3 — Domain and endpoints (what the API actually exposes)

Now that requests are authenticated and authorized, see the business surface.

9. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/Item.java`
   **Read for:** JPA entity mapping and why it must match the Flyway schema
   exactly under `ddl-auto: validate`.
   **Look for:** the `@Column` mappings, `OffsetDateTime` for `TIMESTAMPTZ`, the
   comment that `created_by` is the token subject (provenance, not authz), and
   the `publicId` (UUIDv7) field with its `@PrePersist` generator — the opaque id
   exposed over the API instead of the internal sequential `id` (see V3, file 4b).

9a. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/ItemHistory.java`
   **Read for:** the change-log entity and a JPA **`@ManyToOne`** association back
   to `Item`.
   **Look for:** the **lazy, optional** `@ManyToOne` (nullable FK so a DELETE's
   record survives), `@Enumerated(EnumType.STRING)` for `change_type`, and the
   `getItemPublicId()` helper used to expose the parent item's opaque id (not the
   numeric FK).

10. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/ItemDto.java`
    **Read for:** why a DTO exists separately from the entity (wire shape vs
    persistence object).
    **Look for:** the exposed `id` is the item's **opaque `publicId` (UUID)**, not
    the internal numeric key — the wire never carries the sequential id.

10a. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/ItemHistoryDto.java`
    **Read for:** the read-only projection of a history row.
    **Look for:** both `id` and `itemId` are opaque UUIDs; `itemId` is the parent
    item's public id (or `null` if the item was later deleted).

11. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/CreateItemRequest.java`
    **Read for:** validated input at the API boundary (`@NotBlank`) and why the
    client does **not** supply `createdBy`.

12. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/UpdateItemRequest.java`
    **Read for:** the same validation pattern for updates; identity/provenance is
    not client-supplied.

13. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/ItemRepository.java`
    **Read for:** Spring Data JPA basics and where the persistence layer sits.
    **Look for:** the comment on `count()` being the **no-mutation oracle** the
    property test later uses, and `findByPublicId(UUID)` — the API resolves the
    opaque id to a row (never the internal numeric id).

13a. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/ItemHistoryRepository.java`
    **Read for:** **derived query methods** — Spring Data generates the query from
    the method name.
    **Look for:** `findByItem_PublicIdOrderByChangedAtDesc(...)` (navigating the
    association to the parent item's public id) and `findAllByOrderByChangedAtDesc()`
    for the global log.

14. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/ItemService.java`
    **Read for:** how the authenticated subject flows from the JWT into persisted
    rows, and how a **change-history row** is appended on every write.
    **Look for:** `SecurityContextHolder` reading the `Jwt` principal and
    `jwt.getSubject()` -> `created_by` (never from the request body), the
    `recordHistory(...)` calls on create/update/delete, and how reads/writes
    resolve items **by `publicId`**.

15. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/ItemController.java`
    **Read for:** the role-protected REST surface — the payoff of Stage 2.
    **Look for:** `@PreAuthorize("hasAnyRole('Viewer','Admin')")` on GET vs
    `@PreAuthorize("hasRole('Admin')")` on writes, the `{publicId}` (UUID) path
    variables, and the comment explaining how 401 (missing/invalid token) and 403
    (missing authority, no mutation) each arise **before** the method body runs.

15a. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/HistoryController.java`
    **Read for:** why the **global** change log lives in its own controller.
    **Look for:** the class-doc note that `@RequestMapping("/items")` on
    `ItemController` cannot produce the top-level `/history` path, so a small
    dedicated controller maps it; same `hasAnyRole('Viewer','Admin')` posture.

---

### Stage 4 — Backend tests (proving the security behavior)

These teach both the security guarantees and property-based testing. Read the
converter test first (simplest), then the pipeline tests, then the slice test.

16. **File:** `entra-backend/src/test/java/com/example/entraoauth/security/RolesClaimConverterPropertyTest.java`
    **Read for:** your first **property-based test** (jqwik). A property is a
    universal claim checked over 100 generated inputs.
    **Look for:** the generators (arbitrary role arrays, non-array/non-string
    claims) and the set-equality oracle for `ROLE_` + distinct values.

17. **File:** `entra-backend/src/test/java/com/example/entraoauth/security/InvalidTokenRejectionPropertyTest.java`
    **Read for:** how invalid tokens are rejected with 401 — the strongest test
    in the suite.
    **Look for:** the **local RSA signing** setup (mint real JWTs, no network),
    the four mutations (expired / bad `aud` / bad `iss` / bad signature) run
    against the **real** validator chain, and the `WWW-Authenticate: invalid_token`
    assertion.

18. **File:** `entra-backend/src/test/java/com/example/entraoauth/security/CorsPropertyTest.java`
    **Read for:** CORS preflight behavior as a property.
    **Look for:** the programmatic Spring context (no `jqwik-spring`), the mirror
    of the production CORS policy, and the assertion that only
    `http://localhost:4200` gets an `Access-Control-Allow-Origin` header.

19. **File:** `entra-backend/src/test/java/com/example/entraoauth/item/WriteAuthorizationPropertyTest.java`
    **Read for:** proving writes require `ROLE_Admin` and that a 403 causes **no
    mutation**.
    **Look for:** the fast/hermetic harness (real controller + fake service with
    a counter) and the before/after `count()` oracle across arbitrary role sets.

20. **File:** `entra-backend/src/test/java/com/example/entraoauth/item/ItemControllerSecurityTest.java`
    **Read for:** example-based (not property) `@WebMvcTest` security-slice tests.
    **Look for:** the concrete status matrix — Viewer 200/403, Admin 200/201,
    anonymous 401 — and how `spring-security-test`'s `jwt().authorities(...)`
    injects a caller without minting a real token.

20a. **File:** `entra-backend/src/test/java/com/example/entraoauth/item/HistoryControllerSecurityTest.java`
    **Read for:** the same slice-test technique applied to the global
    `/history` endpoint.
    **Look for:** the Viewer/Admin-200 vs anonymous-401 assertions mirroring the
    item reads (history is observational — both roles may read).

20b. **File:** `entra-backend/src/test/java/com/example/entraoauth/item/ItemServiceHistoryTest.java`
    **Read for:** a plain (non-Spring) **unit test** of the service's history
    writing, using Mockito.
    **Look for:** how a validated `Jwt` is placed in the `SecurityContextHolder`
    and the saved `ItemHistory` is asserted to carry the **actor from the token**
    (subject + `name` claim), never client input — and that a create/update/delete
    each writes the right `ChangeType`.

---

### Stage 5 — Frontend auth infrastructure (the browser token lifecycle)

Switch to the SPA. Start at the bootstrap so you know how the pieces are wired,
then read the auth building blocks in dependency order.

21. **File:** `EntraUi/src/main.ts`
    **Read for:** the Angular 19 standalone bootstrap entry point (tiny — sets
    the stage for `app.config.ts`).

22. **File:** `EntraUi/src/app/auth/msal.config.ts`
    **Read for:** the MSAL configuration and the identity constants.
    **Look for:** the long header comment on how MSAL performs **PKCE**
    (code_verifier/challenge), **state/nonce**, the **code->token exchange**, and
    **refresh-token rotation** — and *why a thin custom layer is added on top*.
    Also `cacheLocation: 'localStorage'` (origin-scoped refresh token).

23. **File:** `EntraUi/src/app/auth/auth-session.store.ts`
    **Read for:** signal-based state management in Angular 19.
    **Look for:** the `SessionState` shape, the `isAuthenticated` computed signal,
    and `needsProactiveRefresh(now)` with the **300000 ms (5 min)** window. Note
    the invariant: state is only ever mutated through this store.

24. **File:** `EntraUi/src/app/auth/token-refresh.service.ts`
    **Read for:** the crux of the browser token lifecycle — **single-flight**
    refresh and **bounded retry**.
    **Look for:** the `inFlight$` latch + `shareReplay(1)` + `finalize` (so N
    concurrent callers cause **one** token call), the `retry({ count: 3, ... })`
    with exponential backoff, the interaction-required bypass, and — importantly —
    the **`defer(() => from(acquireTokenSilent()))`** comment explaining why the
    call must be wrapped so retries actually re-attempt. (This was a real bug the
    Property 6 test caught; see Stage 7.)

25. **File:** `EntraUi/src/app/auth/auth-token.interceptor.ts`
    **Read for:** how every outbound API call gets a token and how refresh is
    triggered.
    **Look for:** the **proactive** path (refresh before a near-expiry request),
    the **reactive** path (one refresh + one retry on a 401 `invalid_token`
    challenge), the single-retry loop guard, and the URL scoping (only
    `/entra-backend` requests get the bearer — never MSAL's own origin). Read the
    `withBearer` and `isExpiredChallenge` helpers.

26. **File:** `EntraUi/src/app/auth/auth.guard.ts`
    **Read for:** functional route guards and the client-vs-server authority line.
    **Look for:** `authGuard` persisting `postLoginRedirect` before starting
    login, and the prominent disclaimer that `roleGuard` is a **client-side UX
    gate only — the server remains authoritative**.

---

### Stage 6 — Frontend components, wiring, and backend audit

See how the auth layer is consumed by real screens and how the whole SPA is
assembled. Then loop back to the backend audit filter that makes authorization
outcomes observable.

27. **File:** `EntraUi/src/app/home/home.component.ts`
    **Read for:** the **public** (unguarded) landing page and why it exists.
    **Look for:** the header comment explaining that making the app root public is
    what stops an unauthenticated visitor (or a just-logged-out user) from being
    instantly bounced to Entra — sign-in is initiated from the header instead.

27a. **File:** `EntraUi/src/app/login/login.component.ts`
    **Read for:** the login/redirect state machine.
    **Look for:** `handleRedirectPromise()` (state validation R1.9, error-param
    detection R1.10), `setSession(...)` on success, error handling that stays
    unauthenticated, and the return-to-requested-route logic reading the
    `postLoginRedirect` key the guard wrote.

28. **File:** `EntraUi/src/app/dashboard/dashboard.component.ts`
    **Read for:** a read screen (`GET /entra-backend/items`) using signals; the interceptor
    attaches the token transparently.
    **Look for:** the `ItemDto` interface (mirrors the backend DTO — note `id` is
    an **opaque string**, a UUID), the read-only change-history section
    (`GET /entra-backend/history`), and the loading/error/items signals.

29. **File:** `EntraUi/src/app/admin/admin.component.ts`
    **Read for:** an Admin-only write screen (`POST /entra-backend/items`), plus edit/delete, and
    graceful 403 handling.
    **Look for:** the signal-bound form, the `editingId`/`deletingId` signals
    keyed by the item's **opaque public id (string)**, and the comment that the
    **server** enforces Admin — the client just presents it nicely.

30. **File:** `EntraUi/src/app/app.routes.ts`
    **Read for:** how routes attach the guards.
    **Look for:** `login` (no guard — it *is* the callback), `dashboard`
    (`authGuard`), `admin` (`authGuard` + `roleGuard(['Admin'])`), and the
    comment on why guarding `login` would cause an infinite loop.

31. **File:** `EntraUi/src/app/app.config.ts`
    **Read for:** the composition root — how the interceptor, guards, and MSAL
    are provided to the running SPA.
    **Look for:** `provideHttpClient(withInterceptors([authTokenInterceptor]))`
    and the comment on why the **custom** interceptor is registered but MSAL's
    class-based `MsalInterceptor` is **not** (to avoid double token attachment).

31a. **File:** `EntraUi/src/app/app.component.ts`
    **Read for:** the **app shell** — the header/nav that hosts every routed view.
    **Look for:** how it reads `AuthSessionStore` to show **Sign in** vs **Sign
    out** + roles, shows nav links only when authenticated (and the **Admin** link
    only for the `Admin` role — UX only), and initiates sign-in/out via
    `TokenRefreshService`.

32. **File:** `entra-backend/src/main/java/com/example/entraoauth/audit/AccessAudit.java`
    **Read for:** the audit entity mapped to `access_audit`.
    **Look for:** the comment that this is **audit-only**, recording the outcome
    of a claim-driven decision — never making one.

33. **File:** `entra-backend/src/main/java/com/example/entraoauth/audit/AccessAuditRepository.java`
    **Read for:** the audit repository (and why `save` runs in its own tx).

34. **File:** `entra-backend/src/main/java/com/example/entraoauth/security/AccessAuditFilter.java`
    **Read for:** a servlet filter that records subject, resolved `ROLE_*`
    authorities, method, path, and status **after** authentication.
    **Look for:** why it runs *after* the chain (to know the status), why a write
    failure must never break the request, and how it ties back to
    `SecurityConfig`'s `ObjectProvider` wiring (Stage 2, file 8).

---

### Stage 7 — Frontend tests (property-based + example-based)

Finish the code tour with the browser-side tests. These teach fast-check and the
async testing techniques (fakeAsync, HttpTestingController).

35. **File:** `EntraUi/src/app/auth/token-refresh.service.spec.ts`
    **Read for:** **Property 5** — refresh is single-flight and rotates the token.
    **Look for:** how N concurrent `refresh()` calls are fired so they race the
    real `inFlight$` latch, and the assertion of exactly one token call.

36. **File:** `EntraUi/src/app/auth/token-refresh.retry.spec.ts`
    **Read for:** **Property 6** — transient failures retried at most 3 times,
    then re-auth. This is the test that **found the `defer` bug** (Stage 5,
    file 24).
    **Look for:** the `driveRetryChain()` helper and its comment on interleaving
    `flushMicrotasks()` with per-retry `tick()` — the microtask/macrotask
    ordering is the subtle lesson here.

37. **File:** `EntraUi/src/app/auth/auth-token.interceptor.spec.ts`
    **Read for:** example-based interceptor tests with `HttpTestingController`.
    **Look for:** the scenarios — bearer attach, non-API pass-through, proactive
    refresh, single reactive refresh + one retry, single-flight coalescing, and
    failure clearing the session.

---

### Stage 8 — Run it and observe (make the theory concrete)

38. **File:** `GUIDE/TOKEN-VERIFICATION-GUIDE.md`
    **Read for:** how to run the stack and *see* everything you just read.
    **Look for:** the three manual procedures — **jwt.io claim inspection**
    (see the `roles`/`aud`/`iss`/`exp` claims), **Network-tab refresh
    observation** (watch `grant_type=refresh_token`, rotation, single-flight
    queuing), and **server-side authority confirmation** (query the
    `access_audit` table and run the status-matrix curl commands). Also note the
    safety note about not pasting sensitive tokens into third-party sites.

---

## Suggested schedules

**Fast path (~60-90 min): the essential spine.**
Read: design.md -> application.yml -> AudienceValidator -> RolesClaimConverter ->
JwtConfig -> SecurityConfig -> ItemController -> msal.config.ts ->
token-refresh.service.ts -> auth-token.interceptor.ts -> login.component.ts ->
TOKEN-VERIFICATION-GUIDE.md. This gives you the complete request/token story end to end.

**Complete path (~3-4 hours): every file, in the order above (Stages 0-8).**
Recommended if you want to genuinely learn all the covered subjects, including
the testing techniques.

**By topic:**

- *Just OAuth2/OIDC + JWT validation:* Stage 0, then Stage 2 (files 5-8).
- *Just Spring Boot resource server + persistence:* Stage 1 and Stage 3.
- *Just the browser token lifecycle:* Stage 5 (files 22-25) and Stage 7.
- *Just testing techniques:* Stage 4 and Stage 7.

---

## File Manifest (the print list, in order)

This is the canonical, ordered list the "read it like a book" commands consume.
It is also shipped as `GUIDE/reading-order.txt` (same order, one path per line).
Stage 0 and the final stop are Markdown docs; everything else is a source file.

```
# Stage 0 — design (concepts first)
.kiro/specs/entra-oauth-fullstack/requirements.md
.kiro/specs/entra-oauth-fullstack/design.md

# Stage 1 — backend foundation
entra-backend/pom.xml
entra-backend/src/main/java/com/example/entraoauth/EntraOauthApplication.java
entra-backend/src/main/resources/application.yml
entra-backend/src/main/resources/db/migration/V1__initial_schema.sql
entra-backend/src/main/resources/db/migration/V2__item_category_and_history.sql
entra-backend/src/main/resources/db/migration/V3__public_ids.sql

# Stage 2 — token validation pipeline
entra-backend/src/main/java/com/example/entraoauth/security/AudienceValidator.java
entra-backend/src/main/java/com/example/entraoauth/security/RolesClaimConverter.java
entra-backend/src/main/java/com/example/entraoauth/security/JwtConfig.java
entra-backend/src/main/java/com/example/entraoauth/security/SecurityConfig.java

# Stage 3 — domain and endpoints
entra-backend/src/main/java/com/example/entraoauth/item/Item.java
entra-backend/src/main/java/com/example/entraoauth/item/ItemHistory.java
entra-backend/src/main/java/com/example/entraoauth/item/ItemDto.java
entra-backend/src/main/java/com/example/entraoauth/item/ItemHistoryDto.java
entra-backend/src/main/java/com/example/entraoauth/item/CreateItemRequest.java
entra-backend/src/main/java/com/example/entraoauth/item/UpdateItemRequest.java
entra-backend/src/main/java/com/example/entraoauth/item/ItemRepository.java
entra-backend/src/main/java/com/example/entraoauth/item/ItemHistoryRepository.java
entra-backend/src/main/java/com/example/entraoauth/item/ItemService.java
entra-backend/src/main/java/com/example/entraoauth/item/ItemController.java
entra-backend/src/main/java/com/example/entraoauth/item/HistoryController.java

# Stage 4 — backend tests
entra-backend/src/test/java/com/example/entraoauth/security/RolesClaimConverterPropertyTest.java
entra-backend/src/test/java/com/example/entraoauth/security/InvalidTokenRejectionPropertyTest.java
entra-backend/src/test/java/com/example/entraoauth/security/CorsPropertyTest.java
entra-backend/src/test/java/com/example/entraoauth/item/WriteAuthorizationPropertyTest.java
entra-backend/src/test/java/com/example/entraoauth/item/ItemControllerSecurityTest.java
entra-backend/src/test/java/com/example/entraoauth/item/HistoryControllerSecurityTest.java
entra-backend/src/test/java/com/example/entraoauth/item/ItemServiceHistoryTest.java

# Stage 5 — frontend auth infrastructure
EntraUi/src/main.ts
EntraUi/src/app/auth/msal.config.ts
EntraUi/src/app/auth/auth-session.store.ts
EntraUi/src/app/auth/token-refresh.service.ts
EntraUi/src/app/auth/auth-token.interceptor.ts
EntraUi/src/app/auth/auth.guard.ts

# Stage 6 — frontend components, wiring, and backend audit
EntraUi/src/app/home/home.component.ts
EntraUi/src/app/login/login.component.ts
EntraUi/src/app/dashboard/dashboard.component.ts
EntraUi/src/app/admin/admin.component.ts
EntraUi/src/app/app.routes.ts
EntraUi/src/app/app.config.ts
EntraUi/src/app/app.component.ts
entra-backend/src/main/java/com/example/entraoauth/audit/AccessAudit.java
entra-backend/src/main/java/com/example/entraoauth/audit/AccessAuditRepository.java
entra-backend/src/main/java/com/example/entraoauth/security/AccessAuditFilter.java

# Stage 7 — frontend tests
EntraUi/src/app/auth/token-refresh.service.spec.ts
EntraUi/src/app/auth/token-refresh.retry.spec.ts
EntraUi/src/app/auth/auth-token.interceptor.spec.ts

# Stage 8 — run it and observe
GUIDE/TOKEN-VERIFICATION-GUIDE.md
```

---

## A note on the comments

Throughout the code you will see requirement tags like `R6.4` or `R4.6`. These
map to `.kiro/specs/entra-oauth-fullstack/requirements.md`. When a comment says
"(R6.4)", it means "this line exists to satisfy requirement 6.4" — a handy way to
trace any line of code back to the behavior it implements.

Happy reading. Follow the stages in order and the whole system — and the ideas
behind it — will assemble itself in your head one file at a time.
