import type { Metadata, Viewport } from "next";

import { Providers } from "@/components/providers";

import { geistMono, geistSans } from "./fonts";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "Anveshan", template: "%s · Anveshan" },
  description: "Research you can defend. Skills you can prove.",
};

// Browser chrome color = --background of each theme (globals.css). Dark is the default theme.
export const viewport: Viewport = {
  // Dark is the default theme whatever the OS prefers (DESIGN §2.1), so the browser bar is always black.
  themeColor: "#0A0A0A",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" suppressHydrationWarning className={`${geistSans.variable} ${geistMono.variable} h-full`}>
      <body className="min-h-full">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
