"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";

import { api } from "../client";
import { unwrap } from "../errors";
import type { components } from "../schema";

export type Me = components["schemas"]["MeView"];

/** GET /me — also makes the API set the XSRF-TOKEN cookie (TECHSPEC §9.2 boot sequence). */
export function useMe() {
  return useQuery({
    queryKey: ["me"],
    queryFn: async () => unwrap(await api.GET("/api/v1/me")),
    retry: false,
    staleTime: 30_000,
  });
}

export function useLogout() {
  const qc = useQueryClient();
  const router = useRouter();
  return useMutation({
    mutationFn: async () => {
      unwrap(await api.POST("/api/v1/auth/logout"));
    },
    onSettled: () => {
      qc.clear();
      router.replace("/login");
    },
  });
}
