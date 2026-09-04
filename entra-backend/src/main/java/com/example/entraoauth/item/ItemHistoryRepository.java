package com.example.entraoauth.item;

import java.util.List;

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
 *   <li>{@link #findByItem_IdOrderByChangedAtDesc(Long)} &mdash; the change log for one item
 *       ({@code GET /entra-backend/items/{id}/history}), newest first.</li>
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
 *   <li>{@code findByItem_Id...} navigates the {@link ItemHistory#getItem() item} association to its
 *       {@code id} property. The underscore ({@code Item_Id}) explicitly disambiguates "the
 *       {@code id} of the {@code item} association" from any hypothetical {@code itemId} property, so
 *       the generated query filters on the {@code item_id} foreign-key column. This is the query the
 *       {@code idx_item_history_item_id} index (from the V2 migration) accelerates.</li>
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
     * {@code GET /entra-backend/items/{id}/history}.
     *
     * <p>Filters on the {@code item_id} foreign key (via the {@code item} association's {@code id}),
     * which the {@code idx_item_history_item_id} index makes efficient. Note this matches history
     * rows whose {@code item_id} still points at the given id; rows whose item was later deleted have
     * a {@code null} {@code item_id} and therefore only appear in the global log, not here.
     *
     * @param itemId the id of the item whose history is requested
     * @return that item's history rows ordered by {@code changed_at} descending (empty if none)
     */
    List<ItemHistory> findByItem_IdOrderByChangedAtDesc(Long itemId);
}
