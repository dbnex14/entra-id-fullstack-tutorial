package com.example.entraoauth.item;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Role-protected REST controller exposing the sample {@code item} domain resource under
 * {@code /items} (served at {@code /entra-backend/items} once the {@code /entra-backend} servlet
 * context path is applied). This is the outermost application-layer surface of the Resource_Server and the
 * place where the identity that Entra ID minted, and that the security stack has already validated,
 * is finally turned into an authorization decision (R8).
 *
 * <p><strong>Where this sits in the token pipeline.</strong> Nothing in this class parses or trusts
 * a raw token. By the time a handler method here is even considered for invocation, the request has
 * already passed through the stateless {@code SecurityFilterChain}:
 * <ol>
 *   <li>the bearer Access_Token was pulled off the {@code Authorization: Bearer ...} header;</li>
 *   <li>the {@code JwtDecoder} verified its signature against Entra's JWKS and validated
 *       {@code iss}, {@code aud}, and {@code exp} (with a 60-second clock-skew allowance);</li>
 *   <li>the {@code RolesClaimConverter} mapped the token's {@code roles} claim into
 *       {@code ROLE_*} authorities (e.g. {@code Admin} &rarr; {@code ROLE_Admin});</li>
 *   <li>Spring built an {@code Authentication} whose principal is the validated {@code Jwt}.</li>
 * </ol>
 * Only against that already-authenticated request do the {@code @PreAuthorize} expressions below run.
 *
 * <p><strong>How the two failure statuses arise (and why not here).</strong> This controller
 * contains no explicit 401 or 403 handling because both are produced earlier and uniformly by the
 * filter chain configured in {@code SecurityConfig}:
 * <ul>
 *   <li><strong>401 Unauthorized</strong> &mdash; a request with a missing, malformed, expired, or
 *       otherwise invalid token never reaches these methods. The resource-server entry point
 *       ({@code BearerTokenAuthenticationEntryPoint}) rejects it with 401 and a
 *       {@code WWW-Authenticate: Bearer} challenge before dispatch (R8.4, R8.5).</li>
 *   <li><strong>403 Forbidden</strong> &mdash; a request that <em>is</em> authenticated but lacks
 *       the authority required by {@code @PreAuthorize} is stopped by method security
 *       <em>before the method body executes</em>. The {@code BearerTokenAccessDeniedHandler} returns
 *       403, and because the body never runs, <strong>no data is mutated</strong> on a denied write
 *       (R8.3).</li>
 * </ul>
 * Method security is active because {@code SecurityConfig} is annotated {@code @EnableMethodSecurity},
 * which is what makes the {@code @PreAuthorize} annotations on these handlers enforceable.
 *
 * <p><strong>Why {@code hasRole('Admin')} matches the converter.</strong> Spring's {@code hasRole(x)}
 * SpEL helper is sugar for checking the authority {@code ROLE_x}: it prepends the {@code ROLE_}
 * prefix for you. The {@code RolesClaimConverter} produces authorities using that exact same
 * {@code ROLE_} + rawValue scheme, so {@code hasRole('Admin')} checks for {@code ROLE_Admin} and
 * {@code hasAnyRole('Viewer','Admin')} checks for {@code ROLE_Viewer} or {@code ROLE_Admin}. The role
 * values are case-sensitive on both sides, matching the token's claim values.
 */
@RestController
@RequestMapping("/items")
class ItemController {

    /**
     * Application service that performs the actual read/write work and stamps the authenticated
     * caller's token subject onto persisted rows. The controller is deliberately thin: it owns only
     * the HTTP contract (routing, status codes, body validation) and the authorization gates, and
     * delegates all domain logic here.
     */
    private final ItemService service;

    /**
     * Constructor injection of the {@link ItemService}. Spring supplies the singleton service bean
     * at startup.
     *
     * @param service the item application service
     */
    ItemController(ItemService service) {
        this.service = service;
    }

    /**
     * Read endpoint: lists all items.
     *
     * <p>{@code @PreAuthorize("hasAnyRole('Viewer','Admin')")} permits any caller whose validated
     * token granted either {@code ROLE_Viewer} or {@code ROLE_Admin} (R8.1). Reads are intentionally
     * open to both roles &mdash; Viewers can see data, Admins can see and change it. On success this
     * returns HTTP 200 with the list of {@link ItemDto} (Spring serializes the returned body and uses
     * 200 as the default status for a plain return value). A caller authenticated but holding neither
     * role is denied with 403 before this body runs.
     *
     * @return the items as read-only DTOs (HTTP 200)
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('Viewer','Admin')")
    List<ItemDto> list() {
        return service.findAll();
    }

    /**
     * Read endpoint: lists the change history for a single item, newest first.
     *
     * <p>{@code @PreAuthorize("hasAnyRole('Viewer','Admin')")} mirrors the item read rule (R8.1):
     * history is observational data, so both Viewer and Admin may read it &mdash; even though only
     * Admin can <em>generate</em> it (only Admin can write items). The {@code {id}} path segment is
     * bound to {@code long id} via {@code @PathVariable}.
     *
     * <p>This returns HTTP 200 with the (possibly empty) list of {@link ItemHistoryDto}. It
     * deliberately does <em>not</em> 404 for an unknown/since-deleted id: history is allowed to
     * outlive its item, so a caller polling a deleted id gets a clean empty list rather than an error
     * (a deleted item's rows have a null {@code item_id} and appear only in the global log).
     *
     * @param id the id of the item whose history is requested (from the path)
     * @return that item's history as DTOs, newest first (HTTP 200)
     */
    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('Viewer','Admin')")
    List<ItemHistoryDto> history(@PathVariable long id) {
        return service.findHistoryForItem(id);
    }

    /**
     * Write endpoint: creates a new item.
     *
     * <p>{@code @PreAuthorize("hasRole('Admin')")} restricts this to callers whose token granted
     * {@code ROLE_Admin} (R8.2). A Viewer-only (or unroled) but authenticated caller is stopped by
     * method security with 403 <em>before</em> this method executes, so no row is inserted (R8.3).
     *
     * <p>The request body is bound from JSON into a {@link CreateItemRequest} and validated by
     * {@code @Valid}: its {@code @NotBlank name} is enforced, yielding a 400 Bad Request for a blank
     * name before the service is called. On success the service persists the row (stamping
     * {@code created_by} from the token subject) and this handler returns HTTP 201 Created with the
     * created {@link ItemDto} in the body, built explicitly via {@link ResponseEntity} (R8.2).
     *
     * @param request the validated create request (JSON body)
     * @return HTTP 201 Created with the persisted item
     */
    @PostMapping
    @PreAuthorize("hasRole('Admin')")
    ResponseEntity<ItemDto> create(@Valid @RequestBody CreateItemRequest request) {
        ItemDto created = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Write endpoint: updates an existing item identified by {@code id}.
     *
     * <p>{@code @PreAuthorize("hasRole('Admin')")} again limits mutation to {@code ROLE_Admin}
     * (R8.2, R8.3); a non-Admin authenticated caller receives 403 with no change to the row. The
     * {@code {id}} path segment is bound to the {@code long id} parameter via {@code @PathVariable},
     * and the JSON body is bound and validated into an {@link UpdateItemRequest} by {@code @Valid}
     * (its {@code @NotBlank name} guards against clearing the name).
     *
     * <p>On success the service applies the change and refreshes {@code updated_at}; returning the
     * updated {@link ItemDto} directly yields HTTP 200 with the DTO body.
     *
     * @param id      the identifier of the item to update (from the path)
     * @param request the validated update request (JSON body)
     * @return the updated item as a DTO (HTTP 200)
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('Admin')")
    ItemDto update(@PathVariable long id, @Valid @RequestBody UpdateItemRequest request) {
        return service.update(id, request);
    }

    /**
     * Write endpoint: deletes an existing item identified by {@code id}.
     *
     * <p>{@code @PreAuthorize("hasRole('Admin')")} limits deletion to {@code ROLE_Admin} (R8.2,
     * R8.3); a non-Admin authenticated caller receives 403 with no row removed, because method
     * security stops the request before this body runs. The {@code {id}} path segment is bound to
     * the {@code long id} parameter via {@code @PathVariable}.
     *
     * <p>On success the service removes the row and this handler returns HTTP <strong>204 No
     * Content</strong> &mdash; the conventional response for a successful delete that carries no
     * body. If no item exists for {@code id}, the service throws a 404, which Spring maps to an HTTP
     * 404 response. The allowed {@code DELETE} method is already advertised by the CORS policy
     * (see {@code JwtConfig.corsConfigurationSource}), so the browser preflight succeeds.
     *
     * @param id the identifier of the item to delete (from the path)
     * @return HTTP 204 No Content on success
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('Admin')")
    ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
