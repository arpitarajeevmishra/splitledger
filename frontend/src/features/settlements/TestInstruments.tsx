import { useState } from 'react'

/**
 * Razorpay's published test card details, rendered next to the Pay button (D4, SL-605).
 *
 * `RUNNING_LIVE.md` is blunt about why this exists: "D4 is the difference between a demo
 * someone completes and a demo someone abandons." Nobody memorises test card numbers.
 * Without this a visitor reaches the gateway, has nothing to type, and leaves — so the
 * hint is part of the feature, not decoration on it.
 *
 * The values are copied from Razorpay's test-card documentation. They are *their* numbers
 * and can change, which is why the link is here too: if a card stops working, the link is
 * where the current one lives.
 */

const SUCCESS_CARD = '4100 2800 0000 1007'
const FAILURE_CARD = '4100 2800 0008 0001'
const DOCS = 'https://razorpay.com/docs/payments/payments/test-card-details/'

function Copyable({ label, value, hint }: { label: string; value: string; hint?: string }) {
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(value.replace(/\s/g, ''))
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard access is denied in plenty of browsers and contexts. The number is on
      // screen either way, so a failed copy is a non-event -- what it must not be is an
      // unhandled rejection in the middle of someone paying.
    }
  }

  return (
    <div className="flex items-baseline justify-between gap-3">
      <span className="text-[11px] text-ink-muted">{label}</span>
      <span className="flex items-baseline gap-2">
        <code className="font-mono text-[12px] text-ink">{value}</code>
        {hint !== undefined && <span className="text-[10px] text-ink-muted">{hint}</span>}
        <button
          type="button"
          onClick={copy}
          className="rounded border border-line px-1.5 py-0.5 font-mono text-[10px] text-ink-muted hover:border-info hover:text-info"
          aria-label={`Copy ${label}`}
        >
          {copied ? 'copied' : 'copy'}
        </button>
      </span>
    </div>
  )
}

export function TestInstruments() {
  return (
    <div className="flex flex-col gap-2 rounded border border-info/30 bg-info/5 p-3">
      <p className="text-[11px] font-medium tracking-[0.01em] text-info">
        Test mode — use one of Razorpay's test cards
      </p>
      <Copyable label="Card that succeeds" value={SUCCESS_CARD} />
      <Copyable label="Card that fails" value={FAILURE_CARD} hint="insufficient funds" />
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-[11px] text-ink-muted">Expiry and CVV</span>
        <span className="font-mono text-[12px] text-ink">any future date, any 3 digits</span>
      </div>
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-[11px] text-ink-muted">OTP on the mock bank page</span>
        <span className="font-mono text-[12px] text-ink">any 4–10 digits</span>
      </div>
      <p className="text-[10px] leading-relaxed text-ink-muted">
        These are Razorpay's numbers and can change.{' '}
        <a
          href={DOCS}
          target="_blank"
          rel="noreferrer noopener"
          className="underline hover:text-info"
        >
          Their current list
        </a>{' '}
        is the authority. No real card will work here, and nothing is charged.
      </p>
    </div>
  )
}
