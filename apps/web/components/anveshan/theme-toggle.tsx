"use client";

import { Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";
import { useSyncExternalStore } from "react";

import { buttonVariants } from "@/components/ui/button";

const noop = () => () => {};

/** One-tap light/dark switch for the public pages (the signed-in area has the full choice in the user menu). */
export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  // The theme is only known in the browser; render a neutral icon on the server to avoid a hydration mismatch.
  const mounted = useSyncExternalStore(noop, () => true, () => false);
  const dark = !mounted || resolvedTheme === "dark";
  return (
    <button
      type="button"
      onClick={() => setTheme(dark ? "light" : "dark")}
      aria-label={dark ? "Switch to light theme" : "Switch to dark theme"}
      className={buttonVariants({ variant: "ghost", size: "icon" })}
    >
      {dark ? <Sun aria-hidden /> : <Moon aria-hidden />}
    </button>
  );
}
