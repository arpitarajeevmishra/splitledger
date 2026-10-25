# Deploying to an Oracle Cloud Always Free instance

The production stack in this directory is one Caddy container in front of the SPA and the API,
with Postgres managed and off the host (ADR-015). That is a single small Linux box, which is
exactly what Oracle Cloud's Always Free tier gives you indefinitely — so this runs at no ongoing
cost without compromising the architecture. Nothing is scaled to zero, which matters here: the
settlement expiry sweeper, the provider payment poller, the idempotency purge and the nightly
reconciliation job are all `@Scheduled` inside the API process. A host that sleeps when idle does
not run them.

The host is disposable. It holds the stack files and a `.env`, and nothing else. Losing it costs a
redeploy.

---

## What the free tier actually allows

Oracle halved the Ampere A1 allowance in mid-2026, without announcing it. As of October 2026 the
Always Free limit is **1,500 OCPU-hours and 9,000 GB-hours per month** across all A1 instances —
2 OCPUs and 12 GB running continuously, not the 4 OCPUs and 24 GB that most guides still quote.
Confirm the current figures on [Oracle's Always Free
page](https://www.oracle.com/cloud/free/#always-free) before you provision; this is the number
Oracle has changed quietly once already.

**Provision 1 OCPU and 6 GB** (`VM.Standard.A1.Flex`). That is half the allowance, leaves room to
spare, and is more than this stack needs — the API takes 75% of available memory as heap
(`../backend/Dockerfile`), and Caddy and nginx are rounding error beside it.

Taking the smaller shape also helps with the other trap. Oracle may **reclaim an Always Free
instance it considers idle**: over a 7-day window, CPU below 20% at the 95th percentile, network
below 20%, and — on A1 shapes — memory below 20%. A 24 GB box running a 1 GB heap looks idle by
that measure almost no matter what it serves. A 6 GB box running the same heap does not.

---

## The database

Postgres is managed and off the host. Three things about wiring it up are easy to get wrong and
unpleasant to diagnose later.

**Use the direct endpoint, not the pooled one.** Neon gives you both; the pooled host is the one
with `-pooler` in it. Drop that and use the plain endpoint:

```
ep-example-1234.region.aws.neon.tech          # use this
ep-example-1234-pooler.region.aws.neon.tech   # not this
```

The pooled endpoint is PgBouncer in transaction mode, which is the right choice for serverless
functions opening a connection per request and the wrong one here. Flyway takes a session-level
advisory lock for the duration of a migration, and session-scoped state does not survive
transaction pooling — so migrations are the first thing to break. The JDBC driver's server-side
prepared statements are the second. HikariCP is already a connection pool, and with
`maximum-pool-size: 10` there is nothing for a second pool in front of it to do.

**Put the database in the same region as the box.** Every query pays the round trip, and the
difference between same-region and a neighbouring region is tens of milliseconds on every single
statement. A project's region is fixed once created, so changing your mind later means a new
project and a migration. Decide before you load any data.

**Mind the compute allowance.** A scale-to-zero database suspends after about five minutes without
queries, and the free monthly allowance is spent per hour the compute is *awake* — roughly 400
hours at the free tier's smallest size, against 730 hours in a month. Keeping it awake around the
clock therefore exhausts the month's allowance in about two weeks, at which point the database
suspends until the next billing cycle.

`application.yml` already takes this seriously on the pool side: `minimum-idle: 0`, a 2-minute
idle timeout, and a comment explicitly refusing to set `keepalive-time` because pinging to hold
the compute awake would spend the allowance on nothing. The scheduler is the hole in that
reasoning. The development defaults poll the payment provider every **5 seconds** and sweep expiry
every **minute** — which holds the compute awake permanently, by a different route.

So the deployed stack overrides all three, spaced past the suspend threshold and aligned to the
same wake window, so one compute start serves all three jobs:

| Variable | Deployed | Development default |
|---|---|---|
| `PAYMENTS_POLL_CRON` | `0 */15 * * * *` | `*/5 * * * * *` |
| `SETTLEMENT_EXPIRY_CRON` | `0 0 * * * *` | `0 * * * * *` |
| `IDEMPOTENCY_PURGE_CRON` | `0 0 3 * * *` | `0 */15 * * * *` |

That lands around 40% duty cycle before user traffic — comfortably inside the allowance. Widen
`PAYMENTS_POLL_CRON` to `0 */30 * * * *` if you want more headroom; the webhook is the primary
path for payment confirmation and the poller is the fallback, so a wider interval costs little.
These are defaults in `docker-compose.prod.yml`, not values you have to remember to set.

---

## Before you start

| You need | Notes |
|---|---|
| An Oracle Cloud account | Card required for identity verification; Always Free resources are not charged |
| A domain name | Caddy gets a certificate over ACME, so the name must resolve to the box. A DuckDNS subdomain works |
| A Neon (or other managed) PostgreSQL database | Already how development works — see `../.env.example` |
| `git`, `ssh`, `scp`, `curl` locally | No Docker needed locally (ADR-012) |

---

## 1. Create the instance

In the Oracle console, **Compute → Instances → Create instance**:

- **Image**: Canonical Ubuntu 24.04 (make sure you pick the **aarch64** build)
- **Shape**: `VM.Standard.A1.Flex`, **1 OCPU, 6 GB** — marked *Always Free eligible*
- **Networking**: assign a **public IPv4 address**
- **SSH keys**: upload your public key

If you get **"Out of host capacity"**, Ampere capacity in that availability domain is exhausted.
This is common and not a mistake on your part — try another availability domain, another region, or
the same request again later. It is the single most likely thing to block you for a while.

Note the public IP when the instance is running.

## 2. Open the VCN to HTTP and HTTPS

The instance's subnet has its own firewall in Oracle's control plane, separate from the one on the
host. Open the subnet's **security list** (or the instance's NSG) and add **stateful ingress** rules:

| Source | Protocol | Destination port |
|---|---|---|
| `0.0.0.0/0` | TCP | 80 |
| `0.0.0.0/0` | TCP | 443 |

Port 80 is not optional. Caddy's ACME HTTP-01 challenge needs it, and it serves the redirect to
HTTPS.

## 3. Point DNS at the box

Create an `A` record for your domain to the instance's public IP, and **wait for it to resolve**:

```bash
dig +short splitledger.example.com
```

Do this before the first deploy. Caddy requests a certificate on first boot; if the name does not
resolve to this host yet, the challenge fails and Let's Encrypt rate-limits repeated failures.

## 4. Provision the host

From your laptop:

```bash
scp scripts/provision-oracle.sh ubuntu@<public-ip>:
```

```bash
ssh ubuntu@<public-ip> 'bash provision-oracle.sh'
```

It installs Docker and the Compose plugin, opens 80 and 443 in the host's iptables (Oracle's Ubuntu
images reject everything but SSH by default — this is the usual reason a correct deploy is
unreachable), persists those rules across reboots, and creates `/opt/splitledger`. It is idempotent;
re-run it any time you want to re-check a box.

## 5. Let the host pull the images

CI publishes to GHCR, and GHCR packages are private by default. Either:

- **Make the two packages public** — on GitHub, *Packages → splitledger-backend → Package settings
  → Change visibility*, and the same for `splitledger-web`. Simplest, and puts no credential on the
  box. The images contain no secrets; everything environment-specific arrives through `.env`.
- **Or log in on the host** with a classic PAT scoped to `read:packages`:

  ```bash
  ssh ubuntu@<public-ip> 'docker login ghcr.io -u <github-username>'
  ```

## 6. Write the server `.env`

```bash
scp deploy/.env.example ubuntu@<public-ip>:/opt/splitledger/.env
```

```bash
ssh ubuntu@<public-ip> 'chmod 600 /opt/splitledger/.env && nano /opt/splitledger/.env'
```

Fill in `OWNER` (your lowercased GitHub username), `DOMAIN`, `ACME_EMAIL`, the three `DB_*`
variables from Neon, and `JWT_SECRET`. Generate the secret rather than inventing one:

```bash
openssl rand -base64 48
```

`deploy/.env.example` documents every variable. `REGISTRATION_ENABLED` and
`PAYMENTS_UPI_INTENT_ENABLED` stay `false` — they are kill switches, and both default closed.

The deploy refuses to run if this file is missing or is not mode `600`.

## 7. Deploy

```bash
./scripts/deploy.sh ubuntu@<public-ip>
```

That ships `docker-compose.prod.yml` and the `Caddyfile`, pulls the images CI built for the commit
at your `HEAD`, restarts the stack, waits for every container to report healthy, and then checks
`https://<domain>/api/v1/health-check` from outside — which is also what proves DNS, the VCN rules
and TLS, none of which a container can see.

First boot takes a minute or so: Caddy is talking to Let's Encrypt. The script retries.

---

## arm64 images

CI publishes `linux/amd64` and `linux/arm64` manifests for both images. Both Dockerfiles compile on
`$BUILDPLATFORM`, so the jar and the SPA bundle are built natively on the amd64 runner and only the
thin runtime layers are per-architecture — an emulated Maven build would spend twenty minutes
producing byte-identical output.

**Commits from before this change are amd64-only.** Deploying one to the Ampere box fails at
`docker compose pull` with "no matching manifest for linux/arm64". The oldest commit you can deploy
to this host is the first one on `main` that carries the multi-arch build.

---

## Operating it

**Deploy a specific commit, and roll back the same way:**

```bash
./scripts/deploy.sh ubuntu@<public-ip> <commit-sha>
```

Images are tagged by commit SHA and the host holds no state, so a rollback is a restart on a cached
image rather than a rebuild. This is why `deploy.sh` prunes only unreferenced images and never runs
`prune -a`.

A successful deploy writes that SHA into `TAG` in the host's `.env`. Two things follow: the file is
always an accurate record of what is running, and any later `docker compose up` on the host brings
back *that* image rather than whatever `:latest` has since become. A deploy that fails to come up
healthy writes nothing, so the previous working SHA stays put — which is the one a restart should
return to.

**Logs:**

```bash
ssh ubuntu@<public-ip> 'cd /opt/splitledger && docker compose -f docker-compose.prod.yml logs -f --tail=100 api'
```

**Flip a kill switch** — edit `/opt/splitledger/.env` and restart the API. These toggle with a
restart, not a deploy (SL-906), and the restart reuses the deployed image because `TAG` in that
same file pins it:

```bash
ssh ubuntu@<public-ip> 'cd /opt/splitledger && docker compose -f docker-compose.prod.yml up -d api'
```

**Rebuild the box from scratch:** terminate the instance and start again from step 1. Postgres is
elsewhere, so the only thing you re-enter is the `.env`.

---

## When it does not work

| Symptom | Cause |
|---|---|
| Connection hangs, no response at all | Host iptables, or the VCN ingress rules. Re-run `provision-oracle.sh`, then re-check step 2 |
| `no matching manifest for linux/arm64` | Deploying a commit built before multi-arch CI. Deploy a newer SHA |
| `denied` / `unauthorized` on pull | GHCR package is private and the host is not logged in. Step 5 |
| Caddy logs show ACME challenge failures | DNS does not resolve to this instance yet, or port 80 is closed |
| API container unhealthy, Caddy fine | Database. Check `DB_URL`, `DB_USER`, `DB_PASSWORD`; Flyway connects on startup and fails loudly |
| Instance disappeared | Oracle reclaimed it as idle. See the sizing note above, and re-provision |
| `permission denied` on `docker` right after provisioning | The docker group needs a fresh login. `exit`, then ssh back in |

## Observability (SL-704)

`/actuator/prometheus` is exposed on the API and carries, besides Spring Boot's own HTTP
and JVM metrics, three series this application adds:

| series | what it means |
|---|---|
| `splitledger_ledger_reconciliation_drift_paise` | every posting ever written, summed. **Must be zero** (ADR-003) |
| `splitledger_outbox_lag_seconds` | age of the oldest unpublished event; zero when nothing is waiting |
| `splitledger_webhook_deliveries_total{provider,outcome}` | webhook deliveries, including `DUPLICATE` and `REJECTED` |

`observability/alerts.yml` holds the Prometheus rules and `observability/dashboard.json` a
Grafana dashboard. **Nothing scrapes either yet** — the deployed stack is the API, the
static site and Caddy, with no Prometheus (that is SL-907). They are committed now because
a rule written beside the metric matches what the metric means, while one written months
later from a dashboard is a guess.

**The endpoint is not reachable from the internet, and that is deliberate.** The Caddyfile
routes `/api/*` and `/webhooks/*` to the API and everything else to the static site, so
`https://<domain>/actuator/prometheus` returns the SPA's `index.html` with a `200` — which
looks like success and is not. Checked, because a `200` from `curl` was exactly convincing
enough to get this wrong in the first draft of this file.

So a scraper has to reach it from inside the compose network, as `http://api:8080/actuator/prometheus`.
Running Prometheus as another service in `docker-compose.prod.yml` is the straightforward
way and needs no new routing. Exposing it through Caddy instead would publish operational
detail — every endpoint's latency, the state of the outbox, the size of the ledger — to
anybody who asks, so it would need authentication in front of it first.

Then load `alerts.yml` as a rule file and import `dashboard.json` into Grafana.

Two things worth knowing before trusting the alerts:

- `LedgerReconciliationDrift` fires on a non-zero value, so it is silent when nothing is
  being scraped at all. `LedgerDriftUnmeasured` covers that, by alerting on the absence of
  the series.
- The gauges are refreshed on a schedule (`METRICS_REFRESH_MS`, default 60s) and held, not
  queried during a scrape. A gauge callback runs on whichever thread serves the scrape,
  with no transaction and no timeout, so a slow database there would hang the scrape —
  monitoring taking down the thing it monitors.
