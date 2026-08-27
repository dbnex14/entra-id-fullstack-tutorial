package com.example.entraoauth.item;

/**
 * Read-only data transfer object returned to the Client_App for {@code item} resources.
 *
 * <p><strong>Why a DTO instead of exposing the {@link Item} entity directly.</strong> The JPA
 * {@link Item} entity is a mutable, persistence-managed object tied to Hibernate's lifecycle
 * (lazy loading, dirty tracking, identity). Serializing it straight to JSON leaks those concerns
 * and couples the wire format to the database schema. This immutable {@code record} is the stable,
 * intentional public shape of an item on the API boundary.
 *
 * <p><strong>What is (and is not) exposed.</strong> The DTO surfaces {@code id}, {@code name},
 * {@code description}, and {@code createdBy}. The {@code createdBy} field is the token subject
 * ({@code oid}/{@code sub} claim) that was recorded when the row was written, so a client reading an
 * item can see which identity created it &mdash; this is the point where persisted data is tied back
 * to identity. The internal {@code createdAt}/{@code updatedAt} timestamps are intentionally omitted
 * from this instructional projection; add them here if the UI needs to display them.
 *
 * @param id          database-assigned identifier of the item
 * @param name        the item name
 * @param description optional description (may be null)
 * @param createdBy   the token subject (oid/sub) of the identity that created the item
 */
public record ItemDto(Long id, String name, String description, String createdBy) {
}
