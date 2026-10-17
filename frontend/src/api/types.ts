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

/** Mirrors the backend's problem+json contract; `code` is what the UI branches on. */
export type ApiProblem = {
  code?: string
  title?: string
  detail?: string
  correlationId?: string
}
