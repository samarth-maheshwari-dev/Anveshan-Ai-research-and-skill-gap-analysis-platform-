"use client";

import { ArrowLeft, ExternalLink, Lock } from "lucide-react";
import { useEffect, useState } from "react";

import { Button, buttonVariants } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import type { ProviderInfo } from "@/lib/providers";
import { cn } from "@/lib/utils";

type GetKeyStepProps = { provider: ProviderInfo; onBack: () => void; onNext: () => void };

export function GetKeyStep({ provider, onBack, onNext }: GetKeyStepProps) {
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    const t = window.setTimeout(() => setSlow(true), 120_000);
    return () => window.clearTimeout(t);
  }, []);

  return (
    <Card>
      <h2 className="text-heading-md font-semibold">Get your {provider.name} key</h2>
      <ol className="mt-4 list-decimal space-y-2 pl-5 text-body">
        {provider.steps.map((s) => (
          <li key={s}>{s}</li>
        ))}
      </ol>
      {provider.keyUrl && (
        <a
          href={provider.keyUrl}
          target="_blank"
          rel="noopener noreferrer"
          className={cn(buttonVariants({ variant: "soft" }), "mt-6")}
        >
          {provider.keyUrlLabel} <ExternalLink aria-hidden />
          <span className="sr-only">(opens in a new tab)</span>
        </a>
      )}
      <PrivacyNote />
      {slow && provider.id === "GEMINI" && (
        <p role="status" className="mt-4 text-body-sm text-muted-foreground">
          Prefer a different provider? Groq is free too and only needs an email sign-up.
        </p>
      )}
      <div className="mt-6 flex justify-between">
        <Button variant="ghost" onClick={onBack}>
          <ArrowLeft aria-hidden /> Back
        </Button>
        <Button variant="primary" onClick={onNext}>
          I have my key
        </Button>
      </div>
    </Card>
  );
}

export function PrivacyNote() {
  return (
    <p className="mt-6 flex items-start gap-2 rounded-md bg-surface-2 p-3 text-body-sm text-muted-foreground">
      <Lock aria-hidden className="mt-0.5 size-4 shrink-0" />
      Your key is encrypted and used only for your own requests. Anveshan never shows it again — only its last four
      characters.
    </p>
  );
}
