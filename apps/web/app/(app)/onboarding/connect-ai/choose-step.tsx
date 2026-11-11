"use client";

import { ArrowRight } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { PROVIDERS, type ProviderInfo } from "@/lib/providers";

const MAIN = [PROVIDERS.GEMINI, PROVIDERS.GROQ];
const OTHERS = [PROVIDERS.OPENROUTER, PROVIDERS.OPENAI, PROVIDERS.ANTHROPIC, PROVIDERS.CUSTOM];

type ChooseStepProps = { onChoose: (p: ProviderInfo) => void; exitHref: string };

export function ChooseStep({ onChoose, exitHref }: ChooseStepProps) {
  const [showOthers, setShowOthers] = useState(false);
  return (
    <div>
      <h2 className="text-heading-md font-semibold">Choose a provider</h2>
      <p className="mt-1 text-body-sm text-muted-foreground">Both have a free tier. You can add more later.</p>
      <div className="mt-5 grid gap-3 sm:grid-cols-2">
        {MAIN.map((p) => (
          <ProviderCard key={p.id} provider={p} onChoose={onChoose} />
        ))}
      </div>
      <Button variant="link" className="mt-5" aria-expanded={showOthers} onClick={() => setShowOthers((s) => !s)}>
        I already have a key from another provider
      </Button>
      {showOthers && (
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          {OTHERS.map((p) => (
            <ProviderCard key={p.id} provider={p} onChoose={onChoose} />
          ))}
        </div>
      )}
      <p className="mt-10 border-t border-border pt-4 text-body-sm text-muted-foreground">
        <Link href={exitHref} className="inline-flex min-h-11 items-center text-foreground underline underline-offset-4 hover:decoration-2">
          Skip for now
        </Link>{" "}
        — you can look around, but research needs a connected AI.
      </p>
    </div>
  );
}

type ProviderCardProps = { provider: ProviderInfo; onChoose: (p: ProviderInfo) => void };

function ProviderCard({ provider, onChoose }: ProviderCardProps) {
  return (
    <button
      type="button"
      onClick={() => onChoose(provider)}
      className="group flex min-h-24 flex-col justify-between gap-4 rounded-lg border border-border-strong bg-surface p-4 text-left transition-colors duration-150 hover:border-foreground"
    >
      <span className="flex w-full items-start justify-between gap-3">
        <span className="text-heading-sm font-semibold">{provider.name}</span>
        <ArrowRight aria-hidden className="size-4 shrink-0 text-muted-foreground transition-colors group-hover:text-foreground" />
      </span>
      <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="text-caption text-muted-foreground">{provider.tagline}</span>
        {provider.free && <span className="rounded-xs bg-primary-soft px-1.5 text-micro font-medium text-primary">Free tier</span>}
      </span>
    </button>
  );
}
