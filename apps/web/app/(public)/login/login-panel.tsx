"use client";

import { CircleAlert, Info } from "lucide-react";
import { useSearchParams } from "next/navigation";

import { buttonVariants } from "@/components/ui/button";
import { loginUrl } from "@/lib/env";

/** Only same-app paths are passed on; the API re-checks against its allow-list (TECHSPEC §5.3). */
export function safeNext(next: string | null): string | undefined {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.includes("\\")) return undefined;
  return next;
}

export function LoginPanel() {
  const params = useSearchParams();
  const next = safeNext(params.get("next"));
  const expired = params.get("expired") === "1";
  const failed = params.get("error") === "1";

  return (
    <>
      {expired && (
        <p role="status" className="mt-8 flex items-start gap-2 rounded-md border border-border-strong bg-surface p-3 text-body-sm">
          <Info aria-hidden className="mt-0.5 size-4 shrink-0 text-primary" />
          Your session expired. Please sign in again.
        </p>
      )}
      {failed && (
        <p role="alert" className="mt-8 flex items-start gap-2 rounded-md border border-danger/40 bg-danger-soft p-3 text-body-sm">
          <CircleAlert aria-hidden className="mt-0.5 size-4 shrink-0 text-danger" />
          Sign-in was cancelled or didn&apos;t finish. Please try again.
        </p>
      )}
      <div className="mt-8 flex flex-col gap-3">
        <a href={loginUrl("google", next)} className={buttonVariants({ variant: "primary", size: "lg" })}>
          <GoogleMark /> Continue with Google
        </a>
        <a href={loginUrl("github", next)} className={buttonVariants({ variant: "secondary", size: "lg" })}>
          <GitHubMark /> Continue with GitHub
        </a>
      </div>
    </>
  );
}

/** Monochrome provider marks (currentColor) — no brand colors in our UI. */
function GoogleMark() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden className="size-5" fill="currentColor">
      <path d="M21.35 11.1H12v2.9h5.35c-.5 2.4-2.6 3.9-5.35 3.9a6 6 0 1 1 3.9-10.55l2.1-2.1A9 9 0 1 0 12 21c5.2 0 8.6-3.65 8.6-8.8 0-.4-.05-.75-.1-1.1Z" />
    </svg>
  );
}

function GitHubMark() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden className="size-5" fill="currentColor">
      <path d="M12 2a10 10 0 0 0-3.16 19.49c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.61.07-.61 1 .07 1.53 1.03 1.53 1.03.9 1.52 2.34 1.08 2.91.83.09-.65.35-1.08.63-1.33-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.98 1.03-2.68-.1-.25-.45-1.27.1-2.65 0 0 .84-.27 2.75 1.02a9.6 9.6 0 0 1 5 0c1.91-1.29 2.75-1.02 2.75-1.02.55 1.38.2 2.4.1 2.65.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.68-4.57 4.93.36.31.68.92.68 1.85v2.75c0 .27.18.58.69.48A10 10 0 0 0 12 2Z" />
    </svg>
  );
}
