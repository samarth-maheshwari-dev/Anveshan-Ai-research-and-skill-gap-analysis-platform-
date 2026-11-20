import { TRUST, type TrustLabelValue } from "@/components/anveshan/trust-label";
import { shares, type TrustCounts } from "@/lib/report";
import { cn } from "@/lib/utils";

const ORDER: TrustLabelValue[] = ["SUPPORTED", "WEAK", "CONFLICTING", "NO_SOURCE"];
const ICON: Record<TrustLabelValue, string> = {
  SUPPORTED: "text-trust-supported",
  WEAK: "text-trust-weak",
  CONFLICTING: "text-trust-conflict",
  NO_SOURCE: "text-muted-foreground",
};
const BAR: Record<TrustLabelValue, string> = {
  SUPPORTED: "bg-trust-supported",
  WEAK: "bg-trust-weak",
  CONFLICTING: "bg-trust-conflict",
  NO_SOURCE: "bg-trust-none",
};

type EvidenceBarProps = { counts: TrustCounts; className?: string };

/**
 * S-08 top (DESIGN §9.11 EvidenceBar): the four labels with counts — icon + word + number — and a thin stacked bar
 * in proportion. The bar is decoration; the words carry the meaning.
 */
export function EvidenceBar({ counts, className }: EvidenceBarProps) {
  const pct = shares(counts);
  const total = ORDER.reduce((s, l) => s + counts[l], 0);
  return (
    <section aria-label="Evidence summary" className={cn("rounded-lg border border-border bg-surface p-4", className)}>
      <ul className="flex flex-wrap gap-x-5 gap-y-2">
        {ORDER.map((l) => {
          const t = TRUST[l];
          return (
            <li key={l} className="inline-flex items-center gap-1.5 text-body-sm" data-label={l}>
              <t.Icon aria-hidden className={cn("size-4", ICON[l])} />
              <span className="tabular font-semibold">{counts[l]}</span>
              <span className="text-muted-foreground">{t.word}</span>
            </li>
          );
        })}
      </ul>
      {total > 0 && (
        <div aria-hidden className="mt-3 flex h-1 overflow-hidden rounded-full bg-surface-2">
          {ORDER.filter((l) => pct[l] > 0).map((l) => (
            <span key={l} className={BAR[l]} style={{ width: `${pct[l]}%` }} />
          ))}
        </div>
      )}
    </section>
  );
}
