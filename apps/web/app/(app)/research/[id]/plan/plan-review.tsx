"use client";

import { ArrowDown, ArrowUp, Check, Clock, LoaderCircle, Plus, Sparkles, Trash2 } from "lucide-react";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useId, useState } from "react";

import { Alert } from "@/components/anveshan/alert";
import { ErrorLine } from "@/components/anveshan/error-line";
import { Button } from "@/components/ui/button";
import { Overline, Skeleton } from "@/components/ui/card";
import { Textarea } from "@/components/ui/input";
import { ApiError } from "@/lib/api/errors";
import {
  etagOf,
  runHref,
  useApprove,
  useRun,
  useUpdatePlan,
  type PlanCard,
  type Run,
  type SourceType,
} from "@/lib/api/hooks/runs";
import { estimate, MAX_SUB_QUESTIONS, MIN_SUB_QUESTIONS, sizeOf, type Depth } from "@/lib/estimate";
import { SOURCE_TYPES } from "@/lib/research-options";
import { cn } from "@/lib/utils";

const TEXT_MIN = 5;
const TEXT_MAX = 300;

/** S-06 Plan Review (APPFLOW §5.2, PRD Module D): edit the plan, watch the cost, approve. */
export function PlanReview() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const run = useRun(id, { pollWhilePlanning: true });
  const status = run.data?.status;

  // APPFLOW §1.3: a run that is past planning lives elsewhere
  useEffect(() => {
    if (status && status !== "PLANNING" && status !== "AWAITING_APPROVAL") router.replace(runHref(id, status));
  }, [status, id, router]);

  if (run.isPending) return <PlanSkeleton title="Loading the plan…" />;
  if (run.isError) {
    if (run.error instanceof ApiError && run.error.status === 404) {
      return <Alert className="mx-auto max-w-3xl">This research doesn&apos;t exist or isn&apos;t yours.</Alert>;
    }
    return <ErrorLine message="Couldn't load this research." onRetry={() => void run.refetch()} />;
  }
  if (status === "PLANNING") return <PlanSkeleton title="Building your plan…" topic={run.data.topic} />;
  if (status !== "AWAITING_APPROVAL") return <PlanSkeleton title="Opening…" />;
  // a new version (saved here or elsewhere) starts a fresh draft
  return <PlanEditor key={run.data.version} run={run.data} onReload={() => void run.refetch()} />;
}

function PlanSkeleton({ title, topic }: { title: string; topic?: string }) {
  return (
    <div className="mx-auto max-w-5xl" aria-busy="true">
      <Overline>Plan review</Overline>
      <h1 className="mt-3 flex items-center gap-3 text-display-lg font-semibold">
        <LoaderCircle aria-hidden className="size-6 animate-spin text-primary" />
        {title}
      </h1>
      {topic && <p className="mt-2 max-w-3xl text-body text-muted-foreground">{topic}</p>}
      <p className="mt-1 text-body-sm text-subtle-foreground" role="status">
        The AI is splitting your question into sub-questions. This usually takes a few seconds.
      </p>
      <div className="mt-8 space-y-3">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-28" />
        ))}
      </div>
    </div>
  );
}

type Draft = { key: string; id?: string; text: string; sourceTypes: SourceType[]; queries: string[] };

const toDrafts = (run: Run): Draft[] =>
  (run.subQuestions ?? []).map((q) => ({
    key: q.id ?? crypto.randomUUID(),
    id: q.id,
    text: q.text ?? "",
    sourceTypes: (q.sourceTypes ?? []) as SourceType[],
    queries: q.queries ?? [],
  }));

const textLength = (s: string) => [...s.trim()].length;

function PlanEditor({ run, onReload }: { run: Run; onReload: () => void }) {
  const router = useRouter();
  const id = run.id!;
  const update = useUpdatePlan(id);
  const approve = useApprove(id);
  const [cards, setCards] = useState<Draft[]>(() => toDrafts(run));
  const [dirty, setDirty] = useState(false);
  const [showErrors, setShowErrors] = useState(false);
  const config = (run.config ?? {}) as { depth?: Depth; sources?: SourceType[] };
  const defaultSources = config.sources ?? ["WEB"];
  const sections = run.estimate?.sections ?? 5;
  // the API sends the run's size (preset or custom); fall back to the preset if it is missing
  const size = run.estimate?.sourcesPerQuestion
    ? { sourcesPerQuestion: run.estimate.sourcesPerQuestion, minutesPerQuestion: run.estimate.minutesPerQuestion ?? 3 }
    : sizeOf(config.depth === "CUSTOM" ? "STANDARD" : (config.depth ?? "STANDARD"))!;
  const est = estimate(size, cards.length, sections);
  const invalid = cards.some((c) => textLength(c.text) < TEXT_MIN || textLength(c.text) > TEXT_MAX);
  const error = update.error ?? approve.error;
  const conflict = error instanceof ApiError && error.code === "VERSION_CONFLICT";
  const busy = update.isPending || approve.isPending || approve.isSuccess;

  function change(next: Draft[]) {
    setCards(next);
    setDirty(true);
    update.reset();
    approve.reset();
  }
  const patch = (key: string, p: Partial<Draft>) => change(cards.map((c) => (c.key === key ? { ...c, ...p } : c)));
  const move = (i: number, by: number) => {
    const next = [...cards];
    [next[i], next[i + by]] = [next[i + by], next[i]];
    change(next);
  };
  const body = (): PlanCard[] =>
    cards.map((c) => ({ id: c.id, text: c.text.trim(), sourceTypes: c.sourceTypes, queries: c.queries }));

  async function save(): Promise<string | null> {
    if (invalid) {
      setShowErrors(true);
      return null;
    }
    const saved = await update.mutateAsync({ cards: body(), version: etagOf(run) });
    return etagOf(saved);
  }

  async function start() {
    try {
      const version = dirty ? await save() : etagOf(run);
      if (!version) return;
      await approve.mutateAsync(version);
      router.push(`/research/${id}/live`);
    } catch {
      // shown below from the mutation's error
    }
  }

  return (
    <div className="mx-auto max-w-5xl">
      <Overline>Plan review</Overline>
      <h1 className="mt-3 text-display-lg font-semibold">Review your plan</h1>
      <p className="mt-2 max-w-3xl text-body text-muted-foreground">{run.topic}</p>

      <div className="mt-8 grid items-start gap-6 lg:grid-cols-[1fr_20rem]">
        <section aria-labelledby="sub-questions">
          <div className="flex items-center justify-between gap-3">
            <h2 id="sub-questions" className="text-heading-sm font-semibold">
              Sub-questions <span className="tabular text-muted-foreground">({cards.length})</span>
            </h2>
            <p className="text-caption text-subtle-foreground">
              {MIN_SUB_QUESTIONS}–{MAX_SUB_QUESTIONS} questions · edit, reorder or remove
            </p>
          </div>
          <ol className="mt-4 space-y-3">
            {cards.map((c, i) => (
              <QuestionCard
                key={c.key}
                index={i}
                count={cards.length}
                card={c}
                showError={showErrors}
                onText={(text) => patch(c.key, { text })}
                onSources={(sourceTypes) => patch(c.key, { sourceTypes })}
                onMove={(by) => move(i, by)}
                onRemove={() => change(cards.filter((x) => x.key !== c.key))}
              />
            ))}
          </ol>
          <Button
            className="mt-4 w-full"
            variant="secondary"
            disabled={cards.length >= MAX_SUB_QUESTIONS}
            onClick={() =>
              change([...cards, { key: crypto.randomUUID(), text: "", sourceTypes: defaultSources, queries: [] }])
            }
          >
            <Plus aria-hidden /> Add a sub-question
          </Button>
        </section>

        <aside aria-labelledby="estimate" className="rounded-lg border border-border bg-surface p-6 lg:sticky lg:top-6">
          <h2 id="estimate" className="text-heading-sm font-semibold">
            Estimate
          </h2>
          {est ? (
            <>
              <p className="mt-3 text-body-sm text-muted-foreground">
                <span className="tabular text-display-lg font-semibold text-foreground" data-testid="estimate-total">
                  {est.total}
                </span>{" "}
                AI requests
              </p>
              <p className="mt-1 inline-flex items-center gap-2 text-body-sm text-muted-foreground">
                <Clock aria-hidden className="size-4" /> About <span className="tabular">{est.minutes}</span> min
              </p>
              <dl className="mt-4 space-y-1.5 text-body-sm">
                {(["PLAN", "EXTRACT", "VERIFY", "WRITE"] as const).map((slot) => (
                  <div key={slot} className="flex justify-between">
                    <dt className="text-muted-foreground">{slot.charAt(0) + slot.slice(1).toLowerCase()}</dt>
                    <dd className="tabular">{est.requests[slot]}</dd>
                  </div>
                ))}
              </dl>
              <PerConnection run={run} requests={est.requests} />
            </>
          ) : (
            <p className="mt-3 text-body-sm text-muted-foreground">Keep 1–8 sub-questions to see the estimate.</p>
          )}
          {(run.estimate?.unroutedSlots?.length ?? 0) > 0 && (
            <Alert tone="neutral" className="mt-4">
              No AI model for {run.estimate!.unroutedSlots!.join(", ").toLowerCase()} yet. Set one in Settings → AI before
              the research reaches that step.
            </Alert>
          )}
          {error && (
            <Alert className="mt-4">
              {conflict
                ? "This plan changed in another tab. Reload to see the latest version — your edits here will be replaced."
                : error instanceof ApiError
                  ? error.message
                  : "Couldn't save the plan. Check your connection and try again."}
            </Alert>
          )}
          {conflict && (
            <Button className="mt-2 w-full" variant="secondary" onClick={onReload}>
              Reload
            </Button>
          )}
          {showErrors && invalid && (
            <Alert className="mt-4">
              Each sub-question needs {TEXT_MIN}–{TEXT_MAX} characters.
            </Alert>
          )}
          <div className="mt-6 flex flex-col gap-2 border-t border-border pt-5">
            <Button variant="primary" size="lg" loading={approve.isPending} disabled={busy || !est} onClick={() => void start()}>
              {approve.isSuccess ? <Check aria-hidden /> : <Sparkles aria-hidden />} Approve and start
            </Button>
            {dirty && (
              <Button variant="secondary" loading={update.isPending} disabled={busy} onClick={() => void save().catch(() => {})}>
                Save changes
              </Button>
            )}
            <p className="text-caption text-subtle-foreground">Your edits are saved when you approve.</p>
          </div>
        </aside>
      </div>

    </div>
  );
}

type QuestionCardProps = {
  index: number;
  count: number;
  card: Draft;
  showError: boolean;
  onText: (text: string) => void;
  onSources: (s: SourceType[]) => void;
  onMove: (by: number) => void;
  onRemove: () => void;
};

function QuestionCard({ index, count, card, showError, onText, onSources, onMove, onRemove }: QuestionCardProps) {
  const fieldId = useId();
  const len = textLength(card.text);
  const bad = showError && (len < TEXT_MIN || len > TEXT_MAX);
  const toggle = (s: SourceType) =>
    onSources(card.sourceTypes.includes(s) ? card.sourceTypes.filter((x) => x !== s) : [...card.sourceTypes, s]);
  return (
    <li className="rounded-lg border border-border bg-surface p-4" data-testid="plan-card">
      <div className="flex items-start gap-3">
        <span className="tabular mt-2 flex size-7 shrink-0 items-center justify-center rounded-full bg-surface-2 text-caption font-medium">
          {index + 1}
        </span>
        <div className="min-w-0 flex-1">
          <label htmlFor={fieldId} className="sr-only">
            Sub-question {index + 1}
          </label>
          <Textarea
            id={fieldId}
            rows={2}
            value={card.text}
            aria-invalid={bad || undefined}
            placeholder="What should this part of the research answer?"
            className="min-h-16"
            onChange={(e) => onText(e.target.value)}
          />
          <div className="mt-2 flex flex-wrap gap-1.5" role="group" aria-label={`Sources for sub-question ${index + 1}`}>
            {SOURCE_TYPES.map((s) => {
              const on = card.sourceTypes.includes(s.value);
              return (
                <button
                  key={s.value}
                  type="button"
                  aria-pressed={on}
                  onClick={() => toggle(s.value)}
                  className={cn(
                    "inline-flex h-7 items-center gap-1 rounded-sm border px-2 text-caption max-sm:h-9",
                    on ? "border-primary bg-primary-soft text-foreground" : "border-border text-subtle-foreground hover:border-border-strong",
                  )}
                >
                  {on && <Check aria-hidden className="size-3" />}
                  {s.label}
                </button>
              );
            })}
          </div>
        </div>
        <div className="flex shrink-0 flex-col gap-1">
          <Button size="icon" variant="ghost" aria-label={`Move sub-question ${index + 1} up`} disabled={index === 0} onClick={() => onMove(-1)}>
            <ArrowUp aria-hidden />
          </Button>
          <Button
            size="icon"
            variant="ghost"
            aria-label={`Move sub-question ${index + 1} down`}
            disabled={index === count - 1}
            onClick={() => onMove(1)}
          >
            <ArrowDown aria-hidden />
          </Button>
          <Button
            size="icon"
            variant="ghost"
            aria-label={`Delete sub-question ${index + 1}`}
            disabled={count <= MIN_SUB_QUESTIONS}
            onClick={onRemove}
          >
            <Trash2 aria-hidden />
          </Button>
        </div>
      </div>
    </li>
  );
}

/** D-FR-2: which of the user's keys pays for what, against what is left of today's limit. */
function PerConnection({ run, requests }: { run: Run; requests: Record<string, number> }) {
  const view = run.estimate;
  if (!view?.routes?.length) return null;
  const byConnection = new Map<string, number>();
  for (const r of view.routes) {
    if (r.slot && r.connectionId) byConnection.set(r.connectionId, (byConnection.get(r.connectionId) ?? 0) + (requests[r.slot] ?? 0));
  }
  return (
    <ul className="mt-5 space-y-2 border-t border-border pt-4">
      {(view.perConnection ?? []).map((c) => {
        const n = byConnection.get(c.connectionId!) ?? 0;
        const pct = c.remainingToday ? Math.round((1000 * n) / c.remainingToday) / 10 : null;
        return (
          <li key={c.connectionId} className="text-body-sm">
            <p className="font-medium">{c.label}</p>
            <p className="text-caption text-muted-foreground">
              <span className="tabular">{n}</span> requests
              {pct !== null ? (
                <>
                  {" "}
                  · <span className="tabular">{pct}%</span> of today&apos;s remaining
                </>
              ) : (
                " · daily limit not known yet"
              )}
            </p>
          </li>
        );
      })}
    </ul>
  );
}
