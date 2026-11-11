import type { Metadata } from "next";

import { PlanReview } from "./plan-review";

export const metadata: Metadata = { title: "Plan review" };

export default function PlanPage() {
  return <PlanReview />;
}
