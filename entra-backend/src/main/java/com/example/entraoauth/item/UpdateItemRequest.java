package com.example.entraoauth.item;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for updating an existing {@code item} via the Admin-only write endpoint
 * ({@code PUT /entra-backend/items/{id}}).
 *
 * <p><strong>Validated input at the API boundary.</strong> As with creation, {@code name} is
 * annotated {@link NotBlank} (from {@code jakarta.validation}) so an update cannot clear the name to
 * {@code null}, empty, or whitespace-only; such a request is rejected with {@code 400 Bad Request}
 * before the controller body runs (given a {@code @Valid} parameter). This preserves the schema's
 * {@code NOT NULL} guarantee on {@code item.name}.
 *
 * <p><strong>Identity is not client-supplied.</strong> Like {@link CreateItemRequest}, this record
 * carries no {@code createdBy} field. The original creating identity (the token subject
 * {@code oid}/{@code sub}) recorded when the row was first written is provenance data and is not
 * overwritten from client input; the service layer manages identity and the {@code updated_at}
 * timestamp when applying the change.
 *
 * @param name        the required, non-blank item name
 * @param description optional free-text description (may be null)
 */
public record UpdateItemRequest(@NotBlank String name, String description) {
}
