package dev.anveshan.byok.app;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.byok.domain.AiConnection;
import dev.anveshan.byok.domain.ByokEnums.ConnectionKind;
import dev.anveshan.byok.domain.ByokEnums.ConnectionStatus;
import dev.anveshan.byok.infra.AiConnectionRepository;
import dev.anveshan.byok.infra.SlotStore;
import dev.anveshan.shared.web.ApiException;

/** Which connection + model serves each task slot, with up to 5 fallbacks (PUT /slots). */
@Service
public class SlotService {

	public static final int MAX_ROUTES_PER_SLOT = 5;

	public record RouteChoice(UUID connectionId, String model) {
	}

	private final SlotStore store;
	private final AiConnectionRepository connections;

	SlotService(SlotStore store, AiConnectionRepository connections) {
		this.store = store;
		this.connections = connections;
	}

	@Transactional(readOnly = true)
	public Map<TaskSlot, List<RouteChoice>> get(UUID owner) {
		Map<TaskSlot, List<RouteChoice>> out = empty();
		for (var row : store.all(owner)) {
			out.get(row.slot()).add(new RouteChoice(row.connectionId(), row.model()));
		}
		return out;
	}

	/** Full replace. Every connection must belong to the caller (else 404) and be a VERIFIED LLM key. */
	@Transactional
	public Map<TaskSlot, List<RouteChoice>> put(UUID owner, Map<TaskSlot, List<RouteChoice>> request) {
		if (request == null) {
			throw ApiException.badRequest("Body is required.");
		}
		Set<UUID> ids = new HashSet<>();
		for (var e : request.entrySet()) {
			if (e.getKey() == null) {
				throw ApiException.badRequest("Unknown slot.");
			}
			List<RouteChoice> routes = e.getValue() == null ? List.of() : e.getValue();
			if (routes.size() > MAX_ROUTES_PER_SLOT) {
				throw ApiException.badRequest(e.getKey() + ": at most " + MAX_ROUTES_PER_SLOT + " models per slot.");
			}
			for (RouteChoice r : routes) {
				if (r == null || r.connectionId() == null || r.model() == null || r.model().isBlank()
						|| r.model().length() > 200) {
					throw ApiException.badRequest(e.getKey() + ": every entry needs a connectionId and a model.");
				}
				ids.add(r.connectionId());
			}
		}
		Map<UUID, AiConnection> owned = connections.findByOwnerIdAndIdIn(owner, ids).stream()
				.collect(Collectors.toMap(AiConnection::getId, Function.identity()));
		if (owned.size() != ids.size()) {
			throw ApiException.notFound(); // someone else's (or no) connection: same answer, no leak
		}
		for (AiConnection c : owned.values()) {
			if (c.getKind() != ConnectionKind.LLM || c.getStatus() != ConnectionStatus.VERIFIED) {
				throw ApiException.unprocessable("VALIDATION_FAILED", "Connection not ready",
						"\"" + c.getLabel() + "\" must be a tested (verified) AI key before it can be used.");
			}
		}

		List<SlotStore.Row> rows = new ArrayList<>();
		for (var e : request.entrySet()) {
			List<RouteChoice> routes = e.getValue() == null ? List.of() : e.getValue();
			for (int i = 0; i < routes.size(); i++) {
				rows.add(new SlotStore.Row(e.getKey(), i, routes.get(i).connectionId(), routes.get(i).model().strip()));
			}
		}
		store.replaceAll(owner, rows);
		return get(owner);
	}

	/** The models this user's task routing sends to one connection — what a re-test should check first. */
	@Transactional(readOnly = true)
	public List<String> modelsRoutedTo(UUID owner, UUID connectionId) {
		return store.modelsFor(owner, connectionId);
	}

	/**
	 * A-FR-6: after the first successful key test, route every slot to that connection + model, but
	 * only if the user hasn't set up any routing yet. Returns true when defaults were written.
	 */
	@Transactional
	public boolean fillDefaultsIfEmpty(UUID owner, UUID connectionId, String model) {
		if (model == null || model.isBlank() || !store.all(owner).isEmpty()) {
			return false;
		}
		List<SlotStore.Row> rows = new ArrayList<>();
		for (TaskSlot s : TaskSlot.values()) {
			rows.add(new SlotStore.Row(s, 0, connectionId, model.strip()));
		}
		store.replaceAll(owner, rows);
		return true;
	}

	private static Map<TaskSlot, List<RouteChoice>> empty() {
		Map<TaskSlot, List<RouteChoice>> m = new EnumMap<>(TaskSlot.class);
		for (TaskSlot s : TaskSlot.values()) {
			m.put(s, new ArrayList<>());
		}
		return m;
	}

}
