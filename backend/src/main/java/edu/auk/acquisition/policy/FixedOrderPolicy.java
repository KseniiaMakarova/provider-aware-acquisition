package edu.auk.acquisition.policy;

import java.util.List;
import java.util.Optional;

import edu.auk.acquisition.provider.ProviderMetadata;

/**
 * Queries providers in one fixed order, the same for every record, skipping providers that are
 * unavailable or already attempted. It has no stopping rule of its own: it stops only when no
 * feasible provider remains (budgeted variants are planned for the evaluation phase).
 */
public class FixedOrderPolicy implements AcquisitionPolicy {

	public static final String ID = "FIXED_ORDER";

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
	public AcquisitionDecision selectNext(AcquisitionContext context) {
		Optional<String> next = order.stream()
			.filter(id -> context.feasibleProviders().stream().map(ProviderMetadata::id).anyMatch(id::equals))
			.findFirst();
		if (next.isPresent()) {
			return new AcquisitionDecision.Acquire(next.get(),
					"Next feasible provider in fixed order " + order);
		}
		if (context.acquiredProviders().containsAll(context.configuredProviders())) {
			return new AcquisitionDecision.Stop(StopReason.ALL_PROVIDERS_ACQUIRED, "All configured providers acquired");
		}
		return new AcquisitionDecision.Stop(StopReason.NO_FEASIBLE_PROVIDERS,
				"No available provider left that has not been attempted");
	}

}
