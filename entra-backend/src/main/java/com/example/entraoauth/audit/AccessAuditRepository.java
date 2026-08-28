package com.example.entraoauth.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link AccessAudit} entity.
 *
 * <p><strong>AUDIT ONLY &mdash; NOT AN AUTHORIZATION DECISION (R2).</strong> This repository is used
 * only to <em>write</em> audit rows recording the outcome of already-made, claim-driven
 * authorization decisions (see {@link com.example.entraoauth.security.AccessAuditFilter}). It is
 * never consulted to decide whether a request is allowed &mdash; authorization is driven entirely by
 * the {@code roles} claim in the validated Access_Token.
 *
 * <p><strong>What {@link JpaRepository} gives us.</strong> Extending
 * {@link JpaRepository JpaRepository&lt;AccessAudit, Long&gt;} inherits a full set of CRUD operations
 * without any implementation code (Spring Data generates a proxy at runtime). The two type
 * parameters declare the managed aggregate and the type of its {@code @Id}:
 * <ul>
 *   <li>{@code AccessAudit} &mdash; the JPA entity mapped to the {@code access_audit} table;</li>
 *   <li>{@code Long} &mdash; the type of {@link AccessAudit#getId()} (a {@code BIGSERIAL} primary key
 *       in {@code V1__initial_schema.sql}).</li>
 * </ul>
 *
 * <p><strong>Transaction note.</strong> The audit filter calls {@link #save(Object) save(..)} from
 * within the servlet filter chain, where an application-managed transaction may not be active.
 * Spring Data's {@code save(..)} is transactional on its own &mdash; the generated
 * {@code SimpleJpaRepository.save} is annotated {@code @Transactional}, so it opens (and commits) its
 * own transaction for the insert. No extra {@code @Transactional} is required on this interface for a
 * single {@code save(..)} to be durably committed.
 */
@Repository
public interface AccessAuditRepository extends JpaRepository<AccessAudit, Long> {
    // Intentionally empty: the audit filter only needs the inherited save(..) operation. All CRUD
    // methods are provided by JpaRepository<AccessAudit, Long>.
}
