# API Reference

The backend exposes a small role-protected REST API under `/entra-backend/items`.
All endpoints require a valid Entra ID bearer token; authorization is enforced
from the token's `roles` claim.

- Base URL (local): `http://localhost:8080/entra-backend` (the application is
  mounted under the `/entra-backend` servlet context path).
- Auth: `Authorization: Bearer <access_token>` on every request.
- Content type: `application/json`.

## Authentication and authorization model

- A missing, malformed, expired, or otherwise invalid token is rejected with
  **401 Unauthorized** and a `WWW-Authenticate: Bearer` challenge - before any
  endpoint logic runs.
- An authenticated caller lacking the required role is rejected with
  **403 Forbidden**, and no data is mutated.
- Roles come from the token's `roles` claim, mapped to Spring authorities as
  `ROLE_<value>` (e.g. `Admin` -> `ROLE_Admin`). See `DOC/SECURITY.md`.

## Endpoints

### GET /entra-backend/items

List all items.

- **Required role:** `Viewer` or `Admin` (`hasAnyRole('Viewer','Admin')`).
- **Success:** `200 OK` with a JSON array of items.

Response body (array of `ItemDto`):

```json
[
  {
    "id": 1,
    "name": "Sample item",
    "description": "An example item",
    "createdBy": "00000000-0000-0000-0000-000000000000"
  }
]
```

Example:

```bash
curl -i http://localhost:8080/entra-backend/items \
  -H "Authorization: Bearer $TOKEN"
```

### POST /entra-backend/items

Create a new item.

- **Required role:** `Admin` (`hasRole('Admin')`).
- **Request body:** `CreateItemRequest` - `name` is required (non-blank);
  `description` is optional.
- **Success:** `201 Created` with the created `ItemDto`.
- **Validation:** a blank `name` yields `400 Bad Request`.
- The server sets `createdBy` from the token subject (oid/sub); the client does
  not (and cannot) supply it.

Request body:

```json
{ "name": "New item", "description": "Optional text" }
```

Example:

```bash
curl -i -X POST http://localhost:8080/entra-backend/items \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"New item","description":"Optional text"}'
```

### PUT /entra-backend/items/{id}

Update an existing item.

- **Required role:** `Admin` (`hasRole('Admin')`).
- **Path variable:** `id` - the item id (long).
- **Request body:** `UpdateItemRequest` - `name` is required (non-blank);
  `description` optional.
- **Success:** `200 OK` with the updated `ItemDto`.
- **Not found:** `404 Not Found` if no item exists for `id`.
- Updating refreshes `updated_at`; the original `createdBy`/`created_at`
  (provenance) are not changed.

Example:

```bash
curl -i -X PUT http://localhost:8080/entra-backend/items/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Renamed","description":"Updated text"}'
```

### DELETE /entra-backend/items/{id}

Delete an existing item.

- **Required role:** `Admin` (`hasRole('Admin')`).
- **Path variable:** `id` - the item id (long).
- **Success:** `204 No Content` (empty body).
- **Not found:** `404 Not Found` if no item exists for `id`.
- The delete is a hard delete of the `item` row. The corresponding
  `access_audit` record of the request is written independently, so the fact that
  an Admin issued the delete remains traceable.

Example:

```bash
curl -i -X DELETE http://localhost:8080/entra-backend/items/1 \
  -H "Authorization: Bearer $TOKEN"
```

## Request payloads (copy-paste for Postman)

Set these on the request in Postman:
- Method + URL as shown per endpoint (e.g. `POST http://localhost:8080/entra-backend/items`).
- **Authorization** tab: type **Bearer Token**, paste your access token (see
  "Obtaining a token for manual testing" below).
- **Headers**: `Content-Type: application/json` (POST/PUT only).
- **Body** tab: **raw** + **JSON**, then paste one of the samples below.

### POST /entra-backend/items (create) - body

With description:

```json
{
  "name": "Sample item",
  "description": "Created from Postman"
}
```

Minimal (description is optional):

```json
{
  "name": "Sample item"
}
```

### PUT /entra-backend/items/{id} (update) - body

Put the item id in the URL path (e.g. `.../items/1`); the body has the same shape
as create (`name` required, `description` optional):

```json
{
  "name": "Renamed item",
  "description": "Updated from Postman"
}
```

### DELETE /entra-backend/items/{id}

No body. Set the id in the URL path and send the Bearer token; a successful
delete returns `204 No Content`.

Notes for Postman:
- A blank/whitespace-only `name` on POST/PUT returns `400 Bad Request`.
- Do not send a `createdBy` field - the server sets it from the token subject and
  ignores any client-supplied value.
- POST/PUT/DELETE require the `Admin` role; a `Viewer` token returns `403`.

## Data shapes

`ItemDto` (response):

| Field | Type | Notes |
| --- | --- | --- |
| `id` | number | Database-assigned identifier |
| `name` | string | Item name |
| `description` | string \| null | Optional description |
| `createdBy` | string | Token subject (oid/sub) that created the row |

`CreateItemRequest` / `UpdateItemRequest` (request):

| Field | Type | Notes |
| --- | --- | --- |
| `name` | string | Required, must be non-blank (`@NotBlank`) |
| `description` | string \| null | Optional |

## Status matrix

The authorization behavior across identities and endpoints:

| Identity | GET /items | POST /items | PUT /items/{id} | DELETE /items/{id} |
| --- | --- | --- | --- | --- |
| Anonymous (no token) | 401 | 401 | 401 | 401 |
| Invalid/expired token | 401 | 401 | 401 | 401 |
| Viewer | 200 | 403 | 403 | 403 |
| Admin | 200 | 201 | 200 | 204 |

(Paths abbreviated; all are under `/entra-backend`.)

Notes:
- 401 is produced by the resource-server entry point before endpoint logic runs.
- 403 is produced by method security before the controller body; no mutation
  occurs on a forbidden write or delete.
- 400 is returned for a blank `name` on POST/PUT (bean validation).
- 404 is returned by PUT/DELETE when no item exists for the given `id`.

## CORS

The API is called cross-origin by the SPA. Only `http://localhost:4200` is
allowed; requests from other origins receive no `Access-Control-Allow-Origin`
header and are blocked by the browser. Methods GET/POST/PUT/DELETE/OPTIONS and
headers Authorization/Content-Type are permitted, with credentials allowed. See
`DOC/SECURITY.md` and `DOC/CONFIGURATION.md`.

## Health

`/entra-backend/actuator/health` is permitted without authentication (used for
liveness). All other paths require a valid token.

## Obtaining a token and using it in Postman

Every `/entra-backend/items` call needs `Authorization: Bearer <access_token>`.
Without it you get **401 Unauthorized**. There are two ways to get a token into
Postman - Method A (copy from the running app) is quickest; Method B (Postman logs
in itself) avoids copy/paste for repeated testing.

Use an **Admin** user's token if you want POST/PUT/DELETE to succeed; a **Viewer**
token can only do GET (writes return 403).

### Method A - copy the token from the running SPA (quickest)

1. In Chrome, open `http://localhost:4200` and **sign in** (as your Admin user).
2. Open **DevTools** (F12) -> **Network** tab.
3. In the app, trigger an API call - open the **Dashboard**, or click **Refresh
   list** on the Admin page. A request named **`items`** appears in Network.
4. Click the `items` request -> **Headers** -> **Request Headers** -> find
   `authorization: Bearer eyJ...`. Copy the long token string **after** the word
   `Bearer ` (do not include "Bearer").
5. In Postman, open your request -> **Authorization** tab -> **Auth Type** =
   **Bearer Token** -> paste the token into the **Token** field.
6. **Send**. You should get 200/201/204 instead of 401.

The token expires in ~1 hour. When 401s return, repeat steps 3-5 to grab a fresh
one. (Tip: you can also read it from the app console via
`AuthSessionStore.state().accessToken`.)

### Method B - let Postman perform the OAuth login (no copy/paste)

Postman runs the same Authorization Code + PKCE flow and manages/refreshes the
token for you. This requires a one-time redirect-URI addition in Entra.

**One-time Entra setup:**
1. Entra admin center -> **App registrations** -> your app -> **Authentication**.
2. Under the **Single-page application** platform, click **Add URI** and add
   `https://oauth.pstmn.io/v1/callback`. **Save**.

**In Postman**, on the request (or the collection) -> **Authorization** tab:
1. **Auth Type**: `OAuth 2.0`.
2. Under **Configure New Token**, set:
   - **Token Name**: any label (e.g. `Entra Admin`).
   - **Grant Type**: `Authorization Code (With PKCE)`.
   - **Callback URL**: `https://oauth.pstmn.io/v1/callback`.
   - **Auth URL**:
     `https://login.microsoftonline.com/76325907-a5db-46b1-9d5a-cbcca2e63e66/oauth2/v2.0/authorize`
   - **Access Token URL**:
     `https://login.microsoftonline.com/76325907-a5db-46b1-9d5a-cbcca2e63e66/oauth2/v2.0/token`
   - **Client ID**: `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c`
   - **Client Secret**: leave empty (public client using PKCE).
   - **Scope**:
     `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c/access_as_user openid profile offline_access`
   - **Client Authentication**: `Send client credentials in body`.
3. Click **Get New Access Token**, sign in as your Admin user in the popup, then
   **Proceed** / **Use Token**.
4. Postman now attaches the token as the Bearer credential on the request. When it
   expires, click **Get New Access Token** again (the refresh token, from
   `offline_access`, lets Postman renew without a full re-login).

### If you still get 401 after adding a token

- **Expired token** (~1 hour): get a fresh one.
- **Wrong token version**: the backend requires **v2** access tokens. Paste the
  token at https://jwt.ms and confirm `"ver": "2.0"` and
  `"iss": ".../v2.0"`. If it is `1.0` / `sts.windows.net`, set
  `requestedAccessTokenVersion: 2` in the app manifest (see
  `DOC/CONFIGURATION.md`) and sign in again.
- **Copied "Bearer" too**: the Token field should contain only the JWT, not the
  word `Bearer`.

For inspecting the token at jwt.io/jwt.ms and observing the background refresh,
see `entra-backend/VERIFICATION.md`.
