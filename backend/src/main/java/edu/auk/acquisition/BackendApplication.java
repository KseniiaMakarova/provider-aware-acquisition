package edu.auk.acquisition;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendApplication {

	public static void main(String[] args) {
		// Timestamps are stored and compared in UTC, independent of the host locale. This also
		// avoids legacy JVM zone IDs (e.g. "Europe/Kiev") that PostgreSQL 18 rejects at connect time.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(BackendApplication.class, args);
	}

}
