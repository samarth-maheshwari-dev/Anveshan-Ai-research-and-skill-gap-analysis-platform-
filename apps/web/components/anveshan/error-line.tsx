import { Alert } from "@/components/anveshan/alert";
import { Button } from "@/components/ui/button";

type ErrorLineProps = { message: string; onRetry: () => void };

/** Inline load error (APPFLOW §2.3 error state): icon + plain words + one retry. */
export function ErrorLine({ message, onRetry }: ErrorLineProps) {
  return (
    <Alert
      className="mt-6"
      action={
        <Button variant="secondary" size="sm" onClick={onRetry}>
          Try again
        </Button>
      }
    >
      {message}
    </Alert>
  );
}
