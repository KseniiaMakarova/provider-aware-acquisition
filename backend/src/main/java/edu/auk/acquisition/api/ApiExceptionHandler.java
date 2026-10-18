package edu.auk.acquisition.api;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import edu.auk.acquisition.prediction.PredictionRejectedException;
import edu.auk.acquisition.prediction.PredictionUnavailableException;
import edu.auk.acquisition.session.SessionExceptions.SessionNotFoundException;
import edu.auk.acquisition.session.SessionExceptions.UnknownRecordException;
import edu.auk.acquisition.session.SessionExceptions.UnsupportedPolicyException;

/** Maps failures to HTTP statuses with a consistent {@code {error, message}} body. */
@RestControllerAdvice
class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
		String message = e.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(f -> f.getField() + " " + f.getDefaultMessage())
			.sorted()
			.collect(Collectors.joining("; "));
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException e) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
				"Request body is not valid JSON for this endpoint or contains unknown fields");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ApiError> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
		return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_REQUEST", "Use Content-Type: application/json");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ApiError> badPathValue(MethodArgumentTypeMismatchException e) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getName() + " has an invalid format");
	}

	@ExceptionHandler(UnsupportedPolicyException.class)
	ResponseEntity<ApiError> unsupportedPolicy(UnsupportedPolicyException e) {
		return error(HttpStatus.BAD_REQUEST, "UNSUPPORTED_POLICY", e.getMessage());
	}

	@ExceptionHandler(UnknownRecordException.class)
	ResponseEntity<ApiError> unknownRecord(UnknownRecordException e) {
		return error(HttpStatus.NOT_FOUND, "RECORD_NOT_AVAILABLE", e.getMessage());
	}

	@ExceptionHandler(SessionNotFoundException.class)
	ResponseEntity<ApiError> sessionNotFound(SessionNotFoundException e) {
		return error(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", e.getMessage());
	}

	@ExceptionHandler(PredictionUnavailableException.class)
	ResponseEntity<ApiError> mlUnavailable(PredictionUnavailableException e) {
		log.warn("ML service unavailable: {}", e.getMessage());
		return error(HttpStatus.SERVICE_UNAVAILABLE, "ML_SERVICE_UNAVAILABLE",
				"The prediction service is unavailable; no session was created");
	}

	@ExceptionHandler(PredictionRejectedException.class)
	ResponseEntity<ApiError> mlRejected(PredictionRejectedException e) {
		log.error("ML service rejected a request from the backend: {}", e.getMessage());
		return error(HttpStatus.BAD_GATEWAY, "ML_SERVICE_REJECTED", e.getMessage());
	}

	private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new ApiError(code, message));
	}

}
