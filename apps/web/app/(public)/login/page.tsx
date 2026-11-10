import type { Metadata } from "next";
import { Suspense } from "react";

import { DiyaIllustration, Logo } from "@/components/anveshan/logo";

import { LoginPanel } from "./login-panel";

export const metadata: Metadata = { title: "Sign in" };

/** S-02 Login (APPFLOW §15): two providers, no passwords, one privacy line. */
export default function LoginPage() {
  return (
    <main className="flex min-h-screen items-center justify-center px-4 py-12">
      <div className="w-full max-w-sm">
        <div className="flex justify-center">
          <Logo />
        </div>
        <div className="mt-8 rounded-xl border border-border bg-surface p-8 shadow-e2">
          <div className="flex justify-center">
            <DiyaIllustration className="size-20" />
          </div>
          <h1 className="mt-4 text-center font-display text-heading-lg font-semibold">Sign in to Anveshan</h1>
          <Suspense fallback={<div className="mt-6 h-28" />}>
            <LoginPanel />
          </Suspense>
          <p className="mt-6 text-center text-caption text-muted-foreground">
            We never post anything or read your email content.
          </p>
        </div>
      </div>
    </main>
  );
}
