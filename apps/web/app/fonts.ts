import { Fraunces, Geist, Geist_Mono } from "next/font/google";

// Geist for UI text, Fraunces (editorial serif) as the display face for headings. Self-hosted at build
// time by next/font — no runtime request to Google.
export const geistSans = Geist({ subsets: ["latin"], variable: "--font-geist-sans", display: "swap" });

export const geistMono = Geist_Mono({ subsets: ["latin"], variable: "--font-geist-mono", display: "swap" });

export const displayFace = Fraunces({ subsets: ["latin"], variable: "--font-display-face", display: "swap" });
