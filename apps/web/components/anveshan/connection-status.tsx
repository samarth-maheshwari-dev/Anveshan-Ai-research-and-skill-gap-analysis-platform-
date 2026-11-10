import { CircleCheck, CircleHelp, CircleOff, TriangleAlert } from "lucide-react";

import { Badge } from "@/components/ui/card";

type Status = "UNVERIFIED" | "VERIFIED" | "FAILING" | "DISABLED";

const LOOK: Record<Status, { word: string; className: string; Icon: typeof CircleCheck }> = {
  VERIFIED: { word: "Verified", className: "bg-trust-supported-bg text-trust-supported", Icon: CircleCheck },
  FAILING: { word: "Failing", className: "bg-danger-soft text-danger", Icon: TriangleAlert },
  UNVERIFIED: { word: "Untested", className: "bg-trust-none-bg text-trust-none", Icon: CircleHelp },
  DISABLED: { word: "Disabled", className: "bg-surface-2 text-muted-foreground", Icon: CircleOff },
};

/** Connection status: always icon + word, color is the third signal (DESIGN §13.1). */
export function ConnectionStatus({ status }: { status: Status | undefined }) {
  const look = LOOK[status ?? "UNVERIFIED"];
  return (
    <Badge className={look.className}>
      <look.Icon aria-hidden strokeWidth={1.75} />
      {look.word}
    </Badge>
  );
}
