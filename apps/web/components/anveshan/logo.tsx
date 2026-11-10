import { cn } from "@/lib/utils";

type LogoProps = { className?: string };

/**
 * Mark (DESIGN §6.3): a monochrome magnifier — lens + handle in the text color — holding one small
 * green square, the thing it found. The square is the only accent; everything else is black/white.
 */
export function LogoMark({ className }: LogoProps) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden className={cn("size-6", className)}>
      <circle cx="13" cy="13" r="8.75" fill="none" className="stroke-current" strokeWidth="2.5" />
      <path d="M19.6 19.6 27.5 27.5" className="stroke-current" strokeWidth="3" strokeLinecap="square" />
      <rect x="9.5" y="9.5" width="7" height="7" className="fill-primary" />
    </svg>
  );
}

export function Logo({ className }: LogoProps) {
  return (
    <span className={cn("inline-flex items-center gap-2 text-foreground", className)}>
      <LogoMark />
      <span className="text-heading-md font-semibold">Anveshan</span>
    </span>
  );
}
