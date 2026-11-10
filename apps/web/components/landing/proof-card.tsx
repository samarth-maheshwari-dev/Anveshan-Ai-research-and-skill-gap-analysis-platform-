import { CircleCheck } from "lucide-react";

import { Badge } from "@/components/ui/card";

/**
 * The landing page's one visual: a real report fragment — a claim, its citation, its trust label and
 * the exact sentence it came from (DESIGN §10.14). Static, no animation, an example only.
 */
export function ProofCard() {
  return (
    <figure
      aria-label="Example: a claim with its source and trust label"
      className="rounded-lg border border-border-strong bg-surface"
    >
      <div className="flex items-center justify-between border-b border-border px-5 py-3 font-mono text-micro uppercase text-muted-foreground">
        <span>Report · Key findings</span>
        <span>Claim 03</span>
      </div>
      <div className="p-5 md:p-6">
        <p className="text-body-lg text-foreground">
          Vision transformers matched CNN accuracy on chest X-ray classification when pre-trained on large
          datasets{" "}
          <span className="inline-flex h-5 min-w-5 items-center justify-center rounded-xs border border-border-strong px-1 align-text-top font-mono text-micro text-muted-foreground">
            3
          </span>
        </p>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <Badge className="bg-trust-supported-bg text-trust-supported">
            <CircleCheck aria-hidden strokeWidth={2} /> Supported
          </Badge>
          <span className="font-mono text-micro uppercase text-muted-foreground">2 sources agree · peer-reviewed</span>
        </div>
        <blockquote className="mt-5 border-l-2 border-primary pl-4 text-body-sm text-muted-foreground">
          <span className="marker text-foreground">
            “…ViT-B/16 reached an AUC of 0.92, on par with the ResNet-50 baseline…”
          </span>
        </blockquote>
        <figcaption className="mt-4 font-mono text-micro text-subtle-foreground">
          [3] Peer-reviewed journal article · 2023 · exact quote, page 4
        </figcaption>
      </div>
    </figure>
  );
}
