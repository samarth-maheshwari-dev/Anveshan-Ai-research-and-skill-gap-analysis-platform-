"use client";

import { ArrowLeft, ClipboardPaste, KeyRound } from "lucide-react";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input, Label, Select } from "@/components/ui/input";
import {
  useCreateConnection,
  useDeleteConnection,
  useLoadModels,
  useTestConnection,
  type ModelInfo,
  type TestResult,
} from "@/lib/api/hooks/connections";
import { describeTestError, preferredModel, type ProviderInfo, type WizardError } from "@/lib/providers";

import { PrivacyNote } from "./get-key-step";

type PasteStepProps = {
  provider: ProviderInfo;
  onBack: () => void;
  onVerified: (test: TestResult, model?: string) => void;
};

export function PasteStep({ provider, onBack, onVerified }: PasteStepProps) {
  const [key, setKey] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [connectionId, setConnectionId] = useState<string>();
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [model, setModel] = useState<string>();
  const [error, setError] = useState<WizardError | null>(null);
  const [busy, setBusy] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  // Bumped when the key box should get focus; the effect runs after React has re-enabled the input.
  const [focusRequest, setFocusRequest] = useState(0);
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
    const apiKey = rawKey.trim();
    if (!apiKey && !connectionId) {
      setError({ kind: "key", message: "Paste your key first." });
      setFocusRequest((n) => n + 1);
      return;
    }
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
      let chosen = model;
      if (!chosen) {
        const list = await loadModels.mutateAsync(id);
        setModels(list);
        chosen = preferredModel(provider.id, list);
        if (!chosen) {
          setError({ kind: "model", message: "Your key works, but no chat model was found. Pick one below." });
          return;
        }
      }
      const result = await test.mutateAsync({ id, model: chosen });
      onVerified(result, chosen);
    } catch (e) {
      const described = describeTestError(e, provider.name);
      setError(described);
      if (described.kind === "key" || described.kind === "url") {
        if (id) remove.mutate(id); // don't leave a broken connection behind
        setConnectionId(undefined);
        setModels([]);
        setModel(undefined);
        setKey("");
        setFocusRequest((n) => n + 1); // APPFLOW §4.3: input cleared and ready for the next paste
      }
      if (described.kind === "model" && id && models.length === 0) {
        loadModels.mutateAsync(id).then(setModels, () => undefined);
      }
    } finally {
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
      setFocusRequest((n) => n + 1);
      setError({ kind: "other", message: "Your browser blocked clipboard access. Press Ctrl+V in the box instead." });
    }
  }

  const describedBy = [helpId, error ? errorId : null].filter(Boolean).join(" ");

  return (
    <Card>
      <form onSubmit={onSubmit} noValidate>
        <h2 className="text-heading-md font-semibold">Paste your {provider.name} key</h2>
        {provider.needsBaseUrl && (
          <div className="mt-4 flex flex-col gap-2">
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
        <div className="mt-4 flex flex-col gap-2">
          <Label htmlFor={keyId}>API key</Label>
          <div className="flex gap-2">
            <Input
              id={keyId}
              ref={inputRef}
              type="password"
              autoComplete="off"
              spellCheck={false}
              className="font-mono"
              placeholder={connectionId ? "Key saved — pick a model below" : "Paste your key here"}
              value={key}
              // readOnly (not disabled) while testing, so focus can return here right after an error
              readOnly={busy}
              disabled={!!connectionId}
              aria-invalid={error?.kind === "key" || undefined}
              aria-describedby={describedBy}
              onChange={(e) => setKey(e.target.value)}
              onPaste={(e) => {
                const text = e.clipboardData.getData("text");
                if (text && !provider.needsBaseUrl) {
                  e.preventDefault();
                  setKey(text.trim());
                  void connect(text);
                }
              }}
            />
            <Button
              variant="ghost"
              onClick={() => void pasteFromClipboard()}
              disabled={!!connectionId || busy}
              aria-label="Paste from clipboard"
            >
              <ClipboardPaste aria-hidden /> <span className="hidden sm:inline">Paste</span>
            </Button>
          </div>
          <p id={helpId} className="text-body-sm text-muted-foreground">
            <KeyRound aria-hidden className="mr-1 inline size-3.5" />
            We test it right away with one tiny request.
          </p>
        </div>

        {error?.kind === "model" && models.length > 0 && (
          <div className="mt-4 flex flex-col gap-2">
            <Label htmlFor={`${keyId}-model`}>Model</Label>
            <Select id={`${keyId}-model`} value={model ?? ""} onChange={(e) => setModel(e.target.value || undefined)}>
              <option value="">Choose a model…</option>
              {models.map((mo) => (
                <option key={mo.id} value={mo.id}>
                  {mo.displayName ?? mo.id}
                  {mo.free ? " (free)" : ""}
                </option>
              ))}
            </Select>
          </div>
        )}

        {error && (
          <p id={errorId} role="alert" className="mt-4 rounded-md bg-danger-soft p-3 text-body-sm text-foreground">
            {error.message}
          </p>
        )}

        <PrivacyNote />

        <div className="mt-6 flex justify-between">
          <Button variant="ghost" onClick={onBack} disabled={busy}>
            <ArrowLeft aria-hidden /> Back
          </Button>
          <Button type="submit" variant="primary" loading={busy} disabled={error?.kind === "model" && !model}>
            {error?.kind === "network" ? "Try again" : connectionId ? "Test again" : "Connect"}
          </Button>
        </div>
      </form>
    </Card>
  );
}
