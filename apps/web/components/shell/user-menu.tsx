"use client";

import { cn } from "@/lib/utils";
import { LogOut, Monitor, Moon, Settings, Sun } from "lucide-react";
import Link from "next/link";
import { useTheme } from "next-themes";
import { useEffect, useId, useRef, useState } from "react";

import { useLogout, type Me } from "@/lib/api/hooks/me";

const THEMES = [
  { id: "light", label: "Light", Icon: Sun },
  { id: "dark", label: "Dark", Icon: Moon },
  { id: "system", label: "System", Icon: Monitor },
] as const;

function initials(me: Me) {
  const source = me.name?.trim() || me.email || "?";
  return source
    .split(/[\s@._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((s) => s[0]!.toUpperCase())
    .join("");
}

/** User menu (APPFLOW §2.1): profile header, theme, settings, sign out. Esc and outside click close it. */
type UserMenuProps = { me: Me };

export function UserMenu({ me }: UserMenuProps) {
  const [open, setOpen] = useState(false);
  const { theme, setTheme } = useTheme();
  const logout = useLogout();
  const ref = useRef<HTMLDivElement>(null);
  const menuId = useId();

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    const onClick = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false);
    document.addEventListener("keydown", onKey);
    document.addEventListener("mousedown", onClick);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("mousedown", onClick);
    };
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={menuId}
        aria-label="Account menu"
        onClick={() => setOpen((o) => !o)}
        className="flex size-10 items-center justify-center rounded-full border border-border-strong bg-surface font-mono text-caption font-medium text-foreground hover:border-foreground max-sm:size-11"
      >
        {initials(me)}
      </button>
      {open && (
        <div
          id={menuId}
          role="menu"
          className="absolute right-0 top-12 z-40 w-64 rounded-lg border border-border-strong bg-popover p-2 shadow-float"
        >
          <div className="border-b border-border px-2 pb-2">
            <p className="truncate text-body-sm font-medium">{me.name ?? "Signed in"}</p>
            <p className="truncate text-caption text-muted-foreground">{me.email}</p>
          </div>
          <p className="px-2 pt-3 font-mono text-micro uppercase text-muted-foreground">Theme</p>
          <div className="flex gap-1 p-2" role="group" aria-label="Theme">
            {THEMES.map(({ id, label, Icon }) => (
              <button
                key={id}
                type="button"
                role="menuitemradio"
                aria-checked={theme === id}
                onClick={() => setTheme(id)}
                className={cn(
                  "flex flex-1 flex-col items-center gap-1 rounded-md border px-2 py-2 text-caption",
                  theme === id
                    ? "border-foreground bg-surface-2 font-medium text-foreground"
                    : "border-transparent text-muted-foreground hover:bg-surface-2 hover:text-foreground",
                )}
              >
                <Icon aria-hidden className="size-4" />
                {label}
              </button>
            ))}
          </div>
          <Link
            role="menuitem"
            href="/settings/ai"
            onClick={() => setOpen(false)}
            className="flex h-10 items-center gap-2 rounded-md px-2 text-body-sm hover:bg-surface-2 max-sm:h-11"
          >
            <Settings aria-hidden className="size-4" /> Settings
          </Link>
          <button
            type="button"
            role="menuitem"
            onClick={() => logout.mutate()}
            disabled={logout.isPending}
            className="flex h-10 w-full items-center gap-2 rounded-md px-2 text-body-sm hover:bg-surface-2 max-sm:h-11"
          >
            <LogOut aria-hidden className="size-4" /> Sign out
          </button>
        </div>
      )}
    </div>
  );
}
