"use client";

import { Check } from "lucide-react";
import { AnimatePresence } from "motion/react";
import * as m from "motion/react-m";
import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";

import { Overline } from "@/components/ui/card";
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
  { id: "paste", label: "Test key" },
  { id: "done", label: "Done" },
];

/** S-03 Connect your AI (APPFLOW §4.2–4.3): one working connection in ≤ 2 minutes. */
export function ConnectAiWizard() {
  const router = useRouter();
  const fromSettings = useSearchParams().get("from") === "settings";
  const [step, setStep] = useState<Step>("choose");
  const [provider, setProvider] = useState<ProviderInfo>(PROVIDERS.GEMINI);
  const [result, setResult] = useState<TestResult | null>(null);
  const exitHref = fromSettings ? "/settings/ai" : "/dashboard";
  const index = STEPS.findIndex((s) => s.id === step);

  function choose(p: ProviderInfo) {
    setProvider(p);
    setStep("get-key");
  }

  return (
    <div className="mx-auto max-w-2xl">
      <Overline>
        Setup · Step {index + 1} of {STEPS.length}
      </Overline>
      <h1 className="mt-3 text-display-lg font-semibold">Connect your AI</h1>
      <p className="mt-2 text-body text-muted-foreground">
        Anveshan runs on your own free AI key, so it stays free for everyone.
      </p>
      <Stepper current={index} />

      <AnimatePresence mode="wait" initial={false}>
        <m.div key={step} variants={stepSlide} initial="hidden" animate="show" exit="exit" className="mt-8">
          {step === "choose" && <ChooseStep onChoose={choose} exitHref={exitHref} />}
          {step === "get-key" && (
            <GetKeyStep provider={provider} onBack={() => setStep("choose")} onNext={() => setStep("paste")} />
          )}
          {step === "paste" && (
            <PasteStep
              provider={provider}
              onBack={() => setStep("get-key")}
              onVerified={(test) => {
                setResult(test);
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

type StepperProps = { current: number };

/** Mono, hairline stepper: done = green check, current = white, upcoming = muted. */
function Stepper({ current }: StepperProps) {
  return (
    <ol className="mt-8 grid grid-cols-4 border-t border-border" aria-label="Progress">
      {STEPS.map((s, i) => (
        <li
          key={s.id}
          aria-current={i === current ? "step" : undefined}
          className={cn(
            "-mt-px flex min-w-0 items-center gap-2 border-t-2 pt-3 pr-2 font-mono text-micro uppercase",
            i < current && "border-primary text-muted-foreground",
            i === current && "border-foreground text-foreground",
            i > current && "border-transparent text-subtle-foreground",
          )}
        >
          {i < current ? (
            <Check aria-hidden className="size-3.5 text-primary" strokeWidth={2.5} />
          ) : (
            <span className="tabular">0{i + 1}</span>
          )}
          <span className={cn(i !== current && "max-sm:sr-only")}>{s.label}</span>
          {i < current && <span className="sr-only">(done)</span>}
        </li>
      ))}
    </ol>
  );
}
