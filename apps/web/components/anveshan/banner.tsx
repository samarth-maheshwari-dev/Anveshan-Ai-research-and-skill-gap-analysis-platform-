import { cn } from "@/lib/utils";
import { KeyRound, TriangleAlert } from "lucide-react";
import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";

export type BannerModel = { kind: "info" | "danger"; text: string; action: { label: string; href: string } };

type BannerProps = { banner: BannerModel };

/** Global banner (DESIGN §9.9, APPFLOW §2.2): one at a time, icon + one sentence + one action. */
export function Banner({ banner }: BannerProps) {
  const danger = banner.kind === "danger";
  const Icon = danger ? TriangleAlert : KeyRound;
  return (
    <div
      role="status"
      data-testid="global-banner"
      className={cn(
        "flex min-h-12 flex-wrap items-center gap-3 border-b px-4 py-2 text-body-sm text-foreground md:px-6",
        danger ? "border-danger/40 bg-danger-soft" : "border-border bg-surface",
      )}
    >
      <Icon aria-hidden className={cn("size-4 shrink-0", danger ? "text-danger" : "text-primary")} />
      <span className="flex-1">{banner.text}</span>
      <Link href={banner.action.href} className={buttonVariants({ variant: danger ? "secondary" : "primary", size: "sm" })}>
        {banner.action.label}
      </Link>
    </div>
  );
}

type Conn = { label?: string; status?: string };

/** Highest-priority banner for the shell (APPFLOW §2.2 #2 and #3; #1 is the 401 redirect). */
export function pickBanner(hasVerifiedConnection: boolean | undefined, connections: Conn[] | undefined): BannerModel | null {
  if (hasVerifiedConnection === false) {
    return {
      kind: "info",
      text: "Connect an AI provider to start researching.",
      action: { label: "Connect AI", href: "/onboarding/connect-ai" },
    };
  }
  const failing = connections?.find((c) => c.status === "FAILING");
  if (failing) {
    return {
      kind: "danger",
      text: failing.label ? `The key “${failing.label}” stopped working.` : "Your AI key stopped working.",
      action: { label: "Fix", href: "/settings/ai" },
    };
  }
  return null;
}
