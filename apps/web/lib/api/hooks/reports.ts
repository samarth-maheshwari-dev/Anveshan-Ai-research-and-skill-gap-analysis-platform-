"use client";

import { useQuery } from "@tanstack/react-query";

import { api } from "../client";
import { unwrap } from "../errors";
import type { components } from "../schema";

export type Report = components["schemas"]["ReportView"];
export type ReportSection = components["schemas"]["SectionView"];
export type ReportClaim = components["schemas"]["ClaimView"];
export type ReportSource = components["schemas"]["SourceView"];

export function useReport(id: string | null | undefined) {
  return useQuery({
    queryKey: ["report", id],
    queryFn: async () => unwrap(await api.GET("/api/v1/reports/{id}", { params: { path: { id: id! } } })),
    enabled: !!id,
  });
}
