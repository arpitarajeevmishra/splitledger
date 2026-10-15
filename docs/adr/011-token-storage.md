# ADR-011: Access token in memory; refresh token in an `httpOnly` cookie

**Status:** Accepted
**Date:** 2026-10-15
**Supersedes / relates to:** —

## Context

The SPA must survive a page reload without forcing a re-login. `localStorage` is readable by any
successful XSS, so a 7-day refresh token kept there is a 7-day account compromise. Cookies avoid
that but raise CSRF. This single choice drives CORS, the Axios interceptor, logout, and the Spring
Security configuration, so leaving it open — as the first draft of the LLD did — blocks the whole
identity sprint.

## Decision

- **Access token:** 15-minute TTL, held **in memory only**. Never `localStorage`, never a cookie.
- **Refresh token:** 7-day TTL, set by the backend as
  `httpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/refresh`. Stored server-side as a SHA-256
  hash, **rotated on every use**; presenting a previously-used token revokes the entire chain.
- The SPA and API are **same-origin** behind the reverse proxy, which is what makes
  `SameSite=Strict` viable and removes the CSRF question for every other endpoint. The refresh
  endpoint additionally rejects requests whose `Origin` is not the web origin.
- No access-token denylist. Logout revokes the refresh chain; a stolen access token therefore lives
  at most 15 minutes.

## Alternatives considered

- **Both tokens in `localStorage`.** Pros: trivial to implement, works across origins. Cons: one
  XSS exfiltrates a long-lived credential. Rejected.
- **Both in memory.** Pros: no cookie, no CSRF question at all. Cons: every page reload is a
  logout, which is unacceptable for an app people open to check a balance.
- **Refresh cookie with `SameSite=None`.** Would require CSRF tokens across the whole API for no
  benefit, given that same-origin deployment is available.

## Consequences

- **Positive:** XSS cannot read the long-lived credential. A reload becomes a silent refresh. No
  CSRF machinery is needed anywhere.
- **Negative:** this **requires** same-origin deployment, which makes the relative `/api/v1` base
  URL load-bearing rather than a convenience — a separate frontend origin would invalidate the
  decision. Refresh calls need `withCredentials`. A 15-minute validity window after logout is
  accepted and documented.
- **Follow-up:** tests must cover refresh rotation, reuse-revokes-chain, and that exactly one
  refresh-and-retry happens on a 401 (no retry loops).
