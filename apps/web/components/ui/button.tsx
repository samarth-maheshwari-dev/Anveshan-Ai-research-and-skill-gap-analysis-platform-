import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";
import { LoaderCircle } from "lucide-react";
import type { ButtonHTMLAttributes } from "react";

/** Button (DESIGN §9.1). One `primary` per screen region; destructive actions are never primary. */
export const buttonVariants = cva(
  "inline-flex shrink-0 select-none items-center justify-center gap-2 whitespace-nowrap rounded-md border font-medium " +
    "transition-[background-color,border-color,color,transform,box-shadow] duration-150 ease-standard " +
    "active:scale-[0.97] disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 " +
    "[&_svg]:pointer-events-none [&_svg]:shrink-0",
  {
    variants: {
      variant: {
        primary: "border-transparent bg-primary text-primary-foreground hover:-translate-y-px hover:bg-primary-hover",
        secondary: "border-border-strong bg-surface text-foreground hover:bg-surface-2",
        ghost: "border-transparent bg-transparent text-foreground hover:bg-surface-2",
        soft: "border-transparent bg-primary-soft text-primary hover:bg-surface-3",
        danger: "border-transparent bg-danger text-primary-foreground hover:opacity-90 dark:text-background",
        link: "h-auto border-transparent bg-transparent p-0 text-primary underline-offset-4 hover:underline",
      },
      size: {
        sm: "h-8 px-3 text-body-sm [&_svg]:size-4",
        md: "h-10 px-4 text-body [&_svg]:size-4",
        lg: "h-12 px-5 text-base font-semibold [&_svg]:size-5",
        icon: "size-10 [&_svg]:size-5",
      },
    },
    compoundVariants: [{ variant: "link", className: "h-auto px-0" }],
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
