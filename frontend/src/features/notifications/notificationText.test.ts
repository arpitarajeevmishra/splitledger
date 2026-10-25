import { describe, expect, it } from 'vitest'
import type { AppNotification, NotificationType } from '../../api/types'
import { notificationText } from './notificationText'

function notification(
  type: NotificationType,
  message: Partial<AppNotification['message']> = {},
): AppNotification {
  return {
    id: 'n1',
    type,
    readAt: null,
    createdAt: '2026-10-25T12:00:00Z',
    message: {
      groupId: 'g1',
      groupName: 'Flat 402',
      actorId: 'u1',
      actorName: 'Alice Ray',
      description: 'Dinner',
      amountPaise: 90_000,
      ...message,
    },
  }
}

/**
 * What the bell says.
 *
 * Separate from the component because this is wording, and wording is the part that has
 * twice been wrong in this product while every mechanism around it worked (D-126, D-131).
 */
describe('notificationText', () => {
  it('names who added what, and for how much', () => {
    expect(notificationText(notification('EXPENSE_ADDED'))).toBe('Alice Ray added Dinner · ₹900.00')
  })

  it('says removed, not added, for a void', () => {
    // The two differ by one word and mean opposite things to somebody's balance.
    expect(notificationText(notification('EXPENSE_VOIDED'))).toBe(
      'Alice Ray removed Dinner · ₹900.00',
    )
  })

  it('names the person who confirmed a payment', () => {
    expect(
      notificationText(
        notification('SETTLEMENT_CONFIRMED', { description: null, amountPaise: 50_000 }),
      ),
    ).toBe('Alice Ray confirmed a payment of ₹500.00')
  })

  it('does not invent a person when a gateway confirmed it', () => {
    // A gateway confirmation has no actor. "Someone confirmed a payment" would assert that
    // a person did something, which is the one thing known not to have happened.
    expect(
      notificationText(
        notification('SETTLEMENT_CONFIRMED', {
          actorId: null,
          actorName: null,
          description: null,
          amountPaise: 50_000,
        }),
      ),
    ).toBe('A payment of ₹500.00 was confirmed')
  })

  it('falls back to Someone rather than leaving a gap', () => {
    // A sentence that starts with a blank reads as a bug; the notification is still worth
    // reading without the name.
    expect(notificationText(notification('EXPENSE_ADDED', { actorName: null }))).toBe(
      'Someone added Dinner · ₹900.00',
    )
  })

  it('still reads as a sentence without a description', () => {
    expect(notificationText(notification('EXPENSE_ADDED', { description: null }))).toBe(
      'Alice Ray added an expense · ₹900.00',
    )
  })
})
