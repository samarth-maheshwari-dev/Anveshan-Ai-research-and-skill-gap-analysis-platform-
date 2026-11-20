"use client";

import { NodeViewWrapper, type ReactNodeViewProps } from "@tiptap/react";

import { TRUST, isTrustLabel } from "@/components/anveshan/trust-label";
import { cn } from "@/lib/utils";

import { useReportContext } from "./report-context";

type CitationChipProps = { claimId: string; sourceId?: string | null; n: unknown; label?: unknown };

/**
 * `[3]` in reading text (DESIGN §9.11): mono chip; weak / conflicting claims also show their label icon (icon +
 * the word in the accessible name). Click opens the citation panel with the exact passage (F-FR-1). The label comes
 * from the claim's current label when known, so an upgrade (Find better sources) shows without a reload.
 */
export function CitationChip({ claimId, sourceId, n, label }: CitationChipProps) {
  const { claims, sources, activeClaimId, openCitation } = useReportContext();
  const claim = claims[claimId];
  const current = claim?.trustLabel ?? label;
  const l = isTrustLabel(current) ? current : undefined;
  const t = l ? TRUST[l] : undefined;
  const flagged = l === "WEAK" || l === "CONFLICTING" || l === "NO_SOURCE";
  const source = sources[String(sourceId ?? claim?.sourceId ?? "")];
  const active = activeClaimId === claimId;
  const name = `Citation ${String(n ?? "")}${t ? `, ${t.word}` : ""}${source ? ` — ${source.title}` : ""}`;
  return (
    <button
      type="button"
      data-citation-chip=""
      data-claim-id={claimId}
      aria-label={name}
      aria-pressed={active}
      title={[t?.word, claim?.text, source?.title].filter(Boolean).join(" — ")}
      disabled={!openCitation}
      onClick={() => openCitation?.(claimId)}
      className={cn(
        "mx-0.5 inline-flex h-5 items-center gap-0.5 rounded-xs border px-1 align-baseline font-mono text-micro font-medium",
        "transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-ring disabled:cursor-default",
        active
          ? "border-primary bg-primary text-primary-foreground"
          : "border-border bg-surface-2 text-foreground hover:border-primary",
      )}
    >
      [{String(n ?? "")}]
      {flagged && t && (
        <t.Icon
          aria-hidden
          className={cn("size-3", active ? "" : l === "WEAK" ? "text-trust-weak" : l === "CONFLICTING" ? "text-trust-conflict" : "text-muted-foreground")}
        />
      )}
    </button>
  );
}

/** The TipTap node view: the same chip, inside the editor (not editable, one character for the cursor). */
export function CitationNodeView({ node }: ReactNodeViewProps) {
  return (
    <NodeViewWrapper as="span" className="inline">
      <CitationChip claimId={String(node.attrs.claimId)} sourceId={node.attrs.sourceId} n={node.attrs.n} label={node.attrs.label} />
    </NodeViewWrapper>
  );
}
