import { Geist, Geist_Mono } from "next/font/google";

// One grotesk family + its mono sibling (DESIGN §3.1). Self-hosted at build time by next/font:
// no runtime request to Google. `subsets` only decides what is preloaded: every other subset (e.g.
// latin-ext for names like "Šárka") is still declared and fetched on demand via unicode-range.
export const geistSans = Geist({ subsets: ["latin"], variable: "--font-geist-sans", display: "swap" });

export const geistMono = Geist_Mono({ subsets: ["latin"], variable: "--font-geist-mono", display: "swap" });
