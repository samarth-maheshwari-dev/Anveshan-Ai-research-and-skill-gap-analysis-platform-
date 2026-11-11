import type { Metadata } from "next";

import { Overline } from "@/components/ui/card";

export const metadata: Metadata = { title: "Report" };

/** S-08 Report: arrives with the writer (P14). Completed runs land here per APPFLOW §1.3. */
export default function ReportPage() {
  return (
    <div className="mx-auto max-w-3xl">
      <Overline>Report</Overline>
      <h1 className="mt-3 text-display-lg font-semibold">Research complete</h1>
      <p className="mt-2 text-body text-muted-foreground">
        The report view opens in a later update. Your research and its sources are saved.
      </p>
    </div>
  );
}
