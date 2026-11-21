package dev.anveshan.llm.infra;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.anveshan.llm.domain.LlmCall;

interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {
}
