import { Fraunces, Geist, Geist_Mono } from "next/font/google";

// Self-hosted at build time by next/font: no runtime request to Google (DESIGN §3.1).
export const fraunces = Fraunces({
  subsets: ["latin", "latin-ext"],
  axes: ["SOFT", "WONK", "opsz"],
  variable: "--font-fraunces",
  display: "swap",
});

export const geistSans = Geist({ subsets: ["latin", "latin-ext"], variable: "--font-geist-sans", display: "swap" });

export const geistMono = Geist_Mono({ subsets: ["latin"], variable: "--font-geist-mono", display: "swap" });
