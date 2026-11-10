import { ArrowRight } from "lucide-react";
import Link from "next/link";

import { EvidenceLadder } from "@/components/landing/evidence-ladder";
import { ProofCard } from "@/components/landing/proof-card";
import { ReportExcerpt } from "@/components/landing/report-excerpt";
import { TrustLedger } from "@/components/landing/trust-ledger";
import { Logo } from "@/components/anveshan/logo";
import { buttonVariants } from "@/components/ui/button";
import { Overline } from "@/components/ui/card";

const KEY_FACTS = [
  ["Setup", "About two minutes with a free Gemini or Groq key"],
  ["Storage", "Encrypted with AES-256-GCM"],
  ["Shown back", "Only the last four characters"],
  ["Used for", "Your own requests — nothing else"],
];

/** S-01 Landing (APPFLOW §15): explain Anveshan in 10 seconds, get to sign-in. Static server component. */
export default function LandingPage() {
  return (
    <div className="min-h-screen">
      <header className="border-b border-border">
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
        <section className="mx-auto grid max-w-6xl gap-12 px-4 py-16 md:px-8 lg:grid-cols-12 lg:gap-10 lg:py-24">
          <div className="lg:col-span-7">
            <Overline>AI research assistant · Skill proof</Overline>
            <h1 className="mt-6 text-display-lg font-semibold sm:text-display-xl lg:text-display-2xl">
              Research you can defend. Skills you can prove.
            </h1>
            <p className="mt-6 max-w-xl text-body-lg text-muted-foreground">
              Ask a question or set a goal. Anveshan reads the sources, writes a report where every claim carries
              its exact quote, and tells you plainly when the evidence is weak.
            </p>
            <div className="mt-10 flex flex-wrap items-center gap-x-6 gap-y-4">
              <Link href="/login" className={buttonVariants({ variant: "primary", size: "lg" })}>
                Start free <ArrowRight aria-hidden />
              </Link>
              <span className="font-mono text-micro uppercase text-muted-foreground">
                Free · bring your own AI key
              </span>
            </div>
          </div>
          <div className="lg:col-span-5 lg:pt-14">
            <ProofCard />
          </div>
        </section>

        <section aria-labelledby="labels" className="border-t border-border">
          <div className="mx-auto grid max-w-6xl gap-8 px-4 py-16 md:px-8 lg:grid-cols-12 lg:py-24">
            <div className="lg:col-span-4">
              <Overline>01 — Trust labels</Overline>
              <h2 id="labels" className="mt-4 text-display-lg font-semibold">
                No claim leaves without a label.
              </h2>
              <p className="mt-4 text-body text-muted-foreground">
                The label is computed from the sources, not chosen by the AI.
              </p>
            </div>
            <div className="lg:col-span-8">
              <TrustLedger />
            </div>
          </div>
        </section>

        <section aria-labelledby="report" className="bg-inverse text-inverse-foreground">
          <div className="mx-auto grid max-w-6xl gap-10 px-4 py-16 md:px-8 lg:grid-cols-12 lg:py-24">
            <div className="lg:col-span-4">
              <Overline className="text-inverse-muted">02 — The report</Overline>
              <h2 id="report" className="mt-4 text-display-lg font-semibold">
                Written to survive your examiner.
              </h2>
              <p className="mt-4 text-body text-inverse-muted">
                Every number points to a source you can open, and weak sources are named as weak.
              </p>
            </div>
            <div className="lg:col-span-8">
              <ReportExcerpt />
            </div>
          </div>
        </section>

        <section aria-labelledby="skills" className="border-b border-border">
          <div className="mx-auto grid max-w-6xl gap-8 px-4 py-16 md:px-8 lg:grid-cols-12 lg:py-24">
            <div className="lg:col-span-4">
              <Overline>03 — Skill proof</Overline>
              <h2 id="skills" className="mt-4 text-display-lg font-semibold">
                Skills, ranked by how hard they are to fake.
              </h2>
            </div>
            <div className="lg:col-span-8">
              <EvidenceLadder />
            </div>
          </div>
        </section>

        <section aria-labelledby="free" className="mx-auto grid max-w-6xl gap-10 px-4 py-16 md:px-8 lg:grid-cols-12 lg:py-24">
          <div className="lg:col-span-5">
            <Overline>04 — Cost</Overline>
            <h2 id="free" className="mt-4 text-display-lg font-semibold">
              Free, because you bring your own AI key.
            </h2>
            <Link href="/login" className={`${buttonVariants({ variant: "primary", size: "lg" })} mt-8`}>
              Start free <ArrowRight aria-hidden />
            </Link>
          </div>
          <dl className="border-t border-border-strong lg:col-span-6 lg:col-start-7">
            {KEY_FACTS.map(([term, detail]) => (
              <div key={term} className="flex flex-col gap-1 border-b border-border py-4 sm:flex-row sm:items-baseline sm:justify-between sm:gap-6">
                <dt className="font-mono text-micro uppercase text-muted-foreground">{term}</dt>
                <dd className="text-body text-foreground">{detail}</dd>
              </div>
            ))}
          </dl>
        </section>
      </main>

      <footer className="border-t border-border">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 py-8 md:px-8">
          <Logo />
          <p className="text-caption text-muted-foreground">Reports are AI-assisted drafts. Verify before you submit.</p>
        </div>
      </footer>
    </div>
  );
}
