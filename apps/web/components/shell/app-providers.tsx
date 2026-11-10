"use client";

import { LazyMotion, MotionConfig, domAnimation } from "motion/react";
import type { ReactNode } from "react";
import { Toaster } from "sonner";

import { QueryProvider } from "./query-provider";

type AppProvidersProps = { children: ReactNode };

/**
 * Signed-in area only: server state (TanStack Query), motion (lazy, reduced-motion aware) and
 * token-styled toasts. Kept out of the root layout so the public pages don't ship them.
 */
export function AppProviders({ children }: AppProvidersProps) {
  return (
    <QueryProvider>
      <MotionConfig reducedMotion="user">
        <LazyMotion features={domAnimation} strict>
          {children}
          <Toaster
            position="bottom-right"
            toastOptions={{
              unstyled: true,
              classNames: {
                toast:
                  "flex w-full items-start gap-3 rounded-md border border-border-strong bg-surface p-4 font-sans text-body-sm text-foreground shadow-float",
                title: "font-medium",
                description: "text-muted-foreground",
                icon: "mt-0.5 [&_svg]:size-4",
                success: "[&_[data-icon]]:text-primary",
                error: "[&_[data-icon]]:text-danger",
              },
            }}
          />
        </LazyMotion>
      </MotionConfig>
    </QueryProvider>
  );
}
