package com.example.entraoauth.item;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity mapped to the {@code item} table created by the Flyway migration
 * {@code V1__initial_schema.sql}.
 *
 * <p><strong>Where this sits in the identity pipeline.</strong> {@code item} is the sample
 * "business data" that the role-protected REST endpoints expose (R8): {@code ROLE_Viewer} and
 * {@code ROLE_Admin} may read it, while only {@code ROLE_Admin} may write it. Every row carries
 * a {@link #createdBy} column that ties the persisted data back to the caller's <em>identity</em>
 * &mdash; specifically the token subject (the {@code oid}/{@code sub} claim) extracted from the
 * validated Access_Token. That value is set by the service layer from the authenticated principal,
 * never supplied by the client, so the provenance of each row is trustworthy.
 *
 * <p><strong>Why the mapping must match the schema exactly.</strong> The Resource_Server runs with
 * {@code spring.jpa.hibernate.ddl-auto=validate}, which means Hibernate does <em>not</em> create or
 * alter tables &mdash; the schema is owned entirely by Flyway. Instead, at startup Hibernate
 * <em>validates</em> that this entity lines up with the real {@code item} table. If a column name,
 * nullability, or type disagrees with {@code V1__initial_schema.sql}, startup fails fast with a
 * schema-validation error rather than silently drifting. For that reason every field below mirrors
 * the migration precisely:
 * <ul>
 *   <li>{@code id BIGSERIAL PRIMARY KEY} &rarr; {@code Long id} with
 *       {@link GenerationType#IDENTITY} (Postgres {@code BIGSERIAL} is an identity/sequence-backed
 *       column, so the database assigns the value on insert).</li>
 *   <li>{@code name VARCHAR(200) NOT NULL} &rarr; {@code String name}, {@code nullable = false}.</li>
 *   <li>{@code description TEXT} (nullable) &rarr; {@code String description}, nullable.</li>
 *   <li>{@code created_by VARCHAR(100) NOT NULL} &rarr; {@code String createdBy},
 *       {@code nullable = false}, explicit {@code @Column(name = "created_by")}.</li>
 *   <li>{@code created_at TIMESTAMPTZ NOT NULL} &rarr; {@code OffsetDateTime createdAt}.</li>
 *   <li>{@code updated_at TIMESTAMPTZ NOT NULL} &rarr; {@code OffsetDateTime updatedAt}.</li>
 * </ul>
 *
 * <p><strong>Why {@link OffsetDateTime} for the timestamps.</strong> The columns are
 * {@code TIMESTAMPTZ} (timestamp <em>with</em> time zone). {@link OffsetDateTime} carries an
 * explicit UTC offset, so it maps cleanly onto {@code TIMESTAMPTZ} without losing the zone
 * information that {@link java.time.LocalDateTime} would drop. (Hibernate can also map
 * {@link java.time.Instant}; {@code OffsetDateTime} is chosen here to match the design's data model
 * and to keep the offset visible in the Java type.)
 *
 * <p><strong>Database defaults vs. application-managed values.</strong> The migration defines
 * {@code DEFAULT now()} on {@code created_at}/{@code updated_at}, so the database can populate them.
 * The service layer nonetheless sets these explicitly (and updates {@code updated_at} on mutation)
 * so the persisted values are deterministic and observable from the application side. The columns
 * remain {@code NOT NULL} either way.
 */
@Entity
@Table(name = "item")
public class Item {

    /**
     * Primary key. Maps to {@code id BIGSERIAL PRIMARY KEY}.
     *
     * <p>{@link GenerationType#IDENTITY} tells Hibernate that the database generates the key on
     * insert (Postgres {@code BIGSERIAL} is backed by a sequence). The field is a wrapper
     * {@link Long} rather than a primitive so a not-yet-persisted, unsaved entity can hold
     * {@code null} before the database assigns an id.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Human-readable name of the item. Maps to {@code name VARCHAR(200) NOT NULL}. The
     * {@code nullable = false} keeps the entity mapping consistent with the schema's {@code NOT NULL}
     * constraint (and is what the {@code validate} ddl-auto check compares against).
     */
    @Column(name = "name", nullable = false)
    private String name;

    /**
     * Optional free-text description. Maps to {@code description TEXT} (nullable), so no
     * {@code nullable = false} here.
     */
    @Column(name = "description")
    private String description;

    /**
     * Provenance / identity field. Maps to {@code created_by VARCHAR(100) NOT NULL}.
     *
     * <p>This holds the <strong>token subject</strong> &mdash; the {@code oid}/{@code sub} claim from
     * the validated Access_Token &mdash; and is populated by the service layer from the authenticated
     * principal, never from client input. It is an audit/provenance value tying each persisted row
     * back to the caller's identity; it is <em>not</em> a role and is <em>not</em> consulted for
     * authorization (authorization is claim-driven per R2).
     */
    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /**
     * Row creation timestamp. Maps to {@code created_at TIMESTAMPTZ NOT NULL DEFAULT now()}.
     */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * Last-modification timestamp. Maps to {@code updated_at TIMESTAMPTZ NOT NULL DEFAULT now()};
     * the service layer refreshes it on each update.
     */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * No-argument constructor required by JPA/Hibernate to instantiate managed entities via
     * reflection. Application code should prefer the all-args constructor or the setters.
     */
    protected Item() {
        // Required by the JPA specification.
    }

    /**
     * Convenience constructor for the service layer when creating a brand-new item.
     *
     * @param name        the item name (non-null per the schema)
     * @param description optional description (may be null)
     * @param createdBy   the token subject (oid/sub) of the authenticated caller
     * @param createdAt   creation timestamp
     * @param updatedAt   last-modified timestamp (equal to {@code createdAt} on creation)
     */
    public Item(String name, String description, String createdBy,
                OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
