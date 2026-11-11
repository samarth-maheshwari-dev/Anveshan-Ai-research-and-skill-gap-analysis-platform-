import { cn } from "@/lib/utils";
import type { ComponentProps } from "react";

const FIELD =
  "h-10 w-full rounded-md border border-input bg-surface px-3 text-body text-foreground max-sm:h-11 " +
  "transition-[border-color] duration-150 hover:border-muted-foreground focus:border-primary " +
  "aria-invalid:border-danger disabled:cursor-not-allowed disabled:opacity-60";

/** Input (DESIGN §9.2): --input border (3:1), green border + focus ring on focus, ≥ 44px on phones. */
export function Input({ className, ...props }: ComponentProps<"input">) {
  return (
    <input
      data-slot="input"
      className={cn(FIELD, "placeholder:text-subtle-foreground", className)}
      {...props}
    />
  );
}

/** Multi-line input: same border, focus and error states as {@link Input}. */
export function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(FIELD, "h-auto min-h-24 resize-y py-2.5 placeholder:text-subtle-foreground max-sm:h-auto", className)}
      {...props}
    />
  );
}

export function Select({ className, children, ...props }: ComponentProps<"select">) {
  return (
    <select data-slot="select" className={cn(FIELD, className)} {...props}>
      {children}
    </select>
  );
}

export function Label({ className, ...props }: ComponentProps<"label">) {
  return <label className={cn("text-caption font-medium text-foreground", className)} {...props} />;
}
