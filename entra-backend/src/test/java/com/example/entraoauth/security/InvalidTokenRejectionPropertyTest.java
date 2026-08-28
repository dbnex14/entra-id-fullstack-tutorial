package com.example.entraoauth.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

/**
 * Property-based tests proving the Resource_Server rejects <b>invalid</b> Entra ID access tokens
 * with HTTP 401 and an {@code invalid_token} challenge, driven end-to-end through the real security
 * filter chain via {@link MockMvc}.
 *
 * <p><b>Feature:</b> entra-oauth-fullstack &mdash; <b>Property 2: Invalid tokens are rejected with
 * 401.</b>
 *
 * <p><b>Validates: Requirements 3.1, 6.4, 6.5, 6.7, 6.8</b>
 *
 * <h2>What this property says (the design's Correctness Property 2)</h2>
 * A token is minted that is <i>valid in every dimension except one</i>, and exactly one of the
 * following mutations is applied per generated case:
 * <ul>
 *   <li><b>expired</b> &mdash; {@code exp} is set earlier than {@code now - 60s}, i.e. beyond the
 *       clock-skew window enforced by {@link JwtTimestampValidator} (R3.1).</li>
 *   <li><b>bad audience</b> &mdash; {@code aud} is a value that is neither the Client_ID nor its
 *       {@code api://} form, so {@link AudienceValidator} rejects it (R6.4).</li>
 *   <li><b>bad issuer</b> &mdash; {@code iss} differs from the configured tenant issuer, so
 *       {@link JwtValidators#createDefaultWithIssuer(String)} rejects it (R6.5).</li>
 *   <li><b>bad signature</b> &mdash; the JWS is signed with a <i>second</i>, unrelated RSA private
 *       key whose public key is NOT the one the decoder trusts, so signature verification fails
 *       (R6.7).</li>
 * </ul>
 * In every case the assertion is the same: the response status is <b>401</b> and the
 * {@code WWW-Authenticate} header contains {@code invalid_token} (R6.7, R6.8). Each {@code @Property}
 * runs a minimum of 100 generated cases ({@code tries = 100}).
 *
 * <h2>How the tokens are made (no network, ever)</h2>
 * <p>The production {@link JwtConfig#jwtDecoder(List)} performs live OIDC discovery against Entra to
 * load the JWKS &mdash; unusable in a hermetic unit test. Instead this test mints and verifies tokens
 * entirely locally:
 * <ol>
 *   <li>A {@link KeyPair primary RSA key pair} is generated in-process. Tokens are signed with its
 *       private key (Nimbus {@link RSASSASigner}), and the decoder under test is built from its
 *       <i>public</i> key via {@link NimbusJwtDecoder#withPublicKey(RSAPublicKey)}. No JWKS/network
 *       fetch occurs &mdash; the trust anchor is the in-memory public key.</li>
 *   <li>A <b>second</b>, unrelated RSA key pair is generated purely to forge a "bad signature" token:
 *       signing with its private key produces a JWS that cannot verify against the primary public
 *       key, so the decoder rejects it (R6.7).</li>
 *   <li>The decoder is given the <b>same validator chain</b> that {@link JwtConfig} builds in
 *       production &mdash; {@code DelegatingOAuth2TokenValidator} of
 *       {@code createDefaultWithIssuer(issuer)} + {@code JwtTimestampValidator(60s)} +
 *       {@code AudienceValidator(accepted audiences)} &mdash; so the expired / bad-aud / bad-iss
 *       mutations are judged by the exact production rules.</li>
 * </ol>
 *
 * <h2>Why the wiring below is shaped the way it is</h2>
 * <p>This mirrors the proven pattern in {@code CorsPropertyTest} / {@code ItemControllerSecurityTest}:
 * because {@code jqwik-spring} is intentionally NOT on the classpath, we cannot use
 * {@code @WebMvcTest}/{@code @Autowired} inside a {@code @Property}. Instead a Spring web context is
 * built <b>programmatically</b> once in a {@link BeforeContainer} hook. It imports the <b>real</b>
 * {@link SecurityConfig} filter chain, Spring Boot's {@link SecurityAutoConfiguration} and
 * {@link OAuth2ResourceServerAutoConfiguration}, a {@link ProbeController} mapping {@code /api/items}
 * so there is a real protected path to hit, and a {@link SliceTestConfig} that supplies the
 * offline test {@link JwtDecoder} (with the mirrored validator chain), a {@link JwtAuthenticationConverter},
 * and a mirrored {@link CorsConfigurationSource}. {@link MockMvc} is built with
 * {@code apply(springSecurity())} so the security filter chain &mdash; and thus the resource-server
 * entry point that emits the 401 + {@code WWW-Authenticate} challenge &mdash; participates in every
 * simulated request.
 */
class InvalidTokenRejectionPropertyTest {

    // ---------------------------------------------------------------------------------------------
    // Identity constants (mirroring the authoritative values from design.md / tasks.md).
    // ---------------------------------------------------------------------------------------------

    /**
     * The trusted issuer the decoder is configured to accept. Any stable https URL works for the
     * local-signing test; we use the real tenant's {@code /v2.0} authority so the constant matches
     * production. A token whose {@code iss} differs from this is rejected (R6.5).
     */
    private static final String TEST_ISSUER =
            "https://login.microsoftonline.com/76325907-a5db-46b1-9d5a-cbcca2e63e66/v2.0";

    /**
     * First accepted audience: the bare Client_ID. A valid token's {@code aud} contains this (R6.4).
     */
    private static final String AUD1 = "4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c";

    /**
     * Second accepted audience: the {@code api://} Application ID URI form of the Client_ID. Either
     * of {@link #AUD1} / {@link #AUD2} is accepted by {@link AudienceValidator} (R6.4).
     */
    private static final String AUD2 = "api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c";

    // ---------------------------------------------------------------------------------------------
    // In-memory crypto material. Generated once per container in startContext().
    // ---------------------------------------------------------------------------------------------

    /**
     * The RSA private key that mints tokens the decoder is expected to TRUST (its public counterpart
     * is the decoder's configured verification key). Used for the expired / bad-aud / bad-iss
     * mutations, where the signature is genuine but some other claim is wrong.
     */
    private static RSAPrivateKey trustedPrivateKey;

    /**
     * A DIFFERENT, unrelated RSA private key used only to forge "bad signature" tokens. Because its
     * public counterpart is NOT the decoder's configured key, tokens signed with it fail signature
     * verification and are rejected with 401 (R6.7).
     */
    private static RSAPrivateKey untrustedPrivateKey;

    /**
     * The programmatically-built Spring web application context (stand-in for {@code @WebMvcTest}).
     * Held statically because jqwik {@link BeforeContainer}/{@link AfterContainer} hooks are static
     * and the same context (and its {@link MockMvc}) is reused across every generated case.
     */
    private static AnnotationConfigWebApplicationContext context;

    /**
     * The {@link MockMvc} used to issue simulated requests through the real security filter chain.
     * Built once from {@link #context}.
     */
    private static MockMvc mockMvc;

    // ---------------------------------------------------------------------------------------------
    // Container lifecycle: generate keys, build the offline decoder + web slice, build MockMvc.
    // ---------------------------------------------------------------------------------------------

    /**
     * Generates the two RSA key pairs and builds the Spring web context + {@link MockMvc} a single
     * time before any property runs. Publishing the trusted public key into
     * {@link SliceTestConfig#configuredPublicKey} lets the {@link Configuration} class build the
     * decoder against the same key material the tokens are minted from, all without any network I/O.
     *
     * @throws Exception if RSA key generation fails
     */
    @BeforeContainer
    static void startContext() throws Exception {
        // 2048-bit RSA is the minimum Nimbus accepts for RS256 signing; ample for a test.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);

        KeyPair trustedPair = generator.generateKeyPair();
        KeyPair untrustedPair = generator.generateKeyPair();

        trustedPrivateKey = (RSAPrivateKey) trustedPair.getPrivate();
        untrustedPrivateKey = (RSAPrivateKey) untrustedPair.getPrivate();

        // The decoder trusts ONLY the primary public key. Hand it to the slice config so the
        // NimbusJwtDecoder bean can be built from it (offline).
        SliceTestConfig.configuredPublicKey = (RSAPublicKey) trustedPair.getPublic();

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebSliceConfig.class);
        context.refresh();

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                // Register the Spring Security filter chain so the real SecurityConfig (its
                // oauth2ResourceServer().jwt() wiring and the 401 entry point) is active.
                .apply(springSecurity())
                .build();
    }

    /**
     * Closes the Spring context and clears static state after all properties in this container run,
     * preventing key material and context leakage between test classes.
     */
    @AfterContainer
    static void stopContext() {
        if (context != null) {
            context.close();
            context = null;
            mockMvc = null;
        }
        trustedPrivateKey = null;
        untrustedPrivateKey = null;
        SliceTestConfig.configuredPublicKey = null;
    }

    // ---------------------------------------------------------------------------------------------
    // The mutation model: one enum value per invalid dimension.
    // ---------------------------------------------------------------------------------------------

    /**
     * The single dimension a generated token is mutated along. Exactly one of these is applied per
     * case; every other dimension of the token stays valid, so the property proves each validator in
     * the chain independently causes a 401.
     */
    enum Mutation {
        /** {@code exp} earlier than {@code now - 60s} (beyond the skew window) -> R3.1. */
        EXPIRED,
        /** {@code aud} not in the accepted set -> R6.4. */
        BAD_AUDIENCE,
        /** {@code iss} differs from the configured issuer -> R6.5. */
        BAD_ISSUER,
        /** Signed with the untrusted key so the signature does not verify -> R6.7. */
        BAD_SIGNATURE
    }

    // ---------------------------------------------------------------------------------------------
    // The property.
    // ---------------------------------------------------------------------------------------------

    /**
     * <b>Property 2.</b> For any token mutated along exactly one invalid dimension, the
     * Resource_Server responds 401 and the {@code WWW-Authenticate} header contains
     * {@code invalid_token}.
     *
     * <p>Alongside the {@link Mutation} choice, the generator varies orthogonal, still-valid token
     * attributes (which accepted audience the baseline would have used, the subject value, and how
     * far the mutation pushes past its threshold). This ensures the rejection guarantee is robust
     * across a spread of otherwise-well-formed tokens rather than a single fixed shape.
     *
     * @param spec a jqwik-generated token specification carrying the mutation and the orthogonal
     *             valid-dimension values
     * @throws Exception if minting/signing the token or dispatching the request fails
     */
    @Property(tries = 100)
    void invalidTokensAreRejectedWith401(@ForAll("invalidTokens") TokenSpec spec) throws Exception {
        String bearer = buildToken(spec);

        mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + bearer))
                // (R6.8) Every invalid-token dimension maps to 401 Unauthorized.
                .andExpect(status().isUnauthorized())
                // (R6.7, R6.8) The bearer challenge advertises error="invalid_token", which is also
                // exactly what the frontend interceptor keys on to decide whether to refresh.
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
    }

    // ---------------------------------------------------------------------------------------------
    // Token minting helper.
    // ---------------------------------------------------------------------------------------------

    /**
     * Mints a signed JWS reflecting the mutation described by {@code spec}. The baseline (pre-mutation)
     * token is valid in every dimension: issuer = {@link #TEST_ISSUER}, audience = one accepted value,
     * {@code exp} well in the future, {@code iat}/{@code nbf} in the past, signed with the trusted key.
     * The chosen mutation then invalidates exactly one dimension.
     *
     * @param spec the generated token specification
     * @return the compact-serialized JWS string to place in the {@code Authorization: Bearer} header
     * @throws JOSEException if signing fails
     */
    private static String buildToken(TokenSpec spec) throws JOSEException {
        Instant now = Instant.now();

        // --- Baseline VALID values (any one of which a mutation may override) ---
        String issuer = TEST_ISSUER;
        String audience = spec.baselineAudience; // one of AUD1 / AUD2 (accepted)
        Instant expiry = now.plus(30, ChronoUnit.MINUTES); // comfortably in the future
        RSAPrivateKey signingKey = trustedPrivateKey; // signature the decoder trusts

        // --- Apply exactly ONE mutation ---
        switch (spec.mutation) {
            case EXPIRED ->
                // Push exp past the 60s clock-skew window. spec.secondsBeyondSkew is >= 61, so
                // (now - that many seconds) is unambiguously beyond `now - 60s`. iat/nbf are set
                // relative to this so the token is internally consistent, just expired (R3.1).
                    expiry = now.minus(spec.secondsBeyondSkew, ChronoUnit.SECONDS);
            case BAD_AUDIENCE ->
                // An audience guaranteed not to be AUD1 or AUD2 (R6.4).
                    audience = spec.wrongAudience;
            case BAD_ISSUER ->
                // An issuer guaranteed not to equal TEST_ISSUER (R6.5).
                    issuer = spec.wrongIssuer;
            case BAD_SIGNATURE ->
                // Sign with the untrusted key; all claims stay valid but verification fails (R6.7).
                    signingKey = untrustedPrivateKey;
        }

        // For the EXPIRED case, anchor iat/nbf a little before exp so timing claims are coherent;
        // otherwise use `now`. This avoids an nbf-in-the-future artifact confounding the exp test.
        Instant issuedAt = spec.mutation == Mutation.EXPIRED
                ? expiry.minus(5, ChronoUnit.MINUTES)
                : now.minus(1, ChronoUnit.MINUTES);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(spec.subject)
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiry))
                // A benign roles claim so the token looks like a real Entra access token; irrelevant
                // to rejection, which happens during validation before authorities matter.
                .claim("roles", List.of("Viewer"))
                .build();

        // RS256 header. A "kid" is included for realism; the local decoder verifies by the single
        // configured public key regardless of kid, so its value does not affect the outcome.
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID("test-key")
                .build();

        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    // ---------------------------------------------------------------------------------------------
    // Generators.
    // ---------------------------------------------------------------------------------------------

    /**
     * Generates {@link TokenSpec} values. Each combines:
     * <ul>
     *   <li>a {@link Mutation} (the one invalid dimension),</li>
     *   <li>a baseline accepted audience ({@link #AUD1}/{@link #AUD2}) &mdash; the value a valid
     *       token would carry, and which non-audience mutations leave intact,</li>
     *   <li>a "wrong" audience string guaranteed not to be accepted (used by {@code BAD_AUDIENCE}),</li>
     *   <li>a "wrong" issuer string guaranteed not to equal {@link #TEST_ISSUER} (used by
     *       {@code BAD_ISSUER}),</li>
     *   <li>a subject value, and</li>
     *   <li>an "expired-by" amount &ge; 61 seconds so the {@code EXPIRED} mutation is unambiguously
     *       beyond the 60s skew (used by {@code EXPIRED}).</li>
     * </ul>
     * Unused fields for a given mutation are simply ignored by {@link #buildToken(TokenSpec)}, which
     * keeps the generator uniform and the specs self-contained.
     */
    @Provide
    Arbitrary<TokenSpec> invalidTokens() {
        Arbitrary<Mutation> mutation = Arbitraries.of(Mutation.values());

        // Baseline accepted audience the valid token would use.
        Arbitrary<String> baselineAudience = Arbitraries.of(AUD1, AUD2);

        // A wrong audience: random UUID-like or api:// strings that can never equal AUD1/AUD2.
        Arbitrary<String> wrongAudience = Arbitraries.oneOf(
                        Arbitraries.create(() -> UUID.randomUUID().toString()),
                        Arbitraries.of(
                                "api://00000000-0000-0000-0000-000000000000",
                                "https://graph.microsoft.com",
                                "some-other-api",
                                "00000000-0000-0000-0000-000000000000"))
                .filter(a -> !a.equals(AUD1) && !a.equals(AUD2));

        // A wrong issuer: a different tenant or an entirely different host, never TEST_ISSUER.
        Arbitrary<String> wrongIssuer = Arbitraries.of(
                        "https://login.microsoftonline.com/00000000-0000-0000-0000-000000000000/v2.0",
                        "https://sts.windows.net/76325907-a5db-46b1-9d5a-cbcca2e63e66/",
                        "https://accounts.google.com",
                        "https://evil.example/issuer")
                .filter(i -> !i.equals(TEST_ISSUER));

        // A subject value: any non-empty alphanumeric-ish string (Entra uses opaque oid/sub values).
        Arbitrary<String> subject = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")
                .ofMinLength(1)
                .ofMaxLength(30);

        // How far past the 60s skew the EXPIRED token is: 61s .. ~7 days. Always > 60 so the token
        // is genuinely expired relative to the validator's allowance (R3.1).
        Arbitrary<Long> secondsBeyondSkew = Arbitraries.longs().between(61L, 604_800L);

        return Combinators.combine(mutation, baselineAudience, wrongAudience, wrongIssuer, subject,
                        secondsBeyondSkew)
                .as(TokenSpec::new);
    }

    /**
     * An immutable specification describing how to mint one (invalid) token. Carries the chosen
     * {@link Mutation} plus every orthogonal value {@link #buildToken(TokenSpec)} might need. Only
     * the fields relevant to the selected mutation actually influence the token; the rest are inert.
     */
    static final class TokenSpec {
        /** The single dimension to invalidate. */
        final Mutation mutation;
        /** An accepted audience the valid baseline would carry (kept intact unless mutated). */
        final String baselineAudience;
        /** A guaranteed-not-accepted audience, used only by {@link Mutation#BAD_AUDIENCE}. */
        final String wrongAudience;
        /** A guaranteed-wrong issuer, used only by {@link Mutation#BAD_ISSUER}. */
        final String wrongIssuer;
        /** The token subject ({@code sub}); always valid, present for realism. */
        final String subject;
        /** Seconds beyond the 60s skew for {@link Mutation#EXPIRED} (always &ge; 61). */
        final long secondsBeyondSkew;

        TokenSpec(Mutation mutation, String baselineAudience, String wrongAudience,
                  String wrongIssuer, String subject, long secondsBeyondSkew) {
            this.mutation = mutation;
            this.baselineAudience = baselineAudience;
            this.wrongAudience = wrongAudience;
            this.wrongIssuer = wrongIssuer;
            this.subject = subject;
            this.secondsBeyondSkew = secondsBeyondSkew;
        }

        @Override
        public String toString() {
            // Readable shrink/failure output naming the mutation under test.
            return "TokenSpec{" + mutation + ", baselineAud=" + baselineAudience + '}';
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Programmatic context configuration (stand-in for @WebMvcTest, without jqwik-spring).
    // ---------------------------------------------------------------------------------------------

    /**
     * Root configuration for the programmatically-built web slice. It assembles exactly the pieces
     * needed to route a bearer request through the production security machinery and no more:
     * <ul>
     *   <li>{@code @EnableWebMvc} &mdash; Spring MVC infrastructure so {@code /api/items} resolves;</li>
     *   <li>{@code @Import(SecurityConfig.class)} &mdash; the <b>real</b> production filter chain,
     *       including {@code oauth2ResourceServer().jwt()} and the default 401 entry point;</li>
     *   <li>{@code @Import} of {@link SecurityAutoConfiguration} and
     *       {@link OAuth2ResourceServerAutoConfiguration} &mdash; so the security filter is registered
     *       exactly as in production;</li>
     *   <li>{@code @Import(SliceTestConfig.class)} &mdash; the offline {@link JwtDecoder} (with the
     *       mirrored validator chain), the {@link JwtAuthenticationConverter}, and the mirrored
     *       {@link CorsConfigurationSource};</li>
     *   <li>{@code @Import(ProbeController.class)} &mdash; a tiny protected controller mapping
     *       {@code /api/items} so there is a real path to hit. The production {@code ItemController}
     *       is package-private in another package and cannot be referenced here; a probe is
     *       equivalent for the 401 path because rejection happens in the filter chain, before any
     *       handler runs.</li>
     * </ul>
     */
    @Configuration
    @EnableWebMvc
    @Import({
            SecurityConfig.class,
            SecurityAutoConfiguration.class,
            OAuth2ResourceServerAutoConfiguration.class,
            SliceTestConfig.class,
            ProbeController.class
    })
    static class WebSliceConfig {
    }

    /**
     * A minimal protected REST controller giving the invalid-token requests a real, mapped path
     * ({@code /api/items}) to target. Its {@code GET} handler is never actually invoked in these
     * tests: an invalid token is rejected by the resource-server filter with 401 before dispatch, so
     * the handler body never runs. The mapping merely needs to exist and be protected.
     */
    @RestController
    @RequestMapping("/api/items")
    static class ProbeController {

        /**
         * Placeholder read mapping so {@code /api/items} is a registered, authenticated path. Never
         * reached by these tests because every request carries an invalid token (401 first).
         *
         * @return an empty list (unused by these rejection tests)
         */
        @GetMapping
        List<String> list() {
            return List.of();
        }
    }

    /**
     * Test-only bean wiring that satisfies {@link SecurityConfig#filterChain}'s dependencies with
     * <b>no</b> network access, while running the <b>real</b> production validator chain against
     * locally-signed tokens.
     */
    @TestConfigurationMarker
    static class SliceTestConfig {

        /**
         * The RSA public key the decoder trusts, published by {@link #startContext()} before the
         * context refreshes. Static because it is shared between the static container hook and this
         * container-managed configuration.
         */
        static RSAPublicKey configuredPublicKey;

        /**
         * Builds the offline {@link JwtDecoder} used by the resource server. It verifies signatures
         * against the single in-memory {@link #configuredPublicKey} (so no JWKS/network fetch), and
         * attaches the <b>same validator chain</b> {@link JwtConfig} builds in production:
         * {@code createDefaultWithIssuer(TEST_ISSUER)} (iss + standard timing, R6.5) +
         * {@code JwtTimestampValidator(60s)} (exp with 60s skew, R3.1) +
         * {@code AudienceValidator(AUD1, AUD2)} (aud, R6.4). This is what makes the expired /
         * bad-aud / bad-iss mutations fail exactly as they would in production; the bad-signature
         * mutation fails earlier, during signature verification against this public key (R6.7).
         *
         * @return a {@link NimbusJwtDecoder} that verifies locally and enforces the production chain
         */
        @Bean
        JwtDecoder jwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(configuredPublicKey).build();

            OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(TEST_ISSUER);
            OAuth2TokenValidator<Jwt> withClockSkew = new JwtTimestampValidator(Duration.ofSeconds(60));
            OAuth2TokenValidator<Jwt> withAudience = new AudienceValidator(List.of(AUD1, AUD2));

            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    withIssuer, withClockSkew, withAudience));
            return decoder;
        }

        /**
         * The JWT-to-authentication converter the imported {@link SecurityConfig} requires. A default
         * {@link JwtAuthenticationConverter} suffices because every request in this test is rejected
         * during validation (401) and never produces an authenticated principal whose authorities
         * matter.
         *
         * @return a default JWT authentication converter to satisfy the filter-chain wiring
         */
        @Bean
        Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
            return new JwtAuthenticationConverter();
        }

        /**
         * A {@link CorsConfigurationSource} mirroring {@link JwtConfig#corsConfigurationSource()}.
         * The invalid-token requests here are simple {@code GET}s (not preflights), so CORS is not
         * the subject under test, but {@link SecurityConfig} injects a CORS source by type and this
         * bean satisfies that dependency without importing {@code JwtConfig} (which would trigger a
         * network OIDC discovery). Marked {@link Primary} to disambiguate against the
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
     * A local alias for {@link Configuration} used to annotate {@link SliceTestConfig}. We avoid
     * {@code @TestConfiguration} (whose auto-registration semantics target Spring Boot test slices,
     * not a hand-built {@link AnnotationConfigWebApplicationContext}) and simply treat the nested
     * class as a plain {@code @Configuration} to the container.
     */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
    @Configuration
    @interface TestConfigurationMarker {
    }
}
