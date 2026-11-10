"use client";

import { FlaskConical } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { ConnectionStatus } from "@/components/anveshan/connection-status";
import { buttonVariants } from "@/components/ui/button";
import { Overline, Skeleton } from "@/components/ui/card";
import { useConnections } from "@/lib/api/hooks/connections";
import { ErrorLine } from "@/components/anveshan/error-line";
import { useMe } from "@/lib/api/hooks/me";

const EXAMPLES = [
  "Literature review: transformers in medical imaging",
  "Compare PostgreSQL, MongoDB and Cassandra for a college ERP",
  "What does a junior backend developer need to know in 2026?",
];

export const ONBOARDING_OFFERED_KEY = "anv.onboarding.offered";

function offeredOnce(): boolean {
  try {
    if (window.sessionStorage.getItem(ONBOARDING_OFFERED_KEY)) return true;
    window.sessionStorage.setItem(ONBOARDING_OFFERED_KEY, "1");
    return false;
  } catch {
    return true; // storage blocked: never trap the user in a redirect loop
  }
}

/** S-04 Dashboard, first-time state (APPFLOW §4.2 step 4 and 10). */
export function DashboardView() {
  const router = useRouter();
  const me = useMe();
  const connections = useConnections();
  const verified = me.data?.onboarding?.hasVerifiedConnection;

  // First visit without a working key → offer the wizard once per browser session.
  useEffect(() => {
    if (verified === false && !offeredOnce()) router.replace("/onboarding/connect-ai");
  }, [verified, router]);

  const firstName = me.data?.name?.split(" ")[0];

  return (
    <div>
      <Overline>Dashboard</Overline>
      <h1 className="mt-3 text-display-lg font-semibold">{firstName ? `Welcome, ${firstName}` : "Welcome"}</h1>

      <div className="mt-10 grid gap-6 lg:grid-cols-3">
        <section aria-labelledby="first-research" className="rounded-lg border border-border bg-surface lg:col-span-2">
          <div className="p-6 md:p-8">
            <Overline>01 — First research</Overline>
            <h2 id="first-research" className="mt-4 text-heading-lg font-semibold">
              Start your first research
            </h2>
            <p className="mt-2 max-w-lg text-body text-muted-foreground">
              Ask a question once. Anveshan plans it with you, reads the sources and writes a report where every
              claim shows its proof.
            </p>
            <div className="mt-6 flex flex-wrap items-center gap-x-4 gap-y-2">
              <span aria-disabled="true" className={buttonVariants({ variant: "secondary" })}>
                <FlaskConical aria-hidden /> New research
              </span>
              <span className="font-mono text-micro uppercase text-subtle-foreground">Opens in the next update</span>
            </div>
          </div>
          <div className="border-t border-border p-6 md:p-8">
            <Overline>Example topics</Overline>
            <ol className="mt-3 border-t border-border">
              {EXAMPLES.map((t, i) => (
                <li key={t} className="flex gap-4 border-b border-border py-3 text-body-sm">
                  <span className="font-mono text-subtle-foreground tabular">0{i + 1}</span>
                  <span>{t}</span>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section aria-labelledby="ai-connections" className="rounded-lg border border-border bg-surface p-6 lg:self-start">
          <h2 id="ai-connections" className="font-mono text-micro uppercase text-muted-foreground">
            02 — AI connections
          </h2>
          {connections.isPending ? (
            <div className="mt-4 space-y-2">
              <Skeleton className="h-6 w-full" />
              <Skeleton className="h-6 w-2/3" />
            </div>
          ) : connections.isError ? (
            <ErrorLine message="Couldn't load your AI connections." onRetry={() => void connections.refetch()} />
          ) : connections.data && connections.data.length > 0 ? (
            <ul className="mt-4 border-t border-border">
              {connections.data.map((c) => (
                <li key={c.id} className="flex items-center justify-between gap-2 border-b border-border py-3">
                  <span className="truncate text-body-sm">{c.label}</span>
                  <ConnectionStatus status={c.status} />
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-4 text-body-sm text-muted-foreground">No AI connected yet.</p>
          )}
          <Link
            href={verified ? "/settings/ai" : "/onboarding/connect-ai"}
            className={`${buttonVariants({ variant: verified ? "secondary" : "primary" })} mt-6 w-full`}
          >
            {verified ? "Manage AI" : "Connect AI"}
          </Link>
        </section>
      </div>
    </div>
  );
}
