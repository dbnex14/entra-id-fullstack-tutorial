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
token for you. This is the "hands-free" method for repeated testing.

**The catch (read this first).** Our Entra app registration exposes its client id
**only under the Single-page application (SPA) platform**. A SPA-platform client
carries a special restriction: Entra will only redeem its authorization code for a
token via a **cross-origin browser request** (one that carries an `Origin` header,
i.e. issued by JavaScript in a browser). Postman's default "Get New Access Token"
performs the code->token exchange **server-side** (from the Postman desktop agent,
with no browser `Origin`), so Entra rejects it with:

> **AADSTS9002327**: Tokens issued for the 'Single-Page Application' client-type may
> only be redeemed via cross-origin requests.

This is by design and cannot be fixed by changing scopes or the callback URL alone.
Two configurations resolve it. **Option B1 is recommended and is the verified,
working setup for this app.**

#### Option B1 (recommended, verified) - add a native redirect URI so Postman's server-side exchange is allowed

A **Mobile and desktop applications** (native/public-client) redirect URI does NOT
carry the SPA cross-origin restriction, so Postman's normal token exchange succeeds.
You keep a single app registration; the SPA platform (used by the Angular app) and
the native platform (used by Postman) coexist on the same client id.

**One-time Entra setup:**
1. Entra admin center -> **App registrations** -> **EntraOAuth** ->
   **Manage** -> **Authentication**.
2. In **Platform configurations**, click **+ Add a platform** ->
   **Mobile and desktop applications**.
3. That panel shows three Microsoft-suggested redirect URIs with checkboxes
   (`https://login.microsoftonline.com/common/oauth2/nativeclient`,
   `https://login.live.com/oauth20_desktop.srf`, and an `msal<client-id>://auth`
   entry). **Leave all three unchecked** - none of them are for Postman. Instead, in
   the **Custom redirect URIs** free-text field below them, type exactly:
   `https://oauth.pstmn.io/v1/callback`
4. Click **Configure**, then **Save**. (Leave the existing **Single-page
   application** platform and its `http://localhost:4200` URI exactly as-is - the
   Angular app still needs it.)

   The end state you want:
   - **Single-page application:** `http://localhost:4200` (only)
   - **Mobile and desktop applications:** `https://oauth.pstmn.io/v1/callback`

   > **"Redirect URIs must have distinct values" on Configure.** Entra requires every
   > redirect URI to be unique **across all platforms**. If you previously tried the
   > SPA approach you likely already added `https://oauth.pstmn.io/v1/callback` (and/or
   > `.../v1/browser-callback`) under the **Single-page application** platform, and
   > Entra now refuses to add the same value again. Fix: under **Single-page
   > application**, **delete** the `https://oauth.pstmn.io/v1/callback` row and
   > **Save**, then add it under **Mobile and desktop applications** as in steps 3-4.
   > (A stray `.../v1/browser-callback` under SPA can be deleted too unless you plan to
   > use Option B2.)

   > **If "+ Add a platform" is missing:** make sure you opened the **App
   > registration** (breadcrumb: *App registrations*), not the **Enterprise
   > application** - the latter has no platform configuration. As a fallback you can
   > add the URI via **Manage -> Manifest** by putting
   > `"https://oauth.pstmn.io/v1/callback"` into the `publicClient.redirectUris`
   > array (that array *is* the "Mobile and desktop applications" platform) and
   > leaving the `spa` block untouched.

**In Postman**, on the request (or the collection) -> **Authorization** tab:
1. **Auth Type**: `OAuth 2.0`.
2. Leave **"Authorize using browser" UNCHECKED** (this is the key difference from
   Option B2 - we want Postman's own server-side exchange, which the native platform
   now permits).
3. Under **Configure New Token**, set:
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
4. Click **Get New Access Token**, sign in as a **tenant** user holding the **Admin**
   role in the popup (see "Which account to sign in with" below), then
   **Proceed** / **Use Token**.
5. Postman attaches the token as the Bearer credential on the request. When it
   expires, click **Get New Access Token** again; the refresh token (from
   `offline_access`) lets Postman renew without a full re-login.

**Verify it worked:** send `GET http://localhost:8080/entra-backend/items` (expect
**200**), then `POST http://localhost:8080/entra-backend/items` with body
`{"name":"From Postman"}` (expect **201** for an Admin token; **403** means the token
lacks the Admin role).

#### Option B2 (SPA-only alternative) - force Postman through the browser

If you cannot add a native platform (e.g. org policy) and must keep the app SPA-only,
Postman's **"Authorize using browser"** option runs the entire flow - including the
token exchange - in your real browser. That makes the redemption genuinely
cross-origin, satisfying the SPA rule.

**One-time Entra setup:** under the **Single-page application** platform, **Add URI**
`https://oauth.pstmn.io/v1/browser-callback` and **Save**. (Note this is the
`browser-callback` URI, which differs from Option B1's `/v1/callback`.)

**In Postman:** same fields as Option B1, except:
- **Check** the **"Authorize using browser"** box.
- **Callback URL** becomes `https://oauth.pstmn.io/v1/browser-callback` (Postman
  fills this automatically when the box is checked).

Tradeoff: no platform change, but it depends on Postman's browser interception and is
less reliable than B1. If it misbehaves, use B1.

#### Option B3 - a separate app registration for API testing

Only if org policy forbids adding a native platform to the SPA app. Create a second
app registration configured as **Mobile and desktop applications** with redirect URI
`https://oauth.pstmn.io/v1/callback`, grant it the `access_as_user` scope of this API
(**API permissions** -> add a permission -> **My APIs** -> EntraOAuth), and use its
client id in Postman. Tradeoff: a second client id and separate consent to manage; the
role claim still comes from the signed-in user, so no extra role assignment is needed.
More moving parts - prefer B1.

### Which account to sign in with (avoiding the wrong-account error)

Sign in with an identity that is a **member or guest of this tenant** AND is assigned
the **Admin** app role (for POST/PUT/DELETE) - for read-only testing, a **Viewer**
user is enough (GET only).

- Your personal outlook.com account works **only because it was added to the tenant
  as a guest/member** and given the Admin role. `viewer@<tenant>.onmicrosoft.com`
  covers the Viewer path.
- Do **not** sign in with an arbitrary Microsoft/Google account that is not in the
  tenant. Signing in with `...@gmail.com` (identity provider `live.com`/Google)
  produced **AADSTS50020** ("user account ... does not exist in tenant ... and cannot
  access the application"). If you see that, you picked the wrong account in the
  popup: use the popup's **"Use another account"** / **Sign in with a different
  account** link and choose the tenant user. Signing out of other Microsoft sessions
  first, or using a fresh/incognito browser profile, avoids the popup silently reusing
  a wrong cached account.

### Troubleshooting Postman / Entra errors

| Symptom | Cause | Fix |
| --- | --- | --- |
| **AADSTS90102** - `'redirect_uri' value must be a valid absolute URI` | The **Callback URL** field in Postman was blank or not a valid absolute URI, so no (or a malformed) `redirect_uri` was sent. | Set **Callback URL** to `https://oauth.pstmn.io/v1/callback` (Option B1) or, with "Authorize using browser" checked, `https://oauth.pstmn.io/v1/browser-callback` (Option B2). The exact value must also be registered on the app (see each option). |
| **AADSTS50020** - `User account '...@gmail.com' from identity provider 'live.com' does not exist in tenant ... and cannot access the application` | You signed in with an account that is **not a member/guest of this tenant** (e.g. a personal Google/Microsoft account that was never invited). | In the sign-in popup choose **Use another account** and sign in as a tenant user/guest that holds the required app role. Invite the account as a guest and assign a role first if needed. |
| **AADSTS9002327** - `Tokens issued for the 'Single-Page Application' client-type may only be redeemed via cross-origin requests` | The app is registered **SPA-only**, and Postman tried a **server-side** code->token exchange (no browser `Origin`), which Entra forbids for SPA clients. | Use **Option B1** (add a **Mobile and desktop applications** redirect URI `https://oauth.pstmn.io/v1/callback` and leave "Authorize using browser" **unchecked**), or **Option B2** (keep SPA-only, **check** "Authorize using browser", register `.../v1/browser-callback`). |
| **"Redirect URIs must have distinct values"** when clicking **Configure**/**Save** in Entra | You're trying to add `https://oauth.pstmn.io/v1/callback` to one platform while the **same URI already exists on another platform** (typically left under **Single-page application** from an earlier Method B attempt). Entra requires redirect URIs to be unique across all platforms. | Delete the duplicate `https://oauth.pstmn.io/v1/callback` from the **Single-page application** platform and **Save**, then add it under **Mobile and desktop applications**. Also remove any stray `.../v1/browser-callback` unless using Option B2. |

### If you still get 401 after adding a token

- **Expired token** (~1 hour): get a fresh one.
- **Wrong token version**: the backend requires **v2** access tokens. Paste the
  token at https://jwt.ms and confirm `"ver": "2.0"` and
  `"iss": ".../v2.0"`. If it is `1.0` / `sts.windows.net`, set
  `requestedAccessTokenVersion: 2` in the app manifest (see
  `DOC/CONFIGURATION.md`) and sign in again.
- **Copied "Bearer" too**: the Token field should contain only the JWT, not the
  word `Bearer`.
- **403 (not 401) on POST/PUT/DELETE**: the token is valid but lacks the `Admin`
  role. Sign in as an Admin user (a Viewer token can only GET).

For inspecting the token at jwt.io/jwt.ms and observing the background refresh,
see `entra-backend/VERIFICATION.md`.
