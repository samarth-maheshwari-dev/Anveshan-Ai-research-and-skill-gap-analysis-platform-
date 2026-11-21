import { cn } from "@/lib/utils";
import { Info, TriangleAlert } from "lucide-react";
import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";

export type BannerModel = { kind: "info" | "danger"; text: string; action: { label: string; href: string } };

type BannerProps = { banner: BannerModel };

/** Global banner (DESIGN §9.9, APPFLOW §2.2): one at a time, icon + one sentence + one action. */
export function Banner({ banner }: BannerProps) {
  const danger = banner.kind === "danger";
  const Icon = danger ? TriangleAlert : Info;
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
