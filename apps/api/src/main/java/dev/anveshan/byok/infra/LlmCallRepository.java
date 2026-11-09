package dev.anveshan.byok.infra;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.anveshan.byok.domain.LlmCall;

interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {
}
