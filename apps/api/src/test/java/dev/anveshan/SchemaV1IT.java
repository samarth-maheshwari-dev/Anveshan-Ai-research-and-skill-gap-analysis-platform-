package dev.anveshan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.byok.domain.AiConnection;
import dev.anveshan.byok.domain.ByokEnums.AiProvider;
import dev.anveshan.byok.domain.ByokEnums.ConnectionKind;
import dev.anveshan.byok.domain.ByokEnums.LlmCallStatus;
import dev.anveshan.byok.domain.LlmCall;
import dev.anveshan.byok.domain.RouteQuota;
import dev.anveshan.byok.domain.SlotRoute;
import dev.anveshan.identity.domain.AppUser;
import dev.anveshan.identity.domain.AuthProvider;
import dev.anveshan.identity.domain.PersonalToken;
import dev.anveshan.identity.domain.UserIdentity;
import jakarta.persistence.EntityManager;

/**
 * P04 schema v1 (identity + byok). The Spring context itself only starts if Flyway
 * migrated an empty database and Hibernate {@code validate} found no mismatch.
 */
@SpringBootTest(properties = "management.server.port=0")
@Import(TestcontainersConfig.class)
class SchemaV1IT {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	EntityManager em;

	@Autowired
	TransactionTemplate tx;

	static String uniqueEmail(String name) {
		return name + "+" + UUID.randomUUID() + "@example.com";
	}

	UUID insertUser(String email) {
		return jdbc.sql("INSERT INTO app_user (email) VALUES (:email) RETURNING id")
				.param("email", email).query(UUID.class).single();
	}

	@Test
	@DisplayName("T-04-01 Flyway migrated an empty DB: all 4 versions recorded and successful")
	void flywayHistory() {
		List<String> versions = jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
				.query(String.class).list();

		assertThat(versions).containsExactly("202611080900", "202611080901", "202611080902", "202611080910");
		assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE NOT success").query(Long.class).single())
				.isZero();
		assertThat(jdbc.sql("SELECT extname FROM pg_extension").query(String.class).list())
				.contains("vector", "pg_trgm");
		assertThat(jdbc.sql("""
				SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
				""").query(String.class).list())
				.contains("app_user", "user_identity", "personal_token", "spring_session",
						"spring_session_attributes", "event_publication", "ai_connection", "slot_route",
						"route_quota", "llm_call");
	}

	@Test
	@DisplayName("T-04-02 Hibernate validate passed and every entity round-trips through the DB")
	void entitiesRoundTrip() {
		UUID[] ids = tx.execute(s -> {
			var user = new AppUser(uniqueEmail("rt"), true, "Round Trip");
			em.persist(user);
			em.persist(new UserIdentity(user.getId(), AuthProvider.GITHUB, "12345", "octocat"));
			em.persist(new PersonalToken(user.getId(), "laptop", "anv_abcd", new byte[32], List.of("mcp.read"), null));
			var conn = new AiConnection(user.getId(), ConnectionKind.LLM, AiProvider.GROQ, "My Groq",
					"https://api.groq.com/openai/v1", new byte[] { 1, 2, 3 }, "x9Q2");
			em.persist(conn);
			em.persist(new SlotRoute(new SlotRoute.Key(user.getId(), TaskSlot.PLAN, (short) 0), conn.getId(), "llama"));
			em.persist(new RouteQuota(new RouteQuota.Key(conn.getId(), "llama", LocalDate.of(2026, 11, 9))));
			em.persist(new LlmCall(user.getId(), TaskSlot.PLAN, conn.getId(), "llama", 420, LlmCallStatus.OK)
					.withTokens(100, 50));
			return new UUID[] { user.getId(), conn.getId() };
		});
		em.clear();

		var conn = em.find(AiConnection.class, ids[1]);
		assertThat(conn.getProvider()).isEqualTo(AiProvider.GROQ);
		assertThat(conn.getKeyLast4()).isEqualTo("x9Q2");
		assertThat(conn.getCreatedAt()).isNotNull();
		assertThat(conn.toString()).doesNotContain("[1, 2, 3]");
		assertThat(jdbc.sql("SELECT scopes FROM personal_token WHERE owner_id = :id").param("id", ids[0])
				.query(String.class).single()).isEqualTo("{mcp.read}");
		assertThat(jdbc.sql("SELECT slot FROM slot_route WHERE owner_id = :id").param("id", ids[0])
				.query(String.class).single()).isEqualTo("PLAN");
	}

	@Test
	@DisplayName("T-04-03 two live users with emails differing only in case → second insert fails (ux_app_user_email)")
	void emailUniqueIgnoringCase() {
		String email = uniqueEmail("sneha");
		insertUser(email);

		assertThatThrownBy(() -> insertUser(email.toUpperCase()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ux_app_user_email");
	}

	@Test
	@DisplayName("T-04-04 same email is allowed again once the first user has deleted_at set")
	void emailFreedAfterSoftDelete() {
		String email = uniqueEmail("karan");
		UUID first = insertUser(email);
		jdbc.sql("UPDATE app_user SET deleted_at = :now WHERE id = :id")
				.param("now", java.sql.Timestamp.from(Instant.now())).param("id", first).update();

		UUID second = insertUser(email.toUpperCase());

		assertThat(second).isNotEqualTo(first);
	}

	@Test
	@DisplayName("T-04-05 deleting an app_user cascades to identities, tokens, connections, routes, quotas, calls")
	void deleteCascades() {
		UUID user = insertUser(uniqueEmail("riya"));
		jdbc.sql("INSERT INTO user_identity (user_id, provider, provider_subject) VALUES (:u, 'GOOGLE', :s)")
				.param("u", user).param("s", UUID.randomUUID().toString()).update();
		jdbc.sql("INSERT INTO personal_token (owner_id, name, token_prefix, token_hash) VALUES (:u, 't', 'anv_1234', :h)")
				.param("u", user).param("h", UUID.randomUUID().toString().getBytes()).update();
		UUID conn = jdbc.sql("""
				INSERT INTO ai_connection (owner_id, kind, provider, label, base_url, key_ciphertext, key_last4)
				VALUES (:u, 'LLM', 'GEMINI', 'g', 'https://example.test', '\\x00'::bytea, 'abcd') RETURNING id
				""").param("u", user).query(UUID.class).single();
		jdbc.sql("INSERT INTO slot_route (owner_id, slot, position, connection_id, model) VALUES (:u, 'ASK', 0, :c, 'm')")
				.param("u", user).param("c", conn).update();
		jdbc.sql("INSERT INTO route_quota (connection_id, model, day) VALUES (:c, 'm', current_date)")
				.param("c", conn).update();
		jdbc.sql("INSERT INTO llm_call (owner_id, slot, model, latency_ms, status) VALUES (:u, 'ASK', 'm', 1, 'OK')")
				.param("u", user).update();

		jdbc.sql("DELETE FROM app_user WHERE id = :u").param("u", user).update();

		for (String sql : List.of(
				"SELECT count(*) FROM user_identity WHERE user_id = :u",
				"SELECT count(*) FROM personal_token WHERE owner_id = :u",
				"SELECT count(*) FROM ai_connection WHERE owner_id = :u",
				"SELECT count(*) FROM slot_route WHERE owner_id = :u",
				"SELECT count(*) FROM llm_call WHERE owner_id = :u")) {
			assertThat(jdbc.sql(sql).param("u", user).query(Long.class).single()).as(sql).isZero();
		}
		assertThat(jdbc.sql("SELECT count(*) FROM route_quota WHERE connection_id = :c").param("c", conn)
				.query(Long.class).single()).isZero();
	}

	@Test
	@DisplayName("T-04-05 CHECK constraints reject values outside the Java enums")
	void checkConstraints() {
		UUID user = insertUser(uniqueEmail("ananya"));

		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO ai_connection (owner_id, kind, provider, label, base_url, key_ciphertext, key_last4)
				VALUES (:u, 'LLM', 'NOT_A_PROVIDER', 'x', 'https://x.test', '\\x00'::bytea, 'abcd')
				""").param("u", user).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO llm_call (owner_id, slot, model, latency_ms, status) VALUES (:u, 'CHAT', 'm', 1, 'OK')
				""").param("u", user).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

}
