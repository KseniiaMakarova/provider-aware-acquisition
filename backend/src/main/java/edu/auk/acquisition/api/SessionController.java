package edu.auk.acquisition.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.auk.acquisition.feature.FeatureConfiguration;
import edu.auk.acquisition.session.SessionHistory;
import edu.auk.acquisition.session.SessionService;

@RestController
@RequestMapping("/api/v1/sessions")
class SessionController {

	private final SessionService sessions;
	private final FeatureConfiguration config;

	SessionController(SessionService sessions, FeatureConfiguration config) {
		this.sessions = sessions;
		this.config = config;
	}

	/** Starts a session from the initial information and returns it with the initial prediction. */
	@PostMapping
	ResponseEntity<SessionResponse> create(@Valid @RequestBody CreateSessionRequest request) {
		SessionHistory history = sessions.create(request.recordId(), request.policyId());
		SessionResponse body = toResponse(history);
		return ResponseEntity.created(URI.create("/api/v1/sessions/" + body.sessionId())).body(body);
	}

	@GetMapping("/{id}")
	SessionResponse get(@PathVariable UUID id) {
		return toResponse(sessions.get(id));
	}

	private SessionResponse toResponse(SessionHistory history) {
		return SessionResponse.of(history, config.providerIds(), sessions.remainingProviders(history));
	}

}
