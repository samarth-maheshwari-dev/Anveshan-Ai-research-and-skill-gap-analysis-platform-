package dev.anveshan.llm.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.anveshan.llm.api.AiAccess;
import dev.anveshan.llm.api.AiStatus;
import dev.anveshan.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Whether the shared AI service can answer now, and the signed-in user's runs left today. */
@RestController
@RequestMapping("/api/v1/ai")
@Tag(name = "AI")
class AiStatusController {

	private final AiAccess access;
	private final CurrentUser me;

	AiStatusController(AiAccess access, CurrentUser me) {
		this.access = access;
		this.me = me;
	}

	@GetMapping("/status")
	@Operation(summary = "AI service status", description = "ONLINE: can answer; BUSY: every endpoint is cooling "
			+ "down (runs pause and resume); OFFLINE: not set up on this server. Plus today's run allowance (UTC).")
	AiStatus status() {
		return access.status(me.id());
	}

}
