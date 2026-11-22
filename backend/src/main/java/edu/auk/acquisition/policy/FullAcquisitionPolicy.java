package edu.auk.acquisition.policy;

/**
 * Full-acquisition reference (ER-2): queries every feasible provider, in configuration order,
 * until none is left. It uses the same model and session lifecycle as the other policies, so its
 * sessions show what acquiring everything available costs and predicts.
 *
 * <p>Without budgets it acquires the same providers as {@link FixedOrderPolicy} with the default
 * order; it is kept as a separate strategy because it is the reference, not a baseline.
 */
public class FullAcquisitionPolicy implements AcquisitionPolicy {

	public static final String ID = "FULL_ACQUISITION";

	static final String VERSION = "full-acquisition-v1";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String version() {
		return VERSION;
	}

	@Override
	public String description() {
		return "Reference strategy: acquires every available provider, in configuration order";
	}

	@Override
	public AcquisitionDecision selectNext(AcquisitionContext context) {
		if (!context.feasibleProviders().isEmpty()) {
			String next = context.feasibleProviders().getFirst().id();
			return new AcquisitionDecision.Acquire(next, "Full acquisition: next feasible provider in configuration order",
					details(context, DecisionDetails.ACQUIRE, next));
		}
		return FeasibilityStop.of(context, action -> details(context, action, null));
	}

	private DecisionDetails details(AcquisitionContext context, String action, String selected) {
		return new DecisionDetails(ID, VERSION, parameters(), action, null,
				DecisionDetails.unevaluated(context, selected));
	}

}
