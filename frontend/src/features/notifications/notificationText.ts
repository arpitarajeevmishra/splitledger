import { formatPaise } from '../../lib/money'
import type { AppNotification } from '../../api/types'

/**
 * The sentence a person reads in the bell.
 *
 * <p>Pure and separate from the component so it can be tested as what it is: wording. The
 * mechanism around it -- polling, marking read -- can be entirely correct while the bell
 * says something that makes no sense, and that failure has already happened twice in this
 * product (D-126, D-131).
 *
 * <p>Falls back to "Someone" rather than rendering a blank where a name should be. A
 * notification with a missing actor is still worth reading; a sentence beginning with a gap
 * reads as a bug.
 */
export function notificationText(notification: AppNotification): string {
  const { actorName, description, amountPaise } = notification.message
  const who = actorName ?? 'Someone'
  const amount = formatPaise(amountPaise)

  switch (notification.type) {
    case 'EXPENSE_ADDED':
      return `${who} added ${description ?? 'an expense'} · ${amount}`
    case 'EXPENSE_VOIDED':
      return `${who} removed ${description ?? 'an expense'} · ${amount}`
    case 'SETTLEMENT_CONFIRMED':
      // No actor means a gateway confirmed it, and there is no person to name. "Someone
      // confirmed" would invent one.
      return actorName === null
        ? `A payment of ${amount} was confirmed`
        : `${who} confirmed a payment of ${amount}`
  }
}
