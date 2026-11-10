"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "../client";
import { unwrap } from "../errors";
import type { components } from "../schema";

export type Connection = components["schemas"]["ConnectionView"];
export type CreateConnection = components["schemas"]["CreateRequest"];
export type TestResult = components["schemas"]["TestResult"];
export type ModelInfo = components["schemas"]["ModelInfo"];
export type Provider = NonNullable<CreateConnection["provider"]>;

export function useConnections() {
  return useQuery({
    queryKey: ["connections"],
    queryFn: async () => unwrap(await api.GET("/api/v1/connections")).items ?? [],
  });
}

export function useCreateConnection() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateConnection) => unwrap(await api.POST("/api/v1/connections", { body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["connections"] }),
  });
}

export function useTestConnection() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, model }: { id: string; model?: string }) =>
      unwrap(
        await api.POST("/api/v1/connections/{id}/test", {
          params: { path: { id } },
          body: model ? { model } : {},
        }),
      ),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: ["connections"] });
      void qc.invalidateQueries({ queryKey: ["me"] });
      void qc.invalidateQueries({ queryKey: ["slots"] });
    },
  });
}

export function useDeleteConnection() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      unwrap(await api.DELETE("/api/v1/connections/{id}", { params: { path: { id } } }));
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: ["connections"] });
      void qc.invalidateQueries({ queryKey: ["me"] });
      void qc.invalidateQueries({ queryKey: ["slots"] });
    },
  });
}

/** Imperative model load for the wizard (it needs the list before it can test). */
export function useLoadModels() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) =>
      qc.fetchQuery({
        queryKey: ["models", id, false],
        queryFn: async () =>
          unwrap(
            await api.GET("/api/v1/connections/{id}/models", {
              params: { path: { id }, query: { freeOnly: false } },
            }),
          ).items ?? [],
        staleTime: 60 * 60 * 1000,
      }),
  });
}

/** Live model list (cached 1 h by the API). Only fetched when {@code id} is set. */
export function useModels(id: string | undefined, freeOnly = false) {
  return useQuery({
    queryKey: ["models", id, freeOnly],
    enabled: !!id,
    queryFn: async () =>
      unwrap(
        await api.GET("/api/v1/connections/{id}/models", {
          params: { path: { id: id! }, query: { freeOnly } },
        }),
      ).items ?? [],
    staleTime: 60 * 60 * 1000,
  });
}
