import { BookOpen, CircleCheck, FileText, KeyRound, Trophy } from "lucide-react";
import Link from "next/link";

import { Logo } from "@/components/anveshan/logo";
import { buttonVariants } from "@/components/ui/button";

const OUTPUTS = [
  {
    Icon: FileText,
    title: "A report you can defend",
    body: "Every claim shows its source and an honest label — Supported, Weak evidence, Conflicting or No source found.",
  },
  {
    Icon: BookOpen,
    title: "A knowledge library that grows",
    body: "Concepts from every research become linked notes you can search, edit and revisit before your viva.",
  },
  {
    Icon: Trophy,
    title: "Skills you can prove",
    body: "See what your code and answers actually prove, what's missing for your goal, and what to learn next.",
  },
];

const STEPS = [
  "Ask a question or set a career goal.",
  "Review and edit the research plan.",
  "Watch sources get read and claims get checked.",
  "Get a cited report, notes and a learning roadmap.",
];

/** S-01 Landing (APPFLOW §15): explain Anveshan in 10 seconds, get to sign-in. Static server component. */
export default function LandingPage() {
  return (
    <div className="min-h-screen">
      <header className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 md:px-8">
        <Logo />
        <Link href="/login" className={buttonVariants({ variant: "ghost", size: "sm" })}>
          Sign in
        </Link>
      </header>

      <main>
        <section className="mx-auto grid max-w-6xl items-center gap-12 px-4 py-16 md:px-8 lg:grid-cols-2 lg:py-24">
          <div>
            <h1 className="font-display text-display-lg font-medium tracking-tight md:text-display-2xl">
              Research you can defend. Skills you can prove.
            </h1>
            <p className="mt-6 max-w-xl text-body-lg text-muted-foreground">
              Give Anveshan a question or a goal. It researches it, writes a report where every claim shows its
              source, and tells you plainly when the evidence is weak.
            </p>
            <div className="mt-8 flex flex-wrap items-center gap-3">
              <Link href="/login" className={buttonVariants({ variant: "primary", size: "lg" })}>
                Start free
              </Link>
              <span className="inline-flex items-center gap-2 text-body-sm text-muted-foreground">
                <KeyRound aria-hidden className="size-4" /> Free — bring your own AI key
              </span>
            </div>
          </div>

          <figure
            aria-label="Example: a claim with its source and trust label"
            className="rounded-xl border border-border bg-surface p-6 shadow-e2"
          >
            <p className="text-micro font-medium uppercase tracking-wide text-muted-foreground">Key findings</p>
            <p className="mt-3 text-body-lg">
              Vision transformers matched CNN accuracy on chest X-ray classification when pre-trained on large
              datasets{" "}
              <span className="inline-flex h-5 items-center rounded-xs border border-border bg-surface-2 px-1 font-mono text-micro">
                3
              </span>
            </p>
            <div className="mt-4 flex flex-wrap items-center gap-3">
              <span className="inline-flex h-6 items-center gap-1 rounded-sm bg-trust-supported-bg px-2 text-caption font-semibold text-trust-supported">
                <CircleCheck aria-hidden className="size-3.5" /> Supported
              </span>
              <span className="text-caption text-muted-foreground">2 sources agree · peer-reviewed</span>
            </div>
            <blockquote className="mt-4 rounded-md bg-surface-2 p-4 text-body-sm text-muted-foreground">
              <span className="marker" data-active="true">
                “…ViT-B/16 reached an AUC of 0.92, on par with the ResNet-50 baseline…”
              </span>
            </blockquote>
          </figure>
        </section>

        <section aria-labelledby="outputs" className="border-y border-border bg-surface-2">
          <div className="mx-auto max-w-6xl px-4 py-16 md:px-8">
            <h2 id="outputs" className="font-display text-display-lg font-medium">
              Three things you walk away with
            </h2>
            <ul className="mt-10 grid gap-4 md:grid-cols-3">
              {OUTPUTS.map(({ Icon, title, body }) => (
                <li key={title} className="rounded-lg border border-border bg-surface p-6 shadow-e1">
                  <Icon aria-hidden className="size-6 text-primary" strokeWidth={1.75} />
                  <h3 className="mt-4 text-heading-sm font-semibold">{title}</h3>
                  <p className="mt-2 text-body-sm text-muted-foreground">{body}</p>
                </li>
              ))}
            </ul>
          </div>
        </section>

        <section aria-labelledby="how" className="mx-auto max-w-6xl px-4 py-16 md:px-8">
          <h2 id="how" className="font-display text-display-lg font-medium">
            How it works
          </h2>
          <ol className="mt-10 grid gap-6 md:grid-cols-4">
            {STEPS.map((step, i) => (
              <li key={step} className="border-l-2 border-primary pl-4">
                <span className="font-display text-heading-lg font-semibold text-primary tabular">{i + 1}</span>
                <p className="mt-2 text-body">{step}</p>
              </li>
            ))}
          </ol>
        </section>

        <section aria-labelledby="free" className="mx-auto max-w-6xl px-4 pb-24 md:px-8">
          <div className="rounded-xl border border-border bg-primary-soft p-8">
            <h2 id="free" className="text-heading-lg font-semibold">
              Free, because you bring your own AI key
            </h2>
            <p className="mt-2 max-w-2xl text-body text-muted-foreground">
              Connect a free Gemini or Groq key in about two minutes. Your key is encrypted, used only for your
              requests, and never shown again — only its last four characters.
            </p>
            <Link href="/login" className={`${buttonVariants({ variant: "primary" })} mt-6`}>
              Start free
            </Link>
          </div>
        </section>
      </main>

      <footer className="border-t border-border">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 py-8 text-caption text-muted-foreground md:px-8">
          <span>Anveshan — AI research and skill gap analysis</span>
          <span>Reports are AI-assisted drafts. Verify before you submit.</span>
        </div>
      </footer>
    </div>
  );
}
