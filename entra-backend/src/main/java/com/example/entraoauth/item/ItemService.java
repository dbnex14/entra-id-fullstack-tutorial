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
     * Constructor injection of the {@link ItemRepository}. Spring supplies the generated repository
     * proxy at startup.
     *
     * @param repository the JPA repository for {@link Item} rows
     */
    public ItemService(ItemRepository repository) {
        this.repository = repository;
    }

    /**
     * Reads every {@code item} row and projects each into an immutable {@link ItemDto}.
     *
     * <p>This backs the read endpoint ({@code GET /api/items}), which the controller permits for
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
     * Creates a new {@code item} owned by the currently authenticated caller.
     *
     * <p>This backs the write endpoint ({@code POST /api/items}), which the controller permits for
     * {@code ROLE_Admin} only (R8.2). The identity that owns the row is <em>not</em> taken from the
     * request &mdash; it is derived from the validated token subject via
     * {@link #currentSubject()} and written into {@code created_by}. Both timestamps are set to the
     * same instant on creation.
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
                subject,   // created_by = token subject; never client-supplied
                now,       // created_at
                now);      // updated_at (equal to created_at on creation)

        Item saved = repository.save(item);
        return toDto(saved);
    }

    /**
     * Updates the mutable fields of an existing {@code item}.
     *
     * <p>This backs the write endpoint ({@code PUT /api/items/{id}}), which the controller permits
     * for {@code ROLE_Admin} only (R8.2, R8.3). If no row exists for {@code id}, a
     * {@link ResponseStatusException} with {@link HttpStatus#NOT_FOUND 404} is thrown so the client
     * receives a clean 404 rather than an opaque error.
     *
     * <p><strong>Identity and timestamps on update.</strong> The original {@code created_by} (the
     * subject of whoever first created the row) and {@code created_at} are provenance and are left
     * unchanged &mdash; an update does not re-stamp ownership. Only {@code name}/{@code description}
     * are applied and {@code updated_at} is refreshed to the current instant.
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

        // Apply the client-supplied, validated changes. created_by and created_at are intentionally
        // NOT touched here: ownership provenance is set once at creation time.
        item.setName(request.name());
        item.setDescription(request.description());

        // Refresh only the last-modified timestamp.
        item.setUpdatedAt(OffsetDateTime.now());

        Item saved = repository.save(item);
        return toDto(saved);
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
                item.getCreatedBy());
    }
}
