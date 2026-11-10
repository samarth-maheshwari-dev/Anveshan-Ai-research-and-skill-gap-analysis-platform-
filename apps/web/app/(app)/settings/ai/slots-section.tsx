"use client";

import { Plus, X } from "lucide-react";
import { useId, useState } from "react";
import { toast } from "sonner";

import { Alert } from "@/components/anveshan/alert";
import { Button } from "@/components/ui/button";
import { ErrorLine } from "@/components/anveshan/error-line";
import { Card, CardTitle, Overline, Skeleton } from "@/components/ui/card";
import { Input, Label, Select } from "@/components/ui/input";
import { ApiError } from "@/lib/api/errors";
import { useConnections, useModels, type Connection } from "@/lib/api/hooks/connections";
import { SLOT_LABELS, TASK_SLOTS, useSaveSlots, useSlots, type RouteChoice, type Slots } from "@/lib/api/hooks/slots";

/** Settings → AI: task routing — primary model + up to 4 fallbacks per slot (A-FR-5). */
export function SlotsSection() {
  const slots = useSlots();
  const connections = useConnections();
  const save = useSaveSlots();
  // Unsaved edits; until the user changes something, show what the server has.
  const [edits, setEdits] = useState<Slots | null>(null);
  const draft = edits ?? slots.data ?? null;
  const [error, setError] = useState<string | null>(null);

  const usable = (connections.data ?? []).filter((c) => c.kind === "LLM" && c.status === "VERIFIED");

  function update(slot: string, routes: RouteChoice[]) {
    setEdits({ ...(draft ?? {}), [slot]: routes });
    setError(null);
  }

  async function onSave() {
    if (!draft) return;
    const cleaned: Slots = {};
    for (const s of TASK_SLOTS) {
      cleaned[s] = (draft[s] ?? []).filter((r) => r.connectionId && r.model?.trim());
    }
    try {
      const saved = await save.mutateAsync(cleaned);
      setEdits(saved);
      toast.success("Task routing saved.");
    } catch (e) {
      setError(e instanceof ApiError ? (e.problem.detail ?? e.message) : "Couldn't save. Try again.");
    }
  }

  return (
    <Card>
      <Overline>02 — Routing</Overline>
      <CardTitle className="mt-2">Task routing</CardTitle>
      <p className="mt-1 text-body-sm text-muted-foreground">
        Which model does each job. If the first one hits its limit, Anveshan moves to the next (up to 5).
      </p>
      {slots.isError || connections.isError ? (
        <ErrorLine
          message="Couldn't load your task routing."
          onRetry={() => {
            void slots.refetch();
            void connections.refetch();
          }}
        />
      ) : slots.isPending || connections.isPending || !draft ? (
        <div className="mt-6 space-y-3">
          {TASK_SLOTS.map((s) => (
            <Skeleton key={s} className="h-10 w-full" />
          ))}
        </div>
      ) : usable.length === 0 ? (
        <p className="mt-6 text-body-sm text-muted-foreground">Test a connection first — only verified keys can be used.</p>
      ) : (
        <>
          <div className="mt-6 space-y-6">
            {TASK_SLOTS.map((slot) => (
              <SlotEditor
                key={slot}
                slot={slot}
                routes={draft[slot] ?? []}
                connections={usable}
                onChange={(r) => update(slot, r)}
              />
            ))}
          </div>
          {error && (
            <Alert className="mt-4">{error}</Alert>
          )}
          <div className="mt-6 flex justify-end">
            <Button variant="primary" loading={save.isPending} onClick={() => void onSave()}>
              Save routing
            </Button>
          </div>
        </>
      )}
    </Card>
  );
}

type SlotEditorProps = {
  slot: (typeof TASK_SLOTS)[number];
  routes: RouteChoice[];
  connections: Connection[];
  onChange: (r: RouteChoice[]) => void;
};

function SlotEditor({ slot, routes, connections, onChange }: SlotEditorProps) {
  const id = useId();
  const label = SLOT_LABELS[slot];
  const rows = routes.length > 0 ? routes : [{ connectionId: "", model: "" }];
  return (
    <fieldset>
      <legend className="text-body font-medium">{label.name}</legend>
      <p className="text-body-sm text-muted-foreground">{label.help}</p>
      <div className="mt-2 space-y-2">
        {rows.map((r, i) => (
          <RouteRow
            key={i}
            idPrefix={`${id}-${i}`}
            index={i}
            route={r}
            connections={connections}
            onChange={(next) => onChange(rows.map((x, j) => (j === i ? next : x)))}
            onRemove={rows.length > 1 ? () => onChange(rows.filter((_, j) => j !== i)) : undefined}
          />
        ))}
      </div>
      {rows.length < 5 && (
        <Button
          variant="ghost"
          size="sm"
          className="mt-2 -ml-3 text-muted-foreground hover:text-foreground"
          onClick={() => onChange([...rows, { connectionId: connections[0]?.id ?? "", model: "" }])}
        >
          <Plus aria-hidden /> Add fallback
        </Button>
      )}
    </fieldset>
  );
}

type RouteRowProps = {
  idPrefix: string;
  index: number;
  route: RouteChoice;
  connections: Connection[];
  onChange: (r: RouteChoice) => void;
  onRemove?: () => void;
};

function RouteRow({ idPrefix, index, route, connections, onChange, onRemove }: RouteRowProps) {
  const models = useModels(route.connectionId || undefined);
  const listId = `${idPrefix}-models`;
  return (
    <div className="grid items-end gap-2 sm:grid-cols-[1fr_1fr_auto]">
      <div className="flex flex-col gap-1">
        <Label htmlFor={`${idPrefix}-c`} className={index > 0 ? "sr-only sm:not-sr-only" : undefined}>
          {index === 0 ? "Connection" : `Fallback ${index}`}
        </Label>
        <Select
          id={`${idPrefix}-c`}
          value={route.connectionId ?? ""}
          onChange={(e) => onChange({ connectionId: e.target.value, model: "" })}
        >
          <option value="">Choose…</option>
          {connections.map((c) => (
            <option key={c.id} value={c.id}>
              {c.label}
            </option>
          ))}
        </Select>
      </div>
      <div className="flex flex-col gap-1">
        <Label htmlFor={`${idPrefix}-m`}>Model</Label>
        <Input
          id={`${idPrefix}-m`}
          list={listId}
          className="font-mono"
          value={route.model ?? ""}
          placeholder={models.isPending && route.connectionId ? "Loading models…" : "model id"}
          onChange={(e) => onChange({ ...route, model: e.target.value })}
        />
        <datalist id={listId}>
          {(models.data ?? []).filter((m) => m.chat !== false).map((m) => (
            <option key={m.id} value={m.id}>
              {m.free ? "free" : undefined}
            </option>
          ))}
        </datalist>
      </div>
      {onRemove ? (
        <Button variant="ghost" size="icon" aria-label="Remove fallback" onClick={onRemove}>
          <X aria-hidden />
        </Button>
      ) : (
        <span className="hidden size-10 sm:block" aria-hidden />
      )}
    </div>
  );
}
