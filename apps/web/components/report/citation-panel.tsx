"use client";

import { useQueryClient } from "@tanstack/react-query";
import { ExternalLink, Search } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { Alert } from "@/components/anveshan/alert";
import { ErrorLine } from "@/components/anveshan/error-line";
import { TierBadge, TrustLabel, isTrustLabel } from "@/components/anveshan/trust-label";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import { useFindBetterSources, usePassage } from "@/lib/api/hooks/reports";
import { useRun } from "@/lib/api/hooks/runs";
import { labelExplanation, refreshStatusText, splitHighlight } from "@/lib/report";

const VERDICT: Record<string, string> = {
  SUPPORTS: "Supports",
  PARTIAL: "Partly supports",
  CONTRADICTS: "Contradicts",
  INSUFFICIENT: "Doesn't say",
};

type CitationPanelProps = {
  reportId: string;
  claimId: string;
  /** Find-better-sources runs started in this visit, by claim. */
  refreshes: Record<string, { runId: string; before: string | undefined }>;
  onRefreshStarted: (claimId: string, runId: string, before: string | undefined) => void;
};

/**
 * F-FR-1: the source behind a citation and the exact passage, the quoted span highlighted by the offsets the server
 * stored (L1) — never searched for in the browser. Shows why the label is what it is (each source's verdict) and,
 * for anything not Supported, Find better sources (F-FR-5).
 */
export function CitationPanel({ reportId, claimId, refreshes, onRefreshStarted }: CitationPanelProps) {
  const passage = usePassage(reportId, claimId);
  const find = useFindBetterSources(reportId);
  const markRef = useRef<HTMLElement>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    markRef.current?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [passage.data]);

  if (passage.isPending) {
    return (
      <div className="space-y-3" aria-busy="true">
        <Skeleton className="h-6 w-1/2" />
        <Skeleton className="h-24" />
        <Skeleton className="h-40" />
      </div>
    );
  }
  if (passage.isError || !passage.data) {
    if (passage.error instanceof ApiError && passage.error.status === 404) {
      return <Alert tone="neutral">This citation no longer points to a claim of this report.</Alert>;
    }
    return <ErrorLine message="Couldn't load the passage." onRetry={() => void passage.refetch()} />;
  }

  const p = passage.data;
  const label = isTrustLabel(p.trustLabel) ? p.trustLabel : undefined;
  const parts = splitHighlight(p.chunkText ?? "", p.highlight?.start ?? 0, p.highlight?.end ?? 0);
  const refresh = refreshes[claimId];
  const others = (p.evidence ?? []).filter((e) => !e.own);

  const start = async () => {
    setError(null);
    try {
      const runId = await find.mutateAsync(claimId);
      if (runId) onRefreshStarted(claimId, runId, p.trustLabel);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't start the search. Try again.");
    }
  };

  return (
    <div className="space-y-5" data-testid="citation-panel">
      <div>
        <p className="font-mono text-micro uppercase text-subtle-foreground">Claim</p>
        <p className="mt-1 text-body">{p.claimText}</p>
        {label && <TrustLabel className="mt-2" label={label} />}
        {label && (
          <p className="mt-2 text-body-sm text-muted-foreground" data-testid="label-explanation">
            {labelExplanation(label, p.evidence)}
          </p>
        )}
      </div>

      <div className="border-t border-border pt-4">
        <p className="font-mono text-micro uppercase text-subtle-foreground">Source</p>
        <p className="mt-1 text-body font-medium">{p.source?.title ?? "Untitled source"}</p>
        <p className="mt-0.5 text-body-sm text-muted-foreground">
          {[p.source?.site, p.source?.publishedAt ? new Date(p.source.publishedAt).getFullYear() : null].filter(Boolean).join(" · ")}
        </p>
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <TierBadge tier={p.source?.credibilityTier} reason={p.source?.credibilityReason} />
          {p.source?.credibilityReason && <span className="text-caption text-muted-foreground">{p.source.credibilityReason}</span>}
        </div>
        {p.source?.url && (
          <a
            href={p.source.url}
            target="_blank"
            rel="noopener noreferrer nofollow"
            className="mt-2 inline-flex items-center gap-1 text-body-sm text-primary underline underline-offset-4"
          >
            Open source <ExternalLink aria-hidden className="size-3.5" />
          </a>
        )}
      </div>

      <figure className="border-t border-border pt-4">
        <figcaption className="font-mono text-micro uppercase text-subtle-foreground">Exact passage</figcaption>
        <blockquote className="mt-2 max-h-96 overflow-y-auto whitespace-pre-line text-body-sm leading-relaxed text-muted-foreground">
          {parts.before}
          <mark ref={markRef} data-testid="passage-highlight" className="marker-sweep text-foreground">
            {parts.mark}
          </mark>
          {parts.after}
        </blockquote>
      </figure>

      {others.length === 0 && (
        <div className="border-t border-border pt-4">
          <p className="font-mono text-micro uppercase text-subtle-foreground">Checked against</p>
          <p className="mt-2 text-body-sm text-muted-foreground">No other source in this research was checked for this claim.</p>
        </div>
      )}

      {others.length > 0 && (
        <div className="border-t border-border pt-4">
          <p className="font-mono text-micro uppercase text-subtle-foreground">Checked against</p>
          <ul className="mt-2 space-y-2">
            {others.map((e) => (
              <li key={`${e.sourceId}-${e.verdict}`} className="flex items-start justify-between gap-3 text-body-sm">
                <span className="min-w-0">
                  <span className="block truncate">{e.title}</span>
                  <span className="text-caption text-muted-foreground">{e.site}</span>
                </span>
                <span className="shrink-0 text-caption font-medium">{VERDICT[e.verdict ?? ""] ?? e.verdict}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {label && label !== "SUPPORTED" && (
        <div className="border-t border-border pt-4">
          {refresh ? (
            <RefreshStatus reportId={reportId} claimId={claimId} runId={refresh.runId} before={refresh.before} after={p.trustLabel} />
          ) : (
            <>
              <p className="text-body-sm text-muted-foreground">Look for stronger sources for this claim. It runs in the background.</p>
              <Button className="mt-2" size="sm" onClick={() => void start()} loading={find.isPending}>
                <Search aria-hidden /> Find better sources
              </Button>
            </>
          )}
          {error && <p className="mt-2 text-body-sm text-danger">{error}</p>}
        </div>
      )}
    </div>
  );
}

const FINAL = new Set(["COMPLETED", "FAILED", "CANCELLED"]);

/** Follows the child run; when it ends, the report and this passage reload with the new label. */
function RefreshStatus({ reportId, claimId, runId, before, after }: { reportId: string; claimId: string; runId: string; before?: string; after?: string }) {
  const qc = useQueryClient();
  const run = useRun(runId);
  const status = run.data?.status;
  const done = !!status && FINAL.has(status);

  useEffect(() => {
    if (!done) return;
    void qc.invalidateQueries({ queryKey: ["report", reportId] });
    void qc.invalidateQueries({ queryKey: ["report-passage", reportId, claimId] });
  }, [done, qc, reportId, claimId]);

  return (
    <p role="status" aria-live="polite" className="text-body-sm">
      {refreshStatusText(status, before, done ? after : undefined)}
    </p>
  );
}
