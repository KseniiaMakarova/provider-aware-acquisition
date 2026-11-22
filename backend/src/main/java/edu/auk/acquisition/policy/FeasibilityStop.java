package edu.auk.acquisition.policy;

import java.util.function.Function;

/** The stop every policy makes when no provider can be queried any more. */
final class FeasibilityStop {

	private FeasibilityStop() {
	}

	/**
	 * @param details builds the decision details for the stop
	 * @return the stop decision; call only when no provider is feasible
	 */
	static AcquisitionDecision.Stop of(AcquisitionContext context, Function<String, DecisionDetails> details) {
		if (context.acquiredProviders().containsAll(context.configuredProviders())) {
			return new AcquisitionDecision.Stop(StopReason.ALL_PROVIDERS_ACQUIRED, "All configured providers acquired",
					details.apply(DecisionDetails.STOP));
		}
		return new AcquisitionDecision.Stop(StopReason.NO_FEASIBLE_PROVIDERS,
				"No available provider left that has not been attempted", details.apply(DecisionDetails.STOP));
	}

}
