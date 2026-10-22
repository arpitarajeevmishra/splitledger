import type { AxiosInstance } from 'axios'
import { api } from './client'
import type { CreateSettlement, Settlement } from './types'

export function settlementsApi(client: AxiosInstance = api) {
  return {
    list: (groupId: string) =>
      client.get<Settlement[]>(`/groups/${groupId}/settlements`).then((r) => r.data),

    detail: (settlementId: string) =>
      client.get<Settlement>(`/settlements/${settlementId}`).then((r) => r.data),

    create: (groupId: string, settlement: CreateSettlement, idempotencyKey: string) =>
      client
        .post<Settlement>(`/groups/${groupId}/settlements`, settlement, {
          headers: { 'Idempotency-Key': idempotencyKey },
        })
        .then((r) => r.data),

    /** Starts a provider order. Only gateway methods have one. */
    startOrder: (settlementId: string) =>
      client.post<Settlement>(`/settlements/${settlementId}/gateway-order`).then((r) => r.data),

    /** The payer says they have sent it. */
    markPaid: (settlementId: string) =>
      client.post<Settlement>(`/settlements/${settlementId}/mark-paid`).then((r) => r.data),

    /** The receiver says it arrived -- this is what moves the ledger. */
    confirm: (settlementId: string) =>
      client.post<Settlement>(`/settlements/${settlementId}/confirm`).then((r) => r.data),

    reject: (settlementId: string) =>
      client.post<Settlement>(`/settlements/${settlementId}/reject`).then((r) => r.data),

    cancel: (settlementId: string) =>
      client.post<Settlement>(`/settlements/${settlementId}/cancel`).then((r) => r.data),
  }
}
