package edu.auk.acquisition.provider;

/**
 * A provider call was not allowed (unknown, unavailable or already attempted provider) or a
 * provider returned something other than its declared features. Both indicate a bug, not a
 * simulated provider failure.
 */
public class ProviderContractException extends RuntimeException {

	public ProviderContractException(String message) {
		super(message);
	}

}
