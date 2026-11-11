"use client";

import { CircleCheck, FlaskConical, KeyRound, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { ConnectionStatus } from "@/components/anveshan/connection-status";
import { ErrorLine } from "@/components/anveshan/error-line";
import { buttonVariants } from "@/components/ui/button";
import { Overline, Skeleton, Stat } from "@/components/ui/card";
import { useConnections, type Connection } from "@/lib/api/hooks/connections";
import { useMe } from "@/lib/api/hooks/me";
import { TASK_SLOTS, useSlots } from "@/lib/api/hooks/slots";
import { timeAgo } from "@/lib/time";

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

/**
 * S-04 Dashboard (APPFLOW §4.2 steps 4 and 10), laid out like the owner's reference design: stats, ongoing
 * research, recent activity. Only real numbers — research and knowledge show "—" until those modules exist.
 */
export function DashboardView() {
  const router = useRouter();
  const me = useMe();
  const connections = useConnections();
  const slots = useSlots();
  const verified = me.data?.onboarding?.hasVerifiedConnection;

  // First visit without a working key → offer the wizard once per browser session.
  useEffect(() => {
    if (verified === false && !offeredOnce()) router.replace("/onboarding/connect-ai");
  }, [verified, router]);

  const firstName = me.data?.name?.split(" ")[0];
  const verifiedCount = connections.data?.filter((c) => c.status === "VERIFIED").length ?? 0;
  const routed = slots.data ? TASK_SLOTS.filter((s) => (slots.data[s]?.length ?? 0) > 0).length : null;

  return (
    <div>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <Overline>Workspace</Overline>
          <h1 className="mt-2 text-display-lg font-semibold">{firstName ? `Welcome, ${firstName}` : "Welcome"}</h1>
          <p className="mt-1 text-body text-muted-foreground">Your research overview at a glance.</p>
        </div>
        <Link href="/research/new" className={buttonVariants({ variant: "primary" })}>
          <Plus aria-hidden /> New research
        </Link>
      </div>

      <div className="mt-8 grid grid-cols-2 gap-3 md:gap-4 xl:grid-cols-4">
        <Stat
          label="AI connections"
          value={connections.data ? `${verifiedCount}` : "—"}
          hint={connections.data ? `${connections.data.length} connected · ${verifiedCount} verified` : undefined}
        />
        <Stat label="Tasks routed" value={routed === null ? "—" : `${routed} / ${TASK_SLOTS.length}`} hint="Plan, extract, verify, write, ask, interview" />
        <Stat label="Research" value="—" hint="Your runs list arrives soon" />
        <Stat label="Knowledge items" value="—" hint="Grows with every report" />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <section aria-labelledby="ongoing" className="rounded-lg border border-border bg-surface lg:col-span-2">
          <div className="flex items-center justify-between border-b border-border px-6 py-4">
            <h2 id="ongoing" className="text-heading-sm font-semibold">
              Ongoing research
            </h2>
          </div>
          <div className="p-6">
            <div className="flex items-start gap-4">
              <span className="flex size-10 shrink-0 items-center justify-center rounded-md bg-surface-2">
                <FlaskConical aria-hidden className="size-5 text-muted-foreground" />
              </span>
              <div>
                <h3 className="text-heading-sm font-semibold">Start your first research</h3>
                <p className="mt-1 max-w-lg text-body-sm text-muted-foreground">
                  Ask a question once. Anveshan plans it with you, reads the sources and writes a report where every
                  claim shows its proof.
                </p>
              </div>
            </div>
            <p className="mt-6 text-caption font-medium text-subtle-foreground">Try a topic like</p>
            <ul className="mt-2 space-y-2">
              {EXAMPLES.map((t) => (
                <li key={t} className="rounded-md border border-border bg-background px-4 py-3 text-body-sm">
                  {t}
                </li>
              ))}
            </ul>
          </div>
        </section>

        <div className="space-y-6">
          <section aria-labelledby="activity" className="rounded-lg border border-border bg-surface p-6">
            <h2 id="activity" className="text-heading-sm font-semibold">
              Recent activity
            </h2>
            <Activity connections={connections.data} />
          </section>

          <section aria-labelledby="ai-connections" className="rounded-lg border border-border bg-surface p-6">
            <h2 id="ai-connections" className="text-heading-sm font-semibold">
              AI connections
            </h2>
            {connections.isPending ? (
              <div className="mt-4 space-y-2">
                <Skeleton className="h-6 w-full" />
                <Skeleton className="h-6 w-2/3" />
              </div>
            ) : connections.isError ? (
              <ErrorLine message="Couldn't load your AI connections." onRetry={() => void connections.refetch()} />
            ) : connections.data && connections.data.length > 0 ? (
              <ul className="mt-4 space-y-2">
                {connections.data.map((c) => (
                  <li key={c.id} className="flex items-center justify-between gap-2 rounded-md bg-background px-3 py-2.5">
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
              className={`${buttonVariants({ variant: verified ? "secondary" : "primary" })} mt-5 w-full`}
            >
              {verified ? "Manage AI" : "Connect AI"}
            </Link>
          </section>
        </div>
      </div>
    </div>
  );
}

type ActivityProps = { connections: Connection[] | undefined };

/** A timeline from what really happened so far: keys connected and verified (research events join in P10+). */
function Activity({ connections }: ActivityProps) {
  // "now" is read once per mount (render stays pure); minutes-ago only needs to be roughly right
  const [now] = useState(() => new Date());
  const items = (connections ?? [])
    .flatMap((c) => [
      ...(c.createdAt ? [{ key: `${c.id}-c`, at: c.createdAt, text: `Connected ${c.label}`, done: false }] : []),
      ...(c.lastTestedAt && c.status === "VERIFIED"
        ? [{ key: `${c.id}-v`, at: c.lastTestedAt, text: `${c.label} verified`, done: true }]
        : []),
    ])
    .sort((a, b) => b.at.localeCompare(a.at))
    .slice(0, 5);

  if (items.length === 0) {
    return <p className="mt-4 text-body-sm text-muted-foreground">Nothing to show yet. Connecting keys and running research shows up here.</p>;
  }
  return (
    <ol className="mt-4 space-y-4">
      {items.map((i) => (
        <li key={i.key} className="flex gap-3">
          <span
            className={`mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full ${i.done ? "bg-primary-soft text-primary" : "bg-surface-2 text-muted-foreground"}`}
          >
            {i.done ? <CircleCheck aria-hidden className="size-3.5" /> : <KeyRound aria-hidden className="size-3.5" />}
          </span>
          <div>
            <p className="text-body-sm">{i.text}</p>
            <p className="text-caption text-subtle-foreground">{timeAgo(i.at, now)}</p>
          </div>
        </li>
      ))}
    </ol>
  );
}
