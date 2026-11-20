/**
 * Pure helpers for the Report Studio (S-08). No React, no fetch — unit-tested in lib/__tests__/report.test.ts.
 */

import type { components } from "@/lib/api/schema";

type Evidence = Pick<components["schemas"]["EvidenceView"], "own" | "verdict" | "credibilityTier">;

export type TrustCounts = { SUPPORTED: number; WEAK: number; CONFLICTING: number; NO_SOURCE: number };

/** EvidenceBar counts: one per cited claim, by its current label (PRD §5.4.2: shown first). */
export function countLabels(labels: (string | null | undefined)[]): TrustCounts {
  const c: TrustCounts = { SUPPORTED: 0, WEAK: 0, CONFLICTING: 0, NO_SOURCE: 0 };
  for (const l of labels) if (l && l in c) c[l as keyof TrustCounts]++;
  return c;
}

/** Share of each label for the stacked bar, in whole percents that add up to 100 (largest remainder). */
export function shares(counts: TrustCounts): TrustCounts {
  const keys = Object.keys(counts) as (keyof TrustCounts)[];
  const total = keys.reduce((s, k) => s + counts[k], 0);
  const out: TrustCounts = { SUPPORTED: 0, WEAK: 0, CONFLICTING: 0, NO_SOURCE: 0 };
  if (total === 0) return out;
  const raw = keys.map((k) => ({ k, v: (counts[k] * 100) / total }));
  raw.forEach(({ k, v }) => (out[k] = Math.floor(v)));
  let left = 100 - keys.reduce((s, k) => s + out[k], 0);
  for (const { k } of [...raw].sort((a, b) => (b.v % 1) - (a.v % 1))) {
    if (left-- <= 0) break;
    out[k]++;
  }
  return out;
}

/**
 * F-FR-1: the chunk split around the quoted span [start, end) the server computed (L1). Out-of-range offsets are
 * clamped, so a broken span never throws — it just highlights less.
 */
export function splitHighlight(text: string, start: number, end: number): { before: string; mark: string; after: string } {
  const s = Math.max(0, Math.min(text.length, Math.floor(start)));
  const e = Math.max(s, Math.min(text.length, Math.floor(end)));
  return { before: text.slice(0, s), mark: text.slice(s, e), after: text.slice(e) };
}

/** A TipTap node as the API stores it. */
export type DocNode = { type?: string; text?: string; attrs?: Record<string, unknown>; content?: DocNode[] };

/**
 * What an Assist suggestion inserts: a single paragraph goes in as inline content (so a selection inside a
 * sentence stays inside that sentence); anything bigger goes in as blocks.
 */
export function insertableContent(fragment: DocNode | null | undefined): DocNode[] {
  const blocks = fragment?.content ?? [];
  if (blocks.length === 1 && blocks[0].type === "paragraph") return blocks[0].content ?? [];
  return blocks;
}

/** Plain text of a fragment, citations as [n] (for the suggestion preview's accessible text). */
export function plainText(node: DocNode | null | undefined): string {
  if (!node) return "";
  if (node.type === "text") return node.text ?? "";
  if (node.type === "citation") return `[${String(node.attrs?.n ?? "")}]`;
  const inner = (node.content ?? []).map(plainText);
  const block = node.type === "doc" || node.type === "bulletList" || node.type === "orderedList" || node.type === "listItem";
  return inner.join(block ? "\n" : "");
}

/** Sections built by code from the sources (F-FR-11, references): read, never edited. */
export const GENERATED_SECTIONS = new Set(["conflicts", "references"]);

/** Autosave every 30 s while editing (F-FR-6). */
export const AUTOSAVE_MS = 30_000;

/** "Saved", "Saving…", "Unsaved changes" — the editor's save state, in words. */
export type SaveState = "saved" | "dirty" | "saving" | "conflict" | "error";

export function saveStateText(state: SaveState): string {
  switch (state) {
    case "saved":
      return "All changes saved";
    case "dirty":
      return "Unsaved changes";
    case "saving":
      return "Saving…";
    case "conflict":
      return "Changed somewhere else — reload to see the latest";
    case "error":
      return "Couldn't save — trying again on the next save";
  }
}

/** Status words for a Find-better-sources child run. */
export function refreshStatusText(status: string | undefined, before?: string, after?: string): string {
  if (status === "COMPLETED") {
    if (after && before && after !== before) return "Found stronger evidence — the label was updated.";
    return "No stronger sources found. The claim keeps its label.";
  }
  if (status === "FAILED" || status === "CANCELLED") return "The search for better sources stopped. Try again later.";
  if (status === "PAUSED") return "Paused: your AI limit was reached. It continues when the limit resets.";
  return "Looking for stronger sources…";
}

/**
 * Why a claim has its label, in one sentence, from the verdicts the server stored (the label itself is computed by
 * code on the server — PRD §14.3 + D-106 independent-source rule). Shown under the label in the citation panel.
 */
export function labelExplanation(label: string | undefined, evidence: Evidence[] | undefined): string {
  const all = evidence ?? [];
  const others = all.filter((e) => !e.own);
  const credible = (e: Evidence) => e.credibilityTier === "HIGH" || e.credibilityTier === "MEDIUM";
  const otherSupports = others.filter((e) => e.verdict === "SUPPORTS");
  const contradicts = others.filter((e) => e.verdict === "CONTRADICTS" && credible(e)).length;
  const plural = (n: number, one: string) => `${n} ${one}${n === 1 ? "" : "s"}`;
  switch (label) {
    case "SUPPORTED": {
      const n = otherSupports.filter(credible).length;
      return `Confirmed by ${plural(n, "other credible source")}, not only the one it came from.`;
    }
    case "CONFLICTING":
      return `Sources disagree: ${plural(contradicts, "credible source")} ${contradicts === 1 ? "says" : "say"} the opposite. Read both sides before you rely on it.`;
    case "WEAK":
      if (otherSupports.length > 0) return "Only low-credibility sources confirm it (blogs, forums or unknown authors).";
      if (all.some((e) => e.verdict === "PARTIAL")) return "The sources only partly match this claim.";
      return "Only the source it came from says this. No other source in this research confirmed it yet.";
    case "NO_SOURCE":
      if (all.length === 0) return "This claim couldn't be checked: the AI didn't give a verdict for it.";
      return "Not even the source it came from clearly says this. Treat it as unverified.";
    default:
      return "";
  }
}
