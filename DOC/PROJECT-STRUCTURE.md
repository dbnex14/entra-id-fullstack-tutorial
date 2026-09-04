# Project Structure

A guided tour of every non-source artifact in the repository: all the Markdown
documents, the `.kiro/` tooling directory (specs, hooks, agents), both `.vscode/`
directories, and a file-by-file reference for the Angular frontend components.

Where a topic already has a dedicated document, this page summarizes it and links
out rather than duplicating it.

---

## 1. Documentation map (every `.md` file)

The repository is written to be *read*. Documentation lives in three places: the
root `README.md`, the how-to `GUIDE/` folder, and the reference `DOC/` folder,
plus a couple of project-local READMEs and a verification runbook.

| File | Role | Read it when |
| --- | --- | --- |
| `README.md` (root) | Project overview, architecture-at-a-glance, quick start, test commands, tech stack, and the map to everything else. | You are new to the repo. Start here. |
| `GUIDE/LEARNING-GUIDE.md` | A file-by-file **reading order** through every commented source file, backend and frontend, mapped back to the spec requirements. | You want to learn how the whole system works, in sequence. |
| `GUIDE/RUNNING-GUIDE.md` | How to run both projects locally on **any OS**, and what startup/output to expect. | You want to run it and are not on macOS specifically. |
| `GUIDE/RUNNING-MAC-GUIDE.md` | Step-by-step setup for a 2015 **MacBook Pro (Intel), macOS Monterey**, incl. the Entra app-registration checklist. | You are setting up on that Mac. |
| `GUIDE/TOKEN-VERIFICATION-GUIDE.md` | Manual token/identity-flow verification (jwt.io claim inspection, background-refresh observation, and the server-side `ROLE_*`/status matrix), including live-login validation. | You want to validate the token flow on a running system by hand. |
| `DOC/ARCHITECTURE.md` | Components, request/token flows, startup sequence, and the key design decisions. | You want the mental model of the system. |
| `DOC/API.md` | REST endpoint reference, status-code matrix, and how to exercise the API (incl. Postman). | You are calling or testing the API. |
| `DOC/SECURITY.md` | Token validation chain, roles → authorities, CORS, the trust boundary. | You are reasoning about auth/security. |
| `DOC/DATABASE.md` | Schema, Flyway migrations, and the audit trail. | You are touching persistence or migrations. |
| `DOC/CONFIGURATION.md` | Every setting and identity constant, and how to change it (tenant/app, ports, editor). | You are configuring or re-pointing the app. |
| `DOC/AGENT-HOOKS.md` | The Kiro Agent Hooks under `.kiro/hooks/`, their limits, and the CI/Git-hook enforcement that should be the team baseline. | You want to understand the `.kiro/hooks/` automation. |
| `DOC/PROJECT-STRUCTURE.md` | **This file** — the doc map, `.kiro/`, both `.vscode/`, and the frontend component reference. | You want to know what a non-source file is for. |
| `EntraUi/README.md` | The **default Angular CLI** README (`ng serve`, `ng build`, `ng test` boilerplate). Not project-specific. | You want the stock Angular CLI command reference. |

> Reading path: `README.md` → `GUIDE/LEARNING-GUIDE.md` → the `DOC/` reference set
> as needed. The `GUIDE/` docs are task-oriented ("how do I run it?"); the `DOC/`
> docs are reference-oriented ("what is the exact behavior/setting?").

---

## 2. The `.kiro/` directory

`.kiro/` holds Kiro-specific tooling: the **spec** that drove the build, the
**agent hooks** that assist development, and an (empty) **agents** directory.
Nothing here is required to compile or run the applications — it is developer
tooling and project history.

```
.kiro/
  specs/
    entra-oauth-fullstack/
      .config.kiro          spec metadata (id, workflow type, spec type)
      requirements.md       the WHAT — user stories + acceptance criteria
      design.md             the HOW — architecture, sequences, decisions
      tasks.md              the PLAN — ordered, checkable implementation tasks
      tasks.meta.json       machine-readable task status/metadata
  hooks/                    8 Kiro Agent Hooks (see DOC/AGENT-HOOKS.md)
  agents/                   (empty — reserved for custom agent definitions)
```

### 2.1 `.kiro/specs/entra-oauth-fullstack/`

This is the **spec** — the requirements-first record of what was built and why.
It follows the standard three-document spec workflow (requirements → design →
tasks), plus two metadata files.

| File | What it contains | Format |
| --- | --- | --- |
| `.config.kiro` | Spec metadata read by Kiro: `specId` (a GUID), `workflowType` (`requirements-first`), and `specType` (`feature`). Identifies this folder as a Kiro spec and how it should be handled. | JSON |
| `requirements.md` | The **WHAT**: user stories and numbered acceptance criteria (e.g. `R1.1`, `R1.9`). Source-file comments reference these IDs, so this file is the traceability anchor. | Markdown |
| `design.md` | The **HOW**: the full architecture, identity/token flow sequence diagrams, the validation chain, and design decisions. The single richest document in the repo; even a skim is worthwhile. | Markdown |
| `tasks.md` | The **PLAN**: an ordered, checkbox list of implementation tasks (e.g. task 6.4 = the HTTP interceptor). Source comments cite task numbers so you can trace code → plan. | Markdown |
| `tasks.meta.json` | Machine-readable companion to `tasks.md`: per-task status and metadata Kiro uses to track spec progress. Not meant to be hand-edited. | JSON |

The `.config.kiro` values, verbatim:

```json
{ "specId": "ae2f3e17-6095-4151-a5a7-0e5e5b7ed8a3", "workflowType": "requirements-first", "specType": "feature" }
```

> The spec is historical/reference material. The running behavior of the app is
> defined by the source and the `DOC/` set; the spec explains the intent behind
> them and is how `GUIDE/LEARNING-GUIDE.md` cross-references requirement and task
> IDs found in the code comments.

### 2.2 `.kiro/hooks/`

Eight JSON hook definitions that automate build/test-on-save, per-file code
review, docs-freshness reminders, and the security- and Flyway-migration guards,
scoped per app (`entra-backend/` vs `EntraUi/`). These are **fully documented**,
including their triggers, matchers, limitations, and the IDE-agnostic enforcement
(Git hooks + CI) that should be the real team baseline, in
[AGENT-HOOKS.md](AGENT-HOOKS.md). They only run inside Kiro IDE, so treat them as
a per-developer convenience.

### 2.3 `.kiro/agents/`

Currently **empty**. It is the conventional location for custom Kiro agent
definitions (specialized agents with their own tools/prompts). Because no agent
files are checked in, the project uses the default Kiro agent. If you add custom
agents later, their definition files live here.

---

## 3. The `.vscode/` directories (two of them)

There are **two** `.vscode/` folders, each scoped to a different concern:

- Repository root `/.vscode/` — **backend Java tooling** for VS Code.
- `EntraUi/.vscode/` — **frontend Angular** run/debug/test and extension hints.

All VS Code config is optional and IDE-specific: it does not affect the Maven or
Angular builds, and it is inert in IntelliJ/WebStorm. It only shapes the VS Code
experience.

### 3.1 Root `/.vscode/settings.json` (backend Java)

Points the VS Code Java language server (Eclipse JDT.LS) at the project's JDK so
the editor resolves the classpath the same way the Maven build does.

| Setting | Value | Purpose |
| --- | --- | --- |
| `java.jdt.ls.java.home` | `C:\Program Files\java\jdk-corretto-21` | JDK the Java **language server** itself runs on. Prevents fallback to an older JDK and spurious "cannot resolve" errors. |
| `java.configuration.runtimes` | one entry, `JavaSE-21` → the Corretto 21 path, `default: true` | Registers JDK 21 as the default runtime, aligning with `<java.version>21</java.version>` in `entra-backend/pom.xml`. |
| `java.compile.nullAnalysis.mode` | `automatic` | Lets the extension enable null analysis automatically. |
| `java.configuration.updateBuildConfiguration` | `interactive` | Prompts before re-syncing the build config instead of doing it silently. |

> **The path is Windows-specific.** On macOS/Linux the `C:\...` path does not
> exist, so either use IntelliJ for the backend and ignore this file, or replace
> the paths with your local JDK 21 home (`/usr/libexec/java_home -v 21` on macOS).
> See [CONFIGURATION.md](CONFIGURATION.md) → "Editor configuration" and
> `GUIDE/RUNNING-MAC-GUIDE.md` Section 4. This file is committed, so keep local
> path edits out of shared commits.

### 3.2 `EntraUi/.vscode/` (frontend Angular)

Three files, all standard Angular-CLI scaffolding, that make VS Code run, debug,
and lint the SPA:

| File | Purpose |
| --- | --- |
| `tasks.json` | Defines two background npm tasks — **`npm: start`** (`ng serve`) and **`npm: test`** (`ng test`). Both are marked `isBackground: true` with a TypeScript problem matcher whose `endsPattern` is `bundle generation complete`, so VS Code knows when the dev server/test build has finished starting. These tasks are what the debug configs launch. |
| `launch.json` | Two Chrome launch configurations: **`ng serve`** (pre-launches `npm: start`, opens `http://localhost:4200/`) and **`ng test`** (pre-launches `npm: test`, opens the Karma debug page `http://localhost:9876/debug.html`). Lets you debug the app or the unit tests in Chrome from VS Code's Run panel. |
| `extensions.json` | Recommends one extension: **`angular.ng-template`** (the Angular Language Service — template type-checking, completions, go-to-definition in templates). VS Code offers to install it when you open the folder. |

> None of these are needed to build or test from the terminal (`npm start`,
> `npm test`, `npm run build` work standalone); they just wire the same commands
> into VS Code's Run/Debug UI.

---

## 4. Frontend component reference (`EntraUi/src/app/`)

The SPA is an **Angular 19 standalone** app (no NgModules; `inject()` DI and
signals throughout). This section is a per-file reference. For the higher-level
flows (login, logout, authenticated call, refresh) see
[ARCHITECTURE.md](ARCHITECTURE.md) → "Frontend architecture"; for the reading
order with full inline commentary see `GUIDE/LEARNING-GUIDE.md`.

```
EntraUi/src/
  main.ts                     bootstraps AppComponent with app.config.ts
  index.html                  host page; document <title> = "Item Manager"
  styles.css                  global plain-CSS styles (no SCSS)
  app/
    app.component.{ts,html,css}  the app shell (header/nav/outlet)
    app.config.ts             providers: router, HttpClient+interceptor, MSAL init
    app.routes.ts             route table + guards
    auth/                     auth infrastructure (see 4.2)
    home/                     public landing page
    login/                    OAuth redirect/callback handler
    dashboard/                read path (Viewer or Admin)
    admin/                    write path (Admin only)
```

### 4.1 App shell and composition

| File | Responsibility |
| --- | --- |
| `app.component.ts` / `.html` / `.css` | The **shell**. Class name `AppComponent`, `title = 'EntraUi'`, but the visible header reads **"Item Manager"**. Hosts a `RouterOutlet` and top-bar nav (`RouterLink`/`RouterLinkActive`). Reads `AuthSessionStore` to show **Sign in** (signed out) or **Sign out** + current roles (signed in); nav links appear only when authenticated and the **Admin** link only for the `Admin` role (UX only). Sign-in/out is initiated here, via `TokenRefreshService`. |
| `app.config.ts` | The **composition root** (`ApplicationConfig`). Provides the router, `HttpClient` with the custom functional interceptor (`withInterceptors([authTokenInterceptor])`), and MSAL. Registers a `provideAppInitializer` that awaits MSAL's async `initialize()` (required by `@azure/msal-browser` v5) and then `handleRedirectPromise()` **before** the router/guards run, so the session is established regardless of the landing route. |
| `app.routes.ts` | The **route table**: `/home` (public, default), `/login` (public callback), `/dashboard` (`authGuard`), `/admin` (`authGuard` + `roleGuard(['Admin'])`), with unknown URLs falling back to `/home`. |
| `main.ts` | Bootstraps `AppComponent` using `app.config.ts`. |

### 4.2 Auth infrastructure (`app/auth/`)

The reusable identity layer that every feature component depends on transparently.

| File | Responsibility |
| --- | --- |
| `msal.config.ts` | Identity constants (`TENANT_ID`, `CLIENT_ID`, derived `API_SCOPE` and `AUTHORITY`) and the MSAL instance/guard/interceptor factories. `localStorage` token cache; redirect flow; protected-resource map that scopes the API token to `http://localhost:8080/entra-backend/*`. The one file to edit when re-pointing to a different tenant/app (see [CONFIGURATION.md](CONFIGURATION.md)). |
| `auth-session.store.ts` | Signal-based **session state** (token, expiry, roles). Exposes `isAuthenticated` and `needsProactiveRefresh` (the 300s / 5-min pre-expiry window). The single source of truth the shell and guards read. |
| `token-refresh.service.ts` | **Single-flight silent refresh** with bounded retry (max 3 transient retries, 500/1000/2000 ms backoff). Concurrent callers coalesce into one token call (`inFlight$` + `shareReplay(1)`); interaction-required errors bypass retry and trigger interactive login. Also owns `beginInteractiveLogin()`. |
| `auth-token.interceptor.ts` | The **functional HTTP interceptor**. For requests to the API prefix, refreshes proactively if within the expiry window, attaches `Authorization: Bearer <token>`, and on a `401 invalid_token` performs one reactive refresh and retries the request exactly once. Used instead of MSAL's class interceptor to avoid double token attachment. |
| `auth.guard.ts` | `authGuard` (session gate — saves the intended URL and begins login if unauthenticated) and `roleGuard` (client-side UX gate; the **server remains authoritative**). |

### 4.3 Feature components

| Component | Route / Guard | What it does |
| --- | --- | --- |
| `home/home.component.ts` | `/home` — **public**, no guard | The default, unauthenticated landing page. Exists so opening the app root (or returning after logout) does **not** force an immediate login. Intentionally has **no Sign-in button** — sign-in is offered by the header control in the shell, avoiding two sign-in affordances. |
| `login/login.component.ts` | `/login` — public | The **return leg** of the Authorization Code + PKCE flow. In `ngOnInit` it calls `handleRedirectPromise()`, which validates `state` (R1.9), detects `error` params (R1.10), and on success completes the code→token exchange and populates `AuthSessionStore`. (The app initializer in `app.config.ts` also processes the redirect, so the session is reliably established either way.) |
| `dashboard/dashboard.component.ts` | `/dashboard` — `authGuard` | The **read path**. Calls `GET /entra-backend/items` (and the read-only change log `GET /entra-backend/history`) and renders results with signals. Deliberately thin: it does not attach or refresh tokens — the interceptor does. Backend gates it with `@PreAuthorize("hasAnyRole('Viewer','Admin')")`. |
| `admin/admin.component.ts` | `/admin` — `authGuard` + `roleGuard(['Admin'])` | The **write path**. Create (POST), edit (PUT), and delete (DELETE, with a confirm prompt) items — all Admin-only. Client-side `roleGuard` is UX only (hides the page); the backend independently enforces `hasRole('Admin')` on every request, so the server is authoritative. |

> Cross-cutting principle: components never handle tokens directly. The bearer
> token attach/refresh machinery lives entirely in `auth/`
> (`auth-token.interceptor.ts` + `token-refresh.service.ts`), and authorization is
> ultimately enforced by the backend from the token's `roles` claim.

---

## See also

- [ARCHITECTURE.md](ARCHITECTURE.md) — component roles and request/token flows.
- [CONFIGURATION.md](CONFIGURATION.md) — settings, identity constants, editor config.
- [AGENT-HOOKS.md](AGENT-HOOKS.md) — the `.kiro/hooks/` automation in detail.
- `GUIDE/LEARNING-GUIDE.md` — the file-by-file reading order with full commentary.
