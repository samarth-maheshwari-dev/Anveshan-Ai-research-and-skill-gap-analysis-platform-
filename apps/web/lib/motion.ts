import type { Variants } from "motion/react";

/**
 * The only place motion values live (DESIGN §8, §15.3). Swiss restraint: opacity + small transforms,
 * 140–220 ms, no springs or bounces. Reduced motion is handled by <MotionConfig reducedMotion="user">.
 */
export const duration = { fast: 0.14, base: 0.22 } as const;

export const ease = {
  enter: [0.05, 0.7, 0.1, 1],
  exit: [0.3, 0, 0.8, 0.15],
} as const;

/** Wizard step change: 12px slide + fade (DESIGN §11, S-03). */
export const stepSlide: Variants = {
  hidden: { opacity: 0, x: 12 },
  show: { opacity: 1, x: 0, transition: { duration: duration.base, ease: ease.enter } },
  exit: { opacity: 0, x: -8, transition: { duration: duration.fast, ease: ease.exit } },
};

/** "Verified" — the first proof moment a user sees (DESIGN §10.1): a short settle, no bounce. */
export const stamp: Variants = {
  hidden: { opacity: 0, scale: 0.96 },
  show: { opacity: 1, scale: 1, transition: { duration: duration.base, ease: ease.enter } },
};
