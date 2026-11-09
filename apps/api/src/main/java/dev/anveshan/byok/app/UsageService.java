package dev.anveshan.byok.app;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import dev.anveshan.shared.web.ApiException;

/** Per-connection daily usage from {@code route_quota} (GET /usage, A-FR-7). Owner-scoped in SQL. */
@Service
public class UsageService {

	public record Day(LocalDate day, int requests, int tokensIn, int tokensOut) {
	}

	public record ConnectionUsage(UUID connectionId, List<Day> days) {
	}

	public record Usage(List<ConnectionUsage> byConnection) {
	}

	private final JdbcClient jdbc;

	UsageService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Usage usage(UUID owner, LocalDate from, LocalDate to) {
		if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 92) {
			throw ApiException.badRequest("Use from ≤ to and a range of at most 92 days.");
		}
		record Row(UUID connectionId, LocalDate day, int requests, int tokensIn, int tokensOut) {
		}
		List<Row> rows = jdbc.sql("""
				SELECT q.connection_id, q.day, sum(q.requests), sum(q.tokens_in), sum(q.tokens_out)
				FROM route_quota q JOIN ai_connection c ON c.id = q.connection_id
				WHERE c.owner_id = :o AND q.day BETWEEN :from AND :to
				GROUP BY q.connection_id, q.day
				ORDER BY q.connection_id, q.day
				""")
				.param("o", owner).param("from", from).param("to", to)
				.query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getInt(3),
						rs.getInt(4), rs.getInt(5)))
				.list();
		Map<UUID, List<Day>> grouped = new LinkedHashMap<>();
		for (Row r : rows) {
			grouped.computeIfAbsent(r.connectionId(), k -> new ArrayList<>())
					.add(new Day(r.day(), r.requests(), r.tokensIn(), r.tokensOut()));
		}
		return new Usage(grouped.entrySet().stream().map(e -> new ConnectionUsage(e.getKey(), e.getValue())).toList());
	}

}
