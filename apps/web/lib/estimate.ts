/**
 * Mirror of the API's `Estimate.of` / `ResearchSize.of` (TECHSPEC §12.3, D-FR-4): the screens recompute the
 * estimate on every change without a request. The API stays the source of truth — it sends the same numbers with
 * the run.
 */

export type Depth = "QUICK" | "STANDARD" | "DEEP" | "CUSTOM";

/** PRD §14.4 presets (CUSTOM: the starting values of the user's own numbers). */
export const DEPTHS: Record<Depth, { subQuestions: number; sourcesPerQuestion: number; minutes: number }> = {
  QUICK: { subQuestions: 3, sourcesPerQuestion: 4, minutes: 5 },
  STANDARD: { subQuestions: 5, sourcesPerQuestion: 6, minutes: 15 },
  DEEP: { subQuestions: 6, sourcesPerQuestion: 10, minutes: 30 },
  CUSTOM: { subQuestions: 5, sourcesPerQuestion: 6, minutes: 15 },
};

export const MIN_SUB_QUESTIONS = 1;
export const MAX_SUB_QUESTIONS = 8;
export const MIN_SOURCES = 2;
export const MAX_SOURCES = 10;
const MINUTES_PER_SOURCE = 0.5;

/** How big a run is: sub-questions, sources per sub-question, minutes per sub-question. */
export type Size = { subQuestions: number; sourcesPerQuestion: number; minutesPerQuestion: number };

/** A preset's size, or the user's own numbers for CUSTOM (null when they are out of range). */
export function sizeOf(depth: Depth, custom?: { subQuestions?: number; sourcesPerQuestion?: number }): Size | null {
  const d = DEPTHS[depth];
  if (depth !== "CUSTOM") {
    return { subQuestions: d.subQuestions, sourcesPerQuestion: d.sourcesPerQuestion, minutesPerQuestion: d.minutes / d.subQuestions };
  }
  const subQuestions = custom?.subQuestions ?? d.subQuestions;
  const sourcesPerQuestion = custom?.sourcesPerQuestion ?? d.sourcesPerQuestion;
  const ok =
    Number.isInteger(subQuestions) &&
    Number.isInteger(sourcesPerQuestion) &&
    subQuestions >= MIN_SUB_QUESTIONS &&
    subQuestions <= MAX_SUB_QUESTIONS &&
    sourcesPerQuestion >= MIN_SOURCES &&
    sourcesPerQuestion <= MAX_SOURCES;
  return ok ? { subQuestions, sourcesPerQuestion, minutesPerQuestion: sourcesPerQuestion * MINUTES_PER_SOURCE } : null;
}

export type SlotRequests = { PLAN: number; EXTRACT: number; VERIFY: number; WRITE: number };

export type Estimate = { requests: SlotRequests; total: number; minutes: number };

export function estimate(
  size: Pick<Size, "sourcesPerQuestion" | "minutesPerQuestion">,
  subQuestions: number,
  sections: number,
  cacheHitRate = 0,
): Estimate | null {
  if (subQuestions < MIN_SUB_QUESTIONS || subQuestions > MAX_SUB_QUESTIONS || sections < 1) return null;
  const sources = subQuestions * size.sourcesPerQuestion;
  const requests: SlotRequests = {
    PLAN: 1,
    EXTRACT: Math.ceil(Math.ceil(sources / 2.5) * (1 - cacheHitRate)),
    VERIFY: Math.ceil((sources * 2) / 9),
    WRITE: sections,
  };
  const total = requests.PLAN + requests.EXTRACT + requests.VERIFY + requests.WRITE;
  const minutes = Math.max(2, Math.round(size.minutesPerQuestion * subQuestions));
  return { requests, total, minutes };
}
