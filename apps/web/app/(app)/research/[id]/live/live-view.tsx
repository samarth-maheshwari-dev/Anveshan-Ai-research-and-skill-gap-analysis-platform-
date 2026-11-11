"use client";

import { CircleAlert, CircleCheck, CircleDashed, CirclePause, LoaderCircle, Pause, Play, Square } from "lucide-react";
import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";

import { Alert } from "@/components/anveshan/alert";
import { ErrorLine } from "@/components/anveshan/error-line";
import { Button } from "@/components/ui/button";
import { Badge, Overline, Skeleton } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import { runHref, useRun, useRunAction, type Run, type RunStatus } from "@/lib/api/hooks/runs";
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

const KIND_LABEL: Record<string, string> = {
  PLAN: "Plan",
  SEARCH: "Search",
  FETCH: "Read sources",
  RELEVANCE: "Check relevance",
  EXTRACT: "Extract claims",
  VERIFY: "Verify claims",
  LABEL: "Label trust",
  WRITE_SECTION: "Write section",
  ASSEMBLE: "Assemble report",
};

/**
 * S-07 Live research (minimal for P10): status, progress, steps and pause / resume / stop. The live log, sources
 * and claims stream in with the research steps (P11+).
 */
export function LiveView() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const run = useRun(id);
  const action = useRunAction(id);
  const status = run.data?.status;

  useEffect(() => {
    if (status === "PLANNING" || status === "AWAITING_APPROVAL" || status === "COMPLETED") {
      router.replace(runHref(id, status));
    }
  }, [status, id, router]);

  if (run.isPending) return <Skeleton className="mx-auto h-60 max-w-4xl" />;
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
    <div className="mx-auto max-w-4xl">
      <Overline>Research</Overline>
      <h1 className="mt-3 text-heading-lg font-semibold md:text-display-lg">{r.topic}</h1>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Badge className={s.tone}>
          <s.Icon aria-hidden className={cn(r.status === "RUNNING" && "animate-spin")} /> {s.label}
        </Badge>
        {r.status === "PAUSED" && r.pauseReason && r.pauseReason !== "USER" && (
          <span className="text-body-sm text-muted-foreground">
            Your AI limit was reached. It resumes on its own{r.resumeAt ? ` at ${new Date(r.resumeAt).toLocaleTimeString()}` : ""}.
          </span>
        )}
      </div>

      <div className="mt-6">
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

      <Controls run={r} pending={action.isPending} onAction={(a) => action.mutate(a)} />
      {action.isError && (
        <Alert className="mt-4">{action.error instanceof ApiError ? action.error.message : "Couldn't do that. Try again."}</Alert>
      )}
      {r.status === "FAILED" && (
        <Alert className="mt-6">
          {r.steps?.find((x) => x.status === "FAILED")?.lastError ?? "This research stopped because a step failed."}
        </Alert>
      )}

      <section aria-labelledby="steps" className="mt-8 rounded-lg border border-border bg-surface">
        <h2 id="steps" className="border-b border-border px-6 py-4 text-heading-sm font-semibold">
          Steps
        </h2>
        <ol className="divide-y divide-border">
          {(r.steps ?? []).map((step) => (
            <li key={step.id} className="flex items-center justify-between gap-3 px-6 py-3 text-body-sm">
              <span className="flex items-center gap-3">
                <StepIcon status={step.status} />
                {KIND_LABEL[step.kind ?? ""] ?? step.kind}
              </span>
              <span className="text-caption text-subtle-foreground">{(step.status ?? "").toLowerCase()}</span>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}

function StepIcon({ status }: { status: string | undefined }) {
  if (status === "DONE") return <CircleCheck aria-hidden className="size-4 text-primary" />;
  if (status === "RUNNING") return <LoaderCircle aria-hidden className="size-4 animate-spin text-primary" />;
  if (status === "FAILED") return <CircleAlert aria-hidden className="size-4 text-danger" />;
  return <CircleDashed aria-hidden className="size-4 text-subtle-foreground" />;
}

function Controls({
  run,
  pending,
  onAction,
}: {
  run: Run;
  pending: boolean;
  onAction: (a: "pause" | "resume" | "cancel") => void;
}) {
  const active = run.status === "RUNNING" || run.status === "PAUSED";
  if (!active) return null;
  return (
    <div className="mt-6 flex flex-wrap gap-3">
      {run.status === "RUNNING" ? (
        <Button variant="secondary" disabled={pending} onClick={() => onAction("pause")}>
          <Pause aria-hidden /> Pause
        </Button>
      ) : (
        <Button variant="primary" disabled={pending} onClick={() => onAction("resume")}>
          <Play aria-hidden /> Resume
        </Button>
      )}
      <Button variant="danger" disabled={pending} onClick={() => onAction("cancel")}>
        <Square aria-hidden /> Stop
      </Button>
    </div>
  );
}
