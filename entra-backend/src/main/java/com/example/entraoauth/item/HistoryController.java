package com.example.entraoauth.item;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role-protected REST controller exposing the <strong>global</strong> item change log under
 * {@code /history} (served at {@code /entra-backend/history} once the servlet context path is
 * applied).
 *
 * <p><strong>Why a separate controller instead of a method on {@link ItemController}.</strong>
 * {@link ItemController} is mapped at the class level with {@code @RequestMapping("/items")}, so every
 * handler it declares is prefixed with {@code /items} (that is why the per-item history lives at
 * {@code /items/{id}/history}). The feature's global change log is specified at the top-level path
 * {@code /entra-backend/history}, which cannot be produced from under the {@code /items} prefix. A
 * small dedicated controller mapped at {@code /history} yields exactly that path while keeping each
 * controller's routing focused and easy to read.
 *
 * <p><strong>Same security posture as the item reads.</strong> Like the other GET endpoints, this is
 * gated with {@code @PreAuthorize("hasAnyRole('Viewer','Admin')")}: the change log is observational,
 * so both Viewer and Admin may read it, while only Admin can generate entries (only Admin can write
 * items). By the time this handler runs, the request has already passed the stateless filter chain
 * (token signature/issuer/audience/expiry validation and {@code roles} &rarr; {@code ROLE_*}
 * conversion); an unauthenticated caller is rejected with 401 before dispatch, and an authenticated
 * caller lacking either role is rejected with 403 before the body runs (R8). This controller never
 * parses a token itself and never makes an authorization decision beyond the declarative
 * {@code @PreAuthorize} gate.
 */
@RestController
@RequestMapping("/history")
class HistoryController {

    /**
     * Application service that reads the change log. The controller is deliberately thin: it owns
     * only the HTTP contract and the authorization gate, delegating the query to the service.
     */
    private final ItemService service;

    /**
     * Constructor injection of the {@link ItemService}. Spring supplies the singleton service bean at
     * startup.
     *
     * @param service the item application service (also serves history reads)
     */
    HistoryController(ItemService service) {
        this.service = service;
    }

    /**
     * Read endpoint: lists the entire item change log, newest first.
     *
     * <p>{@code @PreAuthorize("hasAnyRole('Viewer','Admin')")} permits any caller whose validated
     * token granted {@code ROLE_Viewer} or {@code ROLE_Admin} (R8.1). Returns HTTP 200 with the list
     * of {@link ItemHistoryDto} (empty if there is no history yet). Includes rows whose originating
     * item was later deleted (those carry a null {@code itemId}), which is the value of a global log:
     * the record of a deletion is retained even after the item is gone.
     *
     * @return the full change log as DTOs, newest first (HTTP 200)
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('Viewer','Admin')")
    List<ItemHistoryDto> list() {
        return service.findHistory();
    }
}
