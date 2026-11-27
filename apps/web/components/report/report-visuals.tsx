"use client";

import { Quote } from "lucide-react";
import { useState } from "react";

import { TRUST, type TrustLabelValue } from "@/components/anveshan/trust-label";
import type { components } from "@/lib/api/schema";
import { countCitations, keyFindings, sourceTypes, sourceYears, topSources, type TrustCounts } from "@/lib/report";
import { cn } from "@/lib/utils";

type Report = components["schemas"]["ReportView"];
type Bin = { key: string; label: string; value: number };

const ORDER: TrustLabelValue[] = ["SUPPORTED", "WEAK", "CONFLICTING", "NO_SOURCE"];
const STROKE: Record<TrustLabelValue, string> = {
  SUPPORTED: "stroke-trust-supported",
  WEAK: "stroke-trust-weak",
  CONFLICTING: "stroke-trust-conflict",
  NO_SOURCE: "stroke-trust-none",
};
const INK: Record<TrustLabelValue, string> = {
  SUPPORTED: "text-trust-supported",
  WEAK: "text-trust-weak",
  CONFLICTING: "text-trust-conflict",
  NO_SOURCE: "text-muted-foreground",
};

/** Panel frame shared by every chart: title, one-line "what this shows", then the plot. */
function Panel({ title, note, className, children }: { title: string; note: string; className?: string; children: React.ReactNode }) {
  return (
    <figure className={cn("rounded-xl border border-border bg-surface p-5 shadow-card md:p-6", className)}>
      <figcaption>
        <p className="text-heading-sm font-semibold">{title}</p>
        <p className="mt-0.5 text-caption text-subtle-foreground">{note}</p>
      </figcaption>
      <div className="mt-5">{children}</div>
    </figure>
  );
}

/** Evidence donut: one ring segment per label (status colors), 2px surface gaps; the legend carries icon + word + count. */
function EvidenceDonut({ counts }: { counts: TrustCounts }) {
  const total = ORDER.reduce((s, l) => s + counts[l], 0);
  const [hover, setHover] = useState<TrustLabelValue | null>(null);
  const r = 52;
  const c = 2 * Math.PI * r;
  const gap = total > 1 ? 3 : 0;
  let offset = 0;
  const supportedPct = total ? Math.round((counts.SUPPORTED * 100) / total) : 0;
  const shown = hover ?? "SUPPORTED";
  return (
    <div className="flex flex-col items-center gap-6 sm:flex-row sm:items-center">
      <div className="relative size-40 shrink-0">
        <svg viewBox="0 0 128 128" className="size-full -rotate-90" role="img" aria-label={`${supportedPct}% of claims supported`}>
          <circle cx="64" cy="64" r={r} fill="none" strokeWidth="14" className="stroke-surface-2" />
          {total > 0 &&
            ORDER.filter((l) => counts[l] > 0).map((l) => {
              const len = (counts[l] / total) * c;
              const seg = (
                <circle
                  key={l}
                  cx="64"
                  cy="64"
                  r={r}
                  fill="none"
                  strokeWidth={hover === l ? 18 : 14}
                  strokeDasharray={`${Math.max(len - gap, 1)} ${c}`}
                  strokeDashoffset={-offset}
                  className={cn(STROKE[l], "transition-[stroke-width] duration-200")}
                  onMouseEnter={() => setHover(l)}
                  onMouseLeave={() => setHover(null)}
                >
                  <title>{`${TRUST[l].word}: ${counts[l]}`}</title>
                </circle>
              );
              offset += len;
              return seg;
            })}
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center text-center">
          <span className="tabular font-display text-display-lg font-semibold">
            {total ? Math.round((counts[shown] * 100) / total) : 0}%
          </span>
          <span className="text-caption text-muted-foreground">{TRUST[shown].word.toLowerCase()}</span>
        </div>
      </div>
      <ul className="w-full space-y-2">
        {ORDER.map((l) => {
          const t = TRUST[l];
          return (
            <li
              key={l}
              onMouseEnter={() => setHover(l)}
              onMouseLeave={() => setHover(null)}
              className={cn(
                "flex items-center gap-2 rounded-md px-2 py-1.5 text-body-sm transition-colors",
                hover === l && "bg-surface-2",
              )}
            >
              <t.Icon aria-hidden className={cn("size-4 shrink-0", INK[l])} />
              <span className="flex-1 text-muted-foreground">{t.word}</span>
              <span className="tabular font-semibold text-foreground">{counts[l]}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** Horizontal bars, one hue (magnitude), value labelled at the end, row hover. */
function Bars({ bins, unit }: { bins: Bin[]; unit: string }) {
  const max = Math.max(1, ...bins.map((b) => b.value));
  return (
    <ul className="space-y-3">
      {bins.map((b) => (
        <li key={b.key} className="group" title={`${b.label}: ${b.value} ${unit}`}>
          <div className="flex items-baseline justify-between gap-3 text-body-sm">
            <span className="line-clamp-1 text-muted-foreground group-hover:text-foreground">{b.label}</span>
            <span className="tabular shrink-0 font-semibold">{b.value}</span>
          </div>
          <div className="mt-1.5 h-2 rounded-full bg-surface-2">
            <div
              className="h-full rounded-full bg-primary transition-opacity group-hover:opacity-80"
              style={{ width: `${(b.value / max) * 100}%` }}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}

/** Columns per year, one hue, baseline-anchored with rounded tops; hover shows the exact count. */
function Columns({ bins }: { bins: Bin[] }) {
  const max = Math.max(1, ...bins.map((b) => b.value));
  const [hover, setHover] = useState<string | null>(null);
  const shown = bins.find((b) => b.key === hover);
  return (
    <div>
      <p className="h-5 text-caption text-muted-foreground" aria-live="polite">
        {shown ? `${shown.label}: ${shown.value} source${shown.value === 1 ? "" : "s"}` : "Point at a bar to see the count"}
      </p>
      <div className="mt-2 flex h-36 items-end gap-0.5 border-b border-border-strong" role="list">
        {bins.map((b) => (
          <div
            key={b.key}
            role="listitem"
            aria-label={`${b.label}: ${b.value}`}
            className="flex h-full flex-1 items-end"
            onMouseEnter={() => setHover(b.key)}
            onMouseLeave={() => setHover(null)}
          >
            <div
              className={cn("w-full rounded-t-[4px] bg-primary transition-opacity", hover && hover !== b.key && "opacity-40")}
              style={{ height: b.value ? `${Math.max((b.value / max) * 100, 4)}%` : "0%" }}
            />
          </div>
        ))}
      </div>
      <div className="mt-1.5 flex justify-between text-micro text-subtle-foreground">
        <span>{bins[0]?.label}</span>
        <span>{bins.at(-1)?.label}</span>
      </div>
    </div>
  );
}

/** Report map: the question at the root, every section as a numbered node with how many citations back it. */
function ReportMap({ title, sections }: { title: string; sections: { id: string; title: string; cites: number }[] }) {
  return (
    <div>
      <div className="mx-auto max-w-xl rounded-lg border border-border-strong bg-background px-5 py-4 text-center">
        <p className="font-mono text-micro uppercase text-subtle-foreground">Your question</p>
        <p className="mt-1 font-display text-heading-md font-semibold">{title}</p>
      </div>
      <div aria-hidden className="mx-auto h-6 w-px bg-border-strong" />
      <div className="relative">
        <div aria-hidden className="absolute inset-x-[8%] top-0 hidden h-px bg-border-strong md:block" />
        <ol className="grid gap-3 pt-0 sm:grid-cols-2 md:grid-cols-3 md:pt-6 lg:grid-cols-4">
          {sections.map((s, i) => (
            <li key={s.id} className="relative">
              <span aria-hidden className="absolute -top-6 left-1/2 hidden h-6 w-px bg-border-strong md:block" />
              <a
                href={`#section-${s.id}`}
                className="flex h-full items-start gap-3 rounded-lg border border-border bg-background p-3 transition-[border-color,transform] duration-200 hover:-translate-y-0.5 hover:border-primary"
              >
                <span className="tabular flex size-7 shrink-0 items-center justify-center rounded-full bg-surface-2 font-mono text-caption">
                  {i + 1}
                </span>
                <span className="min-w-0">
                  <span className="line-clamp-2 text-body-sm font-medium">{s.title}</span>
                  <span className="mt-0.5 block text-caption text-subtle-foreground">
                    {s.cites ? `${s.cites} citation${s.cites === 1 ? "" : "s"}` : "Built from the sources"}
                  </span>
                </span>
              </a>
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}

type ReportVisualsProps = { report: Report; counts: TrustCounts; onOpenClaim: (claimId: string) => void };

/** S-08 "At a glance": key findings, evidence donut, source charts and the report map — all counted from the report. */
export function ReportVisuals({ report, counts, onOpenClaim }: ReportVisualsProps) {
  const claims = report.claims ?? {};
  const sources = report.sources ?? {};
  const citations = report.citations ?? [];
  const findings = keyFindings(claims, citations);
  const types = sourceTypes(Object.values(sources));
  const years = sourceYears(Object.values(sources));
  const top = topSources(citations, sources);
  const map = (report.sections ?? []).map((s) => ({ id: s.id!, title: s.title ?? "", cites: countCitations(s.contentDoc) }));

  return (
    <section aria-label="At a glance" className="space-y-4">
      {findings.length > 0 && (
        <div className="rounded-xl border border-border bg-surface p-5 shadow-card md:p-6">
          <p className="text-heading-sm font-semibold">Key findings</p>
          <p className="mt-0.5 text-caption text-subtle-foreground">The supported facts this report relies on most</p>
          <ol className="mt-5 grid gap-3 md:grid-cols-3">
            {findings.map((f, i) => (
              <li key={f.id}>
                <button
                  type="button"
                  onClick={() => onOpenClaim(f.id)}
                  className="flex h-full w-full flex-col gap-3 rounded-lg border border-border bg-background p-4 text-left transition-[border-color] duration-200 hover:border-primary"
                >
                  <span className="flex items-center justify-between">
                    <span className="font-mono text-caption text-primary">0{i + 1}</span>
                    <Quote aria-hidden className="size-4 text-subtle-foreground" />
                  </span>
                  <span className="text-body-sm text-foreground">{f.text}</span>
                  <span className="mt-auto text-caption text-subtle-foreground">See the source →</span>
                </button>
              </li>
            ))}
          </ol>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Panel title="How strong is the evidence?" note="Every cited claim, by its trust label">
          <EvidenceDonut counts={counts} />
        </Panel>
        {top.length > 0 && (
          <Panel title="Sources used most" note="Number of claims each source backs">
            <Bars bins={top} unit="claims" />
          </Panel>
        )}
        {types.length > 0 && (
          <Panel title="What kind of sources" note="All sources read for this report">
            <Bars bins={types} unit="sources" />
          </Panel>
        )}
        {years.length > 1 && (
          <Panel title="How recent are the sources" note="Sources by year published">
            <Columns bins={years} />
          </Panel>
        )}
      </div>

      {map.length > 0 && (
        <Panel title="Report map" note="How the report answers your question — tap a box to jump there">
          <ReportMap title={report.title ?? ""} sections={map} />
        </Panel>
      )}
    </section>
  );
}
