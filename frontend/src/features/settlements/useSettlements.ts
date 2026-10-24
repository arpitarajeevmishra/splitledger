import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { settlementsApi } from '../../api/settlements'
import { expenseKeys } from '../expenses/useExpenses'
import type { CreateSettlement, Settlement } from '../../api/types'

const settlements = settlementsApi()

export const settlementKeys = {
  all: ['settlements'] as const,
  list: (groupId: string) => [...settlementKeys.all, 'list', groupId] as const,
}

/**
 * A sandbox settlement is confirmed by a background poller, not by anything the browser
 * does, so the list refetches on an interval while one is still in flight. It stops once
 * nothing is pending -- polling a settled group forever would be a request per few
 * seconds for no reason.
 *
 * <p>`refetchIntervalInBackground` because the interval is otherwise paused while the tab
 * is not focused, and this app disables refetch-on-focus globally. Together those meant a
 * payment confirmed while someone looked away stayed "waiting to be confirmed" until they
 * reloaded the page -- the one state in this flow that resolves without them doing
 * anything, and the one they are most likely to look away from.
 */
export function useSettlements(groupId: string) {
  return useQuery<Settlement[]>({
    queryKey: settlementKeys.list(groupId),
    queryFn: () => settlements.list(groupId),
    refetchInterval: (query) => {
      const data = query.state.data
      const waiting = data?.some(
        (settlement) => settlement.status === 'PENDING' || settlement.status === 'INITIATED',
      )
      return waiting === true ? 3_000 : false
    },
    refetchIntervalInBackground: true,
  })
}

function useSettlementAction<T>(groupId: string, action: (input: T) => Promise<Settlement>) {
  const queryClient = useQueryClient()
  return useMutation<Settlement, unknown, T>({
    mutationFn: action,
    // A confirmation posts to the ledger, so the balances, the plan and the feed all
    // change with it. Refreshing only the settlement list would leave the rest of the
    // screen contradicting the row that just changed.
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: settlementKeys.list(groupId) }),
        queryClient.invalidateQueries({ queryKey: expenseKeys.balances(groupId) }),
        queryClient.invalidateQueries({ queryKey: expenseKeys.simplified(groupId) }),
        queryClient.invalidateQueries({ queryKey: expenseKeys.activity(groupId) }),
      ])
    },
  })
}

export function useCreateSettlement(groupId: string) {
  return useSettlementAction<{ settlement: CreateSettlement; idempotencyKey: string }>(
    groupId,
    ({ settlement, idempotencyKey }) => settlements.create(groupId, settlement, idempotencyKey),
  )
}

export function useStartOrder(groupId: string) {
  return useSettlementAction<string>(groupId, (id) => settlements.startOrder(id))
}

/**
 * Hand Checkout's answer to the server, which verifies the signature before believing it.
 */
export function useGatewayCallback(groupId: string) {
  return useSettlementAction<{
    settlementId: string
    callback: { orderId: string; paymentId: string; signature: string }
  }>(groupId, ({ settlementId, callback }) => settlements.gatewayCallback(settlementId, callback))
}

export function useMarkPaid(groupId: string) {
  return useSettlementAction<string>(groupId, (id) => settlements.markPaid(id))
}

export function useConfirmSettlement(groupId: string) {
  return useSettlementAction<string>(groupId, (id) => settlements.confirm(id))
}

export function useRejectSettlement(groupId: string) {
  return useSettlementAction<string>(groupId, (id) => settlements.reject(id))
}

export function useCancelSettlement(groupId: string) {
  return useSettlementAction<string>(groupId, (id) => settlements.cancel(id))
}
