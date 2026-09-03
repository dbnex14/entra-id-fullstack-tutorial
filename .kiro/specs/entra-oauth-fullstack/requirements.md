# Requirements Document

## Introduction

This feature delivers a decoupled full-stack application secured by Microsoft Entra ID
using the OAuth2 Authorization Code Flow with PKCE. The system is composed of three
independently deployed tiers:

- An **Angular 19 Standalone client** (port 4200) that initiates authentication, holds
  session state in signals, and calls protected APIs with bearer tokens.
- A **Spring Boot 3 Resource Server** (JDK 21, port 8080) that validates JWT access
  tokens issued by Entra ID, maps identity claims to Spring authorities, and exposes
  role-protected REST endpoints.
- A **native Windows PostgreSQL 17 database** (port 5432, database `my_workspace`)
  whose schema is owned and versioned by Flyway migrations.

The identity boundary is Microsoft Entra ID acting as the OAuth2 Authorization Server
and OpenID Connect provider. The client authenticates users, obtains access and refresh
tokens, and refreshes access tokens silently in the background. The Resource Server never
issues tokens; it only validates them and derives authorization decisions from the
`roles` claim.

The eventual implementation is intended as an instructional reference: code files carry
extensive comments explaining how identity payloads, tokens, and data flow down the stack,
and the design includes verification procedures using tools such as jwt.io and browser
network inspection.

## Glossary

- **Entra_ID**: Microsoft Entra ID, the external OAuth2 Authorization Server and OpenID Connect identity provider that authenticates users and issues tokens.
- **Client_App**: The Angular 19 Standalone single-page application served on `http://localhost:4200`.
- **Resource_Server**: The Spring Boot 3 application (JDK 21) served on `http://localhost:8080` that validates JWT access tokens and exposes protected REST endpoints.
- **Database**: The native Windows PostgreSQL 17 instance on `localhost:5432`, database `my_workspace`, accessed with user `postgres`.
- **Flyway_Migrator**: The Flyway component (flyway-core plus flyway-database-postgresql v10 or later) that applies versioned SQL migrations to the Database.
- **Access_Token**: A JWT issued by Entra_ID, presented by the Client_App to the Resource_Server as a bearer credential.
- **Refresh_Token**: A credential issued by Entra_ID that the Client_App exchanges for a new Access_Token without user interaction.
- **Authorization_Code_Flow_PKCE**: The OAuth2 Authorization Code Flow using Proof Key for Code Exchange (RFC 7636).
- **Roles_Claim**: The `roles` claim in the Access_Token, a JSON array of role strings assigned in Entra_ID (values `Admin` and `Viewer`).
- **Spring_Authority**: A `GrantedAuthority` in Spring Security derived from a Roles_Claim value, prefixed with `ROLE_`.
- **Tenant_ID**: The Entra_ID directory identifier `76325907-a5db-46b1-9d5a-cbcca2e63e66`.
- **Client_ID**: The Entra_ID application identifier `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c`.
- **API_Scope**: The delegated permission scope `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c/access_as_user`.
- **JWT_Decoder**: The Resource_Server component that validates and decodes the Access_Token and extracts claims.
- **CORS_Policy**: The Cross-Origin Resource Sharing configuration governing browser requests from the Client_App origin to the Resource_Server.
- **Migration_File**: A versioned SQL file consumed by the Flyway_Migrator following the naming convention `V<version>__<description>.sql`.

## Requirements

### Requirement 1: User Authentication via Authorization Code Flow with PKCE

**User Story:** As a user, I want to sign in through Microsoft Entra ID, so that I can access protected features with my organizational identity.

#### Acceptance Criteria

1. WHEN an unauthenticated user requests a protected route, THE Client_App SHALL persist the originally requested route and initiate the Authorization_Code_Flow_PKCE by redirecting the browser to the Entra_ID authorization endpoint for Tenant_ID `76325907-a5db-46b1-9d5a-cbcca2e63e66`.
2. WHEN the Client_App initiates authentication, THE Client_App SHALL generate a PKCE code verifier of between 43 and 128 characters and derive a code challenge using the SHA-256 method.
3. WHEN the Client_App requests authorization, THE Client_App SHALL include Client_ID `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c`, the API_Scope `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c/access_as_user`, the `openid`, `profile`, and `offline_access` scopes, and a unique state value in the authorization request.
4. WHEN Entra_ID redirects back with an authorization code, THE Client_App SHALL exchange the authorization code and the PKCE code verifier for an Access_Token and a Refresh_Token at the Entra_ID token endpoint.
5. WHEN the token exchange succeeds, THE Client_App SHALL store the authenticated session state in a signal-based store.
6. IF the token exchange fails, THEN THE Client_App SHALL display an error message indicating that authentication failed, discard any partial session state, and return the user to the unauthenticated state.
7. WHEN authentication completes successfully AND a persisted originally requested route exists, THE Client_App SHALL redirect the user to that originally requested route.
8. WHEN authentication completes successfully AND no persisted originally requested route exists, THE Client_App SHALL redirect the user to the default authenticated landing route.
9. IF the state value in the Entra_ID redirect does not match the state value sent in the authorization request, THEN THE Client_App SHALL reject the redirect, discard any partial session state, and return the user to the unauthenticated state with an error message indicating an invalid authentication response.
10. IF Entra_ID redirects back with an authorization error instead of an authorization code, THEN THE Client_App SHALL display an error message indicating that authorization was not granted and return the user to the unauthenticated state.

### Requirement 2: Role Assignment from the Roles Claim

**User Story:** As a security administrator, I want application roles derived from the Entra ID roles claim, so that access is controlled by centrally managed role assignments.

#### Acceptance Criteria

1. WHEN the Resource_Server receives a request bearing an Access_Token, THE Resource_Server SHALL read the Roles_Claim array from the validated Access_Token.
2. WHEN the Resource_Server reads a Roles_Claim array, THE Resource_Server SHALL create one Spring_Authority for each distinct value by prefixing the case-sensitive role value with `ROLE_`.
3. WHEN a request carries a Roles_Claim value of `Admin`, THE Resource_Server SHALL grant the Spring_Authority `ROLE_Admin`.
4. WHEN a request carries a Roles_Claim value of `Viewer`, THE Resource_Server SHALL grant the Spring_Authority `ROLE_Viewer`.
5. IF the validated Access_Token contains no Roles_Claim, THEN THE Resource_Server SHALL grant zero Spring_Authority values for that request.
6. IF the Roles_Claim is present but is not a JSON array of strings, THEN THE Resource_Server SHALL treat the request as carrying zero Spring_Authority values.
7. WHEN a request lacks the Spring_Authority required by a protected endpoint, THE Resource_Server SHALL respond with HTTP status 403.

### Requirement 3: Access Token Expiration Handling

**User Story:** As a user, I want expired access tokens to be rejected and handled gracefully, so that stale credentials cannot access protected resources.

#### Acceptance Criteria

1. WHEN the Resource_Server receives an Access_Token whose expiration time is earlier than the current server time minus a clock skew allowance of 60 seconds, THE Resource_Server SHALL reject the request with HTTP status 401.
2. WHEN the Resource_Server rejects an expired Access_Token, THE Resource_Server SHALL include a `WWW-Authenticate` response header indicating the token is expired.
3. WHEN the Client_App receives an HTTP 401 response from the Resource_Server that includes a `WWW-Authenticate` header indicating the token is expired, THE Client_App SHALL attempt at most one background token refresh before surfacing an error to the user.
4. IF the Client_App holds an Access_Token whose recorded expiration time is within 60 seconds of the current client time, THEN THE Client_App SHALL initiate a background token refresh before issuing the next protected request.
5. IF a background token refresh fails or the Client_App holds no valid Refresh_Token, THEN THE Client_App SHALL discard the stored Access_Token and surface an error to the user indicating that re-authentication is required.
6. WHEN a background token refresh completes successfully, THE Client_App SHALL retry the original protected request exactly once using the new Access_Token.

### Requirement 4: Secure Background Refresh Token Cycles

**User Story:** As a user, I want my session to renew silently, so that I remain signed in without repeated interactive logins.

#### Acceptance Criteria

1. WHEN the Access_Token is within 300 seconds of its expiration time, THE Client_App SHALL trigger a background token refresh that exchanges the Refresh_Token for a new Access_Token at the Entra_ID token endpoint without user interaction, completing within 30 seconds.
2. WHEN the Entra_ID token endpoint returns a new Refresh_Token during a refresh, THE Client_App SHALL replace the stored Refresh_Token with the newly issued Refresh_Token.
3. WHEN a background refresh succeeds, THE Client_App SHALL update the signal-based session state with the new Access_Token and its expiration time expressed as an absolute timestamp.
4. WHILE a background refresh is in progress, THE Client_App SHALL queue outbound protected requests, and WHEN the refresh completes successfully, THE Client_App SHALL release all queued requests using the new Access_Token.
5. IF the Refresh_Token is expired or rejected by Entra_ID, THEN THE Client_App SHALL clear the stored session state, fail all queued outbound protected requests with an error indicating the session ended, and initiate the Authorization_Code_Flow_PKCE.
6. IF a background refresh fails due to a transient network or endpoint error rather than token rejection, THEN THE Client_App SHALL retry the refresh up to 3 times with increasing delay, and IF all 3 retries fail, THEN THE Client_App SHALL clear the stored session state and initiate the Authorization_Code_Flow_PKCE.
7. THE Client_App SHALL store the Refresh_Token such that it is not accessible to scripts originating from any domain other than the Client_App origin.

### Requirement 5: CORS Between Client and Resource Server

**User Story:** As a frontend developer, I want the Angular client to call the Spring Boot API across origins, so that the decoupled tiers communicate in the browser.

#### Acceptance Criteria

1. WHEN the Resource_Server receives a cross-origin request from origin `http://localhost:4200`, THE Resource_Server SHALL include the `Access-Control-Allow-Origin` header set to `http://localhost:4200` in the response.
2. WHEN the browser sends a CORS preflight `OPTIONS` request to the Resource_Server, THE Resource_Server SHALL respond with HTTP status 200.
3. WHEN the Resource_Server responds to a preflight request, THE Resource_Server SHALL include the allowed methods `GET`, `POST`, `PUT`, `DELETE`, and `OPTIONS` in the `Access-Control-Allow-Methods` header.
4. WHEN the Resource_Server responds to a preflight request, THE Resource_Server SHALL include `Authorization` and `Content-Type` in the `Access-Control-Allow-Headers` header.
5. WHEN the Resource_Server responds to a cross-origin request from origin `http://localhost:4200`, THE Resource_Server SHALL include the `Access-Control-Allow-Credentials` header set to `true`.
6. IF a cross-origin request originates from an origin other than `http://localhost:4200`, THEN THE Resource_Server SHALL omit the `Access-Control-Allow-Origin` header from the response.

### Requirement 6: Custom JWT Claim Decoding

**User Story:** As a backend developer, I want the resource server to validate and decode Entra ID tokens with custom claim handling, so that authorization is based on trusted, correctly parsed claims.

#### Acceptance Criteria

1. WHEN the Resource_Server starts, THE JWT_Decoder SHALL load the Entra_ID signing keys from the OpenID Connect discovery endpoint for Tenant_ID `76325907-a5db-46b1-9d5a-cbcca2e63e66`.
2. IF the JWT_Decoder cannot load the Entra_ID signing keys at startup, THEN THE Resource_Server SHALL report a startup error indicating the signing keys were unreachable.
3. WHEN the Resource_Server receives an Access_Token, THE JWT_Decoder SHALL verify the token signature against the Entra_ID signing keys.
4. WHEN the JWT_Decoder validates an Access_Token, THE JWT_Decoder SHALL verify that the `aud` claim equals Client_ID `4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c` or the API_Scope audience `api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c`.
5. WHEN the JWT_Decoder validates an Access_Token, THE JWT_Decoder SHALL verify that the `iss` claim identifies Entra_ID for Tenant_ID `76325907-a5db-46b1-9d5a-cbcca2e63e66`.
6. WHEN the JWT_Decoder decodes a valid Access_Token, THE JWT_Decoder SHALL convert the Roles_Claim into Spring_Authority values as defined in Requirement 2.
7. IF the Access_Token signature verification fails, THEN THE Resource_Server SHALL reject the request with HTTP status 401.
8. IF the `aud` claim or `iss` claim does not match the expected values, THEN THE Resource_Server SHALL reject the request with HTTP status 401.

### Requirement 7: Flyway Schema Definitions

**User Story:** As a database owner, I want the schema defined and versioned through Flyway migrations, so that the database structure is reproducible and tracked.

#### Acceptance Criteria

1. WHEN the Resource_Server starts, THE Flyway_Migrator SHALL connect to the Database at `localhost:5432`, database `my_workspace`, using user `postgres`, within 10 seconds.
2. IF the Flyway_Migrator cannot establish a connection to the Database within 10 seconds, THEN THE Flyway_Migrator SHALL halt startup and report a connection error indicating the Database was unreachable.
3. WHEN the Flyway_Migrator runs, THE Flyway_Migrator SHALL apply all pending Migration_File items in ascending version order.
4. THE Flyway_Migrator SHALL record each applied Migration_File in the `flyway_schema_history` table.
5. WHEN a Migration_File has already been recorded as applied, THE Flyway_Migrator SHALL skip that Migration_File on subsequent starts.
6. IF the checksum of an applied Migration_File differs from the recorded checksum, THEN THE Flyway_Migrator SHALL halt startup and report a validation error, leaving the Database schema unchanged.
7. IF a Migration_File fails to apply, THEN THE Flyway_Migrator SHALL halt startup, roll back the changes of the failed Migration_File, and report a migration error indicating which Migration_File failed.
8. THE Flyway_Migrator SHALL use flyway-core together with flyway-database-postgresql at version 10 or later.
9. THE initial Migration_File SHALL follow the naming convention `V<version>__<description>.sql`.

### Requirement 8: Role-Protected REST Endpoints

**User Story:** As an application owner, I want API endpoints protected by role, so that Admin and Viewer users receive appropriately scoped access.

#### Acceptance Criteria

1. WHEN a request bearing a valid Access_Token with Spring_Authority `ROLE_Viewer` targets a read endpoint, THE Resource_Server SHALL respond with HTTP status 200 and the requested data.
2. WHEN a request bearing a valid Access_Token with Spring_Authority `ROLE_Admin` targets a write endpoint, THE Resource_Server SHALL perform the write operation and respond with HTTP status 200 or 201.
3. WHEN a request bearing a valid Access_Token with only Spring_Authority `ROLE_Viewer` targets a write endpoint, THE Resource_Server SHALL respond with HTTP status 403 and SHALL NOT modify any persisted data.
4. IF a request to a protected endpoint carries no Access_Token, THEN THE Resource_Server SHALL respond with HTTP status 401.
5. IF a request to a protected endpoint carries an Access_Token that is expired, malformed, or fails signature validation, THEN THE Resource_Server SHALL respond with HTTP status 401 and SHALL NOT process the requested operation.
6. WHEN a request bearing a valid Access_Token with Spring_Authority `ROLE_Admin` targets the delete endpoint (`DELETE /entra-backend/items/{id}`), THE Resource_Server SHALL delete the item and respond with HTTP status 204; a caller with only `ROLE_Viewer` SHALL receive 403 with no row removed, and a request for a non-existent id SHALL receive 404.

### Requirement 9: Session Lifecycle and Application Shell (Client)

**User Story:** As a user, I want a clear landing page and explicit sign-in/sign-out controls, so that I choose when to authenticate and can end my session.

#### Acceptance Criteria

1. WHEN a user requests sign-out, THE Client_App SHALL clear the local session state AND end the Entra_ID session via a logout redirect, then return the browser to the application origin.
2. WHEN an unauthenticated user opens the application root or an unrecognized route, THE Client_App SHALL display a public landing page and SHALL NOT initiate authentication until the user requests it.
3. WHILE a session is authenticated, THE Client_App SHALL display the current roles and navigation appropriate to those roles, showing the Admin area only to users whose session carries the `Admin` role (a client-side convenience; the Resource_Server remains authoritative).
