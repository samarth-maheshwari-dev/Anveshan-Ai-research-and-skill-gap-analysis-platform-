import type { ReactNode } from "react";

import { TRUST, isTrustLabel } from "@/components/anveshan/trust-label";
import type { ReportClaim, ReportSource } from "@/lib/api/hooks/reports";

/** A TipTap JSON node as the API stores it (TECHSPEC §12.9). */
type TipTapNode = {
  type?: string;
  text?: string;
  attrs?: Record<string, unknown>;
  marks?: { type?: string }[];
  content?: TipTapNode[];
};

type Props = {
  doc: unknown;
  claims: Record<string, ReportClaim>;
  sources: Record<string, ReportSource>;
};

/**
 * Read-only renderer for a report section (P14). Only the node types the assembler writes are rendered — nothing
 * from the document is ever injected as HTML. Citations show [n]; weak and conflicting ones carry their label icon.
 * Clicking a citation to open the exact passage arrives with the Report Studio (P15).
 */
export function TipTapView({ doc, claims, sources }: Props) {
  const root = doc as TipTapNode;
  return <div className="space-y-3 text-body leading-relaxed">{render(root.content ?? [], claims, sources, "n")}</div>;
}

function render(nodes: TipTapNode[], claims: Props["claims"], sources: Props["sources"], prefix: string): ReactNode[] {
  return nodes.map((n, i) => node(n, claims, sources, `${prefix}-${i}`));
}

function node(n: TipTapNode, claims: Props["claims"], sources: Props["sources"], key: string): ReactNode {
  const kids = render(n.content ?? [], claims, sources, key);
  switch (n.type) {
    case "paragraph":
      return <p key={key}>{kids}</p>;
    case "heading":
      return (
        <h3 key={key} className="pt-2 text-heading-sm font-semibold">
          {kids}
        </h3>
      );
    case "bulletList":
      return (
        <ul key={key} className="list-disc space-y-1 pl-6">
          {kids}
        </ul>
      );
    case "orderedList":
      return (
        <ol key={key} className="list-decimal space-y-1 pl-6">
          {kids}
        </ol>
      );
    case "listItem":
      return <li key={key}>{kids}</li>;
    case "blockquote":
      return (
        <blockquote key={key} className="border-l-2 border-border-strong pl-4 text-muted-foreground">
          {kids}
        </blockquote>
      );
    case "codeBlock":
      return (
        <pre key={key} className="overflow-x-auto rounded-md bg-surface-2 p-3 font-mono text-body-sm">
          <code>{kids}</code>
        </pre>
      );
    case "horizontalRule":
      return <hr key={key} className="border-border" />;
    case "hardBreak":
      return <br key={key} />;
    case "citation":
      return <CitationMark key={key} attrs={n.attrs ?? {}} claims={claims} sources={sources} />;
    case "text": {
      let out: ReactNode = n.text ?? "";
      for (const m of n.marks ?? []) {
        if (m.type === "bold") out = <strong>{out}</strong>;
        else if (m.type === "italic") out = <em>{out}</em>;
        else if (m.type === "code") out = <code className="rounded-xs bg-surface-2 px-1 font-mono text-body-sm">{out}</code>;
      }
      return <span key={key}>{out}</span>;
    }
    default:
      return null; // unknown node types are never rendered
  }
}

function CitationMark({
  attrs,
  claims,
  sources,
}: {
  attrs: Record<string, unknown>;
  claims: Props["claims"];
  sources: Props["sources"];
}) {
  const n = Number(attrs.n);
  const claim = claims[String(attrs.claimId)];
  const source = sources[String(attrs.sourceId)];
  const label = isTrustLabel(attrs.label) ? attrs.label : undefined;
  const flagged = label === "WEAK" || label === "CONFLICTING";
  const t = label ? TRUST[label] : undefined;
  const title = [t?.word, claim?.text, source?.title].filter(Boolean).join(" — ");
  return (
    <sup className="ml-0.5 whitespace-nowrap">
      <span
        title={title}
        aria-label={`Citation ${n}${t ? `, ${t.word}` : ""}`}
        className="inline-flex items-center gap-0.5 rounded-xs px-0.5 font-mono text-micro font-medium text-primary"
      >
        [{n}]
        {flagged && t && <t.Icon aria-hidden className={label === "WEAK" ? "size-3 text-trust-weak" : "size-3 text-trust-conflict"} />}
      </span>
    </sup>
  );
}
