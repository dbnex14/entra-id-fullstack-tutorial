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
migration is `V1__initial_schema.sql`; `V2__item_category_and_history.sql` adds
the `category` column and the `item_history` table (a forward-only change - V1 is
never edited).

## Tables

### item

The sample business data exercised by the role-protected endpoints. Viewer/Admin
may read; Admin may write.

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | BIGSERIAL | PRIMARY KEY | DB-generated identity |
| `name` | VARCHAR(200) | NOT NULL | Item name |
| `description` | TEXT | nullable | Optional description |
| `category` | VARCHAR(100) | nullable | Optional short label (hardware/software/service); added in `V2` |
| `created_by` | VARCHAR(100) | NOT NULL | **Token subject (oid/sub)** of the creator; provenance only, not authorization |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | Creation time |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | Last-modified time |

Index: `idx_item_created_by` on `item(created_by)` - speeds "items created by
this subject" lookups.

### item_history

The change log for items, added by `V2__item_category_and_history.sql`. One row is
written on every create/update/delete of an item. **Audit/change-log only - it
records who changed what and when; it is never read to make an authorization
decision** (R2). Viewer and Admin may read it; only Admin can generate it (only
Admin can write items).

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | BIGSERIAL | PRIMARY KEY | DB-generated identity |
| `item_id` | BIGINT | FK -> `item(id)`, nullable, `ON DELETE SET NULL` | The changed item; becomes `null` after that item is deleted so the record survives |
| `change_type` | VARCHAR(20) | NOT NULL, CHECK in (CREATE, UPDATE, DELETE) | Kind of change |
| `actor_subject` | VARCHAR(100) | NOT NULL | **Token subject (oid/sub)** of the actor; identity/provenance, not authorization |
| `actor_name` | VARCHAR(200) | nullable | Actor's display name (`name` claim) if present |
| `details` | TEXT | NOT NULL | Human-readable summary of the change |
| `changed_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | When the change happened |

Index: `idx_item_history_item_id` on `item_history(item_id)` - speeds the
"history for this item" lookup (`GET /entra-backend/items/{id}/history`).
PostgreSQL does not auto-index foreign-key columns, so this index is added
explicitly.

**Why `ON DELETE SET NULL` (not `CASCADE`).** A CASCADE would delete an item's
history when the item is deleted - erasing the very "this item was deleted" record
we want to keep. SET NULL lets the item row be hard-deleted while its history rows
(including the DELETE entry, written just before removal) remain, with `item_id`
set to `null`.

Rows are written by `ItemService` from within the create/update/delete paths,
after the `@PreAuthorize("hasRole('Admin')")` gate - so every entry is provably
attributable to an authorized Admin. The actor is taken from the validated JWT
(subject + `name` claim), never from client input.

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

1. Create `entra-backend/src/main/resources/db/migration/V3__your_change.sql`
   (next ascending version after the existing `V2`, double underscore before the
   description).
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

## Guarding the forward-only migration rule

Because applied Flyway migrations are immutable (editing one changes its checksum
and breaks `flyway validate`), a Kiro Agent Hook (`Backend: Flyway migration
guard`) warns against editing an existing `V*__*.sql` file and steers changes into
a new, higher-numbered migration. That hook only runs inside Kiro IDE, so the
team-wide equivalent is `flyway validate` in CI. See `DOC/AGENT-HOOKS.md`.
