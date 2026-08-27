package com.example.entraoauth.security;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Converts the Entra ID {@code roles} claim of a validated {@link Jwt} into Spring Security
 * {@link GrantedAuthority} values (Requirement 2).
 *
 * <p>This class is the bridge between <em>identity</em> (what Entra ID asserts about the caller in
 * the token's {@code roles} claim) and <em>authorization</em> (what Spring Security uses to decide
 * whether a request may proceed). The {@code Resource_Server} never consults a local roles table:
 * authorization is entirely claim-driven, so the correctness of this converter directly determines
 * who can call which endpoint.
 *
 * <h2>Where this runs in the request pipeline</h2>
 * <p>After the {@code JwtDecoder} has verified the token's signature and its {@code iss}/{@code aud}
 * /{@code exp} claims, Spring's {@code JwtAuthenticationConverter} calls this converter to turn the
 * decoded token into the set of authorities attached to the {@code Authentication}. Those
 * authorities are what {@code @PreAuthorize("hasRole('Admin')")} and
 * {@code hasAnyRole('Viewer','Admin')} evaluate later in the controller layer.
 *
 * <h2>The {@code ROLE_} prefixing scheme (why {@code hasRole(...)} relies on it)</h2>
 * <p>Spring Security's {@code hasRole('X')} and {@code hasAnyRole('X', ...)} expressions are a
 * convenience shorthand: they do <strong>not</strong> match an authority named literally {@code X}.
 * Instead they automatically prepend the fixed {@code "ROLE_"} prefix and check for the authority
 * {@code "ROLE_X"}. Consequently, for {@code hasRole('Admin')} to succeed, the authenticated
 * principal must carry the authority {@code "ROLE_Admin"} &mdash; not {@code "Admin"}.
 *
 * <p>Entra ID, however, emits the raw role values ({@code "Admin"}, {@code "Viewer"}) in the
 * {@code roles} claim <em>without</em> that prefix. This converter is therefore the single place
 * responsible for applying the {@code "ROLE_"} prefix, mapping each raw role value {@code r} to a
 * {@link SimpleGrantedAuthority} named {@code "ROLE_" + r}. This keeps the token's vocabulary
 * ({@code Admin}/{@code Viewer}) aligned with Spring's expectation ({@code ROLE_Admin}/
 * {@code ROLE_Viewer}) so that {@code hasRole(...)} works as intended (R2.3, R2.4).
 *
 * <h2>Case sensitivity</h2>
 * <p>The prefix is applied with the raw value's original case preserved &mdash; no upper/lower
 * normalization is performed (R2.2&ndash;R2.4). A role value {@code "Admin"} produces
 * {@code "ROLE_Admin"}; a differently-cased value such as {@code "admin"} would produce a distinct
 * authority {@code "ROLE_admin"} that would <em>not</em> satisfy {@code hasRole('Admin')}. This
 * mirrors Entra ID's own case-sensitive role identifiers and avoids silently granting access to a
 * mis-cased role.
 *
 * <p>Implements {@code Converter<Jwt, Collection<GrantedAuthority>>} so it can be plugged directly
 * into a {@code JwtAuthenticationConverter} via {@code setJwtGrantedAuthoritiesConverter(...)}.
 */
final class RolesClaimConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /**
     * The fixed prefix Spring Security's {@code hasRole(...)}/{@code hasAnyRole(...)} expressions
     * implicitly expect on role-based authorities. Applying it here is what lets a token role value
     * of {@code Admin} satisfy {@code @PreAuthorize("hasRole('Admin')")}.
     */
    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * The name of the Entra ID claim that carries the caller's assigned application roles. Entra ID
     * populates this as a JSON array of strings (e.g. {@code ["Admin", "Viewer"]}).
     */
    private static final String ROLES_CLAIM = "roles";

    /**
     * Maps the token's {@code roles} claim to the corresponding set of {@code ROLE_}-prefixed
     * authorities.
     *
     * @param jwt the already-validated access token (signature, {@code iss}, {@code aud} and
     *            {@code exp} have all been checked before this converter is invoked)
     * @return the granted authorities derived from the {@code roles} claim; never {@code null}, and
     *         empty when the claim is absent, is not an array, or contains no usable string values
     */
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        // Read the raw claim value. Spring's Jwt.getClaim(...) returns whatever type the JSON
        // deserialized to (a List for a JSON array, a String for a scalar, a Map for an object,
        // or null when the claim is absent). We must not assume it is a Collection.
        Object claim = jwt.getClaim(ROLES_CLAIM);

        // Defensive branch #1 (R2.5 + R2.6): reject anything that is not a JSON array.
        //
        //   * Absent claim              -> getClaim returns null -> not a Collection -> empty (R2.5).
        //   * Non-array claim           -> e.g. a plain String, a number, or a JSON object (Map)
        //                                  -> not a Collection -> empty (R2.6).
        //
        // In both cases we grant ZERO authorities so the request is treated as carrying no roles.
        // Returning an immutable empty list (rather than null) guarantees downstream code never has
        // to null-check the authority collection, and a caller with no roles simply fails every
        // hasRole(...) check and receives 403 on protected endpoints (R2.7).
        if (!(claim instanceof Collection<?> rawRoles)) {
            return List.of();
        }

        // The claim is a JSON array. Elements are, however, still individually untrusted: a
        // malformed or hostile token could interleave non-string entries (numbers, nested objects,
        // nulls) among the role strings. We build the authority set defensively, element by element.
        return rawRoles.stream()
                // Defensive branch #2 (R2.6): keep only genuine string entries. Any non-string
                // element in the array is ignored rather than being coerced or causing a failure,
                // so a partially-malformed roles array still yields the valid subset of roles.
                .filter(String.class::isInstance)
                .map(String.class::cast)
                // Distinct raw values (R2.2): Entra ID should not repeat roles, but a duplicated
                // entry must not produce duplicate authorities. Deduplicating on the RAW value
                // (before prefixing) keeps the resulting authority set minimal and matches the
                // "one Spring_Authority for each distinct value" wording of the requirement.
                .distinct()
                // ROLE_ prefixing, case preserved (R2.2-R2.4): map each distinct raw role to
                // SimpleGrantedAuthority("ROLE_" + value). No case normalization is applied, so
                // "Admin" -> "ROLE_Admin" and "Viewer" -> "ROLE_Viewer", exactly matching what
                // hasRole('Admin') / hasAnyRole('Viewer','Admin') look for later in the chain.
                .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                // Collect into an unmodifiable list so the authority set attached to the
                // Authentication cannot be mutated after conversion.
                .collect(Collectors.<GrantedAuthority>toUnmodifiableList());
    }
}
