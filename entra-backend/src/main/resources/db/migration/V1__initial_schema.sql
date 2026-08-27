-- =============================================================================
-- V1__initial_schema.sql
-- =============================================================================
-- Flyway migration file.
--
-- NAMING CONVENTION (R7.9):
--   Flyway parses this filename as V<version>__<description>.sql, where:
--     * the leading "V" marks a versioned (as opposed to repeatable "R") migration,
--     * "1"  is the ascending version number, and
--     * "initial_schema" (after the DOUBLE underscore "__") is the human-readable
--       description that Flyway stores in the flyway_schema_history table.
--   Getting this convention wrong (single underscore, missing "V", etc.) makes
--   Flyway ignore the file entirely, so the schema below would never be created.
--
-- LIFECYCLE / OWNERSHIP:
--   This schema is OWNED by Flyway, not by Hibernate. The Resource_Server runs with
--   spring.jpa.hibernate.ddl-auto=validate, so JPA only checks that the entities
--   line up with these tables -- it never creates or alters them. Flyway applies
--   this migration once, at Resource_Server startup, in ascending version order
--   (R7.3), and records the result (version, checksum, success) in the
--   flyway_schema_history ledger (R7.4). On every subsequent start the already-
--   recorded migration is skipped, and any checksum drift halts startup.
--
-- POSTGRESQL / TRANSACTIONAL DDL:
--   PostgreSQL runs DDL inside a transaction, so if any statement below fails the
--   whole migration is rolled back and startup halts (R7.7) -- the database is
--   never left in a half-migrated state.
--
-- SECURITY / IDENTITY MODEL (read this before touching app_user / access_audit):
--   Authorization in this system is CLAIM-DRIVEN (R2): the ROLE_* authorities that
--   gate the REST endpoints come exclusively from the "roles" claim inside the
--   validated Access_Token. NONE of the tables below participate in an authorization
--   decision. In particular:
--     * app_user     -> profile/identity record derived from token claims; audit only.
--     * access_audit -> after-the-fact trail of who did what; audit only.
--   These tables exist for traceability and the instructional verification story,
--   and must NEVER be consulted to decide whether a request is allowed.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- item
-- -----------------------------------------------------------------------------
-- Sample domain table exercised by the role-protected REST endpoints (R8). This is
-- the "business data" that Viewer/Admin roles read and Admin roles write.
CREATE TABLE item (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    description  TEXT,
    -- created_by ties each persisted row back to the caller's IDENTITY. It stores
    -- the token SUBJECT (the "oid"/"sub" claim from the Access_Token), NOT a role
    -- and NOT a foreign key used for authorization. It is a provenance/audit field.
    created_by   VARCHAR(100) NOT NULL,      -- subject (oid/sub) from the Access_Token
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- -----------------------------------------------------------------------------
-- app_user
-- -----------------------------------------------------------------------------
-- App-level user/profile record derived from token claims. AUDIT / PROFILE ONLY --
-- it is NEVER used for authorization (authorization is claim-driven per R2). We
-- keep it purely so the system can display and trace which identities have been
-- seen, keyed by the Entra subject claim.
CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    -- subject is the Entra "oid"/"sub" claim -- the stable identifier of the caller.
    -- UNIQUE so each identity maps to exactly one profile row.
    subject       VARCHAR(100) NOT NULL UNIQUE, -- Entra oid/sub claim
    display_name  VARCHAR(200),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- -----------------------------------------------------------------------------
-- access_audit
-- -----------------------------------------------------------------------------
-- Audit trail of security-relevant actions, for the instructional verification
-- story (R2, R8). AUDIT ONLY -- it records the OUTCOME of an authorization decision
-- that was already made from the token's claims; it is NEVER read to MAKE an
-- authorization decision. The "authorities" column captures the serialized ROLE_*
-- set that the claim-driven pipeline granted for the request, so the verification
-- guide can confirm that the roles claim really produced the expected authorities.
CREATE TABLE access_audit (
    id           BIGSERIAL PRIMARY KEY,
    subject      VARCHAR(100) NOT NULL,     -- token subject (oid/sub) that made the request
    authorities  TEXT NOT NULL,             -- serialized ROLE_* granted for the request
    http_method  VARCHAR(10) NOT NULL,
    path         VARCHAR(300) NOT NULL,
    status       INTEGER NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- -----------------------------------------------------------------------------
-- Indexes
-- -----------------------------------------------------------------------------
-- Speeds up "show me the items created by this subject" provenance lookups.
CREATE INDEX idx_item_created_by ON item(created_by);
-- Speeds up "show me the audit trail for this subject" traceability lookups.
CREATE INDEX idx_access_audit_subject ON access_audit(subject);
