"use client";

import { Plus, RefreshCw, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { toast } from "sonner";

import { ConnectionStatus } from "@/components/anveshan/connection-status";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardTitle, Skeleton } from "@/components/ui/card";
import { useConnections, useDeleteConnection, useTestConnection, type Connection } from "@/lib/api/hooks/connections";
import { describeTestError } from "@/lib/providers";

/** Settings → AI: the user's connections — test, delete (with confirm), add via the wizard. */
export function ConnectionsSection() {
  const connections = useConnections();
  return (
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <CardTitle>AI connections</CardTitle>
          <p className="mt-1 text-body-sm text-muted-foreground">Your keys are encrypted. Only the last 4 characters are ever shown.</p>
        </div>
        <Link href="/onboarding/connect-ai?from=settings" className={buttonVariants({ variant: "primary", size: "sm" })}>
          <Plus aria-hidden /> Add
        </Link>
      </div>
      {connections.isPending ? (
        <div className="mt-6 space-y-3">
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-14 w-full" />
        </div>
      ) : connections.isError ? (
        <ErrorLine onRetry={() => void connections.refetch()} />
      ) : connections.data.length === 0 ? (
        <p className="mt-6 text-body-sm text-muted-foreground">No connections yet. Add one to start researching.</p>
      ) : (
        <ul className="mt-6 divide-y divide-border">
          {connections.data.map((c) => (
            <ConnectionRow key={c.id} connection={c} />
          ))}
        </ul>
      )}
    </Card>
  );
}

type ConnectionRowProps = { connection: Connection };

function ConnectionRow({ connection: c }: ConnectionRowProps) {
  const test = useTestConnection();
  const remove = useDeleteConnection();
  const [confirming, setConfirming] = useState(false);

  async function runTest() {
    try {
      const r = await test.mutateAsync({ id: c.id! });
      toast.success(r.note ? `${c.label}: ${r.note}` : `${c.label} works.`);
    } catch (e) {
      toast.error(`${c.label}: ${describeTestError(e, c.provider ?? "the provider").message}`);
    }
  }

  return (
    <li className="flex flex-wrap items-center gap-3 py-4" data-testid="connection-row">
      <div className="min-w-0 flex-1">
        <p className="truncate text-body font-medium">{c.label}</p>
        <p className="text-caption text-muted-foreground">
          {c.provider} · key <span className="font-mono">••••{c.keyLast4}</span>
          {c.statusDetail ? ` · ${c.statusDetail}` : ""}
        </p>
      </div>
      <ConnectionStatus status={c.status} />
      {confirming ? (
        <span className="flex items-center gap-2" role="group" aria-label={`Delete ${c.label}?`}>
          <span className="text-body-sm">Delete?</span>
          <Button variant="danger" size="sm" loading={remove.isPending} onClick={() => remove.mutate(c.id!)}>
            Delete
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setConfirming(false)}>
            Cancel
          </Button>
        </span>
      ) : (
        <span className="flex items-center gap-1">
          <Button variant="secondary" size="sm" loading={test.isPending} onClick={() => void runTest()}>
            <RefreshCw aria-hidden /> Test
          </Button>
          <Button variant="ghost" size="sm" aria-label={`Delete ${c.label}`} onClick={() => setConfirming(true)}>
            <Trash2 aria-hidden />
          </Button>
        </span>
      )}
    </li>
  );
}

type ErrorLineProps = { onRetry: () => void };

function ErrorLine({ onRetry }: ErrorLineProps) {
  return (
    <div role="alert" className="mt-6 flex items-center justify-between gap-3 rounded-md bg-danger-soft p-3 text-body-sm">
      Couldn&apos;t load your connections.
      <Button variant="secondary" size="sm" onClick={onRetry}>
        Try again
      </Button>
    </div>
  );
}
