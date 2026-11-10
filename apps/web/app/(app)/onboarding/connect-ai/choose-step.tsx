"use client";

import Link from "next/link";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { PROVIDERS, type ProviderInfo } from "@/lib/providers";
import { cn } from "@/lib/utils";

const MAIN = [PROVIDERS.GEMINI, PROVIDERS.GROQ];
const OTHERS = [PROVIDERS.OPENROUTER, PROVIDERS.OPENAI, PROVIDERS.ANTHROPIC, PROVIDERS.CUSTOM];

type ChooseStepProps = { onChoose: (p: ProviderInfo) => void; exitHref: string };

export function ChooseStep({ onChoose, exitHref }: ChooseStepProps) {
  const [showOthers, setShowOthers] = useState(false);
  return (
    <div>
      <h2 className="text-heading-md font-semibold">Choose a provider</h2>
      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        {MAIN.map((p) => (
          <ProviderCard key={p.id} provider={p} onChoose={onChoose} />
        ))}
      </div>
      <Button variant="link" className="mt-4" aria-expanded={showOthers} onClick={() => setShowOthers((s) => !s)}>
        I already have a key from another provider
      </Button>
      {showOthers && (
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          {OTHERS.map((p) => (
            <ProviderCard key={p.id} provider={p} onChoose={onChoose} />
          ))}
        </div>
      )}
      <p className="mt-8 text-body-sm text-muted-foreground">
        <Link href={exitHref} className="text-primary underline-offset-4 hover:underline">
          Skip for now
        </Link>{" "}
        — you can browse, but research needs a connected AI.
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
      className="flex flex-col items-start gap-1 rounded-lg border border-border bg-surface p-4 text-left shadow-e1 transition-[transform,box-shadow,border-color] duration-150 hover:-translate-y-0.5 hover:border-border-strong hover:shadow-e2"
    >
      <span className="text-heading-sm font-semibold">{provider.name}</span>
      <span className={cn("text-caption", provider.free ? "text-trust-supported" : "text-muted-foreground")}>
        {provider.tagline}
      </span>
    </button>
  );
}
