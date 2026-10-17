import type { ReactNode } from 'react'
import { DemoBanner } from '../../components/DemoBanner'

export function AuthLayout({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string
  subtitle: string
  children: ReactNode
  footer: ReactNode
}) {
  return (
    <div className="flex min-h-dvh flex-col bg-surface">
      <DemoBanner />
      <main className="flex flex-1 items-center justify-center px-4 py-10">
        <div className="w-full max-w-[380px]">
          <div className="mb-6">
            <p className="font-mono text-[11px] font-medium tracking-[0.06em] text-accent uppercase">
              Knot
            </p>
            <h1 className="mt-2 text-2xl font-semibold tracking-[-0.015em] text-ink">{title}</h1>
            <p className="mt-1 text-sm text-ink-muted">{subtitle}</p>
          </div>
          <div className="rounded-lg border border-line bg-surface-1 p-4">{children}</div>
          <div className="mt-4 text-center text-sm text-ink-muted">{footer}</div>
        </div>
      </main>
    </div>
  )
}
