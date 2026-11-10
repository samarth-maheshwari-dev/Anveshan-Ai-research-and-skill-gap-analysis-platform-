import type { Metadata } from "next";
import { Suspense } from "react";

import { ConnectAiWizard } from "./connect-ai-wizard";

export const metadata: Metadata = { title: "Connect your AI" };

export default function ConnectAiPage() {
  return (
    <Suspense>
      <ConnectAiWizard />
    </Suspense>
  );
}
