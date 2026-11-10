"use client";

import { ThemeProvider } from "next-themes";
import type { ReactNode } from "react";

type ProvidersProps = { children: ReactNode };

/**
 * Theme: black-first — dark is the default for every visitor; Light and System stay one click away in
 * the user menu (DESIGN §2.3). Motion and toasts are only used in the signed-in area, so they load
 * there (components/shell/app-providers.tsx), not on the public pages.
 */
export function Providers({ children }: ProvidersProps) {
  return (
    <ThemeProvider attribute="class" defaultTheme="dark" enableSystem disableTransitionOnChange>
      {children}
    </ThemeProvider>
  );
}
