"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "../client";
import { unwrap } from "../errors";
import type { components } from "../schema";

export type Template = components["schemas"]["TemplateView"];
export type CreateRun = components["schemas"]["CreateRunRequest"];
export type Run = components["schemas"]["RunView"];
export type RunStatus = NonNullable<Run["status"]>;
export type SubQuestion = components["schemas"]["SubQuestionView"];
export type PlanCard = components["schemas"]["PlanCard"];
export type RunEstimate = components["schemas"]["EstimateView"];
export type SourceType = NonNullable<PlanCard["sourceTypes"]>[number];

/** APPFLOW §1.3: where a run lives, by status. */
export function runHref(id: string, status: RunStatus | undefined): string {
  switch (status) {
    case "PLANNING":
    case "AWAITING_APPROVAL":
      return `/research/${id}/plan`;
    case "COMPLETED":
      return `/research/${id}/report`;
    default:
      return `/research/${id}/live`;
  }
}

/** The ETag the API expects back in If-Match. */
export const etagOf = (run: Pick<Run, "version">) => `"${run.version ?? 0}"`;

export function useTemplates() {
  return useQuery({
    queryKey: ["templates"],
    queryFn: async () => unwrap(await api.GET("/api/v1/templates")).items ?? [],
    staleTime: 10 * 60 * 1000,
  });
}

/** C-FR-6: one key per form submission, so a double click or a retry never starts two runs. */
export function useCreateRun() {
  return useMutation({
    mutationFn: async ({ body, idempotencyKey }: { body: CreateRun; idempotencyKey: string }) =>
      unwrap(
        await api.POST("/api/v1/runs", { body, params: { header: { "Idempotency-Key": idempotencyKey } } }),
      ),
  });
}

/** One run; polls while the Planner works (the live room uses SSE in P11+). */
export function useRun(id: string, { pollWhilePlanning = false }: { pollWhilePlanning?: boolean } = {}) {
  return useQuery({
    queryKey: ["run", id],
    queryFn: async () => unwrap(await api.GET("/api/v1/runs/{id}", { params: { path: { id } } })),
    refetchInterval: (q) => {
      const status = q.state.data?.status;
      if (pollWhilePlanning && status === "PLANNING") return 1500;
      if (status === "RUNNING") return 3000;
      return false;
    },
  });
}

export function useUpdatePlan(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ cards, version }: { cards: PlanCard[]; version: string }) =>
      unwrap(
        await api.PUT("/api/v1/runs/{id}/plan", {
          params: { path: { id }, header: { "If-Match": version } },
          body: { subQuestions: cards },
        }),
      ),
    onSuccess: (run) => qc.setQueryData(["run", id], run),
  });
}

/** D-FR-6: a fresh plan from the AI (1 PLAN request), optionally guided ("focus on India"). */
export function useRegeneratePlan(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ instruction, version }: { instruction: string; version: string }) =>
      unwrap(
        await api.POST("/api/v1/runs/{id}/plan/regenerate", {
          params: { path: { id }, header: { "If-Match": version } },
          body: instruction.trim() ? { instruction: instruction.trim() } : {},
        }),
      ),
    onSuccess: (run) => qc.setQueryData(["run", id], run),
  });
}

export function useApprove(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (version: string) => {
      unwrap(
        await api.POST("/api/v1/runs/{id}/approve", { params: { path: { id }, header: { "If-Match": version } } }),
      );
    },
    onSettled: () => qc.invalidateQueries({ queryKey: ["run", id] }),
  });
}

export type RunAction = "pause" | "resume" | "cancel";

export function useRunAction(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (action: RunAction) => {
      const opts = { params: { path: { id } } };
      if (action === "pause") unwrap(await api.POST("/api/v1/runs/{id}/pause", opts));
      else if (action === "resume") unwrap(await api.POST("/api/v1/runs/{id}/resume", opts));
      else unwrap(await api.POST("/api/v1/runs/{id}/cancel", opts));
    },
    onSettled: () => qc.invalidateQueries({ queryKey: ["run", id] }),
  });
}

export type RunSummary = components["schemas"]["RunSummary"];
export type RunSource = components["schemas"]["RunSource"];

/** The caller's researches, newest first (dashboard). Polls while any is still working. */
export function useRuns(limit = 10) {
  return useQuery({
    queryKey: ["runs", limit],
    queryFn: async () => unwrap(await api.GET("/api/v1/runs", { params: { query: { limit } } })),
    refetchInterval: (q) =>
      q.state.data?.items?.some((r) => r.status === "RUNNING" || r.status === "PLANNING") ? 5000 : false,
  });
}

/** S-07: the run's sources with status and credibility. */
export function useRunSources(id: string, enabled = true) {
  return useQuery({
    queryKey: ["run-sources", id],
    queryFn: async () => unwrap(await api.GET("/api/v1/runs/{id}/sources", { params: { path: { id } } })),
    enabled,
  });
}

/** E-FR-5: skip a source; its claims stay out of the report. */
export function useSkipSource(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (sourceId: string) => {
      unwrap(await api.POST("/api/v1/runs/{id}/sources/{sourceId}/skip", { params: { path: { id, sourceId } } }));
    },
    onSettled: () => qc.invalidateQueries({ queryKey: ["run-sources", id] }),
  });
}

/** E-FR-4: re-run one failed step. */
export function useRetryStep(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (stepId: string) => {
      unwrap(await api.POST("/api/v1/runs/{id}/steps/{stepId}/retry", { params: { path: { id, stepId } } }));
    },
    onSettled: () => qc.invalidateQueries({ queryKey: ["run", id] }),
  });
}
