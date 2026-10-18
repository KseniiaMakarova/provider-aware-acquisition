package edu.auk.acquisition.api;

/** Error body of every failed request. */
public record ApiError(String error, String message) {

}
