-- =============================================================================
-- V3__public_ids.sql
-- =============================================================================
-- Flyway migration file (the THIRD versioned migration in this project).
--
-- WHY THIS MIGRATION EXISTS (the security lesson):
--   The item and item_history tables use a sequential BIGSERIAL primary key
--   (id). Until now that internal id was also the identifier the REST API sent
--   over the wire (in JSON bodies and in /items/{id} URLs). Sequential ids are
--   GUESSABLE and ENUMERABLE: seeing id=20 tells an observer that ~20 rows exist
--   and that ids 1..20 probably existed, which is information disclosure. That is
--   fine for an "item" but would be sensitive in a "customer"/"invoice"/"patient"
--   manager -- and this project is a learning reference that should model the
--   industry-standard practice.
--
--   THE FIX (defence in depth, not the primary control): keep the fast BIGINT as
--   the INTERNAL primary key and all foreign keys, and add a separate OPAQUE
--   public identifier -- public_id -- that is what the API exposes. The real
--   access control remains claim-driven role checks (@PreAuthorize on the token's
--   `roles`); the opaque id is an ADDITIONAL layer that removes the count/order
--   disclosure and makes ids non-enumerable. An unguessable id is never a
--   substitute for an authorization check.
--
--   WHY UUIDv7, GENERATED IN THE APPLICATION:
--     * UUIDv7 (RFC 9562) is TIME-ORDERED, so as an indexed key it keeps good
--       B-tree insert locality (unlike random UUIDv4, which fragments the index).
--     * It is generated in Java (com.github.f4b6a3:uuid-creator,
--       UuidCreator.getTimeOrderedEpoch()) rather than by the database, because
--       PostgreSQL's native uuidv7() only exists from PG 18 and this project runs
--       on PG 17. Generating in the app keeps the schema portable across PG
--       versions. (The UUID column TYPE itself has existed in Postgres for years,
--       so PG 17 stores it natively.)
--
--   SCOPE: only the tables whose primary key crosses the wire get a public_id --
--   item and item_history. The internal audit tables app_user and access_audit
--   are intentionally NOT changed: neither ever exposes its id over the API
--   (app_user has no entity/endpoint at all; access_audit is written by a filter
--   and never read back through a controller), so there is nothing to make opaque.
--
-- WHY A NEW FILE INSTEAD OF EDITING V1/V2 (the golden rule of Flyway):
--   Applied migrations are IMMUTABLE. Flyway records each file's checksum in
--   flyway_schema_history and re-validates it on every startup; editing an
--   already-applied migration causes a "Migration checksum mismatch" and halts
--   startup. Schema changes are therefore FORWARD-ONLY: we add this higher-
--   numbered V3 that ALTERs on top of what V1/V2 built.
--
-- NAMING CONVENTION (must be exact or Flyway ignores the file):
--   V<version>__<description>.sql  ->  "V" + "3" + "__" (double underscore) +
--   "public_ids". The description is stored in the flyway_schema_history ledger.
--
-- OWNERSHIP / LIFECYCLE (unchanged): the schema is owned by Flyway, not Hibernate
--   (ddl-auto=validate). The Item / ItemHistory entities add a matching
--   `UUID publicId` field mapped NOT NULL UNIQUE; if the mapping and these columns
--   disagree, startup fails fast rather than drifting.
--
-- POSTGRESQL / TRANSACTIONAL DDL: PostgreSQL runs DDL inside a transaction, so if
--   any statement below fails the whole migration rolls back and startup halts --
--   the database is never left half-migrated.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. item.public_id
-- -----------------------------------------------------------------------------
-- Add the column NULLABLE first so the statement succeeds on a table that may
-- already contain rows (a NOT NULL column with no default cannot be added to a
-- populated table in one step).
ALTER TABLE item
    ADD COLUMN public_id UUID;

-- Backfill any pre-existing rows with a random UUID. gen_random_uuid() is built
-- into PostgreSQL core (pgcrypto was merged into core in PG 13), so it is
-- available on PG 17 without an extension. These backfilled values are UUIDv4
-- (random) -- that is fine: they only need to be unique and opaque, and they are
-- a one-time fill for rows created before this feature. All NEW rows created by
-- the application get a UUIDv7 from the Java generator.
UPDATE item
    SET public_id = gen_random_uuid()
    WHERE public_id IS NULL;

-- Now that every row has a value, enforce the invariants the entity mapping and
-- the API contract rely on: always present, and globally unique (the API looks
-- items up by public_id, so it must be unique). The UNIQUE constraint also
-- creates the index that backs those lookups.
ALTER TABLE item
    ALTER COLUMN public_id SET NOT NULL;
ALTER TABLE item
    ADD CONSTRAINT uq_item_public_id UNIQUE (public_id);


-- -----------------------------------------------------------------------------
-- 2. item_history.public_id
-- -----------------------------------------------------------------------------
-- The history row's OWN identifier that the API exposes (ItemHistoryDto.id). Same
-- three-step pattern: add nullable, backfill, then constrain NOT NULL + UNIQUE.
--
-- Note: item_history ALSO references its parent item. Over the wire that
-- reference is now the parent item's public_id (mapped in the service layer from
-- the association), NOT a numeric item id -- so no numeric id for item_history
-- leaves the server either. The internal item_id BIGINT foreign key (with its
-- ON DELETE SET NULL behaviour from V2) is unchanged and stays internal.
ALTER TABLE item_history
    ADD COLUMN public_id UUID;

UPDATE item_history
    SET public_id = gen_random_uuid()
    WHERE public_id IS NULL;

ALTER TABLE item_history
    ALTER COLUMN public_id SET NOT NULL;
ALTER TABLE item_history
    ADD CONSTRAINT uq_item_history_public_id UNIQUE (public_id);
