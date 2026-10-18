package edu.auk.acquisition.provider;

/** What a provider needs for one call: only the key of the record being acquired. */
public record ProviderRequest(String recordId) {

}
