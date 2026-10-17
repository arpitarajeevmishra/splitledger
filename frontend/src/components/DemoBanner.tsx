/**
 * ADR-013 requires this on every page: a payment that looks successful while no money
 * moves is worse than no payment feature at all. SL-903 does the full treatment (D1-D5);
 * this is the non-dismissible strip that must never be absent in the meantime.
 */
export function DemoBanner() {
  return (
    <div className="border-b border-line bg-surface-2 px-4 py-2 text-center">
      <p className="font-mono text-[11px] font-medium tracking-[0.06em] text-ink-muted uppercase">
        Demo — Razorpay test mode. No real money moves. Data may be reset.
      </p>
    </div>
  )
}
