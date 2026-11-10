"use client";

import { CircleCheck } from "lucide-react";
import * as m from "motion/react-m";

import { stamp } from "@/lib/motion";

type VerifiedStampProps = { label?: string };

/**
 * "Verified" (DESIGN §10.1): the first proof moment a user sees — their key, checked. Icon + word,
 * mono caps, a short settle. Reduced motion: MotionConfig turns this into a plain fade.
 */
export function VerifiedStamp({ label = "Verified" }: VerifiedStampProps) {
  return (
    <m.span
      variants={stamp}
      initial="hidden"
      animate="show"
      className="inline-flex items-center gap-2 rounded-sm border border-trust-supported bg-trust-supported-bg px-3 py-1.5 font-mono text-caption font-medium uppercase tracking-wider text-trust-supported"
    >
      <CircleCheck aria-hidden strokeWidth={2} className="size-4" />
      {label}
    </m.span>
  );
}
