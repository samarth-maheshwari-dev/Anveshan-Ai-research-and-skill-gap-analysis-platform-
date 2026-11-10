import { VerifiedStamp } from "@/components/anveshan/verified-stamp";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import type { TestResult } from "@/lib/api/hooks/connections";
import type { ProviderInfo } from "@/lib/providers";

type DoneStepProps = {
  provider: ProviderInfo;
  result: { test: TestResult; model?: string };
  fromSettings: boolean;
  onAddAnother: () => void;
  onFinish: () => void;
};

export function DoneStep({ provider, result, fromSettings, onAddAnother, onFinish }: DoneStepProps) {
  return (
    <Card className="text-center">
      <div className="flex justify-center py-2">
        <VerifiedStamp />
      </div>
      <div role="status">
        <h2 className="mt-4 text-heading-lg font-semibold">Connected. {provider.name} is ready.</h2>
      </div>
      {result.test.note && <p className="mt-2 text-body-sm text-muted-foreground">{result.test.note}.</p>}
      {result.test.defaultSlotsSet && result.model && (
        <p className="mt-2 text-body-sm text-muted-foreground">
          All six tasks will use <span className="font-mono text-foreground">{result.model}</span>. You can change this
          in Settings → AI.
        </p>
      )}
      <p className="mt-6 rounded-md bg-surface-2 p-3 text-body-sm text-muted-foreground">
        Tip: add a second free provider (for example Groq) to get more daily capacity — when one hits its limit,
        Anveshan switches to the other.
      </p>
      <div className="mt-6 flex flex-wrap justify-center gap-3">
        <Button variant="secondary" onClick={onAddAnother}>
          Add another
        </Button>
        <Button variant="primary" onClick={onFinish}>
          {fromSettings ? "Back to settings" : "Start researching"}
        </Button>
      </div>
    </Card>
  );
}
