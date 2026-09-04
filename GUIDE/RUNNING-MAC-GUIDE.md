# Running on macOS - Setup Guide (MacBook Pro 2015, macOS Monterey)

This guide takes a fresh 2015 MacBook Pro (Intel x86_64) on macOS Monterey from
nothing to running both projects locally, using **Visual Studio Code** and
**IntelliJ IDEA**. No Kiro and no Docker are required.

Your Mac hardware is **Intel (x86_64)**, not Apple Silicon - this matters when
you download the JDK and when Homebrew warns about your OS version. Every step
below assumes Intel + Monterey.

Everything lives in **one GitHub repository** (a monorepo):

- `https://github.com/dbnex14/entra-id-fullstack-tutorial.git`

Inside it are two projects side by side:

- `entra-backend/` - the Spring Boot resource server (JDK 21, port 8080).
- `EntraUi/` - the Angular 19 SPA (Node, port 4200).

A single `git clone` retrieves the whole working full-stack project - no
submodules, no second repository, no special flags.

> Related documentation: the repository `README.md` is the overview and entry
> point; the `DOC/` folder holds reference docs (ARCHITECTURE, API, SECURITY,
> DATABASE, CONFIGURATION); `LEARNING-GUIDE.md` is a file-by-file reading path;
> `RUNNING-GUIDE.md` covers running on any OS.

---

## 1. Install the toolchain

### 1a. Xcode Command Line Tools (git, compilers)

```bash
xcode-select --install
```

Accept the prompt. This gives you `git` and build basics. Verify:

```bash
git --version
```

### 1b. Homebrew (package manager)

Monterey (macOS 12) is likewise past Homebrew's officially supported window, so
you may see a warning like "You are using macOS 12. We do not provide support for
this version." That warning is fine for what we need. Install Homebrew:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

On Intel Macs Homebrew installs to `/usr/local`. Verify:

```bash
brew --version
```

> If a specific formula later fails to build from source on Monterey, install a
> slightly older version or use a direct installer (noted per tool below).

### 1c. JDK 21 (Intel x86_64 build)

Do NOT grab an Apple Silicon (aarch64) build. Get the **macOS x64** JDK 21.

Option A - Homebrew (Temurin):

```bash
brew install --cask temurin@21
```

Option B - direct download: get "macOS x64" JDK 21 from Adoptium (Temurin) or
Amazon Corretto and run the `.pkg` installer.

Verify it is 21:

```bash
/usr/libexec/java_home -V        # lists installed JDKs
java -version                    # should report 21.x
```

Set `JAVA_HOME` for your shell (Monterey default shell is zsh):

```bash
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zshrc
source ~/.zshrc
echo "$JAVA_HOME"                # should point at a jdk-21 home
```

### 1d. Maven

```bash
brew install maven
mvn -version                     # confirm the "Java version: 21" line
```

If `mvn` reports a Java version other than 21, your `JAVA_HOME` is wrong - fix
1c before continuing.

### 1e. Node.js + npm (for Angular 19)

Angular 19 needs Node 18+ (Node 20 LTS recommended). On Monterey the cleanest path
is `nvm` (avoids Homebrew Node build issues on an older OS):

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
# restart the terminal, or:
export NVM_DIR="$HOME/.nvm"; [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm install 20
nvm use 20
node --version                   # v20.x
npm --version
```

(Alternatively `brew install node`, but nvm is more forgiving on Monterey.)

### 1f. PostgreSQL

```bash
brew install postgresql@16
brew services start postgresql@16
```

Add its binaries to PATH if `psql` is not found (Intel path shown):

```bash
echo 'export PATH="/usr/local/opt/postgresql@16/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
psql --version
```

By default Homebrew Postgres creates a superuser named after your macOS
username, NOT `postgres`, and with no password. The projects expect user
`postgres` / password `postgres`. Create that role and the database:

```bash
# Create the 'postgres' login role with the expected password:
psql -d postgres -c "CREATE ROLE postgres LOGIN PASSWORD 'postgres' SUPERUSER;"

# Create the database the backend expects:
createdb my_workspace

# Sanity check the expected credentials work:
psql "postgresql://postgres:postgres@localhost:5432/my_workspace" -c "SELECT 1;"
```

Expect `1` back. If the `CREATE ROLE` says the role already exists, that is fine.

### 1g. Google Chrome (for the frontend tests)

The Angular tests run in headless Chrome. Install Chrome if you do not have it:

```bash
brew install --cask google-chrome
```

---

## 2. Get a GitHub personal access token (if the repo is private)

GitHub no longer accepts account passwords over HTTPS. If the repo is private,
create a Personal Access Token (github.com -> Settings -> Developer settings ->
Personal access tokens) and use it as the password when git prompts, or set up an
SSH key (github.com -> Settings -> SSH and GPG keys) and use the SSH clone URL.
If the repo is public, plain HTTPS clone works with no token.

---

## 3. Clone the repository (one clone, everything included)

Pick a working directory and clone:

```bash
mkdir -p ~/dev && cd ~/dev
git clone https://github.com/dbnex14/entra-id-fullstack-tutorial.git
cd entra-id-fullstack-tutorial
```

Verify both projects came down with real files:

```bash
ls entra-backend/pom.xml            # backend build file
ls EntraUi/package.json             # frontend project file
ls EntraUi/src/app/auth/            # frontend auth sources (msal.config.ts, etc.)
```

All three should exist. `EntraUi` is now an ordinary folder in the repo (not a
submodule), so there is nothing else to fetch.

---

## 4. Fix the VS Code Java settings for macOS

The repo ships `.vscode/settings.json` with **Windows** JDK paths, e.g.
`C:\\Program Files\\java\\jdk-corretto-21`. On the Mac those paths do not exist,
so the VS Code Java language server would fail to resolve them.

Two options:

**Option A (recommended): use IntelliJ for the backend and VS Code for Angular.**
Then the Java paths in `.vscode/settings.json` do not matter (VS Code is only
your Angular editor) and you can leave the file alone. IntelliJ manages its own
JDK (Section 5).

**Option B: if you want VS Code to also do Java**, edit `.vscode/settings.json`
and replace the Windows paths with your Mac JDK home (from
`/usr/libexec/java_home -v 21`). For example:

```json
{
  "java.jdt.ls.java.home": "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home",
  "java.configuration.runtimes": [
    {
      "name": "JavaSE-21",
      "path": "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home",
      "default": true
    }
  ],
  "java.compile.nullAnalysis.mode": "automatic"
}
```

Use the actual path `/usr/libexec/java_home -v 21` prints on your machine (the
folder name differs between Temurin and Corretto). This file is committed to the
repo, so if you change it, be aware that committing your local Mac path back would
disrupt the Windows setup - keep it local or on a branch.

---

## 5. Run the backend (IntelliJ or terminal)

### Terminal

```bash
cd ~/dev/entra-id-fullstack-tutorial/entra-backend
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # if not already in ~/.zshrc
mvn spring-boot:run
```

Expect the same startup milestones described in `GUIDE/RUNNING-GUIDE.md`
Section 2: Hikari pool starts, Flyway applies `V1__initial_schema.sql`, Hibernate
validates, OIDC discovery loads JWKS, and Tomcat starts on port 8080.

Validate:

```bash
curl -i http://localhost:8080/entra-backend/items      # expect HTTP/1.1 401 + WWW-Authenticate: Bearer
psql "postgresql://postgres:postgres@localhost:5432/my_workspace" -c "\dt"
# expect: item, app_user, access_audit, flyway_schema_history
```

The 401 is the success signal (stateless server refusing anonymous access).

### IntelliJ IDEA

1. File -> Open -> select the `entra-backend` folder (open it as its own project;
   it is a standalone Maven project).
2. IntelliJ detects the Maven project. If prompted, "Load Maven project".
3. File -> Project Structure -> Project -> set **SDK to your JDK 21**
   (Add SDK -> download or point to the Temurin/Corretto 21 you installed).
   Set "Language level" to 21.
4. Let IntelliJ import dependencies (first import downloads them).
5. Run `EntraOauthApplication` (green run arrow on the main class), or use the
   Maven tool window -> `spring-boot:run`.
6. Ensure the run configuration's JRE is JDK 21 (Run -> Edit Configurations).

---

## 6. Run the frontend (VS Code or terminal)

```bash
cd ~/dev/entra-id-fullstack-tutorial/EntraUi
npm install            # first time; downloads Angular + MSAL + fast-check, etc.
npm start              # ng serve -> http://localhost:4200
```

Open http://localhost:4200. As noted in `RUNNING-GUIDE.md`, the root page still
shows the default Angular CLI starter splash ("Hello, EntraUi / Congratulations!
Your app is running") with the routed view rendering beneath it. Navigation to
`/dashboard` triggers the login redirect (which only completes with a valid Entra
app registration - see Section 8).

Open the folder in VS Code:

```bash
code .
```

Recommended VS Code extension: "Angular Language Service". You do NOT need the
Java extensions for the frontend.

---

## 7. Run the tests (no login / no Entra needed)

Prove the whole system is correct locally without any cloud dependency.

Backend (from `entra-backend`):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -o clean test          # expect: Tests run: 15, ... BUILD SUCCESS
```

Frontend (from `EntraUi`):

```bash
npm test -- --watch=false --browsers=ChromeHeadless   # expect: 13 SUCCESS
npm run build                                          # expect: exit 0 (bundle-size WARNING is fine)
```

If ChromeHeadless cannot launch, confirm Chrome is installed (Section 1g).

---

## 8. Entra ID app registration - do you need a new one?

**You do NOT need to register a NEW app** if you have access to the SAME Entra
tenant and app registration the project is hard-wired to. The identity constants
are baked into two files:

- `EntraUi/src/app/auth/msal.config.ts` (TENANT_ID, CLIENT_ID, API_SCOPE, AUTHORITY)
- `entra-backend/src/main/resources/application.yml` (issuer-uri, app.security.audiences)

Current values:

- Tenant ID: `76325907-a5db-46b1-9d5a-cbcca2e63e66`
- Client ID: `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c`
- API scope: `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c/access_as_user`
- Redirect URI: `http://localhost:4200`

Because the redirect URI is `http://localhost:4200` (a port, not a machine), the
SAME app registration works from your Mac exactly as it does from Windows - the
localhost origin is identical. **So if the existing registration already lists
`http://localhost:4200` as a SPA redirect URI and your test users have the
`Admin`/`Viewer` app roles assigned, you can log in from the Mac with no changes
at all.**

### When you WOULD register a new app

Register a new Entra app only if:
- You do not have access to that tenant/app, or
- You want to use a different tenant.

If so, in the Azure portal (Microsoft Entra ID -> App registrations -> New
registration):

1. **Platform:** add a **Single-page application (SPA)** redirect URI
   `http://localhost:4200`.
2. **Expose an API:** set the Application ID URI to `api://<your-new-client-id>`
   and add a delegated scope named `access_as_user`.
3. **App roles:** define app roles `Admin` and `Viewer` (allowed member types:
   Users/Groups), then assign them to your test users under Enterprise
   applications -> your app -> Users and groups.
4. **API permissions:** add (and grant admin consent for) the `access_as_user`
   delegated permission for your own API, plus `openid`, `profile`,
   `offline_access`.
5. **Update the code constants** to your new tenant/client id:
   - In `EntraUi/src/app/auth/msal.config.ts`: `TENANT_ID`, `CLIENT_ID`
     (API_SCOPE and AUTHORITY derive from them).
   - In `entra-backend/src/main/resources/application.yml`: the
     `spring.security.oauth2.resourceserver.jwt.issuer-uri` (tenant) and the
     `app.security.audiences` list (both `<client-id>` and
     `api://<client-id>`).
6. Rebuild/restart both apps (`ng serve` picks up TS changes on save; restart
   `mvn spring-boot:run` after editing `application.yml`).

> No client secret is needed anywhere: the SPA is a public client using PKCE, and
> the backend only validates tokens (it never calls Entra with a secret).

---

## 9. What will and will not work on the Mac

**Works with zero cloud setup (validate today):**
- Backend boots, Flyway migrates, tables created.
- `curl /entra-backend/items` -> 401 (filter chain active).
- `mvn -o clean test` -> 15 tests pass.
- `npm test` -> 13 tests pass; `npm run build` -> succeeds.
- Frontend serves at :4200 and attempts the login redirect.

**Needs the Entra app registration (Section 8) to fully exercise:**
- Completing interactive sign-in and returning with a real token.
- Authenticated dashboard reads, the Admin write path, and real `access_audit`
  rows.

No macOS/Intel/Monterey blocker exists for any of this. The one thing that needs
cloud setup is the **Entra app registration / redirect URI** for live login
(Section 8). Everything else is standard toolchain setup, and the whole codebase
now comes down in a single `git clone`.

---

## 10. Quick order-of-operations recap

```bash
# one-time toolchain
xcode-select --install
# install Homebrew, then:
brew install --cask temurin@21 google-chrome
brew install maven postgresql@16
brew services start postgresql@16
# install nvm, then: nvm install 20

# database
psql -d postgres -c "CREATE ROLE postgres LOGIN PASSWORD 'postgres' SUPERUSER;"
createdb my_workspace

# get the code (ONE repo, everything included)
mkdir -p ~/dev && cd ~/dev
git clone https://github.com/dbnex14/entra-id-fullstack-tutorial.git
cd entra-id-fullstack-tutorial

# run
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
( cd entra-backend && mvn spring-boot:run ) &
( cd EntraUi && npm install && npm start )

# validate
curl -i http://localhost:8080/entra-backend/items       # 401 = good
open http://localhost:4200
```
