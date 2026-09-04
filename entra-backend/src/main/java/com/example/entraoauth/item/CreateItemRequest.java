package com.example.entraoauth.item;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for creating a new {@code item} via the Admin-only write endpoint
 * ({@code POST /entra-backend/items}).
 *
 * <p><strong>Validated input at the API boundary.</strong> The {@code name} field is annotated with
 * {@link NotBlank} (from {@code jakarta.validation}), so a request whose {@code name} is {@code null},
 * empty, or only whitespace is rejected with a {@code 400 Bad Request} before the controller body
 * runs &mdash; provided the controller parameter is annotated {@code @Valid}. This keeps malformed
 * input from ever reaching the service or the database, where {@code name} is {@code NOT NULL}.
 *
 * <p><strong>What the client does NOT send.</strong> Note there is no {@code createdBy} field here.
 * The identity that owns a created row is <em>not</em> client-supplied &mdash; the service layer
 * derives {@code createdBy} from the token subject ({@code oid}/{@code sub}) of the authenticated
 * principal. Letting the client set it would let a caller forge provenance, so it is deliberately
 * absent from the request contract. Timestamps are likewise managed server-side.
 *
 * <p><strong>Optional category.</strong> The {@code category} field is a short, optional label
 * (e.g. {@code "hardware"}). It has no {@link NotBlank} constraint because the schema column
 * {@code item.category} is nullable and the feature makes category optional; the client may omit it
 * or send {@code null}.
 *
 * @param name        the required, non-blank item name
 * @param description optional free-text description (may be null)
 * @param category    optional short category label (may be null)
 */
public record CreateItemRequest(@NotBlank String name, String description, String category) {
}
