import { cn } from "@/lib/utils";

/** Symbol: a magnifier whose lens holds a small diya flame (DESIGN §6.3). Colors come from tokens. */
export function LogoMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden className={cn("size-7", className)}>
      <circle cx="13.5" cy="13.5" r="9" fill="none" stroke="var(--primary)" strokeWidth="2.75" />
      <path d="M20.2 20.2 27.5 27.5" stroke="var(--primary)" strokeWidth="3.25" strokeLinecap="round" />
      <path
        d="M13.5 7.8c2.4 2.4 3.6 4.4 3.6 6.3a3.6 3.6 0 0 1-7.2 0c0-1.2.5-2.3 1.4-3.3.3 1 .9 1.6 1.6 1.8-.3-1.6.1-3.2.6-4.8Z"
        fill="var(--accent)"
      />
    </svg>
  );
}

export function Logo({ className }: { className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-2", className)}>
      <LogoMark />
      <span className="font-display text-heading-md font-semibold tracking-tight">Anveshan</span>
    </span>
  );
}

/** Line-drawing diya with one saffron accent (DESIGN §6.2). Decorative. */
export function DiyaIllustration({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 120 120" aria-hidden className={cn("size-28", className)}>
      <path
        d="M60 30c8 9 12 16 12 23a12 12 0 0 1-24 0c0-4 1.7-8 5-11.5 1 3.4 3 5.4 5.5 6-1-5.5.3-11 1.5-17.5Z"
        fill="var(--accent)"
      />
      <path
        d="M24 70h72c-2 14-17 24-36 24S26 84 24 70Z"
        fill="none"
        stroke="var(--muted-foreground)"
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
      <path d="M44 94c4 6 9 8 16 8s12-2 16-8" fill="none" stroke="var(--muted-foreground)" strokeWidth="1.5" />
      <path d="M58 70v-6M62 70v-6" stroke="var(--muted-foreground)" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  );
}
