package com.example.entraoauth.item;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
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
 * Security-slice tests for {@link HistoryController} (the global change log at
 * {@code GET /entra-backend/history}), matching the style of {@link ItemControllerSecurityTest}.
 *
 * <p><strong>What this proves.</strong> The global change log is a <em>read</em> endpoint open to
 * both roles: a {@code ROLE_Viewer} and a {@code ROLE_Admin} each receive <strong>200</strong>, while
 * an unauthenticated caller receives <strong>401</strong> before dispatch. This mirrors the item-list
 * rule (reads open to Viewer/Admin) and confirms history is observational data both roles may read,
 * even though only Admin can generate it.
 *
 * <p><strong>Why the wiring matches {@link ItemControllerSecurityTest}.</strong> {@code @WebMvcTest}
 * loads only the target controller and MVC/security infrastructure, not the application's
 * {@code JwtConfig}. We therefore {@link Import} the real {@link SecurityConfig} (so
 * {@code @EnableMethodSecurity} and the genuine filter chain are active) and supply offline
 * mock/stub beans for the {@code Converter<Jwt, AbstractAuthenticationToken>} and {@link JwtDecoder}
 * the chain requires - identical to the item-controller slice. Authentication is simulated with the
 * {@code jwt()} post-processor, so no token is ever really decoded.
 */
@WebMvcTest(HistoryController.class)
@Import({SecurityConfig.class, HistoryControllerSecurityTest.SecuritySliceTestConfig.class})
class HistoryControllerSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    /**
     * Mock of the service the controller delegates to. Stubbing it keeps the test focused on HTTP +
     * security behavior and never touches JPA.
     */
    @MockBean
    private ItemService itemService;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // An authorized GET returns this list and yields 200. The 401 path never reaches this stub.
        when(itemService.findHistory())
                .thenReturn(List.of(new ItemHistoryDto(
                        1L, 1L, ItemHistory.ChangeType.CREATE,
                        "subject-oid", "Ada Admin", "Created item 'existing'",
                        OffsetDateTime.parse("2026-01-15T10:22:31.512Z"))));
    }

    /**
     * A {@code ROLE_Viewer} may read the global change log (200): reads are open to Viewer and Admin.
     */
    @Test
    void viewerCanReadGlobalHistory() throws Exception {
        mockMvc.perform(get("/history")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Viewer"))))
                .andExpect(status().isOk());
    }

    /**
     * A {@code ROLE_Admin} may likewise read the global change log (200).
     */
    @Test
    void adminCanReadGlobalHistory() throws Exception {
        mockMvc.perform(get("/history")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Admin"))))
                .andExpect(status().isOk());
    }

    /**
     * An unauthenticated caller is rejected with 401 before dispatch (R8.4, R8.5).
     */
    @Test
    void anonymousReadGlobalHistoryIsUnauthorized() throws Exception {
        mockMvc.perform(get("/history"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Minimal test-only beans that satisfy {@link SecurityConfig#filterChain}'s dependencies offline,
     * identical in purpose to the config in {@link ItemControllerSecurityTest}.
     */
    @TestConfiguration
    static class SecuritySliceTestConfig {

        @Bean
        Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
            return new JwtAuthenticationConverter();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return org.mockito.Mockito.mock(JwtDecoder.class);
        }
    }
}
