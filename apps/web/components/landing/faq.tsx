"use client";

import { ChevronDown } from "lucide-react";
import { useState } from "react";

import { cn } from "@/lib/utils";

const FAQ = [
  ["Is it really free?", "Yes. Sign in with Google or GitHub and start. You get 5 researches every day, no payment, no card."],
  ["Do I need to install anything?", "No. It works in your browser, on a laptop or on a phone."],
  ["How do I know the report is true?", "Every line has a small number next to it. Click the number and you see the exact sentence from the source it came from."],
  ["What does the coloured label mean?", "Green means sources agree. Yellow means the proof is weak. Red means sources disagree. Grey means no source was found."],
  ["Can I download my report?", "Yes. Open a finished report and download it as a PDF or a Markdown file."],
];

/** Plain-language questions a first-time visitor asks, opened one at a time. */
export function Faq() {
  const [open, setOpen] = useState(0);
  return (
    <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-surface shadow-card">
      {FAQ.map(([q, a], i) => (
        <li key={q}>
          <button
            type="button"
            aria-expanded={open === i}
            onClick={() => setOpen(open === i ? -1 : i)}
            className="flex w-full items-center justify-between gap-4 px-6 py-5 text-left text-body-lg font-semibold hover:bg-surface-2"
          >
            {q}
            <ChevronDown aria-hidden className={cn("size-5 shrink-0 transition-transform duration-300", open === i && "rotate-180")} />
          </button>
          <div className={cn("grid transition-[grid-template-rows] duration-300 ease-standard", open === i ? "grid-rows-[1fr]" : "grid-rows-[0fr]")}>
            <p className="overflow-hidden px-6 text-body text-muted-foreground">
              <span className="block pb-5">{a}</span>
            </p>
          </div>
        </li>
      ))}
    </ul>
  );
}
