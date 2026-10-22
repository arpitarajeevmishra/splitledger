import type { AxiosInstance } from 'axios'
import { api } from './client'
import type {
  ActivityEntry,
  Balances,
  CreateExpense,
  Expense,
  ExpenseSummary,
  SimplifiedPlan,
} from './types'

export function expensesApi(client: AxiosInstance = api) {
  return {
    list: (groupId: string) =>
      client.get<ExpenseSummary[]>(`/groups/${groupId}/expenses`).then((r) => r.data),

    detail: (expenseId: string) =>
      client.get<Expense>(`/expenses/${expenseId}`).then((r) => r.data),

    /**
     * The idempotency key is generated per submission, not per keystroke: a retry after a
     * dropped connection must reuse it, and a genuinely new expense must not.
     */
    create: (groupId: string, expense: CreateExpense, idempotencyKey: string) =>
      client
        .post<Expense>(`/groups/${groupId}/expenses`, expense, {
          headers: { 'Idempotency-Key': idempotencyKey },
        })
        .then((r) => r.data),

    /** If-Match carries the version last read, so a stale edit is refused rather than applied. */
    void: (expenseId: string, version: number) =>
      client
        .delete<Expense>(`/expenses/${expenseId}`, { headers: { 'If-Match': String(version) } })
        .then((r) => r.data),

    replace: (expenseId: string, version: number, expense: CreateExpense) =>
      client
        .put<Expense>(`/expenses/${expenseId}`, expense, {
          headers: { 'If-Match': String(version) },
        })
        .then((r) => r.data),
  }
}

export function balancesApi(client: AxiosInstance = api) {
  return {
    of: (groupId: string) =>
      client.get<Balances>(`/groups/${groupId}/balances`).then((r) => r.data),

    simplified: (groupId: string) =>
      client.get<SimplifiedPlan>(`/groups/${groupId}/balances/simplified`).then((r) => r.data),
  }
}

export function activityApi(client: AxiosInstance = api) {
  return {
    feed: (groupId: string, page = 0, size = 50) =>
      client
        .get<ActivityEntry[]>(`/groups/${groupId}/activity`, { params: { page, size } })
        .then((r) => r.data),
  }
}
