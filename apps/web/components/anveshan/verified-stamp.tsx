"use client";

import { CircleCheck } from "lucide-react";
import * as m from "motion/react-m";

import { stamp } from "@/lib/motion";

/**
 * The Pramaan stamp (DESIGN §10.1): the first proof moment a user ever sees — their key, verified.
 * Reduced motion: MotionConfig turns this into a plain fade.
 */
export function VerifiedStamp({ label = "Verified" }: { label?: string }) {
  return (
    <m.span
      variants={stamp}
      initial="hidden"
      animate="show"
      className="inline-flex items-center gap-2 rounded-sm border-2 border-trust-supported bg-trust-supported-bg px-3 py-1 font-display text-heading-md font-semibold uppercase tracking-wide text-trust-supported"
    >
      <CircleCheck aria-hidden strokeWidth={2} className="size-5" />
      {label}
    </m.span>
  );
}
