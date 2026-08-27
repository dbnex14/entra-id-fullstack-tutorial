package com.example.entraoauth.item;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Item} entity.
 *
 * <p><strong>Where this sits in the identity pipeline.</strong> By the time any method on this
 * repository executes, the request has already traveled through the entire security stack: the
 * {@code SecurityFilterChain} decoded and validated the Access_Token (signature, {@code iss},
 * {@code aud}, and expiry with 60s skew), the {@code RolesClaimConverter} turned the {@code roles}
 * claim into {@code ROLE_*} authorities, and the controller's {@code @PreAuthorize} gate confirmed
 * the caller holds the authority the endpoint requires. In other words, persistence is the
 * <em>last</em> hop of the identity payload's journey down the stack &mdash; this repository never
 * makes an authorization decision, it simply reads and writes rows once access has been granted.
 *
 * <p><strong>What it backs.</strong> This single interface powers <em>both</em> sides of the
 * role-protected REST surface (R8):
 * <ul>
 *   <li><strong>Read endpoints</strong> ({@code GET /api/items}, permitted for
 *       {@code ROLE_Viewer} or {@code ROLE_Admin}) call {@link #findAll()} to project rows into
 *       {@code ItemDto} instances (R8.1).</li>
 *   <li><strong>Write endpoints</strong> ({@code POST}/{@code PUT /api/items}, permitted for
 *       {@code ROLE_Admin} only) call {@link #save(Object) save(..)} to persist the new/updated
 *       row, with {@code created_by} set from the authenticated principal's token subject
 *       ({@code oid}/{@code sub}) (R8.2).</li>
 * </ul>
 *
 * <p><strong>What {@link JpaRepository} gives us for free.</strong> Extending
 * {@link JpaRepository JpaRepository&lt;Item, Long&gt;} inherits a full set of CRUD and paging
 * operations without any implementation code &mdash; Spring Data generates a proxy at runtime.
 * The two type parameters declare the managed aggregate and the type of its {@code @Id}:
 * <ul>
 *   <li>{@code Item} &mdash; the JPA entity mapped to the {@code item} table.</li>
 *   <li>{@code Long} &mdash; the type of {@link Item#getId() Item.id} (a {@code BIGSERIAL} primary
 *       key in {@code V1__initial_schema.sql}).</li>
 * </ul>
 * No custom query methods are needed for this instructional reference: {@code findAll()} and
 * {@code save(..)} inherited from the base interface cover every read and write the endpoints
 * perform.
 *
 * <p><strong>Why {@link #count()} matters for the property tests.</strong> The inherited
 * {@link org.springframework.data.repository.CrudRepository#count() count()} method returns the
 * total number of {@code item} rows. Property 4 ("Write endpoints require {@code ROLE_Admin}",
 * Validates R8.2/R8.3) uses it as a <em>no-mutation oracle</em>: the test snapshots
 * {@code count()} before issuing a write with an arbitrary authority subset, then asserts that a
 * forbidden write (a caller lacking {@code ROLE_Admin}) is rejected with <strong>403</strong> and
 * leaves the row count <em>unchanged</em> ({@code after == before}), while an authorized
 * {@code ROLE_Admin} write succeeds with <strong>200/201</strong> and increments the count
 * ({@code after == before + 1}). This is what makes "no data is modified on 403" (R8.3) directly
 * observable and testable &mdash; the {@code @PreAuthorize} check runs before the controller body,
 * so a denied write never reaches {@code save(..)} and therefore never changes the count.
 *
 * <p>The {@link Repository @Repository} stereotype is optional on a Spring Data interface (the
 * interface is detected by its base type during repository scanning), but it is included here for
 * clarity and to enable Spring's persistence-exception translation.
 */
@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {
    // Intentionally empty: all read (findAll) and write (save) operations used by the
    // role-protected endpoints, plus count() used by the no-mutation-on-403 property test,
    // are inherited from JpaRepository<Item, Long>.
}
