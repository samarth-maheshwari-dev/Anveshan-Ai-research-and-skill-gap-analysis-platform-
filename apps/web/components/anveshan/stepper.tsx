"use client";

import { Check } from "lucide-react";

import { cn } from "@/lib/utils";

type StepperProps = { steps: string[]; current: number; className?: string };

/** Numbered circles joined by a line (owner's reference): done = green check, current = green, upcoming = muted. */
export function Stepper({ steps, current, className }: StepperProps) {
  return (
    <ol className={cn("mt-8 flex items-start", className)} aria-label="Progress">
      {steps.map((label, i) => (
        <li
          key={label}
          aria-current={i === current ? "step" : undefined}
          className="relative flex min-w-0 flex-1 flex-col items-center gap-2 text-center"
        >
          {i > 0 && (
            <span
              aria-hidden
              className={cn(
                "absolute top-4 right-1/2 h-0.5 w-full -translate-y-1/2", // from the previous circle's centre to this one's
                i <= current ? "bg-primary" : "bg-border",
              )}
            />
          )}
          <span
            className={cn(
              "relative z-10 flex size-8 items-center justify-center rounded-full border text-body-sm font-medium tabular",
              i < current && "border-primary bg-primary text-primary-foreground",
              i === current && "border-primary bg-primary-soft text-primary",
              i > current && "border-border bg-surface text-subtle-foreground",
            )}
          >
            {i < current ? <Check aria-hidden className="size-4" strokeWidth={2.5} /> : i + 1}
          </span>
          <span
            className={cn(
              "text-caption",
              i === current ? "font-medium text-foreground" : "text-muted-foreground",
              i !== current && "max-sm:sr-only",
            )}
          >
            {label}
          </span>
          {i < current && <span className="sr-only">(done)</span>}
        </li>
      ))}
    </ol>
  );
}
