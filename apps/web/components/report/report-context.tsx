"use client";

import { createContext, useContext } from "react";

import type { ReportClaim, ReportSource } from "@/lib/api/hooks/reports";

/** What every citation chip on S-08 needs: the claims and sources, which one is open, and how to open one. */
export type ReportContextValue = {
  claims: Record<string, ReportClaim>;
  sources: Record<string, ReportSource>;
  activeClaimId: string | null;
  openCitation: ((claimId: string) => void) | null;
};

export const ReportContext = createContext<ReportContextValue>({
  claims: {},
  sources: {},
  activeClaimId: null,
  openCitation: null,
});

export function useReportContext() {
  return useContext(ReportContext);
}
