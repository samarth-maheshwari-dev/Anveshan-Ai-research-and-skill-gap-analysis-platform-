"use client";

import { useState } from "react";

import { ErrorLine } from "@/components/anveshan/error-line";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import { useRestoreVersion, useVersions } from "@/lib/api/hooks/reports";

type VersionsPanelProps = { reportId: string; onRestored: () => void };

/**
 * F-FR-6: every saved version, newest first. Restoring brings an older version back as a NEW version, so the history
 * is never lost (T-15-04).
 */
export function VersionsPanel({ reportId, onRestored }: VersionsPanelProps) {
  const versions = useVersions(reportId, true);
  const restore = useRestoreVersion(reportId);
  const [confirm, setConfirm] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (versions.isPending) {
    return (
      <div className="space-y-2" aria-busy="true">
        <Skeleton className="h-12" />
        <Skeleton className="h-12" />
        <Skeleton className="h-12" />
      </div>
    );
  }
  if (versions.isError) return <ErrorLine message="Couldn't load the versions." onRetry={() => void versions.refetch()} />;

  const items = versions.data?.items ?? [];
  const newest = items[0]?.version;

  const run = async (v: number) => {
    setError(null);
    try {
      await restore.mutateAsync(v);
      setConfirm(null);
      onRestored();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't restore this version. Try again.");
    }
  };

  return (
    <div>
      <p className="text-body-sm text-muted-foreground">Every save is kept. Restoring adds a new version; nothing is deleted.</p>
      {error && <p className="mt-2 text-body-sm text-danger">{error}</p>}
      <ol className="mt-4 divide-y divide-border border-y border-border">
        {items.map((v) => (
          <li key={v.version} className="py-3" data-version={v.version}>
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="font-mono text-body-sm">
                  v{v.version}
                  {v.version === newest && <span className="ml-2 text-caption text-subtle-foreground">current</span>}
                </p>
                <p className="text-caption text-muted-foreground">
                  {v.createdAt ? new Date(v.createdAt).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" }) : ""} ·{" "}
                  {v.byYou ? "your edit" : "written by Anveshan"}
                </p>
              </div>
              {v.version !== newest && confirm !== v.version && (
                <Button size="sm" variant="ghost" onClick={() => setConfirm(v.version ?? null)}>
                  Restore
                </Button>
              )}
            </div>
            {confirm === v.version && (
              <div role="group" aria-label={`Restore version ${v.version}`} className="mt-2 flex flex-wrap items-center gap-2 text-body-sm">
                <span>Bring back v{v.version}?</span>
                <Button size="sm" variant="primary" loading={restore.isPending} onClick={() => void run(v.version!)}>
                  Restore v{v.version}
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setConfirm(null)}>
                  Cancel
                </Button>
              </div>
            )}
          </li>
        ))}
      </ol>
      {items.length === 0 && <p className="mt-4 text-body-sm text-muted-foreground">No saved versions yet.</p>}
    </div>
  );
}
