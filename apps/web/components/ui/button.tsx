import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";
import { LoaderCircle } from "lucide-react";
import type { ButtonHTMLAttributes } from "react";

/**
 * Button (DESIGN §9.1). Green `primary` is the 10 % — one per screen region. Everything else is
 * black/white. Flat, 4px radius, ≥ 44px tall on phones. Destructive actions are never primary.
 */
export const buttonVariants = cva(
  "inline-flex shrink-0 select-none items-center justify-center gap-2 whitespace-nowrap rounded-md border font-medium " +
    "transition-[background-color,border-color,color,opacity] duration-150 ease-standard " +
    "disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 " +
    "[&_svg]:pointer-events-none [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        primary: "border-primary bg-primary text-primary-foreground hover:border-primary-hover hover:bg-primary-hover",
        secondary: "border-border-strong bg-transparent text-foreground hover:border-foreground",
        ghost: "border-transparent bg-transparent text-foreground hover:bg-surface-2",
        danger: "border-danger bg-transparent text-danger hover:bg-danger-soft",
        link: "h-auto border-transparent bg-transparent p-0 text-primary underline underline-offset-4 decoration-1 hover:decoration-2",
      },
      size: {
        sm: "h-8 px-3 text-body-sm max-sm:h-11 [&_svg]:size-4",
        md: "h-10 px-4 text-body max-sm:h-11 [&_svg]:size-4",
        lg: "h-12 px-6 text-body [&_svg]:size-4",
        icon: "size-10 max-sm:size-11 [&_svg]:size-5",
      },
    },
    compoundVariants: [{ variant: "link", className: "h-auto px-0 max-sm:min-h-11" }],
    defaultVariants: { variant: "secondary", size: "md" },
  },
);

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> &
  VariantProps<typeof buttonVariants> & { loading?: boolean };

export function Button({ className, variant, size, loading = false, disabled, children, type, ...props }: ButtonProps) {
  return (
    <button
      type={type ?? "button"}
      data-slot="button"
      className={cn(buttonVariants({ variant, size }), className)}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...props}
    >
      {loading && <LoaderCircle className="animate-spin" aria-hidden />}
      <span className={cn("inline-flex items-center gap-2", loading && "opacity-60")}>{children}</span>
    </button>
  );
}
