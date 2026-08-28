# Entra ID OAuth2 Full-Stack Reference

A decoupled, three-tier reference application demonstrating how to secure a
Single-Page App and a REST API with **Microsoft Entra ID** using the **OAuth2
Authorization Code Flow with PKCE**. It is written to be *read* as much as run:
every source file carries extensive inline comments explaining how the identity
payload (tokens, claims, roles) flows down the stack.

- **Backend** (`entra-backend/`) - Spring Boot 3 resource server on JDK 21. A
  stateless API that validates Entra-issued JWT access tokens and exposes
  role-protected REST endpoints backed by PostgreSQL (schema owned by Flyway).
- **Frontend** (`EntraUi/`) - Angular 19 standalone SPA. Signs users in via MSAL,
  manages the browser-side token lifecycle (proactive/reactive refresh,
  single-flight, bounded retry), and calls the API with a bearer token.

This is a **monorepo**: a single `git clone` retrieves both projects.

---

## Architecture at a glance

```
  Browser (SPA, http://localhost:4200)
        |  1. Authorization Code Flow + PKCE (MSAL)
        v
  Microsoft Entra ID  (Authority / OIDC issuer)
        |  2. mints Access_Token (JWT) + id_token + rotating refresh token
        v
  SPA attaches "Authorization: Bearer <JWT>" to API calls
        |  3. cross-origin call to the API
        v
  Spring Boot Resource Server (API, http://localhost:8080)
        4. validate signature (JWKS) + iss + aud + exp
        5. roles claim -> ROLE_* authorities
        6. @PreAuthorize gates endpoints; JPA/PostgreSQL persists data
```

The central principle: **authorization is claim-driven**. No database table
decides who can do what - the `roles` claim inside the validated token does.

---

## Repository layout

```
entra-id-fullstack-tutorial/
  entra-backend/          Spring Boot 3 resource server (JDK 21, Maven)
    src/main/java/...     security, item domain, audit
    src/main/resources/   application.yml, Flyway migrations
    src/test/java/...     jqwik property tests + security-slice tests
    VERIFICATION.md       manual verification procedures
  EntraUi/                Angular 19 standalone SPA
    src/app/auth/         MSAL config, session store, refresh service,
                          interceptor, route guards
    src/app/{login,dashboard,admin}/  feature components
  GUIDE/                  How-to guides (learning path + running instructions)
    LEARNING-GUIDE.md     file-by-file reading order to learn the whole system
    RUNNING-GUIDE.md      run locally (any OS) and what to expect
    RUNNING-MAC-GUIDE.md  macOS (Intel/Big Sur) setup, step by step
  DOC/                    Reference documentation (this set)
    ARCHITECTURE.md       components, request flows, design decisions
    API.md                REST endpoint reference + status matrix
    SECURITY.md           token validation, roles, CORS, the trust boundary
    DATABASE.md           schema, Flyway, the audit trail
    CONFIGURATION.md      every setting and identity constant, and how to change it
  .kiro/specs/...         the original requirements + design spec
```

---

## Quick start

Prerequisites: JDK 21, Maven, Node 18+, PostgreSQL. (Full setup, including macOS,
is in `GUIDE/`.)

```bash
# 1. database (one time)
createdb my_workspace     # user postgres / password postgres

# 2. backend  (terminal A)
cd entra-backend
mvn spring-boot:run       # starts on http://localhost:8080

# 3. frontend (terminal B)
cd EntraUi
npm install
npm start                 # starts on http://localhost:4200
```

Validate the backend is up (401 is the expected success signal for an
unauthenticated call to a stateless resource server):

```bash
curl -i http://localhost:8080/api/items    # HTTP/1.1 401 + WWW-Authenticate: Bearer
```

> Live sign-in requires a Microsoft Entra app registration matching the identity
> constants (see `DOC/CONFIGURATION.md`). Everything else - startup, the schema,
> the full test suites, the build, and the unauthenticated 401 - works with no
> cloud setup.

---

## Running the tests

```bash
# backend: 15 tests (jqwik property tests + security slice)
cd entra-backend && mvn -o clean test

# frontend: 13 tests (fast-check property tests + interceptor tests)
cd EntraUi && npm test -- --watch=false --browsers=ChromeHeadless
```

---

## Where to go next

- **Want to learn the codebase?** Start with `GUIDE/LEARNING-GUIDE.md` - a
  file-by-file reading path through every commented source file.
- **Want to run it?** See `GUIDE/RUNNING-GUIDE.md` (any OS) or
  `GUIDE/RUNNING-MAC-GUIDE.md` (macOS).
- **Want the reference details?** See the `DOC/` folder:
  [ARCHITECTURE](DOC/ARCHITECTURE.md) - [API](DOC/API.md) -
  [SECURITY](DOC/SECURITY.md) - [DATABASE](DOC/DATABASE.md) -
  [CONFIGURATION](DOC/CONFIGURATION.md).
- **Want to validate a live login?** Follow `entra-backend/VERIFICATION.md`.

---

## Technology stack

| Tier | Technology |
| --- | --- |
| Frontend | Angular 19 (standalone, signals, `inject()`), MSAL (`@azure/msal-angular` / `@azure/msal-browser`) |
| Backend | Spring Boot 3, Spring Security OAuth2 Resource Server, Spring Data JPA |
| Database | PostgreSQL, Flyway migrations |
| Runtime | JDK 21, Node 18+ |
| Testing | jqwik (backend property tests), fast-check (frontend property tests), MockMvc, Karma/Jasmine |

---

## Status

All spec tasks complete. Backend suite green (15 tests), frontend suite green
(13 tests), production build passing. See the `DOC/` set for details.
