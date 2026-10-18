package edu.auk.acquisition.session;

import java.util.Set;
import java.util.UUID;

/** Errors a client can cause when creating or reading sessions. */
public final class SessionExceptions {

	private SessionExceptions() {
	}

	/** The record is not available in the current dataset access mode. */
	public static class UnknownRecordException extends RuntimeException {

		public UnknownRecordException(String recordId, String split) {
			super("Record " + recordId + " is not available in the " + split + " split served in this access mode");
		}

	}

	public static class SessionNotFoundException extends RuntimeException {

		public SessionNotFoundException(UUID id) {
			super("Session " + id + " does not exist");
		}

	}

	public static class UnsupportedPolicyException extends RuntimeException {

		public UnsupportedPolicyException(String policyId, Set<String> supported) {
			super("Policy " + policyId + " is not supported; available: " + supported);
		}

	}

}
