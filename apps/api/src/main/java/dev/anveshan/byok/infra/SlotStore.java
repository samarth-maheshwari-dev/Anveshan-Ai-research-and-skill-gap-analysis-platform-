package dev.anveshan.byok.infra;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.byok.domain.ByokEnums.AiProvider;

/** {@code slot_route} reads/writes. Every query is owner-scoped; routes join only the owner's connections. */
@Component
public class SlotStore {

	/** A stored route. */
	public record Row(TaskSlot slot, int position, UUID connectionId, String model) {
	}

	/** A route ready for the router: connection data needed to decrypt and call. */
	public record RouteRow(int position, String model, UUID connectionId, AiProvider provider, String baseUrl,
			byte[] keyCiphertext) {
	}

	private final JdbcClient jdbc;

	SlotStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Row> all(UUID owner) {
		return jdbc.sql("SELECT slot, position, connection_id, model FROM slot_route WHERE owner_id = :o ORDER BY slot, position")
				.param("o", owner)
				.query((rs, i) -> new Row(TaskSlot.valueOf(rs.getString(1)), rs.getInt(2), rs.getObject(3, UUID.class),
						rs.getString(4)))
				.list();
	}

	/** Models the owner routes to one connection, primary positions first (each model once). */
	public List<String> modelsFor(UUID owner, UUID connectionId) {
		return jdbc.sql("""
				SELECT model FROM slot_route
				WHERE owner_id = :o AND connection_id = :c
				GROUP BY model
				ORDER BY min(position), model
				""")
				.param("o", owner).param("c", connectionId)
				.query(String.class)
				.list();
	}

	/** Replaces all routes of the owner (call inside a transaction). */
	public void replaceAll(UUID owner, List<Row> rows) {
		jdbc.sql("DELETE FROM slot_route WHERE owner_id = :o").param("o", owner).update();
		for (Row r : rows) {
			jdbc.sql("INSERT INTO slot_route (owner_id, slot, position, connection_id, model) VALUES (:o, :s, :p, :c, :m)")
					.param("o", owner).param("s", r.slot().name()).param("p", (short) r.position())
					.param("c", r.connectionId()).param("m", r.model())
					.update();
		}
	}

	/** Usable routes for a slot, in fallback order: only the owner's VERIFIED LLM connections. */
	public List<RouteRow> usableRoutes(UUID owner, TaskSlot slot) {
		return jdbc.sql("""
				SELECT r.position, r.model, c.id, c.provider, c.base_url, c.key_ciphertext
				FROM slot_route r
				JOIN ai_connection c ON c.id = r.connection_id AND c.owner_id = r.owner_id
				WHERE r.owner_id = :o AND r.slot = :s AND c.status = 'VERIFIED' AND c.kind = 'LLM'
				ORDER BY r.position
				""")
				.param("o", owner).param("s", slot.name())
				.query((rs, i) -> new RouteRow(rs.getInt(1), rs.getString(2), rs.getObject(3, UUID.class),
						AiProvider.valueOf(rs.getString(4)), rs.getString(5), rs.getBytes(6)))
				.list();
	}

}
