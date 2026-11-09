package dev.anveshan.byok.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.anveshan.byok.api.TaskSlot;
import dev.anveshan.byok.app.SlotService;
import dev.anveshan.byok.app.SlotService.RouteChoice;
import dev.anveshan.byok.app.UsageService;
import dev.anveshan.shared.security.CurrentUser;

/** Task-slot routing and usage (TECHSPEC §8.5). */
@RestController
class SlotController {

	private final SlotService slots;
	private final UsageService usage;
	private final CurrentUser currentUser;

	SlotController(SlotService slots, UsageService usage, CurrentUser currentUser) {
		this.slots = slots;
		this.usage = usage;
		this.currentUser = currentUser;
	}

	@GetMapping("/api/v1/slots")
	Map<TaskSlot, List<RouteChoice>> get() {
		return slots.get(currentUser.id());
	}

	@PutMapping("/api/v1/slots")
	Map<TaskSlot, List<RouteChoice>> put(@RequestBody Map<TaskSlot, List<RouteChoice>> body) {
		return slots.put(currentUser.id(), body);
	}

	@GetMapping("/api/v1/usage")
	UsageService.Usage usage(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return usage.usage(currentUser.id(), from, to);
	}

}
