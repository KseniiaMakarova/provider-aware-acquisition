package edu.auk.acquisition.policy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import edu.auk.acquisition.provider.ProviderMetadata;

/**
 * Queries providers in one fixed order, the same for every record, skipping providers that are
 * unavailable or already attempted. It has no stopping rule of its own: it stops only when no
 * feasible provider remains (budgeted variants are planned for the evaluation phase).
 */
public class FixedOrderPolicy implements AcquisitionPolicy {

	public static final String ID = "FIXED_ORDER";

	static final String VERSION = "fixed-order-v1";

	private final List<String> order;

	/** @param order provider IDs in the order they are queried */
	public FixedOrderPolicy(List<String> order) {
		this.order = List.copyOf(order);
	}

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
		return "Queries providers in one fixed order " + order + ", skipping unavailable and attempted providers;"
				+ " stops when none is left";
	}

	@Override
	public Map<String, Object> parameters() {
		return Map.of("order", order);
	}

	@Override
	public AcquisitionDecision selectNext(AcquisitionContext context) {
		Optional<String> next = order.stream()
			.filter(id -> context.feasibleProviders().stream().map(ProviderMetadata::id).anyMatch(id::equals))
			.findFirst();
		if (next.isPresent()) {
			return new AcquisitionDecision.Acquire(next.get(), "Next feasible provider in fixed order " + order,
					details(context, DecisionDetails.ACQUIRE, next.get()));
		}
		return FeasibilityStop.of(context, action -> details(context, action, null));
	}

	private DecisionDetails details(AcquisitionContext context, String action, String selected) {
		return new DecisionDetails(ID, VERSION, parameters(), action, null,
				DecisionDetails.unevaluated(context, selected));
	}

}
