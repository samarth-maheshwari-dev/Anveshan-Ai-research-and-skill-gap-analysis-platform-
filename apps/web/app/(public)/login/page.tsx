import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";

import { Logo } from "@/components/anveshan/logo";
import { ResearchIllustration } from "@/components/anveshan/research-illustration";

import { LoginPanel } from "./login-panel";

export const metadata: Metadata = { title: "Sign in" };

/**
 * S-02 Login (APPFLOW §15), laid out like the owner's reference: story panel left, sign-in right. Sign-in is
 * Google or GitHub only — Anveshan never stores passwords (TECHSPEC §5).
 */
export default function LoginPage() {
  return (
    <div className="grid min-h-screen bg-background lg:grid-cols-2">
      <aside
        aria-label="About Anveshan"
        className="relative hidden flex-col justify-between overflow-hidden border-r border-border bg-surface p-10 lg:flex"
      >
        <div aria-hidden className="dot-grid absolute inset-0" />
        <Link href="/" aria-label="Anveshan home" className="relative inline-flex min-h-11 items-center self-start">
          <Logo />
        </Link>
        <ResearchIllustration className="relative mx-auto w-full max-w-md" />
        <div className="relative">
          <p className="text-display-xl font-semibold">
            Research deeper.
            <br />
            Prove every claim.
          </p>
          <p className="mt-4 max-w-sm text-body-lg text-muted-foreground">
            Your AI-powered research workspace — every sentence linked to the source behind it.
          </p>
        </div>
      </aside>

      <main className="flex flex-col px-4 py-6 md:px-10">
        <Link href="/" aria-label="Anveshan home" className="inline-flex min-h-11 items-center self-start lg:hidden">
          <Logo />
        </Link>
        <div className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center py-12">
          <h1 className="text-display-lg font-semibold">Welcome back</h1>
          <p className="mt-2 text-body text-muted-foreground">
            Sign in to continue to Anveshan. Use your Google or GitHub account — no new password.
          </p>
          <Suspense fallback={<div className="mt-8 h-28" />}>
            <LoginPanel />
          </Suspense>
          <p className="mt-8 border-t border-border pt-4 text-caption text-muted-foreground">
            We never post anything or read your email content. New here? The same buttons create your account.
          </p>
        </div>
      </main>
    </div>
  );
}
