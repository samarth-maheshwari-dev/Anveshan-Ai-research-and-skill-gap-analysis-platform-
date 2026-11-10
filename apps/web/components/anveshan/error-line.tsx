import { CircleAlert } from "lucide-react";

import { Button } from "@/components/ui/button";

type ErrorLineProps = { message: string; onRetry: () => void };

/** Inline load error (APPFLOW §2.3 error state): icon + plain words + one retry. */
export function ErrorLine({ message, onRetry }: ErrorLineProps) {
  return (
    <div
      role="alert"
      className="mt-6 flex items-center justify-between gap-3 rounded-md border border-danger/40 bg-danger-soft p-3 text-body-sm"
    >
      <span className="flex items-start gap-2">
        <CircleAlert aria-hidden className="mt-0.5 size-4 shrink-0 text-danger" />
        {message}
      </span>
      <Button variant="secondary" size="sm" onClick={onRetry}>
        Try again
      </Button>
    </div>
  );
}
