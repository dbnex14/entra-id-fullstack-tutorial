package com.example.entraoauth.item;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * JPA entity mapped to the {@code item_history} table created by the Flyway migration
 * {@code V2__item_category_and_history.sql}. Each row is a durable, append-only record of a single
 * change (create / update / delete) to an {@link Item}.
 *
 * <p><strong>Where this sits in the identity pipeline &mdash; a change log tied to identity.</strong>
 * This is a sibling in spirit to {@link com.example.entraoauth.audit.AccessAudit}: it captures the
 * <em>outcome</em> of an already-authorized mutation. By the time a row is written here, the request
 * has passed the whole security stack (signature/issuer/audience/expiry validation, {@code roles}
 * &rarr; {@code ROLE_*} conversion, and the controller's {@code @PreAuthorize("hasRole('Admin')")}
 * gate, since only Admins can write items). The {@link #actorSubject} and {@link #actorName} fields
 * record <em>who</em> made the change, taken from the validated token by {@link ItemService} &mdash;
 * never from client input. This table is <strong>read</strong> by both Viewer and Admin but is
 * <strong>never consulted to make an authorization decision</strong> (R2); authorization is
 * claim-driven end to end.
 *
 * <p><strong>Why an explicit {@code @ManyToOne} to {@link Item} (a "proper" relationship).</strong>
 * Many history rows belong to one item, so the association is many-to-one. Modeling it as a JPA
 * relationship (rather than a bare {@code Long itemId}) lets code navigate {@code history.getItem()}
 * and lets Hibernate manage the {@code item_id} foreign key. Two deliberate choices:
 * <ul>
 *   <li><strong>{@code fetch = LAZY}</strong> &mdash; the parent {@link Item} is not loaded from the
 *       database unless actually accessed. History listings project into {@link ItemHistoryDto}
 *       using only the already-stored {@code item_id} (via {@link #getItemId()}), so we avoid an
 *       extra query per row (the classic N+1 problem).</li>
 *   <li><strong>{@code optional = true} / nullable join column</strong> &mdash; the FK is
 *       {@code ON DELETE SET NULL} in the migration precisely so a DELETE's history row
 *       <em>survives</em> the removal of the item. After the item row is hard-deleted, this
 *       association simply becomes {@code null}; the history (including the "who deleted it" record)
 *       remains. A non-optional mapping would contradict the nullable column and fail the
 *       {@code ddl-auto=validate} check.</li>
 * </ul>
 *
 * <p><strong>Why {@code change_type} is an {@code @Enumerated(EnumType.STRING)}.</strong> Storing the
 * enum by its {@code name()} ({@code "CREATE"}/{@code "UPDATE"}/{@code "DELETE"}) rather than its
 * ordinal keeps the database values human-readable and stable if the enum's declaration order ever
 * changes. It also lines up with the {@code CHECK (change_type IN ('CREATE','UPDATE','DELETE'))}
 * constraint in the migration. Using {@code ORDINAL} would store fragile integers and silently
 * corrupt meaning if a new constant were inserted in the middle of the enum.
 *
 * <p><strong>Why the mapping mirrors the schema exactly.</strong> As with {@link Item} and
 * {@link com.example.entraoauth.audit.AccessAudit}, {@code ddl-auto=validate} means Hibernate checks
 * (never alters) that this entity lines up with the real {@code item_history} table at startup. Each
 * field below mirrors {@code V2__item_category_and_history.sql} precisely.
 */
@Entity
@Table(name = "item_history")
public class ItemHistory {

    /**
     * The kind of change a history row records. The names of these constants are the exact strings
     * persisted to the {@code change_type} column (see {@link Enumerated} with {@link EnumType#STRING}
     * above), and they match the migration's {@code CHECK} constraint values.
     */
    public enum ChangeType {
        /** A new item was created ({@code POST /entra-backend/items}). */
        CREATE,
        /** An existing item's fields were updated ({@code PUT /entra-backend/items/{id}}). */
        UPDATE,
        /** An item was deleted ({@code DELETE /entra-backend/items/{id}}). */
        DELETE
    }

    /**
     * Primary key. Maps to {@code id BIGSERIAL PRIMARY KEY}. {@link GenerationType#IDENTITY} lets the
     * database assign the value on insert (Postgres {@code BIGSERIAL} is sequence-backed); a wrapper
     * {@link Long} lets an unsaved row hold {@code null} before persistence.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The item this change concerned. Maps the {@code item_id} foreign key via a lazy, optional
     * many-to-one association. It may be {@code null} for a history row whose item was later
     * hard-deleted (the FK is {@code ON DELETE SET NULL}), which is exactly what preserves the audit
     * trail past a delete.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "item_id")
    private Item item;

    /**
     * The kind of change. Maps to {@code change_type VARCHAR(20) NOT NULL}, stored as the enum name
     * (STRING) rather than an ordinal for readability and stability.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private ChangeType changeType;

    /**
     * WHO made the change: the token subject ({@code oid}/{@code sub}) of the authenticated caller.
     * Maps to {@code actor_subject VARCHAR(100) NOT NULL}. Identity/provenance only &mdash; captured
     * from the validated token, never client-supplied, never used for authorization (R2).
     */
    @Column(name = "actor_subject", nullable = false)
    private String actorSubject;

    /**
     * The caller's human-readable display name (the {@code name} claim), if present on the token.
     * Maps to {@code actor_name VARCHAR(200)} (nullable, since not every token carries a name claim).
     */
    @Column(name = "actor_name")
    private String actorName;

    /**
     * A short human-readable summary of what changed. Maps to {@code details TEXT NOT NULL}.
     */
    @Column(name = "details", nullable = false)
    private String details;

    /**
     * When the change happened. Maps to {@code changed_at TIMESTAMPTZ NOT NULL DEFAULT now()}.
     * {@link OffsetDateTime} carries an explicit UTC offset so it maps cleanly onto {@code TIMESTAMPTZ}
     * (matching {@link Item} / {@link com.example.entraoauth.audit.AccessAudit}). The service sets it
     * explicitly for a deterministic, application-visible value.
     */
    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    /**
     * No-argument constructor required by JPA/Hibernate to instantiate managed entities via
     * reflection. Application code should prefer the all-args constructor.
     */
    protected ItemHistory() {
        // Required by the JPA specification.
    }

    /**
     * Convenience constructor used by {@link ItemService} to build a fully-populated history row.
     *
     * @param item         the item the change concerned (may be null for a post-delete record)
     * @param changeType   the kind of change (CREATE/UPDATE/DELETE)
     * @param actorSubject the token subject (oid/sub) of the authenticated caller
     * @param actorName    the caller's display name from the {@code name} claim (may be null)
     * @param details      a short human-readable summary of the change
     * @param changedAt    when the change happened
     */
    public ItemHistory(Item item, ChangeType changeType, String actorSubject, String actorName,
                       String details, OffsetDateTime changedAt) {
        this.item = item;
        this.changeType = changeType;
        this.actorSubject = actorSubject;
        this.actorName = actorName;
        this.details = details;
        this.changedAt = changedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Item getItem() {
        return item;
    }

    public void setItem(Item item) {
        this.item = item;
    }

    /**
     * Convenience accessor that returns the associated item's id without forcing a full load of the
     * parent entity, or {@code null} if the item was deleted (the FK was set to null). Because
     * Hibernate stores the foreign key value on this row, reading {@code item.getId()} through the
     * lazy proxy does not trigger a second query.
     *
     * @return the parent item's id, or {@code null} if the item no longer exists
     */
    public Long getItemId() {
        return item != null ? item.getId() : null;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(ChangeType changeType) {
        this.changeType = changeType;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public void setActorSubject(String actorSubject) {
        this.actorSubject = actorSubject;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public OffsetDateTime getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(OffsetDateTime changedAt) {
        this.changedAt = changedAt;
    }
}
