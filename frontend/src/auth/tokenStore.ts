/**
 * The access token lives here and nowhere else: a module variable, in memory (ADR-011).
 *
 * Not localStorage, not sessionStorage, not a cookie this code can read. A successful XSS
 * can call any function in the bundle, so holding the token in a variable is not a defence
 * against a running attacker -- but it does mean the token is gone when the tab closes and
 * is never sitting in storage for a *later* or passive read. The refresh token, which is
 * the long-lived credential, is an httpOnly cookie this code cannot touch at all.
 */
let accessToken: string | null = null

export const tokenStore = {
  get(): string | null {
    return accessToken
  },
  set(token: string | null): void {
    accessToken = token
  },
  clear(): void {
    accessToken = null
  },
}
