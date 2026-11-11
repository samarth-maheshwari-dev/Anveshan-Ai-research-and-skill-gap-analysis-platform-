import type { CreateRun, SourceType, Template } from "@/lib/api/hooks/runs";
import { DEPTHS, MAX_SOURCES, MAX_SUB_QUESTIONS, MIN_SOURCES, MIN_SUB_QUESTIONS, sizeOf, type Depth } from "@/lib/estimate";
import {
  charCount,
  DEFAULT_SECTIONS,
  FIRST_YEAR,
  INSTRUCTIONS_MAX,
  LABEL_MAX,
  parseSites,
  sectionsError,
  TOPIC_MAX,
  TOPIC_MIN,
  type Language,
  type Objective,
  type OutputStyle,
  type TimeRange,
} from "@/lib/research-options";

/** Everything the New Research form holds; free-text parts are kept as typed until submit. */
export type Form = {
  templateCode: string | null;
  topic: string;
  instructions: string;
  objective: Objective;
  objectiveText: string;
  depth: Depth;
  subQuestions: number;
  sourcesPerQuestion: number;
  outputStyle: OutputStyle;
  outputStyleText: string;
  /** false: the template's sections (or the writer decides); true: `sections`. */
  ownSections: boolean;
  sections: string[];
  sources: SourceType[];
  timeRange: TimeRange;
  sinceYear: number;
  language: Language;
  includeSites: string;
  excludeSites: string;
};

export const initialForm = (currentYear: number): Form => ({
  templateCode: null,
  topic: "",
  instructions: "",
  objective: "PROJECT_REPORT",
  objectiveText: "",
  depth: "STANDARD",
  subQuestions: DEPTHS.CUSTOM.subQuestions,
  sourcesPerQuestion: DEPTHS.CUSTOM.sourcesPerQuestion,
  outputStyle: "PROJECT_REPORT",
  outputStyleText: "",
  ownSections: false,
  sections: [],
  sources: ["WEB", "ACADEMIC"],
  timeRange: "ANY",
  sinceYear: currentYear - 3,
  language: "en",
  includeSites: "",
  excludeSites: "",
});

/** A template pre-fills the form (C-FR-5); the user can still change every field. */
export function withTemplate(form: Form, t: Template | null): Form {
  if (!t) return { ...form, templateCode: null };
  const d = (t.formDefaults ?? {}) as Record<string, unknown>;
  return {
    ...form,
    templateCode: t.code ?? null,
    objective: (d.objective as Objective) ?? form.objective,
    depth: (d.depth as Depth) ?? form.depth,
    outputStyle: (d.outputStyle as OutputStyle) ?? form.outputStyle,
    sources: Array.isArray(d.sources) && d.sources.length > 0 ? (d.sources as SourceType[]) : form.sources,
  };
}

/** Starting rows when the user switches to their own sections: the template's, else a sensible default. */
export const startingSections = (template: Template | null) =>
  template?.sections?.length ? [...template.sections] : [...DEFAULT_SECTIONS];

export type FieldErrors = Partial<Record<keyof Form, string>>;

/** Field errors of one step (0 Topic, 1 Objective, 2 Sources); the same rules as the API (400 otherwise). */
export function stepErrors(form: Form, step: number, currentYear: number): FieldErrors {
  const e: FieldErrors = {};
  if (step === 0) {
    const n = charCount(form.topic);
    if (n < TOPIC_MIN) e.topic = `Write at least ${TOPIC_MIN} characters.`;
    else if (n > TOPIC_MAX) e.topic = `Keep it under ${TOPIC_MAX} characters.`;
    if (charCount(form.instructions) > INSTRUCTIONS_MAX) e.instructions = `Keep it under ${INSTRUCTIONS_MAX} characters.`;
  }
  if (step === 1) {
    if (form.objective === "OTHER") e.objectiveText = labelError(form.objectiveText, "Describe your objective in a few words.");
    if (form.outputStyle === "CUSTOM") e.outputStyleText = labelError(form.outputStyleText, "Describe the report format you want.");
    if (form.depth === "CUSTOM") {
      if (!inRange(form.subQuestions, MIN_SUB_QUESTIONS, MAX_SUB_QUESTIONS))
        e.subQuestions = `Choose ${MIN_SUB_QUESTIONS}–${MAX_SUB_QUESTIONS} sub-questions.`;
      if (!inRange(form.sourcesPerQuestion, MIN_SOURCES, MAX_SOURCES))
        e.sourcesPerQuestion = `Choose ${MIN_SOURCES}–${MAX_SOURCES} sources per sub-question.`;
    }
    if (form.ownSections) {
      const s = sectionsError(form.sections);
      if (s) e.sections = s;
    }
  }
  if (step === 2) {
    if (form.sources.length === 0) e.sources = "Pick at least one kind of source.";
    if (form.timeRange === "SINCE_YEAR" && !inRange(form.sinceYear, FIRST_YEAR, currentYear))
      e.sinceYear = `Choose a year between ${FIRST_YEAR} and ${currentYear}.`;
    const inc = parseSites(form.includeSites);
    const exc = parseSites(form.excludeSites);
    if (inc.error) e.includeSites = inc.error;
    if (exc.error) e.excludeSites = exc.error;
    const both = inc.sites.find((s) => exc.sites.includes(s));
    if (!e.excludeSites && both) e.excludeSites = `${both} is in both "prefer" and "avoid".`;
  }
  for (const k of Object.keys(e) as (keyof Form)[]) if (!e[k]) delete e[k];
  return e;
}

function labelError(text: string, empty: string): string | undefined {
  const n = charCount(text);
  if (n === 0) return empty;
  if (n > LABEL_MAX) return `Keep it under ${LABEL_MAX} characters.`;
  return undefined;
}

const inRange = (n: number, min: number, max: number) => Number.isInteger(n) && n >= min && n <= max;

/** The request body: custom parts only with the choice that uses them, blanks left out. */
export function toBody(form: Form): CreateRun {
  const text = (s: string) => (s.trim() ? s.trim() : undefined);
  const inc = parseSites(form.includeSites).sites;
  const exc = parseSites(form.excludeSites).sites;
  return {
    topic: form.topic.trim(),
    objective: form.objective,
    objectiveText: form.objective === "OTHER" ? text(form.objectiveText) : undefined,
    depth: form.depth,
    subQuestions: form.depth === "CUSTOM" ? form.subQuestions : undefined,
    sourcesPerQuestion: form.depth === "CUSTOM" ? form.sourcesPerQuestion : undefined,
    outputStyle: form.outputStyle,
    outputStyleText: form.outputStyle === "CUSTOM" ? text(form.outputStyleText) : undefined,
    sources: form.sources,
    timeRange: form.timeRange,
    sinceYear: form.timeRange === "SINCE_YEAR" ? form.sinceYear : undefined,
    language: form.language,
    templateCode: form.templateCode ?? undefined,
    sections: form.ownSections ? form.sections.map((s) => s.trim()) : undefined,
    instructions: text(form.instructions),
    includeSites: inc.length ? inc : undefined,
    excludeSites: exc.length ? exc : undefined,
  };
}

/** Sizes for the estimate line (null while custom numbers are invalid). */
export const formSize = (form: Form) =>
  sizeOf(form.depth, { subQuestions: form.subQuestions, sourcesPerQuestion: form.sourcesPerQuestion });
