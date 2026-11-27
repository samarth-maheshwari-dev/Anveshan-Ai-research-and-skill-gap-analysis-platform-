"use client";

import { motion, MotionConfig, useScroll } from "motion/react";
import type { ReactNode } from "react";

type RevealProps = { children: ReactNode; delay?: number; className?: string };

/** Fades and lifts its content in the first time it scrolls into view. Respects reduced motion. */
export function Reveal({ children, delay = 0, className }: RevealProps) {
  return (
    <MotionConfig reducedMotion="user">
      <motion.div
        className={className}
        initial={{ opacity: 0, y: 28 }}
        whileInView={{ opacity: 1, y: 0 }}
        viewport={{ once: true, margin: "-60px" }}
        transition={{ duration: 0.7, delay, ease: [0.16, 1, 0.3, 1] }}
      >
        {children}
      </motion.div>
    </MotionConfig>
  );
}

/** A thin bar along the top edge that fills as the visitor scrolls down the page. */
export function ScrollProgress() {
  const { scrollYProgress } = useScroll();
  return (
    <motion.div
      aria-hidden
      className="fixed inset-x-0 top-0 z-50 h-1 origin-left bg-primary"
      style={{ scaleX: scrollYProgress }}
    />
  );
}
