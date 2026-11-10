"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "../client";
import { unwrap } from "../errors";
import type { components } from "../schema";

export type RouteChoice = components["schemas"]["RouteChoice"];
export type Slots = Record<string, RouteChoice[]>;

/** Task slots in display order (RULES §8). */
export const TASK_SLOTS = ["PLAN", "EXTRACT", "VERIFY", "WRITE", "ASK", "INTERVIEW"] as const;
export type TaskSlot = (typeof TASK_SLOTS)[number];

export const SLOT_LABELS: Record<TaskSlot, { name: string; help: string }> = {
  PLAN: { name: "Plan", help: "Breaks your question into sub-questions" },
  EXTRACT: { name: "Extract", help: "Pulls claims out of each source" },
  VERIFY: { name: "Verify", help: "Checks every claim against its source" },
  WRITE: { name: "Write", help: "Writes the report sections" },
  ASK: { name: "Ask", help: "Answers questions about a report" },
  INTERVIEW: { name: "Interview", help: "Runs project defenses and challenges" },
};

export function useSlots() {
  return useQuery({
    queryKey: ["slots"],
    queryFn: async () => unwrap(await api.GET("/api/v1/slots")) as Slots,
  });
}

export function useSaveSlots() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (slots: Slots) => unwrap(await api.PUT("/api/v1/slots", { body: slots })) as Slots,
    onSuccess: (data) => qc.setQueryData(["slots"], data),
  });
}
