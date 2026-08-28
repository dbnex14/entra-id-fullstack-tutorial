# Learning Guide — Entra ID OAuth2 Full-Stack Reference

This guide is a **guided reading path** through the whole project. Every source
file in this repository was written with extensive inline comments that explain
*why* each line exists and how the identity payload (tokens, claims, roles)
flows down the stack. The fastest way to learn the subjects covered here is to
open the files **in the order below** and read them top to bottom, comments and
all.

You do not need to run anything to learn from this path — reading the files in
order is enough. When you *do* want to see it working, the final stop
(`entra-backend/VERIFICATION.md`) tells you how.

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
   **Look for:** the `@Column` mappings, `OffsetDateTime` for `TIMESTAMPTZ`, and
   the comment that `created_by` is the token subject (provenance, not authz).

10. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/ItemDto.java`
    **Read for:** why a DTO exists separately from the entity (wire shape vs
    persistence object).

11. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/CreateItemRequest.java`
    **Read for:** validated input at the API boundary (`@NotBlank`) and why the
    client does **not** supply `createdBy`.

12. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/UpdateItemRequest.java`
    **Read for:** the same validation pattern for updates; identity/provenance is
    not client-supplied.

13. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/ItemRepository.java`
    **Read for:** Spring Data JPA basics and where the persistence layer sits.
    **Look for:** the comment on `count()` being the **no-mutation oracle** the
    property test later uses.

14. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/ItemService.java`
    **Read for:** how the authenticated subject flows from the JWT into persisted
    rows.
    **Look for:** `SecurityContextHolder` reading the `Jwt` principal and
    `jwt.getSubject()` -> `created_by` (never from the request body), plus
    timestamp management.

15. **File:** `entra-backend/src/main/java/com/example/entraoauth/item/ItemController.java`
    **Read for:** the role-protected REST surface — the payoff of Stage 2.
    **Look for:** `@PreAuthorize("hasAnyRole('Viewer','Admin')")` on GET vs
    `@PreAuthorize("hasRole('Admin')")` on writes, and the comment explaining how
    401 (missing/invalid token) and 403 (missing authority, no mutation) each
    arise **before** the method body runs.

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
    `/api` requests get the bearer — never MSAL's own origin). Read the
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

27. **File:** `EntraUi/src/app/login/login.component.ts`
    **Read for:** the login/redirect state machine.
    **Look for:** `handleRedirectPromise()` (state validation R1.9, error-param
    detection R1.10), `setSession(...)` on success, error handling that stays
    unauthenticated, and the return-to-requested-route logic reading the
    `postLoginRedirect` key the guard wrote.

28. **File:** `EntraUi/src/app/dashboard/dashboard.component.ts`
    **Read for:** a read screen (`GET /api/items`) using signals; the interceptor
    attaches the token transparently.
    **Look for:** the `ItemDto` interface (mirrors the backend DTO) and the
    loading/error/items signals.

29. **File:** `EntraUi/src/app/admin/admin.component.ts`
    **Read for:** an Admin-only write screen (`POST /api/items`) and graceful 403
    handling.
    **Look for:** the signal-bound form and the comment that the **server**
    enforces Admin — the client just presents it nicely.

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

38. **File:** `entra-backend/VERIFICATION.md`
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
VERIFICATION.md. This gives you the complete request/token story end to end.

**Complete path (~3-4 hours): every file, in the order above (Stages 0-8).**
Recommended if you want to genuinely learn all the covered subjects, including
the testing techniques.

**By topic:**

- *Just OAuth2/OIDC + JWT validation:* Stage 0, then Stage 2 (files 5-8).
- *Just Spring Boot resource server + persistence:* Stage 1 and Stage 3.
- *Just the browser token lifecycle:* Stage 5 (files 22-25) and Stage 7.
- *Just testing techniques:* Stage 4 and Stage 7.

---

## A note on the comments

Throughout the code you will see requirement tags like `R6.4` or `R4.6`. These
map to `.kiro/specs/entra-oauth-fullstack/requirements.md`. When a comment says
"(R6.4)", it means "this line exists to satisfy requirement 6.4" — a handy way to
trace any line of code back to the behavior it implements.

Happy reading. Follow the stages in order and the whole system — and the ideas
behind it — will assemble itself in your head one file at a time.
