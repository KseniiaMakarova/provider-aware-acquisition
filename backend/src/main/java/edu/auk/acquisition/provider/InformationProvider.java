package edu.auk.acquisition.provider;

import java.util.List;

/**
 * A logical information source that reveals one group of features per call (FR-1).
 *
 * <p>Implementations are simulated for the Alpha. A remote HTTP provider can implement the same
 * interface later without changes to the registry, executor or policies.
 */
public interface InformationProvider {

	String id();

	/** The exact features one successful call returns. */
	List<String> suppliedFeatures();

	/** Called only by {@link ProviderExecutor}, never by a policy. */
	ProviderResult acquire(ProviderRequest request);

}
