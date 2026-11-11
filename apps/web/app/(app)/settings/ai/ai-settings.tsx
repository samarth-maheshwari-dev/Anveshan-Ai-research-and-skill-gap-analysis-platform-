"use client";

import { BarChart3, Bot, FlaskConical, Shield, UserRound } from "lucide-react";
import Link from "next/link";

import { Overline } from "@/components/ui/card";

import { ConnectionsSection } from "./connections-section";
import { SlotsSection } from "./slots-section";

const TABS = [
  { id: "profile", label: "Profile", Icon: UserRound },
  { id: "ai", label: "AI", Icon: Bot },
  { id: "research", label: "Research", Icon: FlaskConical },
  { id: "privacy", label: "Privacy", Icon: Shield },
  { id: "usage", label: "Usage", Icon: BarChart3 },
];

/** S-20 Settings → AI (APPFLOW §12.3). Other tabs arrive with their phases. */
export function AiSettings() {
  return (
    <div>
      <Overline>Account</Overline>
      <h1 className="mt-2 text-display-lg font-semibold">Settings</h1>
      <p className="mt-1 text-body text-muted-foreground">Your AI keys and which model does each research task.</p>
      <div className="mt-8 flex flex-col gap-8 lg:flex-row lg:gap-12">
        <nav aria-label="Settings sections" className="lg:w-48 lg:shrink-0">
          <ul className="flex flex-wrap gap-1 border-b border-border pb-2 lg:flex-col lg:flex-nowrap lg:border-b-0 lg:pb-0">
            {TABS.map((t) =>
              t.id === "ai" ? (
                <li key={t.id}>
                  <Link
                    href="/settings/ai"
                    aria-current="page"
                    // left-aligned like the other tabs in the vertical list (centred only in the phone row)
                    className="relative flex h-10 min-w-11 items-center justify-center rounded-md bg-surface-2 px-3 text-body-sm font-medium text-foreground max-sm:h-11 lg:justify-start"
                  >
                    <span aria-hidden className="absolute inset-y-2.5 left-0 w-0.5 rounded-full bg-primary" />
                    <t.Icon aria-hidden className="mr-2 size-4" />
                    {t.label}
                  </Link>
                </li>
              ) : (
                <li key={t.id}>
                  <span
                    aria-disabled="true"
                    className="flex h-10 items-center gap-2 whitespace-nowrap rounded-md px-3 text-body-sm text-subtle-foreground"
                  >
                    <t.Icon aria-hidden className="size-4" />
                    {t.label} <span className="rounded-xs bg-surface-2 px-1.5 text-micro">Soon</span>
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
