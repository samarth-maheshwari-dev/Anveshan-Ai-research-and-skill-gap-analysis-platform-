"use client";

import { useQuery } from "@tanstack/react-query";

import { api } from "../client";
import { unwrap } from "../errors";
import type { components } from "../schema";

export type AiStatus = components["schemas"]["AiStatus"];

/** GET /api/v1/ai/status — the platform's AI gateway health and today's research-run usage for the signed-in user. */
export function useAiStatus() {
  return useQuery({
    queryKey: ["ai-status"],
    queryFn: async () => unwrap(await api.GET("/api/v1/ai/status")),
    staleTime: 30_000,
  });
}
