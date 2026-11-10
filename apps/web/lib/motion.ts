import type { Transition, Variants } from "motion/react";

/** The only place motion values live (DESIGN §8, §15.3). */
export const duration = { instant: 0.08, fast: 0.14, base: 0.22, slow: 0.36, slower: 0.56, epic: 0.9 } as const;

export const ease = {
  standard: [0.2, 0, 0, 1],
  enter: [0.05, 0.7, 0.1, 1],
  exit: [0.3, 0, 0.8, 0.15],
  marker: [0.65, 0, 0.35, 1],
} as const;

export const spring = {
  snappy: { type: "spring", stiffness: 500, damping: 32, mass: 0.8 },
  gentle: { type: "spring", stiffness: 260, damping: 28 },
  bouncy: { type: "spring", stiffness: 420, damping: 14 },
} satisfies Record<string, Transition>;

/** Standard enter/exit for cards, toasts, list items. */
export const rise: Variants = {
  hidden: { opacity: 0, y: 8 },
  show: { opacity: 1, y: 0, transition: spring.gentle },
  exit: { opacity: 0, y: 4, transition: { duration: duration.base * 0.7, ease: ease.exit } },
};

/** Wizard step change: slide 24px + fade (DESIGN §11, S-03). */
export const stepSlide: Variants = {
  hidden: { opacity: 0, x: 24 },
  show: { opacity: 1, x: 0, transition: spring.gentle },
  exit: { opacity: 0, x: -16, transition: { duration: duration.base * 0.7, ease: ease.exit } },
};

/** Stagger children, capped: items beyond 8 get no extra delay (§8.5 rule 3). */
export const staggerDelay = (index: number) => Math.min(index, 8) * 0.04;

/** Pramaan stamp (§10.1) — the first proof moment a user sees is "Verified" in the wizard. */
export const stamp: Variants = {
  hidden: { opacity: 0, scale: 1.35, rotate: -8 },
  show: { opacity: 1, scale: 1, rotate: 0, transition: spring.bouncy },
};
