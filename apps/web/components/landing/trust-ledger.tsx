import { CircleCheck, CircleHelp, Swords, TriangleAlert, type LucideIcon } from "lucide-react";

import { Badge } from "@/components/ui/card";
import { cn } from "@/lib/utils";

type Row = { label: string; Icon: LucideIcon; tone: string; claim: string; reason: string };

/** The four trust labels (PRD §19.5) with one worked example each — what Anveshan actually prints. */
const ROWS: Row[] = [
  {
    label: "Supported",
    Icon: CircleCheck,
    tone: "bg-trust-supported-bg text-trust-supported",
    claim: "ViT-B/16 matched ResNet-50 on chest X-ray classification (AUC 0.92).",
    reason: "Two peer-reviewed papers say it, and the exact sentences are quoted.",
  },
  {
    label: "Weak evidence",
    Icon: TriangleAlert,
    tone: "bg-trust-weak-bg text-trust-weak",
    claim: "Transformers need ten times less labelled data in radiology.",
    reason: "Only one blog post says so. No study in the sources backs it.",
  },
  {
    label: "Conflicting",
    Icon: Swords,
    tone: "bg-trust-conflict-bg text-trust-conflict",
    claim: "Vision transformers are more robust to scanner noise than CNNs.",
    reason: "One credible study finds yes, another finds the opposite. Both are shown.",
  },
  {
    label: "No source found",
    Icon: CircleHelp,
    tone: "bg-trust-none-bg text-trust-none",
    claim: "Most Indian hospitals adopted transformer models in 2024.",
    reason: "Nothing Anveshan read supports it — so it is flagged, not quietly kept.",
  },
];

export function TrustLedger() {
  return (
    <ol className="border-t border-border-strong">
      {ROWS.map(({ label, Icon, tone, claim, reason }) => (
        <li key={label} className="grid gap-3 border-b border-border py-6 md:grid-cols-12 md:gap-6">
          <div className="md:col-span-3">
            <Badge className={cn(tone)}>
              <Icon aria-hidden strokeWidth={2} /> {label}
            </Badge>
          </div>
          <p className="text-body text-foreground md:col-span-5">{claim}</p>
          <p className="text-body-sm text-muted-foreground md:col-span-4">{reason}</p>
        </li>
      ))}
    </ol>
  );
}
