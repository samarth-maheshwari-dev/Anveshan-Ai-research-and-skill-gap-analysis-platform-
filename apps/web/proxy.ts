import { NextResponse, type NextRequest } from "next/server";

/** App areas that need a session (APPFLOW §1.1). */
export const PROTECTED_PREFIXES = [
  "/dashboard",
  "/onboarding",
  "/settings",
  "/research",
  "/reports",
  "/sources",
  "/knowledge",
  "/graph",
  "/skills",
  "/w",
];

export function isProtected(pathname: string): boolean {
  return PROTECTED_PREFIXES.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}

/**
 * UX shortcut only (TECHSPEC §9.3): no session cookie → login; signed-in visitor on "/" → dashboard.
 * Real protection is the API (every call is authorized server-side; an expired session gets a 401).
 */
export function proxy(request: NextRequest) {
  const hasSession = request.cookies.has("ANV_SESSION");
  const { pathname } = request.nextUrl;

  if (pathname === "/" && hasSession) {
    return NextResponse.redirect(new URL("/dashboard", request.url));
  }
  if (!hasSession && isProtected(pathname)) {
    const login = new URL("/login", request.url);
    login.searchParams.set("next", pathname);
    return NextResponse.redirect(login);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/|favicon.ico|.*\\.(?:svg|png|ico|txt|webmanifest)$).*)"],
};
