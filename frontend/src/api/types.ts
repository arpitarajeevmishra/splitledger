export type SelfProfile = {
  id: string
  email: string
  name: string
  upiId: string | null
}

export type AuthResponse = {
  accessToken: string
  expiresInSeconds: number
  user: SelfProfile
}

export type Role = 'ADMIN' | 'MEMBER'

export type GroupSummary = {
  id: string
  name: string
  currency: string
  yourRole: Role
  memberCount: number
  createdAt: string
}

/**
 * A member as the rest of the group sees them. No email and no payment address: the
 * backend's DTO does not carry them, and the UI must not invent a place to put them.
 */
export type GroupMember = {
  userId: string
  name: string
  role: Role
  joinedAt: string
}

export type GroupDetail = {
  id: string
  name: string
  currency: string
  yourRole: Role
  createdAt: string
  members: GroupMember[]
}

export type InviteStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'REVOKED'

/** As an admin sees it in a listing. Deliberately carries no token. */
export type Invite = {
  id: string
  email: string
  status: InviteStatus
  expiresAt: string
  createdAt: string
}

/**
 * The response to creating an invite, and the only place the link ever appears. It is
 * never cached and never re-fetched, because the server cannot produce it twice.
 */
export type IssuedInvite = {
  id: string
  email: string
  url: string
  expiresAt: string
}

export type InvitePreview = {
  groupName: string
  email: string
  expiresAt: string
}

/** Mirrors the backend's problem+json contract; `code` is what the UI branches on. */
export type ApiProblem = {
  code?: string
  title?: string
  detail?: string
  correlationId?: string
}

export type SplitType = 'EQUAL' | 'EXACT' | 'PERCENT' | 'SHARES'
export type ExpenseStatus = 'ACTIVE' | 'VOIDED'

export type ExpenseSplit = {
  userId: string
  owedPaise: number
  /** What was entered: paise, basis points or a share count. Null for EQUAL. */
  inputValue: number | null
}

export type Expense = {
  id: string
  groupId: string
  description: string
  amountPaise: number
  currency: string
  paidBy: string
  expenseDate: string
  splitType: SplitType
  status: ExpenseStatus
  createdAt: string
  version: number
  splits: ExpenseSplit[]
}

/** A row in the group's expense list; splits come with the detail. */
export type ExpenseSummary = {
  id: string
  description: string
  amountPaise: number
  paidBy: string
  expenseDate: string
  splitType: SplitType
  status: ExpenseStatus
  version: number
}

export type CreateExpense = {
  description: string
  amountPaise: number
  paidBy: string
  expenseDate: string
  splitType: SplitType
  participants: { userId: string; value?: number }[]
}

export type NetBalance = { userId: string; netPaise: number; settled: boolean }
export type Debt = { from: string; to: string; amountPaise: number }
export type Balances = { net: NetBalance[]; pairwise: Debt[] }

export type SuggestedTransfer = { from: string; to: string; amountPaise: number }
export type SimplifiedPlan = { transfers: SuggestedTransfer[]; settled: boolean }

export type ActivityKind =
  'EXPENSE_ADDED' | 'EXPENSE_VOIDED' | 'SETTLEMENT_CONFIRMED' | 'MEMBER_JOINED' | 'MEMBER_LEFT'

export type ActivityEntry = {
  kind: ActivityKind
  subjectId: string
  /** Null for an expense voided before the backend recorded who did it. */
  actorId: string | null
  at: string
  description: string | null
  amountPaise: number | null
  counterpartyId: string | null
  movedMoney: boolean
}

export type PaymentMethod = 'RAZORPAY' | 'SANDBOX' | 'UPI_INTENT' | 'CASH'

export type SettlementStatus =
  'INITIATED' | 'PENDING' | 'CONFIRMED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'

export type Settlement = {
  id: string
  groupId: string
  fromUser: string
  toUser: string
  amountPaise: number
  method: PaymentMethod
  status: SettlementStatus
  gatewayOrderId: string | null
  gatewayPaymentId: string | null
  expiresAt: string | null
  createdAt: string
  confirmedAt: string | null
  version: number
  /** Always true in this product; the flag exists so the UI need not hardcode it. */
  demoOnly: boolean
}

export type CreateSettlement = {
  toUser: string
  amountPaise: number
  method: PaymentMethod
}
