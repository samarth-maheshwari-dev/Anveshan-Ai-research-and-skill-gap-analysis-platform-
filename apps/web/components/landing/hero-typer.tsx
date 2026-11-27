"use client";

import { Search } from "lucide-react";
import { AnimatePresence, motion, MotionConfig } from "motion/react";
import { useEffect, useState } from "react";

const QUESTIONS = [
  "Why do volcanoes erupt?",
  "Is solar power cheaper than coal?",
  "How do vaccines train our body?",
  "Which database suits a college app?",
  "What did the Mughals build in Agra?",
];

/** The hero's search box: example questions rotate inside it, so a first-time visitor sees what to type. */
export function HeroTyper() {
  const [i, setI] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setI((n) => (n + 1) % QUESTIONS.length), 2600);
    return () => clearInterval(id);
  }, []);
  return (
    <MotionConfig reducedMotion="user">
      <div className="relative flex h-16 items-center gap-3 overflow-hidden rounded-xl border border-border-strong bg-surface pl-5 pr-2 shadow-card">
        <Search aria-hidden className="size-5 shrink-0 text-subtle-foreground" />
        <div className="relative h-7 flex-1 overflow-hidden text-left text-body-lg text-muted-foreground">
          <AnimatePresence mode="wait">
            <motion.span
              key={i}
              className="absolute inset-0 truncate"
              initial={{ y: 24, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={{ y: -24, opacity: 0 }}
              transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
            >
              {QUESTIONS[i]}
            </motion.span>
          </AnimatePresence>
        </div>
        <span className="hidden h-12 items-center rounded-lg bg-inverse px-5 text-body font-semibold text-inverse-foreground sm:inline-flex">
          Find out
        </span>
      </div>
    </MotionConfig>
  );
}
