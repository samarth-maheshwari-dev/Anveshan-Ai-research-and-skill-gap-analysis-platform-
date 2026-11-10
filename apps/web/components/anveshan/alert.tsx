import { CircleAlert, Info, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

/**
 * - danger: something failed (bad key, couldn't save, couldn't load).
 * - neutral: not a failure of the thing the user did ("Your key works, but…").
 * - info: a heads-up ("Your session expired").
 */
export type AlertTone = "danger" | "neutral" | "info";

const TONES: Record<AlertTone, { box: string; icon: LucideIcon; iconColor: string }> = {
  danger: { box: "border-danger/40 bg-danger-soft", icon: CircleAlert, iconColor: "text-danger" },
  neutral: { box: "border-border-strong bg-surface-2", icon: CircleAlert, iconColor: "text-foreground" },
  info: { box: "border-border-strong bg-surface", icon: Info, iconColor: "text-primary" },
};

type AlertProps = {
  tone?: AlertTone;
  children: ReactNode;
  /** Optional control on the right, e.g. a "Try again" button. */
  action?: ReactNode;
  id?: string;
  /** Outer spacing only (e.g. "mt-5"); colours come from `tone`. */
  className?: string;
};

/**
 * The one inline message box (APPFLOW §2.3, DESIGN §12): icon + plain words, never colour alone.
 * danger/neutral are announced at once (role="alert"); info politely (role="status").
 */
export function Alert({ tone = "danger", children, action, id, className }: AlertProps) {
  const { box, icon: Icon, iconColor } = TONES[tone];
  return (
    <div
      id={id}
      role={tone === "info" ? "status" : "alert"}
      className={cn(
        "flex gap-3 rounded-md border p-3 text-body-sm text-foreground",
        action ? "items-center justify-between" : "items-start",
        box,
        className,
      )}
    >
      <span className="flex items-start gap-2">
        <Icon aria-hidden className={cn("mt-0.5 size-4 shrink-0", iconColor)} />
        <span>{children}</span>
      </span>
      {action}
    </div>
  );
}
