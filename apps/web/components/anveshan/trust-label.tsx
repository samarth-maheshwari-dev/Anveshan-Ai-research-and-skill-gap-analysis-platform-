import { CircleCheck, CircleHelp, Swords, TriangleAlert, type LucideIcon } from "lucide-react";

import { cn } from "@/lib/utils";

export type TrustLabelValue = "SUPPORTED" | "WEAK" | "CONFLICTING" | "NO_SOURCE";

/** APPFLOW §2.4: always icon + word, never colour alone. */
export const TRUST: Record<TrustLabelValue, { word: string; Icon: LucideIcon; tone: string }> = {
  SUPPORTED: { word: "Supported", Icon: CircleCheck, tone: "bg-trust-supported-bg text-trust-supported" },
  WEAK: { word: "Weak evidence", Icon: TriangleAlert, tone: "bg-trust-weak-bg text-trust-weak" },
  CONFLICTING: { word: "Conflicting", Icon: Swords, tone: "bg-trust-conflict-bg text-trust-conflict" },
  NO_SOURCE: { word: "No source found", Icon: CircleHelp, tone: "bg-surface-2 text-muted-foreground" },
};

export function isTrustLabel(v: unknown): v is TrustLabelValue {
  return typeof v === "string" && v in TRUST;
}

type TrustLabelProps = { label: TrustLabelValue; className?: string; title?: string };

/** A claim's trust label as a small badge. */
export function TrustLabel({ label, className, title }: TrustLabelProps) {
  const t = TRUST[label];
  return (
    <span
      title={title}
      className={cn("inline-flex h-6 items-center gap-1.5 rounded-sm px-2 text-caption font-medium", t.tone, className)}
    >
      <t.Icon aria-hidden className="size-3.5" />
      {t.word}
    </span>
  );
}

/** A source's credibility tier as a small badge with its reason on hover. */
export function TierBadge({ tier, reason }: { tier: string | null | undefined; reason?: string | null }) {
  const word = tier === "HIGH" ? "High" : tier === "MEDIUM" ? "Medium" : tier === "LOW" ? "Low" : "—";
  return (
    <span
      title={reason ?? undefined}
      className={cn(
        "inline-flex h-6 shrink-0 items-center rounded-sm px-2 text-caption font-medium",
        tier === "HIGH" ? "bg-trust-supported-bg text-trust-supported" : tier === "MEDIUM" ? "bg-surface-2 text-foreground" : "bg-surface-2 text-muted-foreground",
      )}
    >
      {word} credibility
    </span>
  );
}
