import { CircleCheck, CircleHelp, Swords, TriangleAlert, type LucideIcon } from "lucide-react";

import { Badge } from "@/components/ui/card";
import { cn } from "@/lib/utils";

type Row = { label: string; Icon: LucideIcon; tone: string; claim: string; reason: string };

/** The four trust labels (PRD §19.5) with one worked example each — in everyday words. */
const ROWS: Row[] = [
  {
    label: "Supported",
    Icon: CircleCheck,
    tone: "bg-trust-supported-bg text-trust-supported",
    claim: "Water boils at 100 °C at sea level.",
    reason: "Many good sources agree. You can trust this line.",
  },
  {
    label: "Weak evidence",
    Icon: TriangleAlert,
    tone: "bg-trust-weak-bg text-trust-weak",
    claim: "Drinking cold water makes you smarter.",
    reason: "Only one weak source says so. Check it again.",
  },
  {
    label: "Conflicting",
    Icon: Swords,
    tone: "bg-trust-conflict-bg text-trust-conflict",
    claim: "Coffee is good for your heart.",
    reason: "Some sources say yes, some say no. Both are shown.",
  },
  {
    label: "No source found",
    Icon: CircleHelp,
    tone: "bg-trust-none-bg text-trust-none",
    claim: "Most students study better at night.",
    reason: "No source backs this, so it is clearly flagged.",
  },
];

export function TrustLedger() {
  return (
    <ol className="grid gap-4 sm:grid-cols-2">
      {ROWS.map(({ label, Icon, tone, claim, reason }) => (
        <li
          key={label}
          className="rounded-xl border border-border bg-surface p-6 shadow-card transition-transform duration-300 hover:-translate-y-1"
        >
          <Badge className={cn(tone, "h-8 px-3 text-body-sm")}>
            <Icon aria-hidden strokeWidth={2} /> {label}
          </Badge>
          <p className="mt-4 text-body-lg font-semibold text-foreground">&ldquo;{claim}&rdquo;</p>
          <p className="mt-2 text-body text-muted-foreground">{reason}</p>
        </li>
      ))}
    </ol>
  );
}
