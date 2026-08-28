package com.example.entraoauth.security;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Central wiring for how the Resource_Server <em>decodes and validates</em> an incoming Entra ID
 * Access_Token, how it <em>converts</em> that token's claims into Spring Security authorities, and
 * how it answers <em>cross-origin</em> browser requests from the Angular Client_App.
 *
 * <p>This class deliberately concentrates three closely-related beans in one place because they all
 * describe the same trust boundary &mdash; "which tokens do we accept, what do they authorize, and
 * which browser origin may call us":
 *
 * <ol>
 *   <li>{@link #jwtDecoder(List)} &mdash; builds the {@link JwtDecoder} that loads Entra's signing
 *       keys (JWKS) via OIDC discovery and runs the composite validator chain
 *       (issuer + timing/clock-skew + audience).</li>
 *   <li>{@link #jwtAuthenticationConverter()} &mdash; wires the {@link RolesClaimConverter} so the
 *       token's {@code roles} claim becomes {@code ROLE_*} authorities.</li>
 *   <li>{@link #corsConfigurationSource()} &mdash; encodes the single-origin CORS policy so only the
 *       {@code http://localhost:4200} SPA can make credentialed browser calls.</li>
 * </ol>
 *
 * <p>{@code SecurityConfig} (task 2.4) references these beans: it hands the decoder + converter to
 * {@code oauth2ResourceServer(...).jwt(...)} and registers the CORS source on the filter chain.
 */
@Configuration
public class JwtConfig {

    /**
     * The trusted OpenID Connect issuer for this Entra ID tenant. Injected from
     * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} in {@code application.yml}
     * (the tenant's {@code /v2.0} authority URL).
     *
     * <p>Reading it via {@code @Value} keeps the identity constant in configuration rather than
     * hard-coded in Java. This one value drives two things at once: (a) OIDC discovery of the JWKS
     * endpoint used to verify token signatures (R6.1, R6.2), and (b) the expected {@code iss} claim
     * that {@link JwtValidators#createDefaultWithIssuer(String)} enforces (R6.5).
     */
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /**
     * Builds the {@link JwtDecoder} that every protected request flows through, together with the
     * full set of validators that decide whether a presented token is acceptable.
     *
     * <p><strong>Key loading (R6.1, R6.2).</strong> {@link NimbusJwtDecoder#withIssuerLocation(String)}
     * performs OpenID Connect discovery against
     * {@code <issuer-uri>/.well-known/openid-configuration}, reads the {@code jwks_uri} from that
     * document, and loads Entra's public signing keys (JWKS). This happens eagerly as the bean is
     * created at startup: if the discovery/JWKS endpoint is unreachable, bean creation fails and the
     * application aborts startup with a signing-keys error (R6.2) &mdash; a misconfigured server
     * never serves traffic. The decoder also transparently verifies the RS256 signature of each
     * incoming token against these keys; a token whose signature does not verify is rejected, which
     * the framework maps to HTTP 401 (R6.3, R6.7).
     *
     * <p><strong>Validator chain (composed via {@link DelegatingOAuth2TokenValidator}).</strong>
     * The delegating validator runs each member validator and aggregates their results; if any one
     * fails, the token is rejected. Each of the three members below rejects a token by producing an
     * {@code invalid_token} {@link OAuth2TokenValidator} failure, which Spring's
     * {@code BearerTokenAuthenticationEntryPoint} turns into an HTTP <strong>401 Unauthorized</strong>
     * with a {@code WWW-Authenticate: Bearer error="invalid_token"} challenge header:
     *
     * <ul>
     *   <li>{@code withIssuer} &mdash; {@link JwtValidators#createDefaultWithIssuer(String)} asserts
     *       the {@code iss} claim equals our tenant issuer (R6.5). A token minted by any other
     *       issuer/tenant is rejected -&gt; 401. (This default bundle also contains a standard
     *       timestamp validator, but we add our own skew-aware one below so the 60s allowance is
     *       explicit and authoritative.)</li>
     *   <li>{@code withClockSkew} &mdash; a {@link JwtTimestampValidator} configured with a
     *       {@link Duration#ofSeconds(long) 60-second} clock-skew allowance (R3.1). A token whose
     *       {@code exp} is earlier than {@code now - 60s} is treated as expired and rejected -&gt;
     *       401 with a {@code WWW-Authenticate} challenge indicating expiry (R3.2). The 60s window
     *       tolerates minor clock differences between Entra and this server without accepting truly
     *       stale tokens.</li>
     *   <li>{@code withAudience} &mdash; the custom {@link AudienceValidator} (task 2.1) accepts the
     *       token only if its {@code aud} claim contains one of the configured audiences (the
     *       Client_ID or its {@code api://} form). Spring does <em>not</em> validate {@code aud} by
     *       default, so this closes the "confused deputy" gap: a validly-signed token minted for a
     *       different API is rejected -&gt; 401 (R6.4, R6.8).</li>
     * </ul>
     *
     * @param audiences the accepted {@code aud} values, injected from the custom
     *                  {@code app.security.audiences} list in {@code application.yml}. These are the
     *                  Client_ID and its {@code api://Client_ID} form; they are handed straight to
     *                  the {@link AudienceValidator} constructor (R6.4).
     * @return a fully-configured decoder that loads JWKS at startup and enforces
     *         signature + issuer + expiry(+skew) + audience on every token
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.security.audiences}") List<String> audiences) {
        // Build the decoder from the issuer location. This triggers OIDC discovery and eager JWKS
        // loading at startup (R6.1); an unreachable endpoint fails bean creation -> startup aborts
        // with a signing-keys error (R6.2). The returned NimbusJwtDecoder also verifies the token
        // signature against the loaded keys on every request (R6.3, R6.7).
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();

        // (R6.5) Issuer + default claim validation. This asserts the token's `iss` claim identifies
        // our tenant; a token from any other issuer is rejected -> 401.
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);

        // (R3.1) Expiry validation with a 60-second clock-skew allowance. A token whose `exp` is
        // earlier than now-60s is rejected as expired -> 401 + WWW-Authenticate (R3.2).
        OAuth2TokenValidator<Jwt> withClockSkew = new JwtTimestampValidator(Duration.ofSeconds(60));

        // (R6.4) Audience validation. Accepts only tokens whose `aud` matches a configured value;
        // Spring does not check `aud` by default, so a mismatch is rejected here -> 401 (R6.8).
        OAuth2TokenValidator<Jwt> withAudience = new AudienceValidator(audiences);

        // Compose the three into a single delegating validator. Any single failure rejects the
        // token; each failure surfaces as invalid_token -> 401 with a WWW-Authenticate challenge.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                withIssuer, withClockSkew, withAudience));

        return decoder;
    }

    /**
     * Wires the token-to-authorities conversion used by the OAuth2 resource server.
     *
     * <p>Spring's {@link JwtAuthenticationConverter} is the adapter that turns a decoded, validated
     * {@link Jwt} into the {@link AbstractAuthenticationToken} placed in the security context. By
     * default it derives authorities from the {@code scope}/{@code scp} claim; we override that with
     * {@link #jwtAuthenticationConverter()}'s call to
     * {@code setJwtGrantedAuthoritiesConverter(new RolesClaimConverter())} so authorities come from
     * the Entra {@code roles} claim instead (R6.6).
     *
     * <p>{@link RolesClaimConverter} is <em>package-private</em> in
     * {@code com.example.entraoauth.security}. Because {@code JwtConfig} lives in the same package,
     * it can instantiate the converter directly here without widening its visibility. The converter
     * maps each distinct raw role value {@code r} to a {@code ROLE_ + r} authority, which is exactly
     * what {@code hasRole('Admin')} / {@code hasAnyRole('Viewer','Admin')} expect downstream.
     *
     * @return a converter that produces {@code ROLE_*} authorities from the token's {@code roles}
     *         claim (R6.6)
     */
    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        // Replace the default scope-based authority conversion with our roles-claim conversion.
        // RolesClaimConverter is package-private; same-package access lets us construct it directly.
        converter.setJwtGrantedAuthoritiesConverter(new RolesClaimConverter());
        return converter;
    }

    /**
     * Defines the Cross-Origin Resource Sharing (CORS) policy the Resource_Server advertises to
     * browsers. {@code SecurityConfig} (task 2.4) registers this source on the filter chain so both
     * preflight ({@code OPTIONS}) and actual responses carry the correct CORS headers.
     *
     * <p><strong>Why CORS matters here.</strong> The Angular Client_App is served from
     * {@code http://localhost:4200} while this API is served from {@code http://localhost:8080} &mdash;
     * a different origin. Browsers enforce the same-origin policy and will block the SPA from reading
     * cross-origin responses unless the server explicitly opts in with the appropriate
     * {@code Access-Control-Allow-*} headers. This bean is that opt-in.
     *
     * <p><strong>Single explicit origin (R5.1, R5.6).</strong> Exactly one origin is allowed,
     * {@code http://localhost:4200} &mdash; not the {@code *} wildcard. When a request arrives from
     * that origin, Spring reflects it back in the {@code Access-Control-Allow-Origin} header (R5.1).
     * When a request arrives from <em>any other</em> origin, Spring omits the
     * {@code Access-Control-Allow-Origin} header entirely (R5.6); the browser then sees no matching
     * allow-origin and <strong>blocks</strong> the response, so other websites cannot read this API
     * from a user's browser. (A wildcard {@code *} is also incompatible with
     * {@code allow-credentials: true}, which is a second reason to pin the single origin.)
     *
     * <ul>
     *   <li>Allowed methods {@code GET, POST, PUT, DELETE, OPTIONS} &mdash; advertised in
     *       {@code Access-Control-Allow-Methods} on preflight so the SPA's read and write calls are
     *       permitted; {@code OPTIONS} covers the preflight request itself (R5.3).</li>
     *   <li>Allowed headers {@code Authorization, Content-Type} &mdash; advertised in
     *       {@code Access-Control-Allow-Headers} so the browser permits sending the bearer token
     *       ({@code Authorization}) and JSON bodies ({@code Content-Type}) on the actual request
     *       (R5.4).</li>
     *   <li>Allow credentials {@code true} &mdash; sets {@code Access-Control-Allow-Credentials: true}
     *       so credentialed cross-origin requests are honored for the allowed origin (R5.5).</li>
     * </ul>
     *
     * @return a {@link CorsConfigurationSource} applying the above policy to every path ({@code /**})
     */
    @Bean
    @Primary
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // (R5.1, R5.6) Single explicit origin. Requests from other origins get NO
        // Access-Control-Allow-Origin header, so the browser blocks them.
        config.setAllowedOrigins(List.of("http://localhost:4200"));
        // (R5.3) Methods the SPA is allowed to use cross-origin, including the OPTIONS preflight.
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // (R5.4) Request headers the browser may send: the bearer token and the JSON content type.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // (R5.5) Permit credentialed cross-origin requests for the allowed origin.
        config.setAllowCredentials(true);

        // Apply this single policy to every path on the server.
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
