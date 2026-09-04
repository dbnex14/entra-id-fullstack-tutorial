package com.example.entraoauth.item;

import java.util.UUID;

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
 * {@code description}, {@code category}, and {@code createdBy}. Critically, the exposed {@code id} is
 * the item's opaque {@link Item#getPublicId() public_id} (a UUIDv7), <strong>not</strong> the
 * internal sequential {@code BIGINT} primary key. Exposing the opaque id keeps the internal row
 * sequence off the wire, so a client cannot infer row counts/ordering or enumerate ids. The
 * {@code createdBy} field is the token subject ({@code oid}/{@code sub} claim) recorded when the row
 * was written, so a client reading an item can see which identity created it. The internal
 * {@code createdAt}/{@code updatedAt} timestamps are intentionally omitted from this instructional
 * projection; add them here if the UI needs to display them.
 *
 * @param id          the item's opaque public identifier (UUIDv7; not the internal numeric PK)
 * @param name        the item name
 * @param description optional description (may be null)
 * @param category    optional short category label such as "hardware"/"software" (may be null)
 * @param createdBy   the token subject (oid/sub) of the identity that created the item
 */
public record ItemDto(UUID id, String name, String description, String category, String createdBy) {
}
