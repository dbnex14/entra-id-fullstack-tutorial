package com.example.entraoauth.item;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link ItemHistory} entity.
 *
 * <p><strong>What it backs.</strong> This repository powers the two <em>read</em> history endpoints
 * (both permitted for {@code ROLE_Viewer} or {@code ROLE_Admin}) and the <em>write</em> of a history
 * row from within the item create/update/delete service paths:
 * <ul>
 *   <li>{@link #findAllByOrderByChangedAtDesc()} &mdash; the global change log
 *       ({@code GET /entra-backend/history}), newest first.</li>
 *   <li>{@link #findByItem_PublicIdOrderByChangedAtDesc(java.util.UUID)} &mdash; the change log for
 *       one item ({@code GET /entra-backend/items/{publicId}/history}), newest first.</li>
 *   <li>the inherited {@link #save(Object) save(..)} &mdash; used by {@link ItemService} to append a
 *       history row on each create/update/delete.</li>
 * </ul>
 *
 * <p><strong>Never an authorization source (R2).</strong> Like {@link ItemRepository} and
 * {@link com.example.entraoauth.audit.AccessAuditRepository}, this repository only reads and writes
 * rows once access has already been granted from the token's {@code roles} claim. It never makes an
 * authorization decision.
 *
 * <p><strong>How the derived query method names work.</strong> Spring Data parses these method names
 * at startup and generates the query for you &mdash; no SQL/JPQL to write or maintain:
 * <ul>
 *   <li>{@code findByItem_PublicId...} navigates the {@link ItemHistory#getItem() item} association
 *       to its {@code publicId} property. The underscore ({@code Item_PublicId}) explicitly
 *       disambiguates "the {@code publicId} of the {@code item} association", so the generated query
 *       joins {@code item} and filters on its {@code public_id} column (backed by the
 *       {@code uq_item_public_id} unique index from the V3 migration). Matching on the opaque public
 *       id &mdash; not the internal numeric {@code item_id} &mdash; is what keeps the sequential id
 *       out of the API path.</li>
 *   <li>{@code ...OrderByChangedAtDesc} appends {@code ORDER BY changed_at DESC}, returning the most
 *       recent change first &mdash; the natural order for a history/audit view.</li>
 *   <li>{@code findAllByOrderByChangedAtDesc} is the "no filter" form: {@code findAllBy} + the same
 *       ordering, i.e. every row newest-first.</li>
 * </ul>
 *
 * <p>The two type parameters {@code JpaRepository<ItemHistory, Long>} declare the managed entity and
 * the type of its {@code @Id}.
 */
@Repository
public interface ItemHistoryRepository extends JpaRepository<ItemHistory, Long> {

    /**
     * Returns every history row, most recent change first. Backs
     * {@code GET /entra-backend/history} (the global change log).
     *
     * @return all history rows ordered by {@code changed_at} descending (empty if none)
     */
    List<ItemHistory> findAllByOrderByChangedAtDesc();

    /**
     * Returns the history rows for a single item, most recent change first. Backs
     * {@code GET /entra-backend/items/{publicId}/history}.
     *
     * <p>Filters on the parent item's opaque {@code public_id} by navigating the {@code item}
     * association to its {@code publicId} property ({@code findByItem_PublicId...}). The underscore
     * disambiguates "the {@code publicId} of the {@code item} association". We match on the public id
     * (not the internal numeric id) because that is the identifier the API accepts in the path, so
     * the sequential id never appears in a URL. Rows whose item was later deleted have a
     * {@code null} {@code item_id} and therefore only appear in the global log, not here.
     *
     * @param itemPublicId the opaque public id of the item whose history is requested
     * @return that item's history rows ordered by {@code changed_at} descending (empty if none)
     */
    List<ItemHistory> findByItem_PublicIdOrderByChangedAtDesc(UUID itemPublicId);
}
