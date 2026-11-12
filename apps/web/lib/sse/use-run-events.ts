"use client";

import { useEffect, useState } from "react";

import { API_URL } from "@/lib/env";

/** One run event as the API streams it (TECHSPEC §8.6): the SSE id, the event name and its JSON payload. */
export type RunEvent = { id: number; type: string; data: Record<string, unknown> };

/** Every event type the run engine and steps emit. */
export const RUN_EVENT_TYPES = [
  "run.status",
  "run.progress",
  "step.started",
  "step.completed",
  "step.failed",
  "step.retry",
  "log",
  "plan.ready",
  "plan.updated",
  "plan.regenerated",
  "plan.expired",
  "source.added",
  "source.skipped",
  "conflict.found",
  "report.ready",
] as const;

const FINAL = new Set(["COMPLETED", "CANCELLED", "FAILED"]);

/**
 * Live events of one run over SSE (E-FR-1). The browser's EventSource reconnects by itself and sends
 * Last-Event-ID, so the API replays what was missed; on first connect everything since the start is replayed (a
 * reopened tab sees the earlier log, T-14-07). The stream is closed once the run reaches a final status.
 */
export function useRunEvents(runId: string, enabled: boolean): { events: RunEvent[]; live: boolean } {
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [live, setLive] = useState(false);

  useEffect(() => {
    if (!enabled) return;
    const source = new EventSource(`${API_URL}/api/v1/runs/${runId}/events`, { withCredentials: true });
    const seen = new Set<number>();
    const onEvent = (e: MessageEvent) => {
      const id = Number(e.lastEventId);
      if (Number.isFinite(id) && seen.has(id)) return;
      seen.add(id);
      let data: Record<string, unknown> = {};
      try {
        data = JSON.parse(e.data as string) as Record<string, unknown>;
      } catch {
        // a malformed payload is ignored, never rendered
      }
      setEvents((prev) => [...prev, { id, type: e.type, data }]);
      if (e.type === "run.status" && FINAL.has(String(data.status))) {
        source.close();
        setLive(false);
      }
    };
    for (const t of RUN_EVENT_TYPES) source.addEventListener(t, onEvent as EventListener);
    source.onopen = () => setLive(true);
    source.onerror = () => setLive(false);
    return () => {
      source.close();
      setEvents([]);
    };
  }, [runId, enabled]);

  return { events, live };
}
