package dev.anveshan.research.app;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * P09 ships the engine only (proven with fake steps in tests). The real stage graph — SEARCH → FETCH → … →
 * ASSEMBLE (TECHSPEC §12.1) — replaces this from P10/P11 on. Until then an approved run has no further stages.
 */
@Component
class DefaultStageMaterializer implements StageMaterializer {

	@Override
	public List<NewStep> next(RunSnapshot run, int closedStage) {
		return List.of();
	}

}
