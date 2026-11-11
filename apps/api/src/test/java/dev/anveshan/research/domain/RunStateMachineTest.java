package dev.anveshan.research.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.research.domain.ResearchEnums.RunStatus;

class RunStateMachineTest {

	/** PRD §8.7, written out by hand so the test doesn't just mirror the implementation. */
	static final Map<RunStatus, Set<RunStatus>> ALLOWED = Map.of(
			RunStatus.PLANNING, EnumSet.of(RunStatus.AWAITING_APPROVAL, RunStatus.FAILED),
			RunStatus.AWAITING_APPROVAL, EnumSet.of(RunStatus.RUNNING, RunStatus.CANCELLED),
			RunStatus.RUNNING, EnumSet.of(RunStatus.PAUSED, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED),
			RunStatus.PAUSED, EnumSet.of(RunStatus.RUNNING, RunStatus.CANCELLED),
			RunStatus.FAILED, EnumSet.of(RunStatus.RUNNING),
			RunStatus.COMPLETED, EnumSet.noneOf(RunStatus.class),
			RunStatus.CANCELLED, EnumSet.noneOf(RunStatus.class));

	@Test
	@DisplayName("T-09-01 every allowed transition passes; every other one (incl. same → same) throws RUN_INVALID_STATE")
	void everyTransition() {
		int allowed = 0;
		int forbidden = 0;
		for (RunStatus from : RunStatus.values()) {
			for (RunStatus to : RunStatus.values()) {
				if (ALLOWED.get(from).contains(to)) {
					assertThat(RunStateMachine.require(from, to)).as(from + " → " + to).isEqualTo(to);
					assertThat(RunStateMachine.canMove(from, to)).isTrue();
					allowed++;
				}
				else {
					assertThatThrownBy(() -> RunStateMachine.require(from, to)).as(from + " → " + to)
							.isInstanceOf(RunInvalidStateException.class)
							.satisfies(e -> {
								var ex = (RunInvalidStateException) e;
								assertThat(ex.code()).isEqualTo("RUN_INVALID_STATE");
								assertThat(ex.from()).isEqualTo(from);
								assertThat(ex.to()).isEqualTo(to);
							});
					assertThat(RunStateMachine.canMove(from, to)).isFalse();
					forbidden++;
				}
			}
		}
		assertThat(allowed).isEqualTo(11);
		assertThat(forbidden).isEqualTo(49 - 11);
	}

	@Test
	@DisplayName("T-09-01 sources(to) lists exactly the statuses a move to `to` may start from (used in the SQL guard)")
	void sources() {
		for (RunStatus to : RunStatus.values()) {
			Set<RunStatus> expected = EnumSet.noneOf(RunStatus.class);
			ALLOWED.forEach((from, tos) -> {
				if (tos.contains(to)) {
					expected.add(from);
				}
			});
			assertThat(RunStateMachine.sources(to)).as("→ " + to).containsExactlyInAnyOrderElementsOf(expected);
		}
	}

	@Test
	@DisplayName("T-09-01 COMPLETED and CANCELLED are final; only PLANNING and RUNNING execute steps")
	void finalAndActive() {
		assertThat(RunStateMachine.isFinal(RunStatus.COMPLETED)).isTrue();
		assertThat(RunStateMachine.isFinal(RunStatus.CANCELLED)).isTrue();
		assertThat(RunStateMachine.isFinal(RunStatus.FAILED)).isFalse(); // retry brings it back
		for (RunStatus s : RunStatus.values()) {
			assertThat(RunStateMachine.executesSteps(s)).as(s.name())
					.isEqualTo(s == RunStatus.PLANNING || s == RunStatus.RUNNING);
		}
	}

}
