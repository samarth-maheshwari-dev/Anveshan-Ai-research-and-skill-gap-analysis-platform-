"use client";

import { ArrowLeft, Info } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";

import { Alert } from "@/components/anveshan/alert";
import { ErrorLine } from "@/components/anveshan/error-line";
import { TRUST, TrustLabel, type TrustLabelValue } from "@/components/anveshan/trust-label";
import { TipTapView } from "@/components/report/tiptap-view";
import { buttonVariants } from "@/components/ui/button";
import { Overline, Skeleton } from "@/components/ui/card";
import { ApiError } from "@/lib/api/errors";
import { useReport } from "@/lib/api/hooks/reports";
import { runHref, useRun } from "@/lib/api/hooks/runs";

/**
 * S-08 Report — read-only for now (P14): sections with numbered citations, trust labels, conflicts and references.
 * Editing, versions, clicking a citation to see the exact passage, and export come with the Report Studio (P15–P16).
 */
export function ReportView() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const run = useRun(id);
  const reportId = run.data?.reportId;
  const report = useReport(reportId);
  const status = run.data?.status;

  useEffect(() => {
    if (status && status !== "COMPLETED") router.replace(runHref(id, status));
  }, [status, id, router]);

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
  if (report.isError || !report.data) {
    return <ErrorLine message="Couldn't load the report." onRetry={() => void report.refetch()} />;
  }

  const r = report.data;
  const claims = r.claims ?? {};
  const sources = r.sources ?? {};
  const counts = countLabels(Object.values(claims).map((c) => c.trustLabel));
  return (
    <article className="mx-auto max-w-3xl">
      <Link href="/dashboard" className={`${buttonVariants({ variant: "ghost", size: "sm" })} -ml-3`}>
        <ArrowLeft aria-hidden /> Dashboard
      </Link>
      <Overline className="mt-4">Research report</Overline>
      <h1 className="mt-2 text-display-lg font-semibold">{r.title}</h1>
      <p className="mt-2 text-body-sm text-muted-foreground">
        {r.createdAt ? new Date(r.createdAt).toLocaleDateString(undefined, { dateStyle: "long" }) : ""} ·{" "}
        {Object.keys(sources).length} sources · {Object.keys(claims).length} cited claims · {r.citationStyle} references
      </p>

      <section aria-label="Evidence summary" className="mt-6 rounded-lg border border-border bg-surface p-4">
        <div className="flex flex-wrap gap-2">
          {(Object.keys(TRUST) as TrustLabelValue[])
            .filter((l) => counts[l] > 0)
            .map((l) => (
              <span key={l} className="inline-flex items-center gap-1.5 text-body-sm">
                <TrustLabel label={l} /> <span className="tabular text-muted-foreground">× {counts[l]}</span>
              </span>
            ))}
        </div>
        <p className="mt-3 flex items-start gap-2 text-caption text-subtle-foreground">
          <Info aria-hidden className="mt-0.5 size-3.5 shrink-0" />
          Every numbered citation points to a claim checked against its source. Hover a citation to see the claim;
          a warning icon marks weak evidence. Opening the exact passage arrives with the Report Studio.
        </p>
      </section>

      {(r.sections ?? []).map((s) => (
        <section key={s.id} aria-labelledby={`sec-${s.id}`} className="mt-10">
          <h2 id={`sec-${s.id}`} className="border-b border-border pb-2 text-heading-lg font-semibold">
            {s.title}
          </h2>
          <div className={s.key === "references" ? "mt-4 text-body-sm text-muted-foreground" : "mt-4"}>
            <TipTapView doc={s.contentDoc} claims={claims} sources={sources} />
          </div>
        </section>
      ))}
      <p className="mt-12 border-t border-border pt-4 text-caption text-subtle-foreground">
        AI-assisted research draft. Verify before submitting it anywhere.
      </p>
    </article>
  );
}

function countLabels(labels: (string | undefined)[]): Record<TrustLabelValue, number> {
  const c: Record<TrustLabelValue, number> = { SUPPORTED: 0, WEAK: 0, CONFLICTING: 0, NO_SOURCE: 0 };
  for (const l of labels) if (l && l in c) c[l as TrustLabelValue]++;
  return c;
}
