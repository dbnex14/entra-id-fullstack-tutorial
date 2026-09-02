# Database

The backend persists to **PostgreSQL**. The schema is owned and versioned by
**Flyway**; Hibernate runs in `validate` mode and never creates or alters tables.

- Connection (local): `jdbc:postgresql://localhost:5432/my_workspace`
- Credentials (local): user `postgres`, password `postgres`
- Migrations: `entra-backend/src/main/resources/db/migration/`

## Schema ownership: Flyway, not the ORM

On startup Flyway applies any pending migrations in ascending version order and
records each in the `flyway_schema_history` ledger. Because
`spring.jpa.hibernate.ddl-auto` is set to `validate`, Hibernate only checks that
the JPA entities line up with the Flyway-created tables - if they disagree,
startup fails fast rather than silently mutating the schema. This makes the schema
reproducible ("schema is code") and prevents drift.

Migration file naming follows `V<version>__<description>.sql`. The initial
migration is `V1__initial_schema.sql`.

## Tables

### item

The sample business data exercised by the role-protected endpoints. Viewer/Admin
may read; Admin may write.

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | BIGSERIAL | PRIMARY KEY | DB-generated identity |
| `name` | VARCHAR(200) | NOT NULL | Item name |
| `description` | TEXT | nullable | Optional description |
| `created_by` | VARCHAR(100) | NOT NULL | **Token subject (oid/sub)** of the creator; provenance only, not authorization |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | Creation time |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | Last-modified time |

Index: `idx_item_created_by` on `item(created_by)` - speeds "items created by
this subject" lookups.

### app_user

An app-level profile record derived from token claims. **Audit/profile only - it
is never used for authorization.** Kept so the system can display/trace which
identities have been seen.

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | BIGSERIAL | PRIMARY KEY | |
| `subject` | VARCHAR(100) | NOT NULL UNIQUE | Entra oid/sub - one row per identity |
| `display_name` | VARCHAR(200) | nullable | |
| `first_seen_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| `last_seen_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | |

### access_audit

An audit trail of security-relevant requests, for traceability and the
verification story. **Audit only - it records the OUTCOME of a claim-driven
decision and is never read to MAKE one.**

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | BIGSERIAL | PRIMARY KEY | |
| `subject` | VARCHAR(100) | NOT NULL | Token subject that made the request |
| `authorities` | TEXT | NOT NULL | Serialized `ROLE_*` set granted for the request |
| `http_method` | VARCHAR(10) | NOT NULL | e.g. GET, POST |
| `path` | VARCHAR(300) | NOT NULL | e.g. /entra-backend/items |
| `status` | INTEGER | NOT NULL | HTTP response status |
| `occurred_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | When it happened |

Index: `idx_access_audit_subject` on `access_audit(subject)` - speeds "audit
trail for this subject" lookups.

Rows are written by `AccessAuditFilter` after each request completes (so the
status is known). A failure to write an audit row never affects the response.

### flyway_schema_history

Created and maintained by Flyway itself. Records which migrations have been
applied, their checksums, and success/failure. Do not edit by hand.

## Type mapping notes

- `BIGSERIAL` maps to a `Long` id with `GenerationType.IDENTITY` (the database
  assigns the value on insert).
- `TIMESTAMPTZ` (timestamp with time zone) maps to `java.time.OffsetDateTime`,
  which preserves the UTC offset. The JPA entities use `OffsetDateTime`
  accordingly.

## Inspecting the database

```bash
# list tables
psql "postgresql://postgres:postgres@localhost:5432/my_workspace" -c "\dt"

# confirm the migration applied
psql "postgresql://postgres:postgres@localhost:5432/my_workspace" \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"

# view the audit trail (after exercising the API)
psql "postgresql://postgres:postgres@localhost:5432/my_workspace" \
  -c "SELECT subject, authorities, http_method, path, status, occurred_at FROM access_audit ORDER BY occurred_at DESC LIMIT 20;"
```

## Adding a new migration

1. Create `entra-backend/src/main/resources/db/migration/V2__your_change.sql`
   (next ascending version, double underscore before the description).
2. Write the DDL/DML. PostgreSQL runs DDL transactionally, so a failing migration
   rolls back and halts startup rather than leaving a half-applied schema.
3. If the change affects a JPA entity, update the entity to match (remember:
   `ddl-auto: validate` will fail startup on any mismatch).
4. Restart the backend - Flyway applies the new migration and records it.

Do not edit an already-applied migration; Flyway's checksum validation will halt
startup if a previously-applied file changes. Make a new versioned migration
instead.

## Local database setup

```bash
createdb my_workspace          # database only; tables are created by Flyway
```

On macOS with Homebrew Postgres, you may also need to create the `postgres` role
(see `GUIDE/RUNNING-MAC-GUIDE.md`). Connection settings live in
`application.yml` (see `DOC/CONFIGURATION.md`).
