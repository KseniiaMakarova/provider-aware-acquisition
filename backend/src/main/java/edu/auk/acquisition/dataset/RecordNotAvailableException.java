package edu.auk.acquisition.dataset;

/** The record is not part of the split served in the current access mode. */
public class RecordNotAvailableException extends RuntimeException {

	private final String recordId;

	public RecordNotAvailableException(String recordId, DatasetAccessMode mode) {
		super("Record " + recordId + " is not available in access mode " + mode + " (" + mode.split() + " split)");
		this.recordId = recordId;
	}

	public String recordId() {
		return recordId;
	}

}
