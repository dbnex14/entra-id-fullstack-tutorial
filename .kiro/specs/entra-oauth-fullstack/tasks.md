# Implementation Plan: Entra ID OAuth Full-Stack Reference

## Overview

This plan converts the design into a series of incremental, code-generation prompts that build
a decoupled three-tier application secured by Microsoft Entra ID using the OAuth2 Authorization
Code Flow with PKCE. Each task builds on the previous ones and ends by wiring new code into the
running system so there is no orphaned code.

The implementation spans two existing project roots:

- **Backend** — `entra-backend/` (Spring Boot 3, JDK 21). Java sources under
  `entra-backend/src/main/java/com/example/entraoauth/`, resources under
  `entra-backend/src/main/resources/`, tests under `entra-backend/src/test/java/com/example/entraoauth/`.
- **Frontend** — `EntraUi/` (Angular 19 Standalone). Sources under `EntraUi/src/app/`.

**Instructional intent (applies to every code task):** Every generated class, configuration
key, SQL statement, and TypeScript property MUST carry extensive inline comments explaining how
the identity payload, tokens, and data move down the stack. All code MUST be fully realized —
no shorthand, no elided lines, no `// ...` placeholders. Frontend code MUST use modern Angular
19 conventions: `inject()` for dependency injection and signals for state.

**Authoritative identity constants** (referenced across tasks):

- Tenant_ID: `76325907-a5db-46b1-9d5a-cbcca2e63e66`
- Client_ID: `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c`
- API_Scope: `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c/access_as_user`
- Authority / Issuer: `https://login.microsoftonline.com/76325907-a5db-46b1-9d5a-cbcca2e63e66/v2.0`
- Accepted audiences: `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c` or `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c`
- Database: `jdbc:postgresql://localhost:5432/my_workspace`, user `postgres`, password `postgres`
- Frontend origin: `http://localhost:4200`; Backend: `http://localhost:8080`

## Tasks

- [~] 1. Backend foundation: build, configuration, and initial schema
  - [x] 1.1 Create the Maven build file `entra-backend/pom.xml`
    - Define Spring Boot 3 parent, `<java.version>21</java.version>`, and `<flyway.version>10.20.1</flyway.version>`.
    - Add dependencies exactly as in the design's "Maven Dependencies" excerpt: `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-data-jpa`, `postgresql` (runtime), `flyway-core`, `flyway-database-postgresql`, `spring-boot-starter-test` (test), `spring-security-test` (test), and `net.jqwik:jqwik` (test).
    - Include an XML comment on the two Flyway dependencies explaining the v10 modular split: `flyway-core` alone cannot handle PostgreSQL 17, and `flyway-database-postgresql` (v10+) is mandatory or startup fails with "No database found to handle" (R7.8).
    - Comment each dependency describing its role in the token-validation / persistence pipeline.
    - _Requirements: 7.8_
    - _Design: Components and Interfaces > Backend > Maven Dependencies_

  - [x] 1.2 Create the Spring Boot application entry point `entra-backend/src/main/java/com/example/entraoauth/EntraOauthApplication.java`
    - Standard `@SpringBootApplication` main class in package `com.example.entraoauth`.
    - Add a class-level comment describing that Flyway migrates on startup and the OAuth2 resource server wiring is auto-configured from `application.yml`.
    - _Requirements: 6.1, 7.1_
    - _Design: Architecture > Architectural Principles_

  - [x] 1.3 Create `entra-backend/src/main/resources/application.yml`
    - Set `server.port: 8080`.
    - Configure `spring.security.oauth2.resourceserver.jwt.issuer-uri` to the Authority; comment that OIDC discovery derives the JWKS URI automatically (R6.1) and include the commented-out explicit `jwk-set-uri` override.
    - Configure `spring.datasource` url/username/password and `hikari.connection-timeout: 10000` (10s budget, R7.1/R7.2).
    - Configure `spring.jpa.hibernate.ddl-auto: validate` (schema owned by Flyway) and `open-in-view: false`.
    - Configure `spring.flyway` with `enabled: true`, `locations: classpath:db/migration`, `baseline-on-migrate: false`, `validate-on-migrate: true` (checksum drift halts startup, R7.6).
    - Configure custom `app.security.audiences` list with both accepted audience values (R6.4).
    - Comment every block explaining how it participates in token validation, schema ownership, or connection budgeting.
    - _Requirements: 6.1, 6.4, 7.1, 7.2, 7.6_
    - _Design: Components and Interfaces > Backend > application.yml; Database / Flyway Design_

  - [x] 1.4 Create the initial Flyway migration `entra-backend/src/main/resources/db/migration/V1__initial_schema.sql`
    - Follow the `V<version>__<description>.sql` naming convention (R7.9).
    - Create tables `item`, `app_user`, and `access_audit` exactly as specified in the design's initial-migration SQL, including all columns, constraints, defaults, and the two indexes (`idx_item_created_by`, `idx_access_audit_subject`).
    - Comment each table and security-relevant column, stating that `app_user`/`access_audit` are for audit/profile only and never used for authorization (authorization is claim-driven per R2), and that `created_by` holds the token subject (oid/sub).
    - _Requirements: 7.3, 7.4, 7.9_
    - _Design: Database / Flyway Design > Initial migration_

- [x] 2. Backend security: filter chain, JWT decoding, claim conversion, and CORS
  - [x] 2.1 Implement the audience validator `entra-backend/src/main/java/com/example/entraoauth/security/AudienceValidator.java`
    - Implement `OAuth2TokenValidator<Jwt>` that accepts the token only if the `aud` claim contains one of the configured audiences (Client_ID or `api://Client_ID`); otherwise return `OAuth2Error("invalid_token")` (R6.4, R6.8).
    - Comment how a failed audience check maps to a 401 with a `WWW-Authenticate` challenge and why Spring does not validate `aud` by default.
    - _Requirements: 6.4, 6.8_
    - _Design: Components and Interfaces > Backend > JWT Decoder, Issuer/Audience Validation_

  - [x] 2.2 Implement the roles-claim converter `entra-backend/src/main/java/com/example/entraoauth/security/RolesClaimConverter.java`
    - Implement `Converter<Jwt, Collection<GrantedAuthority>>` reading the `roles` claim: if absent or not a `Collection`, return an empty list (R2.5, R2.6); filter to strings, ignore non-strings (R2.6), take distinct values (R2.2), map each to `new SimpleGrantedAuthority("ROLE_" + value)` preserving case (R2.2-R2.4).
    - Comment each defensive branch and explain the `ROLE_` prefixing scheme that `hasRole(...)` relies on.
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6_
    - _Design: Components and Interfaces > Backend > roles claim -> authorities_

  - [x] 2.3 Implement the JWT decoder and CORS beans `entra-backend/src/main/java/com/example/entraoauth/security/JwtConfig.java`
    - Provide a `JwtDecoder` bean built from `issuer-uri` that loads JWKS at startup (R6.1, R6.2) and sets a `DelegatingOAuth2TokenValidator` composed of `JwtValidators.createDefaultWithIssuer(issuer)` (iss, R6.5), a `JwtTimestampValidator(Duration.ofSeconds(60))` (exp + 60s skew, R3.1), and the `AudienceValidator` from 2.1 (R6.4).
    - Provide the `JwtAuthenticationConverter` bean wiring `RolesClaimConverter` from 2.2 (R6.6).
    - Provide the `CorsConfigurationSource` bean: allowed origin `http://localhost:4200` (R5.1, R5.6), methods GET/POST/PUT/DELETE/OPTIONS (R5.3), headers Authorization/Content-Type (R5.4), allow-credentials true (R5.5).
    - Comment how each validator rejects a token (401 mapping) and how the single explicit origin causes the browser to block other origins.
    - _Requirements: 3.1, 5.1, 5.3, 5.4, 5.5, 5.6, 6.1, 6.2, 6.4, 6.5, 6.6_
    - _Design: Components and Interfaces > Backend > JWT Decoder ... and CORS Configuration_

  - [x] 2.4 Implement the security filter chain `entra-backend/src/main/java/com/example/entraoauth/security/SecurityConfig.java`
    - `@Configuration @EnableMethodSecurity`; define the `SecurityFilterChain` bean wiring CORS source (R5), disabling CSRF, `SessionCreationPolicy.STATELESS` (R6, R8), permitting `OPTIONS /**` (preflight, R5.2) and `/actuator/health`, authenticating all other requests (R8.4), and enabling `oauth2ResourceServer().jwt()` with the converter from 2.3 (R6).
    - Comment that the default `BearerTokenAuthenticationEntryPoint` returns 401 + `WWW-Authenticate` (R3.2, R8.5) and the default `BearerTokenAccessDeniedHandler` returns 403 (R2.7, R8.3).
    - _Requirements: 2.7, 3.2, 5.2, 6.3, 6.7, 8.3, 8.4, 8.5_
    - _Design: Components and Interfaces > Backend > SecurityFilterChain_

- [ ] 3. Backend domain: entity, DTOs, repository, service, and controller
  - [x] 3.1 Implement the JPA entity and DTOs `entra-backend/src/main/java/com/example/entraoauth/item/Item.java`, `ItemDto.java`, `CreateItemRequest.java`, `UpdateItemRequest.java`
    - `Item` entity mapped to table `item` with fields id/name/description/createdBy/createdAt/updatedAt and column mappings matching V1 (R7 schema alignment).
    - `ItemDto` record and validated `CreateItemRequest`/`UpdateItemRequest` records with `@NotBlank name`.
    - Comment that `createdBy` is populated from the token subject (oid/sub), tying persisted data back to identity.
    - _Requirements: 8.1, 8.2_
    - _Design: Data Models > Backend domain / DTOs_

  - [x] 3.2 Implement the repository `entra-backend/src/main/java/com/example/entraoauth/item/ItemRepository.java`
    - `JpaRepository<Item, Long>`; comment that it backs both read and write endpoints and that `count()` is used by property tests to assert no-mutation on 403.
    - _Requirements: 8.1, 8.2, 8.3_
    - _Design: Data Models_

  - [x] 3.3 Implement the service `entra-backend/src/main/java/com/example/entraoauth/item/ItemService.java`
    - `findAll()` returning `List<ItemDto>`, `create(CreateItemRequest)` and `update(long, UpdateItemRequest)` returning `ItemDto`; set `createdBy` from the authenticated principal (token subject) and manage timestamps.
    - Comment how the subject flows from the validated JWT into persisted rows.
    - _Requirements: 8.1, 8.2_
    - _Design: Components and Interfaces > Backend > Role-Protected REST Controller_

  - [ ] 3.4 Implement the controller `entra-backend/src/main/java/com/example/entraoauth/item/ItemController.java`
    - `@RestController @RequestMapping("/api/items")`. `GET` with `@PreAuthorize("hasAnyRole('Viewer','Admin')")` returns 200 (R8.1); `POST` with `@PreAuthorize("hasRole('Admin')")` returns 201 (R8.2); `PUT /{id}` with `@PreAuthorize("hasRole('Admin')")` (R8.2, R8.3).
    - Comment that `hasRole('Admin')` checks `ROLE_Admin` from the converter, that missing authority yields 403 with no mutation (R8.3), and that missing/invalid tokens are stopped earlier with 401 (R8.4, R8.5).
    - _Requirements: 8.1, 8.2, 8.3_
    - _Design: Components and Interfaces > Backend > Role-Protected REST Controller_

- [ ] 4. Backend property-based and security-slice tests
  - [ ]* 4.1 Write the roles-converter property test `entra-backend/src/test/java/com/example/entraoauth/security/RolesClaimConverterPropertyTest.java`
    - **Property 1: Role claim maps to exactly the prefixed distinct set**
    - **Validates: Requirements 2.2, 2.3, 2.4, 2.5, 2.6**
    - jqwik `@Property(tries = 100)`; generate arbitrary role arrays, assert authorities equal `ROLE_` + distinct raw values (case preserved); second property asserts absent/non-array claims yield empty authorities.
    - _Design: Correctness Properties > Property 1_

  - [ ]* 4.2 Write the invalid-token rejection property test `entra-backend/src/test/java/com/example/entraoauth/security/InvalidTokenRejectionPropertyTest.java`
    - **Property 2: Invalid tokens are rejected with 401**
    - **Validates: Requirements 3.1, 6.4, 6.5, 6.7, 6.8**
    - jqwik + MockMvc; generator mutates one dimension (expired / bad aud / bad iss / bad signature); assert 401 and `WWW-Authenticate` contains `invalid_token`.
    - _Design: Correctness Properties > Property 2_

  - [ ]* 4.3 Write the CORS property test `entra-backend/src/test/java/com/example/entraoauth/security/CorsPropertyTest.java`
    - **Property 3: CORS headers honor only the allowed origin**
    - **Validates: Requirements 5.1, 5.5, 5.6**
    - jqwik + MockMvc OPTIONS preflight; for `http://localhost:4200` assert ACAO + allow-credentials headers; for other origins assert no ACAO header.
    - _Design: Correctness Properties > Property 3_

  - [ ]* 4.4 Write the write-authorization property test `entra-backend/src/test/java/com/example/entraoauth/item/WriteAuthorizationPropertyTest.java`
    - **Property 4: Write endpoints require ROLE_Admin**
    - **Validates: Requirements 8.2, 8.3**
    - jqwik + MockMvc with `spring-security-test` `jwt().authorities(...)`; arbitrary role subsets; Admin -> 200/201 and count+1, non-Admin -> 403 and count unchanged.
    - _Design: Correctness Properties > Property 4_

  - [ ]* 4.5 Write the security-slice unit tests `entra-backend/src/test/java/com/example/entraoauth/item/ItemControllerSecurityTest.java`
    - `@WebMvcTest` + MockMvc concrete cases: Viewer -> 200 read / 403 write, Admin -> 200 read / 201 write, anonymous -> 401 on any protected endpoint.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_
    - _Design: Testing Strategy > Automated tests > Backend security slice_

- [ ] 5. Checkpoint - backend complete
  - Ensure all backend tests pass, ask the user if questions arise.

- [ ] 6. Frontend auth infrastructure
  - [ ] 6.1 Create MSAL and identity configuration `EntraUi/src/app/auth/msal.config.ts`
    - Export identity constants `TENANT_ID`, `CLIENT_ID`, `API_SCOPE`, `AUTHORITY` (R1.3).
    - Implement `msalInstanceFactory()` building a `PublicClientApplication` with `clientId`, `authority`, `redirectUri: 'http://localhost:4200'`, `navigateToLoginRequestUrl: true` (R1.7), and `cacheLocation: 'localStorage'` (refresh token scoped to origin, R4.7).
    - Implement `msalGuardConfigFactory()` and `msalInterceptorConfigFactory()` requesting scopes `api://.../access_as_user`, `openid`, `profile`, `offline_access` (R1.3).
    - Comment how PKCE, code_verifier/challenge, and refresh-token rotation are handled by MSAL under the hood, and why a thin custom layer is added on top.
    - _Requirements: 1.2, 1.3, 4.7_
    - _Design: Components and Interfaces > Frontend > Standalone Bootstrap and Providers_

  - [ ] 6.2 Create the signal session store `EntraUi/src/app/auth/auth-session.store.ts`
    - `@Injectable({ providedIn: 'root' })` holding a `signal<SessionState>` with `{ authenticated, accessToken, expiresAt (absolute epoch ms), roles }`; expose `state` readonly signal and `isAuthenticated` computed (R1.5).
    - Implement `setSession(token, expiresAt, roles)` (R4.3), `clear()` (R3.5, R4.5), and `needsProactiveRefresh(now)` returning true within 300000 ms of expiry (R3.4, R4.1).
    - Comment how each mutation reflects the token lifecycle and that state is only mutated through this store.
    - _Requirements: 1.5, 3.4, 3.5, 4.1, 4.3, 4.5_
    - _Design: Components and Interfaces > Frontend > Signal-based Session Store_

  - [ ] 6.3 Create the token refresh service `EntraUi/src/app/auth/token-refresh.service.ts`
    - `@Injectable({ providedIn: 'root' })` using `inject(MsalService)` and `inject(AuthSessionStore)`; implement single-flight `refresh()` with an `inFlight$` latch that queues concurrent callers (R4.4), calls `acquireTokenSilent({ scopes: [API_SCOPE] })`, retries transient failures at most 3 times with increasing delay (R4.6), updates the store with new access token + absolute expiry + rotated refresh token handling (R4.2, R4.3, R4.7), clears session and begins interactive login on failure (R4.5), and releases the latch with `finalize` + `shareReplay(1)`.
    - Implement `beginInteractiveLogin()` calling `acquireTokenRedirect` (R4.5).
    - Comment how the single-flight latch guarantees exactly one token-endpoint call and how rotation is persisted by MSAL.
    - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_
    - _Design: Components and Interfaces > Frontend > token-refresh.service_

  - [ ] 6.4 Create the HTTP auth interceptor `EntraUi/src/app/auth/auth-token.interceptor.ts`
    - Functional `HttpInterceptorFn` using `inject()`; attach bearer token via `withBearer`, proactively refresh before the request when `needsProactiveRefresh()` (R3.4, R4.1), on 401 expired challenge perform at most one background refresh (R3.3) and retry the original request exactly once (R3.6), and on refresh failure clear session + begin interactive login (R3.5, R4.5).
    - Include helper functions `withBearer(req, token)` and `isExpiredChallenge(err)`; comment the proactive vs reactive paths and the single-retry guarantee.
    - _Requirements: 3.3, 3.4, 3.5, 3.6, 4.1, 4.5_
    - _Design: Components and Interfaces > Frontend > HTTP Interceptor_

  - [ ] 6.5 Create the route guards `EntraUi/src/app/auth/auth.guard.ts`
    - `authGuard: CanActivateFn` that returns true when authenticated, else persists `state.url` to `sessionStorage` under `postLoginRedirect` (R1.1, R1.7) and calls `beginInteractiveLogin()` (R1.1).
    - `roleGuard(required: string[]): CanActivateFn` client-side gate checking `store.state().roles`; comment that the server remains authoritative.
    - _Requirements: 1.1, 1.7_
    - _Design: Components and Interfaces > Frontend > Routing and Role Guard_

- [ ] 7. Frontend components, providers, and routing (wiring)
  - [ ] 7.1 Create the login/redirect-handling component `EntraUi/src/app/login/login.component.ts` (+ template)
    - Standalone component using `inject()`; handle the redirect callback: verify returned `state` matches sent `state` (R1.9), detect authorization `error` params (R1.10), exchange code for tokens on success and populate the session store (R1.4, R1.5), on token-exchange failure show an error and stay unauthenticated (R1.6), then redirect to the persisted `postLoginRedirect` route or the default landing route (R1.7, R1.8).
    - Comment each branch of the login state machine and how the session transitions.
    - _Requirements: 1.1, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10_
    - _Design: Identity & Token Flow > Login sequence_

  - [ ] 7.2 Create the dashboard and admin components `EntraUi/src/app/dashboard/dashboard.component.ts` and `EntraUi/src/app/admin/admin.component.ts` (+ templates)
    - Dashboard: read `/api/items` via `HttpClient` (interceptor attaches bearer) and render results using signals.
    - Admin: perform a write (`POST /api/items`) demonstrating the Admin-only path; render 403 handling for non-admins.
    - Comment how the bearer token and roles drive what each component can do.
    - _Requirements: 8.1, 8.2, 1.5_
    - _Design: Components and Interfaces > Frontend_

  - [ ] 7.3 Update the routes `EntraUi/src/app/app.routes.ts`
    - Define routes: `login`, `dashboard` guarded by `authGuard` (R1.1), `admin` guarded by `authGuard` + `roleGuard(['Admin'])`, default redirect to `dashboard` (R1.8).
    - Comment how unauthenticated access triggers login and how the role guard gates admin.
    - _Requirements: 1.1, 1.8_
    - _Design: Components and Interfaces > Frontend > Routing and Role Guard_

  - [ ] 7.4 Update the application config `EntraUi/src/app/app.config.ts`
    - Assemble providers: `provideRouter(routes)`, `provideHttpClient(withInterceptors([authTokenInterceptor]))`, MSAL providers (`MSAL_INSTANCE`, `MSAL_GUARD_CONFIG`, `MSAL_INTERCEPTOR_CONFIG`) from the factories in 6.1, plus `MsalService`, `MsalGuard`, `MsalBroadcastService`.
    - Comment how this bootstrap wires the interceptor, guards, and MSAL into the running SPA.
    - _Requirements: 1.1, 1.3, 3.3, 4.1_
    - _Design: Components and Interfaces > Frontend > Standalone Bootstrap and Providers_

- [ ] 8. Frontend property-based tests
  - [ ]* 8.1 Write the single-flight refresh property test `EntraUi/src/app/auth/token-refresh.service.spec.ts`
    - **Property 5: Refresh is single-flight and rotates the stored token**
    - **Validates: Requirements 4.2, 4.4**
    - fast-check `numRuns: 100`; fire N concurrent `refresh()` calls, assert exactly one token-endpoint call, all callers observe the same access token, and a rotated refresh token replaces the stored one.
    - _Design: Correctness Properties > Property 5_

  - [ ]* 8.2 Write the retry-then-reauth property test `EntraUi/src/app/auth/token-refresh.retry.spec.ts`
    - **Property 6: Transient refresh failures are retried at most three times, then re-auth**
    - **Validates: Requirements 4.6**
    - fast-check `numRuns: 100`; vary the number of leading transient failures; assert at most 4 total attempts, recovery within budget avoids re-auth, exhausted budget clears session and starts interactive login exactly once.
    - _Design: Correctness Properties > Property 6_

  - [ ]* 8.3 Write the interceptor unit tests `EntraUi/src/app/auth/auth-token.interceptor.spec.ts`
    - Use `HttpTestingController`: assert bearer header attached, proactive refresh fires within threshold, single reactive refresh on 401 expired with exactly one retry, concurrent requests queue behind one in-flight refresh, transient failures retry at most three times before clearing the session.
    - _Requirements: 3.3, 3.4, 3.5, 3.6, 4.1, 4.4, 4.6_
    - _Design: Testing Strategy > Automated tests > Frontend interceptor tests_

- [ ] 9. Integration verification scripts and instructional guide
  - [ ] 9.1 Create the verification guide `entra-backend/VERIFICATION.md` (or `docs/VERIFICATION.md`)
    - Document the four verification procedures from the design as step-by-step instructions:
      1. Manual claim inspection with jwt.io — capture the encoded Access_Token from the Network tab `Authorization: Bearer` header or `AuthSessionStore.state().accessToken`, paste into jwt.io, confirm the `roles` claim array contains `Admin`/`Viewer`, and verify `aud`, `iss`, and future `exp` (include the safety note about not pasting sensitive tokens).
      2. Browser Network-tab observation of the background refresh — filter the token endpoint, trigger proactive/reactive refresh, confirm `grant_type=refresh_token`, rotated `refresh_token`, new `access_token`, `expires_in`, and single-call request queuing.
      3. Server-side authority confirmation — log resolved authorities and inspect the `access_audit` table's `authorities` column, then exercise the status matrix (anonymous 401, Viewer 200/403, Admin 200/201).
    - _Requirements: 2.2, 2.3, 2.4, 4.2, 4.3, 4.4, 4.7, 6.4, 6.5, 8.1, 8.2, 8.3, 8.4_
    - _Design: Testing Strategy > Manual verification (sections 2, 3, 4)_

  - [ ] 9.2 Implement the access-audit request filter `entra-backend/src/main/java/com/example/entraoauth/security/AccessAuditFilter.java`
    - A servlet filter (or `OncePerRequestFilter`) that, after authentication, records the subject, resolved `ROLE_*` authorities, HTTP method, path, and status into the `access_audit` table for the server-side authority-confirmation verification step; comment that this is audit-only and not an authorization decision (R2).
    - Wire the filter into the security chain from 2.4.
    - _Requirements: 2.2, 2.3, 2.4_
    - _Design: Testing Strategy > Server-side authority confirmation; Database / Flyway Design (access_audit)_

- [ ] 10. Final checkpoint - full stack
  - Ensure all backend and frontend tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific granular requirement clauses and the design section it fulfills for traceability.
- Checkpoints ensure incremental validation at the backend and full-stack boundaries.
- Property-based tests (jqwik on the backend, fast-check on the frontend) validate the six universal correctness properties, each running a minimum of 100 generated cases.
- Every code file must include extensive instructional comments and be fully realized, using `inject()` and signals on the frontend per the design's Angular 19 conventions.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "1.4"] },
    { "id": 1, "tasks": ["2.1", "2.2"] },
    { "id": 2, "tasks": ["2.3"] },
    { "id": 3, "tasks": ["2.4", "3.1"] },
    { "id": 4, "tasks": ["3.2"] },
    { "id": 5, "tasks": ["3.3"] },
    { "id": 6, "tasks": ["3.4", "6.1", "6.2"] },
    { "id": 7, "tasks": ["4.1", "4.2", "4.3", "4.4", "4.5", "6.3", "6.5"] },
    { "id": 8, "tasks": ["6.4", "7.1", "7.2"] },
    { "id": 9, "tasks": ["7.3", "9.2"] },
    { "id": 10, "tasks": ["7.4"] },
    { "id": 11, "tasks": ["8.1", "8.2", "8.3", "9.1"] }
  ]
}
```
