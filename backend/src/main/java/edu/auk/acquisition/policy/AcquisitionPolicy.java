package edu.auk.acquisition.policy;

/**
 * Chooses the next provider to query or decides to stop (FR-7, FR-8, FR-10). A policy never
 * calls providers or stores anything; the orchestrator does that.
 */
public interface AcquisitionPolicy {

	String id();

	AcquisitionDecision selectNext(AcquisitionContext context);

}
