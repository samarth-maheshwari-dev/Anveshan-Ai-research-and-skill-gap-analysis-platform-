package dev.anveshan.research.infra;

import java.time.Instant;
import java.util.UUID;

import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.anveshan.research.app.StepQueue;
import dev.anveshan.research.app.StepWorker;

/**
 * One JobRunr job per step (TECHSPEC §12.2). Job arguments are the step id only (TS-G15). The job itself is
 * harmless to run twice — {@link StepWorker} claims the step atomically — so a duplicate enqueue is fine.
 */
@Component
class JobRunrStepQueue implements StepQueue {

	private final JobScheduler jobs;

	JobRunrStepQueue(JobScheduler jobs) {
		this.jobs = jobs;
	}

	@Override
	public void enqueue(UUID stepId) {
		afterCommit(() -> jobs.<StepWorker>enqueue(w -> w.execute(stepId)));
	}

	@Override
	public void enqueueAt(UUID stepId, Instant at) {
		afterCommit(() -> jobs.<StepWorker>schedule(at, w -> w.execute(stepId)));
	}

	/** Inside a transaction, wait for the commit; otherwise a worker could miss a step that isn't visible yet. */
	private static void afterCommit(Runnable r) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					r.run();
				}
			});
		}
		else {
			r.run();
		}
	}

}
