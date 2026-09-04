package com.example.entraoauth.item;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Application service for the {@code item} domain resource. It sits between the
 * {@code ItemController} (which enforces {@code @PreAuthorize} role checks) and the
 * {@link ItemRepository} (which reads/writes rows), and is where the read/write endpoints of the
 * role-protected REST surface (R8) actually do their work.
 *
 * <p><strong>Where this sits in the identity pipeline &mdash; how the subject reaches a row.</strong>
 * By the time any method here runs, the request has already traversed the full security stack:
 * <ol>
 *   <li>the {@code SecurityFilterChain} pulled the bearer Access_Token off the {@code Authorization}
 *       header;</li>
 *   <li>the {@code JwtDecoder} verified its signature against Entra's JWKS and validated
 *       {@code iss}, {@code aud}, and {@code exp} (with the 60s skew allowance);</li>
 *   <li>the {@code RolesClaimConverter} turned the {@code roles} claim into {@code ROLE_*}
 *       authorities and Spring built an {@code Authentication} whose <em>principal is the validated
 *       {@link Jwt}</em>;</li>
 *   <li>the controller's {@code @PreAuthorize} gate confirmed the caller holds the required
 *       authority.</li>
 * </ol>
 * Only then does this service execute. When it needs to stamp a new row with the caller's identity,
 * it reads that <em>same validated {@link Jwt}</em> back out of the {@link SecurityContextHolder}
 * and takes its subject via {@link Jwt#getSubject()} (the {@code sub}/{@code oid} claim). That value
 * flows straight into {@link Item#getCreatedBy() Item.createdBy} and is persisted &mdash; so the
 * {@code created_by} column of every written row is provably the identity Entra minted the token
 * for. Crucially, the subject is <strong>never taken from the request body</strong>
 * ({@link CreateItemRequest}/{@link UpdateItemRequest} deliberately have no {@code createdBy}
 * field), which prevents a caller from forging provenance. This is the point where a validated
 * identity claim becomes durable data.
 *
 * <p><strong>Change history.</strong> Each create/update/delete also appends a row to
 * {@code item_history} via {@link ItemHistoryRepository}, capturing <em>who</em> made the change
 * (the token subject and {@code name} claim), <em>what</em> changed, and <em>when</em>. Because
 * every mutation path is gated upstream by {@code @PreAuthorize("hasRole('Admin')")}, every history
 * entry is provably attributable to an authorized Admin. See {@link #recordHistory}.
 *
 * <p><strong>Timestamp management.</strong> The {@code created_at}/{@code updated_at} columns are
 * {@code TIMESTAMPTZ NOT NULL} (with a {@code DEFAULT now()} in the migration). This service sets
 * them explicitly from {@link OffsetDateTime#now()} so the persisted values are deterministic and
 * observable from the application side: on {@link #create(CreateItemRequest) create} both are set
 * to the same instant, and on {@link #update(long, UpdateItemRequest) update} only
 * {@code updated_at} is refreshed while {@code created_at} (and the original {@code created_by}) are
 * left untouched. {@link OffsetDateTime} is used because it maps cleanly onto {@code TIMESTAMPTZ},
 * carrying an explicit UTC offset (matching the {@link Item} entity's field types).
 */
@Service
public class ItemService {

    /**
     * Repository backing both the read ({@link #findAll()}) and write
     * ({@link #create(CreateItemRequest)}, {@link #update(long, UpdateItemRequest)}) paths. It is
     * injected via constructor injection so the dependency is explicit and the service is easy to
     * test.
     */
    private final ItemRepository repository;

    /**
     * Repository used to append an {@link ItemHistory} row on every create/update/delete, and to
     * read the change log back for the history endpoints. Injected by constructor so the dependency
     * is explicit and easy to mock in tests.
     */
    private final ItemHistoryRepository historyRepository;

    /**
     * Constructor injection of the two repositories. Spring supplies the generated repository proxies
     * at startup.
     *
     * @param repository        the JPA repository for {@link Item} rows
     * @param historyRepository the JPA repository for {@link ItemHistory} change-log rows
     */
    public ItemService(ItemRepository repository, ItemHistoryRepository historyRepository) {
        this.repository = repository;
        this.historyRepository = historyRepository;
    }

    /**
     * Reads every {@code item} row and projects each into an immutable {@link ItemDto}.
     *
     * <p>This backs the read endpoint ({@code GET /entra-backend/items}), which the controller permits for
     * {@code ROLE_Viewer} or {@code ROLE_Admin} (R8.1). Returning DTOs rather than the JPA entities
     * keeps the persistence-managed {@link Item} objects off the API boundary; the exposed
     * {@code createdBy} lets a reader see which identity created each row.
     *
     * @return the list of items as read-only DTOs (empty if there are none)
     */
    public List<ItemDto> findAll() {
        return repository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Reads the entire item change log, most recent change first.
     *
     * <p>This backs the read endpoint {@code GET /entra-backend/history}, which the controller
     * permits for {@code ROLE_Viewer} or {@code ROLE_Admin} &mdash; the same read audience as the
     * item list. History is observational data; both roles may read it, only Admins can generate it
     * (because only Admins can write items). Returning {@link ItemHistoryDto} rather than the JPA
     * entities keeps the persistence-managed {@link ItemHistory} objects (and their lazy {@link Item}
     * association) off the API boundary.
     *
     * @return the full change log as read-only DTOs, newest first (empty if there is none)
     */
    public List<ItemHistoryDto> findHistory() {
        return historyRepository.findAllByOrderByChangedAtDesc().stream()
                .map(this::toHistoryDto)
                .toList();
    }

    /**
     * Reads the change log for a single item, most recent change first.
     *
     * <p>This backs the read endpoint {@code GET /entra-backend/items/{id}/history}, permitted for
     * {@code ROLE_Viewer} or {@code ROLE_Admin}. Note we do <em>not</em> require the item to still
     * exist: history is deliberately allowed to outlive the item (a deleted item's rows have a null
     * {@code item_id} and thus will not match this per-item query, but the endpoint still returns an
     * empty list rather than a 404, so a Viewer polling a since-deleted id gets a clean, empty result
     * rather than an error).
     *
     * @param itemId the id of the item whose history is requested
     * @return that item's history as read-only DTOs, newest first (empty if none)
     */
    public List<ItemHistoryDto> findHistoryForItem(long itemId) {
        return historyRepository.findByItem_IdOrderByChangedAtDesc(itemId).stream()
                .map(this::toHistoryDto)
                .toList();
    }

    /**
     * Creates a new {@code item} owned by the currently authenticated caller.
     *
     * <p>This backs the write endpoint ({@code POST /entra-backend/items}), which the controller permits for
     * {@code ROLE_Admin} only (R8.2). The identity that owns the row is <em>not</em> taken from the
     * request &mdash; it is derived from the validated token subject via
     * {@link #currentSubject()} and written into {@code created_by}. Both timestamps are set to the
     * same instant on creation. A {@code CREATE} {@link ItemHistory} row is appended.
     *
     * @param request the validated create request ({@code name} is {@code @NotBlank})
     * @return the persisted item projected to a DTO, including its database-assigned id
     */
    public ItemDto create(CreateItemRequest request) {
        // Read the subject (oid/sub) out of the validated JWT principal. This is the moment the
        // caller's identity, established purely from the bearer token, becomes the provenance of a
        // brand-new row.
        String subject = currentSubject();

        // A single instant used for both created_at and updated_at so a freshly created row has
        // equal timestamps.
        OffsetDateTime now = OffsetDateTime.now();

        Item item = new Item(
                request.name(),
                request.description(),
                request.category(),   // optional category label (may be null)
                subject,   // created_by = token subject; never client-supplied
                now,       // created_at
                now);      // updated_at (equal to created_at on creation)

        Item saved = repository.save(item);

        // Append a CREATE history row. This runs AFTER the @PreAuthorize("hasRole('Admin')") gate, so
        // only an authorized Admin write ever reaches here to generate history.
        recordHistory(saved, ItemHistory.ChangeType.CREATE, now,
                "Created item '" + saved.getName() + "'"
                        + (saved.getCategory() != null ? " [category: " + saved.getCategory() + "]" : ""));

        return toDto(saved);
    }

    /**
     * Updates the mutable fields of an existing {@code item}.
     *
     * <p>This backs the write endpoint ({@code PUT /entra-backend/items/{id}}), which the controller permits
     * for {@code ROLE_Admin} only (R8.2, R8.3). If no row exists for {@code id}, a
     * {@link ResponseStatusException} with {@link HttpStatus#NOT_FOUND 404} is thrown so the client
     * receives a clean 404 rather than an opaque error.
     *
     * <p><strong>Identity and timestamps on update.</strong> The original {@code created_by} (the
     * subject of whoever first created the row) and {@code created_at} are provenance and are left
     * unchanged &mdash; an update does not re-stamp ownership. Only {@code name}/{@code description}/
     * {@code category} are applied and {@code updated_at} is refreshed to the current instant. An
     * {@code UPDATE} {@link ItemHistory} row is appended summarizing the before/after of the
     * human-facing fields and recording the acting identity.
     *
     * @param id      the identifier of the item to update
     * @param request the validated update request ({@code name} is {@code @NotBlank})
     * @return the updated item projected to a DTO
     * @throws ResponseStatusException with status 404 if no item exists for {@code id}
     */
    public ItemDto update(long id, UpdateItemRequest request) {
        Item item = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Item not found: " + id));

        // Capture the "before" values so the history summary can describe what actually changed.
        // (We snapshot into locals because we are about to overwrite the entity's fields.)
        String beforeName = item.getName();
        String beforeCategory = item.getCategory();

        // Apply the client-supplied, validated changes. created_by and created_at are intentionally
        // NOT touched here: ownership provenance is set once at creation time.
        item.setName(request.name());
        item.setDescription(request.description());
        item.setCategory(request.category());

        // Refresh only the last-modified timestamp.
        OffsetDateTime now = OffsetDateTime.now();
        item.setUpdatedAt(now);

        Item saved = repository.save(item);

        // Append an UPDATE history row summarizing the before -> after of the human-facing fields.
        recordHistory(saved, ItemHistory.ChangeType.UPDATE, now,
                "Updated item #" + saved.getId()
                        + ": name '" + beforeName + "' -> '" + saved.getName() + "'"
                        + ", category '" + beforeCategory + "' -> '" + saved.getCategory() + "'");

        return toDto(saved);
    }

    /**
     * Deletes an existing {@code item} identified by {@code id}.
     *
     * <p>This backs the write endpoint ({@code DELETE /entra-backend/items/{id}}), which the
     * controller permits for {@code ROLE_Admin} only (R8.2, R8.3); a non-Admin authenticated caller
     * is stopped by method security with 403 before this runs, so no row is removed. If no row
     * exists for {@code id}, a {@link ResponseStatusException} with {@link HttpStatus#NOT_FOUND 404}
     * is thrown so the client receives a clean 404 rather than a silent no-op &mdash; making a delete
     * of a non-existent id observably distinct from a successful delete.
     *
     * <p>Deletion is a hard delete: the row is removed from the {@code item} table. Before removal, a
     * {@code DELETE} {@link ItemHistory} row is appended capturing the item's final state and the
     * acting identity. Because the {@code item_history} foreign key is {@code ON DELETE SET NULL},
     * that history row survives the delete (its {@code item_id} becomes null) &mdash; so the record
     * that the item was deleted, by whom, and when, is preserved. The {@code access_audit} record of
     * the request (subject, authorities, method, path, status) is also written independently by the
     * audit filter, so the fact that an Admin issued the delete remains traceable.
     *
     * @param id the identifier of the item to delete
     * @throws ResponseStatusException with status 404 if no item exists for {@code id}
     */
    public void delete(long id) {
        // Load the row first (rather than existsById) for two reasons: a missing id yields a clean
        // 404, and we need the item's current fields to write a meaningful DELETE history entry.
        Item item = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Item not found: " + id));

        // Record the DELETE history BEFORE removing the item. Ordering matters:
        //   * writing history first means the foreign key still references a live item row, and the
        //     summary can capture the item's final name/category;
        //   * the item_history FK is ON DELETE SET NULL, so once we delete the item below, this
        //     history row's item_id becomes null but the row itself (the record that the item was
        //     deleted, by whom, and when) SURVIVES -- which is the whole point of an audit trail.
        OffsetDateTime now = OffsetDateTime.now();
        recordHistory(item, ItemHistory.ChangeType.DELETE, now,
                "Deleted item #" + item.getId() + " '" + item.getName() + "'"
                        + (item.getCategory() != null ? " [category: " + item.getCategory() + "]" : ""));

        repository.delete(item);
    }

    /**
     * Appends a single {@link ItemHistory} row capturing a create/update/delete of an item.
     *
     * <p><strong>Where "who" comes from.</strong> The actor is taken from the <em>validated</em> JWT
     * in the security context, never from client input: {@link #currentSubject()} yields the stable
     * subject ({@code oid}/{@code sub}) and {@link #currentActorName()} yields the display
     * ({@code name}) claim when present. Because this method is only reached from the item write
     * paths &mdash; each gated by {@code @PreAuthorize("hasRole('Admin')")} on the controller &mdash;
     * every history row is provably attributable to an authorized Admin.
     *
     * <p>The insert is a normal Spring Data {@code save(..)}, which runs in its own transaction. In
     * the create/update paths it participates in the same request; in the delete path it is written
     * <em>before</em> the item is removed so the FK still references a live row (see {@link #delete}).
     *
     * @param item       the item the change concerned (still live at the time of the call)
     * @param changeType the kind of change
     * @param when       the timestamp to record (shared with the item's own timestamp for coherence)
     * @param details    a short human-readable summary of the change
     */
    private void recordHistory(Item item, ItemHistory.ChangeType changeType,
                               OffsetDateTime when, String details) {
        ItemHistory history = new ItemHistory(
                item,
                changeType,
                currentSubject(),    // actor_subject = token oid/sub
                currentActorName(),  // actor_name    = token "name" claim (may be null)
                details,
                when);
        historyRepository.save(history);
    }

    /**
     * Extracts the caller's human-readable display name from the {@code name} claim of the validated
     * JWT, or {@code null} if the token carried no such claim.
     *
     * <p>Unlike {@link #currentSubject()} (the stable, always-present identifier used for
     * authorization-independent provenance), the {@code name} claim is a convenience for display and
     * may be absent, so this returns {@code null} rather than throwing when it is missing. It is read
     * from the same validated {@link Jwt} principal, so it is trustworthy identity data, not client
     * input.
     *
     * @return the {@code name} claim of the authenticated caller, or {@code null} if absent/unauthenticated
     */
    private String currentActorName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("name");
        }
        return null;
    }

    /**
     * Extracts the token subject ({@code sub}/{@code oid}) of the currently authenticated caller
     * from the security context.
     *
     * <p>After the OAuth2 resource-server filter chain authenticates a request, the
     * {@link Authentication} held in the {@link SecurityContextHolder} carries the validated
     * {@link Jwt} as its principal. Reading {@link Jwt#getSubject()} yields the subject claim &mdash;
     * the stable identifier Entra assigned to the caller &mdash; which we persist as
     * {@code created_by}. Because this comes from a token whose signature, issuer, audience, and
     * expiry were already verified, the value is trustworthy identity data, not client input.
     *
     * @return the JWT subject of the authenticated caller
     * @throws ResponseStatusException with status 401 if there is no authenticated JWT principal
     *                                 (a defensive guard; in normal operation the filter chain
     *                                 guarantees one is present on protected endpoints)
     */
    private String currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        // Should not happen on a @PreAuthorize-protected write endpoint, but fail safely rather than
        // persist a row with a null/forged owner.
        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "No authenticated JWT principal in the security context");
    }

    /**
     * Maps a persistence-managed {@link Item} entity to the immutable {@link ItemDto} exposed on the
     * API boundary. The internal {@code created_at}/{@code updated_at} timestamps are intentionally
     * not surfaced in this instructional projection.
     *
     * @param item the entity to project
     * @return the corresponding DTO
     */
    private ItemDto toDto(Item item) {
        return new ItemDto(
                item.getId(),
                item.getName(),
                item.getDescription(),
                item.getCategory(),
                item.getCreatedBy());
    }

    /**
     * Maps a persistence-managed {@link ItemHistory} entity to the immutable {@link ItemHistoryDto}
     * exposed on the API boundary. Uses {@link ItemHistory#getItemId()} so we read the already-stored
     * foreign key without forcing a lazy load of the parent {@link Item}; the id is {@code null} for a
     * history row whose item was later deleted.
     *
     * @param history the entity to project
     * @return the corresponding DTO
     */
    private ItemHistoryDto toHistoryDto(ItemHistory history) {
        return new ItemHistoryDto(
                history.getId(),
                history.getItemId(),
                history.getChangeType(),
                history.getActorSubject(),
                history.getActorName(),
                history.getDetails(),
                history.getChangedAt());
    }
}
