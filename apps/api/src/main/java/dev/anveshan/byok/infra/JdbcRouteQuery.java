package dev.anveshan.byok.infra;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.RouteQuery;
import dev.anveshan.byok.api.TaskSlot;

@Component
class JdbcRouteQuery implements RouteQuery {

	private final JdbcClient jdbc;
	private final Clock clock;

	JdbcRouteQuery(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	public Set<TaskSlot> readySlots(UUID ownerId) {
		Set<TaskSlot> out = EnumSet.noneOf(TaskSlot.class);
		jdbc.sql("""
				SELECT DISTINCT r.slot FROM slot_route r JOIN ai_connection c ON c.id = r.connection_id
				 WHERE r.owner_id = :owner AND c.owner_id = :owner AND c.status = 'VERIFIED'""")
				.param("owner", ownerId).query(String.class).list().forEach(s -> out.add(TaskSlot.valueOf(s)));
		return out;
	}

	@Override
	public List<FirstRoute> firstRoutes(UUID ownerId) {
		LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
		return jdbc.sql("""
				SELECT DISTINCT ON (r.slot) r.slot, r.connection_id, c.label, c.provider, r.model,
				       COALESCE(q.requests, 0) AS used, q.remaining_requests_hint AS remaining
				  FROM slot_route r
				  JOIN ai_connection c ON c.id = r.connection_id AND c.owner_id = :owner AND c.status = 'VERIFIED'
				  LEFT JOIN route_quota q ON q.connection_id = r.connection_id AND q.model = r.model AND q.day = :today
				 WHERE r.owner_id = :owner
				 ORDER BY r.slot, r.position""")
				.param("owner", ownerId).param("today", Date.valueOf(today))
				.query((rs, n) -> new FirstRoute(TaskSlot.valueOf(rs.getString("slot")),
						rs.getObject("connection_id", UUID.class), rs.getString("label"), rs.getString("provider"),
						rs.getString("model"), rs.getInt("used"), (Integer) rs.getObject("remaining")))
				.list().stream().sorted((a, b) -> a.slot().compareTo(b.slot())).toList();
	}

}
