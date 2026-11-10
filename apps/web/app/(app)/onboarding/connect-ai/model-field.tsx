"use client";

import { Input, Label, Select } from "@/components/ui/input";
import type { ModelInfo } from "@/lib/api/hooks/connections";

type ModelFieldProps = {
  id: string;
  /** Chat models from the provider; empty = it gave no list, so the user types the model id. */
  models: ModelInfo[];
  model: string | undefined;
  onChange: (model: string | undefined) => void;
  providerName: string;
  /** The error above, so a screen reader hears why the field appeared. */
  errorId: string;
  /** A test is running: the model it uses can't change under it. */
  busy: boolean;
};

/** Shown after a model error: a picker when the provider lists chat models, else a typed field. */
export function ModelField({ id, models, model, onChange, providerName, errorId, busy }: ModelFieldProps) {
  if (models.length === 0) {
    return (
      <div className="mt-5 flex flex-col gap-2">
        <Label htmlFor={id}>Model name</Label>
        <Input
          id={id}
          className="font-mono"
          spellCheck={false}
          placeholder="e.g. llama-3.1-8b-instruct"
          value={model ?? ""}
          readOnly={busy}
          aria-describedby={`${id}-help ${errorId}`}
          onChange={(e) => onChange(e.target.value.trim() || undefined)}
        />
        <p id={`${id}-help`} className="text-body-sm text-muted-foreground">
          {providerName} didn&apos;t give us a list of chat models. Type the model id from its docs.
        </p>
      </div>
    );
  }
  return (
    <div className="mt-5 flex flex-col gap-2">
      <Label htmlFor={id}>Model</Label>
      <Select
        id={id}
        value={model ?? ""}
        disabled={busy}
        aria-describedby={errorId}
        onChange={(e) => onChange(e.target.value || undefined)}
      >
        <option value="">Choose a model…</option>
        {models.map((mo) => (
          <option key={mo.id} value={mo.id}>
            {mo.displayName ?? mo.id}
            {mo.free ? " (free)" : ""}
          </option>
        ))}
      </Select>
    </div>
  );
}
