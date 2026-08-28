package com.example.entraoauth.audit;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity mapped to the {@code access_audit} table created by the Flyway migration
 * {@code V1__initial_schema.sql}.
 *
 * <p><strong>AUDIT ONLY &mdash; NOT AN AUTHORIZATION DECISION (R2).</strong> This entity, and the
 * {@code access_audit} table behind it, exist purely for the instructional server-side
 * authority-confirmation verification story (see design "Testing Strategy &gt; Server-side authority
 * confirmation"). A row here records the <em>OUTCOME</em> of a claim-driven authorization decision
 * that has <em>already</em> been made from the token's {@code roles} claim &mdash; it is written
 * <em>after</em> the request has been fully handled and its response status is known. It is
 * <strong>never</strong> read to <em>make</em> an authorization decision. Authorization in this
 * system is claim-driven end to end (R2): the {@code ROLE_*} authorities that gate the endpoints
 * come exclusively from the validated Access_Token, not from any row in this table.
 *
 * <p><strong>What each row captures.</strong> The {@link com.example.entraoauth.security.AccessAuditFilter}
 * populates one row per audited request with:
 * <ul>
 *   <li>{@link #subject} &mdash; the token subject (the {@code oid}/{@code sub} claim) of the caller,
 *       taken from the authenticated principal in the {@code SecurityContextHolder} after the
 *       resource-server filter has validated the token and populated the security context;</li>
 *   <li>{@link #authorities} &mdash; the resolved {@code ROLE_*} authorities joined into a single
 *       string, so the verification guide can confirm the {@code roles} claim really produced the
 *       expected authorities (R2.2&ndash;R2.4);</li>
 *   <li>{@link #httpMethod} &mdash; the request's HTTP method (GET/POST/PUT/...);</li>
 *   <li>{@link #path} &mdash; the request path ({@code HttpServletRequest#getRequestURI()});</li>
 *   <li>{@link #status} &mdash; the final HTTP response status, which is why the filter must record
 *       the row <em>after</em> the request completes (200 read, 201 created, 403 forbidden, ...);</li>
 *   <li>{@link #occurredAt} &mdash; when the request occurred.</li>
 * </ul>
 *
 * <p><strong>Why the mapping mirrors the schema exactly.</strong> The Resource_Server runs with
 * {@code spring.jpa.hibernate.ddl-auto=validate}: Hibernate never creates or alters tables (the
 * schema is owned by Flyway) but it <em>validates</em> at startup that this entity lines up with the
 * real {@code access_audit} table. If a column name, nullability, or type disagreed with
 * {@code V1__initial_schema.sql}, startup would fail fast. The columns therefore map precisely:
 * <ul>
 *   <li>{@code id BIGSERIAL PRIMARY KEY} &rarr; {@code Long id} with {@link GenerationType#IDENTITY}
 *       (Postgres {@code BIGSERIAL} is sequence-backed, so the database assigns the value on insert);</li>
 *   <li>{@code subject VARCHAR(100) NOT NULL} &rarr; {@code String subject}, {@code nullable = false};</li>
 *   <li>{@code authorities TEXT NOT NULL} &rarr; {@code String authorities}, {@code nullable = false};</li>
 *   <li>{@code http_method VARCHAR(10) NOT NULL} &rarr; {@code String httpMethod}, {@code nullable = false};</li>
 *   <li>{@code path VARCHAR(300) NOT NULL} &rarr; {@code String path}, {@code nullable = false};</li>
 *   <li>{@code status INTEGER NOT NULL} &rarr; {@code int status}, {@code nullable = false};</li>
 *   <li>{@code occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()} &rarr; {@code OffsetDateTime occurredAt}.</li>
 * </ul>
 *
 * <p><strong>Why {@link OffsetDateTime} for {@code occurred_at}.</strong> The column is
 * {@code TIMESTAMPTZ} (timestamp <em>with</em> time zone). {@link OffsetDateTime} carries an explicit
 * UTC offset, so it maps cleanly onto {@code TIMESTAMPTZ} without dropping the zone information, exactly
 * as {@link com.example.entraoauth.item.Item} does for its {@code created_at}/{@code updated_at}
 * columns. The migration also defines {@code DEFAULT now()} on this column, so the database could
 * populate it; the filter nonetheless sets it explicitly in code (like {@code Item} does) so the
 * persisted value is deterministic and observable from the application side.
 */
@Entity
@Table(name = "access_audit")
public class AccessAudit {

    /**
     * Primary key. Maps to {@code id BIGSERIAL PRIMARY KEY}.
     *
     * <p>{@link GenerationType#IDENTITY} tells Hibernate the database generates the key on insert
     * (Postgres {@code BIGSERIAL} is backed by a sequence). A wrapper {@link Long} (not a primitive)
     * lets a not-yet-persisted row hold {@code null} before the database assigns an id.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The token subject &mdash; the {@code oid}/{@code sub} claim of the caller whose request is being
     * audited. Maps to {@code subject VARCHAR(100) NOT NULL}. This is an identity/provenance value,
     * never a role and never consulted for authorization (R2).
     */
    @Column(name = "subject", nullable = false)
    private String subject;

    /**
     * The resolved {@code ROLE_*} authorities for the request, serialized into a single string (the
     * filter joins them with a comma). Maps to {@code authorities TEXT NOT NULL}. This is the column
     * the verification guide cross-checks against the presented token's {@code roles} claim
     * (R2.2&ndash;R2.4).
     */
    @Column(name = "authorities", nullable = false)
    private String authorities;

    /**
     * The HTTP method of the audited request (e.g. {@code GET}, {@code POST}, {@code PUT}). Maps to
     * {@code http_method VARCHAR(10) NOT NULL}.
     */
    @Column(name = "http_method", nullable = false)
    private String httpMethod;

    /**
     * The request path ({@code HttpServletRequest#getRequestURI()}). Maps to
     * {@code path VARCHAR(300) NOT NULL}.
     */
    @Column(name = "path", nullable = false)
    private String path;

    /**
     * The final HTTP response status of the audited request (e.g. 200, 201, 403). Maps to
     * {@code status INTEGER NOT NULL}. Because this is only known once the request has completed, the
     * filter records the row <em>after</em> the filter chain runs.
     */
    @Column(name = "status", nullable = false)
    private int status;

    /**
     * When the audited request occurred. Maps to {@code occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()};
     * the filter sets it explicitly for a deterministic, application-visible value.
     */
    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    /**
     * No-argument constructor required by JPA/Hibernate to instantiate managed entities via
     * reflection. Application code should prefer the all-args constructor.
     */
    protected AccessAudit() {
        // Required by the JPA specification.
    }

    /**
     * Convenience constructor used by {@link com.example.entraoauth.security.AccessAuditFilter} to
     * build a fully-populated audit row after a request completes.
     *
     * @param subject     the token subject (oid/sub) of the authenticated caller
     * @param authorities the resolved {@code ROLE_*} authorities, joined into a single string
     * @param httpMethod  the request's HTTP method
     * @param path        the request path (request URI)
     * @param status      the final HTTP response status
     * @param occurredAt  the timestamp of the request
     */
    public AccessAudit(String subject, String authorities, String httpMethod, String path,
                       int status, OffsetDateTime occurredAt) {
        this.subject = subject;
        this.authorities = authorities;
        this.httpMethod = httpMethod;
        this.path = path;
        this.status = status;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getAuthorities() {
        return authorities;
    }

    public void setAuthorities(String authorities) {
        this.authorities = authorities;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(OffsetDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }
}
