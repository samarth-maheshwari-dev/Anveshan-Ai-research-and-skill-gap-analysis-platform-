import { cn } from "@/lib/utils";
import type { HTMLAttributes } from "react";

/** Card (DESIGN §9.4): dark surface, 1px hairline, 12px radius, no shadow — depth comes from contrast. */
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

/** Small uppercase section label, e.g. "WORKSPACE" (DESIGN §3.2 micro). */
export function Overline({ className, ...props }: HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn("text-micro font-medium uppercase text-subtle-foreground", className)} {...props} />;
}

/** One number with a label (dashboard): honest values only — "—" when there is nothing to count yet. */
type StatProps = { label: string; value: string; hint?: string; className?: string };

export function Stat({ label, value, hint, className }: StatProps) {
  return (
    <div className={cn("rounded-lg border border-border bg-surface p-4 md:p-5", className)}>
      <p className="text-body-sm text-muted-foreground">{label}</p>
      <p className="tabular mt-2 text-heading-lg font-semibold md:text-display-lg">{value}</p>
      {hint && <p className="mt-1 text-caption text-subtle-foreground">{hint}</p>}
    </div>
  );
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
