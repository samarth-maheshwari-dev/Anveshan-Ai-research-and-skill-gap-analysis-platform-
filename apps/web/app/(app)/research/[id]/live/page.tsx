import type { Metadata } from "next";

import { LiveView } from "./live-view";

export const metadata: Metadata = { title: "Live research" };

export default function LivePage() {
  return <LiveView />;
}
