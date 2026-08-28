package com.example.entraoauth.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

import com.example.entraoauth.audit.AccessAuditRepository;

/**
 * The single, authoritative HTTP security policy for the Resource_Server. Everything about
 * "who may call which endpoint, and what happens when a request is unauthenticated or
 * unauthorized" is decided here and in the method-level {@code @PreAuthorize} rules this class
 * switches on.
 *
 * <p>This is the last piece of the backend trust boundary. {@link JwtConfig} (task 2.3) already
 * defined <em>how</em> a bearer token is decoded/validated and <em>how</em> its {@code roles}
 * claim becomes {@code ROLE_*} authorities; this class wires those beans into the servlet filter
 * chain and defines the coarse per-path access rules. Together they implement a
 * <strong>stateless, claim-driven, bearer-token</strong> API:
 *
 * <ul>
 *   <li>No server session is ever created &mdash; every request must carry its own proof of
 *       identity (the bearer token). See {@code SessionCreationPolicy.STATELESS} below (R6, R8).</li>
 *   <li>Authorization is driven entirely by claims in the validated token (the {@code roles}
 *       claim), never by any server-side user record (R2). The database {@code app_user}/
 *       {@code access_audit} tables are audit/profile only.</li>
 *   <li>Authentication failures (missing/expired/invalid token) produce <strong>401</strong>;
 *       authorization failures (valid token but missing role) produce <strong>403</strong>.
 *       See the entry-point / access-denied notes at the end of {@link #filterChain}.</li>
 * </ul>
 *
 * <p><strong>{@code @EnableMethodSecurity}.</strong> This annotation activates Spring Security's
 * method-security interceptor so that {@code @PreAuthorize("hasRole('Admin')")} /
 * {@code @PreAuthorize("hasAnyRole('Viewer','Admin')")} on the REST controller methods (task 3.4)
 * are actually enforced (R8). Those expressions test the {@code ROLE_*} authorities that
 * {@link RolesClaimConverter} produced from the token's {@code roles} claim; a caller lacking the
 * required authority is denied with 403 by the {@code BearerTokenAccessDeniedHandler} (R2.7, R8.3).
 *
 * <p><strong>Access-audit filter (task 9.2).</strong> This class also wires an
 * {@link AccessAuditFilter} into the chain, positioned <em>after</em> the resource server's
 * {@code BearerTokenAuthenticationFilter} so the authenticated principal is available. That filter is
 * <strong>audit-only</strong> &mdash; it records the outcome of each {@code /api/**} request (subject,
 * resolved {@code ROLE_*} authorities, method, path, status) for the server-side authority-confirmation
 * verification story, and never makes an authorization decision (R2). It is added <em>only</em> when an
 * {@link AccessAuditRepository} bean is present (resolved via {@link ObjectProvider}), so this config
 * degrades gracefully for slice tests that lack a JPA layer.
 */
@Configuration
@EnableMethodSecurity // enables @PreAuthorize on controller methods (R8)
public class SecurityConfig {

    /**
     * Builds the one {@link SecurityFilterChain} that every HTTP request to this server flows
     * through. The two collaborator beans are injected by type from {@link JwtConfig}:
     *
     * @param http        the Spring-provided builder used to declare the chain's rules
     * @param cors        the {@link CorsConfigurationSource} defined in {@link JwtConfig}
     *                    ({@code corsConfigurationSource}, R5): the single-origin
     *                    ({@code http://localhost:4200}) credentialed CORS policy. Registering it
     *                    here makes both the preflight ({@code OPTIONS}) and the actual response
     *                    carry the correct {@code Access-Control-Allow-*} headers.
     * @param jwtConverter the {@code Converter<Jwt, AbstractAuthenticationToken>} defined in
     *                    {@link JwtConfig} ({@code jwtAuthenticationConverter}, R6): it turns a
     *                    decoded/validated {@link Jwt} into the authenticated principal, deriving
     *                    {@code ROLE_*} authorities from the {@code roles} claim via
     *                    {@link RolesClaimConverter}.
     * @param auditRepositoryProvider an {@link ObjectProvider} for the {@link AccessAuditRepository}
     *                    (task 9.2). It is deliberately an {@code ObjectProvider} rather than a direct
     *                    dependency so this configuration <strong>degrades gracefully</strong>: in the
     *                    full application a repository bean exists and the {@link AccessAuditFilter} is
     *                    added to the chain; in web/security-slice tests that import this
     *                    {@code SecurityConfig} without a JPA layer, no repository bean is present, the
     *                    provider resolves to nothing, and the audit filter is simply not added &mdash;
     *                    so those tests continue to load the context and pass unchanged. The audit
     *                    filter is <em>audit-only</em> and never affects authorization (R2), so
     *                    omitting it in a slice test does not change any security behavior under test.
     * @return the fully-built, stateless, JWT-resource-server filter chain
     * @throws Exception if the {@link HttpSecurity} builder fails to assemble the chain
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, CorsConfigurationSource cors,
                                    Converter<Jwt, AbstractAuthenticationToken> jwtConverter,
                                    ObjectProvider<AccessAuditRepository> auditRepositoryProvider)
            throws Exception {
        http
            // (R5) Register the CORS policy from JwtConfig. Spring Security's CorsFilter runs early
            // in the chain, before authentication, so browser preflight (OPTIONS) requests are
            // answered with the Access-Control-Allow-* headers even though they carry no token.
            .cors(c -> c.configurationSource(cors))
            // Disable CSRF. CSRF protection defends session-cookie-based apps against a browser
            // silently attaching an ambient credential; this API holds NO session and authenticates
            // solely via an explicit `Authorization: Bearer` header that a cross-site attacker
            // cannot forge, so CSRF tokens add no value here. (Consistent with STATELESS below.)
            .csrf(AbstractHttpConfigurer::disable)
            // (R6, R8) Never create or use an HttpSession. Each request must independently present a
            // valid bearer token; nothing about the caller is remembered between requests. This is
            // what makes the server horizontally scalable and the security model purely token-driven.
            .sessionManagement(s -> s.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS))
            // Coarse, path-level access rules. These run in order; the first matching rule wins.
            .authorizeHttpRequests(auth -> auth
                // (R5.2) Permit all CORS preflight requests. Browsers send an unauthenticated
                // OPTIONS request before a real cross-origin call; it carries no Authorization
                // header, so it must be allowed through or the actual request never happens.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Permit the health probe so orchestrators/load-balancers can check liveness
                // without a token. This endpoint exposes no protected data.
                .requestMatchers("/actuator/health").permitAll()
                // (R8.4) Everything else requires a valid, authenticated bearer token. A request
                // with no token (or an invalid one) never reaches a controller; it is stopped here
                // and answered with 401 by the entry point noted below.
                .anyRequest().authenticated())
            // (R6) Turn this application into an OAuth2 Resource Server that authenticates via JWT.
            // The JwtDecoder bean from JwtConfig (task 2.3) is auto-detected by type and performs
            // signature + issuer + expiry(skew) + audience validation on the presented token
            // (R6.3, R6.7). We explicitly set the authentication converter so that authorities are
            // derived from the Entra `roles` claim (ROLE_*), not the default `scope`/`scp` claim.
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));

        // (Task 9.2) AUDIT-ONLY access-audit filter. This is NOT an authorization decision (R2): it
        // records the OUTCOME of the claim-driven decision (subject, resolved ROLE_* authorities,
        // method, path, response status) into the access_audit table for the server-side
        // authority-confirmation verification step. It never gates a request.
        //
        // We resolve the AccessAuditRepository lazily via ObjectProvider so this config degrades
        // gracefully: only when a repository bean actually exists (the full application, with the JPA
        // layer) is the filter constructed and added. Web/security-slice tests that @Import this
        // SecurityConfig without a JPA layer have no such bean, so getIfAvailable() returns null and
        // the filter is skipped -- keeping those tests loading and passing unchanged.
        //
        // Placement: addFilterAfter(..., BearerTokenAuthenticationFilter.class). The resource server's
        // BearerTokenAuthenticationFilter is what validates the token and populates the
        // SecurityContext, so running the audit filter AFTER it guarantees the authenticated principal
        // and its authorities are available for recording.
        AccessAuditRepository auditRepository = auditRepositoryProvider.getIfAvailable();
        if (auditRepository != null) {
            http.addFilterAfter(new AccessAuditFilter(auditRepository),
                    BearerTokenAuthenticationFilter.class);
        }
        // Failure handling is provided by the framework defaults for a bearer-token resource server:
        //
        //   * Authentication failure (missing / expired / malformed / invalid-signature / wrong-aud
        //     / wrong-iss token): the default BearerTokenAuthenticationEntryPoint returns HTTP 401
        //     Unauthorized together with a `WWW-Authenticate: Bearer ...` challenge header. For an
        //     expired or otherwise invalid token the challenge carries error="invalid_token", which
        //     is exactly what the frontend interceptor keys on to trigger a silent refresh
        //     (R3.2, R8.5).
        //
        //   * Authorization failure (a valid, authenticated token that lacks the ROLE_* authority a
        //     @PreAuthorize rule requires): the default BearerTokenAccessDeniedHandler returns HTTP
        //     403 Forbidden. Because the request is rejected before the controller body runs, no
        //     state is mutated on a forbidden write (R2.7, R8.3).
        return http.build();
    }
}
