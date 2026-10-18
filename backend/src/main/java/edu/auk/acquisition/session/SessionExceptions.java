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

	/** The session has finished and cannot be advanced. */
	public static class SessionNotActiveException extends RuntimeException {

		public SessionNotActiveException(UUID id, SessionStatus status, String stopReason) {
			super("Session " + id + " is " + status + " (" + stopReason + ") and cannot be advanced");
		}

	}

	/** Another advance of the same session is running. */
	public static class AdvanceInProgressException extends RuntimeException {

		public AdvanceInProgressException(UUID id) {
			super("Another advance of session " + id + " is in progress; try again when it has finished");
		}

	}

	/** The claim expired and was taken over before this advance could save; nothing was saved. */
	public static class ClaimLostException extends RuntimeException {

		public ClaimLostException(UUID id) {
			super("The advance of session " + id + " took longer than its claim and was discarded");
		}

	}

}
