"use client";

import { ArrowLeft, History, X } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";

import { Alert } from "@/components/anveshan/alert";
import { ErrorLine } from "@/components/anveshan/error-line";
import { EvidenceBar } from "@/components/anveshan/evidence-bar";
import { CitationPanel } from "@/components/report/citation-panel";
import { ReportContext } from "@/components/report/report-context";
import { SectionEditor } from "@/components/report/section-editor";
import { TipTapView } from "@/components/report/tiptap-view";
import { VersionsPanel } from "@/components/report/versions-panel";
import { Button, buttonVariants } from "@/components/ui/button";
import { Overline, Skeleton } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import { useReport } from "@/lib/api/hooks/reports";
import { runHref, useRun } from "@/lib/api/hooks/runs";
import { GENERATED_SECTIONS, countLabels } from "@/lib/report";
import { cn } from "@/lib/utils";

type Aside = { kind: "citation"; claimId: string } | { kind: "versions" } | null;

/**
 * S-08 Report Studio (Module F): evidence summary first, then the sections — editable with citation atoms, except the
 * Conflicts and References that code builds — and a side panel with the exact passage behind a citation or the
 * version history.
 */
export function ReportView() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const run = useRun(id);
  const reportId = run.data?.reportId;
  const report = useReport(reportId);
  const status = run.data?.status;
  const [aside, setAside] = useState<Aside>(null);
  const [epoch, setEpoch] = useState(0);
  const [refreshes, setRefreshes] = useState<Record<string, { runId: string; before: string | undefined }>>({});

  useEffect(() => {
    if (status && status !== "COMPLETED") router.replace(runHref(id, status));
  }, [status, id, router]);

  useEffect(() => {
    if (!aside) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setAside(null);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [aside]);

  const openCitation = useCallback((claimId: string) => setAside({ kind: "citation", claimId }), []);
  const r = report.data;
  const context = useMemo(
    () => ({
      claims: r?.claims ?? {},
      sources: r?.sources ?? {},
      activeClaimId: aside?.kind === "citation" ? aside.claimId : null,
      openCitation,
    }),
    [r?.claims, r?.sources, aside, openCitation],
  );

  if (run.isError) {
    if (run.error instanceof ApiError && run.error.status === 404) {
      return <Alert className="mx-auto max-w-3xl">This research doesn&apos;t exist or isn&apos;t yours.</Alert>;
    }
    return <ErrorLine message="Couldn't load this research." onRetry={() => void run.refetch()} />;
  }
  if (run.isPending || (reportId && report.isPending)) {
    return (
      <div className="mx-auto max-w-3xl space-y-4" aria-busy="true">
        <Skeleton className="h-10 w-2/3" />
        <Skeleton className="h-40" />
        <Skeleton className="h-40" />
      </div>
    );
  }
  if (status === "COMPLETED" && !reportId) {
    return <Alert className="mx-auto max-w-3xl">This research finished without a report.</Alert>;
  }
  if (report.isError || !r || !reportId) {
    return <ErrorLine message="Couldn't load the report." onRetry={() => void report.refetch()} />;
  }

  const claims = r.claims ?? {};
  const sources = r.sources ?? {};
  const sections = r.sections ?? [];
  const counts = countLabels(Object.values(claims).map((c) => c.trustLabel));

  return (
    <ReportContext.Provider value={context}>
      <Link href="/dashboard" className={`${buttonVariants({ variant: "ghost", size: "sm" })} -ml-3`}>
        <ArrowLeft aria-hidden /> Dashboard
      </Link>
      <div className="mt-4 flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <Overline>Research report</Overline>
          <h1 className="mt-2 text-display-lg font-semibold">{r.title}</h1>
          <p className="mt-2 text-body-sm text-muted-foreground">
            {r.createdAt ? new Date(r.createdAt).toLocaleDateString(undefined, { dateStyle: "long" }) : ""} ·{" "}
            {Object.keys(sources).length} sources · {Object.keys(claims).length} cited claims · {r.citationStyle} references
          </p>
        </div>
        <Button
          size="sm"
          variant={aside?.kind === "versions" ? "primary" : "secondary"}
          aria-expanded={aside?.kind === "versions"}
          onClick={() => setAside(aside?.kind === "versions" ? null : { kind: "versions" })}
        >
          <History aria-hidden /> Versions
        </Button>
      </div>

      <EvidenceBar className="mt-6" counts={counts} />
      <p className="mt-2 text-caption text-subtle-foreground">
        Click a citation to see the exact passage it comes from. Select text to simplify, expand or translate it.
      </p>

      <div className={cn("mt-8 xl:grid xl:gap-8", aside ? "xl:grid-cols-[minmax(0,1fr)_22rem]" : "xl:grid-cols-[12rem_minmax(0,1fr)]")}>
        {!aside && (
          <nav aria-label="Sections" className="hidden xl:block">
            <ol className="sticky top-4 space-y-1 border-l border-border text-body-sm">
              {sections.map((s) => (
                <li key={s.id}>
                  <a href={`#section-${s.id}`} className="-ml-px block border-l border-transparent py-1 pl-3 text-muted-foreground hover:border-foreground hover:text-foreground">
                    {s.title}
                  </a>
                </li>
              ))}
            </ol>
          </nav>
        )}

        <article className="min-w-0 space-y-12">
          {sections.map((s) =>
            GENERATED_SECTIONS.has(s.key ?? "") ? (
              <section key={s.id} id={`section-${s.id}`} aria-labelledby={`sec-${s.id}`} className="scroll-mt-6" data-section-key={s.key}>
                <div className="flex items-end justify-between gap-2 border-b border-border pb-2">
                  <h2 id={`sec-${s.id}`} className="text-heading-lg font-semibold">
                    {s.title}
                  </h2>
                  <span className="font-mono text-micro text-subtle-foreground">Built from the sources</span>
                </div>
                <div className={s.key === "references" ? "mt-4 text-body-sm text-muted-foreground" : "mt-4"}>
                  <TipTapView doc={s.contentDoc} />
                </div>
              </section>
            ) : (
              <SectionEditor key={`${s.id}:${epoch}`} reportId={reportId} section={s} />
            ),
          )}
          <p className="border-t border-border pt-4 text-caption text-subtle-foreground">
            AI-assisted research draft. Verify before submitting it anywhere.
          </p>
        </article>

        {aside && (
          <aside
            aria-label={aside.kind === "citation" ? "Citation source" : "Versions"}
            className="fixed inset-0 z-40 overflow-y-auto bg-background p-4 xl:static xl:z-auto xl:p-0"
          >
            <div className="xl:sticky xl:top-4 xl:max-h-screen xl:overflow-y-auto xl:border-l xl:border-border xl:pl-6">
              <div className="mb-4 flex items-center justify-between">
                <p className="font-mono text-micro uppercase text-subtle-foreground">{aside.kind === "citation" ? "Source" : "Versions"}</p>
                <Button size="icon" variant="ghost" aria-label="Close panel" onClick={() => setAside(null)}>
                  <X aria-hidden />
                </Button>
              </div>
              {aside.kind === "citation" ? (
                <CitationPanel
                  key={aside.claimId}
                  reportId={reportId}
                  claimId={aside.claimId}
                  refreshes={refreshes}
                  onRefreshStarted={(claimId, runId, before) => setRefreshes((m) => ({ ...m, [claimId]: { runId, before } }))}
                />
              ) : (
                <VersionsPanel reportId={reportId} onRestored={() => setEpoch((e) => e + 1)} />
              )}
            </div>
          </aside>
        )}
      </div>
    </ReportContext.Provider>
  );
}
