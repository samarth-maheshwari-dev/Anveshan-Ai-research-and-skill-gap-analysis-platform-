import type { CreateRun, SourceType } from "@/lib/api/hooks/runs";
import type { Depth } from "@/lib/estimate";

export type Objective = NonNullable<CreateRun["objective"]>;
export type OutputStyle = NonNullable<CreateRun["outputStyle"]>;
export type TimeRange = NonNullable<CreateRun["timeRange"]>;
export type Language = NonNullable<CreateRun["language"]>;

/** Labels for the New Research form (PRD Module C) and the plan cards. */
export const OBJECTIVES: { value: Objective; label: string; help: string }[] = [
  { value: "PROJECT_REPORT", label: "Project report", help: "A full report for a college or work project" },
  { value: "COMPARISON", label: "Comparison", help: "Weigh options side by side" },
  { value: "LEARNING", label: "Learning", help: "Understand a topic from the ground up" },
  { value: "DECISION", label: "Decision", help: "Pick one option, with reasons" },
];

export const DEPTH_OPTIONS: { value: Depth; label: string; help: string }[] = [
  { value: "QUICK", label: "Quick", help: "3 sub-questions · about 5 min" },
  { value: "STANDARD", label: "Standard", help: "5 sub-questions · about 15 min" },
  { value: "DEEP", label: "Deep", help: "6 sub-questions · about 30 min" },
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
];

export const TIME_RANGES: { value: TimeRange; label: string }[] = [
  { value: "ANY", label: "Any time" },
  { value: "LAST_1_YEAR", label: "Last year" },
  { value: "LAST_2_YEARS", label: "Last 2 years" },
  { value: "LAST_5_YEARS", label: "Last 5 years" },
];

export const LANGUAGES: { value: Language; label: string }[] = [
  { value: "en", label: "English" },
  { value: "hi", label: "Hindi" },
];

/** Topic length in characters as the API counts them (code points, not UTF-16 units). */
export const topicLength = (s: string) => [...s.trim()].length;
export const TOPIC_MIN = 10;
export const TOPIC_MAX = 500;
