# Verification Guide — Entra ID OAuth Full-Stack Reference

This guide walks through the manual, instructional verification procedures for the
Entra ID OAuth full-stack sample. The goal is to let you *observe* the identity
payload and refresh machinery directly:

1. **Manual claim inspection with jwt.io** — decode an Access Token and confirm its claims.
2. **Browser Network-tab observation of the background refresh cycle** — watch the silent
   refresh-token exchange and single-flight request queuing.
3. **Server-side authority confirmation** — confirm the resolved `ROLE_*` authorities and
   exercise the endpoint status matrix.

These manual checks complement — they do not replace — the automated jqwik/fast-check
property tests and the Spring `@WebMvcTest` security slice.

---

## Authoritative identity constants

These values are the single source of truth used throughout the guide. They match the
backend `application.yml` and the frontend `msal.config.ts`.

| Constant | Value |
| --- | --- |
| Tenant ID | `76325907-a5db-46b1-9d5a-cbcca2e63e66` |
| Client ID | `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c` |
| API Scope | `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c/access_as_user` |
| Authority / Issuer (`iss`) | `https://login.microsoftonline.com/76325907-a5db-46b1-9d5a-cbcca2e63e66/v2.0` |
| Accepted audiences (`aud`) | `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c` **or** `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c` |
| Token endpoint | `https://login.microsoftonline.com/76325907-a5db-46b1-9d5a-cbcca2e63e66/oauth2/v2.0/token` |
| Frontend origin | `http://localhost:4200` |
| Backend origin | `http://localhost:8080` |
| Database | `jdbc:postgresql://localhost:5432/my_workspace` (user `postgres`, password `postgres`) |

---

## Prerequisites / How to run

You need three things running before you can verify anything: PostgreSQL, the backend
Resource Server, and the frontend SPA.

### Requirements

- **JDK 21** — the backend targets Java 21. Confirm with `java -version` (expect `21.x`).
- **Flyway v10** — bundled via the Spring Boot dependency; it owns the schema and applies
  `V1__initial_schema.sql` at backend startup. Hibernate runs with `ddl-auto: validate`, so
  the tables must be created by Flyway, never by Hibernate.
- **Node.js + npm** — to run the Angular SPA.
- **PostgreSQL** — a running instance with a `my_workspace` database.

### 1. Start PostgreSQL

Start your PostgreSQL server and ensure the `my_workspace` database exists with the
`postgres` / `postgres` credentials. If the database does not exist yet, create it:

```bash
# Using psql (adjust host/port if needed)
psql -h localhost -p 5432 -U postgres -c "CREATE DATABASE my_workspace;"
```

The backend uses a 10-second connection budget (`hikari.connection-timeout: 10000`), so if
PostgreSQL is unreachable the backend fails fast at startup rather than hanging.

### 2. Run the backend

From the `entra-backend` directory:

```bash
mvn spring-boot:run
```

On startup, Flyway applies `V1__initial_schema.sql` (creating `item`, `app_user`, and
`access_audit`), records it in `flyway_schema_history`, and Hibernate validates the entity
mappings. The Resource Server then listens on `http://localhost:8080`.

### 3. Run the frontend

From the `EntraUi` directory:

```bash
npm install   # first time only
npm start
```

The SPA serves at `http://localhost:4200`. Sign in with an Entra user that has an app role
assigned (`Admin` or `Viewer`) so the SPA acquires an Access Token for the API scope.

---

## Procedure 1 — Manual claim inspection with jwt.io

The purpose of this check is to confirm the token the SPA sends actually contains the
`roles` claim that drives authorization, and that its `aud`, `iss`, and `exp` are correct.

### Step 1 — Sign in

Open the Client App at `http://localhost:4200` and sign in so a session is established.

### Step 2 — Capture the encoded Access Token

Capture the encoded token one of two ways:

**Option A — Network tab (`Authorization: Bearer` header):**

1. Open DevTools → **Network**.
2. Select a call to the API, e.g. a request to `http://localhost:8080/api/items`.
3. In the **Request Headers**, find `Authorization: Bearer <token>` and copy the value
   *after* `Bearer ` — that is the encoded Access Token.

**Option B — Session store (browser console):**

In the DevTools **Console**, read the current token from the session store:

```js
AuthSessionStore.state().accessToken
```

Copy the returned string.

### Step 3 — Decode and inspect at jwt.io

1. Paste the encoded token into [jwt.io](https://jwt.io).
2. Inspect the **decoded payload** and confirm:
   - **`roles`** — an array containing `Admin` and/or `Viewer` (matching the app roles
     assigned to the signed-in user in Entra). This is the claim the backend converts to
     `ROLE_Admin` / `ROLE_Viewer` authorities.
   - **`aud`** — equals the Client ID `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c` **or**
     `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c`. Either form is accepted by the backend's
     `AudienceValidator`.
   - **`iss`** — equals the tenant issuer
     `https://login.microsoftonline.com/76325907-a5db-46b1-9d5a-cbcca2e63e66/v2.0`
     (note the trailing `/v2.0`).
   - **`exp`** — a Unix timestamp in the **future** (the token is not yet expired). jwt.io
     shows this as a human-readable expiry date.

> **Safety note.** Do **not** paste production or otherwise sensitive tokens into
> third-party sites. Treat all tokens as secrets. jwt.io's decoder runs in your browser and
> keeps the data local, but as a rule use only **test tokens** here. For real secrets prefer
> a fully local decoder — for example, base64url-decode the middle (payload) segment of the
> JWT offline — so the token never leaves your machine.

---

## Procedure 2 — Browser Network-tab observation of the background refresh cycle

The purpose of this check is to watch the SPA silently refresh the Access Token using a
refresh token, confirm the token rotates, and confirm that concurrent API calls trigger
exactly **one** token request (the single-flight behavior in `TokenRefreshService`).

### Step 1 — Filter the Network tab to the token endpoint

Open DevTools → **Network** and filter for the token endpoint:

```
login.microsoftonline.com/.../oauth2/v2.0/token
```

(The full URL is
`https://login.microsoftonline.com/76325907-a5db-46b1-9d5a-cbcca2e63e66/oauth2/v2.0/token`.)

### Step 2 — Trigger a refresh

Trigger a refresh one of two ways:

- **Proactive refresh** — wait until the session is within the near-expiry threshold (the
  SPA refreshes proactively when the token is within ~5 minutes / 300s of expiry), then make
  an API call. The SPA refreshes *before* sending the request.
- **Reactive refresh** — force a `401` by letting an API call receive an expired-token
  challenge; the SPA refreshes and retries the original request exactly once.

### Step 3 — Inspect the token request and response

On the `.../oauth2/v2.0/token` call, confirm:

- The request **form body** carries `grant_type=refresh_token`.
- The **response** returns:
  - a **rotated `refresh_token`** (a new refresh-token value replacing the previous one),
  - a new **`access_token`**, and
  - an **`expires_in`** value (the new token lifetime in seconds).

The SPA stores the new access token together with its absolute expiry and replaces the
rotated refresh token in the session store.

### Step 4 — Confirm single-call request queuing (single-flight)

Issue several API calls simultaneously while the token is near expiry (for example, load a
page that fires multiple `http://localhost:8080/api/items` requests at once). Confirm in the
Network tab that:

- **Exactly one** call to the token endpoint appears — not one per API request.
- The concurrent `/api/...` calls **wait** for that single refresh to complete, then all
  proceed using the freshly refreshed token.

This is the single-flight latch in `TokenRefreshService`: concurrent callers queue behind
one in-flight refresh instead of each starting their own.

---

## Procedure 3 — Server-side authority confirmation

The purpose of this check is to confirm that the token's `roles` claim really produces the
expected `ROLE_*` authorities on the server, and that the endpoint status matrix holds for
anonymous, Viewer, and Admin callers.

> **Reminder — audit only.** The `app_user` and `access_audit` tables are **never** consulted
> to make an authorization decision. Authorization is claim-driven: the `ROLE_*` authorities
> that gate the endpoints come exclusively from the validated token's `roles` claim. These
> tables record the *outcome* for traceability.

### Step 1 — Observe the resolved authorities

The resolved authorities are `ROLE_`-prefixed and derived from the token's `roles` claim by
`RolesClaimConverter` (`Admin` → `ROLE_Admin`, `Viewer` → `ROLE_Viewer`). You can observe
them two ways:

- **In the running server** — the resolved authorities are available from
  `SecurityContextHolder.getContext().getAuthentication().getAuthorities()` after
  authentication, and are recorded per-request by `AccessAuditFilter` (see task 9.2).
- **In the `access_audit` table** — `AccessAuditFilter` writes the subject, the serialized
  `ROLE_*` authorities, HTTP method, path, and status for each request.

### Step 2 — Inspect the `access_audit` table

`AccessAuditFilter` populates the `authorities` column with the `ROLE_*` set that the
claim-driven pipeline granted for each request. Query the most recent entries and cross-check
the `authorities` against the token you presented:

```sql
SELECT subject, authorities, http_method, path, status, occurred_at
FROM access_audit
ORDER BY occurred_at DESC
LIMIT 20;
```

Run it against the configured database:

```bash
psql "jdbc:postgresql://localhost:5432/my_workspace" -U postgres
# or:
psql -h localhost -p 5432 -U postgres -d my_workspace \
  -c "SELECT subject, authorities, http_method, path, status, occurred_at FROM access_audit ORDER BY occurred_at DESC LIMIT 20;"
```

The `access_audit` schema (from `V1__initial_schema.sql`) is:

| Column | Type | Meaning |
| --- | --- | --- |
| `id` | `BIGSERIAL` | Primary key |
| `subject` | `VARCHAR(100)` | Token subject (`oid`/`sub`) that made the request |
| `authorities` | `TEXT` | Serialized `ROLE_*` set granted for the request |
| `http_method` | `VARCHAR(10)` | e.g. `GET`, `POST` |
| `path` | `VARCHAR(300)` | e.g. `/api/items` |
| `status` | `INTEGER` | HTTP response status |
| `occurred_at` | `TIMESTAMPTZ` | When the request occurred |

Confirm that an Admin caller's row shows `ROLE_Admin` (and `ROLE_Viewer` if that role is also
present), and a Viewer caller's row shows `ROLE_Viewer`.

### Step 3 — Exercise the status matrix

Exercise the read (`GET`) and write (`POST`) endpoints with each identity and confirm the
following matrix:

| Identity | `GET /api/items` (read) | `POST /api/items` (write) |
| --- | --- | --- |
| **Anonymous** (no token) | **401** | **401** |
| **Viewer** | **200** | **403** |
| **Admin** | **200** | **201** |

- Read (`GET`) requires `hasAnyRole('Viewer','Admin')` → `ROLE_Viewer` or `ROLE_Admin`.
- Write (`POST`) requires `hasRole('Admin')` → `ROLE_Admin`. A caller missing `ROLE_Admin`
  gets **403** and **no mutation** occurs.
- A missing or invalid token is stopped earlier with **401** and a `WWW-Authenticate`
  challenge, before role checks run.

#### Obtaining a token for a Viewer vs an Admin user

The `roles` claim in the Access Token is determined by the **app role assignment** in Entra
ID for the signed-in user:

- To get a **Viewer** token, sign in with a user assigned the **Viewer** app role. Their
  token's `roles` claim contains `Viewer`.
- To get an **Admin** token, sign in with a user assigned the **Admin** app role. Their
  token's `roles` claim contains `Admin`.

Sign in at `http://localhost:4200` as the appropriate user, then capture the token using
either method from **Procedure 1, Step 2** (Network `Authorization: Bearer` header or
`AuthSessionStore.state().accessToken`).

#### curl examples

Set the captured token into a shell variable first:

```bash
TOKEN="<paste-the-encoded-access-token-here>"
```

**Anonymous — no token (expect 401 for both read and write):**

```bash
# GET without a token -> 401
curl -i http://localhost:8080/api/items

# POST without a token -> 401
curl -i -X POST http://localhost:8080/api/items \
  -H "Content-Type: application/json" \
  -d '{"name":"Widget","description":"A sample item"}'
```

**Viewer — token with the Viewer role (expect 200 on read, 403 on write):**

```bash
# GET with a Viewer token -> 200
curl -i http://localhost:8080/api/items \
  -H "Authorization: Bearer $TOKEN"

# POST with a Viewer token -> 403 (no mutation)
curl -i -X POST http://localhost:8080/api/items \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Widget","description":"A sample item"}'
```

**Admin — token with the Admin role (expect 200 on read, 201 on write):**

```bash
# GET with an Admin token -> 200
curl -i http://localhost:8080/api/items \
  -H "Authorization: Bearer $TOKEN"

# POST with an Admin token -> 201 (item created)
curl -i -X POST http://localhost:8080/api/items \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Widget","description":"A sample item"}'
```

After running the write calls, re-run the `access_audit` query from **Step 2** to see the
recorded authorities, method, path, and status for each request — confirming end-to-end that
the token's `roles` claim produced the expected server-side authorities and outcomes.
