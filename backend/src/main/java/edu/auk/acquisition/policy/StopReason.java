package edu.auk.acquisition.policy;

/** Why a session stopped acquiring (FR-8). */
public enum StopReason {

	/** Every configured provider was acquired successfully. */
	ALL_PROVIDERS_ACQUIRED,

	/** No provider is left that is available and not yet attempted (some failed or are unavailable). */
	NO_FEASIBLE_PROVIDERS,

	/** The policy judged that no further acquisition is worthwhile (adaptive policies, later phases). */
	INSUFFICIENT_EXPECTED_BENEFIT

}
