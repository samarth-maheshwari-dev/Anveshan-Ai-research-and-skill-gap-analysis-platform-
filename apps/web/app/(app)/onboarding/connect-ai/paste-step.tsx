"use client";

import { ArrowLeft, CircleAlert, ClipboardPaste } from "lucide-react";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input, Label } from "@/components/ui/input";
import {
  useCreateConnection,
  useDeleteConnection,
  useLoadModels,
  useTestConnection,
  type ModelInfo,
  type TestResult,
} from "@/lib/api/hooks/connections";
import { describeTestError, type ProviderInfo, type WizardError } from "@/lib/providers";
import { cn } from "@/lib/utils";

import { PrivacyNote } from "./get-key-step";
import { ModelField } from "./model-field";

type PasteStepProps = {
  provider: ProviderInfo;
  onBack: () => void;
  onVerified: (test: TestResult) => void;
};

/**
 * Paste & test. The backend picks and checks the model (the one already routed → a known-good one →
 * the provider's ranked list, with fallback). A model is only sent when the user picked it from the
 * list, which appears only when the backend reports that no model worked.
 */
export function PasteStep({ provider, onBack, onVerified }: PasteStepProps) {
  const [key, setKey] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [connectionId, setConnectionId] = useState<string>();
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [model, setModel] = useState<string>();
  // True once the model list was requested after a model error — even if it failed or came back empty.
  const [modelsLoaded, setModelsLoaded] = useState(false);
  const [error, setError] = useState<WizardError | null>(null);
  const [busy, setBusy] = useState(false);
  // Guards against a second connect (e.g. a quick double paste) before `busy` has re-rendered.
  const inFlight = useRef(false);
  // The submit label shown while busy: whatever it said when the user pressed it.
  const [busyLabel, setBusyLabel] = useState("Connect");
  const idleLabel = error?.kind === "network" ? "Try again" : connectionId ? "Test again" : "Connect";
  const inputRef = useRef<HTMLInputElement>(null);
  // Bumped when the key box should get focus; the effect runs after React has re-enabled the input.
  const [focusRequest, setFocusRequest] = useState(0);
  const requestFocus = () => setFocusRequest((n) => n + 1);
  useEffect(() => {
    if (focusRequest > 0) inputRef.current?.focus();
  }, [focusRequest]);
  const keyId = useId();
  const helpId = useId();
  const errorId = useId();

  const create = useCreateConnection();
  const loadModels = useLoadModels();
  const test = useTestConnection();
  const remove = useDeleteConnection();

  async function connect(rawKey: string) {
    if (inFlight.current) return; // one test at a time: never two connections from one key
    const apiKey = rawKey.trim();
    if (!apiKey && !connectionId) {
      setError({ kind: "key", message: "Paste your key first." });
      requestFocus();
      return;
    }
    inFlight.current = true;
    setBusyLabel(idleLabel);
    setBusy(true);
    setError(null);
    let id = connectionId;
    try {
      if (!id) {
        const created = await create.mutateAsync({
          kind: "LLM",
          provider: provider.id,
          label: `My ${provider.name}`,
          apiKey,
          baseUrl: provider.needsBaseUrl ? baseUrl.trim() : undefined,
        });
        id = created.id!;
        setConnectionId(id);
      }
      onVerified(await test.mutateAsync({ id, model }));
    } catch (e) {
      // The message must match what's on screen: the model list, or the typed field when the provider
      // gave no list. So on the first model error the list is loaded (once) before the alert is shown.
      const typedField = modelsLoaded && models.length === 0;
      let described = describeTestError(e, provider.name, typedField ? "wizard-typed" : "wizard");
      if (described.kind === "model" && id && !modelsLoaded) {
        let chat: ModelInfo[] = [];
        try {
          chat = (await loadModels.mutateAsync(id)).filter((m) => m.chat !== false);
        } catch {
          // no list (a custom endpoint may not have one): the user types the model id instead
        }
        setModels(chat);
        setModelsLoaded(true);
        if (chat.length === 0) described = describeTestError(e, provider.name, "wizard-typed");
      }
      setError(described);
      if (described.kind === "key" || described.kind === "url") {
        if (id) remove.mutate(id); // don't leave a broken connection behind
        setConnectionId(undefined);
        setModels([]);
        setModelsLoaded(false);
        setModel(undefined);
        setKey("");
        requestFocus(); // APPFLOW §4.3: input cleared and ready for the next paste
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    void connect(key);
  }

  async function pasteFromClipboard() {
    try {
      const text = await navigator.clipboard.readText();
      setKey(text.trim());
      if (!provider.needsBaseUrl) void connect(text);
    } catch {
      requestFocus();
      setError({ kind: "other", message: "Your browser blocked clipboard access. Press Ctrl+V in the box instead." });
    }
  }

  const describedBy = [helpId, error ? errorId : null].filter(Boolean).join(" ");
  // Once a model error loaded the list (or found none), the model field stays — through "Test again"
  // and through a network error — so the user always sees which model will be sent.
  const picking = !!connectionId && modelsLoaded;
  // "Your key works, but…" isn't a failure of the key: shown neutral, not red.
  const keyWorks = error?.kind === "model";

  return (
    <Card className="p-6 md:p-8">
      <form onSubmit={onSubmit} noValidate>
        <h2 className="text-heading-md font-semibold">Paste your {provider.name} key</h2>
        {provider.needsBaseUrl && (
          <div className="mt-5 flex flex-col gap-2">
            <Label htmlFor={`${keyId}-url`}>Base URL</Label>
            <Input
              id={`${keyId}-url`}
              type="url"
              inputMode="url"
              placeholder="https://api.example.com/v1/"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              aria-invalid={error?.kind === "url" || undefined}
              disabled={!!connectionId}
            />
          </div>
        )}
        <div className="mt-5 flex flex-col gap-2">
          <Label htmlFor={keyId}>API key</Label>
          <div className="flex gap-2">
            <Input
              id={keyId}
              ref={inputRef}
              type="password"
              autoComplete="off"
              spellCheck={false}
              className="font-mono"
              placeholder={connectionId ? "Key saved — choose a model" : "Paste your key here"}
              value={key}
              // readOnly (not disabled) while testing, so focus can return here right after an error
              readOnly={busy}
              disabled={!!connectionId}
              aria-invalid={error?.kind === "key" || undefined}
              aria-describedby={describedBy}
              onChange={(e) => setKey(e.target.value)}
              onPaste={(e) => {
                const text = e.clipboardData.getData("text");
                if (inFlight.current) {
                  e.preventDefault(); // a test is running: ignore the paste, don't start another
                  return;
                }
                if (text && !provider.needsBaseUrl) {
                  e.preventDefault();
                  setKey(text.trim());
                  void connect(text);
                }
              }}
            />
            <Button
              variant="secondary"
              onClick={() => void pasteFromClipboard()}
              disabled={!!connectionId || busy}
              aria-label="Paste from clipboard"
            >
              <ClipboardPaste aria-hidden /> <span className="hidden sm:inline">Paste</span>
            </Button>
          </div>
          <p id={helpId} className="text-body-sm text-muted-foreground">
            We check it right away with a short test message.
          </p>
        </div>

        {picking && (
          <ModelField
            id={`${keyId}-model`}
            models={models}
            model={model}
            onChange={setModel}
            providerName={provider.name}
            errorId={errorId}
            busy={busy}
          />
        )}

        {error && (
          <p
            id={errorId}
            role="alert"
            className={cn(
              "mt-5 flex items-start gap-2 rounded-md border p-3 text-body-sm text-foreground",
              keyWorks ? "border-border-strong bg-surface-2" : "border-danger/40 bg-danger-soft",
            )}
          >
            <CircleAlert aria-hidden className={cn("mt-0.5 size-4 shrink-0", keyWorks ? "text-foreground" : "text-danger")} />
            {error.message}
          </p>
        )}

        <PrivacyNote />

        <div className="mt-8 flex justify-between gap-3">
          <Button variant="ghost" onClick={onBack} disabled={busy}>
            <ArrowLeft aria-hidden /> Back
          </Button>
          <Button type="submit" variant="primary" loading={busy} disabled={picking && !model}>
            {busy ? busyLabel : idleLabel}
          </Button>
        </div>
      </form>
    </Card>
  );
}
