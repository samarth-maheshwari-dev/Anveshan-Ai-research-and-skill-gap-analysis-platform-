import { cn } from "@/lib/utils";
import type { HTMLAttributes } from "react";

/** Card (DESIGN §9.4): flat surface, 1px hairline, 6px radius, no shadow — depth comes from contrast. */
export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      data-slot="card"
      className={cn("rounded-lg border border-border bg-surface p-6 text-card-foreground", className)}
      {...props}
    />
  );
}

export function CardTitle({ className, ...props }: HTMLAttributes<HTMLHeadingElement>) {
  return <h2 className={cn("text-heading-sm font-semibold", className)} {...props} />;
}

/** Mono overline label, e.g. "01 — AI CONNECTIONS" (DESIGN §3.2 micro). */
export function Overline({ className, ...props }: HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn("font-mono text-micro uppercase text-muted-foreground", className)} {...props} />;
}

export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div aria-hidden className={cn("skeleton rounded-md", className)} {...props} />;
}

/** Badge (DESIGN §9.5): 24px, small radius, caption text, icon + word. */
export function Badge({ className, ...props }: HTMLAttributes<HTMLSpanElement>) {
  return (
    <span
      className={cn(
        "inline-flex h-6 items-center gap-1.5 rounded-sm px-2 text-caption font-medium [&_svg]:size-3.5",
        className,
      )}
      {...props}
    />
  );
}
