"use client";

import { LazyMotion, MotionConfig, domAnimation } from "motion/react";
import { ThemeProvider } from "next-themes";
import type { ReactNode } from "react";
import { Toaster } from "sonner";

/** Theme (no flash, follows OS) + motion (lazy, reduced-motion aware) + toasts. */
export function Providers({ children }: { children: ReactNode }) {
  return (
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
      <MotionConfig reducedMotion="user">
        <LazyMotion features={domAnimation} strict>
          {children}
          <Toaster position="bottom-right" toastOptions={{ className: "font-sans" }} />
        </LazyMotion>
      </MotionConfig>
    </ThemeProvider>
  );
}
