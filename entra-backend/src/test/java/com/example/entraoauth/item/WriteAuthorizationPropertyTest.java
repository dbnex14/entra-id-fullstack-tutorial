package com.example.entraoauth.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.example.entraoauth.security.SecurityConfig;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * Property-based tests proving that the {@code item} write endpoints are gated on
 * {@code ROLE_Admin}: a caller holding that authority may create a row (success + the row count
 * grows by one), while a caller lacking it is rejected with <b>403</b> and the row count is left
 * <b>unchanged</b> (no mutation on a forbidden write).
 *
 * <p><b>Feature:</b> entra-oauth-fullstack &mdash; <b>Property 4: Write endpoints require
 * ROLE_Admin.</b>
 *
 * <p><b>Validates: Requirements 8.2, 8.3</b>
 *
 * <h2>What this property says (the design's Correctness Property 4)</h2>
 * <p>For <i>any</i> request to a write endpoint ({@code POST /items}) whose granted authorities
 * do <b>not</b> include {@code ROLE_Admin}, the Resource_Server responds <b>403</b> and performs
 * <b>no</b> data mutation. For <i>any</i> such request whose authorities <b>do</b> include
 * {@code ROLE_Admin}, the response is a success status (<b>200</b>/<b>201</b> &mdash; the real
 * controller returns 201 Created) and the mutation is applied (the item count increases by one).
 * The property runs a minimum of 100 generated cases ({@code tries = 100}).
 *
 * <h2>The no-mutation oracle (count before/after)</h2>
 * <p>The design expresses "no data is modified on 403" (R8.3) as a
 * {@code repository.count()}-based oracle: snapshot the count before the write, perform the write,
 * then assert the count is {@code before + 1} for an authorized Admin write and exactly
 * {@code before} for a forbidden non-Admin write. This test slice has <b>no database</b> (it is a
 * hermetic, offline security slice &mdash; no JPA, no PostgreSQL, no network), so it replicates that
 * exact semantics with an in-memory <i>fake</i> {@link ItemService} whose {@link FakeItemService#count()}
 * reflects an {@link AtomicInteger} that {@link FakeItemService#create(CreateItemRequest)} increments.
 * Because the real {@link ItemController}'s {@code @PreAuthorize("hasRole('Admin')")} check runs
 * <i>before</i> the controller body, a forbidden write never reaches {@code service.create(..)} and
 * therefore never bumps the counter &mdash; making "count unchanged on 403" (R8.3) directly
 * observable, precisely mirroring the design's {@code repository.count()} oracle.
 *
 * <h2>Why the real ItemController is exercised (not a probe)</h2>
 * <p>Unlike {@code CorsPropertyTest} / {@code InvalidTokenRejectionPropertyTest} (which live in the
 * {@code security} package and therefore had to use a local {@code ProbeController} because the real
 * {@link ItemController} is package-private in {@code com.example.entraoauth.item}), <b>this test is
 * itself in {@code com.example.entraoauth.item}</b>. It can therefore reference and register the
 * <b>real</b> package-private {@link ItemController} directly. That is essential: the whole point of
 * Property 4 is to exercise the actual {@code @PreAuthorize("hasRole('Admin')")} annotation on the
 * real {@code POST} mapping, wired to a controllable (fake) service so the count oracle works.
 *
 * <h2>How a caller's authorities are simulated</h2>
 * <p>Rather than mint and decode a real Entra token, this test uses {@code spring-security-test}'s
 * {@code jwt()} request post-processor with {@code .authorities(...)}. That injects an already
 * authenticated JWT principal carrying exactly the generated {@code ROLE_*} authorities, short-
 * circuiting token decoding while still driving the request through the real
 * {@link SecurityConfig} filter chain and, crucially, through {@code @EnableMethodSecurity}'s
 * {@code @PreAuthorize} evaluation. This isolates the property under test (authorization by role)
 * from token validation (covered by Property 2).
 *
 * <h2>Why the wiring below is shaped the way it is</h2>
 * <p>This mirrors the proven offline pattern in {@code CorsPropertyTest} /
 * {@code InvalidTokenRejectionPropertyTest}: because {@code jqwik-spring} is intentionally NOT on
 * the classpath, we cannot use {@code @WebMvcTest}/{@code @Autowired} inside a {@code @Property}.
 * Instead a Spring web context is built <b>programmatically</b> once in a {@link BeforeContainer}
 * hook. It imports the <b>real</b> {@link SecurityConfig} (so {@code @EnableMethodSecurity} +
 * {@code @PreAuthorize('Admin')} are actually enforced), Spring Boot's {@link SecurityAutoConfiguration}
 * and {@link OAuth2ResourceServerAutoConfiguration}, the <b>real</b> {@link ItemController} wired to
 * a {@link FakeItemService}, and a {@link WriteAuthSliceTestConfig} supplying an offline mock
 * {@link JwtDecoder} (so no OIDC discovery / JWKS fetch at startup), a {@link JwtAuthenticationConverter},
 * and a mirrored {@link CorsConfigurationSource}. {@link MockMvc} is built with
 * {@code apply(springSecurity())} so the security filter chain participates in every simulated
 * request.
 */
class WriteAuthorizationPropertyTest {

    /**
     * The single authority that unlocks the write endpoints. {@code hasRole('Admin')} in the
     * controller is sugar for checking this exact authority string (Spring prepends {@code ROLE_}),
     * and the {@code RolesClaimConverter} produces authorities with the same scheme, so this is the
     * value the property pivots on.
     */
    private static final String ADMIN_AUTHORITY = "ROLE_Admin";

    /**
     * The programmatically-built Spring web application context (stand-in for {@code @WebMvcTest}).
     * Held statically because jqwik {@link BeforeContainer}/{@link AfterContainer} hooks are static
     * and the same context (and its {@link MockMvc}) is reused across every generated case.
     */
    private static AnnotationConfigWebApplicationContext context;

    /**
     * The {@link MockMvc} used to issue simulated {@code POST} requests through the real security
     * filter chain (and thus through {@code @PreAuthorize}). Built once from {@link #context}.
     */
    private static MockMvc mockMvc;

    /**
     * The single {@link FakeItemService} bean the real {@link ItemController} is wired to. Held
     * statically so each generated case can snapshot its {@link FakeItemService#count()} before and
     * after the write. It is the in-memory stand-in for {@code repository.count()} that makes the
     * no-mutation-on-403 oracle observable without a database.
     */
    private static FakeItemService fakeService;

    // ---------------------------------------------------------------------------------------------
    // Container lifecycle: build the offline web slice + MockMvc once for all generated cases.
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds the Spring web context and {@link MockMvc} a single time before any property in this
     * container runs. This replaces what {@code @WebMvcTest} + {@code @Autowired} would do, but works
     * without the {@code jqwik-spring} bridge. The {@link FakeItemService} instance the container
     * created is captured into {@link #fakeService} so the property body can read its counter.
     */
    @BeforeContainer
    static void startContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebSliceConfig.class);
        context.refresh();

        // Capture the container-managed fake service so the property can snapshot its counter. This
        // is the SAME instance the real ItemController calls, which is what ties create() to count().
        fakeService = context.getBean(FakeItemService.class);

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                // Register the Spring Security filter chain into the MockMvc pipeline so the real
                // SecurityConfig (its oauth2ResourceServer wiring and @EnableMethodSecurity) is active.
                .apply(springSecurity())
                .build();
    }

    /**
     * Closes the Spring context and clears static state after all properties in this container run,
     * preventing context/bean leakage between test classes.
     */
    @AfterContainer
    static void stopContext() {
        if (context != null) {
            context.close();
            context = null;
            mockMvc = null;
            fakeService = null;
        }
    }

    /**
     * Resets the fake service's counter to zero before each generated try. This keeps the counts
     * small and independent per case; the property asserts a <i>relative</i> change (before vs
     * after) rather than an absolute value, so a reset is not strictly required, but it keeps the
     * oracle easy to reason about and failure output readable.
     */
    @BeforeTry
    void resetCounter() {
        if (fakeService != null) {
            fakeService.reset();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The property.
    // ---------------------------------------------------------------------------------------------

    /**
     * <b>Property 4.</b> For any generated set of role strings mapped to {@code ROLE_*} authorities,
     * a {@code POST /items} performed as a caller holding those authorities:
     * <ul>
     *   <li>if the set contains {@code ROLE_Admin} &rarr; returns <b>201</b> and the item count
     *       increases by exactly one (R8.2, mutation applied);</li>
     *   <li>otherwise &rarr; returns <b>403</b> and the item count is <b>unchanged</b> (R8.3, no
     *       mutation, because {@code @PreAuthorize} blocks the body before {@code create(..)} runs).</li>
     * </ul>
     *
     * <p>The generator biases roughly half of the generated sets to include {@code "Admin"} so both
     * branches are exercised across the 100 tries (see {@link #authoritySets()}).
     *
     * @param roles a jqwik-generated set of raw role strings (subsets of a known pool plus random
     *              strings); each maps to a {@code ROLE_<raw>} {@link SimpleGrantedAuthority}
     * @throws Exception if the simulated request dispatch fails
     */
    @Property(tries = 100)
    void writeEndpointsRequireAdmin(@ForAll("authoritySets") Set<String> roles) throws Exception {
        // Map the raw role strings to ROLE_-prefixed authorities exactly as the production
        // RolesClaimConverter would (ROLE_ + rawValue, case preserved).
        List<GrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .collect(Collectors.toList());

        // Whether this caller should be allowed to write is decided purely by the presence of
        // ROLE_Admin among the granted authorities (mirrors hasRole('Admin')).
        boolean isAdmin = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);

        // Snapshot the in-memory count BEFORE the write. This is the no-mutation oracle: it stands
        // in for repository.count() from the design's Property 4.
        int before = fakeService.count();

        MockHttpServletResponse response = mockMvc.perform(post("/items")
                        // Simulate an already-authenticated caller carrying exactly these authorities.
                        // The jwt() post-processor injects a JWT principal so @PreAuthorize can evaluate
                        // hasRole('Admin') against the generated authority set, without token decoding.
                        .with(jwt().authorities(authorities))
                        // A valid create body: name is @NotBlank, so this passes bean validation and
                        // the only thing that can stop the write is the authorization gate.
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andReturn().getResponse();

        // Snapshot the count AFTER the write to compare against the before-snapshot.
        int after = fakeService.count();

        if (isAdmin) {
            // (R8.2) An Admin caller is permitted: the real controller returns 201 Created and the
            // service.create(..) call bumped the counter by exactly one (mutation applied).
            assertThat(response.getStatus())
                    .as("Admin write should succeed with 200/201 for roles=%s", roles)
                    .isIn(200, 201);
            assertThat(after)
                    .as("Admin write should increase the item count by one for roles=%s", roles)
                    .isEqualTo(before + 1);
        } else {
            // (R8.3) A non-Admin caller is forbidden: method security returns 403 BEFORE the body
            // runs, so create(..) is never invoked and the counter is unchanged (no mutation).
            assertThat(response.getStatus())
                    .as("Non-Admin write should be forbidden (403) for roles=%s", roles)
                    .isEqualTo(403);
            assertThat(after)
                    .as("Non-Admin write must NOT mutate the item count for roles=%s", roles)
                    .isEqualTo(before);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Generator.
    // ---------------------------------------------------------------------------------------------

    /**
     * Generates arbitrary <b>sets</b> of raw role strings to be turned into {@code ROLE_*}
     * authorities. Each generated set is drawn from a pool of realistic role names plus random
     * strings so the property covers both meaningful roles ({@code Admin}, {@code Viewer},
     * {@code Editor}, {@code Guest}) and arbitrary/unknown authorities.
     *
     * <p><b>Branch coverage.</b> The property must exercise BOTH the Admin path (expect 201 +
     * count+1) and the non-Admin path (expect 403 + count unchanged) within the 100 tries. If
     * {@code "Admin"} were merely one element among many in an unbiased pool, sets containing it
     * would be relatively rare. To guarantee a healthy mix, the generator is built as a
     * {@code oneOf} of two equally-weighted branches:
     * <ul>
     *   <li>an <b>Admin-including</b> branch: an arbitrary base set with {@code "Admin"} explicitly
     *       added, so it always contains Admin;</li>
     *   <li>a <b>non-Admin</b> branch: an arbitrary base set filtered to remove any element equal to
     *       {@code "Admin"} (including any randomly generated {@code "Admin"} string), so it never
     *       contains Admin.</li>
     * </ul>
     * This yields roughly a 50/50 split across tries while still varying the surrounding roles.
     *
     * @return an arbitrary producing sets of raw role strings, biased so both the Admin and
     *         non-Admin branches occur frequently across the 100 tries
     */
    @Provide
    Arbitrary<Set<String>> authoritySets() {
        // A pool of well-known role names the application recognizes, plus a source of arbitrary
        // "random" role strings so unknown authorities are also exercised.
        Arbitrary<String> knownRoles = Arbitraries.of("Admin", "Viewer", "Editor", "Guest");
        Arbitrary<String> randomRoles = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
                .ofMinLength(1)
                .ofMaxLength(10);
        // Each element of a generated set is either a known role or a random string.
        Arbitrary<String> anyRole = Arbitraries.oneOf(knownRoles, randomRoles);

        // A base set of 0..4 arbitrary roles. Sets (not lists) so duplicates collapse, matching the
        // "distinct authorities" reality of the converter.
        Arbitrary<Set<String>> baseSet = anyRole.set().ofMinSize(0).ofMaxSize(4);

        // Branch 1: guarantee Admin is present by adding it to the base set.
        Arbitrary<Set<String>> withAdmin = baseSet.map(s -> {
            Set<String> withAdminSet = new java.util.HashSet<>(s);
            withAdminSet.add("Admin");
            return withAdminSet;
        });

        // Branch 2: guarantee Admin is absent by removing any element equal to "Admin".
        Arbitrary<Set<String>> withoutAdmin = baseSet.map(s -> {
            Set<String> withoutAdminSet = new java.util.HashSet<>(s);
            withoutAdminSet.remove("Admin");
            return withoutAdminSet;
        });

        // Equal-weight choice between the two branches -> ~50/50 Admin vs non-Admin across tries.
        return Arbitraries.oneOf(withAdmin, withoutAdmin);
    }

    // ---------------------------------------------------------------------------------------------
    // Fake service: in-memory stand-in for ItemService whose create(..) bumps a counter.
    // ---------------------------------------------------------------------------------------------

    /**
     * An in-memory fake of {@link ItemService} used as the no-mutation oracle. It extends the real
     * {@link ItemService} so it satisfies the real {@link ItemController}'s constructor dependency
     * (the controller takes an {@code ItemService}), but overrides its behavior to avoid any JPA /
     * database access:
     * <ul>
     *   <li>{@link #create(CreateItemRequest)} increments an {@link AtomicInteger} and returns a
     *       synthetic {@link ItemDto}. This is the only mutation path a {@code POST} exercises, so a
     *       successful (Admin) write bumps the counter by one, and a forbidden (non-Admin) write
     *       &mdash; blocked by {@code @PreAuthorize} before the controller body &mdash; never calls
     *       this method, leaving the counter unchanged.</li>
     *   <li>{@link #findAll()} returns an empty list (unused by the write property, overridden only
     *       so no repository is touched).</li>
     *   <li>{@link #count()} exposes the current counter value for the before/after snapshots,
     *       standing in for {@code repository.count()} from the design's Property 4.</li>
     * </ul>
     *
     * <p>Because {@link ItemService} has no no-arg constructor (it requires an {@link ItemRepository}),
     * this subclass passes {@code null} to {@code super(null)}: the superclass merely stores the
     * reference and never uses it, since every method that would touch the repository is overridden
     * here. This keeps the slice completely free of JPA/PostgreSQL wiring.
     */
    static class FakeItemService extends ItemService {

        /**
         * In-memory item count. {@link #create(CreateItemRequest)} increments it; {@link #count()}
         * reads it. This is the observable that replaces {@code repository.count()} for the
         * no-mutation-on-403 oracle (R8.3).
         */
        private final AtomicInteger counter = new AtomicInteger(0);

        /**
         * Constructs the fake, passing {@code null} to the superclass repository field. That field
         * is never dereferenced because all repository-touching methods are overridden below, so no
         * database or JPA infrastructure is needed.
         */
        FakeItemService() {
            super(null);
        }

        /**
         * Overrides the real create to a pure in-memory mutation: bump the counter and return a
         * synthetic DTO. The controller maps a returned DTO to HTTP 201 Created. This method is only
         * reached when {@code @PreAuthorize("hasRole('Admin')")} has already passed, so an increment
         * here corresponds exactly to an authorized (Admin) write.
         *
         * @param request the validated create request (its {@code name} is non-blank)
         * @return a synthetic {@link ItemDto} carrying the new in-memory id and the request's name
         */
        @Override
        public ItemDto create(CreateItemRequest request) {
            int newId = counter.incrementAndGet();
            // The created_by value is irrelevant to this authorization property; a fixed marker keeps
            // the DTO well-formed without reaching into the security context.
            return new ItemDto((long) newId, request.name(), request.description(), "test-subject");
        }

        /**
         * Overrides read to return nothing; the write property never lists items, and this keeps the
         * fake free of any repository access.
         *
         * @return an empty list
         */
        @Override
        public List<ItemDto> findAll() {
            return new ArrayList<>();
        }

        /**
         * The no-mutation oracle accessor: the current in-memory item count. Snapshotting this before
         * and after a {@code POST} lets the property assert {@code +1} on an authorized write and an
         * unchanged value on a forbidden (403) write, mirroring the design's {@code repository.count()}.
         *
         * @return the number of items "created" so far in this fake
         */
        int count() {
            return counter.get();
        }

        /**
         * Resets the counter to zero (used by {@link #resetCounter()} before each generated try).
         */
        void reset() {
            counter.set(0);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Programmatic context configuration (stand-in for @WebMvcTest, without jqwik-spring).
    // ---------------------------------------------------------------------------------------------

    /**
     * Root configuration for the programmatically-built web slice. It assembles exactly the pieces
     * needed to route an authenticated {@code POST} through the production authorization machinery
     * and no more:
     * <ul>
     *   <li>{@code @EnableWebMvc} &mdash; Spring MVC infrastructure so {@code /items} resolves
     *       and request bodies are bound/validated;</li>
     *   <li>{@code @Import(SecurityConfig.class)} &mdash; the <b>real</b> production filter chain,
     *       critically annotated {@code @EnableMethodSecurity} so the controller's
     *       {@code @PreAuthorize("hasRole('Admin')")} is enforced;</li>
     *   <li>{@code @Import} of {@link SecurityAutoConfiguration} and
     *       {@link OAuth2ResourceServerAutoConfiguration} &mdash; so the security filter is
     *       registered exactly as in production;</li>
     *   <li>{@code @Import(ItemController.class)} &mdash; the <b>real</b> package-private controller
     *       (reachable because this test lives in the same package), so the actual write mapping and
     *       its authorization annotation are the code under test;</li>
     *   <li>{@code @Import(WriteAuthSliceTestConfig.class)} &mdash; the offline stub {@link JwtDecoder},
     *       the {@link JwtAuthenticationConverter}, the mirrored {@link CorsConfigurationSource}, and
     *       the {@link FakeItemService} the controller is wired to.</li>
     * </ul>
     */
    @Configuration
    @EnableWebMvc
    @Import({
            SecurityConfig.class,
            SecurityAutoConfiguration.class,
            OAuth2ResourceServerAutoConfiguration.class,
            ItemController.class,
            WriteAuthSliceTestConfig.class
    })
    static class WebSliceConfig {
    }

    /**
     * Test-only bean wiring that satisfies the dependencies of {@link SecurityConfig#filterChain}
     * and the real {@link ItemController} with <b>no</b> network and <b>no</b> database access, while
     * running the <b>real</b> authorization path (method security + {@code @PreAuthorize}).
     *
     * <ul>
     *   <li>{@link #itemService()} &mdash; the {@link FakeItemService} the controller calls; its
     *       {@code create(..)} bumps an in-memory counter so the count oracle works with no JPA.</li>
     *   <li>{@link #jwtDecoder()} &mdash; a Mockito mock so context startup performs no OIDC discovery
     *       / JWKS fetch. It is never invoked because the {@code jwt()} post-processor supplies an
     *       already-authenticated principal, bypassing token decoding.</li>
     *   <li>{@link #jwtAuthenticationConverter()} &mdash; a default converter to satisfy the
     *       {@code oauth2ResourceServer().jwt()} wiring.</li>
     *   <li>{@link #corsConfigurationSource()} &mdash; a {@link CorsConfigurationSource} mirroring the
     *       production single-origin policy, satisfying {@link SecurityConfig}'s by-type injection
     *       without importing {@code JwtConfig} (which would trigger a network OIDC discovery).</li>
     * </ul>
     */
    @TestConfigurationMarker
    static class WriteAuthSliceTestConfig {

        /**
         * Provides the {@link FakeItemService} bean the real {@link ItemController} is constructor-
         * injected with. Because it is a subclass of {@link ItemService}, it satisfies the
         * controller's {@code ItemService} dependency by type, while its overridden {@code create(..)}
         * gives the test a controllable, database-free mutation counter.
         *
         * @return the in-memory fake item service
         */
        @Bean
        FakeItemService itemService() {
            return new FakeItemService();
        }

        /**
         * Provides a mock {@link JwtDecoder} so the OAuth2 resource-server configuration in
         * {@link SecurityConfig} can be assembled entirely offline (no issuer discovery, no JWKS
         * fetch). It is never called: the {@code jwt()} post-processor injects a pre-authenticated
         * principal, so the decoder is bypassed.
         *
         * @return a Mockito mock {@link JwtDecoder} that performs no network I/O
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return org.mockito.Mockito.mock(JwtDecoder.class);
        }

        /**
         * Provides the JWT authentication converter bean the imported {@link SecurityConfig}
         * requires. A default {@link JwtAuthenticationConverter} suffices because authorities in this
         * test are supplied directly by the {@code jwt().authorities(..)} post-processor rather than
         * derived from a decoded token.
         *
         * @return a default JWT-to-authentication converter to satisfy the filter-chain wiring
         */
        @Bean
        Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
            return new JwtAuthenticationConverter();
        }

        /**
         * A {@link CorsConfigurationSource} mirroring {@link SecurityConfig}'s expected single-origin
         * policy. The write requests here are same-origin {@code POST}s (not preflights), so CORS is
         * not the subject under test, but {@link SecurityConfig} injects a CORS source by type and
         * this bean satisfies that dependency without importing {@code JwtConfig} (which would trigger
         * a network OIDC discovery). Marked {@link Primary} to disambiguate against the
         * {@code mvcHandlerMappingIntrospector} that {@code @EnableWebMvc} also contributes as a
         * {@link CorsConfigurationSource}.
         *
         * @return a single-origin CORS policy identical to production
         */
        @Bean
        @Primary
        CorsConfigurationSource corsConfigurationSource() {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(List.of("http://localhost:4200"));
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            config.setAllowCredentials(true);

            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", config);
            return source;
        }
    }

    /**
     * A local alias for {@link Configuration} used to annotate {@link WriteAuthSliceTestConfig}. We
     * avoid {@code @TestConfiguration} (whose auto-registration semantics target Spring Boot test
     * slices, not a hand-built {@link AnnotationConfigWebApplicationContext}) and simply treat the
     * nested class as a plain {@code @Configuration} to the container.
     */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
    @Configuration
    @interface TestConfigurationMarker {
    }
}
