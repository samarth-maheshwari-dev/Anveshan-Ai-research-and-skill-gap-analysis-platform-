package dev.anveshan.llm.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import dev.anveshan.llm.api.TaskSlot;
import dev.anveshan.llm.domain.LlmEnums.LlmCallStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One LLM call through the developer-configured gateway (usage log; never contains prompts or keys). */
@Entity
@Table(name = "llm_call")
public class LlmCall {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private UUID ownerId;

	private UUID runId;

	private UUID stepId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TaskSlot slot;

	private String endpoint;

	@Column(nullable = false)
	private String model;

	private Integer tokensIn;

	private Integer tokensOut;

	@Column(nullable = false)
	private int latencyMs;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private LlmCallStatus status;

	private String errorCode;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected LlmCall() {
	}

	public LlmCall(UUID ownerId, TaskSlot slot, String endpoint, String model, int latencyMs, LlmCallStatus status) {
		this.ownerId = Objects.requireNonNull(ownerId);
		this.slot = Objects.requireNonNull(slot);
		this.endpoint = endpoint;
		this.model = Objects.requireNonNull(model);
		this.latencyMs = latencyMs;
		this.status = Objects.requireNonNull(status);
	}

	public LlmCall forRun(UUID runId, UUID stepId) {
		this.runId = runId;
		this.stepId = stepId;
		return this;
	}

	public LlmCall withTokens(Integer in, Integer out) {
		this.tokensIn = in;
		this.tokensOut = out;
		return this;
	}

	public LlmCall withError(String code) {
		this.errorCode = code;
		return this;
	}

	public UUID getId() {
		return id;
	}

	public UUID getOwnerId() {
		return ownerId;
	}

	public UUID getRunId() {
		return runId;
	}

	public UUID getStepId() {
		return stepId;
	}

	public TaskSlot getSlot() {
		return slot;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public String getModel() {
		return model;
	}

	public Integer getTokensIn() {
		return tokensIn;
	}

	public Integer getTokensOut() {
		return tokensOut;
	}

	public int getLatencyMs() {
		return latencyMs;
	}

	public LlmCallStatus getStatus() {
		return status;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
