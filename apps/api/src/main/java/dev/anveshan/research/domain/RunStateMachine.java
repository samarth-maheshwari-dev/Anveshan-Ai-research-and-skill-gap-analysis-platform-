package dev.anveshan.research.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import dev.anveshan.research.domain.ResearchEnums.RunStatus;

/**
 * The research run state machine (PRD §8.7, invariant L5). Pure: the SQL layer uses {@link #sources} as
 * its {@code WHERE status IN (…)} guard, so a move is checked atomically in the database too.
 */
public final class RunStateMachine {

	private static final Map<RunStatus, Set<RunStatus>> NEXT = new EnumMap<>(RunStatus.class);

	static {
		NEXT.put(RunStatus.PLANNING, EnumSet.of(RunStatus.AWAITING_APPROVAL, RunStatus.FAILED));
		NEXT.put(RunStatus.AWAITING_APPROVAL, EnumSet.of(RunStatus.RUNNING, RunStatus.CANCELLED));
		NEXT.put(RunStatus.RUNNING,
				EnumSet.of(RunStatus.PAUSED, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED));
		NEXT.put(RunStatus.PAUSED, EnumSet.of(RunStatus.RUNNING, RunStatus.CANCELLED));
		NEXT.put(RunStatus.FAILED, EnumSet.of(RunStatus.RUNNING)); // "retry failed steps"
		NEXT.put(RunStatus.COMPLETED, EnumSet.noneOf(RunStatus.class));
		NEXT.put(RunStatus.CANCELLED, EnumSet.noneOf(RunStatus.class));
	}

	private RunStateMachine() {
	}

	public static boolean canMove(RunStatus from, RunStatus to) {
		return NEXT.get(from).contains(to);
	}

	/** Returns {@code to} if the move is allowed; otherwise throws {@link RunInvalidStateException}. */
	public static RunStatus require(RunStatus from, RunStatus to) {
		if (!canMove(from, to)) {
			throw new RunInvalidStateException(from, to);
		}
		return to;
	}

	/** Every status a run may move to {@code to} from. */
	public static Set<RunStatus> sources(RunStatus to) {
		Set<RunStatus> out = EnumSet.noneOf(RunStatus.class);
		NEXT.forEach((from, tos) -> {
			if (tos.contains(to)) {
				out.add(from);
			}
		});
		return out;
	}

	public static boolean isFinal(RunStatus s) {
		return NEXT.get(s).isEmpty();
	}

	/** Steps run only while the run is planning (the PLAN step) or running; otherwise they release. */
	public static boolean executesSteps(RunStatus s) {
		return s == RunStatus.PLANNING || s == RunStatus.RUNNING;
	}

}
