package com.example.entraoauth.security;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.entraoauth.audit.AccessAudit;
import com.example.entraoauth.audit.AccessAuditRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A servlet filter that records an audit trail row for each API request into the {@code access_audit}
 * table, for the instructional "server-side authority confirmation" verification step (see design
 * "Testing Strategy &gt; Server-side authority confirmation").
 *
 * <p><strong>AUDIT ONLY &mdash; THIS IS NOT AN AUTHORIZATION DECISION (R2).</strong> This filter does
 * <em>not</em> decide whether a request is allowed. Authorization is claim-driven end to end: the
 * resource-server filter validates the Access_Token and the {@link RolesClaimConverter} turns its
 * {@code roles} claim into {@code ROLE_*} authorities, which {@code @PreAuthorize} on the controllers
 * then enforces. This filter runs <em>alongside</em> that pipeline purely to <strong>record the
 * OUTCOME</strong> of a decision that has already been made &mdash; it never reads the audit table to
 * gate anything, and it never blocks, alters, or short-circuits a request. Its sole job is to write a
 * row describing "subject X, holding authorities Y, called {@code METHOD path} and got status Z".
 *
 * <p><strong>Why it records AFTER the request completes.</strong> The response status (200 / 201 /
 * 403 / ...) is only known once the downstream filters and the controller have run. Therefore this
 * filter calls {@link FilterChain#doFilter} <em>first</em> and captures the audit data in a
 * {@code finally} block afterwards, reading:
 * <ul>
 *   <li>the {@link Authentication} from the {@link SecurityContextHolder} &mdash; populated by the
 *       resource-server's {@code BearerTokenAuthenticationFilter}, which is why this filter is wired
 *       to run <em>after</em> that filter in {@link SecurityConfig};</li>
 *   <li>the resolved {@code ROLE_*} authorities from that authentication;</li>
 *   <li>the HTTP method and the request path ({@link HttpServletRequest#getRequestURI()});</li>
 *   <li>the final {@link HttpServletResponse#getStatus() response status}.</li>
 * </ul>
 *
 * <p><strong>Scoped to {@code /entra-backend/**}.</strong> To avoid noise from static assets and
 * other traffic outside this application, {@link #shouldNotFilter(HttpServletRequest)} restricts
 * auditing to request paths under the {@code /entra-backend/} servlet context path. The comparison is
 * against {@link HttpServletRequest#getRequestURI()}, which includes that context path. Everything
 * else is passed straight through with no audit row.
 *
 * <p><strong>Auditing must never break the request.</strong> A failure to persist an audit row is a
 * bookkeeping problem, not a user-facing one. The persistence call is therefore wrapped in a
 * try/catch that logs and <em>swallows</em> any exception: the response the user receives is never
 * affected by an audit-write failure. (Equally, the audit write happens in the {@code finally} block
 * <em>after</em> {@code doFilter}, so even if writing the row throws, the response has already been
 * produced and committed.)
 *
 * <p><strong>Lifecycle / construction.</strong> Because this filter is constructed and wired
 * explicitly in {@link SecurityConfig} (rather than component-scanned), its {@link AccessAuditRepository}
 * collaborator is supplied via constructor injection from the Spring context. See
 * {@link SecurityConfig} for how the filter is added to the chain <em>only when</em> a repository bean
 * is present, so security-slice tests that lack a JPA layer are unaffected.
 */
public class AccessAuditFilter extends OncePerRequestFilter {

    /**
     * Logger used to report (and swallow) any failure to persist an audit row, so that auditing never
     * affects the user-facing response.
     */
    private static final Logger log = LoggerFactory.getLogger(AccessAuditFilter.class);

    /**
     * The path prefix this filter audits. Only requests whose URI starts with this prefix produce an
     * audit row; all other traffic is passed through untouched to avoid noise.
     */
    private static final String API_PREFIX = "/entra-backend/";

    /**
     * Placeholder subject recorded when a request reaches this point without an authenticated
     * principal (e.g. an anonymous request that was rejected upstream with 401). The row still
     * captures the method/path/status for the verification story, but attributes it to no identity.
     */
    private static final String ANONYMOUS_SUBJECT = "anonymous";

    /**
     * Repository used to persist audit rows. Injected by {@link SecurityConfig} when constructing the
     * filter. This is used <em>only</em> to write rows; it is never queried to make a decision (R2).
     */
    private final AccessAuditRepository repository;

    /**
     * Creates the filter with the repository it will use to persist audit rows.
     *
     * @param repository the {@link AccessAuditRepository} Spring bean used to save audit rows
     */
    public AccessAuditFilter(AccessAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Restricts auditing to this application's traffic. Returning {@code true} tells
     * {@link OncePerRequestFilter} to skip this filter entirely for the given request, so only
     * requests under the {@code /entra-backend/} context path are audited; anything outside it is
     * passed through with no audit row (keeping the {@code access_audit} table focused on this
     * application's requests).
     *
     * @param request the incoming request
     * @return {@code true} to skip auditing (non-API path); {@code false} to audit it
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith(API_PREFIX);
    }

    /**
     * Runs the rest of the filter chain first, then &mdash; in a {@code finally} block, so the
     * response status is known and the audit write can never suppress the actual response &mdash;
     * records one {@link AccessAudit} row describing the request outcome.
     *
     * <p>The security context is read <em>after</em> {@code doFilter} so the authenticated principal
     * (populated by the resource-server's {@code BearerTokenAuthenticationFilter}, which runs before
     * this filter) and its resolved {@code ROLE_*} authorities are available. The write is wrapped in
     * a try/catch that logs and swallows failures, because auditing must never affect the user-facing
     * response (R2 &mdash; this is bookkeeping, not an access decision).
     *
     * @param request     the incoming request
     * @param response    the outgoing response (its status is read after the chain completes)
     * @param filterChain the remaining filter chain to invoke
     * @throws ServletException if the downstream chain throws it
     * @throws IOException      if the downstream chain throws it
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // Let the request be fully handled first. Only after this returns is the response status
            // (200/201/403/...) known, which is exactly what we want to audit.
            filterChain.doFilter(request, response);
        } finally {
            // Record the outcome AFTER the request completed. Any failure here is logged and
            // swallowed so it can never turn a successful response into an error for the caller.
            try {
                recordAudit(request, response);
            } catch (RuntimeException ex) {
                // Audit is best-effort: a persistence failure must not break the request (R2).
                log.warn("Failed to persist access_audit row for {} {} (audit is best-effort, "
                        + "request outcome is unaffected)",
                        request.getMethod(), request.getRequestURI(), ex);
            }
        }
    }

    /**
     * Builds and persists a single audit row from the completed request's security context and
     * response.
     *
     * <p>Reads the {@link Authentication} from the {@link SecurityContextHolder}; if present, uses its
     * {@code name} as the subject (the token's {@code sub}/{@code oid}) and joins its authorities into
     * a comma-separated string. If there is no authenticated principal (e.g. an anonymous request that
     * was rejected upstream), records a placeholder subject and empty authorities so the method, path,
     * and status are still captured for the verification story.
     *
     * @param request  the completed request
     * @param response the completed response (its committed status is recorded)
     */
    private void recordAudit(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        String subject;
        String authorities;
        if (authentication != null && authentication.isAuthenticated()) {
            // For a JWT resource server, the authentication name is the token subject (sub/oid).
            subject = authentication.getName();
            // Serialize the resolved ROLE_* authorities so the verification guide can cross-check
            // them against the presented token's `roles` claim (R2.2-R2.4). This is a record of the
            // decision inputs, not a decision itself.
            authorities = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.joining(","));
        } else {
            // No authenticated principal (e.g. anonymous request rejected with 401 upstream).
            subject = ANONYMOUS_SUBJECT;
            authorities = "";
        }

        AccessAudit row = new AccessAudit(
                subject,
                authorities,
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                OffsetDateTime.now());

        // Spring Data's save(..) opens and commits its own transaction, so this row is durably
        // written even though no application-managed transaction is active in the filter chain.
        repository.save(row);
    }
}
