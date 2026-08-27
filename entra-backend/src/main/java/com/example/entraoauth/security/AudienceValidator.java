package com.example.entraoauth.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Validates the {@code aud} (audience) claim of an incoming Entra ID Access_Token.
 *
 * <p><strong>Why this class exists at all.</strong> Spring Security's resource-server
 * support validates the token signature, the {@code iss} (issuer) claim, and the timing
 * claims ({@code exp}/{@code nbf}) out of the box, but it does <em>not</em> validate the
 * {@code aud} claim by default. The audience claim is what states "this token was minted to
 * be presented to <em>this</em> API." Without checking it, a token that Entra ID issued for a
 * completely different resource (for example, Microsoft Graph or another app registration in
 * the same tenant) would still carry a valid signature and issuer and would therefore sail
 * through the default validators. That is a "confused deputy" style problem: the token is
 * genuine, just not meant for us. Requirement R6.4 closes that gap by demanding an explicit
 * audience check, so we plug this custom {@link OAuth2TokenValidator} into the decoder's
 * {@code DelegatingOAuth2TokenValidator} chain (see {@code JwtConfig}).
 *
 * <p><strong>Why two accepted audience values.</strong> Depending on how the client requested
 * the token, Entra ID stamps the {@code aud} claim as either the bare Client_ID
 * ({@code 4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c}) or the "Application ID URI" form
 * ({@code api://4ebf7ee5-2120-4d4a-8c31-63642bb9fc9c}). Both identify this same API, so both
 * are accepted. Rather than hard-coding them, the accepted set is injected via the constructor
 * from the custom {@code app.security.audiences} list in {@code application.yml}, which keeps
 * the identity constants in configuration and lets {@code JwtConfig} wire them in.
 *
 * <p><strong>How a failed check becomes a 401.</strong> When none of the token's audiences
 * match, {@link #validate(Jwt)} returns a failure result carrying an
 * {@link OAuth2Error} with error code {@code "invalid_token"}. The decoder propagates that as
 * a {@code JwtValidationException}, and Spring's {@code BearerTokenAuthenticationEntryPoint}
 * translates it into an HTTP <strong>401 Unauthorized</strong> response whose
 * {@code WWW-Authenticate} header advertises the {@code Bearer} scheme along with
 * {@code error="invalid_token"} and a human-readable {@code error_description}. That is exactly
 * the challenge semantics Requirement R6.8 (audience/issuer mismatch -> 401) and R8.5 require,
 * and it is the same shape the frontend interceptor keys off of when deciding whether to
 * attempt a silent refresh.
 */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    /**
     * The standard OAuth 2.0 error code for a rejected bearer token. Using this exact code is
     * important: the framework echoes it into the {@code WWW-Authenticate} challenge, and both
     * RFC 6750 clients and our own frontend rely on {@code invalid_token} to distinguish a
     * token problem (re-authenticate / refresh) from a mere authorization problem (403).
     */
    private static final String INVALID_TOKEN = "invalid_token";

    /**
     * The set of audience values this API will accept. Populated from
     * {@code app.security.audiences} in {@code application.yml} via constructor injection.
     * Stored as an unmodifiable copy so the validator's accepted set cannot be mutated after
     * construction.
     */
    private final List<String> acceptedAudiences;

    /**
     * A pre-built {@link OAuth2Error} describing the failure. It is safe to reuse a single
     * immutable instance for every rejection because the error carries no per-request state;
     * this simply avoids allocating a new object on each failed validation.
     */
    private final OAuth2Error audienceError;

    /**
     * @param acceptedAudiences the audience values that are allowed to reach this API. In this
     *                          application these are the Client_ID and its {@code api://} form,
     *                          supplied from configuration. Must be non-null and non-empty; an
     *                          empty set would reject every token and almost certainly signals a
     *                          misconfiguration, so we fail fast at startup instead.
     */
    public AudienceValidator(Collection<String> acceptedAudiences) {
        Objects.requireNonNull(acceptedAudiences, "acceptedAudiences must not be null");
        if (acceptedAudiences.isEmpty()) {
            // Failing here surfaces the mistake at application startup (a hard, obvious error)
            // rather than silently rejecting every request at runtime with a 401.
            throw new IllegalArgumentException(
                    "At least one accepted audience must be configured (see app.security.audiences)");
        }
        // Defensive copy -> the accepted set is fixed for the lifetime of this validator.
        this.acceptedAudiences = List.copyOf(acceptedAudiences);
        this.audienceError = new OAuth2Error(
                INVALID_TOKEN,
                "The required audience (aud) claim is missing or does not match this API. Accepted: "
                        + this.acceptedAudiences,
                // The optional URI slot in OAuth2Error; null is conventional when there is no
                // dedicated documentation page for the error.
                null);
    }

    /**
     * Accepts the token only if its {@code aud} claim contains at least one of the configured
     * audiences.
     *
     * <p>The {@code aud} claim in a JWT may be either a single string or an array of strings.
     * Spring normalizes this for us: {@link Jwt#getAudience()} always returns a {@code List<String>}
     * (empty when the claim is absent), so a single {@code intersection} check covers both shapes
     * and the missing-claim case without extra branching.
     *
     * @param jwt the decoded token whose audience is being checked (never null in practice, as the
     *            decoder only invokes validators after successfully parsing the token)
     * @return {@link OAuth2TokenValidatorResult#success()} when an accepted audience is present,
     *         otherwise a failure carrying the {@code invalid_token} error (mapped to 401 upstream)
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> tokenAudiences = jwt.getAudience();

        // A token with no audience at all can never match; treat it the same as a mismatch.
        if (tokenAudiences != null) {
            for (String audience : tokenAudiences) {
                if (acceptedAudiences.contains(audience)) {
                    // Exactly one match is sufficient (R6.4): the token was minted for this API.
                    return OAuth2TokenValidatorResult.success();
                }
            }
        }

        // No accepted audience found. Returning a failure result here is what ultimately drives
        // the 401 + WWW-Authenticate: invalid_token challenge described in the class javadoc
        // (R6.8 / R8.5). We deliberately do NOT throw; the delegating validator collects failures
        // from every validator in the chain and reports them together.
        return OAuth2TokenValidatorResult.failure(audienceError);
    }
}
