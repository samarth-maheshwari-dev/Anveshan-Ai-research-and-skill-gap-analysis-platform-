"use client";

import { CircleCheck, CircleDashed, CircleX, FlaskConical, Hourglass, Plus } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { ErrorLine } from "@/components/anveshan/error-line";
import { buttonVariants } from "@/components/ui/button";
import { Overline, Skeleton, Stat } from "@/components/ui/card";
import { useAiStatus, type AiStatus } from "@/lib/api/hooks/ai-status";
import { runHref, useRuns, type RunSummary } from "@/lib/api/hooks/runs";
import { useMe } from "@/lib/api/hooks/me";
import { timeAgo } from "@/lib/time";

const EXAMPLES = [
  "Literature review: transformers in medical imaging",
  "Compare PostgreSQL, MongoDB and Cassandra for a college ERP",
  "What does a junior backend developer need to know in 2026?",
];

/**
 * S-04 Dashboard (APPFLOW §4.2 steps 4 and 10), laid out like the owner's reference design: stats, ongoing
 * research, recent activity. Only real numbers — research and knowledge show "—" until those modules exist.
 */
export function DashboardView() {
  const me = useMe();
  const aiStatus = useAiStatus();
  const runs = useRuns(10);

  const firstName = me.data?.name?.split(" ")[0];

  return (
    <div>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <Overline>Workspace</Overline>
          <h1 className="mt-2 text-display-lg font-semibold">{firstName ? `Welcome, ${firstName}` : "Welcome"}</h1>
          <p className="mt-1 text-body text-muted-foreground">Your research overview at a glance.</p>
        </div>
        <Link href="/research/new" className={buttonVariants({ variant: "primary" })}>
          <Plus aria-hidden /> New research
        </Link>
      </div>

      <div className="mt-8 grid grid-cols-2 gap-3 md:gap-4 xl:grid-cols-4">
        <AiStatusStat query={aiStatus} />
        <Stat
          label="Research"
          value={runs.data ? `${runs.data.items?.length ?? 0}${runs.data.nextCursor ? "+" : ""}` : "—"}
          hint={runs.data ? `${(runs.data.items ?? []).filter((r) => r.status === "COMPLETED").length} with a report` : undefined}
        />
        <Stat label="Knowledge items" value="—" hint="Grows with every report" />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <section aria-labelledby="ongoing" className="rounded-lg border border-border bg-surface lg:col-span-2">
          <div className="flex items-center justify-between border-b border-border px-6 py-4">
            <h2 id="ongoing" className="text-heading-sm font-semibold">
              Your research
            </h2>
          </div>
          {runs.isPending ? (
            <div className="space-y-2 p-6">
              <Skeleton className="h-14 w-full" />
              <Skeleton className="h-14 w-full" />
            </div>
          ) : runs.isError ? (
            <div className="p-6">
              <ErrorLine message="Couldn't load your researches." onRetry={() => void runs.refetch()} />
            </div>
          ) : (runs.data?.items ?? []).length > 0 ? (
            <ul className="divide-y divide-border">
              {(runs.data?.items ?? []).map((r) => (
                <RunRow key={r.id} run={r} />
              ))}
            </ul>
          ) : (
            <div className="p-6">
              <div className="flex items-start gap-4">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-md bg-surface-2">
                  <FlaskConical aria-hidden className="size-5 text-muted-foreground" />
                </span>
                <div>
                  <h3 className="text-heading-sm font-semibold">Start your first research</h3>
                  <p className="mt-1 max-w-lg text-body-sm text-muted-foreground">
                    Ask a question once. Anveshan plans it with you, reads the sources and writes a report where every
                    claim shows its proof.
                  </p>
                </div>
              </div>
              <p className="mt-6 text-caption font-medium text-subtle-foreground">Try a topic like</p>
              <ul className="mt-2 space-y-2">
                {EXAMPLES.map((t) => (
                  <li key={t} className="rounded-md border border-border bg-background px-4 py-3 text-body-sm">
                    {t}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>

        <div className="space-y-6">
          <section aria-labelledby="activity" className="rounded-lg border border-border bg-surface p-6">
            <h2 id="activity" className="text-heading-sm font-semibold">
              Recent activity
            </h2>
            <Activity runs={runs.data?.items} />
          </section>

        </div>
      </div>
    </div>
  );
}

const RUN_STATUS: Record<string, string> = {
  PLANNING: "Planning",
  AWAITING_APPROVAL: "Plan ready — review it",
  RUNNING: "Running",
  PAUSED: "Paused",
  COMPLETED: "Report ready",
  FAILED: "Failed",
  CANCELLED: "Stopped",
};

/** One research in the list: topic, status in words, progress while it runs; opens the right screen (APPFLOW §1.3). */
function RunRow({ run }: { run: RunSummary }) {
  const [now] = useState(() => new Date());
  const working = run.status === "RUNNING" || run.status === "PAUSED";
  const progress = Math.round(Number(run.progress ?? 0));
  return (
    <li>
      <Link href={runHref(run.id!, run.status)} className="block px-6 py-4 transition-colors hover:bg-surface-2">
        <div className="flex items-start justify-between gap-4">
          <p className="line-clamp-2 text-body-sm font-medium">{run.topic}</p>
          <span
            className={`shrink-0 rounded-sm px-2 py-0.5 text-caption font-medium ${run.status === "COMPLETED" ? "bg-primary-soft text-primary" : run.status === "FAILED" ? "bg-danger-soft text-danger" : "bg-surface-2 text-muted-foreground"}`}
          >
            {RUN_STATUS[run.status ?? ""] ?? run.status}
          </span>
        </div>
        <div className="mt-2 flex items-center gap-3 text-caption text-subtle-foreground">
          <span>{run.updatedAt ? timeAgo(run.updatedAt, now) : ""}</span>
          {working && (
            <span className="flex flex-1 items-center gap-2" aria-label={`${progress}% done`}>
              <span className="h-1.5 max-w-40 flex-1 overflow-hidden rounded-full bg-surface-2">
                <span className="block h-full bg-primary" style={{ width: `${progress}%` }} />
              </span>
              <span className="tabular">{progress}%</span>
            </span>
          )}
        </div>
      </Link>
    </li>
  );
}

const AI_STATE = {
  ONLINE: { word: "Online", Icon: CircleCheck, tone: "text-primary" },
  BUSY: { word: "Busy", Icon: Hourglass, tone: "text-muted-foreground" },
  OFFLINE: { word: "Offline", Icon: CircleX, tone: "text-danger" },
} as const;

type AiStatusStatProps = { query: ReturnType<typeof useAiStatus> };

/** The platform AI gateway's state (icon + word, never colour alone) and today's research-run usage. */
function AiStatusStat({ query }: AiStatusStatProps) {
  if (query.isPending) return <Stat label="AI status" value={<Skeleton className="h-8 w-24" />} />;
  if (query.isError) return <Stat label="AI status" value="—" hint="Couldn't check the AI right now." />;
  const status: AiStatus = query.data;
  const state = AI_STATE[status.state as keyof typeof AI_STATE];
  const Icon = state?.Icon ?? CircleDashed;
  return (
    <Stat
      label="AI status"
      value={
        <span className="inline-flex items-center gap-2">
          <Icon aria-hidden className={`size-6 shrink-0 ${state?.tone ?? "text-muted-foreground"}`} />
          {state?.word ?? "Unknown"}
        </span>
      }
      hint={`${status.runsUsedToday} of ${status.runsLimit} research runs used today`}
    />
  );
}

type ActivityProps = { runs: RunSummary[] | undefined };

/** A timeline from what really happened so far: research started and reports finished. */
function Activity({ runs }: ActivityProps) {
  // "now" is read once per mount (render stays pure); minutes-ago only needs to be roughly right
  const [now] = useState(() => new Date());
  const items = (runs ?? [])
    .filter((r) => r.id && r.updatedAt)
    .map((r) => ({
      key: r.id!,
      at: r.updatedAt!,
      text: r.status === "COMPLETED" ? `Report ready: ${r.topic}` : `Research: ${r.topic}`,
      done: r.status === "COMPLETED",
    }))
    .sort((a, b) => b.at.localeCompare(a.at))
    .slice(0, 5);

  if (items.length === 0) {
    return <p className="mt-4 text-body-sm text-muted-foreground">Nothing to show yet. Your research shows up here.</p>;
  }
  return (
    <ol className="mt-4 space-y-4">
      {items.map((i) => (
        <li key={i.key} className="flex gap-3">
          <span
            className={`mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full ${i.done ? "bg-primary-soft text-primary" : "bg-surface-2 text-muted-foreground"}`}
          >
            {i.done ? <CircleCheck aria-hidden className="size-3.5" /> : <FlaskConical aria-hidden className="size-3.5" />}
          </span>
          <div>
            <p className="line-clamp-2 text-body-sm">{i.text}</p>
            <p className="text-caption text-subtle-foreground">{timeAgo(i.at, now)}</p>
          </div>
        </li>
      ))}
    </ol>
  );
}
