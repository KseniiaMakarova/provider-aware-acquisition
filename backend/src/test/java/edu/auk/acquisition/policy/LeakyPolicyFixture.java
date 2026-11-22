package edu.auk.acquisition.policy;

import edu.auk.acquisition.dataset.RestrictedRecordReader;
import edu.auk.acquisition.prediction.PredictionClient;
import edu.auk.acquisition.provider.ProviderExecutor;

/**
 * Deliberately broken class (test sources only) used to prove that the architecture rules
 * reject a policy that reads complete records or calls providers or the ML service itself.
 */
class LeakyPolicyFixture {

	RestrictedRecordReader reader;

	ProviderExecutor executor;

	PredictionClient predictionClient;

}
