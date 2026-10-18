package edu.auk.acquisition.policy;

import edu.auk.acquisition.dataset.RestrictedRecordReader;
import edu.auk.acquisition.provider.ProviderExecutor;

/**
 * Deliberately broken class (test sources only) used to prove that the architecture rules
 * reject a policy that reads complete records or calls providers itself.
 */
class LeakyPolicyFixture {

	RestrictedRecordReader reader;

	ProviderExecutor executor;

}
