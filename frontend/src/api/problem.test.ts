import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
import { messageFor } from './problem'

/** An AxiosError shaped like one the client would throw for a problem+json response. */
function problem(status: number, body: unknown): AxiosError {
  const error = new AxiosError('Request failed', 'ERR_BAD_REQUEST')
  error.response = {
    status,
    statusText: '',
    data: body,
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  }
  return error
}

describe('messageFor', () => {
  it('shows a cap message as the server wrote it, numbers and all', () => {
    // The whole point of the 422 is the number. A mapped sentence could not carry it,
    // and the fallback would turn a clear refusal into "something went wrong".
    const message = messageFor(
      problem(422, {
        code: 'ACCOUNT_LIMIT_REACHED',
        detail: 'This demo allows 20 groups per account, and yours has 20.',
      }),
    )

    expect(message).toBe('This demo allows 20 groups per account, and yours has 20.')
  })

  it('falls back when a cap response arrives without a detail', () => {
    expect(messageFor(problem(422, { code: 'ACCOUNT_LIMIT_REACHED' }), 'Could not do that.')).toBe(
      'Could not do that.',
    )
  })

  it('still refuses to show details for every other code', () => {
    // An unmapped code is the case where a detail is most likely to carry internals, so
    // the allowlist has to be an allowlist and not a precedent.
    expect(
      messageFor(
        problem(500, { code: 'INTERNAL_ERROR', detail: 'could not open connection to host db-1' }),
      ),
    ).toBe('Something went wrong. Please try again.')
    expect(
      messageFor(problem(409, { code: 'VERSION_CONFLICT', detail: 'expected version 3, was 4' })),
    ).toBe('Something went wrong. Please try again.')
  })

  it('prefers the mapped sentence over a detail when there is one', () => {
    expect(
      messageFor(problem(403, { code: 'REGISTRATION_DISABLED', detail: 'Registration is closed' })),
    ).toBe('Registration is closed at the moment.')
  })
})
