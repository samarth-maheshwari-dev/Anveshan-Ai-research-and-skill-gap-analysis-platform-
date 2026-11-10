import { CircleCheck, CircleHelp, Swords, TriangleAlert } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";

import { Logo } from "@/components/anveshan/logo";
import { Overline } from "@/components/ui/card";

import { LoginPanel } from "./login-panel";

export const metadata: Metadata = { title: "Sign in" };

const LABELS = [
  { Icon: CircleCheck, word: "Supported" },
  { Icon: TriangleAlert, word: "Weak evidence" },
  { Icon: Swords, word: "Conflicting" },
  { Icon: CircleHelp, word: "No source found" },
];

/** S-02 Login (APPFLOW §15): two providers, no passwords, one privacy line. */
export default function LoginPage() {
  return (
    <div className="grid min-h-screen lg:grid-cols-3">
      <main className="flex flex-col px-4 py-6 md:px-10 lg:col-span-2">
        <Link href="/" aria-label="Anveshan home" className="inline-flex min-h-11 items-center self-start rounded-md">
          <Logo />
        </Link>
        <div className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center py-12">
          <Overline>Sign in</Overline>
          <h1 className="mt-4 text-display-lg font-semibold">Sign in to Anveshan</h1>
          <p className="mt-3 text-body text-muted-foreground">Use your Google or GitHub account. No new password.</p>
          <Suspense fallback={<div className="mt-8 h-28" />}>
            <LoginPanel />
          </Suspense>
          <p className="mt-8 border-t border-border pt-4 text-caption text-muted-foreground">
            We never post anything or read your email content.
          </p>
        </div>
      </main>

      <aside
        aria-label="How Anveshan labels evidence"
        className="hidden flex-col justify-between bg-inverse p-10 text-inverse-foreground lg:flex"
      >
        <Overline className="text-inverse-muted">Every claim gets one of four labels</Overline>
        <div>
          <p className="text-heading-lg font-semibold">
            A report is only as good as the sentence behind each claim.
          </p>
          <ul className="mt-10 grid grid-cols-1 gap-px border border-inverse-muted bg-inverse-muted">
            {LABELS.map(({ Icon, word }) => (
              <li key={word} className="flex items-center gap-2 bg-inverse px-4 py-3 text-body-sm font-medium">
                <Icon aria-hidden className="size-4" strokeWidth={2} />
                {word}
              </li>
            ))}
          </ul>
        </div>
        <p className="font-mono text-micro uppercase text-inverse-muted">Anveshan · research you can defend</p>
      </aside>
    </div>
  );
}
