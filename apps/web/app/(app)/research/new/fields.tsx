"use client";

import { ArrowDown, ArrowUp, Check, Plus, Trash2 } from "lucide-react";
import { useId, type ReactNode } from "react";

import { Button } from "@/components/ui/button";
import { Input, Label } from "@/components/ui/input";
import { SECTIONS_MAX } from "@/lib/research-options";
import { cn } from "@/lib/utils";

type ChoiceCardProps = { name: string; checked: boolean; onChange: () => void; title: string; help: string };

/** A radio as a card: the whole card is the hit area; checked = green border + check (never colour alone). */
export function ChoiceCard({ name, checked, onChange, title, help }: ChoiceCardProps) {
  return (
    <label
      className={cn(
        "relative flex cursor-pointer flex-col gap-1 rounded-md border p-4 transition-[border-color] duration-150",
        "has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring",
        checked ? "border-primary bg-primary-soft" : "border-border bg-background hover:border-border-strong",
      )}
    >
      <input type="radio" name={name} className="sr-only" checked={checked} onChange={onChange} />
      <span className="flex items-center justify-between gap-2 text-body-sm font-medium">
        {title}
        {checked && <Check aria-hidden className="size-4 text-primary" />}
      </span>
      <span className="text-caption text-muted-foreground">{help}</span>
    </label>
  );
}

type FieldProps = {
  label: string;
  error?: string;
  help?: ReactNode;
  className?: string;
  children: (props: { id: string; "aria-invalid"?: boolean; "aria-describedby"?: string }) => ReactNode;
};

/** Label + control + one line of help or error under it (APPFLOW §2.3: error next to the field). */
export function Field({ label, error, help, className, children }: FieldProps) {
  const id = useId();
  const hint = `${id}-hint`;
  return (
    <div className={className}>
      <Label htmlFor={id}>{label}</Label>
      <div className="mt-2">
        {children({ id, "aria-invalid": error ? true : undefined, "aria-describedby": error || help ? hint : undefined })}
      </div>
      {(error || help) && (
        <p id={hint} className={cn("mt-1.5 text-caption", error ? "text-danger" : "text-subtle-foreground")}>
          {error ?? help}
        </p>
      )}
    </div>
  );
}

type SectionsEditorProps = { sections: string[]; onChange: (s: string[]) => void; error?: string };

/** The user's own report sections: rename, reorder, remove, add (max 12). One WRITE request each. */
export function SectionsEditor({ sections, onChange, error }: SectionsEditorProps) {
  const set = (i: number, v: string) => onChange(sections.map((s, j) => (j === i ? v : s)));
  const move = (i: number, by: number) => {
    const next = [...sections];
    [next[i], next[i + by]] = [next[i + by], next[i]];
    onChange(next);
  };
  return (
    <div>
      <ol className="space-y-2">
        {sections.map((s, i) => (
          <li key={i} className="flex items-center gap-2">
            <span className="tabular w-6 shrink-0 text-center text-caption text-subtle-foreground">{i + 1}</span>
            <Input
              aria-label={`Section ${i + 1} title`}
              value={s}
              maxLength={160}
              onChange={(e) => set(i, e.target.value)}
            />
            <Button size="icon" variant="ghost" aria-label={`Move section ${i + 1} up`} disabled={i === 0} onClick={() => move(i, -1)}>
              <ArrowUp aria-hidden />
            </Button>
            <Button
              size="icon"
              variant="ghost"
              aria-label={`Move section ${i + 1} down`}
              disabled={i === sections.length - 1}
              onClick={() => move(i, 1)}
            >
              <ArrowDown aria-hidden />
            </Button>
            <Button
              size="icon"
              variant="ghost"
              aria-label={`Delete section ${i + 1}`}
              disabled={sections.length <= 1}
              onClick={() => onChange(sections.filter((_, j) => j !== i))}
            >
              <Trash2 aria-hidden />
            </Button>
          </li>
        ))}
      </ol>
      <Button
        className="mt-3"
        size="sm"
        variant="secondary"
        disabled={sections.length >= SECTIONS_MAX}
        onClick={() => onChange([...sections, ""])}
      >
        <Plus aria-hidden /> Add a section
      </Button>
      <p className={cn("mt-2 text-caption", error ? "text-danger" : "text-subtle-foreground")} role={error ? "alert" : undefined}>
        {error ?? `Up to ${SECTIONS_MAX} sections. Each one is written separately (1 AI request each).`}
      </p>
    </div>
  );
}
