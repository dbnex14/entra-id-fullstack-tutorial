package com.example.entraoauth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point for the Entra ID OAuth2 full-stack reference backend.
 *
 * <p>This class is the Spring Boot {@code Resource_Server}: a stateless JDK 21 / Spring Boot 3
 * service (served on {@code http://localhost:8080}) that validates JWT access tokens minted by
 * Microsoft Entra ID and exposes role-protected REST endpoints. It never issues tokens; it only
 * validates them and derives authorization decisions from the {@code roles} claim.
 *
 * <h2>Two things happen automatically at startup that this class deliberately does NOT wire by
 * hand &mdash; both are driven by {@code src/main/resources/application.yml}:</h2>
 *
 * <h3>1. Flyway migrates the database on startup (Requirement 7.1)</h3>
 * <p>Spring Boot detects {@code flyway-core} (plus {@code flyway-database-postgresql}, required at
 * v10+ for PostgreSQL 17) on the classpath and auto-configures the {@code Flyway} bean. Before the
 * JPA/Hibernate {@code EntityManagerFactory} is initialized, Spring Boot runs Flyway against the
 * {@code spring.datasource} defined in {@code application.yml}
 * ({@code jdbc:postgresql://localhost:5432/my_workspace}, user {@code postgres}).
 * <ul>
 *   <li>Flyway connects within the 10-second budget (Hikari {@code connection-timeout: 10000}); if
 *       the database is unreachable it halts startup with a connection error (R7.1 / R7.2).</li>
 *   <li>It scans {@code classpath:db/migration} for {@code V<version>__<description>.sql} files and
 *       applies all pending migrations in ascending version order (R7.3), recording each in the
 *       {@code flyway_schema_history} table (R7.4) and skipping already-applied ones (R7.5).</li>
 *   <li>With {@code validate-on-migrate: true}, a checksum drift on any previously-applied
 *       migration halts startup with a validation error, leaving the schema untouched (R7.6).</li>
 * </ul>
 * <p>Because the schema is owned entirely by Flyway ("schema is code"), Hibernate is configured
 * with {@code spring.jpa.hibernate.ddl-auto: validate} &mdash; it only checks that the entities
 * match the Flyway-managed schema and never mutates it.
 *
 * <h3>2. The OAuth2 resource server wiring is auto-configured from {@code application.yml}
 * (Requirement 6.1)</h3>
 * <p>Spring Boot detects {@code spring-boot-starter-oauth2-resource-server} on the classpath and,
 * seeing {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} in {@code application.yml},
 * auto-configures a {@code JwtDecoder}. At startup it performs OIDC discovery against the Entra ID
 * authority
 * ({@code https://login.microsoftonline.com/76325907-a5db-46b1-9d5a-cbcca2e63e66/v2.0}) to derive
 * the JWKS URI and load the signing keys (R6.1); if the keys are unreachable, startup fails with a
 * clear error (R6.2). Each incoming request is then authenticated purely from its bearer token:
 * the signature is verified against those keys and the {@code iss}/{@code aud} claims are validated
 * (custom audience validation and role-claim conversion are layered on in the {@code security}
 * package). No HTTP session is created &mdash; the server is fully stateless.
 *
 * <p>Keeping this entry point minimal is intentional: the two most important behaviours of the
 * service (schema migration and token validation) are configuration-driven, so the running system
 * is described declaratively in {@code application.yml} rather than in imperative bootstrap code.
 */
@SpringBootApplication
public class EntraOauthApplication {

    /**
     * Boots the Spring application context.
     *
     * <p>{@link SpringApplication#run} triggers the auto-configuration described above: it builds
     * the {@code DataSource}, runs Flyway migrations against it, validates the JPA schema, and
     * stands up the OAuth2 resource server security filter chain &mdash; all before the embedded
     * web server begins accepting requests on port 8080.
     *
     * @param args standard command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(EntraOauthApplication.class, args);
    }
}
