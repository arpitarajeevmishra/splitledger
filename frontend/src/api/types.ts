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
