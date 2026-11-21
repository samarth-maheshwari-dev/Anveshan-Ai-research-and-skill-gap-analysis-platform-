package dev.anveshan.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.anveshan.TestcontainersConfig;
import dev.anveshan.identity.infra.security.SessionUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** No endpoint has a key (the default without FREELLMAPI_KEY / GEMINI_API_KEY): the AI is OFFLINE. */
@SpringBootTest(properties = { "management.server.port=0", "anveshan.llm.endpoints[0].name=freellmapi",
		"anveshan.llm.endpoints[0].base-url=http://localhost:9/v1", "anveshan.llm.endpoints[0].model=auto",
		"anveshan.llm.endpoints[0].api-key=" })
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class GatewayOfflineIT {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@Test
	@DisplayName("T-16-10 no configured endpoint → status OFFLINE, research start → 422 NO_MODEL_FOR_SLOT, no run and no allowance used")
	void offline() throws Exception {
		UUID user = jdbc.sql("INSERT INTO app_user (email, email_verified) VALUES (:e, true) RETURNING id")
				.param("e", "off-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();

		JsonNode status = json.readTree(body(perform(user, get("/api/v1/ai/status"))));
		assertThat(status.path("state").asString()).isEqualTo("OFFLINE");
		assertThat(status.path("runsUsedToday").asInt()).isZero();

		var res = perform(user, post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
				.content("{\"topic\":\"Compare lost-and-found apps for college campuses\",\"depth\":\"QUICK\"}"));
		assertThat(res.getResponse().getStatus()).as(body(res)).isEqualTo(422);
		assertThat(json.readTree(body(res)).path("code").asString()).isEqualTo("NO_MODEL_FOR_SLOT");
		assertThat(jdbc.sql("SELECT count(*) FROM research_run WHERE owner_id = :o").param("o", user)
				.query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM llm_daily_usage WHERE owner_id = :o").param("o", user)
				.query(Long.class).single()).isZero();
	}

	MvcResult perform(UUID user, MockHttpServletRequestBuilder req) throws Exception {
		var token = new OAuth2AuthenticationToken(new SessionUser(user), List.of(new SimpleGrantedAuthority("ROLE_USER")),
				"google");
		return mvc.perform(req.with(authentication(token)).with(csrf())).andReturn();
	}

	static String body(MvcResult r) {
		return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

}
