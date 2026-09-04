-- =============================================================================
-- V2__item_category_and_history.sql
-- =============================================================================
-- Flyway migration file (the SECOND versioned migration in this project).
--
-- WHY A NEW FILE INSTEAD OF EDITING V1 (the golden rule of Flyway):
--   Flyway migrations are IMMUTABLE once applied. When Flyway runs V1 it records a
--   checksum of the file's contents in the flyway_schema_history ledger. On every
--   later startup it re-hashes the file and compares. If we edited
--   V1__initial_schema.sql after it had been applied to any database, the checksum
--   would no longer match and Flyway would HALT startup with a validation error
--   ("Migration checksum mismatch"). So schema changes are always FORWARD-ONLY: we
--   add a new, higher-numbered migration (V2) that ALTERs/CREATEs on top of what V1
--   built. This keeps the schema reproducible from an ordered series of files and
--   lets every environment (dev, CI, prod) converge to the same state by replaying
--   the same migrations in the same order.
--
-- NAMING CONVENTION (must be exact or Flyway ignores the file):
--   V<version>__<description>.sql
--     * "V"                          -> a versioned (not repeatable "R") migration
--     * "2"                          -> ascending version; runs AFTER V1
--     * "__" (DOUBLE underscore)     -> separates version from description
--     * "item_category_and_history"  -> human-readable description stored in the ledger
--
-- OWNERSHIP / LIFECYCLE (unchanged from V1):
--   The schema is owned by Flyway, not Hibernate. The Resource_Server runs with
--   spring.jpa.hibernate.ddl-auto=validate, so JPA only VALIDATES that the entities
--   line up with these tables at startup -- it never creates or alters them. If the
--   Item / ItemHistory entities disagree with the columns below (name, type,
--   nullability), startup fails fast rather than drifting silently.
--
-- POSTGRESQL / TRANSACTIONAL DDL:
--   PostgreSQL runs DDL inside a transaction. If any statement below fails, the whole
--   migration rolls back and startup halts -- the database is never left half-migrated.
--
-- SECURITY / IDENTITY MODEL (important for the new item_history table):
--   Authorization in this system is CLAIM-DRIVEN (R2): the ROLE_* authorities that gate
--   the REST endpoints come exclusively from the "roles" claim in the validated
--   Access_Token. The item_history table added here is an AUDIT / CHANGE-LOG table in
--   the same spirit as access_audit: it records WHO changed WHAT and WHEN, where "who"
--   is the token subject (oid/sub) plus display name captured from the validated token.
--   It is written AFTER an authorization decision has already been made; it is NEVER
--   consulted to MAKE an authorization decision.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Add the optional "category" column to the existing item table.
-- -----------------------------------------------------------------------------
-- A short, optional label such as 'hardware', 'software', or 'service'. It is
-- NULLABLE on purpose:
--   * Existing rows created before this migration have no category, and a NOT NULL
--     column would require a backfill/default to add safely to a populated table.
--   * The feature spec makes category optional for every item.
-- VARCHAR(100) is generous for a short label while still bounding the length (the
-- entity mapping will mirror this exactly for the ddl-auto=validate check).
ALTER TABLE item
    ADD COLUMN category VARCHAR(100);


-- -----------------------------------------------------------------------------
-- 2. Create the item_history change-log table.
-- -----------------------------------------------------------------------------
-- One row is inserted every time an item is created, updated, or deleted, capturing:
--   * which item the change concerned (item_id),
--   * the kind of change (change_type: CREATE / UPDATE / DELETE),
--   * WHO made it (actor_subject = token oid/sub; actor_name = display name if present),
--   * a human-readable summary of the change (details),
--   * WHEN it happened (changed_at).
--
-- This is the durable "audit trail" for item mutations that both Viewer and Admin can
-- READ (GET), while only Admin can generate (because only Admin can write items).
CREATE TABLE item_history (
    id            BIGSERIAL PRIMARY KEY,

    -- FOREIGN KEY back to the item this change concerned.
    --
    -- Why the column is NULLABLE and the FK uses ON DELETE SET NULL:
    --   We want the history of a DELETE to SURVIVE the removal of the item row.
    --   If we used ON DELETE CASCADE, deleting an item would also delete its history
    --   -- erasing the very record that says "this item was deleted", which defeats the
    --   purpose of an audit log. With ON DELETE SET NULL, the item row can be hard-deleted
    --   while its history rows remain, their item_id simply becoming NULL. (The DELETE
    --   history row is written BEFORE the item is removed, so it captures the final state.)
    item_id       BIGINT,

    -- The kind of change. Stored as a short text code rather than a native ENUM so the
    -- set of values can evolve without a Postgres type migration, and so it maps cleanly
    -- to a Java enum (ItemHistory.ChangeType) via its name(). A CHECK constraint documents
    -- and enforces the allowed values at the database level.
    change_type   VARCHAR(20) NOT NULL,

    -- WHO made the change -- the token SUBJECT (oid/sub claim) of the authenticated caller.
    -- This is identity/provenance data captured from the validated Access_Token by the
    -- service layer; it is NEVER client-supplied and NEVER used for authorization (R2).
    actor_subject VARCHAR(100) NOT NULL,

    -- The caller's human-readable display name ("name" claim), if the token carried one.
    -- Nullable because not every token necessarily includes a "name" claim; the subject
    -- above is the stable, always-present identifier.
    actor_name    VARCHAR(200),

    -- A short human-readable summary of what changed (e.g. the item name and category, or
    -- "name: 'A' -> 'B'"). TEXT because summaries can be longer than a label.
    details       TEXT NOT NULL,

    -- WHEN the change happened. TIMESTAMPTZ (with time zone) maps cleanly to Java
    -- OffsetDateTime, matching the item / access_audit convention. DEFAULT now() lets the
    -- database populate it, though the service sets it explicitly for a deterministic,
    -- application-visible value.
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The foreign-key constraint itself. Naming it explicitly (fk_item_history_item) makes
    -- it easy to find in \d output and error messages. ON DELETE SET NULL preserves history
    -- past a hard delete of the item (see the item_id comment above).
    CONSTRAINT fk_item_history_item
        FOREIGN KEY (item_id) REFERENCES item (id) ON DELETE SET NULL,

    -- Document + enforce the allowed change_type values at the DB boundary. If application
    -- code ever tried to insert an unexpected value, the insert would fail rather than
    -- silently storing garbage.
    CONSTRAINT chk_item_history_change_type
        CHECK (change_type IN ('CREATE', 'UPDATE', 'DELETE'))
);


-- -----------------------------------------------------------------------------
-- 3. Index the foreign key.
-- -----------------------------------------------------------------------------
-- The primary read pattern for history is "show me the change history for THIS item"
-- (GET /entra-backend/items/{id}/history), which filters by item_id. PostgreSQL does NOT
-- automatically index foreign-key columns, so without this index that lookup would be a
-- sequential scan as the table grows. The index makes the per-item history query efficient.
CREATE INDEX idx_item_history_item_id ON item_history (item_id);
