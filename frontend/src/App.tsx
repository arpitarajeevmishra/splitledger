import { HealthStatus } from './features/health/HealthStatus'

export default function App() {
  return (
    <main className="mx-auto max-w-xl p-8 font-sans">
      <h1 className="text-2xl font-semibold text-slate-900">SplitLedger</h1>
      <p className="mt-1 text-sm text-slate-500">
        Ledger-first expense splitting. Sprint 0 skeleton.
      </p>
      <div className="mt-6 rounded-lg border border-slate-200 p-4">
        <HealthStatus />
      </div>
    </main>
  )
}
