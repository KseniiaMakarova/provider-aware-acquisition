package edu.auk.acquisition.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/sessions}. Unknown fields are rejected.
 *
 * @param scenarioId provider scenario version; optional, the default scenario if omitted
 */
public record CreateSessionRequest(@NotBlank @Size(max = 64) String recordId, @NotBlank @Size(max = 64) String policyId,
		@Size(min = 1, max = 64) String scenarioId) {
}
