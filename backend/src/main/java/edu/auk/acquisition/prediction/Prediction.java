package edu.auk.acquisition.prediction;

/** Predicted default probability and the model and feature configuration that produced it. */
public record Prediction(double probability, String modelVersion, String featureConfigVersion) {

}
