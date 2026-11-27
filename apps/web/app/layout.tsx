import type { Metadata, Viewport } from "next";

import { Providers } from "@/components/providers";

import { displayFace, geistMono, geistSans } from "./fonts";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "Anveshan", template: "%s · Anveshan" },
  description: "Ask any question. Get a clear report with proof for every line.",
};

// Browser chrome color = --background of each theme (globals.css). Dark is the default theme.
export const viewport: Viewport = {
  // Light is the default theme, so the browser bar matches it.
  themeColor: "#0A0A0A",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" suppressHydrationWarning className={`${geistSans.variable} ${geistMono.variable} ${displayFace.variable} h-full`}>
      {/* Browser extensions (e.g. Kapture adds "kapture-loaded") change <body> attributes before React
          hydrates. This only silences attribute mismatches on <body> itself, not on its children. */}
      <body className="min-h-full" suppressHydrationWarning>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
