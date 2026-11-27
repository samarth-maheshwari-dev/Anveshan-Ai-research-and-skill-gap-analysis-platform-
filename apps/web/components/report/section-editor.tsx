"use client";

import { useQueryClient } from "@tanstack/react-query";
import { EditorContent, useEditor, useEditorState, type Editor } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import { Check, Languages, ListPlus, RotateCcw, Feather, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { Alert } from "@/components/anveshan/alert";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api/errors";
import {
  useAssist,
  useRegenerateSection,
  useSaveSection,
  type AssistAction,
  type Report,
  type ReportSection,
} from "@/lib/api/hooks/reports";
import { AUTOSAVE_MS, insertableContent, plainText, saveStateText, type DocNode, type SaveState } from "@/lib/report";
import { cn } from "@/lib/utils";

import { Citation } from "./citation-node";
import { TipTapView } from "./tiptap-view";

type Doc = Record<string, unknown>;

/** Only what the server keeps (ReportDocs.sanitize): no links, underline, images; headings 2–4; no trailing node. */
const EXTENSIONS = [
  StarterKit.configure({ link: false, underline: false, trailingNode: false, heading: { levels: [2, 3, 4] } }),
  Citation,
];

const ASSIST: { action: AssistAction; label: string; Icon: typeof Feather }[] = [
  { action: "SIMPLIFY", label: "Simplify", Icon: Feather },
  { action: "MORE_DETAIL", label: "More detail", Icon: ListPlus },
  { action: "TRANSLATE_HI", label: "Hindi", Icon: Languages },
];

type Suggestion = { from: number; to: number; fragment: DocNode; action: AssistAction; base: string };

type SectionEditorProps = { reportId: string; section: ReportSection };

/**
 * One editable section of S-08 (F-FR-6): TipTap with citation atoms, so editing around `[3]` never detaches it.
 * Saves with If-Match (autosave every 30 s, Ctrl+S, [Save]); the server renumbers citations and the editor takes its
 * numbers back without moving the cursor. Assist on a selection (F-FR-4) and Regenerate (F-FR-7) live here too.
 */
export function SectionEditor({ reportId, section }: SectionEditorProps) {
  const sectionId = section.id!;
  const qc = useQueryClient();
  const saveSection = useSaveSection(reportId);
  const assist = useAssist(reportId);
  const regenerate = useRegenerateSection(reportId);

  const [state, setState] = useState<SaveState>("saved");
  const [suggestion, setSuggestion] = useState<Suggestion | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [confirmRegenerate, setConfirmRegenerate] = useState(false);
  const version = useRef(section.version ?? 0);
  const saving = useRef<Promise<boolean> | null>(null);

  const editor = useEditor({
    extensions: EXTENSIONS,
    content: section.contentDoc as Doc,
    immediatelyRender: false,
    editorProps: {
      attributes: {
        class: "text-body leading-relaxed",
        "aria-label": `${section.title ?? "Section"} — text`,
        "aria-multiline": "true",
        role: "textbox",
      },
    },
    onUpdate: () => setState("dirty"),
  });

  const selection = useEditorState({
    editor,
    selector: ({ editor: e }) => (e ? { from: e.state.selection.from, to: e.state.selection.to } : null),
  });
  const hasSelection = !!selection && selection.to > selection.from;

  /** The server's version of the text (numbers, labels) without an update event, keeping the cursor where it was. */
  const applyServerDoc = useCallback((e: Editor, doc: unknown) => {
    const { from, to } = e.state.selection;
    e.commands.setContent(doc as Doc, { emitUpdate: false });
    const max = e.state.doc.content.size;
    e.commands.setTextSelection({ from: Math.min(from, max), to: Math.min(to, max) });
  }, []);

  const save = useCallback(async (): Promise<boolean> => {
    if (!editor) return false;
    if (saving.current) return saving.current;
    const doc = editor.getJSON();
    const sent = JSON.stringify(doc);
    setState("saving");
    const run = (async () => {
      try {
        const saved = await saveSection.mutateAsync({ sectionId, version: version.current, contentDoc: doc });
        version.current = saved.version ?? version.current + 1;
        if (JSON.stringify(editor.getJSON()) === sent) {
          applyServerDoc(editor, saved.contentDoc);
          setState("saved");
        } else {
          setState("dirty"); // typed while saving: the next save sends it
        }
        return true;
      } catch (e) {
        setState(e instanceof ApiError && e.status === 409 ? "conflict" : "error");
        if (e instanceof ApiError && e.status !== 409) setMessage(e.message);
        return false;
      } finally {
        saving.current = null;
      }
    })();
    saving.current = run;
    return run;
  }, [editor, saveSection, sectionId, applyServerDoc]);

  // autosave 30 s after the last unsaved change
  useEffect(() => {
    if (state !== "dirty") return;
    const t = window.setTimeout(() => void save(), AUTOSAVE_MS);
    return () => window.clearTimeout(t);
  }, [state, save]);

  // unsaved text: the browser asks before leaving
  useEffect(() => {
    if (state !== "dirty" && state !== "saving") return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [state]);

  // another section's save can renumber this one: take the server's text when nothing here is unsaved
  const serverDoc = JSON.stringify(section.contentDoc ?? null);
  useEffect(() => {
    if (!editor || state !== "saved") return;
    if (JSON.stringify(editor.getJSON()) !== serverDoc) applyServerDoc(editor, JSON.parse(serverDoc));
    version.current = section.version ?? version.current;
    // state is read, not watched: a fresh save already applied its own document
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editor, serverDoc, section.version, applyServerDoc]);

  // Ctrl/⌘+S saves this section
  useEffect(() => {
    if (!editor) return;
    const dom = editor.view.dom;
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
        e.preventDefault();
        void save();
      }
    };
    dom.addEventListener("keydown", onKey);
    return () => dom.removeEventListener("keydown", onKey);
  }, [editor, save]);

  const reload = async () => {
    await qc.refetchQueries({ queryKey: ["report", reportId] });
    const fresh = qc.getQueryData<Report>(["report", reportId])?.sections?.find((s) => s.id === sectionId);
    if (editor && fresh) {
      applyServerDoc(editor, fresh.contentDoc);
      version.current = fresh.version ?? 0;
    }
    setState("saved");
  };

  const runAssist = async (action: AssistAction) => {
    if (!editor || !selection) return;
    setMessage(null);
    setSuggestion(null);
    if (state !== "saved" && !(await save())) return; // positions must match the saved text
    const { from, to } = editor.state.selection;
    if (to <= from) return;
    try {
      const fragment = (await assist.mutateAsync({ sectionId, selection: { from, to }, action })) as DocNode;
      setSuggestion({ from, to, fragment, action, base: JSON.stringify(editor.getJSON()) });
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : "Assist didn't work. Try again.");
    }
  };

  const accept = () => {
    if (!editor || !suggestion) return;
    if (JSON.stringify(editor.getJSON()) !== suggestion.base) {
      setMessage("The text changed after you asked. Select it again and ask again.");
      setSuggestion(null);
      return;
    }
    editor.chain().focus().insertContentAt({ from: suggestion.from, to: suggestion.to }, insertableContent(suggestion.fragment)).run();
    setSuggestion(null);
    void save();
  };

  const runRegenerate = async () => {
    if (!editor) return;
    setConfirmRegenerate(false);
    setMessage(null);
    if (state !== "saved" && !(await save())) return;
    try {
      const saved = await regenerate.mutateAsync({ sectionId, version: version.current });
      version.current = saved.version ?? version.current;
      applyServerDoc(editor, saved.contentDoc);
      setState("saved");
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) setState("conflict");
      else setMessage(e instanceof ApiError ? e.message : "Couldn't rewrite this section. Try again.");
    }
  };

  const busy = assist.isPending || regenerate.isPending;
  const title = section.title ?? "Section";

  return (
    <section aria-labelledby={`sec-${sectionId}`} id={`section-${sectionId}`} className="scroll-mt-6" data-section-key={section.key}>
      <div className="flex flex-wrap items-end justify-between gap-2 border-b border-border pb-2">
        <h2 id={`sec-${sectionId}`} className="text-heading-lg font-semibold">
          {title}
        </h2>
        <div className="flex items-center gap-2">
          <span
            role="status"
            aria-live="polite"
            data-save-state={state}
            className={cn("font-mono text-micro", state === "conflict" || state === "error" ? "text-danger" : "text-subtle-foreground")}
          >
            {saveStateText(state)}
          </span>
          <Button size="sm" variant="ghost" onClick={() => void save()} disabled={state === "saved" || state === "saving"}>
            Save
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setConfirmRegenerate(true)} disabled={busy} aria-expanded={confirmRegenerate}>
            <RotateCcw aria-hidden /> Regenerate
          </Button>
        </div>
      </div>

      {confirmRegenerate && (
        <div role="group" aria-label="Regenerate section" className="mt-3 rounded-md border border-border bg-surface p-3 text-body-sm">
          <p>
            Rewrite <strong>{title}</strong> from its verified claims? No new search is made. Your edits here are replaced — you can bring
            them back from Versions.
          </p>
          <div className="mt-3 flex gap-2">
            <Button size="sm" variant="primary" onClick={() => void runRegenerate()}>
              Regenerate
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setConfirmRegenerate(false)}>
              Cancel
            </Button>
          </div>
        </div>
      )}

      {state === "conflict" && (
        <Alert className="mt-3" action={<Button size="sm" onClick={() => void reload()}>Reload section</Button>}>
          This section was saved somewhere else meanwhile. Reload it to see the latest text (copy anything you want to keep first).
        </Alert>
      )}
      {message && (
        <Alert className="mt-3" tone="neutral" action={<Button size="sm" variant="ghost" onClick={() => setMessage(null)}>Dismiss</Button>}>
          {message}
        </Alert>
      )}

      <div
        role="toolbar"
        aria-label="Assist on the selected text"
        className={cn("mt-3 flex flex-wrap items-center gap-1 text-body-sm", !hasSelection && "sr-only")}
      >
        <span className="mr-1 font-mono text-micro uppercase text-subtle-foreground">Assist</span>
        {ASSIST.map(({ action, label, Icon }) => (
          <Button
            key={action}
            size="sm"
            variant="secondary"
            disabled={!hasSelection || busy}
            loading={assist.isPending && assist.variables?.action === action}
            onMouseDown={(e) => e.preventDefault() /* keep the editor's selection */}
            onClick={() => void runAssist(action)}
          >
            <Icon aria-hidden /> {label}
          </Button>
        ))}
      </div>

      {suggestion && (
        <div role="region" aria-label="Assist suggestion" className="mt-3 rounded-md border border-primary bg-surface p-4">
          <p className="font-mono text-micro uppercase text-subtle-foreground">
            Suggestion · {ASSIST.find((a) => a.action === suggestion.action)?.label}
          </p>
          <div className="mt-2" aria-label={plainText(suggestion.fragment)}>
            <TipTapView doc={suggestion.fragment} />
          </div>
          <div className="mt-3 flex gap-2">
            <Button size="sm" variant="primary" onClick={accept}>
              <Check aria-hidden /> Accept
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setSuggestion(null)}>
              <X aria-hidden /> Discard
            </Button>
          </div>
        </div>
      )}

      <div className="report-editor mt-4 rounded-md border border-transparent px-1 py-1 focus-within:border-border">
        <EditorContent editor={editor} />
      </div>
    </section>
  );
}
