package com.example.entraoauth.item;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only data transfer object returned to the Client_App for a single {@code item_history} row.
 *
 * <p><strong>Why a DTO instead of exposing the {@link ItemHistory} entity directly.</strong> As with
 * {@link ItemDto}, serializing the JPA entity straight to JSON would couple the wire format to the
 * persistence model and risk triggering lazy-loading of the associated {@link Item} during
 * serialization. This immutable {@code record} is the stable public shape of a history entry.
 *
 * <p><strong>Opaque identifiers only.</strong> Both id fields are opaque UUIDs, never the internal
 * sequential {@code BIGINT}s. {@code id} is this history row's own {@link ItemHistory#getPublicId()
 * public_id}; {@code itemId} is the <em>parent item's</em> {@link Item#getPublicId() public_id}
 * (read via {@link ItemHistory#getItemPublicId()}, which does not force a load of the parent), so it
 * lines up with the {@code id} the client already has for that item from the item list. Keeping both
 * opaque means no sequential id for a history row or its item ever crosses the wire.
 *
 * <p><strong>What is exposed.</strong> Everything a reader needs to understand a change: which item
 * ({@code itemId}, which may be {@code null} if the item was later deleted), the kind of change
 * ({@code changeType}), <em>who</em> made it ({@code actorSubject} = token oid/sub, and
 * {@code actorName} = display name when the token carried a {@code name} claim), a human-readable
 * {@code details} summary, and <em>when</em> it happened ({@code changedAt}). The {@code changedAt}
 * is an {@link OffsetDateTime}; Spring Boot's Jackson configuration serializes it as an ISO-8601
 * string with offset (the JSR-310 module is registered by default).
 *
 * @param id           this history row's opaque public identifier (UUIDv7)
 * @param itemId       opaque public id of the item the change concerned, or {@code null} if that item was deleted
 * @param changeType   the kind of change: {@code CREATE}, {@code UPDATE}, or {@code DELETE}
 * @param actorSubject the token subject (oid/sub) of the identity that made the change
 * @param actorName    the display name of that identity ({@code name} claim), or {@code null}
 * @param details      a short human-readable summary of what changed
 * @param changedAt    when the change happened (ISO-8601 with offset)
 */
public record ItemHistoryDto(
        UUID id,
        UUID itemId,
        ItemHistory.ChangeType changeType,
        String actorSubject,
        String actorName,
        String details,
        OffsetDateTime changedAt) {
}
