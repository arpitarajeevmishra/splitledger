import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { activityApi, balancesApi, expensesApi } from '../../api/expenses'
import { groupKeys } from '../groups/useGroups'
import type {
  ActivityEntry,
  Balances,
  CreateExpense,
  Expense,
  ExpenseSummary,
  NetPosition,
  SimplifiedPlan,
} from '../../api/types'

const expenses = expensesApi()
const balances = balancesApi()
const activity = activityApi()

export const expenseKeys = {
  all: ['expenses'] as const,
  list: (groupId: string) => [...expenseKeys.all, 'list', groupId] as const,
  balances: (groupId: string) => [...expenseKeys.all, 'balances', groupId] as const,
  simplified: (groupId: string) => [...expenseKeys.all, 'simplified', groupId] as const,
  myNetPosition: () => [...expenseKeys.all, 'net-position'] as const,
  activity: (groupId: string) => [...expenseKeys.all, 'activity', groupId] as const,
}

export function useExpenses(groupId: string) {
  return useQuery<ExpenseSummary[]>({
    queryKey: expenseKeys.list(groupId),
    queryFn: () => expenses.list(groupId),
  })
}

/**
 * Where you stand across every group.
 *
 * <p>Refetched when the window regains focus, because the figure changes when somebody
 * else records an expense or confirms a payment -- neither of which this tab would hear
 * about. The dashboard is also where people land after doing exactly that in another tab.
 */
export function useMyNetPosition() {
  return useQuery<NetPosition>({
    queryKey: expenseKeys.myNetPosition(),
    queryFn: balances.mine,
    refetchOnWindowFocus: true,
  })
}

export function useBalances(groupId: string) {
  return useQuery<Balances>({
    queryKey: expenseKeys.balances(groupId),
    queryFn: () => balances.of(groupId),
  })
}

export function useSimplifiedPlan(groupId: string) {
  return useQuery<SimplifiedPlan>({
    queryKey: expenseKeys.simplified(groupId),
    queryFn: () => balances.simplified(groupId),
  })
}

export function useActivity(groupId: string) {
  return useQuery<ActivityEntry[]>({
    queryKey: expenseKeys.activity(groupId),
    queryFn: () => activity.feed(groupId),
  })
}

/**
 * Everything an expense touches, refreshed together.
 *
 * <p>Adding or voiding an expense changes the list, both balance views and the feed. A
 * mutation that refreshed only the list would leave the balances on screen contradicting
 * the expense that just changed them, which is precisely the kind of thing that makes
 * people stop believing the numbers.
 */
function invalidateMoney(queryClient: ReturnType<typeof useQueryClient>, groupId: string) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: expenseKeys.list(groupId) }),
    queryClient.invalidateQueries({ queryKey: expenseKeys.balances(groupId) }),
    queryClient.invalidateQueries({ queryKey: expenseKeys.simplified(groupId) }),
    queryClient.invalidateQueries({ queryKey: expenseKeys.activity(groupId) }),
    queryClient.invalidateQueries({ queryKey: groupKeys.detail(groupId) }),
  ])
}

export function useCreateExpense(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation<Expense, unknown, { expense: CreateExpense; idempotencyKey: string }>({
    mutationFn: ({ expense, idempotencyKey }) => expenses.create(groupId, expense, idempotencyKey),
    onSuccess: () => invalidateMoney(queryClient, groupId),
  })
}

export function useVoidExpense(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation<Expense, unknown, { expenseId: string; version: number }>({
    mutationFn: ({ expenseId, version }) => expenses.void(expenseId, version),
    onSuccess: () => invalidateMoney(queryClient, groupId),
  })
}
