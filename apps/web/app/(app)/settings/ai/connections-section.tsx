"use client";

import { Plus, RefreshCw, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { toast } from "sonner";

import { ConnectionStatus } from "@/components/anveshan/connection-status";
import { ErrorLine } from "@/components/anveshan/error-line";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardTitle, Overline, Skeleton } from "@/components/ui/card";
import { useConnections, useDeleteConnection, useTestConnection, type Connection } from "@/lib/api/hooks/connections";
import { describeTestError, providerName } from "@/lib/providers";

/** Settings → AI: the user's connections — test, delete (with confirm), add via the wizard. */
export function ConnectionsSection() {
  const connections = useConnections();
  return (
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Overline>01 — Connections</Overline>
          <CardTitle className="mt-2">AI connections</CardTitle>
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
        <ErrorLine message="Couldn't load your connections." onRetry={() => void connections.refetch()} />
      ) : connections.data.length === 0 ? (
        <p className="mt-6 text-body-sm text-muted-foreground">No connections yet. Add one to start researching.</p>
      ) : (
        <ul className="mt-6 border-t border-border">
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
      const description = r.sampleModel ? `Checked with ${r.sampleModel}` : undefined;
      // the key works, but a note means something needs the user's eye (e.g. the routed model was skipped)
      if (r.note) toast.warning(`${c.label}: ${r.note}`, { description });
      else toast.success(`${c.label} works.`, { description });
    } catch (e) {
      toast.error(`${c.label}: ${describeTestError(e, providerName(c.provider), "settings").message}`);
    }
  }

  return (
    <li className="flex flex-col gap-3 border-b border-border py-4 sm:flex-row sm:items-center" data-testid="connection-row">
      <div className="min-w-0 flex-1">
        <p className="truncate text-body font-medium">{c.label}</p>
        <p className="font-mono text-caption text-muted-foreground">
          {c.provider} · key ••••{c.keyLast4}
          {c.statusDetail ? ` · ${c.statusDetail}` : ""}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-3">
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
            <Button variant="ghost" size="icon" aria-label={`Delete ${c.label}`} onClick={() => setConfirming(true)}>
              <Trash2 aria-hidden />
            </Button>
          </span>
        )}
      </div>
    </li>
  );
}
