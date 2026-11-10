/** Skill Proof Engine evidence ladder (PRD Module K): strongest proof first. Static example. */
const RUNGS = [
  { name: "Project defense", body: "An AI viva on your own code. Hardest to fake.", width: "w-full" },
  { name: "Passed challenge", body: "A timed, real task: predict, explain, debug, improve or build.", width: "w-4/5" },
  { name: "Repository evidence", body: "What your public GitHub code actually shows.", width: "w-3/5" },
  { name: "Quiz", body: "Adaptive questions, graded by code — not by the AI.", width: "w-2/5" },
  { name: "Resume claim", body: "Shown as “Claimed, not verified”. Never counted as proof.", width: "w-1/5" },
];

export function EvidenceLadder() {
  return (
    <ol className="border-t border-border-strong">
      {RUNGS.map((r, i) => (
        <li key={r.name} className="grid gap-2 border-b border-border py-5 md:grid-cols-12 md:items-center md:gap-6">
          <span className="font-mono text-caption text-muted-foreground tabular md:col-span-1">0{i + 1}</span>
          <div className="md:col-span-4">
            <p className="text-heading-sm font-semibold">{r.name}</p>
            <p className="mt-1 text-body-sm text-muted-foreground">{r.body}</p>
          </div>
          <div aria-hidden className="h-1 bg-surface-2 md:col-span-7">
            <div className={`h-1 bg-foreground ${r.width}`} />
          </div>
        </li>
      ))}
    </ol>
  );
}
