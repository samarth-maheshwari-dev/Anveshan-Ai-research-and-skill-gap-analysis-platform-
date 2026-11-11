"use client";

import { ArrowLeft, ArrowRight, Sparkles } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";

import { Alert } from "@/components/anveshan/alert";
import { Stepper } from "@/components/anveshan/stepper";
import { Button, buttonVariants } from "@/components/ui/button";
import { Overline } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import { useCreateRun, useTemplates } from "@/lib/api/hooks/runs";
import { useSlots } from "@/lib/api/hooks/slots";
import { estimate } from "@/lib/estimate";

import { formSize, initialForm, stepErrors, toBody, withTemplate, type FieldErrors, type Form } from "./form-state";
import { ObjectiveStep, ReviewStep, SourcesStep, TopicStep } from "./steps";

const STEPS = ["Topic", "Objective", "Sources", "Review"];
const DEFAULT_SECTIONS = 5;

/**
 * S-05 New Research (APPFLOW §5.1, owner's reference: 4-step stepper). Every choice has a "your own" option
 * (D-097). Nothing is spent until "Start research"; the Planner then proposes a plan the user edits on S-06.
 */
export function NewResearchForm() {
  const router = useRouter();
  const templates = useTemplates();
  const slots = useSlots();
  const create = useCreateRun();
  const [thisYear] = useState(() => new Date().getFullYear());
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<Form>(() => initialForm(thisYear));
  const [shown, setShown] = useState<FieldErrors>({});
  // C-FR-6: the same submission keeps the same key (double click, retry after a network error)
  const [submission, setSubmission] = useState<{ key: string; body: string } | null>(null);

  const missing = slots.data ? (["PLAN", "WRITE"] as const).filter((s) => (slots.data[s]?.length ?? 0) === 0) : [];
  const blocked = missing.length > 0;
  const template = templates.data?.find((t) => t.code === form.templateCode) ?? null;
  const sections = form.ownSections ? form.sections.length : template?.sections?.length || DEFAULT_SECTIONS;
  const size = formSize(form);
  const preview = size ? estimate(size, size.subQuestions, Math.max(1, sections)) : null;

  const set = (patch: Partial<Form>) => {
    setForm((f) => ({ ...f, ...patch }));
    // a corrected field stops showing its error at once
    setShown((e) => {
      const next = { ...e };
      for (const k of Object.keys(patch) as (keyof Form)[]) delete next[k];
      return next;
    });
  };

  /** Shows the step's errors; true when it is fine to move on. */
  function check(s: number): boolean {
    const errors = stepErrors(form, s, thisYear);
    setShown(errors);
    return Object.keys(errors).length === 0;
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (step < STEPS.length - 1) {
      if (check(step)) setStep(step + 1);
      return;
    }
    for (let s = 0; s < STEPS.length - 1; s++) {
      if (!check(s)) return setStep(s);
    }
    if (blocked) return;
    const body = toBody(form);
    const json = JSON.stringify(body);
    const key = submission?.body === json ? submission.key : crypto.randomUUID();
    setSubmission({ key, body: json });
    create.mutate({ body, idempotencyKey: key }, { onSuccess: (run) => router.push(`/research/${run.id}/plan`) });
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
            errors={shown}
            templates={templates}
            onTemplate={(t) => setForm((f) => withTemplate(f, t))}
          />
        )}
        {step === 1 && <ObjectiveStep form={form} set={set} errors={shown} template={template} />}
        {step === 2 && <SourcesStep form={form} set={set} errors={shown} />}
        {step === 3 && <ReviewStep form={form} template={template} preview={preview} onEdit={setStep} />}
      </div>

      {create.isError && <Alert className="mt-6">{createErrorText(create.error)}</Alert>}

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
          <Button type="submit" variant="primary">
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
    if (error.status === 429 || error.status === 400) return error.message;
  }
  return "Couldn't start the research. Check your connection and try again.";
}
