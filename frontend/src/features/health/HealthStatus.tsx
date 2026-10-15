import { useQuery } from '@tanstack/react-query'
import { api } from '../../api/client'

type HealthResponse = { status: string }

export function HealthStatus() {
  const { data, isPending, isError, error } = useQuery({
    queryKey: ['health'],
    queryFn: async () => (await api.get<HealthResponse>('/health-check')).data,
    retry: false,
  })

  if (isPending) return <p className="text-slate-500">Contacting the API…</p>
  if (isError)
    return (
      <p className="text-red-600">
        API unreachable — start the backend on :8080. ({error.message})
      </p>
    )
  return <p className="text-emerald-700">API reports: {data.status}</p>
}
