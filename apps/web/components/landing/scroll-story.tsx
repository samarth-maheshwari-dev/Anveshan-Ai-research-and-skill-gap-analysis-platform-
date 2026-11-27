"use client";

import { CircleCheck, Swords, TriangleAlert } from "lucide-react";
import { AnimatePresence, motion, MotionConfig, useMotionValueEvent, useScroll } from "motion/react";
import { useRef, useState } from "react";

import { Badge } from "@/components/ui/card";
import { cn } from "@/lib/utils";

const STEPS = [
  {
    kicker: "Step one",
    title: "You ask a question.",
    body: "Any question — for homework, a college project or your job. Plain words are fine.",
  },
  {
    kicker: "Step two",
    title: "We plan it with you.",
    body: "Your question is split into a few smaller ones. Remove, add or change any of them, then press Start.",
  },
  {
    kicker: "Step three",
    title: "You get a report with proof.",
    body: "Every line has a small number. Tap it and you see the exact sentence from the book or paper it came from.",
  },
];

/**
 * The landing page's scroll story: a tall section whose inner frame sticks to the screen. As the visitor
 * scrolls, the three steps change on the left and the example on the right builds up — so scrolling itself
 * explains how the product works.
 */
export function ScrollStory() {
  const ref = useRef<HTMLDivElement>(null);
  const { scrollYProgress } = useScroll({ target: ref, offset: ["start start", "end end"] });
  const [step, setStep] = useState(0);
  useMotionValueEvent(scrollYProgress, "change", (v) => setStep(Math.min(2, Math.floor(v * 3))));

  return (
    <MotionConfig reducedMotion="user">
      <div ref={ref} className="relative h-[300vh]">
        <div className="sticky top-16 flex min-h-[calc(100vh-4rem)] items-center">
          <div className="mx-auto grid w-full max-w-6xl items-center gap-10 px-4 py-10 md:px-8 lg:grid-cols-2 lg:gap-16">
            <div>
              <div className="flex gap-2" aria-hidden>
                {STEPS.map((s, i) => (
                  <span
                    key={s.kicker}
                    className={cn(
                      "h-1 flex-1 rounded-full transition-colors duration-500",
                      i <= step ? "bg-primary" : "bg-surface-3",
                    )}
                  />
                ))}
              </div>
              <AnimatePresence mode="wait">
                <motion.div
                  key={step}
                  initial={{ opacity: 0, y: 24 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: -24 }}
                  transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
                  className="mt-10"
                >
                  <p className="font-mono text-body-sm uppercase tracking-widest text-primary">{STEPS[step]!.kicker}</p>
                  <h3 className="mt-4 font-display text-[clamp(2.25rem,4.5vw,3.75rem)] font-medium leading-[1.05] tracking-tight">
                    {STEPS[step]!.title}
                  </h3>
                  <p className="mt-5 max-w-md text-body-lg text-muted-foreground">{STEPS[step]!.body}</p>
                </motion.div>
              </AnimatePresence>
              <p className="mt-10 text-body-sm text-subtle-foreground">Keep scrolling ↓</p>
            </div>

            <div className="rounded-xl border border-border-strong bg-surface p-6 shadow-card md:p-8">
              <p className="font-mono text-micro uppercase text-subtle-foreground">Your question</p>
              <p className="mt-2 font-display text-heading-lg">Why do volcanoes erupt?</p>

              <motion.div
                animate={{ opacity: step >= 1 ? 1 : 0.15 }}
                transition={{ duration: 0.5 }}
                className="mt-6 border-t border-border pt-5"
              >
                <p className="font-mono text-micro uppercase text-subtle-foreground">The plan</p>
                <ol className="mt-3 space-y-2 text-body">
                  {["What is magma made of?", "What builds pressure under the ground?", "Which famous eruptions show this?"].map(
                    (q, i) => (
                      <li key={q} className="flex gap-3">
                        <span className="font-mono text-body-sm text-primary">0{i + 1}</span>
                        {q}
                      </li>
                    ),
                  )}
                </ol>
              </motion.div>

              <motion.div
                animate={{ opacity: step >= 2 ? 1 : 0.15 }}
                transition={{ duration: 0.5 }}
                className="mt-6 border-t border-border pt-5"
              >
                <p className="font-mono text-micro uppercase text-subtle-foreground">The report</p>
                <p className="mt-3 text-body">
                  Gas trapped in magma pushes upward until the rock above can no longer hold it{" "}
                  <sup className="font-mono text-primary">[1]</sup>.
                </p>
                <div className="mt-4 flex flex-wrap gap-2">
                  <Badge className="bg-trust-supported-bg text-trust-supported">
                    <CircleCheck aria-hidden /> Supported
                  </Badge>
                  <Badge className="bg-trust-weak-bg text-trust-weak">
                    <TriangleAlert aria-hidden /> Weak
                  </Badge>
                  <Badge className="bg-trust-conflict-bg text-trust-conflict">
                    <Swords aria-hidden /> Conflicting
                  </Badge>
                </div>
              </motion.div>
            </div>
          </div>
        </div>
      </div>
    </MotionConfig>
  );
}
