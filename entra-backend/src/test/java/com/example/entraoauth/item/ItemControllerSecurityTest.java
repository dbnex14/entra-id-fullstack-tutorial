package com.example.entraoauth.item;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.example.entraoauth.security.SecurityConfig;

/**
 * Example-based security-slice tests for {@link ItemController}, matching the design's
 * <em>Testing Strategy &gt; Automated tests &gt; Backend security slice</em> section.
 *
 * <p><strong>What this test proves.</strong> It exercises the concrete role/status matrix the
 * design mandates for the two representative endpoints of the role-protected REST surface (R8),
 * without minting real Entra tokens and without any network access:
 * <ul>
 *   <li><strong>Viewer</strong> ({@code ROLE_Viewer}) &rarr; {@code GET /items} returns
 *       <strong>200</strong> (reads are open to Viewer and Admin, R8.1); {@code POST /items}
 *       returns <strong>403</strong> (writes are Admin-only, and method security blocks the
 *       forbidden write before the controller body runs, R8.2/R8.3).</li>
 *   <li><strong>Admin</strong> ({@code ROLE_Admin}) &rarr; {@code GET /items} returns
 *       <strong>200</strong> (R8.1); {@code POST /items} returns <strong>201</strong>
 *       (R8.2).</li>
 *   <li><strong>Anonymous</strong> (no bearer token) &rarr; <strong>401</strong> on both the read
 *       and the write endpoint, because every non-permitted path in {@link SecurityConfig}
 *       requires an authenticated request and the resource-server entry point challenges an
 *       unauthenticated caller with 401 before dispatch (R8.4/R8.5).</li>
 * </ul>
 *
 * <p><strong>Why the wiring below is shaped the way it is (this is the tricky part).</strong>
 * {@code @WebMvcTest} loads only the web slice: the target controller, MVC infrastructure, and
 * (importantly) the Spring Security auto-configuration &mdash; but it does <em>not</em> component-scan
 * arbitrary {@code @Configuration} classes such as the application's {@code JwtConfig}. Two
 * consequences drive this test's setup:
 * <ol>
 *   <li><strong>Method security must be active.</strong> The whole point is to assert 403-vs-201 on
 *       writes, and that distinction only exists if {@code @PreAuthorize} is actually enforced.
 *       That enforcement is switched on by {@code @EnableMethodSecurity} on {@link SecurityConfig},
 *       and the real per-path rules (permit OPTIONS/health, authenticate everything else,
 *       {@code oauth2ResourceServer().jwt()}) also live in {@link SecurityConfig}. So we
 *       {@link Import} {@link SecurityConfig} to get the genuine filter chain plus method security,
 *       rather than relying on {@code @WebMvcTest}'s permissive default security.</li>
 *   <li><strong>No real issuer discovery / no network.</strong> {@link SecurityConfig#filterChain}
 *       depends on two beans that the un-scanned {@code JwtConfig} would normally provide: a
 *       {@code Converter<Jwt, AbstractAuthenticationToken>} (the JWT authentication converter) and,
 *       via {@code oauth2ResourceServer().jwt()}, a {@link JwtDecoder}. The production
 *       {@code JwtDecoder} performs OIDC discovery against Entra at bean-creation time (a network
 *       call). To keep this test hermetic and offline we supply <em>mock/stub</em> versions of both
 *       beans from the {@link SecuritySliceTestConfig} {@link TestConfiguration} below. Critically,
 *       we do <strong>not</strong> weaken production security to do this &mdash; the real
 *       {@link SecurityConfig} filter chain (including {@code @EnableMethodSecurity}) is what runs.
 *       We also never actually decode a token: authentication is simulated with
 *       {@code spring-security-test}'s {@code jwt()} request post-processor, so the mock
 *       {@link JwtDecoder} is present only to satisfy the resource-server wiring and is never
 *       invoked.</li>
 * </ol>
 *
 * <p><strong>How callers are simulated.</strong> The {@link MockMvc} instance is built with
 * {@code apply(springSecurity())} so the Security filter chain participates in each simulated
 * request. Each authenticated case attaches the {@code jwt()} post-processor with the exact
 * {@code ROLE_*} authorities the {@code RolesClaimConverter} would have produced from a real
 * token's {@code roles} claim (e.g. {@code ROLE_Viewer}, {@code ROLE_Admin}). Because the
 * authorities are supplied directly, no signing key, no issuer, and no audience are needed &mdash;
 * this isolates the <em>authorization</em> behavior (role &rarr; status) from token
 * <em>validation</em> (which the invalid-token property test covers separately). The anonymous
 * cases simply omit the {@code jwt()} post-processor, leaving the request unauthenticated so the
 * chain answers 401.
 *
 * <p>Because {@link ItemController} is package-private, this test deliberately lives in the same
 * package ({@code com.example.entraoauth.item}) so it can reference the controller type in
 * {@code @WebMvcTest(ItemController.class)}.
 */
@WebMvcTest(ItemController.class)
@Import({SecurityConfig.class, ItemControllerSecurityTest.SecuritySliceTestConfig.class})
class ItemControllerSecurityTest {

    /**
     * The web application context assembled by {@code @WebMvcTest} (controller + MVC infra +
     * imported {@link SecurityConfig} + the test config beans). We build {@link MockMvc} from this
     * context in {@link #setUp()} so we can explicitly {@code apply(springSecurity())}, which wires
     * the Spring Security filter chain into the mock request pipeline.
     */
    @Autowired
    private WebApplicationContext context;

    /**
     * The {@link MockMvc} entry point used to issue simulated HTTP requests against the controller
     * with the real security filter chain applied. Rebuilt fresh for each test in {@link #setUp()}.
     */
    private MockMvc mockMvc;

    /**
     * Mock of the application service the controller delegates to. Using {@code @MockBean} replaces
     * the real {@link ItemService} bean in the slice context with a Mockito mock (Spring Boot 3.3
     * supports {@code @MockBean}; any deprecation warning is harmless here). Stubbing it means the
     * test focuses purely on the HTTP + security behavior and never touches JPA, the database, or
     * the security context's JWT subject.
     */
    @MockBean
    private ItemService itemService;

    /**
     * Rebuilds {@link MockMvc} before each test, applying {@code springSecurity()} so the imported
     * {@link SecurityConfig} filter chain (authentication + {@code @EnableMethodSecurity}) is active
     * for every simulated request. It also stubs the two {@link ItemService} methods the controller
     * calls on the success paths:
     * <ul>
     *   <li>{@code findAll()} returns a small, fixed list so the {@code GET} handler can serialize a
     *       200 body for authorized readers;</li>
     *   <li>{@code create(...)} returns a representative {@link ItemDto} so the {@code POST} handler
     *       can return 201 for an Admin caller.</li>
     * </ul>
     * On the 403/401 paths these stubs are simply never reached, which is exactly the behavior we
     * assert (a forbidden or unauthenticated write must not invoke the service at all).
     */
    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                // Register the Spring Security filter chain into the MockMvc request pipeline so the
                // imported SecurityConfig actually authenticates/authorizes each simulated request.
                .apply(springSecurity())
                .build();

        // Stub the read path: an authorized GET returns this list and yields 200.
        when(itemService.findAll())
                .thenReturn(List.of(new ItemDto(UUID.randomUUID(), "existing", "seeded item", "hardware", "subject-oid")));

        // Stub the write path: an Admin POST returns this DTO, which the controller wraps in 201.
        when(itemService.create(any(CreateItemRequest.class)))
                .thenReturn(new ItemDto(UUID.randomUUID(), "created", "created via POST", "software", "admin-oid"));

        // Stub the per-item history read path: an authorized GET returns this list and yields 200.
        // (The 403/401 paths never reach this stub, which is exactly what we assert.) The lookup key
        // is now the item's opaque public id (UUID), so we match any UUID here.
        when(itemService.findHistoryForItem(any(UUID.class)))
                .thenReturn(List.of(new ItemHistoryDto(
                        UUID.randomUUID(), UUID.randomUUID(), ItemHistory.ChangeType.CREATE,
                        "subject-oid", "Ada Admin", "Created item 'existing'",
                        java.time.OffsetDateTime.parse("2026-01-15T10:22:31.512Z"))));
    }

    // ---------------------------------------------------------------------------------------------
    // Viewer role: read is allowed (200), write is forbidden (403).
    // ---------------------------------------------------------------------------------------------

    /**
     * A caller holding {@code ROLE_Viewer} may read: {@code GET /items} is permitted for both
     * Viewer and Admin (R8.1), so the request reaches the handler and returns 200.
     */
    @Test
    void viewerCanReadItems() throws Exception {
        mockMvc.perform(get("/items")
                        // Simulate an authenticated caller whose token's `roles` claim mapped to
                        // ROLE_Viewer (exactly what RolesClaimConverter would produce for role "Viewer").
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Viewer"))))
                .andExpect(status().isOk());
    }

    /**
     * A caller holding only {@code ROLE_Viewer} may <strong>not</strong> write: {@code POST /items}
     * is guarded by {@code @PreAuthorize("hasRole('Admin')")}. Method security rejects the request
     * with 403 <em>before</em> the controller body runs, so no item is created (R8.2, R8.3).
     */
    @Test
    void viewerCannotCreateItem() throws Exception {
        mockMvc.perform(post("/items")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Viewer")))
                        .contentType(MediaType.APPLICATION_JSON)
                        // A structurally valid body (non-blank name) so the 403 is unambiguously an
                        // authorization decision, not a 400 validation failure.
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------------------------------
    // Admin role: read is allowed (200), write is created (201).
    // ---------------------------------------------------------------------------------------------

    /**
     * A caller holding {@code ROLE_Admin} may read: reads are open to Admin as well as Viewer
     * (R8.1), so {@code GET /items} returns 200.
     */
    @Test
    void adminCanReadItems() throws Exception {
        mockMvc.perform(get("/items")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Admin"))))
                .andExpect(status().isOk());
    }

    /**
     * A caller holding {@code ROLE_Admin} may write: {@code POST /items} satisfies
     * {@code hasRole('Admin')}, so the handler runs, delegates to the (stubbed) service, and returns
     * 201 Created (R8.2).
     */
    @Test
    void adminCanCreateItem() throws Exception {
        mockMvc.perform(post("/items")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------------------------------------
    // Anonymous (no token): 401 on any protected endpoint.
    // ---------------------------------------------------------------------------------------------

    /**
     * An unauthenticated caller (no {@code jwt()} post-processor, so no bearer identity) is stopped
     * by the filter chain on the read endpoint: {@code anyRequest().authenticated()} is not
     * satisfied, and the resource-server entry point answers 401 with a {@code WWW-Authenticate}
     * challenge before dispatch (R8.4, R8.5).
     */
    @Test
    void anonymousReadIsUnauthorized() throws Exception {
        mockMvc.perform(get("/items"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * An unauthenticated caller is likewise rejected with 401 on the write endpoint. Authentication
     * is checked before authorization, so a missing token yields 401 (not 403) and the controller
     * body never runs (R8.4, R8.5).
     */
    @Test
    void anonymousCreateIsUnauthorized() throws Exception {
        mockMvc.perform(post("/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------------------------------
    // Per-item change history (GET /items/{id}/history): reads are open to Viewer AND Admin (like
    // the item list), and unauthenticated callers get 401. History is observational: both roles may
    // read it, and it is not a write endpoint, so there is no Admin-only variant to assert here.
    // ---------------------------------------------------------------------------------------------

    /**
     * A caller holding {@code ROLE_Viewer} may read an item's history: the endpoint is guarded by
     * {@code @PreAuthorize("hasAnyRole('Viewer','Admin')")}, so it returns 200 for a Viewer.
     */
    @Test
    void viewerCanReadItemHistory() throws Exception {
        mockMvc.perform(get("/items/11111111-1111-1111-1111-111111111111/history")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Viewer"))))
                .andExpect(status().isOk());
    }

    /**
     * A caller holding {@code ROLE_Admin} may likewise read an item's history (200).
     */
    @Test
    void adminCanReadItemHistory() throws Exception {
        mockMvc.perform(get("/items/11111111-1111-1111-1111-111111111111/history")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Admin"))))
                .andExpect(status().isOk());
    }

    /**
     * An unauthenticated caller is rejected with 401 on the history read endpoint, just like the
     * item list: {@code anyRequest().authenticated()} is not satisfied, so the resource-server entry
     * point challenges before dispatch (R8.4, R8.5).
     */
    @Test
    void anonymousReadItemHistoryIsUnauthorized() throws Exception {
        mockMvc.perform(get("/items/11111111-1111-1111-1111-111111111111/history"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Minimal test-only bean wiring that satisfies {@link SecurityConfig#filterChain}'s dependencies
     * without any network access.
     *
     * <p>{@code @WebMvcTest} does not component-scan the application's {@code JwtConfig}, yet the
     * imported {@link SecurityConfig} filter chain requires:
     * <ul>
     *   <li>a {@code Converter<Jwt, AbstractAuthenticationToken>} &mdash; supplied here as a plain
     *       {@link JwtAuthenticationConverter}. In these tests the {@code jwt()} post-processor
     *       injects authorities directly, so this converter is only needed to satisfy the wiring and
     *       is not exercised for role derivation;</li>
     *   <li>a {@link JwtDecoder} for {@code oauth2ResourceServer().jwt()} &mdash; supplied here as a
     *       Mockito mock so bean creation performs <strong>no</strong> OIDC discovery and no network
     *       I/O. It is never invoked because we never present a real encoded token to decode.</li>
     * </ul>
     * The CORS {@code CorsConfigurationSource} bean that {@link SecurityConfig} also needs is
     * provided by {@code @WebMvcTest}'s Spring Security auto-configuration default, so it is not
     * redefined here.
     */
    @TestConfiguration
    static class SecuritySliceTestConfig {

        /**
         * Provides the JWT authentication converter bean the imported {@link SecurityConfig}
         * requires. A default {@link JwtAuthenticationConverter} is sufficient for the slice because
         * authorities in these tests come from the {@code jwt()} post-processor, not from decoding a
         * token.
         *
         * @return a default JWT-to-authentication converter to satisfy the filter-chain wiring
         */
        @Bean
        Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
            return new JwtAuthenticationConverter();
        }

        /**
         * Provides a mock {@link JwtDecoder} so the OAuth2 resource-server configuration in
         * {@link SecurityConfig} can be assembled offline. Using a mock (via Mockito) guarantees no
         * real issuer discovery or JWKS fetch occurs during the test context startup; the decoder is
         * never called because authentication is simulated with the {@code jwt()} post-processor.
         *
         * @return a Mockito mock {@link JwtDecoder} that performs no network I/O
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return org.mockito.Mockito.mock(JwtDecoder.class);
        }
    }
}
