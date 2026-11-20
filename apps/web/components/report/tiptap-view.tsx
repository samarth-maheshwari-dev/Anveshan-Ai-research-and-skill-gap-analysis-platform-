import type { ReactNode } from "react";

import { CitationChip } from "./citation-chip";

/** A TipTap JSON node as the API stores it (TECHSPEC §12.9). */
type TipTapNode = {
  type?: string;
  text?: string;
  attrs?: Record<string, unknown>;
  marks?: { type?: string }[];
  content?: TipTapNode[];
};

type Props = { doc: unknown };

/**
 * Read-only renderer for a report section (the code-built Conflicts and References, and view-only reports). Only
 * known node types are rendered — nothing from the document is ever injected as HTML. Citations are clickable chips
 * (claims, sources and the panel come from ReportContext).
 */
export function TipTapView({ doc }: Props) {
  const root = doc as TipTapNode;
  return <div className="space-y-3 text-body leading-relaxed">{render(root.content ?? [], "n")}</div>;
}

function render(nodes: TipTapNode[], prefix: string): ReactNode[] {
  return nodes.map((n, i) => node(n, `${prefix}-${i}`));
}

function node(n: TipTapNode, key: string): ReactNode {
  const kids = render(n.content ?? [], key);
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
      return (
        <CitationChip key={key} claimId={String(n.attrs?.claimId ?? "")} sourceId={n.attrs?.sourceId as string | undefined} n={n.attrs?.n} label={n.attrs?.label} />
      );
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
