package dev.anveshan.byok.app;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.anveshan.byok.app.ProviderModels.ModelInfo;
import dev.anveshan.byok.app.ProviderModels.ProviderException;
import dev.anveshan.byok.domain.AiConnection;
import dev.anveshan.byok.domain.ByokEnums.AiProvider;
import dev.anveshan.byok.domain.ByokEnums.ConnectionKind;
import dev.anveshan.byok.domain.ByokEnums.ConnectionStatus;
import dev.anveshan.byok.domain.ProviderPreset;
import dev.anveshan.byok.infra.AiConnectionRepository;
import dev.anveshan.byok.infra.ByokProperties;
import dev.anveshan.shared.security.OutboundUrlGuard;
import dev.anveshan.shared.security.Secret;
import dev.anveshan.shared.web.ApiException;
import jakarta.persistence.EntityManager;

/** AI connections: add, list, delete, test, list models (Module A, TECHSPEC §8.5 / §10.7). */
@Service
public class ConnectionService {

	public record CreateCommand(ConnectionKind kind, AiProvider provider, String label, String apiKey, String baseUrl) {
	}

	/** What the API returns — never the key, only its last 4 characters (AC-A2). */
	public record ConnectionView(UUID id, ConnectionKind kind, AiProvider provider, String label, String baseUrl,
			String keyLast4, ConnectionStatus status, String statusDetail, Instant lastTestedAt, Instant createdAt) {

		static ConnectionView of(AiConnection c) {
			return new ConnectionView(c.getId(), c.getKind(), c.getProvider(), c.getLabel(), c.getBaseUrl(),
					c.getKeyLast4(), c.getStatus(), c.getStatusDetail(), c.getLastTestedAt(), c.getCreatedAt());
		}

	}

	/**
	 * {@code sampleModel}: the model that answered (the backend picks it when none was asked for).
	 * {@code defaultSlotsSet}: this test filled the user's empty task slots with that model (A-FR-6).
	 */
	public record TestResult(ConnectionStatus status, long latencyMs, String sampleModel, String note,
			boolean defaultSlotsSet) {
	}

	private final AiConnectionRepository connections;
	private final EntityManager em;
	private final KeyVault vault;
	private final ByokProperties props;
	private final OutboundUrlGuard guard;
	private final ConnectionTester tester;
	private final ModelCatalog catalog;

	ConnectionService(AiConnectionRepository connections, EntityManager em, KeyVault vault, ByokProperties props,
			OutboundUrlGuard guard, ConnectionTester tester, ModelCatalog catalog) {
		this.connections = connections;
		this.em = em;
		this.vault = vault;
		this.props = props;
		this.guard = guard;
		this.tester = tester;
		this.catalog = catalog;
	}

	@Transactional
	public ConnectionView create(UUID owner, CreateCommand cmd) {
		if (cmd.kind() == null || cmd.provider() == null) {
			throw ApiException.badRequest("kind and provider are required.");
		}
		boolean search = cmd.provider() == AiProvider.TAVILY;
		if (search != (cmd.kind() == ConnectionKind.SEARCH)) {
			throw ApiException.badRequest("Tavily is a SEARCH key; every other provider is an LLM key.");
		}
		String label = cmd.label() == null ? "" : cmd.label().strip();
		if (label.isEmpty() || label.length() > 80) {
			throw ApiException.badRequest("Label must be 1–80 characters.");
		}
		String key = cmd.apiKey() == null ? "" : cmd.apiKey().strip();
		if (key.length() < 8 || key.length() > 512 || key.chars().anyMatch(Character::isWhitespace)) {
			throw ApiException.badRequest("The API key looks wrong (8–512 characters, no spaces).");
		}
		ProviderPreset preset = props.preset(cmd.provider());
		String baseUrl;
		if (cmd.provider() == AiProvider.CUSTOM) {
			if (cmd.baseUrl() == null || cmd.baseUrl().isBlank()) {
				throw ApiException.badRequest("A base URL is required for a custom provider.");
			}
			URI safe;
			try {
				safe = guard.requireSafe(URI.create(cmd.baseUrl().strip()), OutboundUrlGuard.HTTPS_ONLY);
			}
			catch (IllegalArgumentException e) {
				throw ApiException.badRequest("The base URL is not a valid URL.");
			}
			baseUrl = safe.toString().endsWith("/") ? safe.toString() : safe + "/";
		}
		else {
			baseUrl = preset.baseUrl().toString(); // presets ignore any client-supplied URL
		}

		Secret secret = Secret.of(key);
		UUID id = UUID.randomUUID();
		var entity = new AiConnection(id, owner, cmd.kind(), cmd.provider(), label, baseUrl,
				vault.seal(id, owner, secret), secret.last4());
		em.persist(entity);
		em.flush();
		return ConnectionView.of(entity);
	}

	@Transactional(readOnly = true)
	public List<ConnectionView> list(UUID owner) {
		return connections.findByOwnerIdOrderByCreatedAt(owner).stream().map(ConnectionView::of).toList();
	}

	@Transactional
	public void delete(UUID owner, UUID id) {
		if (connections.deleteByIdAndOwnerId(id, owner) == 0) {
			throw ApiException.notFound();
		}
		catalog.evict(id);
	}

	/**
	 * Proves the key works (A-FR-4); see {@link ConnectionTester}. Without a model the backend picks one.
	 * Not transactional: the provider calls happen outside any DB transaction.
	 */
	public TestResult test(UUID owner, UUID id, String requestedModel) {
		String explicit = requestedModel == null || requestedModel.isBlank() ? null : requestedModel.strip();
		return tester.test(owned(owner, id), explicit);
	}

	/**
	 * Live model list, cached per connection for {@code anveshan.models.cache-ttl} (A-FR-5): chat models
	 * first, best first.
	 */
	public List<ModelInfo> models(UUID owner, UUID id, boolean freeOnly) {
		AiConnection c = owned(owner, id);
		if (c.getKind() == ConnectionKind.SEARCH) {
			return List.of();
		}
		List<ModelInfo> listed;
		try {
			listed = catalog.list(c.getId(), () -> tester.resolveOrFail(c));
		}
		catch (ProviderException e) {
			throw TestOutcome.modelsFailed(e);
		}
		return catalog.ranked(c.getProvider(), listed, freeOnly);
	}

	private AiConnection owned(UUID owner, UUID id) {
		return connections.findByIdAndOwnerId(id, owner).orElseThrow(ApiException::notFound);
	}

}
