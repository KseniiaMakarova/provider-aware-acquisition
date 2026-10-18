package edu.auk.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BackendApplicationTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MockMvc mockMvc;

	@Test
	void connectsToPinnedPostgresVersion() {
		String version = jdbc.queryForObject("SHOW server_version", String.class);
		String expectedMajor = TestcontainersConfiguration.POSTGRES_IMAGE.split(":")[1].split("\\.")[0];
		assertThat(version).startsWith(expectedMajor + ".");
	}

	@Test
	void flywayAppliesBaselineMigration() {
		Boolean success = jdbc.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);
		assertThat(success).isTrue();
	}

	@Test
	void healthEndpointReportsUpWithDatabase() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

}
