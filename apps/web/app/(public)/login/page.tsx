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
          <p className="font-display text-display-xl font-medium">
            Ask anything.
            <br />
            Get answers <em className="text-primary">with proof.</em>
          </p>
          <p className="mt-4 max-w-sm text-body-lg text-muted-foreground">
            Your own research helper — every sentence linked to the place it came from.
          </p>
        </div>
      </aside>

      <main className="flex flex-col px-4 py-6 md:px-10">
        <Link href="/" aria-label="Anveshan home" className="inline-flex min-h-11 items-center self-start lg:hidden">
          <Logo />
        </Link>
        <div className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center py-12">
          <h1 className="text-display-lg font-bold">Welcome</h1>
          <p className="mt-2 text-body text-muted-foreground">
            Pick one button below. If you are new, the same button makes your free account. No new password to remember.
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
