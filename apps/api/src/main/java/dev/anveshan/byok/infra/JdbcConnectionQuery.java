package dev.anveshan.byok.infra;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.ConnectionQuery;

@Component
class JdbcConnectionQuery implements ConnectionQuery {

	private final JdbcClient jdbc;

	JdbcConnectionQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean hasVerifiedConnection(UUID ownerId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ai_connection WHERE owner_id = :owner AND status = 'VERIFIED')")
				.param("owner", ownerId)
				.query(Boolean.class)
				.single();
	}

}
