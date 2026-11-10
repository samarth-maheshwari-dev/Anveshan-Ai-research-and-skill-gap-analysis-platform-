import { VerifiedStamp } from "@/components/anveshan/verified-stamp";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import type { TestResult } from "@/lib/api/hooks/connections";
import type { ProviderInfo } from "@/lib/providers";

type DoneStepProps = {
  provider: ProviderInfo;
  result: TestResult;
  fromSettings: boolean;
  onAddAnother: () => void;
  onFinish: () => void;
};

export function DoneStep({ provider, result, fromSettings, onAddAnother, onFinish }: DoneStepProps) {
  return (
    <Card className="p-6 md:p-8">
      <VerifiedStamp />
      <div role="status">
        <h2 className="mt-5 text-heading-lg font-semibold">Connected. {provider.name} is ready.</h2>
      </div>
      {result.note && <p className="mt-2 text-body-sm text-muted-foreground">{result.note}.</p>}
      {result.sampleModel && (
        <dl className="mt-6 border-y border-border">
          <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1 py-3">
            <dt className="font-mono text-micro uppercase text-muted-foreground">Checked with</dt>
            <dd className="font-mono text-body-sm text-foreground">{result.sampleModel}</dd>
          </div>
        </dl>
      )}
      {result.defaultSlotsSet && result.sampleModel && (
        <p className="mt-3 text-body-sm text-muted-foreground">
          All six tasks will use this model. You can change it any time in Settings → AI.
        </p>
      )}
      <p className="mt-6 border-l-2 border-border-strong pl-3 text-body-sm text-muted-foreground">
        Tip: add a second free provider (for example Groq) for more daily capacity — when one hits its limit,
        Anveshan switches to the other.
      </p>
      <div className="mt-8 flex flex-wrap justify-end gap-3">
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
