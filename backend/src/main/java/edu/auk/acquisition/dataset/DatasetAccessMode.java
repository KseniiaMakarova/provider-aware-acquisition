package edu.auk.acquisition.dataset;

/** Which split of feature records the backend serves to acquisition sessions. */
public enum DatasetAccessMode {

	/** Validation records: development sessions, debugging, demonstrations and policy tuning. */
	DEVELOPMENT("validation"),

	/** Test records: final evaluation only, after model and policy decisions are fixed. */
	FINAL_EVALUATION("test");

	/** Spring profile that must be active to use {@link #FINAL_EVALUATION}. */
	public static final String FINAL_EVALUATION_PROFILE = "final-evaluation";

	private final String split;

	DatasetAccessMode(String split) {
		this.split = split;
	}

	/** Name of the dataset split and of its feature file, {@code <split>.csv}. */
	public String split() {
		return split;
	}

}
