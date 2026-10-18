package edu.auk.acquisition.feature;

import java.util.List;

/** A logical provider and the exact features one acquisition from it reveals. */
public record ProviderGroup(String id, String description, List<String> features) {

	public ProviderGroup {
		features = features == null ? null : List.copyOf(features);
	}

}
