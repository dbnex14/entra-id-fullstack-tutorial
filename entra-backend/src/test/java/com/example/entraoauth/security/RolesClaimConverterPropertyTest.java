package com.example.entraoauth.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link RolesClaimConverter}.
 *
 * <p><b>Feature:</b> entra-oauth-fullstack &mdash; <b>Property 1: Role claim maps to exactly the
 * prefixed distinct set.</b>
 *
 * <p><b>Validates: Requirements 2.2, 2.3, 2.4, 2.5, 2.6</b>
 *
 * <h2>What this property says (the design's Correctness Property 1)</h2>
 * <ul>
 *   <li><b>Property A &mdash; array of strings.</b> For <i>any</i> {@code roles} claim that is an
 *       array of arbitrary strings, the authorities produced by {@link RolesClaimConverter} equal
 *       <i>exactly</i> the set {@code { "ROLE_" + r }} over the <b>distinct</b> raw values, with the
 *       original case preserved. This is order-insensitive set equality: the converter deduplicates
 *       and prefixes, so the natural oracle is a {@link Set}. (R2.2, R2.3, R2.4)</li>
 *   <li><b>Property B &mdash; absent / non-array claim.</b> For <i>any</i> input where the
 *       {@code roles} claim is absent, or is present but is not a {@link Collection} (a plain
 *       string, a number, a JSON object/map), the resulting authority set is <b>empty</b>.
 *       (R2.5, R2.6)</li>
 *   <li><b>Property C (optional, R2.6) &mdash; mixed non-string elements.</b> When a
 *       {@code Collection} claim interleaves non-string elements (numbers, nested maps, nulls) among
 *       genuine role strings, the non-string elements are <b>ignored</b> and only the string subset
 *       produces authorities.</li>
 * </ul>
 *
 * <h2>Why this test lives in package {@code com.example.entraoauth.security}</h2>
 * <p>{@link RolesClaimConverter} is package-private (no {@code public} modifier). Placing this test
 * in the <i>same</i> package under {@code src/test} is what allows {@code new RolesClaimConverter()}
 * to be instantiated directly &mdash; a lightweight, dependency-free unit under test, exercised in
 * isolation from the full Spring context.
 *
 * <h2>Generators</h2>
 * <p>Every {@code @Property} runs a minimum of 100 generated cases ({@code tries = 100}). The
 * generators are written to constrain the input space intelligently to the shapes the converter
 * actually has to defend against, while still covering the interesting cases (duplicates, case
 * variants, empty lists, and hostile non-array/non-string values).
 */
class RolesClaimConverterPropertyTest {

    /**
     * The single instance under test. {@link RolesClaimConverter} is stateless, so one shared
     * instance is safe across every generated case.
     */
    private final RolesClaimConverter converter = new RolesClaimConverter();

    // ---------------------------------------------------------------------------------------------
    // Test helpers: build a real Spring Security Jwt whose "roles" claim carries a chosen value.
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a minimal but valid {@link Jwt} whose {@code roles} claim is set to {@code rolesValue}.
     *
     * <p>{@code Jwt.withTokenValue(...)} requires at least one header and at least one claim to build
     * successfully, so we always supply an {@code alg} header and a {@code sub} claim in addition to
     * the {@code roles} claim under test. The converter only ever reads the {@code roles} claim, so
     * the other fields are inert scaffolding needed purely to satisfy the builder.
     *
     * @param rolesValue the raw value to place under the {@code roles} claim (a List, a String, a
     *                   number, a Map, etc.)
     * @return a built {@link Jwt} carrying that {@code roles} claim
     */
    private static Jwt jwtWithRawRolesClaim(Object rolesValue) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")   // Jwt builder requires at least one header
                .subject("s")            // a non-roles claim so the token is well-formed
                .claim("roles", rolesValue)
                .build();
    }

    /**
     * Builds a {@link Jwt} whose {@code roles} claim is the supplied list of role strings.
     *
     * @param roles the array of role name strings to expose under the {@code roles} claim
     * @return a built {@link Jwt} carrying that {@code roles} array
     */
    private static Jwt jwtWithRolesClaim(List<String> roles) {
        return jwtWithRawRolesClaim(roles);
    }

    /**
     * Builds a {@link Jwt} that has NO {@code roles} claim at all (the absent-claim case for R2.5).
     * We set an unrelated claim so the token is still well-formed.
     *
     * @return a built {@link Jwt} with no {@code roles} claim
     */
    private static Jwt jwtWithoutRolesClaim() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("s")
                .claim("scope", "access_as_user") // some other claim, but deliberately no "roles"
                .build();
    }

    /**
     * Collects the authority strings produced by the converter into a {@link Set} for
     * order-insensitive comparison against the oracle.
     */
    private Set<String> authoritiesOf(Jwt jwt) {
        Collection<GrantedAuthority> authorities = converter.convert(jwt);
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    // ---------------------------------------------------------------------------------------------
    // Property A (R2.2, R2.3, R2.4): array of strings -> ROLE_ + distinct raw values, case preserved.
    // ---------------------------------------------------------------------------------------------

    /**
     * Generates arbitrary role arrays. Role names are non-empty strings (length 1..20). We
     * deliberately draw from a mixed alphabet that includes both cases and digits so the test
     * exercises <b>case sensitivity</b> (R2.2: "Admin" and "admin" must map to distinct authorities)
     * and, because the pool is small, naturally produces <b>duplicates</b> within an array so the
     * distinct-deduplication behaviour (R2.2) is exercised too. The list itself may be empty, which
     * is a valid edge case (an empty array yields no authorities).
     */
    @Provide
    Arbitrary<List<String>> roleArrays() {
        // A small, controlled alphabet of role-name characters keeps names readable while making
        // collisions (duplicate roles) and case variants frequent within a single generated array.
        Arbitrary<String> roleName = Arbitraries.strings()
                .withChars("AaDdMmIiNnVvEeRrWw_0123456789")
                .ofMinLength(1)
                .ofMaxLength(20);
        // Lists of 0..8 role names. The upper bound is small on purpose: the property holds for any
        // size, and a modest cap keeps each of the 100+ generated cases cheap.
        return roleName.list().ofMinSize(0).ofMaxSize(8);
    }

    /**
     * <b>Property A.</b> For any generated {@code roles} array of strings, the converter's output is
     * exactly {@code ROLE_} + each DISTINCT raw value, with case preserved and compared as an
     * (order-insensitive) set.
     *
     * @param roles a jqwik-generated arbitrary array of role name strings
     */
    @Property(tries = 100)
    void rolesMapToPrefixedDistinctAuthorities(@ForAll("roleArrays") List<String> roles) {
        Jwt jwt = jwtWithRolesClaim(roles);

        // Oracle: the exact expected authority set is ROLE_ + each DISTINCT raw value. We deduplicate
        // on the raw value (matching "one authority per distinct value") and apply the ROLE_ prefix
        // WITHOUT any case normalization, so "Admin" -> "ROLE_Admin" and "admin" -> "ROLE_admin"
        // remain distinct entries (R2.2, R2.3, R2.4).
        Set<String> expected = roles.stream()
                .distinct()
                .map(r -> "ROLE_" + r)
                .collect(Collectors.toSet());

        Set<String> actual = authoritiesOf(jwt);

        assertThat(actual).isEqualTo(expected);
    }

    /**
     * A focused variant of Property A using a generator biased toward the domain's real role values
     * (Admin, Viewer) plus their mis-cased forms. This makes case sensitivity explicit: for example,
     * an array containing both "Admin" and "admin" must yield the two distinct authorities
     * "ROLE_Admin" and "ROLE_admin" (R2.2-R2.4).
     *
     * @param roles a jqwik-generated array drawn from a curated pool of realistic / mis-cased roles
     */
    @Property(tries = 100)
    void realisticRoleValuesPreserveCaseAndDistinctness(@ForAll("realisticRoleArrays") List<String> roles) {
        Jwt jwt = jwtWithRolesClaim(roles);

        Set<String> expected = roles.stream()
                .distinct()
                .map(r -> "ROLE_" + r)
                .collect(Collectors.toSet());

        assertThat(authoritiesOf(jwt)).isEqualTo(expected);
    }

    /**
     * Generates arrays whose elements are drawn from a curated pool that mixes the real application
     * roles ("Admin", "Viewer") with mis-cased and adjacent values, so duplicates and case variants
     * appear often across the 100+ runs.
     */
    @Provide
    Arbitrary<List<String>> realisticRoleArrays() {
        Arbitrary<String> pool = Arbitraries.of(
                "Admin", "admin", "ADMIN",
                "Viewer", "viewer", "VIEWER",
                "Editor", "SuperUser", "guest");
        return pool.list().ofMinSize(0).ofMaxSize(6);
    }

    // ---------------------------------------------------------------------------------------------
    // Property B (R2.5, R2.6): absent or non-array roles claim -> empty authorities.
    // ---------------------------------------------------------------------------------------------

    /**
     * <b>Property B.</b> For any {@code roles} claim value that is NOT a {@link Collection} &mdash;
     * including the absent case &mdash; the converter returns an empty authority collection.
     *
     * <p>The generator {@link #nonArrayRolesClaims()} produces values that are never a
     * {@link Collection}: plain strings, integers, doubles, booleans, and JSON-object-like maps. For
     * the absent-claim case, {@code null} is represented by building a Jwt with no {@code roles}
     * claim at all (a {@link Jwt} cannot be built with a literal {@code null} claim value).
     *
     * @param claimValue a jqwik-generated non-Collection value for the {@code roles} claim
     */
    @Property(tries = 100)
    void nonArrayOrAbsentRolesYieldEmptyAuthorities(@ForAll("nonArrayRolesClaims") Object claimValue) {
        // A sentinel string marks the "absent claim" case, since we cannot put a literal null under
        // a claim via the Jwt builder. Everything else is set as the raw (non-Collection) claim value.
        Jwt jwt = ABSENT_CLAIM_SENTINEL.equals(claimValue)
                ? jwtWithoutRolesClaim()
                : jwtWithRawRolesClaim(claimValue);

        assertThat(converter.convert(jwt)).isEmpty();
    }

    /**
     * Sentinel value standing in for the "roles claim absent" case within the generator. The test
     * translates this sentinel into a Jwt that has no {@code roles} claim at all.
     */
    private static final String ABSENT_CLAIM_SENTINEL = "__ABSENT_ROLES_CLAIM__";

    /**
     * Generates values for the {@code roles} claim that are NOT JSON arrays: the absent-claim
     * sentinel, plain strings, integers, doubles, booleans, and JSON-object-like maps. None of these
     * is a {@link Collection}, so the converter must treat every one as "zero roles" (R2.5, R2.6).
     */
    @Provide
    Arbitrary<Object> nonArrayRolesClaims() {
        Arbitrary<Object> absent = Arbitraries.just(ABSENT_CLAIM_SENTINEL);
        Arbitrary<Object> strings = Arbitraries.strings().ofMinLength(0).ofMaxLength(20).map(s -> (Object) s);
        Arbitrary<Object> integers = Arbitraries.integers().map(i -> (Object) i);
        Arbitrary<Object> doubles = Arbitraries.doubles().map(d -> (Object) d);
        Arbitrary<Object> booleans = Arbitraries.of(true, false).map(b -> (Object) b);
        // A JSON-object-like value: a Map. This models a "roles" claim that Entra (or an attacker)
        // sent as an object instead of an array; it is not a Collection, so it must yield no roles.
        Arbitrary<Object> maps = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8)
                .map(key -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put(key, "value");
                    return (Object) m;
                });
        return Arbitraries.oneOf(absent, strings, integers, doubles, booleans, maps);
    }

    // ---------------------------------------------------------------------------------------------
    // Property C (R2.6): non-string elements mixed into the array are ignored.
    // ---------------------------------------------------------------------------------------------

    /**
     * <b>Property C.</b> When the {@code roles} claim is a {@link Collection} that interleaves
     * non-string elements (integers, doubles, booleans, nested maps) among genuine role strings, the
     * converter ignores the non-string elements and produces authorities only for the DISTINCT string
     * values (R2.6, with R2.2 distinctness still holding).
     *
     * <p>The generator produces the list of "genuine" role strings separately from a mixed list that
     * embeds those same strings alongside arbitrary non-string junk. The oracle is derived purely
     * from the string values, so the property asserts the junk had no effect.
     *
     * @param roleStrings the string elements expected to survive filtering
     * @param nonStringCount how many non-string junk elements to interleave (0..5)
     */
    @Property(tries = 100)
    void nonStringElementsAreIgnored(
            @ForAll("roleArrays") List<String> roleStrings,
            @ForAll @Size(min = 0, max = 5) int[] nonStringCount) {

        // Build a mixed claim: all the genuine role strings, plus a handful of non-string values
        // (numbers, booleans, a nested map) interleaved. Order does not matter to the converter.
        List<Object> mixed = new ArrayList<>(roleStrings);
        for (int i = 0; i < nonStringCount.length; i++) {
            // Rotate through a few distinct non-string shapes so the "ignore non-strings" branch is
            // exercised against numbers, booleans, and objects alike.
            switch (i % 3) {
                case 0 -> mixed.add(i);                              // Integer
                case 1 -> mixed.add(Boolean.TRUE);                  // Boolean
                default -> mixed.add(Map.of("nested", "object"));   // Map (JSON object)
            }
        }

        Jwt jwt = jwtWithRawRolesClaim(mixed);

        // Oracle: only the DISTINCT string values become ROLE_-prefixed authorities; all non-string
        // elements contribute nothing.
        Set<String> expected = roleStrings.stream()
                .distinct()
                .map(r -> "ROLE_" + r)
                .collect(Collectors.toSet());

        assertThat(authoritiesOf(jwt)).isEqualTo(expected);
    }

    // ---------------------------------------------------------------------------------------------
    // Concrete example (not a property): the two canonical domain roles map as documented.
    // Complements the universal properties with a fixed, readable sanity check of the ROLE_ scheme.
    // ---------------------------------------------------------------------------------------------

    /**
     * A single concrete example anchoring the property to the design's canonical roles: an
     * {@code ["Admin", "Viewer"]} claim maps to exactly {@code ROLE_Admin} and {@code ROLE_Viewer}
     * (R2.3, R2.4). This is intentionally an example-based check that reads as documentation.
     */
    @Property(tries = 1)
    void canonicalAdminAndViewerRolesMapAsDocumented(
            @ForAll @StringLength(min = 0, max = 0) String ignoredSoJqwikRunsOnce) {
        Jwt jwt = jwtWithRolesClaim(List.of("Admin", "Viewer"));
        assertThat(authoritiesOf(jwt)).containsExactlyInAnyOrder("ROLE_Admin", "ROLE_Viewer");
    }
}
