"use client";

import { Overline, Skeleton } from "@/components/ui/card";
import { useMe } from "@/lib/api/hooks/me";

/** Settings: the account. Anveshan runs the AI itself, so there are no keys to manage. */
export default function SettingsPage() {
  const me = useMe();
  return (
    <div className="mx-auto max-w-3xl">
      <Overline>Settings</Overline>
      <h1 className="mt-3 text-display-lg font-semibold">Settings</h1>
      <section aria-labelledby="account" className="mt-8 rounded-lg border border-border bg-surface p-6">
        <h2 id="account" className="text-heading-sm font-semibold">
          Account
        </h2>
        {me.data ? (
          <dl className="mt-4 space-y-2 text-body-sm">
            <div className="flex gap-2">
              <dt className="text-muted-foreground">Name</dt>
              <dd>{me.data.name}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-muted-foreground">Email</dt>
              <dd>{me.data.email}</dd>
            </div>
          </dl>
        ) : (
          <Skeleton className="mt-4 h-10 w-2/3" />
        )}
        <p className="mt-6 text-body-sm text-muted-foreground">Anveshan runs the AI for you. There are no keys to connect.</p>
      </section>
    </div>
  );
}
