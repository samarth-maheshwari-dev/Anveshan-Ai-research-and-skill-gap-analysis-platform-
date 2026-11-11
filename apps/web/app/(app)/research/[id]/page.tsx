import type { Metadata } from "next";

import { RunRedirect } from "./run-redirect";

export const metadata: Metadata = { title: "Research" };

export default function RunPage() {
  return <RunRedirect />;
}
