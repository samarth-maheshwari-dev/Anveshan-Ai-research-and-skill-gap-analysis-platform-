"use client";

import { useQueryClient } from "@tanstack/react-query";
import {
  CircleAlert,
  CircleCheck,
  CircleDashed,
  CirclePause,
  ExternalLink,
  FileText,
  Info,
  LoaderCircle,
  Pause,
  Play,
  RotateCcw,
  Square,
  Swords,
  TriangleAlert,
} from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";

import { Alert } from "@/components/anveshan/alert";
import { ErrorLine } from "@/components/anveshan/error-line";
import { TierBadge } from "@/components/anveshan/trust-label";
import { Button, buttonVariants } from "@/components/ui/button";
import { Badge, Overline, Skeleton } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import {
  runHref,
  useCreateRun,
  useRetryStep,
  useRun,
  useRunAction,
  useRunSources,
  useSkipSource,
  type CreateRun,
  type Run,
  type RunSource,
  type RunStatus,
} from "@/lib/api/hooks/runs";
import { useRunEvents, type RunEvent } from "@/lib/sse/use-run-events";
import { cn } from "@/lib/utils";

const STATUS: Record<RunStatus, { label: string; Icon: typeof CircleCheck; tone: string }> = {
  PLANNING: { label: "Planning", Icon: LoaderCircle, tone: "bg-surface-2 text-foreground" },
  AWAITING_APPROVAL: { label: "Waiting for you", Icon: CirclePause, tone: "bg-surface-2 text-foreground" },
  RUNNING: { label: "Running", Icon: LoaderCircle, tone: "bg-primary-soft text-primary" },
  PAUSED: { label: "Paused", Icon: CirclePause, tone: "bg-surface-2 text-foreground" },
  COMPLETED: { label: "Completed", Icon: CircleCheck, tone: "bg-primary-soft text-primary" },
  FAILED: { label: "Failed", Icon: CircleAlert, tone: "bg-danger-soft text-danger" },
  CANCELLED: { label: "Stopped", Icon: Square, tone: "bg-surface-2 text-muted-foreground" },
};

/** The stages in order (TECHSPEC §12.1) with the words the user sees. */
const STAGES: { kind: string; label: string }[] = [
  { kind: "PLAN", label: "Plan" },
  { kind: "SEARCH", label: "Search" },
  { kind: "FETCH", label: "Read sources" },
  { kind: "RELEVANCE", label: "Pick passages" },
  { kind: "EXTRACT", label: "Extract claims" },
  { kind: "VERIFY", label: "Verify claims" },
  { kind: "LABEL", label: "Label trust" },
  { kind: "WRITE_SECTION", label: "Write report" },
  { kind: "ASSEMBLE", label: "Assemble report" },
];

const REDIRECT_MS = 3000;

/**
 * S-07 Live Research Room (APPFLOW §5.4): steps on the left, what is happening now (sources with [Skip],
 * conflicts) in the middle, the live log on the right. Live over SSE; the run keeps going if the tab closes.
 */
export function LiveView() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const qc = useQueryClient();
  const run = useRun(id);
  const status = run.data?.status;
  const active = status === "RUNNING" || status === "PAUSED";
  const { events, live } = useRunEvents(id, !!status && status !== "PLANNING" && status !== "AWAITING_APPROVAL");
  const sources = useRunSources(id, !!status && status !== "PLANNING" && status !== "AWAITING_APPROVAL");

  // plan stages live on their own screen
  useEffect(() => {
    if (status === "PLANNING" || status === "AWAITING_APPROVAL") router.replace(runHref(id, status));
  }, [status, id, router]);

  // fresh events → refresh the run and its sources (cheap, only when something happened)
  const seen = useRef(0);
  useEffect(() => {
    if (events.length === seen.current) return;
    const fresh = events.slice(seen.current);
    seen.current = events.length;
    if (fresh.some((e) => e.type.startsWith("step.") || e.type.startsWith("run.") || e.type === "report.ready")) {
      void qc.invalidateQueries({ queryKey: ["run", id] });
    }
    if (fresh.some((e) => e.type === "source.added" || e.type === "source.skipped")) {
      void qc.invalidateQueries({ queryKey: ["run-sources", id] });
    }
  }, [events, id, qc]);

  // E-FR-6: report ready → "Open report", then automatically after 3 s
  useEffect(() => {
    if (status !== "COMPLETED") return;
    const t = setTimeout(() => router.push(`/research/${id}/report`), REDIRECT_MS);
    return () => clearTimeout(t);
  }, [status, id, router]);

  if (run.isPending) return <Skeleton className="mx-auto h-60 max-w-6xl" />;
  if (run.isError) {
    if (run.error instanceof ApiError && run.error.status === 404) {
      return <Alert className="mx-auto max-w-4xl">This research doesn&apos;t exist or isn&apos;t yours.</Alert>;
    }
    return <ErrorLine message="Couldn't load this research." onRetry={() => void run.refetch()} />;
  }
  const r = run.data;
  const s = STATUS[r.status ?? "RUNNING"];
  const progress = Math.round(Number(r.progress ?? 0));

  return (
    <div className="mx-auto max-w-6xl">
      <Overline>Research</Overline>
      <h1 className="mt-3 text-heading-lg font-semibold md:text-display-lg">{r.topic}</h1>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Badge className={s.tone}>
          <s.Icon aria-hidden className={cn(r.status === "RUNNING" && "animate-spin")} /> {s.label}
        </Badge>
        {active && (
          <span className="inline-flex items-center gap-1.5 text-caption text-subtle-foreground">
            <span aria-hidden className={cn("size-2 rounded-full", live ? "bg-primary" : "bg-border-strong")} />
            {live ? "Live" : "Reconnecting…"}
          </span>
        )}
        {r.status === "PAUSED" && r.pauseReason && r.pauseReason !== "USER" && (
          <span className="text-body-sm text-muted-foreground">
            Your AI limit was reached. It resumes on its own
            {r.resumeAt ? ` at ${new Date(r.resumeAt).toLocaleTimeString()}` : ""}.
          </span>
        )}
      </div>

      {r.status === "COMPLETED" && (
        <Alert
          tone="info"
          className="mt-6"
          action={
            <Link href={`/research/${id}/report`} className={buttonVariants({ variant: "primary", size: "sm" })}>
              <FileText aria-hidden /> Open report
            </Link>
          }
        >
          Report ready. Opening it in a moment…
        </Alert>
      )}
      {r.status === "CANCELLED" && (
        <Alert tone="neutral" className="mt-6">
          This research was stopped. What it found so far is shown below.
        </Alert>
      )}
      {r.status === "FAILED" && <FailedNotice run={r} />}

      <div className="mt-6 grid gap-4 lg:grid-cols-[17rem_1fr_20rem]">
        <StepsPanel run={r} progress={progress} />
        <NowPanel runId={id} active={active} sources={sources.data ?? []} loading={sources.isPending} events={events} />
        <LogPanel events={events} />
      </div>
    </div>
  );
}

function StepsPanel({ run, progress }: { run: Run; progress: number }) {
  const action = useRunAction(run.id!);
  const retry = useRetryStep(run.id!);
  const [confirmStop, setConfirmStop] = useState(false);
  const steps = run.steps ?? [];
  const failed = steps.filter((x) => x.status === "FAILED");
  const active = run.status === "RUNNING" || run.status === "PAUSED";
  return (
    <section aria-labelledby="steps" className="rounded-lg border border-border bg-surface p-4">
      <h2 id="steps" className="text-heading-sm font-semibold">
        Steps
      </h2>
      <ol className="mt-3 space-y-2">
        {STAGES.map(({ kind, label }) => {
          const mine = steps.filter((x) => x.kind === kind);
          const done = mine.filter((x) => x.status === "DONE" || x.status === "SKIPPED").length;
          const state = mine.length === 0 ? "todo" : mine.some((x) => x.status === "FAILED") ? "failed"
            : done === mine.length ? "done" : mine.some((x) => x.status === "RUNNING") ? "running" : "waiting";
          return (
            <li key={kind} className="flex items-center justify-between gap-2 text-body-sm">
              <span className="flex items-center gap-2">
                <StageIcon state={state} />
                {label}
              </span>
              {mine.length > 1 && (
                <span className="tabular text-caption text-subtle-foreground">
                  {done}/{mine.length}
                </span>
              )}
            </li>
          );
        })}
      </ol>
      <div className="mt-4">
        <div className="flex justify-between text-caption text-muted-foreground">
          <span>Progress</span>
          <span className="tabular">{progress}%</span>
        </div>
        <div
          className="mt-2 h-2 overflow-hidden rounded-full bg-surface-2"
          role="progressbar"
          aria-label="Research progress"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={progress}
        >
          <div className="h-full bg-primary transition-[width] duration-300" style={{ width: `${progress}%` }} />
        </div>
      </div>

      {failed.filter((f) => f.kind !== "PLAN").map((f) => (
        <div key={f.id} className="mt-4 rounded-md border border-danger/40 bg-danger-soft p-3 text-body-sm">
          <p className="flex items-start gap-2">
            <CircleAlert aria-hidden className="mt-0.5 size-4 shrink-0 text-danger" />
            {f.lastError ?? "This step failed."}
          </p>
          {f.kind !== "PLAN" && (
            <Button size="sm" variant="secondary" className="mt-2" loading={retry.isPending} onClick={() => retry.mutate(f.id!)}>
              <RotateCcw aria-hidden /> Retry this step
            </Button>
          )}
        </div>
      ))}
      {retry.isError && (
        <p className="mt-2 text-caption text-danger">{retry.error instanceof ApiError ? retry.error.message : "Couldn't retry."}</p>
      )}

      {active && (
        <div className="mt-4 space-y-2">
          {run.status === "RUNNING" ? (
            <Button className="w-full" variant="secondary" disabled={action.isPending} onClick={() => action.mutate("pause")}>
              <Pause aria-hidden /> Pause
            </Button>
          ) : (
            <Button className="w-full" variant="primary" disabled={action.isPending} onClick={() => action.mutate("resume")}>
              <Play aria-hidden /> Resume
            </Button>
          )}
          {run.status === "RUNNING" && action.isSuccess && action.variables === "pause" && (
            <p className="text-caption text-subtle-foreground">Pausing after the current step…</p>
          )}
          {!confirmStop ? (
            <Button className="w-full" variant="danger" disabled={action.isPending} onClick={() => setConfirmStop(true)}>
              <Square aria-hidden /> Stop
            </Button>
          ) : (
            <div role="alertdialog" aria-label="Stop this research?" className="rounded-md border border-border-strong p-3">
              <p className="text-body-sm">Stop this research? You can still see what was found.</p>
              <div className="mt-2 flex gap-2">
                <Button size="sm" variant="danger" onClick={() => { action.mutate("cancel"); setConfirmStop(false); }}>
                  Stop research
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setConfirmStop(false)}>
                  Keep going
                </Button>
              </div>
            </div>
          )}
          {action.isError && (
            <p className="text-caption text-danger">{action.error instanceof ApiError ? action.error.message : "Couldn't do that."}</p>
          )}
        </div>
      )}
    </section>
  );
}

function StageIcon({ state }: { state: string }) {
  if (state === "done") return <CircleCheck aria-label="done" className="size-4 text-primary" />;
  if (state === "running") return <LoaderCircle aria-label="running" className="size-4 animate-spin text-primary" />;
  if (state === "failed") return <CircleAlert aria-label="failed" className="size-4 text-danger" />;
  return <CircleDashed aria-label={state === "waiting" ? "waiting" : "not started"} className="size-4 text-subtle-foreground" />;
}

function NowPanel({
  runId,
  active,
  sources,
  loading,
  events,
}: {
  runId: string;
  active: boolean;
  sources: RunSource[];
  loading: boolean;
  events: RunEvent[];
}) {
  const skip = useSkipSource(runId);
  const conflicts = events.filter((e) => e.type === "conflict.found");
  const used = sources.filter((s) => s.status === "USED").length;
  return (
    <section aria-labelledby="now" className="rounded-lg border border-border bg-surface p-4">
      <h2 id="now" className="text-heading-sm font-semibold">
        Now
      </h2>
      <p className="mt-1 text-body-sm text-muted-foreground">
        {loading ? "Loading sources…" : sources.length === 0 ? "Sources appear here as they are read." : `${used} sources in use`}
      </p>
      <ul className="mt-3 divide-y divide-border">
        {sources.map((s) => {
          const skipped = s.status === "REJECTED_BY_USER";
          return (
            <li key={s.sourceId} className="flex items-start justify-between gap-3 py-3" data-testid="live-source">
              <div className="min-w-0">
                <p className={cn("truncate text-body-sm font-medium", skipped && "text-subtle-foreground line-through")}>
                  {s.url ? (
                    <a href={s.url} target="_blank" rel="noopener noreferrer" className="hover:underline">
                      {s.title}
                      <ExternalLink aria-hidden className="ml-1 inline size-3 align-baseline" />
                    </a>
                  ) : (
                    s.title
                  )}
                </p>
                <p className="mt-0.5 flex flex-wrap items-center gap-2 text-caption text-subtle-foreground">
                  <span>{s.site ?? "your file"}</span>
                  <span>·</span>
                  <span>{(s.type ?? "").toLowerCase()}</span>
                  <span>·</span>
                  <span className="tabular">{s.claims ?? 0} claims</span>
                </p>
                <div className="mt-1.5">
                  {skipped ? (
                    <Badge className="bg-surface-2 text-muted-foreground">Skipped</Badge>
                  ) : (
                    <TierBadge tier={s.tier} reason={s.tierReason} />
                  )}
                </div>
              </div>
              {active && !skipped && (
                <Button
                  size="sm"
                  variant="ghost"
                  aria-label={`Skip ${s.title}`}
                  disabled={skip.isPending}
                  onClick={() => skip.mutate(s.sourceId!)}
                >
                  Skip
                </Button>
              )}
            </li>
          );
        })}
      </ul>
      {skip.isError && (
        <p className="mt-2 text-caption text-danger">{skip.error instanceof ApiError ? skip.error.message : "Couldn't skip."}</p>
      )}
      {conflicts.length > 0 && (
        <div className="mt-4 space-y-2">
          {conflicts.map((c) => (
            <p key={c.id} className="flex items-start gap-2 rounded-md bg-trust-conflict-bg p-3 text-body-sm text-foreground">
              <Swords aria-hidden className="mt-0.5 size-4 shrink-0 text-trust-conflict" />
              <span>
                <strong className="font-medium">Conflicting:</strong> {String(c.data.text ?? "")}{" "}
                <span className="text-caption text-muted-foreground">
                  ({String(c.data.supporting ?? 0)} for, {String(c.data.contradicting ?? 0)} against)
                </span>
              </span>
            </p>
          ))}
        </div>
      )}
    </section>
  );
}

function LogPanel({ events }: { events: RunEvent[] }) {
  const logs = useMemo(() => events.filter((e) => e.type === "log" && e.data.message), [events]);
  const end = useRef<HTMLLIElement>(null);
  useEffect(() => end.current?.scrollIntoView({ block: "nearest" }), [logs.length]);
  return (
    <section aria-labelledby="log" className="rounded-lg border border-border bg-surface p-4">
      <h2 id="log" className="text-heading-sm font-semibold">
        Log
      </h2>
      {logs.length === 0 ? (
        <p className="mt-2 text-body-sm text-muted-foreground">What Anveshan does shows up here.</p>
      ) : (
        <ol className="mt-3 max-h-[28rem] space-y-2 overflow-y-auto pr-1" aria-live="polite" data-testid="live-log">
          {logs.map((e, i) => {
            const warn = e.data.level === "WARN";
            return (
              <li key={e.id} ref={i === logs.length - 1 ? end : undefined} className="flex items-start gap-2 text-body-sm">
                {warn ? (
                  <TriangleAlert aria-label="warning" className="mt-0.5 size-3.5 shrink-0 text-trust-weak" />
                ) : (
                  <Info aria-hidden className="mt-0.5 size-3.5 shrink-0 text-subtle-foreground" />
                )}
                <span className={warn ? "text-foreground" : "text-muted-foreground"}>{String(e.data.message)}</span>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}

/**
 * A failed run says why in plain words. A failed PLAN can't be retried in place (no FAILED → PLANNING), so
 * "Start again" creates a new run with the same topic and options (a fresh Idempotency-Key per click).
 */
function FailedNotice({ run }: { run: Run }) {
  const router = useRouter();
  const create = useCreateRun();
  const failed = run.steps?.find((x) => x.status === "FAILED");
  if (failed?.kind !== "PLAN") return null; // other failed steps show [Retry this step] in the Steps panel
  return (
    <>
      <Alert
        className="mt-6"
        action={
          <Button
            size="sm"
            variant="primary"
            loading={create.isPending}
            disabled={create.isSuccess}
            onClick={() =>
              create.mutate(
                {
                  body: { ...(run.config as Omit<CreateRun, "topic">), topic: run.topic ?? "" },
                  idempotencyKey: crypto.randomUUID(),
                },
                { onSuccess: (next) => router.push(`/research/${next.id}/plan`) },
              )
            }
          >
            <RotateCcw aria-hidden /> Start again
          </Button>
        }
      >
        {failed.lastError ?? "This research stopped because a step failed."}
      </Alert>
      {create.isError && (
        <Alert className="mt-3">
          {create.error instanceof ApiError ? create.error.message : "Couldn't start again. Try once more."}
        </Alert>
      )}
    </>
  );
}
