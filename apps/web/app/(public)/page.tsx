import { ArrowRight, ArrowDown } from "lucide-react";
import Link from "next/link";

import { Logo } from "@/components/anveshan/logo";
import { Faq } from "@/components/landing/faq";
import { HeroTyper } from "@/components/landing/hero-typer";
import { ProofCard } from "@/components/landing/proof-card";
import { Reveal, ScrollProgress } from "@/components/landing/reveal";
import { ScrollStory } from "@/components/landing/scroll-story";
import { TrustLedger } from "@/components/landing/trust-ledger";
import { buttonVariants } from "@/components/ui/button";

const FACTS = [
  ["5", "free reports every day"],
  ["0", "things to install"],
  ["1", "click to see any source"],
];

const TOPICS = [
  "Climate change in India",
  "How the heart works",
  "History of the internet",
  "Best way to learn coding",
  "Why the sky is blue",
  "Electric cars vs petrol",
  "Space missions of ISRO",
  "Healthy food for students",
];

/** S-01 Landing: an editorial page that explains the product as the visitor scrolls. */
export default function LandingPage() {
  return (
    <div className="min-h-screen">
      <ScrollProgress />
      <header className="sticky top-0 z-40 border-b border-border bg-background/90 backdrop-blur-md">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 md:px-8">
          <Logo />
          <nav aria-label="Account" className="flex items-center gap-2">
            <Link href="/login" className={buttonVariants({ variant: "ghost", size: "sm" })}>
              Sign in
            </Link>
            <Link href="/login" className={buttonVariants({ variant: "primary", size: "sm" })}>
              Start free
            </Link>
          </nav>
        </div>
      </header>

      <main>
        <section className="mx-auto max-w-6xl px-4 pb-16 pt-14 md:px-8 md:pb-24 md:pt-24">
          <Reveal>
            <p className="font-mono text-body-sm uppercase tracking-widest text-subtle-foreground">
              Anveshan · research helper
            </p>
          </Reveal>
          <Reveal delay={0.06}>
            <h1 className="mt-6 max-w-5xl font-display text-[clamp(3rem,8vw,7rem)] font-medium leading-[0.98] tracking-[-0.03em]">
              Ask a question. Get an answer <em className="hl text-primary">with proof.</em>
            </h1>
          </Reveal>
          <div className="mt-12 grid gap-10 lg:grid-cols-12 lg:items-end">
            <Reveal delay={0.12} className="lg:col-span-5">
              <p className="text-body-lg text-muted-foreground md:text-heading-md md:leading-8">
                Anveshan reads books, articles and research papers for you, then writes a short, clear report. Every
                line shows exactly where it came from.
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <Link href="/login" className={buttonVariants({ variant: "primary", size: "lg" })}>
                  Start my first report <ArrowRight aria-hidden />
                </Link>
              </div>
            </Reveal>
            <Reveal delay={0.18} className="lg:col-span-6 lg:col-start-7">
              <p className="mb-3 text-body-sm text-subtle-foreground">People ask things like…</p>
              <HeroTyper />
            </Reveal>
          </div>
          <a
            href="#how"
            className="mt-16 inline-flex items-center gap-2 text-body-sm font-medium text-muted-foreground hover:text-foreground"
          >
            <ArrowDown aria-hidden className="size-4 animate-bounce" /> Scroll to see how it works
          </a>
        </section>

        <div aria-hidden className="overflow-hidden border-y border-border-strong bg-inverse py-5 text-inverse-foreground">
          <div className="marquee-track gap-10 font-display text-heading-lg italic">
            {[...TOPICS, ...TOPICS].map((t, i) => (
              <span key={i} className="flex items-center gap-10 whitespace-nowrap">
                {t} <span className="text-primary">✦</span>
              </span>
            ))}
          </div>
        </div>

        <section id="how" aria-label="How it works" className="scroll-mt-16">
          <ScrollStory />
        </section>

        <section className="border-y border-border bg-surface">
          <dl className="mx-auto grid max-w-6xl divide-y divide-border px-4 md:grid-cols-3 md:divide-x md:divide-y-0 md:px-8">
            {FACTS.map(([n, label], i) => (
              <Reveal key={label} delay={i * 0.08}>
                <div className="py-10 md:px-8 md:py-14">
                  <dt className="font-display text-[clamp(4rem,9vw,7rem)] font-medium leading-none text-primary">{n}</dt>
                  <dd className="mt-3 text-body-lg text-muted-foreground">{label}</dd>
                </div>
              </Reveal>
            ))}
          </dl>
        </section>

        <section aria-labelledby="example" className="mx-auto grid max-w-6xl items-center gap-12 px-4 py-20 md:px-8 md:py-32 lg:grid-cols-2">
          <Reveal>
            <p className="font-mono text-body-sm uppercase tracking-widest text-primary">What a report looks like</p>
            <h2 id="example" className="mt-4 font-display text-[clamp(2.25rem,5vw,4rem)] font-medium leading-[1.05] tracking-tight">
              The small number is the proof.
            </h2>
            <p className="mt-5 max-w-md text-body-lg text-muted-foreground">
              Tap it and the exact sentence from the source opens, highlighted. No guessing where a fact came from.
            </p>
          </Reveal>
          <Reveal delay={0.12}>
            <ProofCard />
          </Reveal>
        </section>

        <section aria-labelledby="labels" className="bg-surface-2">
          <div className="mx-auto max-w-6xl px-4 py-20 md:px-8 md:py-32">
            <Reveal>
              <p className="font-mono text-body-sm uppercase tracking-widest text-primary">The colour labels</p>
              <h2 id="labels" className="mt-4 max-w-3xl font-display text-[clamp(2.25rem,5vw,4rem)] font-medium leading-[1.05] tracking-tight">
                Like traffic lights, but for facts.
              </h2>
            </Reveal>
            <Reveal delay={0.1} className="mt-12">
              <TrustLedger />
            </Reveal>
          </div>
        </section>

        <section aria-labelledby="faq" className="mx-auto grid max-w-6xl gap-10 px-4 py-20 md:px-8 md:py-32 lg:grid-cols-12">
          <Reveal className="lg:col-span-4">
            <h2 id="faq" className="font-display text-[clamp(2.25rem,4vw,3.25rem)] font-medium leading-[1.05] tracking-tight">
              Questions people ask
            </h2>
          </Reveal>
          <Reveal delay={0.1} className="lg:col-span-8">
            <Faq />
          </Reveal>
        </section>

        <section className="bg-inverse text-inverse-foreground">
          <div className="mx-auto max-w-6xl px-4 py-24 md:px-8 md:py-36">
            <Reveal>
              <h2 className="max-w-4xl font-display text-[clamp(2.75rem,7vw,6rem)] font-medium leading-[1] tracking-[-0.03em]">
                Your first report is <em className="text-primary">one click</em> away.
              </h2>
              <p className="mt-6 max-w-lg text-body-lg text-inverse-muted">
                Free. No card. No setup. Sign in with Google or GitHub.
              </p>
              <Link href="/login" className={`${buttonVariants({ variant: "primary", size: "lg" })} mt-10`}>
                Start free <ArrowRight aria-hidden />
              </Link>
            </Reveal>
          </div>
        </section>
      </main>

      <footer className="bg-inverse text-inverse-muted">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 border-t border-inverse-muted/30 px-4 py-8 md:px-8">
          <Logo className="text-inverse-foreground" />
          <p className="text-body-sm">Always check important facts before you submit your work.</p>
        </div>
      </footer>
    </div>
  );
}
