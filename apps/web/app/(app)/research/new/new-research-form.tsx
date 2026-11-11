"use client";

import { ArrowLeft, ArrowRight, Check, Clock, FileText, Sparkles } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent, type ReactNode } from "react";

import { Alert } from "@/components/anveshan/alert";
import { ErrorLine } from "@/components/anveshan/error-line";
import { Stepper } from "@/components/anveshan/stepper";
import { Button, buttonVariants } from "@/components/ui/button";
import { Overline, Skeleton } from "@/components/ui/card";
import { Label, Select, Textarea } from "@/components/ui/input";
import { ApiError } from "@/lib/api/errors";
import { useCreateRun, useTemplates, type CreateRun, type SourceType, type Template } from "@/lib/api/hooks/runs";
import { useSlots } from "@/lib/api/hooks/slots";
import { estimate, DEPTHS, type Depth } from "@/lib/estimate";
import {
  DEPTH_OPTIONS,
  LANGUAGES,
  OBJECTIVES,
  OUTPUT_STYLES,
  SOURCE_LABEL,
  SOURCE_TYPES,
  TIME_RANGES,
  TOPIC_MAX,
  TOPIC_MIN,
  topicLength,
  type Language,
  type Objective,
  type OutputStyle,
  type TimeRange,
} from "@/lib/research-options";
import { cn } from "@/lib/utils";

const STEPS = ["Topic", "Objective", "Sources", "Review"];
const DEFAULT_SECTIONS = 5;

type Form = {
  templateCode: string | null;
  topic: string;
  objective: Objective;
  depth: Depth;
  outputStyle: OutputStyle;
  sources: SourceType[];
  timeRange: TimeRange;
  language: Language;
};

const INITIAL: Form = {
  templateCode: null,
  topic: "",
  objective: "PROJECT_REPORT",
  depth: "STANDARD",
  outputStyle: "PROJECT_REPORT",
  sources: ["WEB", "ACADEMIC"],
  timeRange: "ANY",
  language: "en",
};

/** A template pre-fills the form (C-FR-5); the user can still change every field. */
function withTemplate(form: Form, t: Template | null): Form {
  if (!t) return { ...form, templateCode: null };
  const d = (t.formDefaults ?? {}) as Partial<Record<keyof Form, unknown>>;
  return {
    ...form,
    templateCode: t.code ?? null,
    objective: (d.objective as Objective) ?? form.objective,
    depth: (d.depth as Depth) ?? form.depth,
    outputStyle: (d.outputStyle as OutputStyle) ?? form.outputStyle,
    sources: Array.isArray(d.sources) && d.sources.length > 0 ? (d.sources as SourceType[]) : form.sources,
  };
}

/**
 * S-05 New Research (APPFLOW §5.1, owner's reference: 4-step stepper). Nothing is spent until "Start research";
 * the Planner then proposes a plan the user edits and approves on S-06.
 */
export function NewResearchForm() {
  const router = useRouter();
  const templates = useTemplates();
  const slots = useSlots();
  const create = useCreateRun();
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<Form>(INITIAL);
  const [touched, setTouched] = useState(false);
  // C-FR-6: the same submission keeps the same key (double click, retry after a network error)
  const [submission, setSubmission] = useState<{ key: string; body: string } | null>(null);

  const missing = slots.data ? (["PLAN", "WRITE"] as const).filter((s) => (slots.data[s]?.length ?? 0) === 0) : [];
  const blocked = missing.length > 0;
  const len = topicLength(form.topic);
  const topicError =
    len < TOPIC_MIN ? `Write at least ${TOPIC_MIN} characters.` : len > TOPIC_MAX ? `Keep it under ${TOPIC_MAX} characters.` : null;
  const template = templates.data?.find((t) => t.code === form.templateCode) ?? null;
  const sections = template?.sections?.length || DEFAULT_SECTIONS;
  const preview = estimate(form.depth, DEPTHS[form.depth].subQuestions, sections);
  const set = (patch: Partial<Form>) => setForm((f) => ({ ...f, ...patch }));

  function next() {
    if (step === 0 && topicError) {
      setTouched(true);
      return;
    }
    if (step === 2 && form.sources.length === 0) return;
    setStep((s) => Math.min(s + 1, STEPS.length - 1));
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (step < STEPS.length - 1) return next();
    if (blocked || topicError) return;
    const body: CreateRun = {
      topic: form.topic.trim(),
      objective: form.objective,
      depth: form.depth,
      outputStyle: form.outputStyle,
      sources: form.sources,
      timeRange: form.timeRange,
      language: form.language,
      ...(form.templateCode ? { templateCode: form.templateCode } : {}),
    };
    const json = JSON.stringify(body);
    const key = submission?.body === json ? submission.key : crypto.randomUUID();
    setSubmission({ key, body: json });
    create.mutate(
      { body, idempotencyKey: key },
      { onSuccess: (run) => router.push(`/research/${run.id}/plan`) },
    );
  }

  return (
    <form onSubmit={submit} noValidate className="mx-auto max-w-3xl">
      <Overline>
        New research · Step {step + 1} of {STEPS.length}
      </Overline>
      <h1 className="mt-3 text-display-lg font-semibold">Start a new research</h1>
      <p className="mt-2 text-body text-muted-foreground">
        Ask once. You will see the plan and what it costs before anything runs.
      </p>
      <Stepper steps={STEPS} current={step} />

      {blocked && (
        <Alert
          tone="neutral"
          className="mt-8"
          action={
            <Link href="/onboarding/connect-ai" className={buttonVariants({ variant: "primary", size: "sm" })}>
              Connect AI
            </Link>
          }
        >
          Research needs an AI model for {missing.map((s) => (s === "PLAN" ? "planning" : "writing")).join(" and ")}.
          Connect a free key first — it takes about 2 minutes.
        </Alert>
      )}

      <div className="mt-8 rounded-lg border border-border bg-surface p-6 md:p-8">
        {step === 0 && (
          <TopicStep
            form={form}
            set={set}
            templates={templates}
            topicError={touched ? topicError : null}
            length={len}
            onTemplate={(t) => setForm((f) => withTemplate(f, t))}
          />
        )}
        {step === 1 && <ObjectiveStep form={form} set={set} />}
        {step === 2 && <SourcesStep form={form} set={set} />}
        {step === 3 && <ReviewStep form={form} template={template} preview={preview} onEdit={setStep} />}
      </div>

      {create.isError && (
        <Alert className="mt-6">{createErrorText(create.error)}</Alert>
      )}

      <div className="mt-6 flex items-center justify-between gap-3">
        {step === 0 ? (
          <Link href="/dashboard" className={buttonVariants({ variant: "ghost" })}>
            Cancel
          </Link>
        ) : (
          <Button variant="ghost" onClick={() => setStep((s) => s - 1)}>
            <ArrowLeft aria-hidden /> Back
          </Button>
        )}
        {step < STEPS.length - 1 ? (
          <Button type="submit" variant="primary" disabled={step === 2 && form.sources.length === 0}>
            Next <ArrowRight aria-hidden />
          </Button>
        ) : (
          <Button type="submit" variant="primary" loading={create.isPending} disabled={blocked || create.isSuccess}>
            <Sparkles aria-hidden /> Start research
          </Button>
        )}
      </div>
    </form>
  );
}

function createErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "NO_MODEL_FOR_SLOT") return "Set an AI model for planning and writing in Settings → AI, then start again.";
    if (error.status === 429) return error.message;
    if (error.status === 400) return error.message;
  }
  return "Couldn't start the research. Check your connection and try again.";
}

type StepProps = { form: Form; set: (patch: Partial<Form>) => void };

function TopicStep({
  form,
  set,
  templates,
  topicError,
  length,
  onTemplate,
}: StepProps & {
  templates: ReturnType<typeof useTemplates>;
  topicError: string | null;
  length: number;
  onTemplate: (t: Template | null) => void;
}) {
  return (
    <div className="space-y-8">
      <div>
        <Label htmlFor="topic">What do you want to research?</Label>
        <Textarea
          id="topic"
          autoFocus
          rows={4}
          value={form.topic}
          maxLength={TOPIC_MAX * 2}
          aria-invalid={topicError ? true : undefined}
          aria-describedby="topic-help"
          placeholder="e.g. Compare lost-and-found apps for college campuses"
          className="mt-2"
          onChange={(e) => set({ topic: e.target.value })}
        />
        <p id="topic-help" className={cn("mt-1.5 flex justify-between text-caption", topicError ? "text-danger" : "text-subtle-foreground")}>
          <span>{topicError ?? "One clear question works best. You can refine the plan next."}</span>
          <span className="tabular">
            {length} / {TOPIC_MAX}
          </span>
        </p>
      </div>

      <fieldset>
        <legend className="text-caption font-medium">Start from a template (optional)</legend>
        {templates.isPending ? (
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            {[0, 1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-20" />
            ))}
          </div>
        ) : templates.isError ? (
          <ErrorLine message="Couldn't load templates." onRetry={() => void templates.refetch()} />
        ) : (
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <ChoiceCard
              name="template"
              checked={form.templateCode === null}
              onChange={() => onTemplate(null)}
              title="No template"
              help="A general research report"
            />
            {templates.data?.map((t) => (
              <ChoiceCard
                key={t.code}
                name="template"
                checked={form.templateCode === t.code}
                onChange={() => onTemplate(t)}
                title={t.name ?? ""}
                help={`${t.sections?.length ?? 0} sections · ${(t.sections ?? []).slice(0, 2).join(", ")}…`}
              />
            ))}
          </div>
        )}
      </fieldset>
    </div>
  );
}

function ObjectiveStep({ form, set }: StepProps) {
  return (
    <div className="space-y-8">
      <fieldset>
        <legend className="text-caption font-medium">What is it for?</legend>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          {OBJECTIVES.map((o) => (
            <ChoiceCard
              key={o.value}
              name="objective"
              checked={form.objective === o.value}
              onChange={() => set({ objective: o.value })}
              title={o.label}
              help={o.help}
            />
          ))}
        </div>
      </fieldset>
      <fieldset>
        <legend className="text-caption font-medium">How deep?</legend>
        <div className="mt-3 grid gap-3 sm:grid-cols-3">
          {DEPTH_OPTIONS.map((d) => (
            <ChoiceCard
              key={d.value}
              name="depth"
              checked={form.depth === d.value}
              onChange={() => set({ depth: d.value })}
              title={d.label}
              help={d.help}
            />
          ))}
        </div>
      </fieldset>
      <div className="max-w-sm">
        <Label htmlFor="output-style">Output style</Label>
        <Select
          id="output-style"
          className="mt-2"
          value={form.outputStyle}
          onChange={(e) => set({ outputStyle: e.target.value as OutputStyle })}
        >
          {OUTPUT_STYLES.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </Select>
      </div>
    </div>
  );
}

function SourcesStep({ form, set }: StepProps) {
  const toggle = (s: SourceType) =>
    set({ sources: form.sources.includes(s) ? form.sources.filter((x) => x !== s) : [...form.sources, s] });
  return (
    <div className="space-y-8">
      <fieldset aria-describedby="sources-help">
        <legend className="text-caption font-medium">Where should Anveshan look?</legend>
        <div className="mt-3 flex flex-wrap gap-2">
          {SOURCE_TYPES.map((s) => {
            const on = form.sources.includes(s.value);
            return (
              <label
                key={s.value}
                className={cn(
                  "inline-flex min-h-10 cursor-pointer items-center gap-2 rounded-md border px-3 text-body-sm max-sm:min-h-11",
                  "has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring",
                  on ? "border-primary bg-primary-soft text-foreground" : "border-border-strong text-muted-foreground hover:border-foreground",
                )}
              >
                <input type="checkbox" className="sr-only" checked={on} onChange={() => toggle(s.value)} />
                {on && <Check aria-hidden className="size-4 text-primary" />}
                {s.label}
              </label>
            );
          })}
        </div>
        <p id="sources-help" className={cn("mt-2 text-caption", form.sources.length === 0 ? "text-danger" : "text-subtle-foreground")}>
          {form.sources.length === 0 ? "Pick at least one kind of source." : "Each sub-question can still change this on the plan."}
        </p>
      </fieldset>
      <div className="grid gap-6 sm:grid-cols-2">
        <div>
          <Label htmlFor="time-range">Time range</Label>
          <Select
            id="time-range"
            className="mt-2"
            value={form.timeRange}
            onChange={(e) => set({ timeRange: e.target.value as TimeRange })}
          >
            {TIME_RANGES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </Select>
        </div>
        <div>
          <Label htmlFor="language">Report language</Label>
          <Select
            id="language"
            className="mt-2"
            value={form.language}
            onChange={(e) => set({ language: e.target.value as Language })}
          >
            {LANGUAGES.map((l) => (
              <option key={l.value} value={l.value}>
                {l.label}
              </option>
            ))}
          </Select>
        </div>
      </div>
    </div>
  );
}

function ReviewStep({
  form,
  template,
  preview,
  onEdit,
}: {
  form: Form;
  template: Template | null;
  preview: ReturnType<typeof estimate>;
  onEdit: (step: number) => void;
}) {
  const label = <T extends string>(list: { value: T; label: string }[], v: T) => list.find((x) => x.value === v)?.label ?? v;
  return (
    <div>
      <h2 className="text-heading-sm font-semibold">Check and start</h2>
      <dl className="mt-4 divide-y divide-border">
        <Row term="Topic" onEdit={() => onEdit(0)}>
          {form.topic.trim()}
        </Row>
        <Row term="Template" onEdit={() => onEdit(0)}>
          {template?.name ?? "No template"}
        </Row>
        <Row term="Objective" onEdit={() => onEdit(1)}>
          {label(OBJECTIVES, form.objective)} · {label(DEPTH_OPTIONS, form.depth)} · {label(OUTPUT_STYLES, form.outputStyle)}
        </Row>
        <Row term="Sources" onEdit={() => onEdit(2)}>
          {form.sources.map((s) => SOURCE_LABEL[s]).join(", ")} · {label(TIME_RANGES, form.timeRange)} ·{" "}
          {label(LANGUAGES, form.language)}
        </Row>
      </dl>
      {preview && (
        <div className="mt-6 flex flex-wrap items-center gap-x-6 gap-y-2 rounded-md bg-background px-4 py-3 text-body-sm">
          <span className="inline-flex items-center gap-2">
            <FileText aria-hidden className="size-4 text-muted-foreground" />
            About <strong className="tabular">{preview.total}</strong> AI requests on your own keys
          </span>
          <span className="inline-flex items-center gap-2">
            <Clock aria-hidden className="size-4 text-muted-foreground" />
            About <strong className="tabular">{preview.minutes}</strong> min
          </span>
          <span className="text-caption text-subtle-foreground">
            First the AI writes a plan (1 request). Nothing else runs until you approve it.
          </span>
        </div>
      )}
    </div>
  );
}

function Row({ term, children, onEdit }: { term: string; children: ReactNode; onEdit: () => void }) {
  return (
    <div className="flex items-start justify-between gap-4 py-3">
      <div className="min-w-0">
        <dt className="text-caption text-subtle-foreground">{term}</dt>
        <dd className="mt-0.5 break-words text-body-sm">{children}</dd>
      </div>
      <Button variant="link" size="sm" onClick={onEdit} aria-label={`Edit ${term.toLowerCase()}`}>
        Edit
      </Button>
    </div>
  );
}

type ChoiceCardProps = { name: string; checked: boolean; onChange: () => void; title: string; help: string };

/** A radio as a card: the whole card is the hit area; checked = green border + check (never colour alone). */
function ChoiceCard({ name, checked, onChange, title, help }: ChoiceCardProps) {
  return (
    <label
      className={cn(
        "relative flex cursor-pointer flex-col gap-1 rounded-md border p-4 transition-[border-color] duration-150",
        "has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring",
        checked ? "border-primary bg-primary-soft" : "border-border bg-background hover:border-border-strong",
      )}
    >
      <input type="radio" name={name} className="sr-only" checked={checked} onChange={onChange} />
      <span className="flex items-center justify-between gap-2 text-body-sm font-medium">
        {title}
        {checked && <Check aria-hidden className="size-4 text-primary" />}
      </span>
      <span className="text-caption text-muted-foreground">{help}</span>
    </label>
  );
}
