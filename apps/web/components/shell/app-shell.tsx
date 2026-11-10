"use client";

import { cn } from "@/lib/utils";
import {
  BookOpen,
  FlaskConical,
  LayoutDashboard,
  Library,
  Menu,
  Network,
  Settings,
  Trophy,
  X,
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState, type ReactNode } from "react";

import { Banner, pickBanner } from "@/components/anveshan/banner";
import { Logo } from "@/components/anveshan/logo";
import { Button } from "@/components/ui/button";
import { Card, Skeleton } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import { useConnections } from "@/lib/api/hooks/connections";
import { useMe, type Me } from "@/lib/api/hooks/me";

import { AppProviders } from "./app-providers";
import { UserMenu } from "./user-menu";

type NavItem = { href: string; label: string; Icon: typeof LayoutDashboard; soon?: boolean };

const NAV: NavItem[] = [
  { href: "/dashboard", label: "Dashboard", Icon: LayoutDashboard },
  { href: "/research/new", label: "New research", Icon: FlaskConical, soon: true },
  { href: "/sources", label: "Sources", Icon: Library, soon: true },
  { href: "/knowledge", label: "Knowledge", Icon: BookOpen, soon: true },
  { href: "/graph", label: "Graph", Icon: Network, soon: true },
  { href: "/skills", label: "Skills", Icon: Trophy, soon: true },
];

/** Signed-in area: QueryClient, motion, toasts + AuthGate + shell (TECHSPEC §9.1). */
type ChildrenProps = { children: ReactNode };

export function AppShell({ children }: ChildrenProps) {
  return (
    <AppProviders>
      <AuthGate>{children}</AuthGate>
    </AppProviders>
  );
}

/** Calls GET /me once (this also sets the XSRF-TOKEN cookie). 401 → the API client redirects to login. */
function AuthGate({ children }: ChildrenProps) {
  const me = useMe();
  if (me.isPending || (me.error instanceof ApiError && me.error.status === 401)) {
    return <ShellSkeleton />;
  }
  if (me.isError) {
    return (
      <main className="mx-auto flex min-h-screen max-w-md items-center px-4">
        <Card className="w-full text-center">
          <h1 className="text-heading-lg font-semibold">We couldn&apos;t load your account</h1>
          <p className="mt-2 text-body-sm text-muted-foreground">
            Anveshan might be restarting. Check your connection and try again.
          </p>
          <Button variant="primary" className="mt-6" onClick={() => void me.refetch()}>
            Try again
          </Button>
        </Card>
      </main>
    );
  }
  return <Shell me={me.data}>{children}</Shell>;
}

type ShellProps = { me: Me; children: ReactNode };

function Shell({ me, children }: ShellProps) {
  const pathname = usePathname();
  // The mobile menu belongs to the page it was opened on: navigating closes it, no effect needed.
  const [navOpenOn, setNavOpenOn] = useState<string | null>(null);
  const navOpen = navOpenOn === pathname;
  const setNavOpen = (open: boolean) => setNavOpenOn(open ? pathname : null);
  const connections = useConnections();
  const onboarding = pathname.startsWith("/onboarding");
  const banner = onboarding ? null : pickBanner(me.onboarding?.hasVerifiedConnection, connections.data);

  return (
    <div className="flex min-h-screen flex-col">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-2 focus:z-80 focus:rounded-md focus:bg-surface focus:px-3 focus:py-2"
      >
        Skip to content
      </a>
      <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-border bg-background px-4 md:px-6">
        <Button
          variant="ghost"
          size="icon"
          className="lg:hidden"
          aria-label={navOpen ? "Close menu" : "Open menu"}
          aria-expanded={navOpen}
          aria-controls="app-nav"
          onClick={() => setNavOpen(!navOpen)}
        >
          {navOpen ? <X aria-hidden /> : <Menu aria-hidden />}
        </Button>
        <Link href="/dashboard" aria-label="Anveshan home" className="inline-flex min-h-11 items-center rounded-md">
          <Logo />
        </Link>
        <div className="flex-1" />
        <UserMenu me={me} />
      </header>
      {banner && <Banner banner={banner} />}
      <div className="flex flex-1">
        <nav
          id="app-nav"
          aria-label="Main"
          className={cn(
            "z-20 w-62 shrink-0 border-r border-border bg-background px-3 py-4",
            "fixed inset-y-14 left-0 lg:static lg:block",
            navOpen ? "block" : "hidden",
          )}
        >
          <p className="px-3 pb-2 font-mono text-micro uppercase text-subtle-foreground">Workspace</p>
          <ul className="flex flex-col gap-0.5">
            {NAV.map((item) => (
              <li key={item.href}>
                <NavLink item={item} active={pathname === item.href || pathname.startsWith(`${item.href}/`)} />
              </li>
            ))}
            <li className="my-3 border-t border-border" aria-hidden />
            <li>
              <NavLink
                item={{ href: "/settings/ai", label: "Settings", Icon: Settings }}
                active={pathname.startsWith("/settings")}
              />
            </li>
          </ul>
        </nav>
        <main id="main" tabIndex={-1} className="min-w-0 flex-1 px-4 py-8 md:px-8 lg:px-12 lg:py-12">
          <div className="mx-auto max-w-6xl">{children}</div>
        </main>
      </div>
    </div>
  );
}

type NavLinkProps = { item: NavItem; active: boolean };

/** Active = white text + a 2px green bar (the accent marks *where you are*, nothing else). */
function NavLink({ item, active }: NavLinkProps) {
  const base = "relative flex h-10 items-center gap-3 rounded-md px-3 text-body-sm max-sm:h-11";
  if (item.soon) {
    return (
      <span aria-disabled="true" className={cn(base, "cursor-not-allowed text-subtle-foreground")}>
        <item.Icon aria-hidden className="size-4" />
        <span className="flex-1">{item.label}</span>
        <span className="font-mono text-micro uppercase">Soon</span>
      </span>
    );
  }
  return (
    <Link
      href={item.href}
      aria-current={active ? "page" : undefined}
      className={cn(
        base,
        active ? "bg-surface-2 font-medium text-foreground" : "text-muted-foreground hover:bg-surface hover:text-foreground",
      )}
    >
      {active && <span aria-hidden className="absolute inset-y-2 left-0 w-0.5 bg-primary" />}
      <item.Icon aria-hidden className="size-4" />
      {item.label}
    </Link>
  );
}

function ShellSkeleton() {
  return (
    <div className="flex min-h-screen flex-col" aria-busy="true" aria-label="Loading">
      <div className="flex h-14 items-center gap-3 border-b border-border bg-background px-4">
        <Skeleton className="h-7 w-32" />
        <div className="flex-1" />
        <Skeleton className="size-10 rounded-full" />
      </div>
      <div className="mx-auto w-full max-w-6xl space-y-4 px-4 py-12">
        <Skeleton className="h-10 w-72" />
        <Skeleton className="h-40 w-full" />
      </div>
    </div>
  );
}
