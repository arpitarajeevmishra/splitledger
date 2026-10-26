// k6: what the balance cache is actually worth (SL-705, ADR-015).
//
//   k6 run -e BASE_URL=http://localhost:8080 -e DEMO_PASSWORD=... perf/balances.js
//
// Two scenarios against the same endpoint, separated by whether the cache can help:
//
//   cached    repeated reads of a group nobody is writing to. Every read after the first
//             is a cache hit, so this measures the cache path and the HTTP stack.
//   uncached  a write before each read, which evicts on commit (SL-703), so every read
//             recomputes from the ledger. This measures what the cache is saving.
//
// The comparison is the point. A single number for "balance read latency" means nothing
// without saying which of these it was, and most load tests quietly measure the first and
// report it as though it were the second.
//
// NOTE: this has not been run. The only database this project has is the one the deployed
// demo uses, and a load test writes expenses -- see docs/perf/README for what that needs.
import http from 'k6/http'
import { check, fail } from 'k6'
import { Trend } from 'k6/metrics'

const BASE = __ENV.BASE_URL || 'http://localhost:8080'
const PASSWORD = __ENV.DEMO_PASSWORD
const DEMO_EMAIL = __ENV.DEMO_EMAIL || 'demo@splitledger.invalid'

const cachedRead = new Trend('balances_cached', true)
const uncachedRead = new Trend('balances_uncached', true)

export const options = {
  scenarios: {
    cached: {
      executor: 'constant-vus',
      vus: 10,
      duration: '30s',
      exec: 'readCached',
      tags: { path: 'cached' },
    },
    uncached: {
      executor: 'constant-vus',
      vus: 5,
      duration: '30s',
      startTime: '30s',
      exec: 'readUncached',
      tags: { path: 'uncached' },
    },
  },
  thresholds: {
    // Deliberately generous and deliberately present. A threshold is what makes a load
    // test able to fail; without one this is a script that prints numbers nobody reads.
    balances_cached: ['p(95)<150'],
    balances_uncached: ['p(95)<600'],
    http_req_failed: ['rate<0.01'],
  },
}

export function setup() {
  if (!PASSWORD) {
    fail('DEMO_PASSWORD is required: this signs in as the seeded demo account')
  }
  const login = http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ email: DEMO_EMAIL, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  )
  if (login.status !== 200) {
    fail(`login failed with ${login.status}: run the app with DEMO_SEED_PASSWORD set first`)
  }
  const token = login.json('accessToken')

  const groups = http.get(`${BASE}/api/v1/groups`, { headers: auth(token) })
  const group = groups.json('0.id')
  if (!group) {
    fail('the demo account is in no group; seeding did not finish')
  }
  // Member ids are needed to write an expense, and they come from the group itself rather
  // than being guessed: the seed creates users with random ids.
  const detail = http.get(`${BASE}/api/v1/groups/${group}`, { headers: auth(token) })
  return {
    token,
    group,
    members: detail.json('members').map((member) => member.userId),
  }
}

function auth(token) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

export function readCached(data) {
  const response = http.get(`${BASE}/api/v1/groups/${data.group}/balances`, {
    headers: auth(data.token),
  })
  check(response, { 'balances ok': (r) => r.status === 200 })
  cachedRead.add(response.timings.duration)
}

export function readUncached(data) {
  // The write is what makes the read uncached: it evicts the group's entry after commit.
  // Its own latency is not recorded -- only the read that follows is, because the question
  // is what a balance costs when the cache cannot answer it.
  const written = http.post(
    `${BASE}/api/v1/groups/${data.group}/expenses`,
    JSON.stringify({
      description: 'k6',
      amountPaise: 100,
      paidBy: data.members[0],
      expenseDate: new Date().toISOString().slice(0, 10),
      splitType: 'EQUAL',
      participants: data.members.map((userId) => ({ userId })),
    }),
    { headers: { ...auth(data.token), 'Idempotency-Key': `k6-${__VU}-${__ITER}` } },
  )
  check(written, { 'expense written': (r) => r.status === 201 || r.status === 200 })

  const response = http.get(`${BASE}/api/v1/groups/${data.group}/balances`, {
    headers: auth(data.token),
  })
  check(response, { 'balances ok': (r) => r.status === 200 })
  uncachedRead.add(response.timings.duration)
}
