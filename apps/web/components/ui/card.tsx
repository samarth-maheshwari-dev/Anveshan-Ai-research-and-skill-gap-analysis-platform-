import { cn } from "@/lib/utils";
import type { HTMLAttributes } from "react";

/** Card (DESIGN §9.4): surface, radius lg, 1px border, e1, 24px padding. */
export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      data-slot="card"
      className={cn("rounded-lg border border-border bg-surface p-6 text-card-foreground shadow-e1", className)}
      {...props}
    />
  );
}

export function CardTitle({ className, ...props }: HTMLAttributes<HTMLHeadingElement>) {
  return <h2 className={cn("text-heading-sm font-semibold", className)} {...props} />;
}

export function Overline({ className, ...props }: HTMLAttributes<HTMLParagraphElement>) {
  return (
    <p
      className={cn("text-micro font-medium uppercase tracking-wide text-muted-foreground", className)}
      {...props}
    />
  );
}

export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div aria-hidden className={cn("skeleton rounded-md", className)} {...props} />;
}

/** Badge (DESIGN §9.5): 22px, radius sm, caption text. */
export function Badge({ className, ...props }: HTMLAttributes<HTMLSpanElement>) {
  return (
    <span
      className={cn(
        "inline-flex h-6 items-center gap-1 rounded-sm px-2 text-caption font-medium [&_svg]:size-3.5",
        className,
      )}
      {...props}
    />
  );
}
