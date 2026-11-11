"use client";

import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";

import { Alert } from "@/components/anveshan/alert";
import { ErrorLine } from "@/components/anveshan/error-line";
import { Skeleton } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import { runHref, useRun } from "@/lib/api/hooks/runs";

/** /research/[id] → the screen for the run's status (APPFLOW §1.3). */
export function RunRedirect() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const run = useRun(id);
  const status = run.data?.status;

  useEffect(() => {
    if (status) router.replace(runHref(id, status));
  }, [status, id, router]);

  if (run.isError) {
    if (run.error instanceof ApiError && run.error.status === 404) {
      return <Alert>This research doesn&apos;t exist or isn&apos;t yours.</Alert>;
    }
    return <ErrorLine message="Couldn't load this research." onRetry={() => void run.refetch()} />;
  }
  return <Skeleton className="h-40" />;
}
