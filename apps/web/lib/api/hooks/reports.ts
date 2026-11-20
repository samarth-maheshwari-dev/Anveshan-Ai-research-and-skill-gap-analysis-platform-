"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "../client";
import { unwrap } from "../errors";
import type { components } from "../schema";

export type Report = components["schemas"]["ReportView"];
export type ReportSection = components["schemas"]["SectionView"];
export type ReportClaim = components["schemas"]["ClaimView"];
export type ReportSource = components["schemas"]["SourceView"];
export type Passage = components["schemas"]["PassageView"];
export type ReportVersion = components["schemas"]["VersionView"];
export type AssistAction = components["schemas"]["AssistRequest"]["action"];
export type SavedSection = components["schemas"]["SavedSection"];

const etag = (version: number | undefined) => `"${version ?? 0}"`;

export function useReport(id: string | null | undefined) {
  return useQuery({
    queryKey: ["report", id],
    queryFn: async () => unwrap(await api.GET("/api/v1/reports/{id}", { params: { path: { id: id! } } })),
    enabled: !!id,
  });
}

/** F-FR-1: the exact passage behind a citation (citation panel). */
export function usePassage(reportId: string, claimId: string | null) {
  return useQuery({
    queryKey: ["report-passage", reportId, claimId],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/reports/{id}/claims/{claimId}/passage", {
          params: { path: { id: reportId, claimId: claimId! } },
        }),
      ),
    enabled: !!claimId,
  });
}

/** PATCH a section with If-Match (autosave / Save). The caller keeps the editor; the report refreshes around it. */
export function useSaveSection(reportId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ sectionId, version, contentDoc }: { sectionId: string; version: number; contentDoc: Record<string, unknown> }) =>
      unwrap(
        await api.PATCH("/api/v1/reports/{id}/sections/{sectionId}", {
          params: { path: { id: reportId, sectionId }, header: { "If-Match": etag(version) } },
          body: { contentDoc },
        }),
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["report", reportId] });
      void qc.invalidateQueries({ queryKey: ["report-versions", reportId] });
    },
  });
}

/** F-FR-6: every saved version, newest first. */
export function useVersions(reportId: string, enabled: boolean) {
  return useQuery({
    queryKey: ["report-versions", reportId],
    queryFn: async () =>
      unwrap(await api.GET("/api/v1/reports/{id}/versions", { params: { path: { id: reportId }, query: { limit: 50 } } })),
    enabled,
  });
}

export function useRestoreVersion(reportId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (version: number) =>
      unwrap(
        await api.POST("/api/v1/reports/{id}/versions/{version}/restore", {
          params: { path: { id: reportId, version } },
        }),
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["report", reportId] });
      void qc.invalidateQueries({ queryKey: ["report-versions", reportId] });
    },
  });
}

/** F-FR-4: a suggestion for the selection; nothing is saved until the user accepts it in the editor. */
export function useAssist(reportId: string) {
  return useMutation({
    mutationFn: async (body: { sectionId: string; selection: { from: number; to: number }; action: AssistAction }) =>
      unwrap(await api.POST("/api/v1/reports/{id}/assist", { params: { path: { id: reportId } }, body })).suggestion,
  });
}

/** F-FR-7: one section rewritten from its existing claims (a new version). */
export function useRegenerateSection(reportId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ sectionId, version }: { sectionId: string; version: number }) =>
      unwrap(
        await api.POST("/api/v1/reports/{id}/sections/{sectionId}/regenerate", {
          params: { path: { id: reportId, sectionId }, header: { "If-Match": etag(version) } },
        }),
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["report", reportId] });
      void qc.invalidateQueries({ queryKey: ["report-versions", reportId] });
    },
  });
}

/** F-FR-5: starts the mini-research; follow it with useRun(runId). */
export function useFindBetterSources(reportId: string) {
  return useMutation({
    mutationFn: async (claimId: string) =>
      unwrap(
        await api.POST("/api/v1/reports/{id}/claims/{claimId}/find-better-sources", {
          params: { path: { id: reportId, claimId } },
        }),
      ).runId,
  });
}
