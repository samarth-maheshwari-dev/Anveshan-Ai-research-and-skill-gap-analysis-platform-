/** Backend origin, e.g. http://localhost:8080 (browser-side; baked in at build time). */
export const API_URL = (process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080").replace(/\/$/, "");

/** Full-page navigation targets for OAuth (TECHSPEC §9.4) — never fetch(). */
export function loginUrl(provider: "google" | "github", next?: string): string {
  const q = next ? `?next=${encodeURIComponent(next)}` : "";
  return `${API_URL}/oauth2/authorization/${provider}${q}`;
}
