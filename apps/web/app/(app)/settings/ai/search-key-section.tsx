"use client";

import { ExternalLink } from "lucide-react";
import { useId, useState } from "react";
import { toast } from "sonner";

import { ErrorLine } from "@/components/anveshan/error-line";
import { Button } from "@/components/ui/button";
import { Card, CardTitle, Overline, Skeleton } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import { ApiError } from "@/lib/api/errors";
import { useConnections, useCreateConnection, useTestConnection } from "@/lib/api/hooks/connections";
import { describeTestError } from "@/lib/providers";

import { ConnectionRow } from "./connections-section";

const LABEL = "Tavily web search";

/**
 * Settings → AI: the optional web-search key (PRD Module A "optional search key", I-013). Without it research uses
 * the built-in SearXNG search; with it the WEB source type also asks Tavily. Stored like every key: encrypted, last 4.
 */
export function SearchKeySection() {
  const connections = useConnections();
  const create = useCreateConnection();
  const test = useTestConnection();
  const [key, setKey] = useState("");
  const [error, setError] = useState<string | null>(null);
  const keyId = useId();
  const tavily = (connections.data ?? []).find((c) => c.provider === "TAVILY");
  const busy = create.isPending || test.isPending;

  async function save() {
    const apiKey = key.trim();
    if (!apiKey) {
      setError("Paste your Tavily key first.");
      return;
    }
    setError(null);
    try {
      const c = await create.mutateAsync({ kind: "SEARCH", provider: "TAVILY", label: LABEL, apiKey });
      setKey("");
      try {
        await test.mutateAsync({ id: c.id! });
        toast.success(`${LABEL} works.`);
      } catch (e) {
        setError(describeTestError(e, "Tavily", "settings").message);
      }
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't save the key. Try again.");
    }
  }

  return (
    <Card data-testid="search-key-section">
      <Overline>Web search</Overline>
      <CardTitle className="mt-2">Web search key (optional)</CardTitle>
      <p className="mt-1 text-body-sm text-muted-foreground">
        Without a key, research searches the web with Anveshan&apos;s built-in free search. A Tavily key usually finds
        better web pages. Tavily has a free plan.
      </p>
      {connections.isPending ? (
        <Skeleton className="mt-6 h-14 w-full" />
      ) : connections.isError ? (
        <ErrorLine message="Couldn't load your keys." onRetry={() => void connections.refetch()} />
      ) : tavily ? (
        <ul className="mt-6 border-t border-border">
          <ConnectionRow connection={tavily} />
        </ul>
      ) : (
        <form
          className="mt-6 flex flex-col gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
        >
          <Label htmlFor={keyId}>Tavily API key</Label>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Input
              id={keyId}
              type="password"
              autoComplete="off"
              spellCheck={false}
              className="font-mono"
              placeholder="tvly-…"
              value={key}
              readOnly={busy}
              aria-invalid={!!error || undefined}
              aria-describedby={error ? `${keyId}-error` : undefined}
              onChange={(e) => setKey(e.target.value)}
            />
            <Button type="submit" variant="secondary" loading={busy}>
              Save and test
            </Button>
          </div>
          {error && (
            <p id={`${keyId}-error`} role="alert" className="text-body-sm text-danger">
              {error}
            </p>
          )}
          <a
            href="https://app.tavily.com/"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex w-fit items-center gap-1 text-body-sm text-primary underline underline-offset-4"
          >
            Get a Tavily key <ExternalLink aria-hidden className="size-3.5" />
          </a>
        </form>
      )}
    </Card>
  );
}
