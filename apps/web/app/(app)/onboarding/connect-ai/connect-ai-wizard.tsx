"use client";

import { AnimatePresence } from "motion/react";
import * as m from "motion/react-m";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";

import type { TestResult } from "@/lib/api/hooks/connections";
import { stepSlide } from "@/lib/motion";
import { PROVIDERS, type ProviderInfo } from "@/lib/providers";
import { cn } from "@/lib/utils";

import { ChooseStep } from "./choose-step";
import { DoneStep } from "./done-step";
import { GetKeyStep } from "./get-key-step";
import { PasteStep } from "./paste-step";


type Step = "choose" | "get-key" | "paste" | "done";
const STEPS: { id: Step; label: string }[] = [
  { id: "choose", label: "Choose" },
  { id: "get-key", label: "Get key" },
  { id: "paste", label: "Paste & test" },
  { id: "done", label: "Done" },
];

/** S-03 Connect your AI (APPFLOW §4.2–4.3): one working connection in ≤ 2 minutes. */
export function ConnectAiWizard() {
  const router = useRouter();
  const fromSettings = useSearchParams().get("from") === "settings";
  const [step, setStep] = useState<Step>("choose");
  const [provider, setProvider] = useState<ProviderInfo>(PROVIDERS.GEMINI);
  const [result, setResult] = useState<{ test: TestResult; model?: string } | null>(null);
  const exitHref = fromSettings ? "/settings/ai" : "/dashboard";

  function choose(p: ProviderInfo) {
    setProvider(p);
    setStep("get-key");
  }

  return (
    <div className="mx-auto max-w-xl">
      <h1 className="font-display text-display-lg font-medium tracking-tight">Connect your AI</h1>
      <p className="mt-2 text-body text-muted-foreground">
        Anveshan uses your own free AI key, so it stays free for everyone.
      </p>
      <Stepper current={step} />

      <AnimatePresence mode="wait" initial={false}>
        <m.div key={step} variants={stepSlide} initial="hidden" animate="show" exit="exit" className="mt-6">
          {step === "choose" && <ChooseStep onChoose={choose} exitHref={exitHref} />}
          {step === "get-key" && (
            <GetKeyStep provider={provider} onBack={() => setStep("choose")} onNext={() => setStep("paste")} />
          )}
          {step === "paste" && (
            <PasteStep
              provider={provider}
              onBack={() => setStep("get-key")}
              onVerified={(test, model) => {
                setResult({ test, model });
                setStep("done");
              }}
            />
          )}
          {step === "done" && result && (
            <DoneStep
              provider={provider}
              result={result}
              fromSettings={fromSettings}
              onAddAnother={() => {
                setResult(null);
                setStep("choose");
              }}
              onFinish={() => router.push(exitHref)}
            />
          )}
        </m.div>
      </AnimatePresence>
    </div>
  );
}

type StepperProps = { current: Step };

function Stepper({ current }: StepperProps) {
  const index = STEPS.findIndex((s) => s.id === current);
  return (
    <ol className="mt-8 flex items-center gap-2" aria-label="Progress">
      {STEPS.map((s, i) => (
        <li key={s.id} className="flex flex-1 items-center gap-2" aria-current={i === index ? "step" : undefined}>
          <span
            className={cn(
              "flex size-7 shrink-0 items-center justify-center rounded-full border text-caption font-semibold tabular",
              i < index && "border-primary bg-primary text-primary-foreground",
              i === index && "border-primary bg-primary-soft text-primary",
              i > index && "border-border-strong text-muted-foreground",
            )}
          >
            {i + 1}
          </span>
          <span className={cn("hidden text-caption sm:inline", i === index ? "text-foreground" : "text-muted-foreground")}>
            {s.label}
          </span>
          {i < STEPS.length - 1 && <span aria-hidden className="h-px flex-1 bg-border-strong" />}
        </li>
      ))}
    </ol>
  );
}
