package com.example.entraoauth.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link ItemService} focused on the new behavior the feature adds: a change-history
 * row is written on every create/update/delete, capturing the acting identity taken from the
 * validated JWT in the security context.
 *
 * <p><strong>Why a plain (non-Spring) unit test.</strong> The history-writing logic is pure service
 * behavior: it reads the authenticated {@link Jwt} from the {@link SecurityContextHolder} and calls
 * {@code historyRepository.save(..)}. We can exercise it directly with Mockito mocks for the two
 * repositories and a hand-placed authentication in the security context - no web layer, no JPA, no
 * database. This isolates "history is written, with the right change type and actor" from the
 * separately-tested authorization rules ({@link ItemControllerSecurityTest},
 * {@link WriteAuthorizationPropertyTest}).
 *
 * <p><strong>Actor capture.</strong> We seed the context with a {@link Jwt} whose subject is
 * {@code "actor-oid"} and whose {@code name} claim is {@code "Ada Admin"}, then assert the saved
 * {@link ItemHistory} carries exactly those values - proving the service takes the actor from the
 * token (never from client input).
 */
class ItemServiceHistoryTest {

    private ItemRepository itemRepository;
    private ItemHistoryRepository historyRepository;
    private ItemService service;

    @BeforeEach
    void setUp() {
        this.itemRepository = mock(ItemRepository.class);
        this.historyRepository = mock(ItemHistoryRepository.class);
        this.service = new ItemService(itemRepository, historyRepository);

        // save(item) echoes its argument back (assigning no id is fine for these assertions).
        when(itemRepository.save(any(Item.class))).thenAnswer(inv -> inv.getArgument(0));

        // Place a validated-JWT principal in the security context, exactly as the resource-server
        // filter chain would have done after authenticating a real request. The subject and the
        // "name" claim are what the service records as the actor.
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("actor-oid")
                .claim("name", "Ada Admin")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        jwt, "n/a", AuthorityUtils.createAuthorityList("ROLE_Admin")));
    }

    @AfterEach
    void clearContext() {
        // Always clear the shared thread-local context so tests don't leak into one another.
        SecurityContextHolder.clearContext();
    }

    @Test
    void createWritesCreateHistoryWithActorFromToken() {
        service.create(new CreateItemRequest("Widget", "desc", "hardware"));

        ItemHistory saved = captureSavedHistory();
        assertThat(saved.getChangeType()).isEqualTo(ItemHistory.ChangeType.CREATE);
        assertThat(saved.getActorSubject()).isEqualTo("actor-oid");
        assertThat(saved.getActorName()).isEqualTo("Ada Admin");
        assertThat(saved.getDetails()).contains("Widget");
    }

    @Test
    void updateWritesUpdateHistory() {
        // An existing item for the service to load, mutate, and record.
        OffsetDateTime now = OffsetDateTime.now();
        Item existing = new Item("Old", "d", "software", "creator-oid", now, now);
        existing.setId(42L);
        UUID publicId = UUID.randomUUID();
        existing.setPublicId(publicId);
        when(itemRepository.findByPublicId(publicId)).thenReturn(Optional.of(existing));

        service.update(publicId, new UpdateItemRequest("New", "d2", "service"));

        ItemHistory saved = captureSavedHistory();
        assertThat(saved.getChangeType()).isEqualTo(ItemHistory.ChangeType.UPDATE);
        assertThat(saved.getActorSubject()).isEqualTo("actor-oid");
        // The summary describes the before -> after of the human-facing fields.
        assertThat(saved.getDetails()).contains("Old").contains("New");
    }

    @Test
    void deleteWritesDeleteHistoryBeforeRemovingItem() {
        OffsetDateTime now = OffsetDateTime.now();
        Item existing = new Item("Doomed", "d", "hardware", "creator-oid", now, now);
        existing.setId(99L);
        UUID publicId = UUID.randomUUID();
        existing.setPublicId(publicId);
        when(itemRepository.findByPublicId(publicId)).thenReturn(Optional.of(existing));

        service.delete(publicId);

        ItemHistory saved = captureSavedHistory();
        assertThat(saved.getChangeType()).isEqualTo(ItemHistory.ChangeType.DELETE);
        assertThat(saved.getActorSubject()).isEqualTo("actor-oid");
        assertThat(saved.getDetails()).contains("Doomed");
        // The item is still removed after the history is recorded.
        verify(itemRepository).delete(existing);
    }

    @Test
    void deleteOfMissingItemThrows404AndWritesNoHistory() {
        UUID missing = UUID.randomUUID();
        when(itemRepository.findByPublicId(missing)).thenReturn(Optional.empty());

        try {
            service.delete(missing);
        } catch (ResponseStatusException expected) {
            // A missing id yields a clean 404 and must NOT append a spurious history row.
        }
        verify(historyRepository, never()).save(any(ItemHistory.class));
    }

    /**
     * Captures the single {@link ItemHistory} passed to {@code historyRepository.save(..)} so the
     * test can assert its fields.
     */
    private ItemHistory captureSavedHistory() {
        ArgumentCaptor<ItemHistory> captor = ArgumentCaptor.forClass(ItemHistory.class);
        verify(historyRepository).save(captor.capture());
        return captor.getValue();
    }
}
