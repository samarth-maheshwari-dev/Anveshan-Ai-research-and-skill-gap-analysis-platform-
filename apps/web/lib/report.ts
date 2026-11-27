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

/* ---------- Report visuals (P17): every number below is counted by code from the report, never by a model. ---------- */

type SourceLike = { type?: string; publishedAt?: string; title?: string; site?: string; credibilityTier?: string };
type CitationLike = { sourceId?: string; claimId?: string };
type Bin = { key: string; label: string; value: number };

const TYPE_WORDS: Record<string, string> = {
  PAPER: "Research papers",
  ARTICLE: "Articles",
  WEB: "Web pages",
  BLOG: "Blogs",
  DOC: "Documentation",
  DOCS: "Documentation",
  BOOK: "Books",
  REPO: "Code repositories",
  GITHUB: "Code repositories",
  NEWS: "News",
  WIKI: "Encyclopedia",
  PDF: "PDF documents",
};

function nice(type: string): string {
  return TYPE_WORDS[type.toUpperCase()] ?? type.charAt(0).toUpperCase() + type.slice(1).toLowerCase().replaceAll("_", " ");
}

/** How many sources of each type, largest first. */
export function sourceTypes(sources: SourceLike[]): Bin[] {
  const m = new Map<string, number>();
  for (const s of sources) {
    const k = nice(s.type || "Other");
    m.set(k, (m.get(k) ?? 0) + 1);
  }
  return [...m].map(([label, value]) => ({ key: label, label, value })).sort((a, b) => b.value - a.value || a.label.localeCompare(b.label));
}

/** Sources per publication year, oldest first, with empty years in between filled (so gaps show). Undated are left out. */
export function sourceYears(sources: SourceLike[]): Bin[] {
  const years = sources.map((s) => Number(s.publishedAt?.slice(0, 4))).filter((y) => Number.isInteger(y) && y > 1800);
  if (years.length === 0) return [];
  const lo = Math.max(Math.min(...years), Math.max(...years) - 14);
  const hi = Math.max(...years);
  const out: Bin[] = [];
  for (let y = lo; y <= hi; y++) out.push({ key: String(y), label: String(y), value: 0 });
  for (const y of years) {
    const b = out.find((o) => o.key === String(Math.max(y, lo)));
    if (b) b.value++;
  }
  return out;
}

/** The sources the report leans on most: distinct claims cited per source, top n. */
export function topSources(citations: CitationLike[], sources: Record<string, SourceLike>, n = 5): Bin[] {
  const claimsBySource = new Map<string, Set<string>>();
  for (const c of citations) {
    if (!c.sourceId || !sources[c.sourceId]) continue;
    const set = claimsBySource.get(c.sourceId) ?? new Set<string>();
    set.add(c.claimId ?? "");
    claimsBySource.set(c.sourceId, set);
  }
  return [...claimsBySource]
    .map(([id, set]) => ({ key: id, label: sources[id]!.title || sources[id]!.site || "Untitled source", value: set.size }))
    .sort((a, b) => b.value - a.value || a.label.localeCompare(b.label))
    .slice(0, n);
}

/** Citation nodes in a TipTap doc (the Report map counts them per section). */
export function countCitations(node: unknown): number {
  if (!node || typeof node !== "object") return 0;
  const n = node as { type?: string; content?: unknown[] };
  return (n.type === "citation" ? 1 : 0) + (n.content ?? []).reduce<number>((s, c) => s + countCitations(c), 0);
}

type ClaimLike = { text?: string; trustLabel?: string };

/** "At a glance": the supported claims cited most often, shortest first among ties — picked by code, never by a model. */
export function keyFindings(claims: Record<string, ClaimLike>, citations: CitationLike[], n = 3): { id: string; text: string }[] {
  const uses = new Map<string, number>();
  for (const c of citations) if (c.claimId) uses.set(c.claimId, (uses.get(c.claimId) ?? 0) + 1);
  return Object.entries(claims)
    .filter(([, c]) => c.trustLabel === "SUPPORTED" && c.text && c.text.length <= 240)
    .sort(([a, ca], [b, cb]) => (uses.get(b) ?? 0) - (uses.get(a) ?? 0) || ca.text!.length - cb.text!.length)
    .slice(0, n)
    .map(([id, c]) => ({ id, text: c.text! }));
}
