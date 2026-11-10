import { cn } from "@/lib/utils";
import type { ComponentProps } from "react";

/** Input (DESIGN §9.2): 40px, --input border (3:1), primary border + soft glow on focus. */
export function Input({ className, ...props }: ComponentProps<"input">) {
  return (
    <input
      data-slot="input"
      className={cn(
        "h-10 w-full rounded-md border border-input bg-surface px-3 text-body text-foreground",
        "placeholder:text-subtle-foreground transition-[border-color,box-shadow] duration-150",
        "focus:border-primary focus:shadow-[0_0_0_3px_var(--primary-soft)]",
        "aria-invalid:border-danger disabled:cursor-not-allowed disabled:opacity-60",
        className,
      )}
      {...props}
    />
  );
}

export function Select({ className, children, ...props }: ComponentProps<"select">) {
  return (
    <select
      data-slot="select"
      className={cn(
        "h-10 w-full rounded-md border border-input bg-surface px-3 text-body text-foreground",
        "focus:border-primary focus:shadow-[0_0_0_3px_var(--primary-soft)] disabled:opacity-60",
        className,
      )}
      {...props}
    >
      {children}
    </select>
  );
}

export function Label({ className, ...props }: ComponentProps<"label">) {
  return <label className={cn("text-caption font-medium text-foreground", className)} {...props} />;
}
