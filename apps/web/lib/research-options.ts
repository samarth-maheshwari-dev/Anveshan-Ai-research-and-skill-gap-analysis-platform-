import type { CreateRun, SourceType } from "@/lib/api/hooks/runs";
import type { Depth } from "@/lib/estimate";

export type Objective = NonNullable<CreateRun["objective"]>;
export type OutputStyle = NonNullable<CreateRun["outputStyle"]>;
export type TimeRange = NonNullable<CreateRun["timeRange"]>;
export type Language = NonNullable<CreateRun["language"]>;

/** Labels for the New Research form (PRD Module C + custom options, D-097) and the plan cards. */
export const OBJECTIVES: { value: Objective; label: string; help: string }[] = [
  { value: "PROJECT_REPORT", label: "Project report", help: "A full report for a college or work project" },
  { value: "COMPARISON", label: "Comparison", help: "Weigh options side by side" },
  { value: "LEARNING", label: "Learning", help: "Understand a topic from the ground up" },
  { value: "DECISION", label: "Decision", help: "Pick one option, with reasons" },
  { value: "OTHER", label: "Something else", help: "Describe it in your own words" },
];

export const DEPTH_OPTIONS: { value: Depth; label: string; help: string }[] = [
  { value: "QUICK", label: "Quick", help: "3 sub-questions · about 5 min" },
  { value: "STANDARD", label: "Standard", help: "5 sub-questions · about 15 min" },
  { value: "DEEP", label: "Deep", help: "6 sub-questions · about 30 min" },
  { value: "CUSTOM", label: "Custom", help: "Choose the numbers yourself" },
];

export const SOURCE_TYPES: { value: SourceType; label: string }[] = [
  { value: "WEB", label: "Web" },
  { value: "ACADEMIC", label: "Academic papers" },
  { value: "GITHUB", label: "GitHub" },
  { value: "BLOGS", label: "Blogs" },
  { value: "DOCS", label: "Official docs" },
];

export const SOURCE_LABEL = Object.fromEntries(SOURCE_TYPES.map((s) => [s.value, s.label])) as Record<
  SourceType,
  string
>;

export const OUTPUT_STYLES: { value: OutputStyle; label: string }[] = [
  { value: "PROJECT_REPORT", label: "Project report" },
  { value: "COMPARISON_TABLE", label: "Comparison table" },
  { value: "EXECUTIVE_SUMMARY", label: "Executive summary" },
  { value: "STUDY_NOTES", label: "Study notes" },
  { value: "CUSTOM", label: "My own format…" },
];

export const TIME_RANGES: { value: TimeRange; label: string }[] = [
  { value: "ANY", label: "Any time" },
  { value: "LAST_1_YEAR", label: "Last year" },
  { value: "LAST_2_YEARS", label: "Last 2 years" },
  { value: "LAST_5_YEARS", label: "Last 5 years" },
  { value: "SINCE_YEAR", label: "Since a year…" },
];

export const LANGUAGES: { value: Language; label: string }[] = [
  { value: "en", label: "English" },
  { value: "hi", label: "Hindi" },
];

/** Characters as the API counts them (code points, not UTF-16 units). */
export const charCount = (s: string) => [...s.trim()].length;
export const topicLength = charCount;
export const TOPIC_MIN = 10;
export const TOPIC_MAX = 500;

// Limits of the API's CustomRules (keep in sync).
export const LABEL_MAX = 120;
export const INSTRUCTIONS_MAX = 500;
export const SECTIONS_MAX = 12;
export const SECTION_MIN = 2;
export const SECTION_MAX = 80;
export const SITES_MAX = 10;
export const FIRST_YEAR = 1990;

/** Suggested starting sections when the user writes their own without a template. */
export const DEFAULT_SECTIONS = ["Introduction", "Background", "Findings", "Comparison", "Conclusion"];

const HOST = /^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;

/**
 * "https://www.IEEE.org/x, arxiv.org" → ["ieee.org", "arxiv.org"], like CustomRules.sites. `error` names the
 * first entry that isn't a website name.
 */
export function parseSites(text: string): { sites: string[]; error: string | null } {
  const out: string[] = [];
  for (const raw of text.split(/[\s,]+/)) {
    if (!raw) continue;
    let h = raw.toLowerCase().replace(/^[a-z][a-z0-9+.-]*:\/\//, "");
    const cut = h.search(/[/?#:]/);
    if (cut >= 0) h = h.slice(0, cut);
    if (h.startsWith("www.")) h = h.slice(4);
    if (h.endsWith(".")) h = h.slice(0, -1);
    if (!HOST.test(h)) return { sites: out, error: `"${raw}" isn't a website name (like ieee.org).` };
    if (!out.includes(h)) out.push(h);
  }
  if (out.length > SITES_MAX) return { sites: out, error: `At most ${SITES_MAX} websites.` };
  return { sites: out, error: null };
}

/** Own sections: 1–12 distinct titles of 2–80 characters (CustomRules.sections). */
export function sectionsError(titles: string[]): string | null {
  if (titles.length === 0) return "Add at least one section, or let the AI decide.";
  if (titles.length > SECTIONS_MAX) return `A report can have at most ${SECTIONS_MAX} sections.`;
  const seen = new Set<string>();
  for (const t of titles) {
    const n = charCount(t);
    if (n < SECTION_MIN || n > SECTION_MAX) return `Each section needs a title of ${SECTION_MIN}–${SECTION_MAX} characters.`;
    const k = t.trim().replace(/\s+/g, " ").toLowerCase();
    if (seen.has(k)) return `Two sections are called "${t.trim()}".`;
    seen.add(k);
  }
  return null;
}
