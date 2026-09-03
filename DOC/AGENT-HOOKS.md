# Agent Hooks (and why they are NOT your enforcement layer)

This document explains the Kiro Agent Hooks configured for this repository, what
each one does, and - just as important for a learning project - where their
limits are. The short version:

> **Kiro Agent Hooks are a per-developer convenience that only run inside Kiro.
> They are NOT team-wide enforcement. The real, IDE-independent safety net is Git
> hooks + CI + build-tool gates, described near the end of this document.**

Read the whole thing once; the "Limitations" and "What actually enforces
quality" sections are the main learning outcome.

---

## What is an Agent Hook?

An Agent Hook is a small JSON file under `.kiro/hooks/` that tells Kiro: "when
event X happens, do Y." The event is a **trigger** (for example, a file was
saved). The "do Y" is an **action**, which is either:

- a **command** action - Kiro runs a shell command, or
- an **agent** action - Kiro feeds a prompt to the AI agent so it performs a
  review, a reminder, or a check.

An optional **matcher** (a regular expression) narrows down *when* the hook
fires. What the matcher is tested against depends on the trigger, and that
distinction matters a lot here (see the note on matchers below).

Every hook file in this repo follows the v1 schema:

```json
{
  "version": "v1",
  "hooks": [
    {
      "name": "human-readable name",
      "trigger": "PostFileSave",
      "matcher": "optional regex",
      "action": { "type": "command | agent", "command": "...", "prompt": "..." }
    }
  ]
}
```

### A crucial detail: what the matcher matches

- For **`PostFileSave`** (and the other file triggers) the matcher is tested
  against the **file path**. So `EntraUi/.*\.(ts|html)$` really does mean "a
  TypeScript or HTML file inside `EntraUi/`". App scoping via the matcher works
  directly for these hooks.
- For **`PreToolUse`** the matcher is tested against the **tool name**, NOT the
  file path. That is why the two `PreToolUse` guard hooks below match the write
  tools (`fs_write|str_replace|fs_append`) and then do the path filtering
  *inside the agent prompt* ("only act if the path is under
  `db/migration/`...", etc.). You cannot path-filter a `PreToolUse` hook with the
  matcher alone.

---

## The hooks in this repository

There are eight hooks, split by the app they target. Each hook's `name` and
`description` state which app it belongs to, and every matcher/command is scoped
to that app's directory (`entra-backend/...` vs `EntraUi/...`).

### Backend hooks (target: `entra-backend/`, Spring Boot 3)

#### 1. Backend: unit tests after each spec task
- **File:** `.kiro/hooks/backend-tests-after-task.json`
- **Trigger:** `PostTaskExec` (fires after a Kiro spec task is marked completed)
- **Action:** command - `mvn -q -f entra-backend/pom.xml test`
- **When it fires:** each time you finish a task in the spec workflow.
- **Why it is good practice:** completing a unit of work is the natural moment to
  prove nothing regressed. Running the Maven test suite automatically closes the
  gap between "I think it works" and "the tests still pass." We use
  `-f entra-backend/pom.xml` to target the backend module explicitly, because the
  hook command runs from the workspace root.

#### 2. Backend: code review on changed Java
- **File:** `.kiro/hooks/backend-code-review.json`
- **Trigger:** `PostFileSave`
- **Matcher (file path):** `entra-backend/.*\.java$`
- **Action:** agent - reviews the saved file for correctness, bugs, security
  issues, and this project's Spring/JPA conventions.
- **When it fires:** every time you save a `.java` file under `entra-backend/`.
- **Why it is good practice:** a fast, focused second pair of eyes on each change
  catches null-handling slips, missing `@PreAuthorize`, leaked secrets in logs,
  and DTO/entity boundary mistakes while the change is still fresh.

#### 3. Backend: docs freshness
- **File:** `.kiro/hooks/backend-docs-freshness.json`
- **Trigger:** `PostFileSave`
- **Matcher (file path):** `entra-backend/.*(Controller|Entity|Repository)\.java$`
- **Action:** agent - reminds you to update the matching `DOC/*.md`
  (`API.md`, `DATABASE.md`, `SECURITY.md`).
- **When it fires:** when you save a controller, entity, or repository - the
  files that define the API surface and the persistence model.
- **Why it is good practice:** docs rot silently. Tying a doc-review nudge to the
  exact files that change public behavior keeps `DOC/` trustworthy.

#### 4. Backend: security-change guard
- **File:** `.kiro/hooks/backend-security-change-guard.json`
- **Trigger:** `PreToolUse`
- **Matcher (tool name):** `fs_write|str_replace|fs_append`
- **Action:** agent - if the write targets `SecurityConfig.java`,
  `application.yml`, or anything in the backend `security/` package, it pauses to
  explain the risk (auth, CORS, audience, issuer, token validation) before the
  write proceeds.
- **When it fires:** before any write tool runs; the agent then checks the path
  and only speaks up for security-critical files.
- **Why it is good practice:** security regressions (a CORS wildcard, a disabled
  audience check) are easy to introduce and expensive to catch later. A
  deliberate pause on exactly those files raises the bar for the riskiest edits.

#### 5. Backend: Flyway migration guard
- **File:** `.kiro/hooks/backend-flyway-migration-guard.json`
- **Trigger:** `PreToolUse`
- **Matcher (tool name):** `fs_write|str_replace|fs_append`
- **Action:** agent - refuses edits to existing
  `entra-backend/src/main/resources/db/migration/V*__*.sql` files and requires a
  new, higher-numbered `V<n>__description.sql` instead.
- **When it fires:** before any write tool runs; the agent then checks whether the
  path is an existing migration.
- **Why it is good practice:** Flyway records a checksum for every applied
  migration. Editing an applied migration changes its checksum and makes
  `flyway validate` fail on every environment that already ran the old version -
  which blocks startup. Migrations must be **forward-only**: fix or extend the
  schema with a new migration, never by mutating history. See
  [DATABASE.md](DATABASE.md).

### Frontend hooks (target: `EntraUi/`, Angular 19 SPA)

#### 6. Frontend: lint/build on save
- **File:** `.kiro/hooks/frontend-build-on-save.json`
- **Trigger:** `PostFileSave`
- **Matcher (file path):** `EntraUi/.*\.(ts|html|css)$`
- **Action:** command - `npm --prefix EntraUi run build`
- **When it fires:** when you save a `.ts`, `.html`, or `.css` file under
  `EntraUi/`.
- **Why it is good practice:** the Angular compiler surfaces type errors, broken
  template bindings, and budget violations early. Note two intentional choices:
  - This project has **no `lint` npm script and no ESLint config**, so we run the
    existing **`build`** script instead of `ng lint`. If the team later adds
    ESLint (`ng add @angular-eslint/schematics` and a `lint` script), switch this
    hook's command to `npm --prefix EntraUi run lint` for a faster feedback loop.
  - The app uses **plain CSS** (`src/styles.css`), not SCSS, so the matcher
    targets `.css`.

#### 7. Frontend: code review on changed TS/templates
- **File:** `.kiro/hooks/frontend-code-review.json`
- **Trigger:** `PostFileSave`
- **Matcher (file path):** `EntraUi/.*\.(ts|html)$`
- **Action:** agent - reviews for correctness, bugs, security (token handling and
  XSS), and Angular 19 conventions.
- **When it fires:** when you save a TypeScript file or HTML template under
  `EntraUi/`.
- **Why it is good practice:** the front end handles tokens via MSAL and renders
  user-influenced data. A per-save review watches for token mishandling (storing
  or logging access tokens, attaching bearer tokens to the wrong origin) and XSS
  footguns (`bypassSecurityTrustHtml`, raw `innerHTML`).

#### 8. Frontend: docs freshness
- **File:** `.kiro/hooks/frontend-docs-freshness.json`
- **Trigger:** `PostFileSave`
- **Matcher (file path):** `EntraUi/.*\.(service|component)\.ts$`
- **Action:** agent - reminds you to update `DOC/*.md` when UI, auth, or API
  behavior changes.
- **When it fires:** when you save an Angular `*.service.ts` or `*.component.ts`.
- **Why it is good practice:** services and components are where auth wiring and
  API calls live, so they are the changes most likely to make
  [API.md](API.md), [SECURITY.md](SECURITY.md), or
  [CONFIGURATION.md](CONFIGURATION.md) stale.

### Summary table

| App | Hook | Trigger | Matcher | Action |
| --- | --- | --- | --- | --- |
| entra-backend | Unit tests after each spec task | `PostTaskExec` | (none) | command: `mvn -q -f entra-backend/pom.xml test` |
| entra-backend | Code review on changed Java | `PostFileSave` | `entra-backend/.*\.java$` (path) | agent: review correctness/bugs/security/conventions |
| entra-backend | Docs freshness | `PostFileSave` | `entra-backend/.*(Controller\|Entity\|Repository)\.java$` (path) | agent: remind to update API/DATABASE/SECURITY docs |
| entra-backend | Security-change guard | `PreToolUse` | `fs_write\|str_replace\|fs_append` (tool name) | agent: flag SecurityConfig/application.yml/security pkg edits |
| entra-backend | Flyway migration guard | `PreToolUse` | `fs_write\|str_replace\|fs_append` (tool name) | agent: forbid editing existing `V*__*.sql`; require new migration |
| EntraUi | Lint/build on save | `PostFileSave` | `EntraUi/.*\.(ts\|html\|css)$` (path) | command: `npm --prefix EntraUi run build` |
| EntraUi | Code review on changed TS/templates | `PostFileSave` | `EntraUi/.*\.(ts\|html)$` (path) | agent: review correctness/bugs/token-XSS/Angular conventions |
| EntraUi | Docs freshness | `PostFileSave` | `EntraUi/.*\.(service\|component)\.ts$` (path) | agent: remind to update API/SECURITY/CONFIGURATION docs |

---

## Limitations (read this part carefully)

These hooks are helpful, but they are **not** a quality gate you can rely on for
the team. Here is why.

### 1. Hooks only run inside Kiro

Agent Hooks fire **only inside Kiro runtimes**. If you open this repo in plain
**VSCode**, **WebStorm**, or **IntelliJ IDEA**, the files in `.kiro/hooks/` are
just inert JSON - nothing runs. No build on save, no review, no guard.

### 2. The trigger and action sets DIFFER between Kiro runtimes

Even among Kiro tools, hooks are not portable. The two runtimes support
different triggers and different action types:

| Capability | Kiro IDE | kiro-cli |
| --- | --- | --- |
| Example triggers | `PostFileSave`, `PostTaskExec`, `PreToolUse`, ... | `agentSpawn`, `userPromptSubmit`, `preToolUse`, `postToolUse`, `stop` |
| `agent`-type actions (AI review/reminder) | supported | not supported (command-only) |

A hook authored for one runtime **will not fire in the other**. In particular:

- The `PostFileSave` and `PostTaskExec` hooks in this repo are **Kiro IDE**
  features; kiro-cli has no equivalent file-save or task triggers.
- The `agent`-type actions (the reviews, reminders, and guards) rely on Kiro IDE.
  kiro-cli hooks can only run **commands**.

### 3. These hooks are a per-developer convenience, not team enforcement

This is a mixed-IDE team: developers use VSCode, WebStorm, IntelliJ, and Kiro
IDE. Because the hooks only run for whoever happens to be in Kiro IDE, they
**cannot** guarantee that tests ran, that the build is green, or that a migration
was not edited. Treat them as a personal productivity aid, and put the real
guarantees somewhere every developer and every machine shares: version control
and CI.

### Why these hooks were created in Kiro IDE

We authored these in **Kiro IDE** deliberately: it has the richer hook engine
(file-save and task triggers, plus `agent`-type actions) and a hook UI for
managing them. An equivalent **kiro-cli** setup would require:

- **Remapping triggers:** `PostFileSave` -> `postToolUse` (react after a write
  tool runs) and `PostTaskExec` -> `stop` (react when the agent finishes).
- **Converting `agent` actions to command scripts:** the review/reminder/guard
  prompts would have to become shell scripts (for example, a script that runs a
  linter, or greps a diff for edited migration files), since kiro-cli actions are
  command-only.

---

## What actually enforces quality (the team baseline)

The industry-standard, **IDE-agnostic** enforcement layers below should be the
baseline. They run the same way for everyone, regardless of editor.

### Git hooks (local, pre-commit / pre-push)

- **Angular repo (`EntraUi/`):** [Husky](https://typicode.github.io/husky/) +
  [lint-staged](https://github.com/lint-staged/lint-staged) to run
  `ng lint`/`ng build` (and tests) against staged files before a commit.
- **Java repo (`entra-backend/`):** a pre-commit framework (for example
  [pre-commit](https://pre-commit.com/)) or a Maven-driven check bound to a build
  phase, so `mvn test` / formatting / static analysis run before code is shared.

Git hooks catch problems earlier than CI, but they are still local and can be
bypassed with `--no-verify`, so they complement CI rather than replace it.

### CI pipeline (the real enforcement)

A CI pipeline (**GitHub Actions**, **GitLab CI**, or similar) that runs
**build + test + lint on every push and pull request** is the real gate.
CI is the real enforcement because it is **IDE-independent and cannot be
bypassed** by an individual developer's setup: a red pipeline blocks the merge no
matter which editor produced the code. A reasonable pipeline here would:

- Backend: `mvn -f entra-backend/pom.xml verify` (compiles, runs tests, and can
  run `flyway:validate` and static-analysis/format checks).
- Frontend: `npm ci` then `ng build` (and `ng test` in headless mode, and
  `ng lint` once ESLint is configured), run in `EntraUi/`.

### Build-tool-native gates

Lean on gates the build tools already provide, so quality checks live with the
build and run everywhere:

- **Backend (Maven):** Surefire (test execution and failure gating),
  Checkstyle (style rules), Spotless (auto-formatting/format checking), and
  **`flyway validate`** to catch edited or out-of-order migrations - which is
  the automated, team-wide version of hook #5.
- **Frontend (Angular CLI):** `ng lint`, `ng test`, and `ng build` - which is the
  automated, team-wide version of hooks #6 and #7.

### How the layers fit together

Think of it as defense in depth, cheapest/fastest feedback first:

1. **Kiro Agent Hooks** - instant, per-developer nudges *while you edit* (only in
   Kiro).
2. **Git hooks** - a local gate *before you commit/push* (all IDEs, bypassable).
3. **CI + build-tool gates** - the authoritative gate *before merge* (everywhere,
   not bypassable).

Use the hooks for speed; rely on CI for guarantees.

---

## Operational caveats

- **Command hooks add latency.** `Frontend: lint/build on save` runs an Angular
  build on every save of a `.ts/.html/.css` file, and `Backend: unit tests after
  each spec task` runs the Maven suite; both take real time. If saves feel slow,
  narrow the matcher or switch the frontend hook to a lint command once ESLint is
  configured.
- **`PreToolUse` agent hooks add a review step on every matching write.** Hooks #4
  and #5 fire before *every* `fs_write`/`str_replace`/`fs_append`, so the agent
  performs a quick path check on each write even when the file is unrelated. This
  is intentional (it is how the guards see the path), but it does add a small
  step to every write the agent makes.
