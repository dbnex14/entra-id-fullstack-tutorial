package com.example.entraoauth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Property-based tests for the Resource_Server's CORS policy, driven through {@link MockMvc}.
 *
 * <p><b>Feature:</b> entra-oauth-fullstack &mdash; <b>Property 3: CORS headers honor only the
 * allowed origin.</b>
 *
 * <p><b>Validates: Requirements 5.1, 5.5, 5.6</b>
 *
 * <h2>What this property says (the design's Correctness Property 3)</h2>
 * <ul>
 *   <li><b>Property A &mdash; the single allowed origin.</b> For a CORS preflight
 *       ({@code OPTIONS}) request carrying {@code Origin: http://localhost:4200} plus the
 *       {@code Access-Control-Request-Method} preflight marker, the response echoes
 *       {@code Access-Control-Allow-Origin: http://localhost:4200} (R5.1) AND carries
 *       {@code Access-Control-Allow-Credentials: true} (R5.5).</li>
 *   <li><b>Property B &mdash; every other origin.</b> For a preflight request whose {@code Origin}
 *       is any arbitrary origin-like string that is <i>not</i> exactly
 *       {@code http://localhost:4200}, the response contains <b>no</b>
 *       {@code Access-Control-Allow-Origin} header, so the browser blocks the response (R5.6).</li>
 * </ul>
 * Each {@code @Property} runs a minimum of 100 generated cases ({@code tries = 100}).
 *
 * <h2>Why the wiring below is shaped the way it is</h2>
 * <p>The production CORS policy lives in {@link JwtConfig#corsConfigurationSource()} and is wired
 * onto the filter chain by {@link SecurityConfig}. This test exercises that same policy hermetically
 * (no network, no real Entra OIDC discovery). Two constraints shape the setup:
 * <ol>
 *   <li><b>No {@code jqwik-spring}.</b> This project depends only on {@code net.jqwik:jqwik}; the
 *       Spring&harr;jqwik lifecycle bridge ({@code jqwik-spring}) is intentionally NOT on the
 *       classpath. So we cannot rely on {@code @Autowired}/{@code @WebMvcTest} inside a
 *       {@code @Property}. Instead we build a small Spring web context <b>programmatically</b> in a
 *       {@link BeforeContainer} hook and construct {@link MockMvc} from it &mdash; a self-contained,
 *       framework-managed context created exactly once for all generated cases.</li>
 *   <li><b>No network at startup.</b> The production {@link JwtConfig#jwtDecoder(List)} performs
 *       OIDC discovery against Entra when its bean is created, so we must NOT load {@code JwtConfig}.
 *       Instead {@link CorsSliceTestConfig} supplies a mock {@link JwtDecoder}, a default
 *       {@link JwtAuthenticationConverter}, and &mdash; crucially &mdash; a
 *       {@link CorsConfigurationSource} that <b>exactly mirrors</b>
 *       {@link JwtConfig#corsConfigurationSource()}. Mirroring (rather than importing
 *       {@code JwtConfig}) is what keeps the test offline while still exercising the production CORS
 *       policy verbatim.</li>
 * </ol>
 *
 * <p>The programmatic context registers Spring MVC ({@code @EnableWebMvc}), the real
 * {@link SecurityConfig} filter chain (which contributes {@code .cors(...)} and the
 * {@code permitAll()} rule for {@code OPTIONS /**}, R5.2), Spring Boot's security and OAuth2
 * resource-server auto-configuration (so the security filter is registered), the {@link ItemController}
 * (so {@code /items} is a real mapped path the preflight can target), and the stub/mirror beans.
 * {@link MockMvc} is then built with {@code apply(springSecurity())} so the security filter chain
 * &mdash; and therefore its CORS processing &mdash; participates in every simulated request.
 * Assertions target response <i>headers</i> only, because a preflight has no body.
 */
class CorsPropertyTest {

    /**
     * The single origin the production CORS policy allows. Kept as a constant so the generators and
     * oracles reference exactly the same value the mirrored policy uses.
     */
    private static final String ALLOWED_ORIGIN = "http://localhost:4200";

    /**
     * The programmatically-built Spring web application context. Created once in
     * {@link #startContext()} and torn down in {@link #stopContext()}. Held statically because jqwik
     * {@link BeforeContainer}/{@link AfterContainer} hooks are static and the same context (and the
     * {@link MockMvc} derived from it) is reused across every generated case of every property.
     */
    private static AnnotationConfigWebApplicationContext context;

    /**
     * The {@link MockMvc} used to issue simulated preflight requests with the real security filter
     * chain (and thus the CORS processor) applied. Built once from {@link #context}.
     */
    private static MockMvc mockMvc;

    /**
     * Builds the Spring web context and {@link MockMvc} a single time before any property in this
     * container runs. This replaces what {@code @WebMvcTest} + {@code @Autowired} would do, but works
     * without the {@code jqwik-spring} bridge:
     * <ul>
     *   <li>register {@link WebSliceConfig} (which pulls in MVC, the real {@link SecurityConfig},
     *       Spring Boot's security + OAuth2 resource-server auto-configuration, the
     *       {@link ItemController}, and the {@link CorsSliceTestConfig} stub/mirror beans);</li>
     *   <li>attach a {@link MockServletContext} so the web context can refresh headlessly;</li>
     *   <li>build {@link MockMvc} via {@code webAppContextSetup(...).apply(springSecurity())} so the
     *       Spring Security filter chain participates in each simulated request.</li>
     * </ul>
     * Because CORS preflight handling is stateless and the context is immutable after refresh,
     * reusing one context/MockMvc across all 100+ tries of both properties is safe and keeps the
     * suite fast.
     */
    @BeforeContainer
    static void startContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebSliceConfig.class);
        context.refresh();

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                // Register the Spring Security filter chain into the MockMvc pipeline so the real
                // SecurityConfig (its .cors(...) registration and OPTIONS permitAll rule) is active.
                .apply(springSecurity())
                .build();
    }

    /**
     * Closes the Spring context after all properties in this container have run, releasing any beans
     * and preventing context leakage between test classes.
     */
    @AfterContainer
    static void stopContext() {
        if (context != null) {
            context.close();
            context = null;
            mockMvc = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Property A (R5.1, R5.5): the single allowed origin is reflected AND credentials are allowed.
    // ---------------------------------------------------------------------------------------------

    /**
     * <b>Property A.</b> For a preflight ({@code OPTIONS}) request that carries the allowed origin,
     * the response reflects that origin in {@code Access-Control-Allow-Origin} (R5.1) and advertises
     * {@code Access-Control-Allow-Credentials: true} (R5.5).
     *
     * <p>Although the origin is fixed to {@link #ALLOWED_ORIGIN}, this is still expressed as a
     * property (running the minimum 100 tries) so it varies the second, orthogonal preflight
     * dimension &mdash; the requested method in {@code Access-Control-Request-Method} &mdash; across
     * the methods the policy permits. The allow-origin/allow-credentials guarantee must hold for the
     * allowed origin regardless of which permitted method the browser is about to use.
     *
     * <p>{@code generation = RANDOMIZED} is set explicitly so jqwik runs the full 100 tries. The
     * requested-method arbitrary has only five values, which jqwik would otherwise cover
     * exhaustively in five runs; forcing randomized generation honors the design's "minimum 100
     * generated cases per property" mandate.
     *
     * @param requestedMethod a jqwik-generated value for the {@code Access-Control-Request-Method}
     *                        preflight header, drawn from the methods the CORS policy allows
     * @throws Exception if the simulated request dispatch fails
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void allowedOriginIsReflectedWithCredentials(@ForAll("allowedRequestMethods") String requestedMethod)
            throws Exception {
        MockHttpServletResponse response = mockMvc.perform(options("/items")
                        // The Origin header identifies the calling site; this is the one origin the
                        // production policy trusts (mirrored in CorsSliceTestConfig).
                        .header("Origin", ALLOWED_ORIGIN)
                        // The Access-Control-Request-Method header is what makes this a genuine CORS
                        // preflight; the browser announces the method the real request will use.
                        .header("Access-Control-Request-Method", requestedMethod))
                .andReturn().getResponse();

        // (R5.1) The allowed origin is echoed back verbatim (not a wildcard), so the browser lets
        // the SPA read the eventual cross-origin response.
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED_ORIGIN);
        // (R5.5) Credentialed cross-origin requests are honored for the allowed origin.
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    /**
     * Generates the HTTP methods the production CORS policy permits, used to vary the preflight's
     * {@code Access-Control-Request-Method} for Property A. These mirror the allowed methods in
     * {@link JwtConfig#corsConfigurationSource()} ({@code GET/POST/PUT/DELETE/OPTIONS}).
     */
    @Provide
    Arbitrary<String> allowedRequestMethods() {
        return Arbitraries.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }

    // ---------------------------------------------------------------------------------------------
    // Property B (R5.6): any origin other than the allowed one gets NO Access-Control-Allow-Origin.
    // ---------------------------------------------------------------------------------------------

    /**
     * <b>Property B.</b> For a preflight request whose {@code Origin} is any arbitrary origin-like
     * string that is <i>not</i> exactly {@link #ALLOWED_ORIGIN}, the response omits the
     * {@code Access-Control-Allow-Origin} header entirely (R5.6). With no matching allow-origin, a
     * real browser blocks the response, which is exactly how a single-origin policy keeps other
     * sites from reading this API on a user's behalf.
     *
     * @param origin a jqwik-generated arbitrary origin-like string, filtered to exclude the one
     *               allowed origin
     * @throws Exception if the simulated request dispatch fails
     */
    @Property(tries = 100)
    void disallowedOriginsGetNoAllowOriginHeader(@ForAll("disallowedOrigins") String origin)
            throws Exception {
        MockHttpServletResponse response = mockMvc.perform(options("/items")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET"))
                .andReturn().getResponse();

        // (R5.6) No ACAO header for any non-allowed origin -> the browser blocks the response.
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    /**
     * Generates arbitrary origin-like strings that are guaranteed <b>not</b> to equal
     * {@link #ALLOWED_ORIGIN}. The generator builds well-formed {@code scheme://host[:port]} values
     * by combining:
     * <ul>
     *   <li>a scheme drawn from {@code http}/{@code https},</li>
     *   <li>a host built from alphanumeric labels (plus a small pool of realistic hostnames), and</li>
     *   <li>an optional explicit port.</li>
     * </ul>
     * The combined result is then filtered to drop the single case that happens to reconstruct
     * exactly {@code http://localhost:4200}, so every generated value exercises the disallowed-origin
     * branch (R5.6). Producing realistic same-scheme, near-miss origins (e.g.
     * {@code http://localhost:4300}, {@code https://localhost:4200}, {@code http://evil.example})
     * is deliberately more adversarial than random junk: it proves the policy matches the origin
     * exactly rather than by a loose prefix/host heuristic.
     */
    @Provide
    Arbitrary<String> disallowedOrigins() {
        Arbitrary<String> scheme = Arbitraries.of("http", "https");

        // Host: either a curated realistic hostname, or a synthetic alphanumeric label. Mixing both
        // yields near-miss origins (localhost on a different port/scheme) alongside clearly foreign
        // hosts, covering both the "same host, different origin tuple" and "entirely other site" cases.
        Arbitrary<String> curatedHost = Arbitraries.of(
                "localhost", "127.0.0.1", "evil.example", "example.com", "attacker.test");
        Arbitrary<String> syntheticHost = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz0123456789")
                .ofMinLength(1)
                .ofMaxLength(12);
        Arbitrary<String> host = Arbitraries.oneOf(curatedHost, syntheticHost);

        // Port: absent (empty string) or an explicit ":<port>". Including 4200 among the choices,
        // paired with a non-http scheme or non-localhost host, still yields a distinct origin, while
        // other ports (e.g. 4300, 8080) exercise the same-host/different-port near miss.
        Arbitrary<String> port = Arbitraries.oneOf(
                Arbitraries.just(""),
                Arbitraries.of(80, 443, 3000, 4200, 4300, 8080, 8081, 65535)
                        .map(p -> ":" + p));

        return Combinators.combine(scheme, host, port)
                .as((s, h, p) -> s + "://" + h + p)
                // Exclude the exactly-allowed origin so every case exercises the R5.6 branch.
                .filter(o -> !o.equals(ALLOWED_ORIGIN));
    }

    // ---------------------------------------------------------------------------------------------
    // Programmatic context configuration (stand-in for @WebMvcTest, without jqwik-spring).
    // ---------------------------------------------------------------------------------------------

    /**
     * The root configuration for the programmatically-built web slice. It assembles exactly the
     * pieces needed to route a preflight through the production security + CORS machinery, and no
     * more:
     * <ul>
     *   <li>{@code @EnableWebMvc} &mdash; registers Spring MVC infrastructure (handler mappings,
     *       the dispatcher wiring) so {@code /items} resolves and CORS preflight is processed;</li>
     *   <li>{@code @Import(SecurityConfig.class)} &mdash; the <b>real</b> production filter chain,
     *       including its {@code .cors(...)} registration and {@code OPTIONS /**} {@code permitAll()}
     *       rule (R5.2);</li>
     *   <li>{@code @Import} of Spring Boot's {@link SecurityAutoConfiguration} and
     *       {@link OAuth2ResourceServerAutoConfiguration} &mdash; so the Spring Security filter is
     *       registered and the resource-server support is present, mirroring what runs in production;</li>
     *   <li>{@code @Import(CorsSliceTestConfig.class)} &mdash; the offline stub {@link JwtDecoder},
     *       the {@link JwtAuthenticationConverter}, and the mirrored production {@link CorsConfigurationSource};</li>
     *   <li>{@code @Import(ProbeController.class)} &mdash; a tiny public test controller mapping
     *       {@code /items}, so the preflight has a real mapped path to target without dragging in
     *       the production JPA service/repository layer. The real {@code ItemController} is
     *       package-private in a different package and cannot be referenced from here; a local probe
     *       controller is functionally equivalent for CORS preflight purposes because a preflight is
     *       answered by the CORS processor, not by any handler method.</li>
     * </ul>
     */
    @Configuration
    @EnableWebMvc
    @Import({
            SecurityConfig.class,
            SecurityAutoConfiguration.class,
            OAuth2ResourceServerAutoConfiguration.class,
            CorsSliceTestConfig.class,
            ProbeController.class
    })
    static class WebSliceConfig {
    }

    /**
     * A minimal public REST controller that exists only to give the CORS preflight a real, mapped
     * path ({@code /items}) to probe. The path deliberately matches the production controller's
     * mapping so the {@code /**} CORS registration and the {@code OPTIONS /**} {@code permitAll()}
     * rule apply exactly as they would in production.
     *
     * <p>Its {@code GET} handler is never invoked by these tests: a CORS preflight is an
     * {@code OPTIONS} request answered by Spring's CORS processor before any handler runs. The
     * handler is present only so that {@code /items} resolves as a known mapping.
     */
    @RestController
    @RequestMapping("/items")
    static class ProbeController {

        /**
         * Placeholder read mapping so {@code /items} is a registered path. Never called by the
         * preflight-only tests in this class.
         *
         * @return an empty list (unused by these CORS tests)
         */
        @GetMapping
        List<String> list() {
            return List.of();
        }
    }

    /**
     * Minimal test-only bean wiring that satisfies {@link SecurityConfig#filterChain}'s dependencies
     * with <b>no</b> network access, while providing the <b>real</b> single-origin CORS policy so the
     * property assertions exercise production behavior.
     *
     * <p>The imported {@link SecurityConfig} needs three collaborators the production
     * {@code JwtConfig} would provide (and which we deliberately do not load, to stay offline); this
     * configuration supplies them:
     * <ul>
     *   <li>{@link #jwtDecoder()} &mdash; a Mockito mock so context startup performs no OIDC
     *       discovery / JWKS fetch. It is never called because preflight requests carry no token.</li>
     *   <li>{@link #jwtAuthenticationConverter()} &mdash; a default converter to satisfy the
     *       {@code oauth2ResourceServer().jwt()} wiring; unauthenticated preflights never exercise it.</li>
     *   <li>{@link #corsConfigurationSource()} &mdash; a {@link CorsConfigurationSource} that
     *       <b>exactly mirrors</b> {@link JwtConfig#corsConfigurationSource()}, the bean the property
     *       under test actually depends on.</li>
     * </ul>
     */
    @TestConfigurationMarker
    static class CorsSliceTestConfig {

        /**
         * Provides a mock {@link JwtDecoder} so the OAuth2 resource-server configuration in
         * {@link SecurityConfig} can be assembled entirely offline (no issuer discovery, no JWKS
         * fetch). Preflight requests are unauthenticated, so the decoder is never invoked.
         *
         * @return a Mockito mock {@link JwtDecoder} that performs no network I/O
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return org.mockito.Mockito.mock(JwtDecoder.class);
        }

        /**
         * Provides the JWT authentication converter bean the imported {@link SecurityConfig}
         * requires. A default {@link JwtAuthenticationConverter} suffices because CORS preflight
         * requests are unauthenticated and never trigger token-to-authority conversion.
         *
         * @return a default JWT-to-authentication converter to satisfy the filter-chain wiring
         */
        @Bean
        Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
            return new JwtAuthenticationConverter();
        }

        /**
         * Provides the CORS policy under test. This bean is an <b>exact mirror</b> of the production
         * {@link JwtConfig#corsConfigurationSource()}: a single allowed origin
         * ({@code http://localhost:4200}), the methods {@code GET/POST/PUT/DELETE/OPTIONS}, the
         * request headers {@code Authorization}/{@code Content-Type}, and {@code allowCredentials=true},
         * applied to every path ({@code /**}).
         *
         * <p>It is deliberately duplicated here rather than pulled in via
         * {@code @Import(JwtConfig.class)}, because importing {@code JwtConfig} would also instantiate
         * its real {@code JwtDecoder} bean, which triggers a network OIDC discovery at startup and
         * would break the hermetic/offline nature of this test. If the production policy in
         * {@link JwtConfig} ever changes, keep this mirror in sync.
         *
         * <p>Marked {@link Primary} to disambiguate injection into {@link SecurityConfig#filterChain}:
         * {@code @EnableWebMvc} also contributes an {@code mvcHandlerMappingIntrospector} bean that
         * happens to implement {@link CorsConfigurationSource}, so without a primary marker the
         * by-type injection of the CORS source would be ambiguous. In production this ambiguity does
         * not arise because Spring Boot's MVC auto-configuration (not {@code @EnableWebMvc}) wires the
         * introspector differently; the {@link Primary} marker here reproduces the production outcome
         * of "the application's single-origin policy is the one that governs the filter chain".
         *
         * @return a {@link CorsConfigurationSource} identical to the production single-origin policy
         */
        @Bean
        @Primary
        CorsConfigurationSource corsConfigurationSource() {
            CorsConfiguration config = new CorsConfiguration();
            // (R5.1, R5.6) Single explicit origin. Any other origin receives no
            // Access-Control-Allow-Origin header, so the browser blocks it.
            config.setAllowedOrigins(List.of(ALLOWED_ORIGIN));
            // (R5.3) Methods the SPA may use cross-origin, including the OPTIONS preflight itself.
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            // (R5.4) Request headers the browser may send: the bearer token and the JSON content type.
            config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            // (R5.5) Permit credentialed cross-origin requests for the allowed origin.
            config.setAllowCredentials(true);

            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", config);
            return source;
        }
    }

    /**
     * A local alias for {@link Configuration} used to annotate {@link CorsSliceTestConfig}. Using a
     * meta-annotation here keeps the nested configuration class self-descriptive as a "test
     * configuration" while remaining a plain {@code @Configuration} to the container (we avoid
     * {@code @TestConfiguration}, whose auto-registration semantics apply to Spring Boot's test
     * slices rather than a hand-built {@link AnnotationConfigWebApplicationContext}).
     */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
    @Configuration
    @interface TestConfigurationMarker {
    }
}
