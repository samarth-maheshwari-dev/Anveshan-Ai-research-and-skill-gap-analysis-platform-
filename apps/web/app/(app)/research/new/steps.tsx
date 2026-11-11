"use client";

import { Check, Clock, FileText } from "lucide-react";
import type { ReactNode } from "react";

import { ErrorLine } from "@/components/anveshan/error-line";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/card";
import { Input, Select, Textarea } from "@/components/ui/input";
import type { SourceType, Template } from "@/lib/api/hooks/runs";
import type { useTemplates } from "@/lib/api/hooks/runs";
import { MAX_SOURCES, MAX_SUB_QUESTIONS, MIN_SOURCES, MIN_SUB_QUESTIONS, type Estimate } from "@/lib/estimate";
import {
  charCount,
  DEPTH_OPTIONS,
  FIRST_YEAR,
  INSTRUCTIONS_MAX,
  LABEL_MAX,
  LANGUAGES,
  OBJECTIVES,
  OUTPUT_STYLES,
  parseSites,
  SOURCE_LABEL,
  SOURCE_TYPES,
  TIME_RANGES,
  TOPIC_MAX,
  type Language,
  type OutputStyle,
  type TimeRange,
} from "@/lib/research-options";
import { cn } from "@/lib/utils";

import { ChoiceCard, Field, SectionsEditor } from "./fields";
import { startingSections, type FieldErrors, type Form } from "./form-state";

type StepProps = { form: Form; set: (patch: Partial<Form>) => void; errors: FieldErrors };

export function TopicStep({
  form,
  set,
  errors,
  templates,
  onTemplate,
}: StepProps & { templates: ReturnType<typeof useTemplates>; onTemplate: (t: Template | null) => void }) {
  return (
    <div className="space-y-8">
      <Field
        label="What do you want to research?"
        error={errors.topic}
        help={
          <span className="flex justify-between gap-4">
            <span>One clear question works best. You can refine the plan next.</span>
            <span className="tabular">
              {charCount(form.topic)} / {TOPIC_MAX}
            </span>
          </span>
        }
      >
        {(p) => (
          <Textarea
            {...p}
            autoFocus
            rows={4}
            value={form.topic}
            maxLength={TOPIC_MAX * 2}
            placeholder="e.g. Compare lost-and-found apps for college campuses"
            onChange={(e) => set({ topic: e.target.value })}
          />
        )}
      </Field>

      <Field
        label="Anything else the AI should know? (optional)"
        error={errors.instructions}
        help={`Focus, audience, what to skip — e.g. "Focus on India, for 2nd-year students". Up to ${INSTRUCTIONS_MAX} characters.`}
      >
        {(p) => (
          <Textarea
            {...p}
            rows={2}
            className="min-h-16"
            value={form.instructions}
            maxLength={INSTRUCTIONS_MAX * 2}
            onChange={(e) => set({ instructions: e.target.value })}
          />
        )}
      </Field>

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
              help="Start from scratch — choose everything yourself"
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
        <p className="mt-2 text-caption text-subtle-foreground">A template only fills in the next steps — you can change all of it.</p>
      </fieldset>
    </div>
  );
}

export function ObjectiveStep({ form, set, errors, template }: StepProps & { template: Template | null }) {
  const templateSections = template?.sections ?? [];
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
        {form.objective === "OTHER" && (
          <Field className="mt-4" label="Your objective" error={errors.objectiveText} help={`e.g. "Hackathon pitch", "Viva preparation". Up to ${LABEL_MAX} characters.`}>
            {(p) => (
              <Input {...p} autoFocus value={form.objectiveText} maxLength={LABEL_MAX * 2} onChange={(e) => set({ objectiveText: e.target.value })} />
            )}
          </Field>
        )}
      </fieldset>

      <fieldset>
        <legend className="text-caption font-medium">How deep?</legend>
        <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
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
        {form.depth === "CUSTOM" && (
          <div className="mt-4 grid gap-4 sm:grid-cols-2">
            <Field label="Sub-questions" error={errors.subQuestions} help={`${MIN_SUB_QUESTIONS}–${MAX_SUB_QUESTIONS}. You can still edit the plan.`}>
              {(p) => (
                <Input
                  {...p}
                  type="number"
                  inputMode="numeric"
                  min={MIN_SUB_QUESTIONS}
                  max={MAX_SUB_QUESTIONS}
                  value={Number.isNaN(form.subQuestions) ? "" : form.subQuestions}
                  onChange={(e) => set({ subQuestions: e.target.valueAsNumber })}
                />
              )}
            </Field>
            <Field label="Sources per sub-question" error={errors.sourcesPerQuestion} help={`${MIN_SOURCES}–${MAX_SOURCES}. More sources = stronger proof, more requests.`}>
              {(p) => (
                <Input
                  {...p}
                  type="number"
                  inputMode="numeric"
                  min={MIN_SOURCES}
                  max={MAX_SOURCES}
                  value={Number.isNaN(form.sourcesPerQuestion) ? "" : form.sourcesPerQuestion}
                  onChange={(e) => set({ sourcesPerQuestion: e.target.valueAsNumber })}
                />
              )}
            </Field>
          </div>
        )}
      </fieldset>

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Report format">
          {(p) => (
            <Select {...p} value={form.outputStyle} onChange={(e) => set({ outputStyle: e.target.value as OutputStyle })}>
              {OUTPUT_STYLES.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        {form.outputStyle === "CUSTOM" && (
          <Field label="Describe the format" error={errors.outputStyleText} help={`e.g. "Slide notes", "Blog post", "FAQ". Up to ${LABEL_MAX} characters.`}>
            {(p) => (
              <Input {...p} autoFocus value={form.outputStyleText} maxLength={LABEL_MAX * 2} onChange={(e) => set({ outputStyleText: e.target.value })} />
            )}
          </Field>
        )}
      </div>

      <fieldset>
        <legend className="text-caption font-medium">Report sections</legend>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <ChoiceCard
            name="sections"
            checked={!form.ownSections}
            onChange={() => set({ ownSections: false })}
            title={templateSections.length ? `Template's ${templateSections.length} sections` : "Let the AI decide"}
            help={templateSections.length ? templateSections.join(", ") : "A standard research report"}
          />
          <ChoiceCard
            name="sections"
            checked={form.ownSections}
            onChange={() =>
              set({ ownSections: true, sections: form.sections.length ? form.sections : startingSections(template) })
            }
            title="My own sections"
            help="Name, order and remove them yourself"
          />
        </div>
        {form.ownSections && (
          <div className="mt-4">
            <SectionsEditor sections={form.sections} onChange={(sections) => set({ sections })} error={errors.sections} />
          </div>
        )}
      </fieldset>
    </div>
  );
}

export function SourcesStep({ form, set, errors }: StepProps) {
  const toggle = (s: SourceType) =>
    set({ sources: form.sources.includes(s) ? form.sources.filter((x) => x !== s) : [...form.sources, s] });
  const thisYear = new Date().getFullYear();
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
        <p id="sources-help" className={cn("mt-2 text-caption", errors.sources ? "text-danger" : "text-subtle-foreground")}>
          {errors.sources ?? "Each sub-question can still change this on the plan."}
        </p>
      </fieldset>

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Prefer these websites (optional)" error={errors.includeSites} help="Comma-separated, e.g. ieee.org, arxiv.org">
          {(p) => <Input {...p} value={form.includeSites} placeholder="ieee.org, arxiv.org" onChange={(e) => set({ includeSites: e.target.value })} />}
        </Field>
        <Field label="Avoid these websites (optional)" error={errors.excludeSites} help="Comma-separated, e.g. quora.com">
          {(p) => <Input {...p} value={form.excludeSites} placeholder="quora.com" onChange={(e) => set({ excludeSites: e.target.value })} />}
        </Field>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Time range">
          {(p) => (
            <Select {...p} value={form.timeRange} onChange={(e) => set({ timeRange: e.target.value as TimeRange })}>
              {TIME_RANGES.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        {form.timeRange === "SINCE_YEAR" && (
          <Field label="From year" error={errors.sinceYear} help={`${FIRST_YEAR}–${thisYear}`}>
            {(p) => (
              <Input
                {...p}
                type="number"
                inputMode="numeric"
                min={FIRST_YEAR}
                max={thisYear}
                value={Number.isNaN(form.sinceYear) ? "" : form.sinceYear}
                onChange={(e) => set({ sinceYear: e.target.valueAsNumber })}
              />
            )}
          </Field>
        )}
        <Field label="Report language">
          {(p) => (
            <Select {...p} value={form.language} onChange={(e) => set({ language: e.target.value as Language })}>
              {LANGUAGES.map((l) => (
                <option key={l.value} value={l.value}>
                  {l.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </div>
    </div>
  );
}

export function ReviewStep({
  form,
  template,
  preview,
  onEdit,
}: {
  form: Form;
  template: Template | null;
  preview: Estimate | null;
  onEdit: (step: number) => void;
}) {
  const label = <T extends string>(list: { value: T; label: string }[], v: T) => list.find((x) => x.value === v)?.label ?? v;
  const objective = form.objective === "OTHER" ? form.objectiveText.trim() : label(OBJECTIVES, form.objective);
  const depth =
    form.depth === "CUSTOM"
      ? `${form.subQuestions} sub-questions × ${form.sourcesPerQuestion} sources`
      : label(DEPTH_OPTIONS, form.depth);
  const format = form.outputStyle === "CUSTOM" ? form.outputStyleText.trim() : label(OUTPUT_STYLES, form.outputStyle);
  const sections = form.ownSections ? form.sections.map((s) => s.trim()) : (template?.sections ?? []);
  const time = form.timeRange === "SINCE_YEAR" ? `Since ${form.sinceYear}` : label(TIME_RANGES, form.timeRange);
  const prefer = parseSites(form.includeSites).sites;
  const avoid = parseSites(form.excludeSites).sites;
  return (
    <div>
      <h2 className="text-heading-sm font-semibold">Check and start</h2>
      <dl className="mt-4 divide-y divide-border">
        <Row term="Topic" onEdit={() => onEdit(0)}>
          {form.topic.trim()}
        </Row>
        {form.instructions.trim() && (
          <Row term="Extra instructions" onEdit={() => onEdit(0)}>
            {form.instructions.trim()}
          </Row>
        )}
        <Row term="Template" onEdit={() => onEdit(0)}>
          {template?.name ?? "No template"}
        </Row>
        <Row term="Objective" onEdit={() => onEdit(1)}>
          {objective} · {depth} · {format}
        </Row>
        <Row term="Sections" onEdit={() => onEdit(1)}>
          {sections.length ? sections.join(" → ") : "The AI decides"}
        </Row>
        <Row term="Sources" onEdit={() => onEdit(2)}>
          {form.sources.map((s) => SOURCE_LABEL[s]).join(", ")} · {time} · {label(LANGUAGES, form.language)}
          {prefer.length > 0 && <span className="block text-muted-foreground">Prefer: {prefer.join(", ")}</span>}
          {avoid.length > 0 && <span className="block text-muted-foreground">Avoid: {avoid.join(", ")}</span>}
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

