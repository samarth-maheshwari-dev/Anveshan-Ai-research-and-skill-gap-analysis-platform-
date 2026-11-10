"use client";

import { FlaskConical, KeyRound } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { ConnectionStatus } from "@/components/anveshan/connection-status";
import { DiyaIllustration } from "@/components/anveshan/logo";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardTitle, Overline, Skeleton } from "@/components/ui/card";
import { useConnections } from "@/lib/api/hooks/connections";
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
      <h1 className="font-display text-display-lg font-medium tracking-tight">
        {firstName ? `Welcome, ${firstName}` : "Welcome"}
      </h1>

      <div className="mt-8 grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <div className="flex flex-col items-center py-6 text-center">
            <DiyaIllustration />
            <h2 className="mt-4 text-heading-lg font-semibold">Start your first research</h2>
            <p className="mt-2 max-w-md text-body-sm text-muted-foreground">
              Ask a question once. Anveshan plans it with you, reads the sources and writes a report where every
              claim shows its proof.
            </p>
            <span
              aria-disabled="true"
              className={`${buttonVariants({ variant: "primary" })} mt-6`}
              title="New research opens in the next update"
            >
              <FlaskConical aria-hidden /> New research
            </span>
            <p className="mt-2 text-caption text-muted-foreground">New research opens in the next update.</p>
          </div>
          <div className="border-t border-border pt-4">
            <Overline>Example topics</Overline>
            <ul className="mt-2 space-y-2">
              {EXAMPLES.map((t) => (
                <li key={t} className="rounded-md bg-surface-2 px-3 py-2 text-body-sm">
                  {t}
                </li>
              ))}
            </ul>
          </div>
        </Card>

        <Card className="lg:self-start">
          <div className="flex items-center gap-2">
            <KeyRound aria-hidden className="size-5 text-primary" strokeWidth={1.75} />
            <CardTitle>AI connections</CardTitle>
          </div>
          {connections.isPending ? (
            <div className="mt-4 space-y-2">
              <Skeleton className="h-6 w-full" />
              <Skeleton className="h-6 w-2/3" />
            </div>
          ) : connections.data && connections.data.length > 0 ? (
            <ul className="mt-4 space-y-3">
              {connections.data.map((c) => (
                <li key={c.id} className="flex items-center justify-between gap-2">
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
            className={`${buttonVariants({ variant: verified ? "secondary" : "soft" })} mt-6 w-full`}
          >
            {verified ? "Manage AI" : "Connect AI"}
          </Link>
        </Card>
      </div>
    </div>
  );
}
