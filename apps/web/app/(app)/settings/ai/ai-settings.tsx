"use client";

import Link from "next/link";

import { Overline } from "@/components/ui/card";

import { ConnectionsSection } from "./connections-section";
import { SlotsSection } from "./slots-section";

const TABS = [
  { id: "profile", label: "Profile" },
  { id: "ai", label: "AI" },
  { id: "research", label: "Research" },
  { id: "privacy", label: "Privacy" },
  { id: "usage", label: "Usage" },
];

/** S-20 Settings → AI (APPFLOW §12.3). Other tabs arrive with their phases. */
export function AiSettings() {
  return (
    <div>
      <Overline>Account</Overline>
      <h1 className="mt-3 text-display-lg font-semibold">Settings</h1>
      <div className="mt-10 flex flex-col gap-8 lg:flex-row lg:gap-12">
        <nav aria-label="Settings sections" className="lg:w-48 lg:shrink-0">
          <ul className="flex flex-wrap gap-1 border-b border-border pb-2 lg:flex-col lg:flex-nowrap lg:border-b-0 lg:pb-0">
            {TABS.map((t) =>
              t.id === "ai" ? (
                <li key={t.id}>
                  <Link
                    href="/settings/ai"
                    aria-current="page"
                    className="relative flex h-10 min-w-11 items-center justify-center rounded-md bg-surface-2 px-3 text-body-sm font-medium text-foreground max-sm:h-11"
                  >
                    <span aria-hidden className="absolute inset-y-2 left-0 w-0.5 bg-primary" />
                    {t.label}
                  </Link>
                </li>
              ) : (
                <li key={t.id}>
                  <span
                    aria-disabled="true"
                    className="flex h-10 items-center gap-2 whitespace-nowrap rounded-md px-3 text-body-sm text-subtle-foreground"
                  >
                    {t.label} <span className="font-mono text-micro uppercase">Soon</span>
                  </span>
                </li>
              ),
            )}
          </ul>
        </nav>
        <div className="min-w-0 flex-1 space-y-8">
          <ConnectionsSection />
          <SlotsSection />
        </div>
      </div>
    </div>
  );
}
