/**
 * A paragraph of a finished report with numbered citations and the source list behind them — shown on
 * the inverted (white) band, so it stays monochrome. Static example.
 */
const SOURCES = [
  { n: 1, title: "Transformers for chest radiograph classification", kind: "Peer-reviewed · 2023", where: "quote on p. 4" },
  { n: 2, title: "ViT vs. CNN under limited labels: a benchmark", kind: "Peer-reviewed · 2024", where: "quote on p. 7" },
  { n: 3, title: "Why radiology will switch to transformers", kind: "Blog · 2024", where: "low tier — not enough alone" },
];

type CiteProps = { n: number };

function Cite({ n }: CiteProps) {
  return (
    <span className="ml-1 inline-flex h-5 min-w-5 items-center justify-center rounded-xs border border-inverse-muted px-1 align-text-top font-mono text-micro text-inverse-muted">
      {n}
    </span>
  );
}

export function ReportExcerpt() {
  return (
    <figure aria-label="Example: a report paragraph and its sources" className="border-t-2 border-inverse-foreground">
      <div className="flex items-center justify-between py-3 font-mono text-micro uppercase text-inverse-muted">
        <span>§2 Findings</span>
        <span>3 sources</span>
      </div>
      <p className="text-body-lg">
        When pre-trained on large datasets, vision transformers reached the accuracy of CNN baselines on chest
        X-rays
        <Cite n={1} />
        <Cite n={2} />. Claims that they need far less labelled data rest on a single blog post
        <Cite n={3} /> and are marked as weak evidence.
      </p>
      <ol className="mt-8 border-t border-inverse-muted">
        {SOURCES.map((s) => (
          <li key={s.n} className="grid gap-1 border-b border-inverse-muted py-3 sm:grid-cols-12 sm:gap-4">
            <span className="font-mono text-caption sm:col-span-1">[{s.n}]</span>
            <span className="text-body-sm sm:col-span-6">{s.title}</span>
            <span className="font-mono text-micro uppercase text-inverse-muted sm:col-span-5">
              {s.kind} · {s.where}
            </span>
          </li>
        ))}
      </ol>
    </figure>
  );
}
