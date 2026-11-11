/**
 * Mirror of the API's `Estimate.of` (TECHSPEC §12.3, D-FR-4): the plan screen recomputes the estimate on every
 * edit without a request. The API stays the source of truth — it sends the same numbers with the run.
 */

export type Depth = "QUICK" | "STANDARD" | "DEEP";

/** PRD §14.4 presets: sub-questions, sources per question, chunks per source, typical minutes. */
export const DEPTHS: Record<Depth, { subQuestions: number; sourcesPerQuestion: number; minutes: number }> = {
  QUICK: { subQuestions: 3, sourcesPerQuestion: 4, minutes: 5 },
  STANDARD: { subQuestions: 5, sourcesPerQuestion: 6, minutes: 15 },
  DEEP: { subQuestions: 6, sourcesPerQuestion: 10, minutes: 30 },
};

export const MIN_SUB_QUESTIONS = 1;
export const MAX_SUB_QUESTIONS = 8;

export type SlotRequests = { PLAN: number; EXTRACT: number; VERIFY: number; WRITE: number };

export type Estimate = { requests: SlotRequests; total: number; minutes: number };

export function estimate(depth: Depth, subQuestions: number, sections: number, cacheHitRate = 0): Estimate | null {
  if (subQuestions < MIN_SUB_QUESTIONS || subQuestions > MAX_SUB_QUESTIONS || sections < 1) return null;
  const d = DEPTHS[depth];
  const sources = subQuestions * d.sourcesPerQuestion;
  const requests: SlotRequests = {
    PLAN: 1,
    EXTRACT: Math.ceil(Math.ceil(sources / 2.5) * (1 - cacheHitRate)),
    VERIFY: Math.ceil((sources * 2) / 9),
    WRITE: sections,
  };
  const total = requests.PLAN + requests.EXTRACT + requests.VERIFY + requests.WRITE;
  const minutes = Math.max(2, Math.round((d.minutes * subQuestions) / d.subQuestions));
  return { requests, total, minutes };
}
