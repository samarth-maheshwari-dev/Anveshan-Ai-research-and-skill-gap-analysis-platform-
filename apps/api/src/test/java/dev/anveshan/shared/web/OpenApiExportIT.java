package dev.anveshan.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import dev.anveshan.TestcontainersConfig;
import dev.anveshan.identity.infra.security.SessionUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exports the OpenAPI spec to {@code target/openapi.json} (TECHSPEC §8.7). The web app generates
 * its TypeScript types from it; CI fails when the committed copy in apps/web is stale.
 */
@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OpenApiExportIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Test
	@DisplayName("T-08 OpenAPI spec is exported with every P05–P07 endpoint and typed bodies")
	void export() throws Exception {
		var token = new OAuth2AuthenticationToken(new SessionUser(UUID.randomUUID()),
				List.of(new SimpleGrantedAuthority("ROLE_USER")), "google");
		String spec = new String(mvc.perform(get("/v3/api-docs").with(authentication(token))).andReturn().getResponse()
				.getContentAsByteArray(), StandardCharsets.UTF_8);

		JsonNode root = json.readTree(spec);
		assertThat(root.path("openapi").asString()).startsWith("3.1");
		JsonNode paths = root.path("paths");
		for (String p : List.of("/api/v1/me", "/api/v1/connections", "/api/v1/connections/{id}",
				"/api/v1/connections/{id}/test", "/api/v1/connections/{id}/models", "/api/v1/slots", "/api/v1/usage",
				"/api/v1/health")) {
			assertThat(paths.has(p)).as(p).isTrue();
		}
		JsonNode schemas = root.path("components").path("schemas");
		assertThat(schemas.has("MeView")).isTrue();
		assertThat(schemas.has("ConnectionView")).isTrue();
		assertThat(schemas.has("TestResult")).isTrue();
		assertThat(spec).as("no secrets or hosts leak into the spec").doesNotContain("localhost").doesNotContain("keyCiphertext");

		Path out = Path.of("target", "openapi.json");
		Files.createDirectories(out.getParent());
		Files.writeString(out, json.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
	}

}
