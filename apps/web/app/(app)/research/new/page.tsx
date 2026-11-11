import type { Metadata } from "next";

import { NewResearchForm } from "./new-research-form";

export const metadata: Metadata = { title: "New research" };

export default function NewResearchPage() {
  return <NewResearchForm />;
}
