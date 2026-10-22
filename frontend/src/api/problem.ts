import { AxiosError } from 'axios'
import type { ApiProblem } from './auth'

/** The backend speaks problem+json; `code` is the contract, so the UI branches on it. */
export function problemOf(error: unknown): ApiProblem | null {
  if (error instanceof AxiosError && error.response?.data) {
    return error.response.data as ApiProblem
  }
  return null
}

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'That email and password do not match an account.',
  EMAIL_ALREADY_REGISTERED: 'An account already exists for that email.',
  REGISTRATION_DISABLED: 'Registration is closed at the moment.',
  RATE_LIMITED: 'Too many attempts. Please wait a moment and try again.',
  VALIDATION_ERROR: 'Please check the details above.',
  REFRESH_TOKEN_INVALID: 'Your session has ended. Please sign in again.',
  ALREADY_GROUP_MEMBER: 'They are already in this group.',
  INVITE_ALREADY_PENDING: 'An invitation to that address was just created. Refresh to see it.',
  // The backend returns one 404 for unknown, revoked, answered and expired on purpose,
  // so this message has to cover all four without guessing which it was.
  INVITE_INVALID: 'This invite link is no longer valid. Ask for a new one.',
  INVITE_EMAIL_MISMATCH: 'This invite was sent to a different email address.',
  MEMBER_HAS_OUTSTANDING_BALANCE: 'Settle up before leaving or removing someone.',
  LAST_ADMIN: 'A group needs at least one admin. Promote someone else first.',
  INVALID_STATE_TRANSITION: 'That invite has already been answered.',
  NOT_FOUND: 'Not found, or you do not have access to it.',
  FORBIDDEN: 'You do not have permission to do that.',
}

/**
 * Codes whose `detail` the server writes for a person to read, and which carry no
 * internals: the cap and the current count, which are the only useful part of the message
 * and are configuration rather than anything secret.
 *
 * A fixed sentence here would have to leave the numbers out, and "you have reached a limit"
 * is not an error message -- SL-906 asks for a clear 422, and clear means the number.
 */
const DETAIL_IS_FOR_READING = new Set(['ACCOUNT_LIMIT_REACHED'])

export function messageFor(
  error: unknown,
  fallback = 'Something went wrong. Please try again.',
): string {
  const problem = problemOf(error)
  // Indexed access is string | undefined under noUncheckedIndexedAccess, so the lookup is
  // bound once rather than indexed twice and asserted.
  const mapped = problem?.code === undefined ? undefined : MESSAGES[problem.code]
  if (mapped !== undefined) {
    return mapped
  }
  if (problem?.code !== undefined && DETAIL_IS_FOR_READING.has(problem.code) && problem.detail) {
    return problem.detail
  }
  // Never surface any other raw server detail: it can carry internals, and the backend
  // already logs the correlation id for anything genuinely unexpected.
  return fallback
}
