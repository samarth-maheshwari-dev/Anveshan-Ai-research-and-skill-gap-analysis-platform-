/**
 * Stacked source documents linked by evidence nodes — the sign-in and landing illustration from the owner's
 * reference design. Pure SVG drawn with currentColor and token classes (no hex, no image files).
 */
export function ResearchIllustration({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 360 260" aria-hidden className={className} fill="none">
      {/* links between evidence */}
      <g className="text-border-strong" stroke="currentColor" strokeWidth="1" strokeDasharray="3 5">
        <path d="M40 200 L120 150" />
        <path d="M120 150 L210 110" />
        <path d="M210 110 L300 70" />
        <path d="M210 110 L290 190" />
        <path d="M120 150 L150 230" />
      </g>
      {/* three tilted documents, back to front */}
      {[0, 1, 2].map((i) => (
        <g key={i} transform={`translate(${110 + i * 34} ${40 + i * 22}) skewY(-12)`}>
          <rect
            width="120"
            height="150"
            rx="10"
            className={i === 2 ? "fill-surface-2 text-border-strong" : "fill-surface text-border"}
            stroke="currentColor"
          />
          <g className="text-subtle-foreground" fill="currentColor" opacity={i === 2 ? 0.9 : 0.4}>
            <rect x="16" y="22" width="60" height="5" rx="2.5" />
            <rect x="16" y="36" width="86" height="4" rx="2" />
            <rect x="16" y="46" width="72" height="4" rx="2" />
            <rect x="16" y="56" width="80" height="4" rx="2" />
          </g>
          {i === 2 && (
            <rect x="16" y="76" width="70" height="10" rx="3" className="fill-marker" />
          )}
        </g>
      ))}
      {/* evidence nodes: the 10 % accent */}
      <g className="text-primary" fill="currentColor">
        <circle cx="40" cy="200" r="5" />
        <circle cx="120" cy="150" r="4" />
        <circle cx="300" cy="70" r="5" />
        <circle cx="290" cy="190" r="4" />
        <circle cx="150" cy="230" r="3" />
      </g>
      <g className="text-primary" stroke="currentColor" opacity="0.35">
        <circle cx="40" cy="200" r="10" />
        <circle cx="300" cy="70" r="10" />
      </g>
    </svg>
  );
}
