# API Reference

The backend exposes a small role-protected REST API under `/api/items`. All
endpoints require a valid Entra ID bearer token; authorization is enforced from
the token's `roles` claim.

- Base URL (local): `http://localhost:8080`
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

### GET /api/items

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
curl -i http://localhost:8080/api/items \
  -H "Authorization: Bearer $TOKEN"
```

### POST /api/items

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
curl -i -X POST http://localhost:8080/api/items \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"New item","description":"Optional text"}'
```

### PUT /api/items/{id}

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
curl -i -X PUT http://localhost:8080/api/items/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Renamed","description":"Updated text"}'
```

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

| Identity | GET /api/items | POST /api/items | PUT /api/items/{id} |
| --- | --- | --- | --- |
| Anonymous (no token) | 401 | 401 | 401 |
| Invalid/expired token | 401 | 401 | 401 |
| Viewer | 200 | 403 | 403 |
| Admin | 200 | 201 | 200 |

Notes:
- 401 is produced by the resource-server entry point before endpoint logic runs.
- 403 is produced by method security before the controller body; no mutation
  occurs on a forbidden write.
- 400 is returned for a blank `name` on POST/PUT (bean validation).

## CORS

The API is called cross-origin by the SPA. Only `http://localhost:4200` is
allowed; requests from other origins receive no `Access-Control-Allow-Origin`
header and are blocked by the browser. Methods GET/POST/PUT/DELETE/OPTIONS and
headers Authorization/Content-Type are permitted, with credentials allowed. See
`DOC/SECURITY.md` and `DOC/CONFIGURATION.md`.

## Health

`/actuator/health` is permitted without authentication (used for liveness). All
other paths require a valid token.

## Obtaining a token for manual testing

The realistic way is to sign in through the SPA and copy the bearer token from
the browser Network tab (or `AuthSessionStore.state().accessToken`). Full steps,
including inspecting the token at jwt.io and observing refresh, are in
`entra-backend/VERIFICATION.md`.
